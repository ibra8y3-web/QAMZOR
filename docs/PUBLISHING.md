# Publishing the SDKs

JVM artifacts (published together at the same version): `qamzor-core`, `qamzor-sdk`, `qamzor-java`, `qamzor-android` (AAR).
The Native SDK is **not** a Maven artifact: it ships as the source archive `libqzra-<version>.tar.gz` attached to the GitHub release.

## What the pipeline does (`.github/workflows/release.yml`, on a `vX.Y.Z` tag)

1. Runs **all** test workflows (Python reference + Compatibility Suite, Kotlin/Java tests on 3 OSes, Android build, native build/fuzz on Linux and macOS).
2. Only if they all pass, in the `maven-central` environment (configure required reviewers): builds the four artifacts, signs
   them with GPG (`signing` plugin) and stages them in `build/central-staging`.
3. `tools/make_central_bundle.py` builds the Portal bundle and **refuses** if a file lacks a signature or sources/javadoc/pom is missing.
4. Uploads the bundle to the Central Portal as `USER_MANAGED`: nothing goes live until a maintainer presses **Publish** in the portal.

## One-time setup you must do (nothing here can be done for you)

- Create a Central Portal account (central.sonatype.com) and **verify a namespace**. The default group id `org.qamzor`
  only works if you control the domain `qamzor.org`; otherwise use `io.github.<your-github-user>` and set `qamzor.groupId`
  in `gradle.properties`. The Java/Kotlin package names do not have to match.
- Generate a Portal user token and a GPG key; publish the public key to a key server.
- GitHub repository secrets: `SIGNING_KEY` (ASCII-armored private key), `SIGNING_KEY_PASSWORD`, `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_TOKEN`.
- GitHub repository variables (real values only): `POM_URL`, `POM_SCM_URL`, `POM_DEVELOPER_ID`, `POM_DEVELOPER_NAME`, `POM_DEVELOPER_EMAIL`.
  The build fails with a clear message if any is missing; no placeholder metadata is generated.
- Release: `git tag v0.8.0 && git push origin v0.8.0`. The Maven version is the tag without `v`; versions ending in `SNAPSHOT` are refused.
  Keep `Qamzor.SDK_VERSION` in `sdk-java` equal to `VERSION_NAME` (a unit test checks this on normal builds).

## Local dry run

```
gradle -Prelease=false -PVERSION_NAME=0.8.0 \
  -Pqamzor.pom.url=... -Pqamzor.pom.scmUrl=... -Pqamzor.pom.developerId=... -Pqamzor.pom.developerName=... -Pqamzor.pom.developerEmail=... \
  :core:publishMavenPublicationToCentralStagingRepository ...
```
Signatures are only created when a key is supplied (`-Prelease` makes signing mandatory).

## Honest status

The publishing convention (`gradle/publishing.gradle`), the Portal upload step and the Android AAR build have **not been executed**:
the authoring environment had no Gradle, Android SDK or network access. The bundle builder is unit-tested (`tests/test_central_bundle.py`).
Expect to fix small things on the first CI run; do the first release as a tag on a throw-away version and inspect the Portal deployment before pressing Publish.
