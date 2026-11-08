# Contributing to QAMZOR

QAMZOR is pre-alpha. Contributions should favor correctness, a small attack surface, clear format rules and reproducible tests.

## Before opening a pull request

1. Read `docs/QZRA_FORMAT.md`, `docs/SECURITY.md` and `ROADMAP.md`.
2. Keep format changes versioned and add compatibility tests.
3. Add tests for success cases and malformed/adversarial inputs.
4. Run `python -m unittest discover -s tests -v`.
5. Update `STATUS.md` only with work that exists and tests that actually ran.
6. Do not add placeholder UI controls or sample results presented as live data.

## Development setup

Python 3.10 or newer is required for the current reference implementation. There are no runtime dependencies.

```bash
python -m unittest discover -s tests -v
PYTHONPATH=src python -m qamzor.cli --help
```

## Security reports

Do not publish details of an exploitable vulnerability before maintainers have had a reasonable opportunity to investigate. Include the affected version, reproduction steps, and impact. Do not attach real secrets or private user data.
