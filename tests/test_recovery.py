import os
import tempfile
import unittest
from pathlib import Path

from qamzor.format import create_archive
from qamzor.recovery import RecoveryError, create_recovery_file, repair_archive


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "payload.bin"
        self.source.write_bytes(os.urandom(640 * 1024))
        self.archive = self.root / "payload.qzra"
        create_archive(self.archive, [self.source])
        self.original = self.archive.read_bytes()
        self.recovery = self.root / "payload.qzrec"
        create_recovery_file(self.archive, self.recovery, block_size=64 * 1024, group_size=4)

    def tearDown(self):
        self.temp.cleanup()

    def test_repairs_one_bad_block_per_parity_group(self):
        damaged = self.root / "damaged.qzra"
        damaged.write_bytes(self.original)
        raw = bytearray(damaged.read_bytes())
        raw[128] ^= 0x5A
        raw[5 * 64 * 1024 + 23] ^= 0xA5
        damaged.write_bytes(raw)
        repaired = self.root / "repaired.qzra"
        result = repair_archive(damaged, self.recovery, repaired)
        self.assertTrue(result["valid"])
        self.assertEqual(result["repaired_count"], 2)
        self.assertEqual(repaired.read_bytes(), self.original)

    def test_refuses_two_bad_blocks_in_same_group(self):
        damaged = self.root / "damaged-many.qzra"
        damaged.write_bytes(self.original)
        raw = bytearray(damaged.read_bytes())
        raw[100] ^= 0x01
        raw[64 * 1024 + 100] ^= 0x02
        damaged.write_bytes(raw)
        output = self.root / "must-not-exist.qzra"
        with self.assertRaises(RecoveryError):
            repair_archive(damaged, self.recovery, output)
        self.assertFalse(output.exists())

    def test_clean_archive_is_copied_and_checked(self):
        output = self.root / "clean-copy.qzra"
        result = repair_archive(self.archive, self.recovery, output)
        self.assertEqual(result["repaired_count"], 0)
        self.assertEqual(output.read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
