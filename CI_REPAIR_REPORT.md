# QAMZOR CI Repair Report — 2026-11-03

## 1. Python test job: `ModuleNotFoundError: No module named 'qamzor'`

### Root cause

The repository uses a `src/` layout (`src/qamzor`), but the GitHub Actions test job ran `python -m unittest discover -s tests -v` before installing the project or adding `src` to Python's import path. Test discovery therefore failed while importing every test module. This was not a missing external PyPI dependency named `qamzor`.

### Correction

`.github/workflows/tests.yml` now installs the checked-out project and the optional crypto extra before tests:

```bash
python -m pip install --upgrade pip
python -m pip install -e ".[crypto]"
python -m unittest discover -s tests -v
qz --help
```

The crypto extra is included because the Ed25519 signature tests exercise `cryptography`. The console check now verifies the installed `qz` command.

## 2. Android job: SDK license acceptance failed inside setup action

### Root cause

The previous job invoked `android-actions/setup-android@v3`, and the action exited while running `sdkmanager` because SDK licenses remained unaccepted. As a result, the workflow never reached the SDK package installation or Gradle build steps.

### Correction

`.github/workflows/platform-builds.yml` now:

1. Uses `android-actions/setup-android@v4` with `accept-android-sdk-licenses: false` and `packages: ''`, so setup does not attempt the failing interactive license step or install packages prematurely.
2. Runs `sdkmanager --licenses` through Python's `subprocess.run`, supplying a finite stream of `y` answers. This avoids relying on an unbounded `yes` process and avoids a possible upstream `SIGPIPE` under GitHub's `pipefail` shell.
3. Installs `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`, again supplying license answers to the package installation process.
4. Runs license acceptance once more after package installation, then starts the Android Gradle assemble and unit-test tasks.

## 3. Node.js deprecation warnings

The workflows now use `actions/checkout@v7`, `actions/setup-python@v7`, `actions/setup-java@v6`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v7` for the relevant jobs. These releases use Node 24-compatible runtimes. The warnings were not the cause of either initial failure, but the update removes the old Node 20 action runtime from those steps.

## 4. Local verification performed

In the available Python 3.13.5 environment:

- `python -m pip install --no-build-isolation -e ".[crypto]"` — passed.
- `env -u PYTHONPATH python -m unittest discover -s tests -v` — **58 tests passed**.
- `qz --help` — passed from the installed entry point.
- `python -m compileall -q src tests` — passed.

## 5. Remaining verification boundary

The local container cannot run GitHub-hosted Actions. The Android SDK/Gradle build is not available locally. Therefore these edits are a repair to the workflow configuration, not proof that the remote workflows have passed. Push this revision to GitHub or rerun both workflows with `workflow_dispatch` and confirm the job results. If a new failure appears after these steps, it will be a different failure further along the pipeline and should be diagnosed from that new log.
