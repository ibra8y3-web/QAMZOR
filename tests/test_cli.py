from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
