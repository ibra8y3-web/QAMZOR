import importlib.util
import tempfile
import unittest
from pathlib import Path

from qamzor.format import IntegrityError, QamzorError, create_archive
from qamzor.signatures import generate_signing_keypair, sign_archive, verify_archive_signature


@unittest.skipUnless(importlib.util.find_spec("cryptography"), "optional cryptography dependency is not installed")
class SignatureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "data.txt"
        self.source.write_text("QAMZOR signed archive content\\n" * 500, encoding="utf-8")
        self.archive = self.root / "signed.qzra"
        create_archive(self.archive, [self.source])
        self.private = self.root / "signing-private.pem"
        self.public = self.root / "signing-public.pem"
        generate_signing_keypair(self.private, self.public, "strong-test-passphrase")
        self.signature = self.root / "signed.qzra.qzsig"

    def tearDown(self):
        self.temp.cleanup()

    def test_sign_and_verify_archive(self):
        result = sign_archive(
            self.archive,
            self.private,
            private_key_password="strong-test-passphrase",
            signature_path=self.signature,
        )
        self.assertEqual(result["algorithm"], "Ed25519")
        self.assertTrue(verify_archive_signature(self.archive, self.public, self.signature)["valid"])

    def test_signature_rejects_modified_archive(self):
        sign_archive(self.archive, self.private, private_key_password="strong-test-passphrase", signature_path=self.signature)
        changed = bytearray(self.archive.read_bytes())
        changed[-1] ^= 1
        self.archive.write_bytes(changed)
        with self.assertRaises(IntegrityError):
            verify_archive_signature(self.archive, self.public, self.signature)

    def test_signing_key_requires_passphrase(self):
        with self.assertRaises(QamzorError):
            generate_signing_keypair(self.root / "x.pem", self.root / "y.pem", "short")


if __name__ == "__main__":
    unittest.main()
