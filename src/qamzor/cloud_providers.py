"""Verified QZRA cloud transports for Google Drive, Dropbox, OneDrive and S3.

Adapters use official HTTPS REST endpoints. OAuth access tokens/secret keys are
provided by the caller at runtime and are never written to disk. Uploads publish
an integrity sidecar only after the archive upload succeeds; downloads require
that sidecar and verify both byte length and SHA-256 before atomically replacing
or creating the destination.
"""
from __future__ import annotations

import hashlib
import hmac
import http.client
import io
import json
import os
import re
import ssl
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlsplit

from .format import (
    HEADER, HEADER_SIZE, MAGIC, MAJOR_VERSION, MINOR_VERSION,
    SUPPORTED_FLAGS, MAX_MANIFEST_SIZE, V13_MANIFEST_PREFIX,
    FormatError, QamzorError,
)

_IO = 1024 * 1024
_MAX_OBJECT = 1 << 40
_S3_EMPTY_SHA = hashlib.sha256(b"").hexdigest()


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def _decode_json(raw: bytes, label: str) -> dict:
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FormatError(f"{label} is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise FormatError(f"{label} must be a JSON object")
    return value


def _digest(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(_IO)
            if not block:
                break
            digest.update(block)
            size += len(block)
    return digest.hexdigest(), size


def _validate_source(archive_path) -> tuple[Path, int, str, os.stat_result]:
    original = Path(archive_path).expanduser()
    if original.is_symlink():
        raise QamzorError("Cloud backup source cannot be a symbolic link")
    source = original.resolve(strict=True)
    if not source.is_file():
        raise QamzorError("Cloud backup source must be a regular file")
    stat_before = source.stat()
    size = stat_before.st_size
    if size < HEADER_SIZE or size > _MAX_OBJECT:
        raise FormatError("Cloud backup source size is outside the supported QZRA cloud limit")
    with source.open("rb") as stream:
        raw = stream.read(HEADER_SIZE)
    try:
        magic, major, minor, flags, manifest_len, entry_count, reserved = HEADER.unpack(raw)
    except Exception as exc:
        raise FormatError("Cloud backup source has an invalid QZRA header") from exc
    max_manifest = MAX_MANIFEST_SIZE + (V13_MANIFEST_PREFIX.size + 16 if minor == 3 else 0)
    if (magic != MAGIC or major != MAJOR_VERSION or minor > MINOR_VERSION or
            flags & ~SUPPORTED_FLAGS or reserved != 0 or manifest_len > max_manifest or
            HEADER_SIZE + manifest_len > size):
        raise FormatError("Cloud backup source has an unsupported or malformed QZRA header")
    digest, actual_size = _digest(source)
    if actual_size != size:
        raise QamzorError("Local archive changed while its checksum was calculated")
    return source, size, digest, stat_before


def _safe_remote_name(name: str) -> str:
    if not isinstance(name, str) or not name or name in {".", ".."}:
        raise QamzorError("Remote archive name is required")
    if len(name.encode("utf-8")) > 900 or any(ord(ch) < 32 for ch in name):
        raise QamzorError("Remote archive name is too long or contains control characters")
    if "/" in name or "\\" in name:
        raise QamzorError("Remote archive name must be a single filename")
    if not name.lower().endswith(".qzra"):
        raise QamzorError("Remote archive name must end with .qzra")
    return name


def _https_parts(url: str):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise QamzorError("Cloud API endpoints must use HTTPS without embedded credentials")
    target = parsed.path or "/"
    if parsed.query:
        target += "?" + parsed.query
    return parsed, target


class _Http:
    def __init__(self, timeout: float = 120):
        if not isinstance(timeout, (int, float)) or timeout <= 0 or timeout > 600:
            raise QamzorError("Cloud request timeout must be between 0 and 600 seconds")
        self.timeout = float(timeout)

    def request(self, method: str, url: str, headers: dict[str, str] | None = None,
                source=None, size: int | None = None, output=None,
                allow_redirect: bool = False) -> tuple[int, dict[str, str], bytes]:
        parsed, target = _https_parts(url)
        connection = http.client.HTTPSConnection(
            parsed.hostname, parsed.port or 443, timeout=self.timeout,
            context=ssl.create_default_context(),
        )
        request_headers = {"Host": parsed.netloc, "Connection": "close", "User-Agent": "QAMZOR-Cloud/0.9.0"}
        if headers:
            request_headers.update(headers)
        if source is not None:
            if size is None or size < 0:
                raise QamzorError("A known upload size is required")
            request_headers["Content-Length"] = str(size)
        try:
            connection.putrequest(method, target, skip_accept_encoding=True)
            for key, value in request_headers.items():
                connection.putheader(key, value)
            connection.endheaders()
            if source is not None:
                sent = 0
                while sent < size:
                    block = source.read(min(_IO, size - sent))
                    if not block:
                        raise QamzorError("Upload source ended before the declared content length")
                    connection.send(block)
                    sent += len(block)
                if source.read(1):
                    raise QamzorError("Upload source grew after its size was calculated")
            response = connection.getresponse()
            response_headers = {k.lower(): v for k, v in response.getheaders()}
            if output is not None and response.status == 200:
                total = 0
                while True:
                    block = response.read(_IO)
                    if not block:
                        break
                    total += len(block)
                    if total > _MAX_OBJECT:
                        raise FormatError("Remote object exceeds the 1 TiB safety ceiling")
                    output.write(block)
                body = b""
            else:
                body = response.read(1024 * 1024 + 1)
                if len(body) > 1024 * 1024:
                    raise FormatError("Cloud API response exceeds its size limit")
            if 300 <= response.status < 400 and not allow_redirect:
                raise QamzorError(f"Cloud API returned an unexpected HTTP redirect ({response.status})")
            return response.status, response_headers, body
        finally:
            connection.close()

    def json(self, method: str, url: str, headers: dict[str, str] | None = None, value=None) -> tuple[int, dict[str, str], dict]:
        request_headers = {"Accept": "application/json"}
        if headers:
            request_headers.update(headers)
        stream = None
        size = None
        if value is not None:
            raw = _json_bytes(value)
            stream = io.BytesIO(raw)
            size = len(raw)
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        status, response_headers, body = self.request(method, url, request_headers, stream, size)
        result = _decode_json(body, "Cloud API response") if body else {}
        return status, response_headers, result


class _VerifiedAdapter(_Http):
    provider = "unknown"

    def _metadata(self, name: str, size: int, digest: str, remote_reference: str) -> bytes:
        return _json_bytes({
            "format": "QAMZOR-CLOUD-OBJECT", "version": 1,
            "provider": self.provider, "name": name,
            "remote_reference": remote_reference,
            "size": size, "sha256": digest,
        })

    def _upload_metadata(self, name: str, payload: bytes) -> str | None:
        raise NotImplementedError

    def push(self, archive_path, *, remote_name: str | None = None) -> dict:
        source, size, digest, before = _validate_source(archive_path)
        name = _safe_remote_name(remote_name or source.name)
        remote_reference = self._upload_archive(name, source, size)
        after = source.stat()
        after_digest, after_size = _digest(source)
        if ((before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino)
                or after_size != size or not hmac.compare_digest(after_digest, digest)):
            raise QamzorError("Local archive changed during cloud upload; the integrity sidecar was not published")
        sidecar = self._metadata(name, size, digest, remote_reference)
        metadata_reference = self._upload_metadata(name + ".qamzor.json", sidecar)
        return {
            "provider": self.provider, "remote_name": name,
            "remote_reference": remote_reference,
            "metadata_reference": metadata_reference,
            "bytes": size, "sha256": digest,
            "source_sha256_recorded": True,
        }

    def _upload_archive(self, name: str, source: Path, size: int) -> str:
        raise NotImplementedError

    def _read_metadata(self, remote_reference: str, metadata_reference: str | None) -> bytes:
        raise NotImplementedError

    def _download_archive(self, remote_reference: str, output) -> None:
        raise NotImplementedError

    def pull(self, remote_reference: str, output_path, *, overwrite: bool = False,
             metadata_reference: str | None = None) -> dict:
        output = Path(output_path).expanduser().absolute()
        if output.exists() and not overwrite:
            raise QamzorError(f"Destination already exists: {output}")
        output.parent.mkdir(parents=True, exist_ok=True)
        # Refuse symlinked parents before creating a temporary file.
        current = output.parent
        while True:
            if current.exists() and current.is_symlink():
                raise QamzorError(f"Symbolic-link destination component is not allowed: {current}")
            if current.parent == current:
                break
            current = current.parent
        metadata = _decode_json(self._read_metadata(remote_reference, metadata_reference), "QAMZOR cloud metadata")
        if (metadata.get("format") != "QAMZOR-CLOUD-OBJECT" or metadata.get("version") != 1 or
                metadata.get("provider") != self.provider or metadata.get("remote_reference") != remote_reference):
            raise FormatError("Cloud metadata does not match this provider/object")
        expected_size = metadata.get("size")
        expected_digest = metadata.get("sha256")
        if (not isinstance(expected_size, int) or isinstance(expected_size, bool) or
                expected_size < HEADER_SIZE or expected_size > _MAX_OBJECT or
                not isinstance(expected_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_digest)):
            raise FormatError("Cloud metadata contains invalid size or SHA-256 fields")
        fd, temp_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".download", dir=output.parent)
        digest = hashlib.sha256()
        total = 0
        try:
            with os.fdopen(fd, "wb") as target:
                class HashingWriter:
                    def write(self, data):
                        nonlocal total
                        total += len(data)
                        if total > expected_size:
                            raise FormatError("Downloaded cloud object exceeds its declared size")
                        digest.update(data)
                        return target.write(data)
                self._download_archive(remote_reference, HashingWriter())
                target.flush()
                os.fsync(target.fileno())
            if total != expected_size or digest.hexdigest() != expected_digest:
                raise FormatError("Downloaded cloud object failed size or SHA-256 verification")
            with open(temp_name, "rb") as check:
                header = check.read(HEADER_SIZE)
            try:
                magic, major, minor, flags, manifest_len, _entries, reserved = HEADER.unpack(header)
            except Exception as exc:
                raise FormatError("Downloaded object is not a valid QZRA container") from exc
            max_manifest = MAX_MANIFEST_SIZE + (V13_MANIFEST_PREFIX.size + 16 if minor == 3 else 0)
            if (magic != MAGIC or major != MAJOR_VERSION or minor > MINOR_VERSION or flags & ~SUPPORTED_FLAGS or
                    reserved != 0 or manifest_len > max_manifest or HEADER_SIZE + manifest_len > total):
                raise FormatError("Downloaded object failed QZRA header validation")
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


class GoogleDriveBackup(_VerifiedAdapter):
    """Google Drive API v3 adapter using a caller-provided OAuth access token."""
    provider = "google_drive"

    def __init__(self, access_token: str, *, timeout: float = 120, api_base: str = "https://www.googleapis.com"):
        super().__init__(timeout)
        if not isinstance(access_token, str) or not access_token.strip():
            raise QamzorError("Google Drive OAuth access token is required at runtime")
        self.token = access_token
        parsed, _ = _https_parts(api_base.rstrip("/") + "/")
        self.api_base = f"https://{parsed.netloc}{parsed.path.rstrip('/')}"

    def _auth(self):
        return {"Authorization": f"Bearer {self.token}"}

    def _resumable_upload(self, name: str, source, size: int, mime: str) -> str:
        status, headers, _ = self.json(
            "POST", self.api_base + "/upload/drive/v3/files?uploadType=resumable&fields=id,name,size",
            {**self._auth(), "X-Upload-Content-Type": mime, "X-Upload-Content-Length": str(size)},
            {"name": name},
        )
        if status not in {200, 201} or not headers.get("location"):
            raise QamzorError(f"Google Drive could not create a resumable upload session (HTTP {status})")
        status, _headers, body = self.request(
            "PUT", headers["location"],
            {"Content-Type": mime, "Content-Range": f"bytes 0-{size - 1}/{size}"},
            source, size,
        )
        if status not in {200, 201}:
            raise QamzorError(f"Google Drive upload failed with HTTP {status}: {body[:300]!r}")
        result = _decode_json(body, "Google Drive upload response")
        file_id = result.get("id")
        if not isinstance(file_id, str) or not file_id:
            raise FormatError("Google Drive upload response did not contain a file ID")
        return file_id

    def _upload_archive(self, name: str, source: Path, size: int) -> str:
        with source.open("rb") as stream:
            return self._resumable_upload(name, stream, size, "application/octet-stream")

    def _upload_metadata(self, name: str, payload: bytes) -> str:
        return self._resumable_upload(name, io.BytesIO(payload), len(payload), "application/json")

    def _read_metadata(self, remote_reference: str, metadata_reference: str | None) -> bytes:
        if not metadata_reference:
            raise QamzorError("Google Drive restore requires the metadata_reference returned by push()")
        url = self.api_base + "/drive/v3/files/" + quote(metadata_reference, safe="") + "?alt=media"
        status, _headers, body = self.request("GET", url, self._auth())
        if status != 200:
            raise QamzorError(f"Google Drive metadata download failed with HTTP {status}")
        return body

    def _download_archive(self, remote_reference: str, output) -> None:
        url = self.api_base + "/drive/v3/files/" + quote(remote_reference, safe="") + "?alt=media"
        status, _headers, _body = self.request("GET", url, self._auth(), output=output)
        if status != 200:
            raise QamzorError(f"Google Drive download failed with HTTP {status}")


class DropboxBackup(_VerifiedAdapter):
    """Dropbox API v2 adapter. Files are uploaded with upload sessions."""
    provider = "dropbox"
    _CHUNK = 8 * 1024 * 1024

    def __init__(self, access_token: str, *, timeout: float = 120):
        super().__init__(timeout)
        if not isinstance(access_token, str) or not access_token.strip():
            raise QamzorError("Dropbox OAuth access token is required at runtime")
        self.token = access_token

    def _headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def _upload_session(self, path: str, source, size: int, content_type: str) -> str:
        path = "/" + path.lstrip("/")
        first = source.read(min(self._CHUNK, size))
        if len(first) != min(self._CHUNK, size):
            raise QamzorError("Dropbox upload source changed during upload")
        status, _headers, body = self.request(
            "POST", "https://content.dropboxapi.com/2/files/upload_session/start",
            {**self._headers(), "Content-Type": "application/octet-stream", "Dropbox-API-Arg": json.dumps({"close": False}, separators=(",", ":"))},
            io.BytesIO(first), len(first),
        )
        if status not in {200, 201}:
            raise QamzorError(f"Dropbox upload session start failed with HTTP {status}")
        session_id = _decode_json(body, "Dropbox upload session response").get("session_id")
        if not isinstance(session_id, str) or not session_id:
            raise FormatError("Dropbox did not return an upload session ID")
        offset = len(first)
        while size - offset > self._CHUNK:
            block = source.read(self._CHUNK)
            if len(block) != self._CHUNK:
                raise QamzorError("Dropbox upload source changed during upload")
            arg = {"cursor": {"session_id": session_id, "offset": offset}, "close": False}
            status, _h, _b = self.request(
                "POST", "https://content.dropboxapi.com/2/files/upload_session/append_v2",
                {**self._headers(), "Content-Type": "application/octet-stream", "Dropbox-API-Arg": json.dumps(arg, separators=(",", ":"))},
                io.BytesIO(block), len(block),
            )
            if status not in {200, 204}:
                raise QamzorError(f"Dropbox upload-session append failed with HTTP {status}")
            offset += len(block)
        final = source.read(size - offset)
        if len(final) != size - offset or source.read(1):
            raise QamzorError("Dropbox upload source changed during upload")
        commit = {"cursor": {"session_id": session_id, "offset": offset}, "commit": {"path": path, "mode": "overwrite", "autorename": False, "mute": True, "strict_conflict": True}}
        status, _headers, body = self.request(
            "POST", "https://content.dropboxapi.com/2/files/upload_session/finish",
            {**self._headers(), "Content-Type": "application/octet-stream", "Dropbox-API-Arg": json.dumps(commit, separators=(",", ":"))},
            io.BytesIO(final), len(final),
        )
        if status not in {200, 201}:
            raise QamzorError(f"Dropbox upload-session finish failed with HTTP {status}: {body[:300]!r}")
        result = _decode_json(body, "Dropbox upload response")
        remote = result.get("path_lower") or result.get("path_display")
        if not isinstance(remote, str) or not remote:
            raise FormatError("Dropbox upload response did not include the uploaded path")
        return remote

    def _upload_archive(self, name: str, source: Path, size: int) -> str:
        with source.open("rb") as stream:
            return self._upload_session("/" + name, stream, size, "application/octet-stream")

    def _upload_metadata(self, name: str, payload: bytes) -> str:
        return self._upload_session("/" + name, io.BytesIO(payload), len(payload), "application/json")

    def _download_path(self, path: str, output=None) -> bytes:
        status, _headers, body = self.request(
            "POST", "https://content.dropboxapi.com/2/files/download",
            {**self._headers(), "Dropbox-API-Arg": json.dumps({"path": path}, separators=(",", ":"))},
            output=output,
        )
        if status != 200:
            raise QamzorError(f"Dropbox download failed with HTTP {status}")
        return body

    def _read_metadata(self, remote_reference: str, metadata_reference: str | None) -> bytes:
        return self._download_path(metadata_reference or (remote_reference + ".qamzor.json"))

    def _download_archive(self, remote_reference: str, output) -> None:
        self._download_path(remote_reference, output)


class OneDriveBackup(_VerifiedAdapter):
    """Microsoft Graph OneDrive adapter with resumable upload sessions."""
    provider = "onedrive"
    _CHUNK = 10 * 1024 * 1024  # multiple of Graph's 320 KiB upload unit

    def __init__(self, access_token: str, *, timeout: float = 120, graph_base: str = "https://graph.microsoft.com/v1.0"):
        super().__init__(timeout)
        if not isinstance(access_token, str) or not access_token.strip():
            raise QamzorError("Microsoft Graph OAuth access token is required at runtime")
        self.token = access_token
        parsed, _ = _https_parts(graph_base.rstrip("/") + "/")
        self.graph_base = f"https://{parsed.netloc}{parsed.path.rstrip('/')}"

    def _headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def _upload_session(self, name: str, source, size: int) -> str:
        encoded = quote(name, safe="")
        status, _headers, body = self.json(
            "POST", f"{self.graph_base}/me/drive/root:/{encoded}:/createUploadSession",
            self._headers(), {"item": {"@microsoft.graph.conflictBehavior": "replace", "name": name}},
        )
        if status not in {200, 201}:
            raise QamzorError(f"OneDrive could not create an upload session (HTTP {status})")
        upload_url = body.get("uploadUrl")
        if not isinstance(upload_url, str) or not upload_url.startswith("https://"):
            raise FormatError("Microsoft Graph returned an invalid upload URL")
        offset = 0
        final_result = None
        while offset < size:
            count = min(self._CHUNK, size - offset)
            block = source.read(count)
            if len(block) != count:
                raise QamzorError("OneDrive upload source changed during upload")
            headers = {
                "Content-Type": "application/octet-stream",
                "Content-Length": str(count),
                "Content-Range": f"bytes {offset}-{offset + count - 1}/{size}",
            }
            status, _h, response = self.request("PUT", upload_url, headers, io.BytesIO(block), count)
            if status == 202:
                offset += count
                continue
            if status in {200, 201}:
                final_result = _decode_json(response, "OneDrive upload response")
                offset += count
                break
            raise QamzorError(f"OneDrive chunk upload failed with HTTP {status}: {response[:300]!r}")
        if size == 0:
            raise QamzorError("OneDrive does not accept an empty archive object")
        remote_id = (final_result or {}).get("id")
        if not isinstance(remote_id, str) or not remote_id:
            raise FormatError("Microsoft Graph did not return the uploaded item ID")
        return remote_id

    def _upload_archive(self, name: str, source: Path, size: int) -> str:
        with source.open("rb") as stream:
            return self._upload_session(name, stream, size)

    def _upload_metadata(self, name: str, payload: bytes) -> str:
        return self._upload_session(name, io.BytesIO(payload), len(payload))

    def _read_metadata(self, remote_reference: str, metadata_reference: str | None) -> bytes:
        if not metadata_reference:
            raise QamzorError("OneDrive restore requires the metadata_reference returned by push()")
        return self._download_item(metadata_reference, None)

    def _download_item(self, item_id: str, output=None) -> bytes:
        url = self.graph_base + "/me/drive/items/" + quote(item_id, safe="") + "/content"
        status, headers, body = self.request("GET", url, self._headers(), output=output, allow_redirect=True)
        if status in {302, 303, 307, 308}:
            location = headers.get("location")
            if not location:
                raise FormatError("OneDrive content redirect did not include a Location header")
            status, _h, body = self.request("GET", location, output=output)
        if status != 200:
            raise QamzorError(f"OneDrive download failed with HTTP {status}")
        return body

    def _download_archive(self, remote_reference: str, output) -> None:
        self._download_item(remote_reference, output)


class S3Backup(_VerifiedAdapter):
    """AWS Signature V4 S3 object transport; supports AWS and S3-compatible endpoints."""
    provider = "s3"

    def __init__(self, bucket: str, access_key: str, secret_key: str, region: str, *,
                 session_token: str | None = None, endpoint_url: str | None = None, timeout: float = 120):
        super().__init__(timeout)
        if not all(isinstance(v, str) and v.strip() for v in (bucket, access_key, secret_key, region)):
            raise QamzorError("S3 bucket, access key, secret key and region are required at runtime")
        if (not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])", bucket)
                or ".." in bucket or ".-" in bucket or "-." in bucket
                or re.fullmatch(r"\d{1,3}(?:\.\d{1,3}){3}", bucket)):
            raise QamzorError("S3 bucket name is malformed or violates DNS-compatible bucket naming rules")
        self.bucket, self.access_key, self.secret_key = bucket, access_key, secret_key
        self.region, self.session_token = region, session_token
        endpoint = endpoint_url or f"https://s3.{region}.amazonaws.com"
        parsed, _ = _https_parts(endpoint.rstrip("/") + "/")
        self.endpoint = f"https://{parsed.netloc}{parsed.path.rstrip('/')}"

    @staticmethod
    def _hmac(key: bytes, value: str) -> bytes:
        return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()

    def _sign(self, method: str, key: str, payload_hash: str, content_length: int | None = None,
              query: str = "") -> tuple[str, dict[str, str]]:
        now = datetime.now(timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        date_stamp = now.strftime("%Y%m%d")
        parsed = urlsplit(self.endpoint)
        object_path = "/".join(quote(part, safe="-_.~") for part in key.split("/"))
        base_path = parsed.path.rstrip("/")
        canonical_uri = f"{base_path}/{quote(self.bucket, safe='-_.~')}/{object_path}"
        host = parsed.netloc
        headers = {
            "host": host,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        }
        if self.session_token:
            headers["x-amz-security-token"] = self.session_token
        if content_length is not None:
            headers["content-length"] = str(content_length)
        canonical_headers = "".join(f"{name}:{headers[name].strip()}\n" for name in sorted(headers))
        signed_headers = ";".join(sorted(headers))
        canonical_request = "\n".join((method, canonical_uri, query, canonical_headers, signed_headers, payload_hash))
        scope = f"{date_stamp}/{self.region}/s3/aws4_request"
        string_to_sign = "AWS4-HMAC-SHA256\n" + amz_date + "\n" + scope + "\n" + hashlib.sha256(canonical_request.encode()).hexdigest()
        key_date = self._hmac(("AWS4" + self.secret_key).encode(), date_stamp)
        key_region = self._hmac(key_date, self.region)
        key_service = self._hmac(key_region, "s3")
        signing_key = self._hmac(key_service, "aws4_request")
        signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
        auth = f"AWS4-HMAC-SHA256 Credential={self.access_key}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"
        request_headers = {name: value for name, value in headers.items() if name != "host"}
        request_headers["Authorization"] = auth
        suffix = "?" + query if query else ""
        return self.endpoint + canonical_uri + suffix, request_headers

    def _object_url(self, key: str, payload_hash: str, method: str, size: int | None = None,
                    query: str = ""):
        return self._sign(method, key, payload_hash, size, query)

    def _put(self, key: str, source, size: int, digest: str, content_type: str):
        url, headers = self._object_url(key, digest, "PUT", size)
        headers["Content-Type"] = content_type
        status, _h, body = self.request("PUT", url, headers, source, size)
        if status not in {200, 201, 204}:
            raise QamzorError(f"S3 PutObject failed with HTTP {status}: {body[:300]!r}")

    def _get(self, key: str, output=None) -> bytes:
        url, headers = self._object_url(key, _S3_EMPTY_SHA, "GET")
        status, _h, body = self.request("GET", url, headers, output=output)
        if status != 200:
            raise QamzorError(f"S3 GetObject failed with HTTP {status}")
        return body

    def _multipart_abort(self, key: str, upload_id: str) -> None:
        query = "uploadId=" + quote(upload_id, safe="-_.~")
        url, headers = self._object_url(key, _S3_EMPTY_SHA, "DELETE", query=query)
        try:
            self.request("DELETE", url, headers)
        except Exception:
            # Preserve the original upload failure; abort is best-effort cleanup.
            pass

    def _multipart_upload(self, key: str, source, size: int) -> None:
        empty_hash = _S3_EMPTY_SHA
        initiate_query = "uploads="
        url, headers = self._object_url(key, empty_hash, "POST", 0, initiate_query)
        status, _headers, body = self.request("POST", url, headers, io.BytesIO(b""), 0)
        if status not in {200, 201}:
            raise QamzorError(f"S3 multipart initiation failed with HTTP {status}: {body[:300]!r}")
        import xml.etree.ElementTree as ET
        try:
            upload_id = ET.fromstring(body).findtext("UploadId")
        except ET.ParseError as exc:
            raise FormatError("S3 multipart initiation returned invalid XML") from exc
        if not upload_id:
            raise FormatError("S3 multipart initiation did not return an upload ID")
        # 128 MiB allows 1 TiB objects to fit within S3's 10,000-part limit.
        part_size = 128 * 1024 * 1024
        parts: list[tuple[int, str]] = []
        offset = 0
        try:
            part_number = 1
            while offset < size:
                block = source.read(min(part_size, size - offset))
                if not block:
                    raise QamzorError("S3 source ended before multipart upload completed")
                digest = hashlib.sha256(block).hexdigest()
                query = f"partNumber={part_number}&uploadId={quote(upload_id, safe='-_.~')}"
                url, headers = self._object_url(key, digest, "PUT", len(block), query)
                status, response_headers, body = self.request("PUT", url, headers, io.BytesIO(block), len(block))
                if status not in {200, 201}:
                    raise QamzorError(f"S3 multipart part {part_number} failed with HTTP {status}: {body[:300]!r}")
                etag = response_headers.get("etag")
                if not etag:
                    raise FormatError(f"S3 multipart part {part_number} did not return an ETag")
                parts.append((part_number, etag))
                offset += len(block)
                part_number += 1
            if offset != size or source.read(1):
                raise QamzorError("S3 source changed during multipart upload")
            xml = ['<CompleteMultipartUpload>']
            for number, etag in parts:
                escaped = etag.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                xml.append(f"<Part><PartNumber>{number}</PartNumber><ETag>{escaped}</ETag></Part>")
            xml.append('</CompleteMultipartUpload>')
            payload = ''.join(xml).encode("utf-8")
            digest = hashlib.sha256(payload).hexdigest()
            query = "uploadId=" + quote(upload_id, safe="-_.~")
            url, headers = self._object_url(key, digest, "POST", len(payload), query)
            headers["Content-Type"] = "application/xml"
            status, _headers, response = self.request("POST", url, headers, io.BytesIO(payload), len(payload))
            if status not in {200, 201}:
                raise QamzorError(f"S3 multipart completion failed with HTTP {status}: {response[:300]!r}")
            if b"<Error>" in response or b":Error>" in response:
                raise QamzorError("S3 returned an error while completing multipart upload")
        except Exception:
            self._multipart_abort(key, upload_id)
            raise

    def _upload_archive(self, name: str, source: Path, size: int) -> str:
        digest, actual_size = _digest(source)
        if actual_size != size:
            raise QamzorError("Local archive changed before S3 upload")
        with source.open("rb") as stream:
            if size <= 5 * 1024 * 1024 * 1024:
                self._put(name, stream, size, digest, "application/octet-stream")
            else:
                self._multipart_upload(name, stream, size)
        return name

    def _upload_metadata(self, name: str, payload: bytes) -> str:
        self._put(name, io.BytesIO(payload), len(payload), hashlib.sha256(payload).hexdigest(), "application/json")
        return name

    def _read_metadata(self, remote_reference: str, metadata_reference: str | None) -> bytes:
        return self._get(metadata_reference or (remote_reference + ".qamzor.json"))

    def _download_archive(self, remote_reference: str, output) -> None:
        self._get(remote_reference, output)
