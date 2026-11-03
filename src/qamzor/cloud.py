"""Minimal secure WebDAV backup transport with verified atomic downloads."""
from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import re
import ssl
import tempfile
from pathlib import Path
from urllib.parse import quote, urlsplit, unquote

from .format import HEADER, MAGIC, HEADER_SIZE, MAJOR_VERSION, MINOR_VERSION, SUPPORTED_FLAGS, MAX_MANIFEST_SIZE, V13_MANIFEST_PREFIX, QamzorError, FormatError

_IO_SIZE = 1024 * 1024
_MAX_CLOUD_OBJECT_BYTES = 1 << 40  # 1 TiB hard safety ceiling


def _json_no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_symlink_components(path: Path) -> None:
    current = path
    while True:
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            metadata = None
        if metadata is not None and os.path.islink(current):
            raise QamzorError(f"Symbolic-link path component is not allowed: {current}")
        if current.parent == current:
            return
        current = current.parent


def _digest(path: Path) -> tuple[str, int]:
    value = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(_IO_SIZE)
            if not block:
                break
            value.update(block)
            size += len(block)
    return value.hexdigest(), size


class WebDavBackup:
    """Push/pull QZRA files to a WebDAV collection over HTTPS.

    Plain HTTP is permitted only for localhost/loopback so tests and local DAV
    servers can be used. Credentials are supplied at runtime and never saved.
    This adapter intentionally handles a named object and its checksum sidecar;
    it does not claim to support every cloud vendor's proprietary API.
    """
    def __init__(self, collection_url: str, username: str, password: str, *, timeout: float = 30.0):
        if not isinstance(collection_url, str):
            raise QamzorError("WebDAV collection URL must be text")
        parsed = urlsplit(collection_url)
        if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.query or parsed.fragment:
            raise QamzorError("WebDAV URL must be an HTTPS collection URL without query or fragment")
        if parsed.username is not None or parsed.password is not None:
            raise QamzorError("Do not embed WebDAV credentials in the URL")
        decoded_path = unquote(parsed.path)
        if "\\" in decoded_path or any(component in {".", ".."} for component in decoded_path.split("/")):
            raise QamzorError("WebDAV collection URL contains an unsafe path component")
        host = parsed.hostname.lower()
        loopback = host in {"localhost", "127.0.0.1", "::1"}
        if parsed.scheme != "https" and not loopback:
            raise QamzorError("WebDAV credentials require HTTPS; plain HTTP is allowed only on loopback")
        if not isinstance(username, str) or not username or not isinstance(password, str) or not password:
            raise QamzorError("WebDAV username and password must be provided")
        if not isinstance(timeout, (int, float)) or timeout <= 0 or timeout > 300:
            raise QamzorError("WebDAV timeout must be between 0 and 300 seconds")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port
        self.prefix = parsed.path.rstrip("/")
        self.username = username
        self.timeout = float(timeout)
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self.authorization = f"Basic {token}"

    @staticmethod
    def _validate_name(name: str) -> str:
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,186}", name):
            raise QamzorError("Remote object name must be a portable filename of at most 187 characters (including room for its QAMZOR metadata sidecar)")
        if name in {".", ".."}:
            raise QamzorError("Invalid remote object name")
        return name

    def _connection(self):
        if self.scheme == "https":
            return http.client.HTTPSConnection(self.host, self.port or 443, timeout=self.timeout, context=ssl.create_default_context())
        return http.client.HTTPConnection(self.host, self.port or 80, timeout=self.timeout)

    def _target(self, name: str) -> str:
        return f"{self.prefix}/{quote(self._validate_name(name), safe='')}"

    def _put_stream(self, name: str, stream, size: int, content_type: str) -> str:
        conn = self._connection()
        try:
            conn.putrequest("PUT", self._target(name))
            conn.putheader("Host", f"{self.host}:{self.port}" if self.port else self.host)
            conn.putheader("Authorization", self.authorization)
            conn.putheader("Content-Type", content_type)
            conn.putheader("Content-Length", str(size))
            conn.putheader("User-Agent", "QAMZOR-WebDAV/1")
            conn.endheaders()
            sent = 0
            digest = hashlib.sha256()
            while sent < size:
                block = stream.read(min(_IO_SIZE, size - sent))
                if not block:
                    raise QamzorError("Local upload source changed or was truncated")
                conn.send(block)
                digest.update(block)
                sent += len(block)
            response = conn.getresponse()
            response.read(4096)
            if response.status not in {200, 201, 202, 204}:
                raise QamzorError(f"WebDAV upload failed with HTTP {response.status}")
            return digest.hexdigest()
        finally:
            conn.close()

    def _get_stream(self, name: str, target_stream, *, max_bytes: int | None = None) -> int:
        conn = self._connection()
        try:
            conn.putrequest("GET", self._target(name))
            conn.putheader("Host", f"{self.host}:{self.port}" if self.port else self.host)
            conn.putheader("Authorization", self.authorization)
            conn.putheader("User-Agent", "QAMZOR-WebDAV/1")
            conn.endheaders()
            response = conn.getresponse()
            if response.status != 200:
                response.read(4096)
                raise QamzorError(f"WebDAV download failed with HTTP {response.status}")
            length = response.getheader("Content-Length")
            if length is not None and (not length.isdigit() or (max_bytes is not None and int(length) > max_bytes)):
                raise QamzorError("Remote object exceeds the permitted size")
            total = 0
            while True:
                block = response.read(_IO_SIZE)
                if not block:
                    break
                total += len(block)
                if max_bytes is not None and total > max_bytes:
                    raise QamzorError("Remote object exceeds the permitted size")
                target_stream.write(block)
            return total
        finally:
            conn.close()

    def push(self, archive_path, *, remote_name: str | None = None) -> dict:
        original_source = Path(archive_path).expanduser()
        if original_source.is_symlink():
            raise QamzorError("Cloud backup source cannot be a symbolic link")
        source = original_source.resolve(strict=True)
        if not source.is_file():
            raise QamzorError("Cloud backup source must be a regular file")
        size = source.stat().st_size
        if size < HEADER_SIZE or size > _MAX_CLOUD_OBJECT_BYTES:
            raise FormatError("Cloud backup source size is outside the supported QZRA cloud limit")
        with source.open("rb") as stream:
            raw_header = stream.read(HEADER_SIZE)
        try:
            magic, major, minor, flags, manifest_len, entry_count, reserved = HEADER.unpack(raw_header)
        except Exception as exc:
            raise FormatError("Cloud backup source has an invalid QZRA header") from exc
        max_manifest = MAX_MANIFEST_SIZE + (V13_MANIFEST_PREFIX.size + 16 if minor == 3 else 0)
        if magic != MAGIC or major != MAJOR_VERSION or minor > MINOR_VERSION or flags & ~SUPPORTED_FLAGS or reserved != 0 or manifest_len > max_manifest or HEADER_SIZE + manifest_len > size:
            raise FormatError("Cloud backup source has an unsupported or malformed QZRA header")
        remote_name = self._validate_name(remote_name or source.name)
        digest, size = _digest(source)
        before = source.stat()
        with source.open("rb") as stream:
            upload_digest = self._put_stream(remote_name, stream, size, "application/octet-stream")
        after = source.stat()
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino) or upload_digest != digest:
            raise QamzorError("Local archive changed during cloud upload; checksum metadata was not published")
        metadata = {
            "format": "QAMZOR-CLOUD-OBJECT", "version": 1,
            "name": remote_name, "size": size, "sha256": digest,
        }
        encoded = (json.dumps(metadata, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        import io
        self._put_stream(remote_name + ".qamzor.json", io.BytesIO(encoded), len(encoded), "application/json")
        return {"remote_name": remote_name, "bytes": size, "sha256": digest, "uploaded_digest_matches_source": True}

    def pull(self, remote_name: str, output_path, *, overwrite: bool = False) -> dict:
        remote_name = self._validate_name(remote_name)
        output = Path(output_path).expanduser().absolute()
        if output.exists() and not overwrite:
            raise QamzorError(f"Destination already exists: {output}")
        conn = self._connection()
        try:
            conn.putrequest("GET", self._target(remote_name + ".qamzor.json"))
            conn.putheader("Host", f"{self.host}:{self.port}" if self.port else self.host)
            conn.putheader("Authorization", self.authorization)
            conn.endheaders()
            response = conn.getresponse()
            if response.status != 200:
                response.read(4096)
                raise QamzorError(f"WebDAV metadata download failed with HTTP {response.status}")
            metadata_bytes = response.read(64 * 1024 + 1)
            if len(metadata_bytes) > 64 * 1024:
                raise FormatError("Cloud metadata exceeds its size limit")
        finally:
            conn.close()
        try:
            metadata = json.loads(metadata_bytes.decode("utf-8"), object_pairs_hook=_json_no_duplicate_keys)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            raise FormatError("Cloud metadata is invalid or ambiguous JSON") from exc
        if not isinstance(metadata, dict) or metadata.get("format") != "QAMZOR-CLOUD-OBJECT" or type(metadata.get("version")) is not int or metadata.get("version") != 1:
            raise FormatError("Unsupported cloud metadata format")
        if metadata.get("name") != remote_name or not isinstance(metadata.get("size"), int) or isinstance(metadata.get("size"), bool) or not HEADER_SIZE <= metadata["size"] <= _MAX_CLOUD_OBJECT_BYTES:
            raise FormatError("Cloud metadata fields are invalid or exceed the 1 TiB safety ceiling")
        if not isinstance(metadata.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", metadata["sha256"]):
            raise FormatError("Cloud metadata digest is invalid")
        _reject_symlink_components(output.parent)
        output.parent.mkdir(parents=True, exist_ok=True)
        fd, temp_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".download", dir=output.parent)
        digest = hashlib.sha256()
        total = 0
        try:
            with os.fdopen(fd, "wb") as target:
                class HashingWriter:
                    def write(self, data):
                        nonlocal total
                        digest.update(data)
                        total += len(data)
                        return target.write(data)
                self._get_stream(remote_name, HashingWriter(), max_bytes=metadata["size"])
                target.flush()
                os.fsync(target.fileno())
            if total != metadata["size"] or digest.hexdigest() != metadata["sha256"]:
                raise FormatError("Downloaded cloud object failed size or SHA-256 verification")
            with open(temp_name, "rb") as check:
                if check.read(len(MAGIC)) != MAGIC:
                    raise FormatError("Downloaded object is not a QZRA archive")
            if overwrite:
                os.replace(temp_name, output)
            else:
                os.link(temp_name, output)
                os.unlink(temp_name)
            return {"archive": str(output), "bytes": total, "sha256": digest.hexdigest(), "valid": True}
        finally:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
