# Publishing QAMZOR to GitHub

The source tree is prepared for GitHub Actions, but it has not been pushed to a remote repository from this environment. To trigger the configured builds:

1. Create an empty GitHub repository named `QAMZOR` (do not pre-create a README, license or `.gitignore`).
2. From the project folder, run:

```sh
git init
git add .
git commit -m "Add QAMZOR reference engine and client projects"
git branch -M main
git remote add origin https://github.com/YOUR_ACCOUNT/QAMZOR.git
git push -u origin main
```

Replace the account placeholder with the GitHub owner. Do not commit signing private keys, passwords, personal archives, APK signing keys or local SDK configuration.

After the push, GitHub Actions should start:

- `Tests`: Python test matrix (Python 3.10–3.13).
- `Platform builds`: Kotlin core/SDK tests and desktop distributions on Ubuntu, Windows and macOS; Android debug APK build and unit-test task on Ubuntu.

The generated APK and desktop distributions are workflow artifacts. They are not published GitHub Releases automatically. A release workflow, signing configuration and user-facing release notes must be added after successful CI and platform acceptance.
