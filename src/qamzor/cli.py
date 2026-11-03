from __future__ import annotations

import argparse
import getpass
import json
import sys
from pathlib import Path

from .format import HEADER, HEADER_SIZE, QamzorError, create_archive, open_archive
from .recovery import create_recovery_file, repair_archive
from .signatures import generate_signing_keypair, sign_archive, verify_archive_signature
from .repository import Repository
from .compare import compare_archives
from .volumes import split_archive, join_archive
from .cloud import WebDavBackup
from .cloud_providers import GoogleDriveBackup, DropboxBackup, OneDriveBackup, S3Backup
from .office_preview import preview_office_document


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="qz", description="QAMZOR command-line tools for QZRA archives")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", aliases=["convert"], help="convert files/folders into a native QZRA archive")
    create.add_argument("archive", help="output .qzra file")
    create.add_argument("sources", nargs="+", help="source files or directories")
    create.add_argument("--profile", choices=("speed", "balanced", "maximum"), default="balanced", help="compression profile")
    create.add_argument("--level", type=int, choices=range(0, 10), help="override profile zlib level (0-9)")
    create.add_argument("--overwrite", action="store_true", help="replace an existing archive")
    create.add_argument("--workers", type=int, default=1, help="bounded compression workers (1-32)")
    create.add_argument("--encrypt", action="store_true", help="encrypt archive payloads with AES-256-GCM (QZRA 1.2)")
    create.add_argument("--hide-metadata", action="store_true", help="write QZRA 1.3 with encrypted filenames, folder names and metadata")
    create.add_argument("--protect-folder", action="append", default=[], metavar="ARCHIVE_PATH", help="protect a directory with an independent password (forces QZRA 1.3)")
    create.add_argument("--exclude", action="append", default=[], metavar="PATTERN", help="exclude a path pattern; repeat as needed")
    create.add_argument("--exclude-from", help="read additional UTF-8 exclusion patterns from a file")

    listing = sub.add_parser("list", help="list archive entries")
    listing.add_argument("archive")
    listing.add_argument("--password-file", help="read QZRA 1.3 master password from a protected text file")

    inspect = sub.add_parser("inspect", help="show archive metadata")
    inspect.add_argument("archive")
    inspect.add_argument("--password-file", help="read QZRA 1.3 master password from a protected text file")

    verify = sub.add_parser("verify", help="verify all stored file data")
    verify.add_argument("archive")
    verify.add_argument("--password-file", help="read the archive master password from a protected text file")
    verify.add_argument("--folder-password-file", action="append", default=[], metavar="PATH=FILE", help="folder password file; can be repeated")

    extract = sub.add_parser("extract", help="extract all or selected archive entries")
    extract.add_argument("archive")
    extract.add_argument("--output", "-o", default=".", help="destination directory")
    extract.add_argument("--file", action="append", dest="selected", help="archive path to extract; can be repeated")
    extract.add_argument("--overwrite", action="store_true", help="replace existing regular files")
    extract.add_argument("--max-total-bytes", type=int, help="refuse extraction above this total uncompressed size")
    extract.add_argument("--max-files", type=int, help="refuse extraction above this number of files")
    extract.add_argument("--password-file", help="read the archive master password from a protected text file")
    extract.add_argument("--folder-password-file", action="append", default=[], metavar="PATH=FILE", help="folder password file; can be repeated")

    search = sub.add_parser("search", help="search archive paths without extracting")
    search.add_argument("archive")
    search.add_argument("query", nargs="?", default="", help="case-insensitive path substring")
    search.add_argument("--kind", choices=("file", "directory"))
    search.add_argument("--password-file", help="read QZRA 1.3 master password from a protected text file")

    preview = sub.add_parser("preview", help="verify and preview a file without extracting it")
    preview.add_argument("archive")
    preview.add_argument("path", help="exact archive path")
    preview.add_argument("--max-bytes", type=int, default=65536, help="maximum preview bytes (0-16 MiB)")
    preview.add_argument("--encoding", default="utf-8", help="text encoding for display")
    preview.add_argument("--errors", choices=("strict", "replace", "ignore"), default="replace")
    preview.add_argument("--password-file", help="read the archive master password from a protected text file")
    preview.add_argument("--folder-password-file", action="append", default=[], metavar="PATH=FILE", help="folder password file; can be repeated")
    preview.add_argument("--office", action="store_true", help="extract text from DOCX, XLSX or PPTX without writing the document to disk")

    compare = sub.add_parser("compare", help="compare two QZRA archives without extracting")
    compare.add_argument("left")
    compare.add_argument("right")
    compare.add_argument("--left-password-file")
    compare.add_argument("--right-password-file")
    compare.add_argument("--left-folder-password-file", action="append", default=[], metavar="PATH=FILE")
    compare.add_argument("--right-folder-password-file", action="append", default=[], metavar="PATH=FILE")
    compare.add_argument("--metadata-only", action="store_true", help="skip payload decoding; compare manifest metadata and stored digests only")

    split = sub.add_parser("split", help="split a QZRA archive into integrity-checked volumes")
    split.add_argument("archive")
    split.add_argument("output_directory")
    split.add_argument("--part-size-mib", type=int, default=100)
    split.add_argument("--overwrite", action="store_true")

    join = sub.add_parser("join", help="reassemble a QZRA split set and verify every volume")
    join.add_argument("set_file")
    join.add_argument("output")
    join.add_argument("--overwrite", action="store_true")

    cloud = sub.add_parser("cloud", help="push/pull QZRA backups through a WebDAV HTTPS collection")
    cloud_sub = cloud.add_subparsers(dest="cloud_command", required=True)
    cloud_push = cloud_sub.add_parser("push", help="upload a QZRA archive and its SHA-256 manifest")
    cloud_push.add_argument("archive")
    cloud_push.add_argument("--url", required=True, help="HTTPS WebDAV collection URL")
    cloud_push.add_argument("--username", required=True)
    cloud_push.add_argument("--password-file")
    cloud_push.add_argument("--remote-name")
    cloud_pull = cloud_sub.add_parser("pull", help="download and verify a QZRA cloud backup")
    cloud_pull.add_argument("remote_name")
    cloud_pull.add_argument("output")
    cloud_pull.add_argument("--url", required=True)
    cloud_pull.add_argument("--username", required=True)
    cloud_pull.add_argument("--password-file")
    cloud_pull.add_argument("--overwrite", action="store_true")

    cloud_provider = sub.add_parser("cloud-provider", help="push/pull verified QZRA backups through Drive, Dropbox, OneDrive or S3")
    cloud_provider_sub = cloud_provider.add_subparsers(dest="provider_command", required=True)
    cloud_provider_push = cloud_provider_sub.add_parser("push", help="upload a QZRA archive and publish its SHA-256 metadata sidecar")
    cloud_provider_push.add_argument("provider", choices=("google-drive", "dropbox", "onedrive", "s3"))
    cloud_provider_push.add_argument("archive")
    cloud_provider_push.add_argument("--remote-name")
    cloud_provider_pull = cloud_provider_sub.add_parser("pull", help="download and verify a cloud backup before publishing it locally")
    cloud_provider_pull.add_argument("provider", choices=("google-drive", "dropbox", "onedrive", "s3"))
    cloud_provider_pull.add_argument("remote_reference", help="provider object ID (Drive/OneDrive), Dropbox path, or S3 key")
    cloud_provider_pull.add_argument("output")
    cloud_provider_pull.add_argument("--metadata-reference", help="metadata sidecar ID (required for Drive and OneDrive)")
    cloud_provider_pull.add_argument("--overwrite", action="store_true")

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
        if args.command == "split":
            result = split_archive(args.archive, args.output_directory,
                                   part_size=args.part_size_mib * 1024 * 1024,
                                   overwrite=args.overwrite)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "join":
            result = join_archive(args.set_file, args.output, overwrite=args.overwrite)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "compare":
            left_password = Path(args.left_password_file).read_text(encoding="utf-8").rstrip("\r\n") if args.left_password_file else None
            right_password = Path(args.right_password_file).read_text(encoding="utf-8").rstrip("\r\n") if args.right_password_file else None

            def read_folder_passwords(values):
                passwords = {}
                for specification in values:
                    if "=" not in specification:
                        raise QamzorError("Folder password option must use PATH=FILE")
                    path, filename = specification.split("=", 1)
                    if not path or not filename or path in passwords:
                        raise QamzorError("Folder password option has an empty or duplicate path/file")
                    passwords[path] = Path(filename).read_text(encoding="utf-8").rstrip("\r\n")
                return passwords

            result = compare_archives(
                args.left, args.right, left_password=left_password, right_password=right_password,
                left_folder_passwords=read_folder_passwords(args.left_folder_password_file),
                right_folder_passwords=read_folder_passwords(args.right_folder_password_file),
                verify_content=not args.metadata_only,
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "cloud":
            password = Path(args.password_file).read_text(encoding="utf-8").rstrip("\r\n") if args.password_file else getpass.getpass("WebDAV password: ")
            store = WebDavBackup(args.url, args.username, password)
            if args.cloud_command == "push":
                result = store.push(args.archive, remote_name=args.remote_name)
            else:
                result = store.pull(args.remote_name, args.output, overwrite=args.overwrite)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "cloud-provider":
            import os
            provider = args.provider
            if provider == "google-drive":
                token = os.environ.get("QAMZOR_GOOGLE_DRIVE_ACCESS_TOKEN", "")
                store = GoogleDriveBackup(token)
            elif provider == "dropbox":
                token = os.environ.get("QAMZOR_DROPBOX_ACCESS_TOKEN", "")
                store = DropboxBackup(token)
            elif provider == "onedrive":
                token = os.environ.get("QAMZOR_ONEDRIVE_ACCESS_TOKEN", "")
                store = OneDriveBackup(token)
            else:
                store = S3Backup(
                    os.environ.get("QAMZOR_S3_BUCKET", ""),
                    os.environ.get("QAMZOR_S3_ACCESS_KEY", ""),
                    os.environ.get("QAMZOR_S3_SECRET_KEY", ""),
                    os.environ.get("QAMZOR_S3_REGION", ""),
                    session_token=os.environ.get("QAMZOR_S3_SESSION_TOKEN") or None,
                    endpoint_url=os.environ.get("QAMZOR_S3_ENDPOINT_URL") or None,
                )
            if args.provider_command == "push":
                result = store.push(args.archive, remote_name=args.remote_name)
            else:
                result = store.pull(
                    args.remote_reference, args.output, overwrite=args.overwrite,
                    metadata_reference=args.metadata_reference,
                )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
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
        if args.command in {"create", "convert"}:
            if args.command == "convert" and Path(args.archive).suffix.lower() != ".qzra":
                raise QamzorError("Native QAMZOR conversion output must use the .qzra extension")
            use_v13 = bool(args.hide_metadata or args.protect_folder)
            password = None
            folder_passwords = {}
            if args.encrypt or use_v13:
                password = getpass.getpass("New QAMZOR archive master password: ")
                confirmation = getpass.getpass("Confirm archive master password: ")
                if not password or password != confirmation:
                    raise QamzorError("Archive master passwords must be non-empty and match")
            for folder_path in args.protect_folder:
                folder_password = getpass.getpass(f"New independent password for folder {folder_path}: ")
                confirmation = getpass.getpass(f"Confirm password for folder {folder_path}: ")
                if not folder_password or folder_password != confirmation:
                    raise QamzorError(f"Folder passwords must be non-empty and match: {folder_path}")
                folder_passwords[folder_path] = folder_password
            exclude_patterns = list(args.exclude)
            if args.exclude_from:
                exclude_patterns.extend(Path(args.exclude_from).read_text(encoding="utf-8").splitlines())
            result = create_archive(
                args.archive,
                args.sources,
                exclude_patterns=exclude_patterns,
                compression_level=args.level,
                profile=args.profile,
                overwrite=args.overwrite,
                workers=args.workers,
                password=password,
                encrypt_metadata=args.hide_metadata,
                folder_passwords=folder_passwords,
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0

        # QZRA 1.3 encrypts the manifest, so even list/inspect/search need the
        # master password. Legacy 1.2 keeps names visible and only data actions
        # require the password.
        with Path(args.archive).open("rb") as header_file:
            raw_header = header_file.read(HEADER_SIZE)
        if len(raw_header) != HEADER_SIZE:
            raise QamzorError("File is too small to be a QZRA archive")
        try:
            _, major, minor, _, _, _, _ = HEADER.unpack(raw_header)
        except Exception as exc:
            raise QamzorError("Could not read QZRA archive header") from exc
        password_file = getattr(args, "password_file", None)
        needs_master = minor == 3 or (minor == 2 and args.command in {"verify", "extract", "preview"})
        password = None
        if password_file:
            password = Path(password_file).read_text(encoding="utf-8").rstrip("\r\n")
        elif needs_master:
            password = getpass.getpass("QAMZOR archive master password: ")

        folder_passwords = {}
        for item in getattr(args, "folder_password_file", []):
            if "=" not in item:
                raise QamzorError("Folder password file must use PATH=FILE syntax")
            folder_path, filename = item.split("=", 1)
            if not folder_path or not filename:
                raise QamzorError("Folder password file must use PATH=FILE syntax")
            folder_passwords[folder_path] = Path(filename).read_text(encoding="utf-8").rstrip("\r\n")

        with open_archive(args.archive, password=password) as archive:
            if args.command in {"verify", "extract"} and archive.version_minor == 3:
                for folder_path in archive._folder_scopes:
                    if folder_path not in folder_passwords:
                        folder_passwords[folder_path] = getpass.getpass(f"Password for protected folder {folder_path}: ")
            elif args.command == "preview" and archive.version_minor == 3:
                path = args.path
                matches = [p for p in archive._folder_scopes if path.startswith(p + "/")]
                if matches:
                    folder_path = max(matches, key=len)
                    if folder_path not in folder_passwords:
                        folder_passwords[folder_path] = getpass.getpass(f"Password for protected folder {folder_path}: ")

            if args.command == "list":
                for entry in archive.list_entries():
                    marker = "DIR " if entry.kind == "directory" else "FILE"
                    print(f"{marker}\t{entry.size}\t{entry.path}")
            elif args.command == "inspect":
                print(json.dumps(archive.inspect(), ensure_ascii=False, indent=2))
            elif args.command == "verify":
                print(json.dumps(archive.verify(folder_passwords=folder_passwords), ensure_ascii=False, indent=2))
            elif args.command == "extract":
                paths = archive.extract(
                    args.output,
                    selected=args.selected,
                    overwrite=args.overwrite,
                    max_total_bytes=args.max_total_bytes,
                    max_files=args.max_files,
                    folder_passwords=folder_passwords,
                )
                print(f"Extracted {len(paths)} entries to {Path(args.output).resolve()}")
            elif args.command == "search":
                matches = archive.find_entries(args.query, kind=args.kind)
                for entry in matches:
                    print(f"{entry.kind.upper()}\t{entry.size}\t{entry.path}")
                print(f"{len(matches)} match(es)", file=sys.stderr)
            elif args.command == "preview":
                if args.office:
                    for folder_path, folder_password in folder_passwords.items():
                        archive.set_folder_password(folder_path, folder_password)
                    result = preview_office_document(archive, args.path)
                    print(result["text"])
                else:
                    result = archive.preview(args.path, max_bytes=args.max_bytes, folder_passwords=folder_passwords)
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
    except (QamzorError, OSError, UnicodeError) as exc:
        print(f"qz: error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
