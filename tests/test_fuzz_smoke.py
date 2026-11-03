"""Deterministic mutation smoke tests for hostile/truncated QZRA input.

This is a bounded regression corpus, not a substitute for a sustained fuzzing
campaign (Atheris/libFuzzer) or independent security review.
"""
from __future__ import annotations

import random
import tempfile
import unittest
import zlib
from pathlib import Path

from qamzor.format import QamzorError, QZRAReader, create_archive


class QZRAMutationSmokeTests(unittest.TestCase):
    def test_single_byte_mutations_do_not_escape_as_unexpected_exceptions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "payload.txt"
            source.write_bytes((b"QAMZOR deterministic mutation test\n" * 3500) + bytes(range(256)))
            pristine = root / "pristine.qzra"
            create_archive(pristine, [source])
            original = pristine.read_bytes()
            rng = random.Random(20261103)

            # Exercise header, JSON manifest, and compressed payload regions.
            for index in range(96):
                mutated = bytearray(original)
                position = rng.randrange(len(mutated))
                mutated[position] ^= rng.randrange(1, 256)
                candidate = root / f"mutated-{index:03d}.qzra"
                candidate.write_bytes(mutated)
                try:
                    with QZRAReader(candidate) as reader:
                        reader.verify()
                except (QamzorError, OSError, zlib.error):
                    pass
                # Any other exception type indicates malformed input escaped
                # the format boundary and is intentionally allowed to fail.


if __name__ == "__main__":
    unittest.main()
