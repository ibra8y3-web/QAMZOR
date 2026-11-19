#!/usr/bin/env python3
"""Generate the QAMZOR Compatibility Suite vectors with the Python reference engine.

Vectors are generated ONCE and then frozen (salts/nonces are random), so this script
is for maintainers. Consumers only read compat/vectors/ and compat/manifest.json.
All passwords below are public test-only values; never use them for real data.
"""
from __future__ import annotations
import hashlib, json, os, shutil, struct, sys, tempfile, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))
from qamzor import create_archive, open_archive  # noqa: E402

OUT = ROOT / "compat" / "vectors"
MASTER = "QAMZOR-compat-master"
VAULT = "QAMZOR-compat-vault"
INNER = "QAMZOR-compat-inner"
MAGIC = b"QZRA\r\n\x1a\n"


def det_bytes(label: str, size: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < size:
        out += hashlib.sha256(f"{label}:{counter}".encode()).digest()
        counter += 1
    return bytes(out[:size])


def build_tree(base: Path) -> None:
    root = base / "Project"
    (root / "data").mkdir(parents=True)
    (root / "dup").mkdir()
    (root / "empty-dir").mkdir()
    (root / "Vault" / "Inner").mkdir(parents=True)
    binary = det_bytes("binary", 300_000)
    (root / "readme.txt").write_bytes(b"QAMZOR compatibility vector\n")
    (root / "data" / "binary.bin").write_bytes(binary)
    (root / "dup" / "copy-of-binary.bin").write_bytes(binary)
    (root / "empty.txt").write_bytes(b"")
    arabic = unicodedata.normalize("NFC", "ملف-عربي.txt")
    (root / arabic).write_bytes("مرحبا من قمزور\n".encode("utf-8"))
    (root / "Vault" / "secret.txt").write_bytes(b"vault secret\n")
    (root / "Vault" / "Inner" / "deep.txt").write_bytes(b"inner secret\n")


def expected_entries(base: Path) -> list[dict]:
    root = base / "Project"
    entries = [{"path": "Project", "type": "directory", "size": 0}]
    for p in sorted(root.rglob("*"), key=lambda q: q.relative_to(base).as_posix()):
        rel = unicodedata.normalize("NFC", p.relative_to(base).as_posix())
        if p.is_dir():
            entries.append({"path": rel, "type": "directory", "size": 0})
        else:
            data = p.read_bytes()
            entries.append({"path": rel, "type": "file", "size": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
    return sorted(entries, key=lambda e: e["path"])


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def craft_unencrypted_10(path: Path, entry_path: str, payload: bytes) -> None:
    """Handcrafted QZRA 1.0 archive used for negative (unsafe path) vectors."""
    import zlib
    comp = zlib.compress(payload)
    manifest = {"format": "QZRA", "version": "1.0", "entries": [{
        "path": entry_path, "type": "file", "size": len(payload), "offset": 0,
        "compressed_size": len(comp), "compression": "zlib",
        "sha256": hashlib.sha256(payload).hexdigest()}]}
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    header = struct.pack(">8sHHIQII", MAGIC, 1, 0, 0, len(raw), 1, 0)
    path.write_bytes(header + raw + comp)


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    work = Path(tempfile.mkdtemp())
    src = work / "src"
    src.mkdir()
    build_tree(src)
    tree = expected_entries(src)
    # Legacy fixtures written by the reference engine earlier in the project's history.
    res = ROOT / "core" / "src" / "test" / "resources"
    legacy = [
        ("positive-v1.0-plain", res / "python-reference-v1.0.qzra", "1.0", None),
        ("positive-v1.1-plain-legacy", res / "python-reference-v1.1.qzra", "1.1", None),
    ]
    vectors: list[dict] = []

    def describe(vid, file, version, kind, password=None, folder_passwords=None, metadata_hidden=False, notes=""):
        with open_archive(OUT / file, password=password, folder_passwords=folder_passwords) as reader:
            entries = sorted(
                [{"path": e.path, "type": e.kind, "size": e.size,
                  **({"sha256": e.sha256} if e.kind == "file" else {})} for e in reader.entries],
                key=lambda e: e["path"])
        vectors.append({
            "id": vid, "file": file, "sha256": sha256_file(OUT / file), "version": version,
            "kind": kind, "password": password, "folder_passwords": folder_passwords or {},
            "metadata_hidden": metadata_hidden, "expect": {"outcome": "ok", "entries": entries}, "notes": notes})

    for vid, source, version, password in legacy:
        name = f"{vid}.qzra"
        shutil.copyfile(source, OUT / name)
        describe(vid, name, version, "positive", password, notes="Reference-engine fixture carried over from the Kotlin core test resources.")

    # Fresh positive vectors.
    def make(name, **kw):
        create_archive(OUT / name, [src / "Project"], **kw)

    make("positive-v1.1-plain.qzra")
    describe("positive-v1.1-plain", "positive-v1.1-plain.qzra", "1.1", "positive")
    make("positive-v1.2-encrypted.qzra", password=MASTER)
    describe("positive-v1.2-encrypted", "positive-v1.2-encrypted.qzra", "1.2", "positive", MASTER)
    make("positive-v1.3-hidden-metadata.qzra", password=MASTER, encrypt_metadata=True)
    describe("positive-v1.3-hidden-metadata", "positive-v1.3-hidden-metadata.qzra", "1.3", "positive", MASTER, metadata_hidden=True)
    scopes = {"Project/Vault": VAULT, "Project/Vault/Inner": INNER}
    make("positive-v1.3-folder-scopes.qzra", password=MASTER, folder_passwords=scopes)
    describe("positive-v1.3-folder-scopes", "positive-v1.3-folder-scopes.qzra", "1.3", "positive", MASTER, scopes, True,
             "Nested scope: files under Project/Vault/Inner use INNER only; the parent scope is replaced, not combined.")

    # Negative vectors: each states the required error category (see compat/README.md).
    def negative(vid, file, category, version=None, password=None, folder_passwords=None, notes=""):
        vectors.append({"id": vid, "file": file, "sha256": sha256_file(OUT / file), "version": version,
                        "kind": "negative", "password": password, "folder_passwords": folder_passwords or {},
                        "metadata_hidden": False, "expect": {"outcome": "error", "category": category}, "notes": notes})

    shutil.copyfile(OUT / "positive-v1.2-encrypted.qzra", OUT / "negative-v1.2-wrong-password.qzra")
    negative("negative-v1.2-wrong-password", "negative-v1.2-wrong-password.qzra", "AUTHENTICATION_FAILED", "1.2", "wrong-password")
    shutil.copyfile(OUT / "positive-v1.3-hidden-metadata.qzra", OUT / "negative-v1.3-wrong-master.qzra")
    negative("negative-v1.3-wrong-master", "negative-v1.3-wrong-master.qzra", "AUTHENTICATION_FAILED", "1.3", "wrong-password")
    shutil.copyfile(OUT / "positive-v1.3-folder-scopes.qzra", OUT / "negative-v1.3-wrong-folder-password.qzra")
    negative("negative-v1.3-wrong-folder-password", "negative-v1.3-wrong-folder-password.qzra", "AUTHENTICATION_FAILED", "1.3",
             MASTER, {"Project/Vault": "wrong-folder-password", "Project/Vault/Inner": INNER},
             "Master password is correct (names are listed); the folder password is wrong, so verify/extract must fail.")

    # Tampered payload: flip one byte in the final payload byte of an unencrypted archive.
    data = bytearray((OUT / "positive-v1.1-plain.qzra").read_bytes())
    data[-1] ^= 0x01
    (OUT / "negative-v1.1-tampered-payload.qzra").write_bytes(bytes(data))
    negative("negative-v1.1-tampered-payload", "negative-v1.1-tampered-payload.qzra", "INTEGRITY_FAILED", "1.1")

    data = bytearray((OUT / "positive-v1.2-encrypted.qzra").read_bytes())
    data[-1] ^= 0x01
    (OUT / "negative-v1.2-tampered-ciphertext.qzra").write_bytes(bytes(data))
    negative("negative-v1.2-tampered-ciphertext", "negative-v1.2-tampered-ciphertext.qzra", "AUTHENTICATION_FAILED", "1.2", MASTER,
             notes="An AES-GCM tag failure cannot distinguish a wrong key from tampering, so it is reported as AUTHENTICATION_FAILED.")

    # Tampered 1.3 manifest envelope ciphertext (byte right after the 32-byte envelope header).
    data = bytearray((OUT / "positive-v1.3-hidden-metadata.qzra").read_bytes())
    data[32 + 32 + 4] ^= 0x01  # file header (32) + envelope header (32) + 4 bytes into ciphertext
    (OUT / "negative-v1.3-tampered-manifest.qzra").write_bytes(bytes(data))
    negative("negative-v1.3-tampered-manifest", "negative-v1.3-tampered-manifest.qzra", "AUTHENTICATION_FAILED", "1.3", MASTER)

    data = (OUT / "positive-v1.1-plain.qzra").read_bytes()
    (OUT / "negative-v1.1-truncated.qzra").write_bytes(data[: len(data) - 100])
    negative("negative-v1.1-truncated", "negative-v1.1-truncated.qzra", "INVALID_FORMAT", "1.1")

    data = bytearray((OUT / "positive-v1.1-plain.qzra").read_bytes())
    data[0] ^= 0xFF
    (OUT / "negative-bad-magic.qzra").write_bytes(bytes(data))
    negative("negative-bad-magic", "negative-bad-magic.qzra", "INVALID_FORMAT")

    data = bytearray((OUT / "positive-v1.1-plain.qzra").read_bytes())
    data[10:12] = struct.pack(">H", 9)  # minor version 9 is unsupported
    (OUT / "negative-unsupported-minor.qzra").write_bytes(bytes(data))
    negative("negative-unsupported-minor", "negative-unsupported-minor.qzra", "UNSUPPORTED_VERSION")

    craft_unencrypted_10(OUT / "negative-path-traversal.qzra", "../escape.txt", b"escape")
    negative("negative-path-traversal", "negative-path-traversal.qzra", "UNSAFE_PATH", "1.0",
             notes="Entry path '../escape.txt' must be rejected before any file is written.")
    craft_unencrypted_10(OUT / "negative-absolute-path.qzra", "/etc/escape.txt", b"escape")
    negative("negative-absolute-path", "negative-absolute-path.qzra", "UNSAFE_PATH", "1.0")
    craft_unencrypted_10(OUT / "negative-windows-device-name.qzra", "docs/CON.txt", b"x")
    negative("negative-windows-device-name", "negative-windows-device-name.qzra", "UNSAFE_PATH", "1.0")

    sums = "".join(f"{v['sha256']}  {v['file']}\n" for v in vectors)
    (OUT / "SHA256SUMS").write_text(sums)
    suite = {
        "suite": "QAMZOR Compatibility Suite", "suite_version": "1.0.0", "format": "QZRA",
        "supported_versions": ["1.0", "1.1", "1.2", "1.3"],
        "generator": "tools/generate_compat_vectors.py (Python reference engine)",
        "test_only_passwords": {"master": MASTER, "vault": VAULT, "inner": INNER},
        "procedure": "open (with the vector passwords) then verify, then extract into an empty directory; an error at any step must carry the expected category and extraction must leave no file outside the destination",
        "error_categories": ["AUTHENTICATION_FAILED", "INTEGRITY_FAILED", "INVALID_FORMAT", "UNSAFE_PATH", "UNSUPPORTED_VERSION"],
        "source_tree": tree, "vectors": vectors}
    (ROOT / "compat" / "manifest.json").write_text(json.dumps(suite, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    shutil.rmtree(work)
    print(f"generated {len(vectors)} vectors")


if __name__ == "__main__":
    main()
