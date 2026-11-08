from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from qamzor.cli import main
from qamzor.format import open_archive


class QamzorCliConversionTests(unittest.TestCase):
    def test_convert_command_packages_file_and_folder_as_qzra(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            single = root / "notes.txt"
            single.write_text("QAMZOR native container", encoding="utf-8")
            folder = root / "project"
            (folder / "src").mkdir(parents=True)
            (folder / "src" / "main.kt").write_text("fun main() = Unit", encoding="utf-8")
            archive = root / "bundle.qzra"
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = main(["convert", str(archive), str(single), str(folder)])
            self.assertEqual(0, code)
            result = json.loads(output.getvalue())
            self.assertTrue(archive.is_file())
            self.assertEqual(2, result["files"])
            with open_archive(archive) as opened:
                self.assertIn("notes.txt", {entry.path for entry in opened.list_entries()})
                self.assertIn("project/src/main.kt", {entry.path for entry in opened.list_entries()})
            self.assertEqual("QAMZOR native container", single.read_text(encoding="utf-8"))

    def test_convert_command_rejects_non_qzra_output_extension(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source.txt"
            source.write_text("unchanged", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = main(["convert", str(root / "output.zip"), str(source)])
            self.assertEqual(2, code)
            self.assertIn("must use the .qzra extension", stderr.getvalue())

    def test_qzra_13_cli_hides_metadata_and_accepts_folder_password_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "Project"
            vault = project / "Finance"
            vault.mkdir(parents=True)
            (vault / "forecast.txt").write_text("private forecast", encoding="utf-8")
            archive = root / "private.qzra"
            master_file = root / "master.txt"
            folder_file = root / "folder.txt"
            master_file.write_text("master-password", encoding="utf-8")
            folder_file.write_text("folder-password", encoding="utf-8")
            with patch("getpass.getpass", side_effect=["master-password", "master-password", "folder-password", "folder-password"]):
                with contextlib.redirect_stdout(io.StringIO()):
                    code = main([
                        "create", str(archive), str(project), "--hide-metadata",
                        "--protect-folder", "Project/Finance",
                    ])
            self.assertEqual(0, code)
            raw = archive.read_bytes()
            self.assertNotIn(b"forecast.txt", raw)
            listing = io.StringIO()
            with contextlib.redirect_stdout(listing):
                code = main(["list", str(archive), "--password-file", str(master_file)])
            self.assertEqual(0, code)
            self.assertIn("Project/Finance/forecast.txt", listing.getvalue())
            result = io.StringIO()
            with contextlib.redirect_stdout(result):
                code = main([
                    "verify", str(archive), "--password-file", str(master_file),
                    "--folder-password-file", f"Project/Finance={folder_file}",
                ])
            self.assertEqual(0, code)
            self.assertTrue(json.loads(result.getvalue())["valid"])


if __name__ == "__main__":
    unittest.main()
