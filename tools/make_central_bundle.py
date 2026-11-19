#!/usr/bin/env python3
"""Turn Gradle's local staging repository into a Maven Central Portal upload bundle.

Usage: make_central_bundle.py <staging-repo-dir> <output.zip> --group org.qamzor --version 0.8.0 \
           --artifacts qamzor-core qamzor-sdk qamzor-java qamzor-android

The tool never signs anything: every publishable file must already have a detached .asc signature
(Gradle's `signing` plugin creates them when the build runs with -Prelease and a real key).
It adds the .md5/.sha1/.sha256 checksums that Central requires, drops maven-metadata files, and
refuses to produce a bundle if any required file or signature is missing.
"""
from __future__ import annotations
import argparse, hashlib, sys, zipfile
from pathlib import Path

CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")


def digests(data: bytes) -> dict[str, str]:
    return {"md5": hashlib.md5(data).hexdigest(), "sha1": hashlib.sha1(data).hexdigest(), "sha256": hashlib.sha256(data).hexdigest()}


def required_files(artifact: str, version: str, packaging_candidates: tuple[str, ...]) -> list[list[str]]:
    base = f"{artifact}-{version}"
    return [[f"{base}{ext}" for ext in packaging_candidates], [f"{base}.pom"], [f"{base}-sources.jar"], [f"{base}-javadoc.jar"]]


def build(repo: Path, out: Path, group: str, version: str, artifacts: list[str]) -> list[str]:
    problems: list[str] = []
    members: list[tuple[str, bytes]] = []
    for artifact in artifacts:
        directory = repo.joinpath(*group.split("."), artifact, version)
        if not directory.is_dir():
            problems.append(f"missing directory {directory}")
            continue
        for alternatives in required_files(artifact, version, (".jar", ".aar")):
            present = [name for name in alternatives if (directory / name).is_file()]
            if not present:
                problems.append(f"{artifact}: none of {alternatives} found")
        for path in sorted(p for p in directory.iterdir() if p.is_file()):
            name = path.name
            if name.startswith("maven-metadata") or name.endswith(CHECKSUM_SUFFIXES):
                continue
            if name.endswith(".asc"):
                if not (directory / name[:-4]).is_file():
                    problems.append(f"{artifact}: signature without file: {name}")
                data = path.read_bytes()
                members.append((f"{'/'.join(group.split('.'))}/{artifact}/{version}/{name}", data))
                continue
            if not (directory / (name + ".asc")).is_file():
                problems.append(f"{artifact}: {name} has no detached signature ({name}.asc)")
            data = path.read_bytes()
            prefix = f"{'/'.join(group.split('.'))}/{artifact}/{version}/{name}"
            members.append((prefix, data))
            for algo, hexdigest in digests(data).items():
                members.append((f"{prefix}.{algo}", hexdigest.encode("ascii")))
    if problems:
        return problems
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as bundle:
        for member, data in sorted(members):
            bundle.writestr(member, data)
    return []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--group", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--artifacts", nargs="+", required=True)
    args = ap.parse_args()
    if args.version.endswith("SNAPSHOT"):
        print("refusing: SNAPSHOT versions cannot be released", file=sys.stderr)
        return 2
    problems = build(args.repo, args.output, args.group, args.version, args.artifacts)
    for problem in problems:
        print("ERROR:", problem, file=sys.stderr)
    if not problems:
        print(f"wrote {args.output}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
