import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("bundle_tool", ROOT / "tools" / "make_central_bundle.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

GROUP, VERSION = "org.example", "1.0.0"


def make_repo(root: Path, artifact: str, *, packaging=".jar", signed=True, skip=None):
    d = root.joinpath("org", "example", artifact, VERSION)
    d.mkdir(parents=True)
    for suffix in (packaging, ".pom", "-sources.jar", "-javadoc.jar", ".module"):
        if suffix == skip:
            continue
        f = d / f"{artifact}-{VERSION}{suffix}"
        f.write_bytes(b"content-" + suffix.encode())
        if signed:
            (d / (f.name + ".asc")).write_bytes(b"-----BEGIN PGP SIGNATURE-----")
        (d / (f.name + ".sha1")).write_text("stale")
    (d.parent / "maven-metadata.xml").write_text("<metadata/>")
    (d / "maven-metadata.xml").write_text("<metadata/>")


class BundleTool(unittest.TestCase):
    def test_builds_bundle_with_checksums_and_no_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo, out = Path(tmp, "repo"), Path(tmp, "bundle.zip")
            make_repo(repo, "lib-a")
            make_repo(repo, "lib-b", packaging=".aar")
            self.assertEqual(tool.build(repo, out, GROUP, VERSION, ["lib-a", "lib-b"]), [])
            with zipfile.ZipFile(out) as z:
                names = set(z.namelist())
                base = "org/example/lib-a/1.0.0/lib-a-1.0.0"
                self.assertIn(base + ".jar", names)
                self.assertIn(base + ".jar.asc", names)
                for algo in ("md5", "sha1", "sha256"):
                    self.assertIn(f"{base}.jar.{algo}", names)
                self.assertEqual(z.read(base + ".jar.sha256").decode(), hashlib.sha256(b"content-.jar").hexdigest())
                self.assertEqual(z.read(base + ".jar.sha1").decode(), hashlib.sha1(b"content-.jar").hexdigest())  # stale value replaced
                self.assertFalse(any("maven-metadata" in n for n in names))
                self.assertIn("org/example/lib-b/1.0.0/lib-b-1.0.0.aar", names)

    def test_refuses_unsigned_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp, "repo")
            make_repo(repo, "lib-a", signed=False)
            problems = tool.build(repo, Path(tmp, "b.zip"), GROUP, VERSION, ["lib-a"])
            self.assertTrue(any("no detached signature" in p for p in problems))
            self.assertFalse(Path(tmp, "b.zip").exists())

    def test_refuses_missing_required_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp, "repo")
            make_repo(repo, "lib-a", skip="-javadoc.jar")
            problems = tool.build(repo, Path(tmp, "b.zip"), GROUP, VERSION, ["lib-a"])
            self.assertTrue(any("javadoc" in p for p in problems))


if __name__ == "__main__":
    unittest.main()
