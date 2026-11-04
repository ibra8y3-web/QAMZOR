# QAMZOR Kotlin SDK (pre-1.0)

The `:sdk` Gradle module exposes `org.qamzor.sdk.QamzorClient` for Kotlin/JVM consumers. It delegates to the shared Kotlin core and currently supports creating QZRA 1.1 archives, listing entries, verifying archives and extracting verified files.

```kotlin
val qamzor = QamzorClient()
val created = qamzor.createArchive(File("backup.qzra"), listOf(File("documents")))
val verification = qamzor.verifyArchive(created.archive)
val entries = qamzor.listEntries(created.archive)
val extracted = qamzor.extractArchive(created.archive, File("restore"))
```

The SDK is version `0.9.0`; it is not a stable API/ABI promise. It exposes QZRA 1.2 archive encryption and QZRA 1.3 encrypted metadata/independent folder-password APIs through the Kotlin Core. Detached signatures, recovery sidecars, delta repositories, remote cloud-provider adapters, enterprise identity and fleet administration are not yet part of the Kotlin SDK.


The SDK exposes `convertToQzra(destination, inputs)` for losslessly packaging ordinary files and folders in the native `.qzra` container. It preserves source bytes and paths; it is not a document/image/audio/video transcoder.
