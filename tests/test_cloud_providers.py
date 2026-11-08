from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from qamzor.cloud_providers import (
    DropboxBackup, GoogleDriveBackup, OneDriveBackup, S3Backup,
    _https_parts, _safe_remote_name, _VerifiedAdapter,
)
from qamzor.format import FormatError, QamzorError, create_archive


class _MemoryProvider(_VerifiedAdapter):
    """Network-free test double for the verified upload/restore lifecycle."""
    provider = "test-provider"

    def __init__(self):
        super().__init__(timeout=10)
        self.objects = {}

    def _upload_archive(self, name, source, size):
        self.objects[name] = source.read_bytes()
        return name

    def _upload_metadata(self, name, payload):
        self.objects[name] = payload
        return name

    def _read_metadata(self, remote_reference, metadata_reference):
        return self.objects[metadata_reference or remote_reference + ".qamzor.json"]

    def _download_archive(self, remote_reference, output):
        output.write(self.objects[remote_reference])


class CloudProviderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "payload.txt").write_text("QAMZOR cloud connector round trip", encoding="utf-8")
        self.archive = self.root / "backup.qzra"
        create_archive(self.archive, [self.source])

    def tearDown(self):
        self.temp.cleanup()

    def test_verified_push_and_pull_round_trip(self):
        provider = _MemoryProvider()
        pushed = provider.push(self.archive)
        self.assertEqual("test-provider", pushed["provider"])
        self.assertTrue(pushed["source_sha256_recorded"])
        destination = self.root / "restored.qzra"
        pulled = provider.pull("backup.qzra", destination)
        self.assertTrue(pulled["valid"])
        self.assertEqual(hashlib.sha256(self.archive.read_bytes()).hexdigest(), pulled["sha256"])
        self.assertEqual(self.archive.read_bytes(), destination.read_bytes())

    def test_rejects_tampered_cloud_object_before_publish(self):
        provider = _MemoryProvider()
        provider.push(self.archive)
        provider.objects["backup.qzra"] = provider.objects["backup.qzra"][:-1] + b"X"
        destination = self.root / "tampered.qzra"
        with self.assertRaises(FormatError):
            provider.pull("backup.qzra", destination)
        self.assertFalse(destination.exists())

    def test_requires_explicit_overwrite(self):
        provider = _MemoryProvider()
        provider.push(self.archive)
        destination = self.root / "existing.qzra"
        destination.write_bytes(b"keep")
        with self.assertRaises(QamzorError):
            provider.pull("backup.qzra", destination)
        self.assertEqual(b"keep", destination.read_bytes())

    def test_rejects_invalid_remote_names_and_non_https_endpoints(self):
        for name in ("", "../backup.qzra", "folder/backup.qzra", "backup.zip", "bad\nname.qzra"):
            with self.subTest(name=name), self.assertRaises(QamzorError):
                _safe_remote_name(name)
        with self.assertRaises(QamzorError):
            _https_parts("http://example.com/api")
        with self.assertRaises(QamzorError):
            _https_parts("https://user:secret@example.com/api")

    def test_provider_clients_require_runtime_credentials(self):
        for factory in (
            lambda: GoogleDriveBackup(""),
            lambda: DropboxBackup(""),
            lambda: OneDriveBackup(""),
            lambda: S3Backup("bucket-name", "", "secret", "us-east-1"),
        ):
            with self.subTest(factory=factory), self.assertRaises(QamzorError):
                factory()

    def test_s3_signature_uses_signed_host_and_payload_hash(self):
        client = S3Backup("qamzor-test-bucket", "ACCESSKEY", "SECRETKEY", "us-east-1")
        url, headers = client._object_url("daily/backup.qzra", hashlib.sha256(b"payload").hexdigest(), "PUT", 7)
        self.assertTrue(url.startswith("https://s3.us-east-1.amazonaws.com/"))
        self.assertIn("AWS4-HMAC-SHA256 Credential=ACCESSKEY/", headers["Authorization"])
        self.assertIn("x-amz-content-sha256", headers)
        self.assertEqual("7", headers["content-length"])

    def test_s3_multipart_upload_builds_signed_lifecycle_and_etag_manifest(self):
        client = S3Backup("qamzor-test-bucket", "ACCESSKEY", "SECRETKEY", "us-east-1")
        calls = []

        def fake_request(method, url, headers=None, source=None, size=None, output=None, allow_redirect=False):
            payload = source.read() if source is not None else b""
            calls.append((method, url, headers or {}, payload))
            if method == "POST" and "uploads=" in url:
                return 200, {}, b"<InitiateMultipartUploadResult><UploadId>upload-123</UploadId></InitiateMultipartUploadResult>"
            if method == "PUT":
                return 200, {"etag": '"part-etag"'}, b""
            if method == "POST":
                return 200, {}, b"<CompleteMultipartUploadResult/>"
            return 204, {}, b""

        client.request = fake_request
        client._multipart_upload("daily/backup.qzra", io.BytesIO(b"large-object-test"), len(b"large-object-test"))
        self.assertEqual(["POST", "PUT", "POST"], [call[0] for call in calls])
        self.assertIn("uploads=", calls[0][1])
        self.assertIn("partNumber=1&uploadId=upload-123", calls[1][1])
        self.assertIn(b"part-etag", calls[2][3])
        self.assertIn("uploadId=upload-123", calls[2][1])


if __name__ == "__main__":
    unittest.main()
