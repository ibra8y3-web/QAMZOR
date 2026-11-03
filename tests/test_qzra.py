from __future__ import annotations

import json
import os
import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from qamzor.format import (
    HEADER, HEADER_SIZE, MAGIC, FormatError, IntegrityError, QamzorError,
    QZRAReader, SafetyError, create_archive,
)


class QZRATests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "input data"
        (self.source / "nested").mkdir(parents=True)
        (self.source / "hello.txt").write_text("Hello QAMZOR\n", encoding="utf-8")
        (self.source / "nested" / "empty.bin").write_bytes(b"")
        (self.source / "nested" / "arabic-ملف.txt").write_text("بيانات عربية", encoding="utf-8")
        (self.source / "empty-dir").mkdir()
        self.archive = self.root / "sample.qzra"

    def tearDown(self):
        self.temp.cleanup()

    def make_archive(self):
        return create_archive(self.archive, [self.source])

    def test_round_trip_and_verify(self):
        result = self.make_archive()
        self.assertGreater(result["archive_bytes"], HEADER_SIZE)
        with QZRAReader(self.archive) as reader:
            report = reader.verify()
            self.assertTrue(report["valid"])
            self.assertEqual(report["files_checked"], 3)
            self.assertEqual(reader.inspect()["directories"], 3)
        output = self.root / "out"
        with QZRAReader(self.archive) as reader:
            reader.extract(output)
        self.assertEqual((output / "input data" / "hello.txt").read_text(encoding="utf-8"), "Hello QAMZOR\n")
        self.assertEqual((output / "input data" / "nested" / "arabic-ملف.txt").read_text(encoding="utf-8"), "بيانات عربية")
        self.assertEqual((output / "input data" / "nested" / "empty.bin").read_bytes(), b"")
        self.assertTrue((output / "input data" / "empty-dir").is_dir())

    def test_selected_directory_extraction(self):
        self.make_archive()
        output = self.root / "selected"
        with QZRAReader(self.archive) as reader:
            names = reader.extract(output, selected=["input data/nested"])
        self.assertTrue((output / "input data" / "nested" / "arabic-ملف.txt").is_file())
        self.assertFalse((output / "input data" / "hello.txt").exists())
        self.assertEqual(len(names), 3)

    def test_refuses_overwrite_archive(self):
        self.make_archive()
        with self.assertRaises(QamzorError):
            create_archive(self.archive, [self.source])

    def test_refuses_overwrite_extracted_file(self):
        self.make_archive()
        output = self.root / "out"
        (output / "input data").mkdir(parents=True)
        (output / "input data" / "hello.txt").write_text("keep", encoding="utf-8")
        with QZRAReader(self.archive) as reader:
            with self.assertRaises(QamzorError):
                reader.extract(output)
        self.assertEqual((output / "input data" / "hello.txt").read_text(encoding="utf-8"), "keep")

    def test_overwrite_regular_file_when_explicit(self):
        self.make_archive()
        output = self.root / "out"
        (output / "input data").mkdir(parents=True)
        (output / "input data" / "hello.txt").write_text("old", encoding="utf-8")
        with QZRAReader(self.archive) as reader:
            reader.extract(output, overwrite=True)
        self.assertEqual((output / "input data" / "hello.txt").read_text(encoding="utf-8"), "Hello QAMZOR\n")

    def test_detects_payload_corruption(self):
        self.make_archive()
        with QZRAReader(self.archive) as reader:
            first_file = next(e for e in reader.entries if e.kind == "file")
            first_chunk = first_file.chunks[0]
            offset = reader.payload_offset + first_chunk.offset
            compressed_size = first_chunk.compressed_size
        raw = bytearray(self.archive.read_bytes())
        raw[offset + min(2, compressed_size - 1)] ^= 0x01
        self.archive.write_bytes(raw)
        with QZRAReader(self.archive) as reader:
            with self.assertRaises((FormatError, IntegrityError, zlib.error)):
                reader.verify()

    def test_rejects_bad_magic(self):
        self.make_archive()
        raw = bytearray(self.archive.read_bytes())
        raw[0] = ord("X")
        self.archive.write_bytes(raw)
        with self.assertRaises(FormatError):
            QZRAReader(self.archive)

    def test_rejects_truncated_header(self):
        self.archive.write_bytes(b"QZRA")
        with self.assertRaises(FormatError):
            QZRAReader(self.archive)

    def test_rejects_manifest_path_traversal(self):
        manifest = {
            "format": "QZRA", "version": "1.0",
            "entries": [{"path": "../escape.txt", "type": "file", "size": 0,
                         "offset": 0, "compressed_size": 8, "compression": "zlib",
                         "sha256": "0" * 64}],
        }
        encoded = json.dumps(manifest).encode("utf-8")
        header = HEADER.pack(MAGIC, 1, 0, 0, len(encoded), 1, 0)
        self.archive.write_bytes(header + encoded + zlib.compress(b""))
        with self.assertRaises(SafetyError):
            QZRAReader(self.archive)

    def test_rejects_symlink_source(self):
        link = self.root / "link.txt"
        try:
            link.symlink_to(self.source / "hello.txt")
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks are not available on this platform")
        with self.assertRaises(SafetyError):
            create_archive(self.root / "link.qzra", [link])

    def test_rejects_symlink_extraction_destination(self):
        self.make_archive()
        actual = self.root / "actual"
        actual.mkdir()
        link = self.root / "destination-link"
        try:
            link.symlink_to(actual, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks are not available on this platform")
        with QZRAReader(self.archive) as reader:
            with self.assertRaises(SafetyError):
                reader.extract(link)

    def test_empty_source_directory_is_valid(self):
        empty = self.root / "nothing"
        empty.mkdir()
        archive = self.root / "empty.qzra"
        create_archive(archive, [empty])
        with QZRAReader(archive) as reader:
            self.assertTrue(reader.verify()["valid"])
            self.assertEqual(len(reader.entries), 1)

    def test_archive_created_inside_source_is_not_archived_into_itself(self):
        target = self.source / "generated.qzra"
        create_archive(target, [self.source])
        with QZRAReader(target) as reader:
            names = [entry.path for entry in reader.entries]
            self.assertNotIn("input data/generated.qzra", names)
            self.assertTrue(reader.verify()["valid"])

    def test_rejects_unreferenced_gap_in_payload(self):
        content = b"gap check"
        compressed = zlib.compress(content)
        manifest = {
            "format": "QZRA", "version": "1.0",
            "entries": [{"path": "file.txt", "type": "file", "size": len(content),
                         "offset": 1, "compressed_size": len(compressed), "compression": "zlib",
                         "sha256": __import__("hashlib").sha256(content).hexdigest()}],
        }
        encoded = json.dumps(manifest).encode("utf-8")
        header = HEADER.pack(MAGIC, 1, 0, 0, len(encoded), 1, 0)
        self.archive.write_bytes(header + encoded + b"X" + compressed)
        with self.assertRaises(FormatError):
            QZRAReader(self.archive)


    def test_adaptive_compression_uses_store_for_incompressible_data(self):
        import os
        source = self.root / "random.bin"
        source.write_bytes(os.urandom(8192))
        archive = self.root / "adaptive.qzra"
        create_archive(archive, [source], profile="speed")
        with QZRAReader(archive) as reader:
            entry = next(e for e in reader.entries if e.kind == "file")
            self.assertTrue(entry.chunks)
            self.assertEqual(entry.chunks[0].compression, "store")
            self.assertTrue(reader.verify()["valid"])
            output = self.root / "adaptive-out"
            reader.extract(output)
        self.assertEqual((output / "random.bin").read_bytes(), source.read_bytes())

    def test_adaptive_compression_uses_zlib_for_repetitive_data(self):
        source = self.root / "repeat.txt"
        source.write_bytes((b"QAMZOR archive engine\n" * 10000))
        archive = self.root / "compressed.qzra"
        create_archive(archive, [source], profile="maximum")
        with QZRAReader(archive) as reader:
            entry = next(e for e in reader.entries if e.kind == "file")
            self.assertTrue(entry.chunks)
            self.assertTrue(all(chunk.compression == "zlib" for chunk in entry.chunks))
            self.assertLess(sum(chunk.compressed_size for chunk in entry.chunks), entry.size)
            self.assertTrue(reader.verify()["valid"])


    def test_duplicate_chunks_are_stored_once_across_files(self):
        content = (b"shared content for the QAMZOR chunk store\n" * 12000)
        first = self.root / "first.bin"
        second = self.root / "second.bin"
        first.write_bytes(content)
        second.write_bytes(content)
        archive = self.root / "deduplicated.qzra"
        result = create_archive(archive, [first, second], workers=3)
        self.assertGreater(result["deduplicated_chunks"], 0)
        self.assertEqual(result["unique_chunks"], 1)
        self.assertEqual(result["deduplication_saved_bytes"], len(content))
        with QZRAReader(archive) as reader:
            self.assertTrue(reader.verify()["valid"])
            self.assertEqual(reader.inspect()["deduplicated_chunk_references"], result["deduplicated_chunks"])
            output = self.root / "dedup-out"
            reader.extract(output)
        self.assertEqual((output / "first.bin").read_bytes(), content)
        self.assertEqual((output / "second.bin").read_bytes(), content)

    def test_archive_can_be_created_with_one_and_multiple_workers(self):
        serial = self.root / "serial.qzra"
        parallel = self.root / "parallel.qzra"
        create_archive(serial, [self.source], workers=1)
        create_archive(parallel, [self.source], workers=4)
        with QZRAReader(serial) as first, QZRAReader(parallel) as second:
            self.assertTrue(first.verify()["valid"])
            self.assertTrue(second.verify()["valid"])
            self.assertEqual(
                [(e.path, e.size, e.sha256) for e in first.entries],
                [(e.path, e.size, e.sha256) for e in second.entries],
            )

    def test_reads_legacy_qzra_1_0_archive(self):
        content = b"legacy archive compatibility" * 10
        encoded_data = zlib.compress(content)
        manifest = {
            "format": "QZRA", "version": "1.0",
            "entries": [{
                "path": "legacy.txt", "type": "file", "size": len(content),
                "offset": 0, "compressed_size": len(encoded_data), "compression": "zlib",
                "sha256": __import__("hashlib").sha256(content).hexdigest(),
            }],
        }
        encoded_manifest = json.dumps(manifest).encode("utf-8")
        header = HEADER.pack(MAGIC, 1, 0, 0, len(encoded_manifest), 1, 0)
        archive = self.root / "legacy-v1.0.qzra"
        archive.write_bytes(header + encoded_manifest + encoded_data)
        with QZRAReader(archive) as reader:
            self.assertEqual(reader.inspect()["version"], "1.0")
            self.assertTrue(reader.verify()["valid"])
            output = self.root / "legacy-out"
            reader.extract(output)
        self.assertEqual((output / "legacy.txt").read_bytes(), content)

    def test_rejects_invalid_worker_count(self):
        with self.assertRaises(QamzorError):
            create_archive(self.root / "bad-workers.qzra", [self.source], workers=0)


    def test_virtual_search_and_preview_without_extraction(self):
        self.make_archive()
        with QZRAReader(self.archive) as reader:
            matches = reader.find_entries("HELLO", kind="file")
            self.assertEqual([entry.path for entry in matches], ["input data/hello.txt"])
            preview = reader.preview("input data/hello.txt", max_bytes=5)
            self.assertEqual(preview["data"], b"Hello")
            self.assertTrue(preview["truncated"])
            self.assertTrue(preview["verified"])
            self.assertEqual(preview["bytes_total"], len(b"Hello QAMZOR\n"))
        self.assertFalse((self.root / "preview-out").exists())

    def test_preview_rejects_directory_and_invalid_limits(self):
        self.make_archive()
        with QZRAReader(self.archive) as reader:
            with self.assertRaises(QamzorError):
                reader.preview("input data")
            with self.assertRaises(QamzorError):
                reader.preview("input data/hello.txt", max_bytes=17 * 1024 * 1024)

    def test_extraction_byte_and_file_quotas_are_enforced_before_writing(self):
        self.make_archive()
        output = self.root / "quota-out"
        with QZRAReader(self.archive) as reader:
            with self.assertRaises(SafetyError):
                reader.extract(output, max_total_bytes=1)
        self.assertFalse(output.exists())
        with QZRAReader(self.archive) as reader:
            with self.assertRaises(SafetyError):
                reader.extract(output, max_files=0)
        self.assertFalse(output.exists())


    @unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
    def test_aes_gcm_encrypted_archive_round_trip(self):
        secret = self.root / "secret.txt"
        secret.write_bytes(b"confidential QAMZOR content\n" * 5000)
        archive = self.root / "encrypted.qzra"
        result = create_archive(archive, [secret], password="correct horse battery")
        self.assertEqual(result["format_version"], "1.2")
        self.assertTrue(result["encrypted"])
        with QZRAReader(archive) as reader:
            self.assertTrue(reader.inspect()["encrypted"])
            with self.assertRaises(QamzorError):
                reader.verify()
        with QZRAReader(archive, password="correct horse battery") as reader:
            self.assertTrue(reader.verify()["valid"])
            output = self.root / "encrypted-out"
            reader.extract(output)
        self.assertEqual((output / "secret.txt").read_bytes(), secret.read_bytes())

    @unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
    def test_encrypted_archive_rejects_wrong_password_and_tampering(self):
        secret = self.root / "secret.txt"
        secret.write_bytes(b"encrypted bytes" * 1000)
        archive = self.root / "encrypted.qzra"
        create_archive(archive, [secret], password="right-password")
        with self.assertRaises(IntegrityError):
            with QZRAReader(archive, password="wrong-password") as reader:
                reader.verify()
        with QZRAReader(archive) as reader:
            first_chunk = next(e for e in reader.entries if e.kind == "file").chunks[0]
            offset = reader.payload_offset + first_chunk.offset
        raw = bytearray(archive.read_bytes())
        raw[offset] ^= 0x01
        archive.write_bytes(raw)
        with QZRAReader(archive, password="right-password") as reader:
            with self.assertRaises(IntegrityError):
                reader.verify()

    @unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
    def test_encrypted_manifest_metadata_tampering_is_rejected(self):
        secret = self.root / "secret.txt"
        secret.write_bytes(b"authenticated manifest contents")
        archive = self.root / "manifest-auth.qzra"
        create_archive(archive, [secret], password="manifest-passphrase")
        raw = bytearray(archive.read_bytes())
        manifest_length = HEADER.unpack(raw[:HEADER_SIZE])[4]
        manifest_start = HEADER_SIZE
        manifest = bytes(raw[manifest_start:manifest_start + manifest_length])
        self.assertIn(b"secret.txt", manifest)
        changed = manifest.replace(b"secret.txt", b"secreu.txt", 1)
        self.assertEqual(len(changed), len(manifest))
        raw[manifest_start:manifest_start + manifest_length] = changed
        archive.write_bytes(raw)
        with self.assertRaises(IntegrityError):
            with QZRAReader(archive, password="manifest-passphrase") as reader:
                reader.verify()

    @unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
    def test_encrypted_empty_files_require_manifest_authentication(self):
        empty = self.root / "empty.txt"
        empty.write_bytes(b"")
        archive = self.root / "empty-encrypted.qzra"
        create_archive(archive, [empty], password="passphrase")
        with QZRAReader(archive) as reader:
            self.assertTrue(reader.is_encrypted)
            with self.assertRaises(QamzorError):
                reader.verify()
        with QZRAReader(archive, password="passphrase") as reader:
            self.assertEqual(reader.verify()["bytes_checked"], 0)

    @unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
    def test_encrypted_archives_keep_deduplicated_chunks(self):
        data = b"identical encrypted chunk data\n" * 10000
        first = self.root / "first-secret.bin"
        second = self.root / "second-secret.bin"
        first.write_bytes(data)
        second.write_bytes(data)
        archive = self.root / "deduplicated-encrypted.qzra"
        result = create_archive(archive, [first, second], password="shared-secret", workers=2)
        self.assertGreater(result["deduplicated_chunks"], 0)
        with QZRAReader(archive, password="shared-secret") as reader:
            self.assertTrue(reader.verify()["valid"])

    def test_unknown_compression_profile_is_rejected(self):
        with self.assertRaises(QamzorError):
            create_archive(self.root / "bad.qzra", [self.source], profile="magic")

    def test_cli_surface_imports(self):
        from qamzor.cli import main
        with self.assertRaises(SystemExit) as result:
            main(["--help"])
        self.assertEqual(result.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
