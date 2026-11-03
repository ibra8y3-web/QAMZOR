# QAMZOR Implementation Roadmap

The project is delivered in eight gated phases. A phase is complete only when its files exist, its acceptance tests pass, and the status record is updated with evidence.

| Phase | Scope | Exit criteria |
|---|---|---|
| 0 | Repository foundation, naming, license, architecture and documentation | Project layout, contribution rules, CI workflow and status tracking exist |
| 1 | QZRA format and Core reference implementation | Create/list/inspect/verify/extract work on real files; format and safety tests pass |
| 2 | Compression profiles, chunking, parallelism and deduplication | Correctness tests and reproducible benchmarks; storage/reference lifecycle defined |
| 3 | Virtual browsing, safe extraction, encryption, signatures and recovery | Threat model, security tests, fuzzing and documented cryptographic construction |
| 4 | Delta updates, archive history and repository backup management | Incremental snapshots restore exactly; direct-child deltas reject the wrong base; retention and garbage-collection tests pass |
| 5 | Android application | Installable build, SAF integration, real file operations and device tests |
| 6 | Windows, Linux and macOS applications | Reproducible builds, platform integration and cross-platform format tests |
| 7 | SDK, developer tooling and enterprise management | Versioned APIs, compatibility suite, admin controls and release documentation |

## Phase policy

- No stage may be marked complete based on plans, generated UI, mocks, placeholder buttons, or unexecuted tests.
- Benchmarks must identify hardware, operating system, dataset, settings and command line.
- Cryptographic algorithms must use established libraries and documented constructions; no custom cryptography.
- Format changes require a versioning note and compatibility tests.
- Every public feature needs an implemented path, a test, and user documentation.


## Current client implementation track

The Android app, cross-platform JVM desktop app and Kotlin SDK now have initial source projects. They remain pending until Gradle CI builds them successfully. The next acceptance sequence is: (1) establish Kotlin/Python golden-file conformance for QZRA 1.0/1.1, (2) run Android emulator tests, (3) build/package Windows, Linux and macOS desktop distributions, and (4) implement the remaining SDK and enterprise requirements rather than claiming them complete.
