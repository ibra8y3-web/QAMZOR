# GitHub Actions and release setup

This repository already exists at
https://github.com/ibra8y3-web/QAMZOR. Do not create a second repository or
copy the project into a new one.

## Verify the automatic builds

Push commits to the default branch or open a pull request. GitHub Actions is
configured to run:

- **Tests**: Python 3.10–3.13, package installation and CLI smoke test.
- **Platform builds**: Kotlin Core/SDK tests on Linux, Windows and macOS;
  desktop distributions; Android debug APK and Android SDK AAR.
- **Native SDK**: Linux/macOS build, the Compatibility Suite, C++ smoke test
  and Linux sanitizer fuzz smoke test.
- **SDK publication checks**: simulated JitPack coordinates, Maven Local
  publication and verification of the generated JAR/AAR/POM files.

Open the repository's **Actions** tab and inspect every job. A workflow being
configured is not proof that it has passed. Download and test the APK/AAR
artifacts from a successful run.

## Release preparation

Read [docs/PUBLISHING.md](docs/PUBLISHING.md) before creating any release tag.
Maven Central requires a verified namespace, GPG signing key, Portal token,
GitHub repository Secrets/Variables and approval on the `maven-central`
Environment. Never commit private signing keys, passwords, tokens, APK signing
keys or local SDK configuration.

A tag matching `v*` starts the release workflow. It waits for the Python,
platform, Native and SDK publication checks to pass, then uploads a signed
Maven Central bundle as a user-managed deployment. The maintainer must inspect
that deployment and press **Publish** in Central Portal; the workflow does not
publish it live automatically.

The Native SDK source archive is attached to the matching GitHub Release.
JitPack builds tagged versions directly from this repository; check the first
build on the JitPack project page before distributing its dependency
coordinates.
