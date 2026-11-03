# QAMZOR feature ideas

Prioritized additions after folder ingestion and archive-password support:

1. **Encrypted metadata / hidden filenames (QZRA 1.3 design):** encrypt the manifest and directory names, not just payload blocks; define a versioned key envelope, authenticated header, and cross-language test vectors first.
2. **Per-folder security profiles:** independent passwords or key slots per selected subtree. This requires a new format extension and explicit UX for mixed protected/unprotected entries.
3. **Background job center:** bounded workers, progress per file, pause/cancel, recoverable staging, and a persistent operation journal.
4. **Smart exclusions:** ignore patterns, hidden/system-file toggle, extension filters, and preview of included/excluded counts before archive creation.
5. **Archive compare and update:** compare two QZRA archives, show added/removed/changed items, and apply a safe incremental update without rebuilding unchanged chunks.
6. **Split volumes:** split large archives into numbered parts with a verified join process and clear missing-part diagnostics.
7. **Archive repair assistant:** verify blocks, identify damaged ranges, and repair only from recovery sidecars or another verified copy; never silently invent bytes.
8. **Document and media preview:** paginated PDF, DOCX/XLSX/PPTX preview, audio/video streaming from archive chunks with explicit codec and memory limits.
9. **Secure sharing:** signed archive manifests, trusted public-key verification, password sharing guidance, optional expiry metadata, and no password transmission in logs.
10. **Cloud and removable-drive backups:** pluggable destinations, bandwidth limits, resumable transfer, retention policies, and restore drills.
11. **Duplicate intelligence across archives:** content-addressed repository backed by explicit quota and garbage-collection preview.
12. **Accessibility and localization:** screen-reader labels, dynamic font scaling, complete RTL/LTR, keyboard navigation, and Arabic/English strings.

Do not label a feature complete until the format behavior is specified, implementation is wired into the real UI, and tests cover success, corruption, wrong-password, resource limits, and cross-platform interoperability.
