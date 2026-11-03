# QAMZOR Android — Native QZRA Conversion and Virtual Archive Explorer (development build)

## Convert files and folders into the native QZRA format

QZRA is QAMZOR's archive/container format. The conversion operation losslessly packages ordinary files and folders into one `.qzra` container. It does **not** transcode a JPEG into a new image codec or rewrite a DOCX as a proprietary document: original file bytes, names, extensions and relative folder paths remain unchanged inside the archive. Original source items are not modified or deleted.

## Create an archive from files and folders

1. Open **إضافة ملفات** and select one or more files.
2. Open **إضافة مجلد كامل** and choose a folder from Android's Storage Access Framework picker. The app walks the selected document tree recursively, including nested and empty folders.
3. Repeat either action to combine several files and folders in one archive. The selected-items panel shows queued top-level sources.
4. Select **تحويل المحدد إلى صيغة البرنامج QZRA**. QAMZOR packages the selected items, verifies the resulting archive, and opens Android's save-document dialog.
5. Choose the destination and filename.

The conversion output is a native `.qzra` archive. It retains each selected folder's name and relative paths beneath it. Individual files use the display names supplied by the document provider. Case-insensitive duplicate top-level names receive a numeric suffix rather than overwriting an earlier selection.

## Open and navigate an archive without unpacking it

1. Select **استعراض الأرشيف وفحصه** and choose a `.qzra` file.
2. QAMZOR reads and validates the archive header and manifest, then opens **مستكشف QZRA الافتراضي**. Opening the explorer does not extract the archive's entries.
3. Tap a folder to enter it. Use **المجلد السابق** to go up one level. The breadcrumb shows the current archive-relative path.
4. File rows show their original size. Tap a file to open its actions.
5. The explorer can read the file's compressed blocks directly from the archive. It retains only the configured preview limit in memory and checks every chunk SHA-256 plus the whole-file SHA-256 before displaying a successful preview.

The archive container itself is copied to the app's private cache because Android document providers do not consistently provide random-access streams. The files *inside* that container are not unpacked to the destination when browsing or previewing.

## In-archive previews

- Text: TXT, MD, CSV/TSV, JSON, XML, HTML/HTM, CSS, JS/TS, PY, KT, JAVA, YAML/YML, TOML, INI, properties, shell, SQL and LOG. The viewer retains up to 2 MiB. Larger files are still fully read and integrity-checked, but only the beginning is displayed; extract the item to read the rest.
- Images: PNG, JPEG, GIF, WEBP and BMP. The Android bitmap decoder displays the image when the complete file is within the 16 MiB preview limit. Larger images are integrity-checked but must be extracted for full viewing.
- PDF: the first page is rendered with Android's `PdfRenderer` when the complete PDF is within the 16 MiB preview limit. The temporary PDF used by the platform renderer is kept in private cache and removed when the preview is closed.
- Other binary/document formats are integrity-checked but are not rendered by the built-in viewer. Choose **استخراج الملف** to open them with an installed application. DOCX/XLSX/ODT and video/audio preview are not implemented in this build.

For each successful preview, QAMZOR decodes the entire selected file chunk-by-chunk to verify its full digest, even when it only retains a bounded prefix for display. This is not a full archive extraction, although reading a large selected file still consumes time proportional to that file's size.

## Selective and complete extraction

- File: tap a file, then choose **استخراج هذا الملف**.
- Folder: long-press a folder and choose **استخراج هذا المجلد فقط**.
- Everything: use **استخراج الكل** in the explorer, or the main **استخراج الأرشيف بالكامل** action.

Choose an Android destination folder. Selected extraction expands only the requested file or directory subtree while preserving its archive-relative directory path. Full extraction recreates the complete tree, including explicit empty directories. Existing destination items are not overwritten by default; choose an empty destination folder to avoid collisions.

## Encrypted archives

The Kotlin core reads QZRA 1.2 using the format's scrypt parameters and AES-256-GCM manifest/chunk authentication. When an encrypted archive is opened, QAMZOR asks for its password. The manifest is authenticated before listing, and every file's authenticated chunks and SHA-256 digests are checked when that file is previewed or extracted. A wrong password or modified ciphertext is rejected.

The Android writer in this development build still creates unencrypted QZRA 1.1 archives. Password-protected archive creation, detached signatures, recovery sidecars and backup repositories are not exposed in the Android UI yet.

## Current limitations and validation status

- This is a development source update. A new APK must be built by the GitHub Actions `Platform builds` workflow and installed on a device before the new explorer can be considered device-validated.
- Kotlin JUnit regression tests have been added for virtual directory navigation, bounded verified previews, selected file/folder extraction, encrypted QZRA 1.2 interoperability and wrong-password rejection. They have not been run in this offline workspace because Gradle and dependency caches are not installed here.
- QZRA 1.0 legacy blocks larger than 16 MiB are rejected by the Kotlin reader to keep memory bounded.
- The archive container is staged in private cache. Very large archives therefore need sufficient free cache storage; low-storage and lifecycle-resume testing remain required.
- SAF providers vary. Some providers restrict access to protected locations or expose virtual documents that cannot be streamed.
- The in-app PDF preview shows the first page only. Office document rendering and a mountable virtual filesystem are future work.


## Native QZRA conversion from other clients

- CLI: `qz convert output.qzra file1.bin folder/` performs the same lossless packaging. The `create` command remains available as a compatible synonym.
- Kotlin SDK: `QamzorClient.convertToQzra(destination, inputs)` exposes the explicit conversion operation.
- Desktop: **Convert files/folders to QZRA** accepts multiple files and directories, writes a `.qzra` file and performs a post-write integrity verification.
