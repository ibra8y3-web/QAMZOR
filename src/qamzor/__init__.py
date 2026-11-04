"""QAMZOR: open archive tooling and the QZRA reference format."""
from .format import (
    ArchiveChunk,
    ArchiveEntry,
    FormatError,
    IntegrityError,
    QamzorError,
    QZRAReader,
    SafetyError,
    create_archive,
    open_archive,
)
from .recovery import RecoveryError, create_recovery_file, repair_archive
from .signatures import generate_signing_keypair, sign_archive, verify_archive_signature
from .repository import Repository, RepositoryError, RepositoryIntegrityError
from .compare import compare_archives
from .volumes import split_archive, join_archive
from .office_preview import preview_office_document
from .cloud import WebDavBackup
from .cloud_providers import GoogleDriveBackup, DropboxBackup, OneDriveBackup, S3Backup
from .jobs import JobCenter, JobControl, JobCancelledError

__version__ = "0.9.0"

__all__ = [
    "ArchiveChunk", "ArchiveEntry", "FormatError", "IntegrityError", "QamzorError",
    "QZRAReader", "RecoveryError", "SafetyError", "create_archive", "open_archive",
    "create_recovery_file", "repair_archive", "generate_signing_keypair", "sign_archive",
    "verify_archive_signature", "Repository", "RepositoryError", "RepositoryIntegrityError",
    "compare_archives", "split_archive", "join_archive", "preview_office_document",
    "WebDavBackup", "GoogleDriveBackup", "DropboxBackup", "OneDriveBackup", "S3Backup",
    "JobCenter", "JobControl", "JobCancelledError",
]

