"""Detached Ed25519 signatures for QAMZOR archive files.

Signatures cover a domain-separated SHA-256 digest and exact archive length,
so large archive files can be signed and verified without loading them all.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Optional

from .format import QamzorError, IntegrityError

SIGNATURE_FORMAT = "QZRA-SIGNATURE"
SIGNATURE_VERSION = 1
SIGNATURE_DOMAIN = b"QAMZOR-ED25519-ARCHIVE-SIGNATURE-V1\0"
MAX_SIGNATURE_FILE = 64 * 1024


def _crypto():
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
        from cryptography.exceptions import InvalidSignature
    except ImportError as exc:
        raise QamzorError("Signing requires the optional dependency: pip install 'qamzor[crypto]'") from exc
    return serialization, Ed25519PrivateKey, Ed25519PublicKey, InvalidSignature


def _atomic_write(path: Path, data: bytes, *, overwrite: bool, private: bool = False) -> None:
    path = path.expanduser().absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and not overwrite:
        raise QamzorError(f"Destination already exists: {path}")
    fd, temp_path = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent))
    try:
        if private and os.name == "posix":
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as target:
            target.write(data)
            target.flush()
            os.fsync(target.fileno())
        if not overwrite:
            try:
                os.link(temp_path, path)
            except FileExistsError as exc:
                raise QamzorError(f"Destination already exists: {path}") from exc
            os.unlink(temp_path)
        else:
            os.replace(temp_path, path)
    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


def generate_signing_keypair(
    private_key_path: os.PathLike | str,
    public_key_path: os.PathLike | str,
    password: str,
    *,
    overwrite: bool = False,
) -> dict:
    """Generate an Ed25519 key pair with an encrypted PKCS#8 private key."""
    if not isinstance(password, str) or len(password) < 8:
        raise QamzorError("Signing-key password must contain at least 8 characters")
    serialization, PrivateKey, _, _ = _crypto()
    private_key = PrivateKey.generate()
    private_bytes = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.BestAvailableEncryption(password.encode("utf-8")),
    )
    public_bytes = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    private_path = Path(private_key_path).expanduser().absolute()
    public_path = Path(public_key_path).expanduser().absolute()
    if private_path == public_path:
        raise QamzorError("Private and public key paths must be different")
    # Check both paths before writing either file, avoiding half-generated pairs.
    if not overwrite and (private_path.exists() or public_path.exists()):
        raise QamzorError("A signing-key destination already exists")
    _atomic_write(private_path, private_bytes, overwrite=overwrite, private=True)
    try:
        _atomic_write(public_path, public_bytes, overwrite=overwrite)
    except Exception:
        # Do not leave a private key behind if publishing its public mate fails.
        try:
            private_path.unlink()
        except OSError:
            pass
        raise
    return {"private_key": str(private_path), "public_key": str(public_path), "algorithm": "Ed25519"}


def _archive_digest(path: os.PathLike | str) -> tuple[bytes, int]:
    digest = hashlib.sha256()
    size = 0
    with Path(path).open("rb") as source:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            digest.update(block)
    return digest.digest(), size


def _signature_message(digest: bytes, size: int) -> bytes:
    return SIGNATURE_DOMAIN + size.to_bytes(8, "big") + digest


def sign_archive(
    archive_path: os.PathLike | str,
    private_key_path: os.PathLike | str,
    *,
    private_key_password: Optional[str] = None,
    signature_path: Optional[os.PathLike | str] = None,
    overwrite: bool = False,
) -> dict:
    serialization, PrivateKey, _, _ = _crypto()
    try:
        private_key = serialization.load_pem_private_key(
            Path(private_key_path).read_bytes(),
            password=private_key_password.encode("utf-8") if private_key_password is not None else None,
        )
    except (TypeError, ValueError) as exc:
        raise QamzorError("Could not unlock signing private key; check the key file and passphrase") from exc
    if not isinstance(private_key, PrivateKey):
        raise QamzorError("Signing key must be an Ed25519 private key")
    digest, size = _archive_digest(archive_path)
    public_bytes = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    fingerprint = hashlib.sha256(public_bytes).hexdigest()
    signature = private_key.sign(_signature_message(digest, size))
    document = {
        "format": SIGNATURE_FORMAT,
        "version": SIGNATURE_VERSION,
        "algorithm": "Ed25519",
        "archive_size": size,
        "archive_sha256": digest.hex(),
        "public_key_sha256": fingerprint,
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    output = Path(signature_path) if signature_path else Path(str(archive_path) + ".qzsig")
    _atomic_write(output, json.dumps(document, sort_keys=True, separators=(",", ":")).encode("utf-8") + b"\n", overwrite=overwrite)
    return {"signature": str(output), "algorithm": "Ed25519", "archive_size": size, "archive_sha256": digest.hex()}


def verify_archive_signature(
    archive_path: os.PathLike | str,
    public_key_path: os.PathLike | str,
    signature_path: os.PathLike | str,
) -> dict:
    serialization, _, PublicKey, InvalidSignature = _crypto()
    signature_file = Path(signature_path)
    if signature_file.stat().st_size > MAX_SIGNATURE_FILE:
        raise QamzorError("Signature metadata file is too large")
    try:
        document = json.loads(signature_file.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise QamzorError("Signature metadata is not valid UTF-8 JSON") from exc
    if not isinstance(document, dict) or document.get("format") != SIGNATURE_FORMAT or document.get("version") != SIGNATURE_VERSION:
        raise QamzorError("Unsupported QAMZOR signature metadata")
    if document.get("algorithm") != "Ed25519":
        raise QamzorError("Unsupported archive signature algorithm")
    try:
        public_key = serialization.load_pem_public_key(Path(public_key_path).read_bytes())
    except (TypeError, ValueError) as exc:
        raise QamzorError("Could not load Ed25519 public key") from exc
    if not isinstance(public_key, PublicKey):
        raise QamzorError("Verification key must be an Ed25519 public key")
    public_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    fingerprint = hashlib.sha256(public_bytes).hexdigest()
    if document.get("public_key_sha256") != fingerprint:
        raise IntegrityError("Signature was created by a different public key")
    digest, size = _archive_digest(archive_path)
    if document.get("archive_size") != size or document.get("archive_sha256") != digest.hex():
        raise IntegrityError("Archive bytes do not match the signed archive")
    encoded_signature = document.get("signature")
    if not isinstance(encoded_signature, str):
        raise QamzorError("Signature value is missing")
    try:
        signature = base64.b64decode(encoded_signature, validate=True)
        public_key.verify(signature, _signature_message(digest, size))
    except (ValueError, InvalidSignature) as exc:
        raise IntegrityError("Ed25519 archive signature verification failed") from exc
    return {"valid": True, "algorithm": "Ed25519", "archive_size": size, "archive_sha256": digest.hex()}
