# QAMZOR File Format Registry

`qzra-registry.json` is the machine-readable, versioned list of the identifiers QAMZOR software uses:

| Format | Extension | MIME type | Magic (offset 0) |
|---|---|---|---|
| QZRA archive | `.qzra` | `application/vnd.qamzor.qzra` (alias `application/x-qzra`) | `QZRA\r\n\x1a\n` |
| Detached signature | `.qzsig` | `application/vnd.qamzor.qzsig+json` | JSON `{"format":"QZRA-SIGNATURE","version":1}` |
| Recovery sidecar | `.qzrec` | `application/vnd.qamzor.qzrec` | `QZRP\r\n\x1a\n` |

It also records header layout, version list, limits, crypto parameters and labels, and the error
categories used by the Compatibility Suite. The normative byte-level specification stays in
`docs/QZRA_FORMAT.md`; `tests/test_registry_and_compat.py` fails the build if the registry and the
reference implementation ever disagree. `qzra.xml` is a freedesktop shared-mime-info package for Linux desktops;
the Android manifest already declares both QZRA MIME types.

## Registration status (read this)

These identifiers are **fixed by this project's registry, not registered with an external authority.**
`application/vnd.qamzor.qzra` follows the vendor-tree naming rules but has not been submitted to IANA;
the UTIs (`org.qamzor.*`) are not registered with Apple. Until that is done, tell integrators to treat them as
"documented, project-defined". To register, submit the media-type template to IANA (vendor tree) once the
specification leaves its development status.

## Change policy

A new format version requires: a registry entry, a spec update, new Compatibility Suite vectors, and passing
runners for all implementations. Readers must reject unknown versions (`UNSUPPORTED_VERSION`).
