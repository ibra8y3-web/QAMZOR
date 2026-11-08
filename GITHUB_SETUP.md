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

- `Tests`: Python test matrix (Python 3.10–3.13). The workflow first installs this local project in editable mode with its crypto test extra (`python -m pip install -e ".[crypto]"`), then runs the test suite and the installed `qz` command. Do not add `qamzor` as a PyPI requirement: it is the package built from this repository's `src/` tree.
- `Platform builds`: Kotlin core/SDK tests and desktop distributions on Ubuntu, Windows and macOS; Android debug APK build and unit-test task on Ubuntu.

## CI failure fixes

The Python test job must install the repository before test discovery. Without that step, `unittest` cannot import `qamzor` because the package uses the `src/qamzor` layout. This is fixed in `.github/workflows/tests.yml` with an editable install and the optional `crypto` dependency required by signature tests.

The Android job installs the command-line tools without running the setup action's automatic license acceptance, then explicitly runs `sdkmanager --licenses` with a finite stream of `y` answers supplied by Python before installing `platform-tools`, Android platform 35 and build-tools 35.0.0. The same safe input method is used during package installation and license verification. This avoids failing inside the setup action while it tries to process unaccepted licenses. The workflow uses `android-actions/setup-android@v4`.

The workflow action majors were also updated to Node 24-compatible releases (`actions/checkout@v7`, `actions/setup-python@v7`, `actions/setup-java@v6`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v7`) to remove the Node 20 deprecation warnings shown in older logs. These changes are configuration fixes; the remote GitHub result remains pending until a push or manual workflow run.

The generated APK and desktop distributions are workflow artifacts. They are not published GitHub Releases automatically. A release workflow, signing configuration and user-facing release notes must be added after successful CI and platform acceptance.
