from __future__ import annotations

import argparse
import getpass
import json
import sys
from pathlib import Path

from .format import QamzorError, create_archive, open_archive
from .recovery import create_recovery_file, repair_archive
from .signatures import generate_signing_keypair, sign_archive, verify_archive_signature
from .repository import Repository


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="qz", description="QAMZOR command-line tools for QZRA archives")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", help="create a QZRA archive")
    create.add_argument("archive", help="output .qzra file")
    create.add_argument("sources", nargs="+", help="source files or directories")
    create.add_argument("--profile", choices=("speed", "balanced", "maximum"), default="balanced", help="compression profile")
    create.add_argument("--level", type=int, choices=range(0, 10), help="override profile zlib level (0-9)")
    create.add_argument("--overwrite", action="store_true", help="replace an existing archive")
    create.add_argument("--workers", type=int, default=1, help="bounded compression workers (1-32)")
    create.add_argument("--encrypt", action="store_true", help="encrypt archive payloads with AES-256-GCM (requires qamzor[crypto])")

    listing = sub.add_parser("list", help="list archive entries")
    listing.add_argument("archive")

    inspect = sub.add_parser("inspect", help="show archive metadata")
    inspect.add_argument("archive")

    verify = sub.add_parser("verify", help="verify all stored file data")
    verify.add_argument("archive")
    verify.add_argument("--password-file", help="read the encryption password from a protected text file")

    extract = sub.add_parser("extract", help="extract all or selected archive entries")
    extract.add_argument("archive")
    extract.add_argument("--output", "-o", default=".", help="destination directory")
    extract.add_argument("--file", action="append", dest="selected", help="archive path to extract; can be repeated")
    extract.add_argument("--overwrite", action="store_true", help="replace existing regular files")
    extract.add_argument("--max-total-bytes", type=int, help="refuse extraction above this total uncompressed size")
    extract.add_argument("--max-files", type=int, help="refuse extraction above this number of files")
    extract.add_argument("--password-file", help="read the encryption password from a protected text file")

    search = sub.add_parser("search", help="search archive paths without extracting")
    search.add_argument("archive")
    search.add_argument("query", nargs="?", default="", help="case-insensitive path substring")
    search.add_argument("--kind", choices=("file", "directory"))

    preview = sub.add_parser("preview", help="verify and preview a file without extracting it")
    preview.add_argument("archive")
    preview.add_argument("path", help="exact archive path")
    preview.add_argument("--max-bytes", type=int, default=65536, help="maximum preview bytes (0-16 MiB)")
    preview.add_argument("--encoding", default="utf-8", help="text encoding for display")
    preview.add_argument("--errors", choices=("strict", "replace", "ignore"), default="replace")
    preview.add_argument("--password-file", help="read the encryption password from a protected text file")

    keygen = sub.add_parser("keygen", help="generate an Ed25519 signing key pair")
    keygen.add_argument("--private-key", required=True)
    keygen.add_argument("--public-key", required=True)

    sign = sub.add_parser("sign", help="sign an archive or delta file with an Ed25519 private key")
    sign.add_argument("archive")
    sign.add_argument("--private-key", required=True)
    sign.add_argument("--signature", help="detached signature output (default: ARCHIVE.qzsig)")
    sign.add_argument("--key-password-file", help="read private-key passphrase from a protected file")
    sign.add_argument("--overwrite", action="store_true")

    verify_sig = sub.add_parser("verify-signature", help="verify a detached QAMZOR file signature")
    verify_sig.add_argument("archive")
    verify_sig.add_argument("--public-key", required=True)
    verify_sig.add_argument("--signature", required=True)

    recover = sub.add_parser("recover", help="create or apply XOR recovery sidecars")
    recover_sub = recover.add_subparsers(dest="recovery_command", required=True)
    recover_create = recover_sub.add_parser("create", help="create recovery data for an archive")
    recover_create.add_argument("archive")
    recover_create.add_argument("--output", help="sidecar path (default: ARCHIVE.qzrec)")
    recover_create.add_argument("--block-size", type=int, default=1024 * 1024)
    recover_create.add_argument("--group-size", type=int, default=8)
    recover_create.add_argument("--overwrite", action="store_true")
    recover_apply = recover_sub.add_parser("repair", help="repair a copy of an archive using recovery data")
    recover_apply.add_argument("archive")
    recover_apply.add_argument("recovery")
    recover_apply.add_argument("--output", required=True)
    recover_apply.add_argument("--overwrite", action="store_true")

    repo = sub.add_parser("repo", help="manage a local content-addressed backup repository")
    repo_sub = repo.add_subparsers(dest="repo_command", required=True)
    repo_init = repo_sub.add_parser("init", help="initialize a repository")
    repo_init.add_argument("repository")
    repo_snapshot = repo_sub.add_parser("snapshot", help="create an immutable incremental snapshot")
    repo_snapshot.add_argument("repository")
    repo_snapshot.add_argument("sources", nargs="+", help="source files or directories")
    repo_snapshot.add_argument("--message", default="")
    repo_list = repo_sub.add_parser("list", help="list repository snapshots")
    repo_list.add_argument("repository")
    repo_verify = repo_sub.add_parser("verify", help="verify snapshot manifests and stored objects")
    repo_verify.add_argument("repository")
    repo_verify.add_argument("--snapshot", help="verify one snapshot; default verifies all")
    repo_restore = repo_sub.add_parser("restore", help="restore a snapshot")
    repo_restore.add_argument("repository")
    repo_restore.add_argument("snapshot")
    repo_restore.add_argument("--output", "-o", required=True)
    repo_restore.add_argument("--overwrite", action="store_true")
    repo_restore.add_argument("--max-total-bytes", type=int)
    repo_restore.add_argument("--max-files", type=int)
    repo_prune = repo_sub.add_parser("prune", help="apply snapshot retention and collect unreferenced objects")
    repo_prune.add_argument("repository")
    repo_prune.add_argument("--keep-last", type=int, default=10)
    repo_prune.add_argument("--apply", action="store_true", help="perform deletion; without this flag only report the plan")
    repo_delta = repo_sub.add_parser("export-delta", help="export a portable delta from a snapshot to its direct child")
    repo_delta.add_argument("repository")
    repo_delta.add_argument("base_snapshot")
    repo_delta.add_argument("target_snapshot")
    repo_delta.add_argument("--output", "-o", required=True)
    repo_delta.add_argument("--overwrite", action="store_true")
    repo_apply_delta = repo_sub.add_parser("apply-delta", help="apply a delta whose base snapshot exists in this repository")
    repo_apply_delta.add_argument("repository")
    repo_apply_delta.add_argument("delta")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "keygen":
            password = getpass.getpass("New signing-key password (8+ characters): ")
            confirmation = getpass.getpass("Confirm signing-key password: ")
            if not password or password != confirmation:
                raise QamzorError("Signing-key passwords must be non-empty and match")
            print(json.dumps(generate_signing_keypair(args.private_key, args.public_key, password), indent=2))
            return 0
        if args.command == "sign":
            if args.key_password_file:
                key_password = Path(args.key_password_file).read_text(encoding="utf-8").rstrip("\r\n")
            else:
                key_password = getpass.getpass("Signing private-key password: ")
            result = sign_archive(
                args.archive,
                args.private_key,
                private_key_password=key_password,
                signature_path=args.signature,
                overwrite=args.overwrite,
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "verify-signature":
            print(json.dumps(
                verify_archive_signature(args.archive, args.public_key, args.signature),
                ensure_ascii=False,
                indent=2,
            ))
            return 0
        if args.command == "repo":
            repository = Repository(args.repository, create=(args.repo_command == "init"))
            if args.repo_command == "init":
                result = {"repository": str(repository.root), "initialized": True}
            elif args.repo_command == "snapshot":
                result = repository.create_snapshot(args.sources, message=args.message)
            elif args.repo_command == "list":
                result = repository.list_snapshots()
            elif args.repo_command == "verify":
                result = repository.verify(args.snapshot)
            elif args.repo_command == "restore":
                result = repository.restore_snapshot(
                    args.snapshot,
                    args.output,
                    overwrite=args.overwrite,
                    max_total_bytes=args.max_total_bytes,
                    max_files=args.max_files,
                )
            elif args.repo_command == "prune":
                result = repository.prune(keep_last=args.keep_last, dry_run=not args.apply)
            elif args.repo_command == "export-delta":
                result = repository.export_delta(
                    args.base_snapshot,
                    args.target_snapshot,
                    args.output,
                    overwrite=args.overwrite,
                )
            else:
                result = repository.apply_delta(args.delta)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "recover":
            if args.recovery_command == "create":
                recovery_output = args.output or (args.archive + ".qzrec")
                result = create_recovery_file(
                    args.archive,
                    recovery_output,
                    block_size=args.block_size,
                    group_size=args.group_size,
                    overwrite=args.overwrite,
                )
            else:
                result = repair_archive(
                    args.archive,
                    args.recovery,
                    args.output,
                    overwrite=args.overwrite,
                )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "create":
            password = None
            if args.encrypt:
                password = getpass.getpass("New QAMZOR archive password: ")
                confirmation = getpass.getpass("Confirm archive password: ")
                if not password or password != confirmation:
                    raise QamzorError("Passwords must be non-empty and match")
            result = create_archive(
                args.archive,
                args.sources,
                compression_level=args.level,
                profile=args.profile,
                overwrite=args.overwrite,
                workers=args.workers,
                password=password,
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        with open_archive(args.archive) as archive:
            if archive.is_encrypted and args.command in {"verify", "extract", "preview"}:
                password_file = getattr(args, "password_file", None)
                if password_file:
                    password = Path(password_file).read_text(encoding="utf-8").rstrip("\r\n")
                else:
                    password = getpass.getpass("QAMZOR archive password: ")
                archive.set_password(password)
            if args.command == "list":
                for entry in archive.list_entries():
                    marker = "DIR " if entry.kind == "directory" else "FILE"
                    print(f"{marker}\t{entry.size}\t{entry.path}")
            elif args.command == "inspect":
                print(json.dumps(archive.inspect(), ensure_ascii=False, indent=2))
            elif args.command == "verify":
                print(json.dumps(archive.verify(), ensure_ascii=False, indent=2))
            elif args.command == "extract":
                paths = archive.extract(
                    args.output,
                    selected=args.selected,
                    overwrite=args.overwrite,
                    max_total_bytes=args.max_total_bytes,
                    max_files=args.max_files,
                )
                print(f"Extracted {len(paths)} entries to {Path(args.output).resolve()}")
            elif args.command == "search":
                matches = archive.find_entries(args.query, kind=args.kind)
                for entry in matches:
                    print(f"{entry.kind.upper()}\t{entry.size}\t{entry.path}")
                print(f"{len(matches)} match(es)", file=sys.stderr)
            elif args.command == "preview":
                result = archive.preview(args.path, max_bytes=args.max_bytes)
                try:
                    rendered = result["data"].decode(args.encoding, errors=args.errors)
                except LookupError as exc:
                    raise QamzorError(f"Unknown text encoding: {args.encoding}") from exc
                print(rendered, end="" if rendered.endswith("\n") else "\n")
                if result["truncated"]:
                    print(
                        f"[preview limited to {result['bytes_previewed']} of {result['bytes_total']} bytes; full file verified]",
                        file=sys.stderr,
                    )
        return 0
    except (QamzorError, OSError) as exc:
        print(f"qz: error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
