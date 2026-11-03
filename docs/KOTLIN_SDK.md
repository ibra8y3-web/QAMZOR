# QAMZOR Kotlin SDK (pre-1.0)

The `:sdk` Gradle module exposes `org.qamzor.sdk.QamzorClient` for Kotlin/JVM consumers. It delegates to the shared Kotlin core and currently supports creating QZRA 1.1 archives, listing entries, verifying archives and extracting verified files.

```kotlin
val qamzor = QamzorClient()
val created = qamzor.createArchive(File("backup.qzra"), listOf(File("documents")))
val verification = qamzor.verifyArchive(created.archive)
val entries = qamzor.listEntries(created.archive)
val extracted = qamzor.extractArchive(created.archive, File("restore"))
```

The SDK is version `0.1.0-dev`; it is not a stable API/ABI promise. It currently does not expose encryption, detached signatures, recovery sidecars, delta repositories, remote object storage, enterprise identity integration or server-side fleet administration. Those capabilities must be implemented and tested before they can be advertised as part of the SDK.
