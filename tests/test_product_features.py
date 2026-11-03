from __future__ import annotations

import hashlib
import http.server
import io
import json
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

from qamzor.cloud import WebDavBackup
from qamzor.compare import compare_archives
from qamzor.format import QZRAReader, create_archive, QamzorError, FormatError, IntegrityError
from qamzor.jobs import JobCenter, JobControl
from qamzor.office_preview import preview_office_document
from qamzor.volumes import split_archive, join_archive
from qamzor.cli import main


class ProductFeatureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_smart_exclusions_and_qzignore(self):
        source = self.root / "Project"
        (source / "build").mkdir(parents=True)
        (source / "src").mkdir()
        (source / "src" / "main.py").write_text("main", encoding="utf-8")
        (source / "src" / "debug.tmp").write_text("ignored", encoding="utf-8")
        (source / "build" / "output.bin").write_bytes(b"ignored")
        (source / "keep.log").write_text("ignored by qzignore", encoding="utf-8")
        (source / ".qzignore").write_text("keep.log\n", encoding="utf-8")
        archive = self.root / "filtered.qzra"
        result = create_archive(archive, [source], exclude_patterns=["build/", "*.tmp"])
        self.assertEqual(1, result["files"])
        with QZRAReader(archive) as reader:
            paths = {entry.path for entry in reader.list_entries()}
        self.assertIn("Project/src/main.py", paths)
        self.assertNotIn("Project/build", paths)
        self.assertNotIn("Project/src/debug.tmp", paths)
        self.assertNotIn("Project/keep.log", paths)

    def test_nested_qzignore_patterns_apply_to_nested_folder(self):
        source = self.root / "Project"
        nested = source / "assets"
        nested.mkdir(parents=True)
        exports = nested / "exports"; exports.mkdir()
        (nested / ".qzignore").write_text("exports/*.psd\n", encoding="utf-8")
        (nested / "keep.png").write_bytes(b"png")
        (exports / "draft.psd").write_bytes(b"psd")
        archive = self.root / "nested-ignore.qzra"
        create_archive(archive, [source])
        with QZRAReader(archive) as reader:
            paths = {entry.path for entry in reader.list_entries()}
        self.assertIn("Project/assets/keep.png", paths)
        self.assertNotIn("Project/assets/exports/draft.psd", paths)
        self.assertNotIn("Project/assets/.qzignore", paths)

    def test_cli_smart_exclusion_compare_split_join_and_office_preview(self):
        source = self.root / "Project"; source.mkdir()
        (source / "keep.txt").write_text("keep", encoding="utf-8")
        (source / "skip.tmp").write_text("skip", encoding="utf-8")
        first = self.root / "first.qzra"
        with __import__("contextlib").redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["convert", str(first), str(source), "--exclude", "*.tmp"]))
        with QZRAReader(first) as reader:
            self.assertEqual({"Project", "Project/keep.txt"}, {entry.path for entry in reader.list_entries()})
        second_source = self.root / "Project2"; second_source.mkdir()
        (second_source / "keep.txt").write_text("keep", encoding="utf-8")
        second = self.root / "second.qzra"; create_archive(second, [second_source])
        compare_output = io.StringIO()
        with __import__("contextlib").redirect_stdout(compare_output):
            self.assertEqual(0, main(["compare", str(first), str(second)]))
        self.assertIn('"summary"', compare_output.getvalue())

        large_file = self.root / "large.bin"
        large_file.write_bytes(__import__("os").urandom(1024 * 1024 + 32))
        large_archive = self.root / "large.qzra"; create_archive(large_archive, [large_file])
        volumes = self.root / "cli-volumes"
        with __import__("contextlib").redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["split", str(large_archive), str(volumes), "--part-size-mib", "1"]))
        joined = self.root / "cli-joined.qzra"
        with __import__("contextlib").redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["join", str(volumes / "large.qzra.qzset"), str(joined)]))
        self.assertEqual(hashlib.sha256(large_archive.read_bytes()).digest(), hashlib.sha256(joined.read_bytes()).digest())

        office_dir = self.root / "Office"; office_dir.mkdir()
        docx = office_dir / "note.docx"
        with zipfile.ZipFile(docx, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("word/document.xml", "<w:document xmlns:w='urn:w'><w:p><w:r><w:t>CLI Preview Text</w:t></w:r></w:p></w:document>")
        office_archive = self.root / "office-cli.qzra"; create_archive(office_archive, [office_dir])
        office_output = io.StringIO()
        with __import__("contextlib").redirect_stdout(office_output):
            self.assertEqual(0, main(["preview", str(office_archive), "Office/note.docx", "--office"]))
        self.assertIn("CLI Preview Text", office_output.getvalue())

    def test_rejects_negated_exclusion_patterns(self):
        source = self.root / "in.txt"
        source.write_text("x", encoding="utf-8")
        with self.assertRaises(QamzorError):
            create_archive(self.root / "x.qzra", [source], exclude_patterns=["!keep.txt"])

    def test_compare_archives_reports_added_removed_changed_and_unchanged(self):
        a = self.root / "left" / "Project"
        b = self.root / "right" / "Project"
        a.mkdir(parents=True); b.mkdir(parents=True)
        (a / "same.txt").write_text("same", encoding="utf-8")
        (a / "change.txt").write_text("old", encoding="utf-8")
        (a / "remove.txt").write_text("remove", encoding="utf-8")
        (b / "same.txt").write_text("same", encoding="utf-8")
        (b / "change.txt").write_text("new", encoding="utf-8")
        (b / "add.txt").write_text("add", encoding="utf-8")
        first = self.root / "a.qzra"; second = self.root / "b.qzra"
        create_archive(first, [a]); create_archive(second, [b])
        report = compare_archives(first, second)
        self.assertEqual({"added": 1, "removed": 1, "changed": 1, "unchanged": 2}, report["summary"])
        self.assertTrue(any(item["path"] == "Project/add.txt" for item in report["added"]))

    def test_compare_verifies_actual_content_before_trusting_manifest_sha256(self):
        source_a = self.root / "verified-left" / "Project"
        source_b = self.root / "verified-right" / "Project"
        source_a.mkdir(parents=True); source_b.mkdir(parents=True)
        (source_a / "same-size.bin").write_bytes(b"AAAA")
        (source_b / "same-size.bin").write_bytes(b"BBBB")
        left = self.root / "verified-left.qzra"
        right = self.root / "verified-right.qzra"
        create_archive(left, [source_a]); create_archive(right, [source_b])
        report = compare_archives(left, right)
        self.assertEqual(["Project/same-size.bin"], [item["path"] for item in report["changed"]])
        self.assertTrue(report["content_verified"])
        self.assertEqual("verified-content-sha256", report["comparison_mode"])

        # Corrupt the payload while leaving its manifest digest untouched.
        raw = bytearray(right.read_bytes()); raw[-1] ^= 0x01; right.write_bytes(raw)
        with self.assertRaises(IntegrityError):
            compare_archives(left, right)

    def test_compare_verifies_qzra_13_protected_scopes_with_independent_passwords(self):
        source_a = self.root / "scope-left" / "Project"
        source_b = self.root / "scope-right" / "Project"
        (source_a / "Vault").mkdir(parents=True); (source_b / "Vault").mkdir(parents=True)
        (source_a / "Vault" / "secret.txt").write_text("secret A", encoding="utf-8")
        (source_b / "Vault" / "secret.txt").write_text("secret B", encoding="utf-8")
        left = self.root / "scope-left.qzra"; right = self.root / "scope-right.qzra"
        create_archive(left, [source_a], password="master-a", folder_passwords={"Project/Vault": "vault-a"})
        create_archive(right, [source_b], password="master-b", folder_passwords={"Project/Vault": "vault-b"})
        with self.assertRaises(QamzorError):
            compare_archives(left, right, left_password="master-a", right_password="master-b")
        report = compare_archives(
            left, right, left_password="master-a", right_password="master-b",
            left_folder_passwords={"Project/Vault": "vault-a"},
            right_folder_passwords={"Project/Vault": "vault-b"},
        )
        self.assertTrue(report["content_verified"])
        self.assertEqual(["Project/Vault/secret.txt"], [item["path"] for item in report["changed"]])

    def test_compare_qzra_13_requires_and_uses_master_password(self):
        source_a = self.root / "left" / "Project"; source_b = self.root / "right" / "Project"
        source_a.mkdir(parents=True); source_b.mkdir(parents=True)
        (source_a / "data.txt").write_text("same", encoding="utf-8")
        (source_b / "data.txt").write_text("changed", encoding="utf-8")
        left = self.root / "left-v13.qzra"; right = self.root / "right-v13.qzra"
        create_archive(left, [source_a], password="master-left", encrypt_metadata=True)
        create_archive(right, [source_b], password="master-right", encrypt_metadata=True)
        with self.assertRaises(QamzorError):
            compare_archives(left, right)
        result = compare_archives(left, right, left_password="master-left", right_password="master-right")
        self.assertEqual(1, result["summary"]["changed"])

    def test_split_rejects_symlinked_output_parent(self):
        source = self.root / "small.qzra"
        file = self.root / "source.txt"; file.write_text("content", encoding="utf-8")
        create_archive(source, [file])
        real_parent = self.root / "real"; real_parent.mkdir()
        link_parent = self.root / "linked"
        try:
            link_parent.symlink_to(real_parent, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Symbolic links are not available on this platform")
        with self.assertRaises(QamzorError):
            split_archive(source, link_parent / "parts")

    def test_split_join_round_trip_and_corruption_detection(self):
        source = self.root / "big.qzra"
        file = self.root / "random.bin"
        file.write_bytes(__import__("os").urandom(2 * 1024 * 1024 + 100))
        create_archive(source, [file])
        parts = self.root / "parts"
        report = split_archive(source, parts, part_size=1024 * 1024)
        self.assertGreaterEqual(report["parts"], 2)
        joined = self.root / "joined.qzra"
        joined_report = join_archive(report["set"], joined)
        self.assertTrue(joined_report["valid"])
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), hashlib.sha256(joined.read_bytes()).hexdigest())
        part = Path(report["parts_directory"]) / "big.qzra.part00001"
        raw = bytearray(part.read_bytes()); raw[-1] ^= 1; part.write_bytes(raw)
        with self.assertRaises(FormatError):
            join_archive(report["set"], self.root / "bad-joined.qzra")

    def test_office_docx_text_preview_without_user_extraction(self):
        source_dir = self.root / "Docs"; source_dir.mkdir()
        docx = source_dir / "notes.docx"
        with zipfile.ZipFile(docx, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'/>")
            package.writestr("word/document.xml", "<w:document xmlns:w='urn:w'><w:body><w:p><w:r><w:t>QAMZOR Office Preview</w:t></w:r></w:p></w:body></w:document>")
        archive_path = self.root / "docs.qzra"
        create_archive(archive_path, [source_dir])
        with QZRAReader(archive_path) as archive:
            result = preview_office_document(archive, "Docs/notes.docx")
        self.assertIn("QAMZOR Office Preview", result["text"])
        self.assertTrue(result["verified"])

    def test_office_preview_xlsx_and_pptx_text(self):
        source_dir = self.root / "Office"; source_dir.mkdir()
        xlsx = source_dir / "data.xlsx"
        with zipfile.ZipFile(xlsx, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("xl/sharedStrings.xml", "<sst xmlns='urn:x'><si><t>Revenue</t></si></sst>")
            package.writestr("xl/worksheets/sheet1.xml", "<worksheet xmlns='urn:x'><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1'><v>150</v></c></row></sheetData></worksheet>")
        pptx = source_dir / "deck.pptx"
        with zipfile.ZipFile(pptx, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("ppt/slides/slide1.xml", "<p:sld xmlns:p='urn:p' xmlns:a='urn:a'><a:t>Quarterly Results</a:t></p:sld>")
        archive_path = self.root / "office.qzra"; create_archive(archive_path, [source_dir])
        with QZRAReader(archive_path) as archive:
            sheet = preview_office_document(archive, "Office/data.xlsx")
            deck = preview_office_document(archive, "Office/deck.pptx")
        self.assertIn("Revenue", sheet["text"])
        self.assertIn("150", sheet["text"])
        self.assertIn("Quarterly Results", deck["text"])

    def test_job_control_pause_and_cancel_at_checkpoint(self):
        import time
        from qamzor.jobs import JobCancelledError
        paused = threading.Event(); done = threading.Event(); errors = []
        control = JobControl(on_state=lambda state: paused.set() if state == "paused" else None)
        control.pause()
        def paused_worker():
            try:
                control.checkpoint({"phase": "reading"})
                done.set()
            except Exception as exc:
                errors.append(exc)
        thread = threading.Thread(target=paused_worker); thread.start()
        self.assertTrue(paused.wait(2), "job did not enter paused state")
        self.assertFalse(done.is_set())
        control.resume(); thread.join(2)
        self.assertTrue(done.is_set())
        self.assertFalse(errors)

        paused2 = threading.Event(); cancelled = []
        control2 = JobControl(on_state=lambda state: paused2.set() if state == "paused" else None)
        control2.pause()
        def cancel_worker():
            try:
                control2.checkpoint({"phase": "reading"})
            except Exception as exc:
                cancelled.append(exc)
        thread2 = threading.Thread(target=cancel_worker); thread2.start()
        self.assertTrue(paused2.wait(2))
        control2.cancel(); thread2.join(2)
        self.assertTrue(cancelled and isinstance(cancelled[0], JobCancelledError))

    def test_job_center_marks_inflight_jobs_interrupted_after_restart(self):
        import time
        state = self.root / "restart-jobs"
        state.mkdir()
        job_id = "a" * 32
        record = {
            "id": job_id, "kind": "create_archive", "status": "running",
            "archive": str(self.root / "unfinished.qzra"), "sources": [],
            "options": {}, "progress": {"phase": "archive", "completed_bytes": 4, "total_bytes": 9},
            "created_at": time.time(), "updated_at": time.time(), "error": None, "result": None,
        }
        (state / f"{job_id}.json").write_text(json.dumps(record), encoding="utf-8")
        center = JobCenter(state, workers=1)
        try:
            recovered = center.get(job_id)
            self.assertEqual("interrupted", recovered["status"])
            self.assertIn("submit again", recovered["error"])
            self.assertEqual(4, recovered["progress"]["completed_bytes"])
            persisted = json.loads((state / f"{job_id}.json").read_text(encoding="utf-8"))
            self.assertEqual("interrupted", persisted["status"])
        finally:
            center.shutdown()

    def test_job_center_create_pause_resume_and_secret_redaction(self):
        control = JobControl()
        paused = threading.Event(); finished = threading.Event()
        control.pause()
        def work():
            control.checkpoint({"phase": "test"})
            finished.set()
        thread = threading.Thread(target=work)
        thread.start()
        # Resume after ensuring checkpoint has had time to observe pause.
        import time
        time.sleep(0.05)
        control.resume()
        thread.join(2)
        self.assertTrue(finished.is_set())

        source = self.root / "input.txt"; source.write_text("job data", encoding="utf-8")
        center = JobCenter(self.root / "jobs", workers=1)
        try:
            job_id = center.submit_create_archive(self.root / "job.qzra", [source], password="must-not-persist")
            record = center.wait(job_id, timeout=10)
            self.assertEqual("completed", record["status"])
            persisted = (self.root / "jobs" / f"{job_id}.json").read_text(encoding="utf-8")
            self.assertNotIn("must-not-persist", persisted)
            with QZRAReader(self.root / "job.qzra", password="must-not-persist") as archive:
                self.assertTrue(archive.verify()["valid"])
        finally:
            center.shutdown()


class _WebDavHandler(http.server.BaseHTTPRequestHandler):
    store: dict[str, bytes] = {}
    auth_value = "Basic "
    def do_PUT(self):
        if self.headers.get("Authorization") != self.auth_value:
            self.send_error(401); return
        length = int(self.headers.get("Content-Length", "0"))
        self.store[self.path] = self.rfile.read(length)
        self.send_response(201); self.end_headers()
    def do_GET(self):
        if self.headers.get("Authorization") != self.auth_value:
            self.send_error(401); return
        value = self.store.get(self.path)
        if value is None:
            self.send_error(404); return
        self.send_response(200); self.send_header("Content-Length", str(len(value))); self.end_headers(); self.wfile.write(value)
    def log_message(self, *args):
        pass


class CloudBackupTests(unittest.TestCase):
    def test_webdav_remote_name_reserves_space_for_sidecar(self):
        store = WebDavBackup("http://127.0.0.1:8080/dav/", "user", "secret")
        self.assertEqual(187, len(store._validate_name("a" * 187)))
        with self.assertRaises(QamzorError):
            store._validate_name("a" * 188)

    def test_webdav_push_pull_verifies_checksum(self):
        import base64
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            src = root / "archive.qzra"
            file = root / "source.txt"; file.write_text("cloud backup", encoding="utf-8")
            create_archive(src, [file])
            _WebDavHandler.store = {}
            _WebDavHandler.auth_value = "Basic " + base64.b64encode(b"user:secret").decode("ascii")
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _WebDavHandler)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                store = WebDavBackup(f"http://127.0.0.1:{server.server_port}/dav/", "user", "secret")
                pushed = store.push(src)
                out = root / "restored.qzra"
                pulled = store.pull(pushed["remote_name"], out)
                self.assertTrue(pulled["valid"])
                self.assertEqual(hashlib.sha256(src.read_bytes()).hexdigest(), hashlib.sha256(out.read_bytes()).hexdigest())
                # Corrupt the remote object while keeping its old manifest; pull must fail closed.
                _WebDavHandler.store["/dav/archive.qzra"] = b"tampered"
                rejected = root / "must-not-exist.qzra"
                with self.assertRaises((FormatError, QamzorError)):
                    store.pull("archive.qzra", rejected)
                self.assertFalse(rejected.exists())
            finally:
                server.shutdown(); thread.join(2); server.server_close()
