#!/usr/bin/env python3
"""Reference conformance runner: executes the QAMZOR Compatibility Suite against the Python engine.

Usage: PYTHONPATH=src python compat/runners/python_runner.py [--suite compat]
Exit status is 0 only if every vector behaves as specified in compat/manifest.json.
"""
from __future__ import annotations
import argparse, hashlib, json, sys, tempfile
from pathlib import Path

from qamzor import open_archive, FormatError, IntegrityError, SafetyError, QamzorError


def categorize(exc: Exception) -> str:
    text = str(exc).lower()
    if isinstance(exc, SafetyError):
        return "UNSAFE_PATH"
    if isinstance(exc, FormatError):
        return "UNSUPPORTED_VERSION" if "unsupported" in text and "version" in text else "INVALID_FORMAT"
    if isinstance(exc, IntegrityError):
        return "AUTHENTICATION_FAILED" if "authentication" in text or "password" in text else "INTEGRITY_FAILED"
    return "UNKNOWN"


def files_under(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.is_file())


def run_vector(suite: Path, v: dict) -> str | None:
    archive = suite / "vectors" / v["file"]
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != v["sha256"]:
        return f"vector file digest mismatch (corrupt checkout?): {digest}"
    expect = v["expect"]
    folder = v["folder_passwords"] or None
    with tempfile.TemporaryDirectory() as tmp:
        sandbox = Path(tmp)
        dest = sandbox / "out"
        dest.mkdir()
        try:
            with open_archive(archive, password=v["password"], folder_passwords=folder) as reader:
                entries = sorted(
                    ({"path": e.path, "type": e.kind, "size": e.size,
                      **({"sha256": e.sha256} if e.kind == "file" else {})} for e in reader.entries),
                    key=lambda e: e["path"])
                reader.verify()
                reader.extract(dest)
        except QamzorError as exc:
            stray = [p for p in files_under(sandbox) if dest not in p.parents]
            if stray:
                return f"files written outside destination: {stray}"
            if expect["outcome"] == "ok":
                return f"unexpected error {type(exc).__name__}: {exc}"
            got = categorize(exc)
            return None if got == expect["category"] else f"expected {expect['category']}, got {got}: {exc}"
        if expect["outcome"] == "error":
            return f"expected {expect['category']} but the archive was accepted"
        if entries != expect["entries"]:
            return "entry list differs from manifest"
        for e in expect["entries"]:
            target = dest / e["path"]
            if e["type"] == "directory":
                if not target.is_dir():
                    return f"missing directory {e['path']}"
            elif not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != e["sha256"]:
                return f"extracted file differs: {e['path']}"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default=str(Path(__file__).resolve().parents[1]))
    suite = Path(ap.parse_args().suite)
    manifest = json.loads((suite / "manifest.json").read_text(encoding="utf-8"))
    failures = 0
    for v in manifest["vectors"]:
        problem = run_vector(suite, v)
        print(("PASS " if problem is None else "FAIL ") + v["id"] + ("" if problem is None else f"  -- {problem}"))
        failures += problem is not None
    print(f"{len(manifest['vectors']) - failures}/{len(manifest['vectors'])} vectors passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
