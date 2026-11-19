# Publishing the QAMZOR SDKs

QAMZOR uses two distribution routes for JVM/Android libraries and a separate
source release for Native C/C++:

| Route | What it distributes | Domain required? |
|---|---|---|
| JitPack | Kotlin/JVM, Java and Android AAR artifacts built from this GitHub repository | No |
| Maven Central | Signed, versioned Kotlin/JVM, Java and Android AAR artifacts | No, after the GitHub namespace is verified |
| GitHub Release | Native C/C++ source archive, Compatibility Suite and registry | No |

The SDKs are currently pre-1.0. A successful source build is not the same as a
published or security-audited release.

## 1. JitPack (first distribution route)

The root Gradle build reads JitPack's `GROUP` and `VERSION` environment
variables when they are present. This is important because the published
coordinates must use the repository and tag being built, not the Maven Central
group or the development version in `gradle.properties`.

The repository contains these Maven artifact IDs:

| Gradle module | Published artifact ID | Intended consumer |
|---|---|---|
| `:core` | `qamzor-core` | Low-level Kotlin/JVM engine |
| `:sdk` | `qamzor-sdk` | Kotlin/JVM facade |
| `:sdk-java` | `qamzor-java` | Java 17+ and Kotlin applications |
| `:sdk-android` | `qamzor-android` | Android applications (AAR) |

The root `jitpack.yml` selects JDK 17 and builds/tests the SDK modules before
publishing their Maven publications to the local Maven repository. JitPack
then builds artifacts from the GitHub repository and tag. The first remote
JitPack build must still be checked on JitPack; it is not claimed successful
until its build page reports success.

### Consumer setup (Gradle Kotlin DSL)

Add JitPack after Maven Central in the consuming app's
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

For a Java or Kotlin/JVM application:

```kotlin
dependencies {
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-java:<TAG>")
}
```

For an Android application:

```kotlin
dependencies {
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-android:<TAG>")
}
```

For the Kotlin facade directly:

```kotlin
dependencies {
    implementation("com.github.ibra8y3-web.QAMZOR:qamzor-sdk:<TAG>")
}
```

Replace `<TAG>` with an actual Git tag published by this repository, for
example the exact tag shown on its JitPack page. Do not copy the placeholder
literally. The Android artifact exposes the Java SDK and Kotlin Core as
transitive API dependencies.

The Native SDK is not a Maven artifact. Its source package is attached to the
GitHub Release as `libqzra-<version>.tar.gz` with a SHA-256 checksum.

## 2. Maven Central (permanent public coordinates)

The GitHub account that owns this repository is `ibra8y3-web`. The configured
Maven Central groupId is therefore:

```text
io.github.ibra8y3-web
```

The hyphen is valid in a Maven groupId and does not need to appear in Java or
Kotlin package names.

One-time maintainer steps:

1. Sign in to [Sonatype Central Portal](https://central.sonatype.com/) using
   **Continue with GitHub** and the GitHub account `ibra8y3-web`.
2. Open **View Namespaces** and confirm that
   `io.github.ibra8y3-web` is present and marked **Verified**. GitHub sign-in
   can provision this namespace automatically; do not assume it is verified
   until the Portal shows that status.
3. Generate a Central Portal user token.
4. Generate a GPG signing key, keep the private key private, and publish the
   public key to a public key server.
5. Add the following GitHub repository **Secrets**:
   - `SIGNING_KEY`: ASCII-armored private GPG key.
   - `SIGNING_KEY_PASSWORD`: password protecting that key.
   - `CENTRAL_PORTAL_USERNAME`: username from the generated Portal token.
   - `CENTRAL_PORTAL_TOKEN`: token password from the generated Portal token.
6. Add these GitHub repository **Variables** with real maintainer details:
   - `POM_URL`: `https://github.com/ibra8y3-web/QAMZOR`
   - `POM_SCM_URL`: `https://github.com/ibra8y3-web/QAMZOR.git`
   - `POM_DEVELOPER_ID`: `ibra8y3-web`
   - `POM_DEVELOPER_NAME`: maintainer's preferred public name.
   - `POM_DEVELOPER_EMAIL`: maintainer's public contact email.

Do not put tokens, private keys, passwords, or personal archive files in Git.
The release workflow requires the `maven-central` GitHub Environment; configure
a required reviewer there so the Portal upload is reviewed before publication.

### Release flow

The `.github/workflows/release.yml` workflow runs for tags matching `v*`.
It first runs Python tests, Kotlin/Java/Android builds and the Native
Compatibility Suite. Only after those pass does it:

- Build and GPG-sign `qamzor-core`, `qamzor-sdk`, `qamzor-java` and
  `qamzor-android`.
- Validate that the Central bundle contains POM, source/Javadoc artifacts
  and signatures.
- Upload the bundle to Sonatype as a **USER_MANAGED** deployment. It does not
  publish the artifacts live automatically; the maintainer must inspect the
  deployment in Central Portal and press **Publish**.

The Native source archive is attached to the matching GitHub Release.

Do not create a release tag until the first CI run passes and every required
Secret, Variable, namespace and environment approval is configured. The release
version comes from the tag without the leading `v`; versions ending in
`SNAPSHOT` are rejected. The SDK remains pre-1.0 until the project explicitly
declares a stable API.

## 3. Local/CI publishing checks

The reusable workflow
`.github/workflows/sdk-publishing-check.yml` simulates JitPack's coordinates
with:

- `GROUP=com.github.ibra8y3-web.QAMZOR`
- `VERSION=0.0.0-ci`

It tests the SDK, builds the Android AAR, publishes the four artifacts to
Maven Local and verifies the generated POM/binary files. A passing check validates
the Gradle coordinates and publication configuration, but it does not replace
the first real JitPack build or a Maven Central Portal deployment.

## 4. Platform and security boundaries

- Native C/C++ is currently read-only and does not create QZRA archives.
- Native CI targets Linux and macOS; Windows support is not claimed.
- The Android AAR requires Android API 26+ and Java 17 toolchain compatibility.
- Kotlin/Java/Android acceptance depends on GitHub Actions. Do not describe a
  module as release-ready until the current CI run passes.
- QZRA has not received an independent security audit. Users should keep
  independent backups and should not use this development release as the only
  copy of important data.
