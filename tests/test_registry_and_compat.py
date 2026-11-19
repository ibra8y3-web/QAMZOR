"""Keeps the File Format Registry and the Compatibility Suite honest against the reference engine."""
import hashlib
import importlib.util
import json
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from qamzor import format as qformat
from qamzor import recovery, signatures

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = json.loads((ROOT / "registry" / "qzra-registry.json").read_text(encoding="utf-8"))
SUITE = json.loads((ROOT / "compat" / "manifest.json").read_text(encoding="utf-8"))


def fmt(format_id):
    return next(f for f in REGISTRY["formats"] if f["id"] == format_id)


class RegistryMatchesReferenceEngine(unittest.TestCase):
    def test_qzra_header_and_magic(self):
        qzra = fmt("QZRA")
        self.assertEqual(bytes.fromhex(qzra["magic"]["bytes_hex"]), qformat.MAGIC)
        self.assertEqual(qzra["header"]["struct"], qformat.HEADER.format.lstrip("<"))
        self.assertEqual(qzra["header"]["size_bytes"], qformat.HEADER_SIZE)
        self.assertEqual(qzra["major_version"], qformat.MAJOR_VERSION)

    def test_versions_cover_every_minor_up_to_writer_version(self):
        minors = [v["minor"] for v in fmt("QZRA")["versions"]]
        self.assertEqual(minors, list(range(0, qformat.MINOR_VERSION + 1)))

    def test_limits(self):
        limits = fmt("QZRA")["limits"]
        self.assertEqual(limits["max_manifest_bytes"], qformat.MAX_MANIFEST_SIZE)
        self.assertEqual(limits["max_entries"], qformat.MAX_ENTRIES)
        self.assertEqual(limits["max_chunk_bytes"], qformat.MAX_CHUNK_SIZE)
        self.assertEqual(limits["max_chunk_compressed_bytes"], qformat.MAX_CHUNK_COMPRESSED_SIZE)
        self.assertEqual(limits["max_folder_scopes"], qformat.MAX_FOLDER_SCOPES)

    def test_crypto_parameters_and_labels(self):
        crypto = fmt("QZRA")["crypto"]
        self.assertEqual((crypto["kdf"]["n"], crypto["kdf"]["r"], crypto["kdf"]["p"]),
                         (qformat.SCRYPT_N, qformat.SCRYPT_R, qformat.SCRYPT_P))
        self.assertEqual(crypto["v13_envelope"]["magic_ascii"].encode(), qformat.V13_MANIFEST_MAGIC)
        source = (ROOT / "src" / "qamzor" / "format.py").read_text(encoding="utf-8")
        for prefix in crypto["aad_prefixes"]:
            self.assertIn(prefix.replace("\\0", "\\0"), source.replace("\\0", "\\0"))
        for purpose in crypto["v13_subkey_purposes"]:
            self.assertIn(f'b"{purpose}"', source)

    def test_sidecar_formats(self):
        sig = fmt("QZRA-SIGNATURE")
        self.assertEqual(sig["json_identity"], {"format": signatures.SIGNATURE_FORMAT, "version": signatures.SIGNATURE_VERSION})
        self.assertEqual(sig["domain_separation"].replace("\\0", "\0").encode(), signatures.SIGNATURE_DOMAIN)
        rec = fmt("QZRA-RECOVERY")
        self.assertEqual(bytes.fromhex(rec["magic"]["bytes_hex"]), recovery.RECOVERY_MAGIC)
        self.assertEqual(rec["header"]["struct"], recovery.RECOVERY_HEADER.format.lstrip("<"))
        self.assertEqual(rec["header"]["size_bytes"], recovery.RECOVERY_HEADER.size)

    def test_extensions_and_mimes_are_unique(self):
        exts = [f["extension"] for f in REGISTRY["formats"]]
        mimes = [f["mime"] for f in REGISTRY["formats"]] + [a for f in REGISTRY["formats"] for a in f["mime_aliases"]]
        self.assertEqual(len(exts), len(set(exts)))
        self.assertEqual(len(mimes), len(set(mimes)))
        for ext in exts:
            self.assertRegex(ext, r"^\.[a-z0-9]+$")

    def test_android_manifest_declares_registry_mime_types(self):
        manifest = (ROOT / "androidApp" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        qzra = fmt("QZRA")
        for mime in [qzra["mime"], *qzra["mime_aliases"]]:
            self.assertIn(f'android:mimeType="{mime}"', manifest)

    def test_shared_mime_info_matches_registry(self):
        ns = {"m": "http://www.freedesktop.org/standards/shared-mime-info"}
        tree = ET.parse(ROOT / "registry" / "qzra.xml").getroot()
        declared = {t.get("type"): t for t in tree.findall("m:mime-type", ns)}
        for entry in REGISTRY["formats"]:
            node = declared[entry["mime"]]
            self.assertEqual(node.find("m:glob", ns).get("pattern"), "*" + entry["extension"])
            self.assertEqual({a.get("type") for a in node.findall("m:alias", ns)}, set(entry["mime_aliases"]))
            if "magic" in entry:
                value = node.find("m:magic/m:match", ns).get("value")
                self.assertEqual(value.replace("\\r", "\r").replace("\\n", "\n").replace("\\x1a", "\x1a").encode("latin-1"),
                                 bytes.fromhex(entry["magic"]["bytes_hex"]))


class CompatibilitySuiteIntegrity(unittest.TestCase):
    def test_error_categories_match_registry(self):
        self.assertEqual(set(SUITE["error_categories"]), set(REGISTRY["error_categories"]))

    def test_every_category_and_version_is_covered(self):
        negatives = {v["expect"]["category"] for v in SUITE["vectors"] if v["expect"]["outcome"] == "error"}
        self.assertEqual(negatives, set(REGISTRY["error_categories"]))
        positives = {v["version"] for v in SUITE["vectors"] if v["kind"] == "positive"}
        self.assertEqual(positives, {"1.0", "1.1", "1.2", "1.3"})

    def test_sha256_sums_match_files(self):
        listed = dict(line.split("  ", 1)[::-1] for line in (ROOT / "compat" / "vectors" / "SHA256SUMS").read_text().splitlines())
        self.assertEqual(set(listed), {v["file"] for v in SUITE["vectors"]})
        for v in SUITE["vectors"]:
            digest = hashlib.sha256((ROOT / "compat" / "vectors" / v["file"]).read_bytes()).hexdigest()
            self.assertEqual(digest, v["sha256"], v["file"])
            self.assertEqual(digest, listed[v["file"]], v["file"])

    def test_python_reference_passes_every_vector(self):
        spec = importlib.util.spec_from_file_location("python_runner", ROOT / "compat" / "runners" / "python_runner.py")
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        for v in SUITE["vectors"]:
            with self.subTest(vector=v["id"]):
                self.assertIsNone(runner.run_vector(ROOT / "compat", v))

    def test_published_passwords_are_marked_test_only(self):
        self.assertEqual(set(SUITE["test_only_passwords"]), {"master", "vault", "inner"})
        self.assertTrue(all(re.fullmatch(r"QAMZOR-compat-[a-z]+", p) for p in SUITE["test_only_passwords"].values()))


if __name__ == "__main__":
    unittest.main()
