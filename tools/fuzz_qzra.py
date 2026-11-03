#!/usr/bin/env python3
"""Deterministic mutation fuzzer for the QZRA parser and verifier.

This is a lightweight regression campaign, not a coverage-guided fuzzer.
It mutates a valid seed archive and checks that hostile input is either
rejected through documented format/integrity errors or safely verified.
"""
from __future__ import annotations

import argparse
import json
import random
import tempfile
import zlib
from pathlib import Path

from qamzor.format import QamzorError, QZRAReader, create_archive


def run_campaign(iterations: int, seed: int) -> dict:
    if not 1 <= iterations <= 100_000:
        raise ValueError("iterations must be between 1 and 100000")
    rng = random.Random(seed)
    with tempfile.TemporaryDirectory(prefix="qamzor-fuzz-") as temporary:
        root = Path(temporary)
        source = root / "seed.bin"
        source.write_bytes((b"QAMZOR QZRA parser fuzz seed\n" * 12000) + bytes(range(256)) * 8)
        archive = root / "seed.qzra"
        create_archive(archive, [source])
        original = archive.read_bytes()
        candidate = root / "candidate.qzra"
        rejected = 0
        verified = 0
        for _ in range(iterations):
            mutated = bytearray(original)
            mutation_count = rng.randint(1, 3)
            for _ in range(mutation_count):
                position = rng.randrange(len(mutated))
                mutated[position] ^= rng.randrange(1, 256)
            candidate.write_bytes(mutated)
            try:
                with QZRAReader(candidate) as reader:
                    reader.verify()
                verified += 1
            except (QamzorError, OSError, zlib.error):
                rejected += 1
        return {
            "seed": seed,
            "iterations": iterations,
            "rejected_inputs": rejected,
            "verified_inputs": verified,
            "unexpected_exceptions": 0,
            "scope": "deterministic byte-mutation fuzzing; not coverage-guided",
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iterations", type=int, default=2000)
    parser.add_argument("--seed", type=int, default=20261103)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = run_campaign(args.iterations, args.seed)
    encoded = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
