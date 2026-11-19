# QAMZOR SDK family (pre-1.0)

| SDK | Module / path | Language | Capabilities | Status |
|---|---|---|---|---|
| Kotlin SDK | `:sdk` (`qamzor-sdk`) | Kotlin/JVM | create, list, verify, compare, extract (QZRA 1.0–1.3) | CI verification pending |
| **Java SDK** | `:sdk-java` (`qamzor-java`) | Java 17+ | fluent create/open API, browse, verified preview, selective extract, typed `ErrorCategory` | CI verification pending |
| **Android SDK (AAR)** | `:sdk-android` (`qamzor-android`) | Kotlin, minSdk 26 | open from content URI, create from documents/folders, extract into a SAF folder | CI verification pending |
| **Native SDK** | `native/` (`libqzra`) | C11 / C++17 | open, unlock folders, list, verify, extract (read-only) | Linux compatibility/sanitizer checks recorded; macOS CI pending |

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


## Distribution: JitPack and Maven Central

The JVM and Android modules are prepared for two publishing routes. They are
not publicly released yet; use a real tag/version only after the CI checks pass.

### JitPack

JitPack uses the GitHub repository and a tag or snapshot. Add the repository to
the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add one of these dependencies (replace `<TAG>` with a tag that has
successfully built on JitPack):

```kotlin
dependencies {
    // Java 17+ or Kotlin/JVM:
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-java:<TAG>")

    // Kotlin facade:
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-sdk:<TAG>")

    // Android AAR (minSdk 26+):
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-android:<TAG>")
}
```

### Maven Central

After the GitHub namespace is shown as Verified in the Sonatype Central
Portal, the expected groupId is `io.github.ibra8y3-web`. After a signed
release is accepted and published in the Portal, use Maven Central normally
(no JitPack repository is needed):

```kotlin
dependencies {
    implementation("io.github.ibra8y3-web:qamzor-java:<VERSION>")
    implementation("io.github.ibra8y3-web:qamzor-sdk:<VERSION>")
    implementation("io.github.ibra8y3-web:qamzor-android:<VERSION>")
}
```

The Android AAR exposes the Java SDK and Kotlin Core as API dependencies.
Native is distributed separately as the `libqzra-<VERSION>.tar.gz` source
archive attached to a GitHub Release; it is not a Maven artifact and is
currently read-only. See [Publishing guide](PUBLISHING.md) for the complete
secret, GPG, Portal token and release setup.
