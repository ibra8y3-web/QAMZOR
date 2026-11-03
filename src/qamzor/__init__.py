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

__version__ = "0.1.0-dev"

__all__ = [
    "ArchiveChunk", "ArchiveEntry", "FormatError", "IntegrityError", "QamzorError",
    "QZRAReader", "RecoveryError", "SafetyError", "create_archive", "open_archive",
    "create_recovery_file", "repair_archive", "generate_signing_keypair", "sign_archive",
    "verify_archive_signature", "Repository", "RepositoryError", "RepositoryIntegrityError",
]

