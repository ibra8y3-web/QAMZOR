# QAMZOR SDK family (pre-1.0)

| SDK | Module / path | Language | Capabilities | Status |
|---|---|---|---|---|
| Kotlin SDK | `:sdk` (`org.qamzor:qamzor-sdk`) | Kotlin/JVM | create, list, verify, compare, extract (QZRA 1.0-1.3) | existing; tests run in CI |
| **Java SDK** | `:sdk-java` (`org.qamzor:qamzor-java`) | Java 17+ | fluent create/open API, browse, verified preview, selective extract, typed `ErrorCategory` | new; compiles against the core's signatures, **CI must run its tests** |
| **Android SDK (AAR)** | `:sdk-android` (`org.qamzor:qamzor-android`) | Kotlin, minSdk 26 | open from content URI, create from documents/folders, extract into a SAF folder | new; **not compiled yet** (needs the Android SDK in CI) |
| **Native SDK** | `native/` (`libqzra`) | C11 / C++17 | open, unlock folders, list, verify, extract (read-only) | new; **verified locally** (see `native/README.md`) |

Supporting assets: **File Format Registry** (`registry/`), **Compatibility Suite** (`compat/`).

All JVM SDKs share one engine (`:core`), so there is a single implementation of the format's cryptography on the JVM.
The Native SDK is an independent implementation on OpenSSL; that it passes the same 18 vectors as the Python and
(after CI) Java implementations is the interoperability evidence.

## Java example

```java
CreateResult created = Qamzor.create(new File("backup.qzra"), List.of(new File("documents")))
        .password("correct horse battery staple").encryptMetadata(true).execute();
try {
    Qamzor.open(created.archive()).password("wrong").verify();
} catch (QamzorException e) {
    if (e.category() == ErrorCategory.AUTHENTICATION_FAILED) { /* ask again */ }
}
```
`ErrorClassifier` maps the core's exception messages to categories; the Compatibility Suite exercises each category.
A typed exception hierarchy inside `:core` would be more robust and is listed in the roadmap.

## Android example (call from a background thread)

```kotlin
val qamzor = QamzorAndroid(context)
qamzor.openArchive(archiveUri).use { archive ->
    archive.reader.password("secret")
    archive.reader.verify()
    qamzor.extractToTree(archive, destinationTreeUri)
}
```
