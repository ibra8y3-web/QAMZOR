"""Metadata-based archive comparison without extracting archive contents."""
from __future__ import annotations

from .format import QZRAReader


def compare_archives(
    left_path,
    right_path,
    *,
    left_password: str | None = None,
    right_password: str | None = None,
    left_folder_passwords: dict[str, str] | None = None,
    right_folder_passwords: dict[str, str] | None = None,
    verify_content: bool = True,
) -> dict:
    """Compare two archives using SHA-256 values from their validated manifests.

    By default, every file is fully decoded and hashed before comparing the
    manifests, so a stale or forged digest cannot make changed content appear
    equal. QZRA 1.3 master and protected-folder passwords can be supplied
    independently for each archive. Set ``verify_content=False`` only for a
    fast metadata-only inventory comparison.
    """
    with QZRAReader(
        left_path, password=left_password, folder_passwords=left_folder_passwords
    ) as left, QZRAReader(
        right_path, password=right_password, folder_passwords=right_folder_passwords
    ) as right:
        left_verification = left.verify() if verify_content else None
        right_verification = right.verify() if verify_content else None
        left_entries = {entry.path: entry for entry in left.list_entries()}
        right_entries = {entry.path: entry for entry in right.list_entries()}
        added = []
        removed = []
        changed = []
        unchanged = []
        for path in sorted(right_entries.keys() - left_entries.keys()):
            item = right_entries[path]
            added.append({"path": path, "type": item.kind, "size": item.size})
        for path in sorted(left_entries.keys() - right_entries.keys()):
            item = left_entries[path]
            removed.append({"path": path, "type": item.kind, "size": item.size})
        for path in sorted(left_entries.keys() & right_entries.keys()):
            old = left_entries[path]
            new = right_entries[path]
            if old.kind != new.kind or old.size != new.size or old.sha256 != new.sha256:
                changed.append({
                    "path": path,
                    "before": {"type": old.kind, "size": old.size, "sha256": old.sha256},
                    "after": {"type": new.kind, "size": new.size, "sha256": new.sha256},
                })
            else:
                unchanged.append(path)
        return {
            "left": str(left.path),
            "right": str(right.path),
            "left_version": f"{left.version_major}.{left.version_minor}",
            "right_version": f"{right.version_major}.{right.version_minor}",
            "comparison_mode": "verified-content-sha256" if verify_content else "manifest-metadata-only",
            "content_verified": bool(verify_content),
            "left_verification": left_verification,
            "right_verification": right_verification,
            "added": added,
            "removed": removed,
            "changed": changed,
            "unchanged": unchanged,
            "summary": {
                "added": len(added), "removed": len(removed),
                "changed": len(changed), "unchanged": len(unchanged),
            },
        }
