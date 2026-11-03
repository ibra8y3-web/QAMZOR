# QAMZOR native QZRA conversion

## Meaning of conversion

QZRA is an archive/container format. `convertToQzra` packages one or more ordinary files and/or directory trees into a single `.qzra` container. It is a lossless packaging operation, not a codec or document-format transcoder.

For example, converting `photo.jpg`, `report.pdf` and `Project/` creates `bundle.qzra` containing those same entries with the same original bytes, names, extensions and relative paths. The original source files are not modified or deleted. They can later be browsed inside the virtual archive explorer or extracted in full/part.

It does not turn the JPEG data into a QZRA image format, rewrite PDF internals, or make a `.docx` directly readable by applications that expect `.qzra`. The `.qzra` file is the container that holds the original files.

## Android

1. Select one or more files with **إضافة ملفات**.
2. Add complete directory trees with **إضافة مجلد كامل**.
3. Press **تحويل المحدد إلى صيغة البرنامج QZRA**.
4. QAMZOR writes the `.qzra` archive, verifies all entries, and opens Android's save-file dialog.
5. Save the resulting archive. The source files remain unchanged.

## Command line

```bash
qz convert bundle.qzra photo.jpg report.pdf Project/
qz verify bundle.qzra
qz list bundle.qzra
```

`qz create` remains supported as a compatible synonym. The explicit `convert` command requires a `.qzra` output extension.

## Kotlin

```kotlin
val result = QamzorClient().convertToQzra(
    destination = File("bundle.qzra"),
    inputs = listOf(File("photo.jpg"), File("Project"))
)
```

The shared engine preserves directory entries, including empty directories, and writes file data through the QZRA chunking/compression/deduplication pipeline. It rejects symbolic-link sources and unsafe/colliding archive paths.
