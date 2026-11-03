#!/usr/bin/env python3
"""Reproducible, local QAMZOR/ZIP/7z archive benchmark.

The generated fixture contains compressible, incompressible, and duplicate
content. Measurements are descriptive, not claims of universal superiority.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import random
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
from qamzor.format import QZRAReader, create_archive  # noqa: E402


def build_fixture(directory: Path, size_mib: int) -> None:
    size = size_mib * 1024 * 1024
    repeated = (b"QAMZOR benchmark repeated block; chunking and compression.\n" * ((size // 59) + 1))[:size]
    rng = random.Random(20261103)
    random_data = rng.randbytes(size)
    (directory / "repetitive.bin").write_bytes(repeated)
    (directory / "random.bin").write_bytes(random_data)
    (directory / "duplicate.bin").write_bytes(repeated)


def timed(function):
    started = time.perf_counter()
    result = function()
    return result, time.perf_counter() - started


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size-mib", type=int, default=8, help="size per fixture file (default: 8 MiB)")
    parser.add_argument("--workers", type=int, default=1, help="QAMZOR compression workers (1-32)")
    parser.add_argument("--output", type=Path, help="optional JSON output path")
    args = parser.parse_args()
    if not 1 <= args.size_mib <= 1024:
        parser.error("--size-mib must be between 1 and 1024")
    if not 1 <= args.workers <= 32:
        parser.error("--workers must be between 1 and 32")

    with tempfile.TemporaryDirectory(prefix="qamzor-benchmark-") as temp:
        work = Path(temp)
        fixture = work / "fixture"
        fixture.mkdir()
        build_fixture(fixture, args.size_mib)
        inputs = sorted(fixture.iterdir())
        report = {
            "environment": {
                "python": platform.python_version(),
                "platform": platform.platform(),
                "machine": platform.machine(),
                "processor": platform.processor(),
                "zlib": __import__("zlib").ZLIB_VERSION,
                "fixture_mib_per_file": args.size_mib,
                "input_files": len(inputs),
                "input_bytes": sum(path.stat().st_size for path in inputs),
                "workers": args.workers,
            },
            "results": {},
        }

        qz_archive = work / "fixture.qzra"
        qz_result, qz_seconds = timed(lambda: create_archive(qz_archive, inputs, profile="balanced", workers=args.workers))
        with QZRAReader(qz_archive) as reader:
            verify_result, verify_seconds = timed(reader.verify)
        report["results"]["qamzor_qzra"] = {
            "archive_bytes": qz_archive.stat().st_size,
            "create_seconds": round(qz_seconds, 6),
            "verify_seconds": round(verify_seconds, 6),
            "verification": verify_result,
            "writer": qz_result,
        }

        zip_archive = work / "fixture.zip"
        def create_zip():
            with zipfile.ZipFile(zip_archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                for path in inputs:
                    archive.write(path, arcname=path.name)
        _, zip_seconds = timed(create_zip)
        def verify_zip():
            with zipfile.ZipFile(zip_archive, "r") as archive:
                bad = archive.testzip()
                if bad is not None:
                    raise RuntimeError(f"ZIP verification failed at {bad}")
                return {"valid": True, "files_checked": len(archive.namelist())}
        zip_verify, zip_verify_seconds = timed(verify_zip)
        report["results"]["zip_deflate"] = {
            "archive_bytes": zip_archive.stat().st_size,
            "create_seconds": round(zip_seconds, 6),
            "verify_seconds": round(zip_verify_seconds, 6),
            "verification": zip_verify,
        }

        seven_zip = shutil.which("7z") or shutil.which("7zz")
        helper = None
        seven_engine = None
        seven_version = None
        if seven_zip:
            seven_engine = seven_zip
        elif shutil.which("cc") and (ROOT / "tools" / "libarchive_7z_helper.c").exists():
            candidate = work / "libarchive-7z-helper"
            compile_result = subprocess.run(
                ["cc", "-O2", "-Wall", "-Wextra", str(ROOT / "tools" / "libarchive_7z_helper.c"), "-larchive", "-o", str(candidate)],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            if compile_result.returncode == 0:
                helper = candidate
                seven_engine = str(candidate)
                version_result = subprocess.run(["pkg-config", "--modversion", "libarchive"], capture_output=True, text=True)
                seven_version = version_result.stdout.strip() if version_result.returncode == 0 else "unknown"
        if seven_engine:
            seven_archive = work / "fixture.7z"
            if helper:
                _, seven_seconds = timed(lambda: subprocess.run(
                    [str(helper), "create", str(seven_archive), *[str(path) for path in inputs]],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True, text=True,
                ))
                seven_test = subprocess.run([str(helper), "test", str(seven_archive)], capture_output=True, text=True, check=True)
                seven_verification = {"valid": True, "output": seven_test.stdout.strip()}
            else:
                _, seven_seconds = timed(lambda: subprocess.run(
                    [seven_zip, "a", "-t7z", "-mx=5", str(seven_archive), *[str(path) for path in inputs]],
                    cwd=fixture,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    check=True,
                    text=True,
                ))
                seven_test = subprocess.run([seven_zip, "t", str(seven_archive)], stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, check=True, text=True)
                seven_verification = {"valid": True, "engine": "7z/7zz CLI"}
            report["results"]["7z"] = {
                "engine": "libarchive 7zip writer" if helper else "7z/7zz CLI",
                "engine_version": seven_version,
                "archive_bytes": seven_archive.stat().st_size,
                "create_seconds": round(seven_seconds, 6),
                "verification": seven_verification,
            }
        else:
            report["results"]["7z"] = {"available": False, "reason": "Neither 7z/7zz nor a buildable libarchive adapter is available"}

        encoded = json.dumps(report, ensure_ascii=False, indent=2)
        print(encoded)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
