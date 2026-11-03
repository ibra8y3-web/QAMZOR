from __future__ import annotations

import contextlib
import io
import shutil
import tempfile
import unittest
from pathlib import Path

from qamzor.format import IntegrityError, QamzorError, SafetyError
from qamzor.repository import Repository, RepositoryIntegrityError


class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "project"
        self.source.mkdir()
        self.repo_path = self.root / "backup.qamzor"
        self.repository = Repository(self.repo_path, create=True)

    def tearDown(self):
        self.temp.cleanup()

    def test_incremental_snapshots_reuse_content_and_restore(self):
        shared = b"same bytes stored once across snapshots\n" * 100
        (self.source / "a.txt").write_bytes(shared)
        (self.source / "b.txt").write_bytes(shared)
        (self.source / "empty-dir").mkdir()
        first = self.repository.create_snapshot([self.source], message="initial")
        self.assertEqual(first["new_objects"], 1)
        self.assertEqual(first["reused_objects"], 1)

        (self.source / "a.txt").write_bytes(b"new version\n")
        (self.source / "c.txt").write_bytes(b"new file\n")
        second = self.repository.create_snapshot([self.source], message="update")
        self.assertEqual(second["parent_id"], first["id"])
        self.assertEqual(second["new_objects"], 2)
        self.assertEqual(second["reused_objects"], 1)
        self.assertTrue(self.repository.verify()["valid"])

        restore_first = self.root / "restore-first"
        restore_second = self.root / "restore-second"
        self.repository.restore_snapshot(first["id"], restore_first)
        self.repository.restore_snapshot(second["id"], restore_second)
        self.assertEqual((restore_first / "project" / "a.txt").read_bytes(), shared)
        self.assertEqual((restore_first / "project" / "b.txt").read_bytes(), shared)
        self.assertTrue((restore_first / "project" / "empty-dir").is_dir())
        self.assertEqual((restore_second / "project" / "a.txt").read_bytes(), b"new version\n")
        self.assertEqual((restore_second / "project" / "b.txt").read_bytes(), shared)
        self.assertEqual((restore_second / "project" / "c.txt").read_bytes(), b"new file\n")

    def test_retention_plan_and_garbage_collection(self):
        (self.source / "old.txt").write_bytes(b"old unique bytes")
        (self.source / "keep.txt").write_bytes(b"shared keep bytes")
        first = self.repository.create_snapshot([self.source])
        (self.source / "old.txt").unlink()
        (self.source / "new.txt").write_bytes(b"new unique bytes")
        second = self.repository.create_snapshot([self.source])
        plan = self.repository.prune(keep_last=1, dry_run=True)
        self.assertEqual(plan["remove_snapshots"], [first["id"]])
        self.assertGreaterEqual(plan["unreferenced_objects"], 1)
        result = self.repository.prune(keep_last=1, dry_run=False)
        self.assertEqual(result["removed_snapshots"], 1)
        self.assertEqual([item["id"] for item in self.repository.list_snapshots()], [second["id"]])
        self.assertTrue(self.repository.verify()["valid"])

    def test_portable_delta_applies_to_a_repository_with_the_exact_base(self):
        (self.source / "stable.txt").write_bytes(b"unchanged content" * 100)
        (self.source / "changing.txt").write_bytes(b"version one")
        base = self.repository.create_snapshot([self.source], message="base")
        clone_path = self.root / "cloned-repository"
        shutil.copytree(self.repo_path, clone_path)
        clone = Repository(clone_path)

        (self.source / "changing.txt").write_bytes(b"version two is new")
        (self.source / "added.txt").write_bytes(b"added in delta")
        target = self.repository.create_snapshot([self.source], message="target")
        delta_path = self.root / "update.qzd"
        exported = self.repository.export_delta(base["id"], target["id"], delta_path)
        self.assertEqual(exported["new_objects"], 2)

        applied = clone.apply_delta(delta_path)
        self.assertEqual(applied["snapshot_id"], target["id"])
        self.assertTrue(clone.verify()["valid"])
        restored = self.root / "delta-restored"
        clone.restore_snapshot(target["id"], restored)
        self.assertEqual((restored / "project" / "stable.txt").read_bytes(), b"unchanged content" * 100)
        self.assertEqual((restored / "project" / "changing.txt").read_bytes(), b"version two is new")
        self.assertEqual((restored / "project" / "added.txt").read_bytes(), b"added in delta")

    def test_portable_delta_rejects_modified_payload_and_wrong_base(self):
        (self.source / "before.txt").write_bytes(b"before")
        base = self.repository.create_snapshot([self.source])
        clone_path = self.root / "delta-clone"
        shutil.copytree(self.repo_path, clone_path)
        clone = Repository(clone_path)
        (self.source / "after.txt").write_bytes(b"new payload" * 100)
        target = self.repository.create_snapshot([self.source])
        delta_path = self.root / "corrupt.qzd"
        self.repository.export_delta(base["id"], target["id"], delta_path)
        damaged = bytearray(delta_path.read_bytes())
        damaged[-1] ^= 0x01
        delta_path.write_bytes(damaged)
        with self.assertRaises(RepositoryIntegrityError):
            clone.apply_delta(delta_path)
        self.assertEqual(len(clone.list_snapshots()), 1)

        empty_repo = Repository(self.root / "empty-delta-repo", create=True)
        with self.assertRaises(QamzorError):
            empty_repo.apply_delta(delta_path)
        self.assertEqual(empty_repo.list_snapshots(), [])

    def test_writer_lock_prevents_overlapping_repository_mutations(self):
        (self.source / "data.txt").write_text("data", encoding="utf-8")
        lock = self.repo_path / ".qamzor-write.lock"
        lock.write_text("pid=external-test\n", encoding="ascii")
        try:
            with self.assertRaises(QamzorError):
                self.repository.create_snapshot([self.source])
            self.assertEqual(self.repository.list_snapshots(), [])
        finally:
            lock.unlink(missing_ok=True)

    def test_many_file_snapshot_and_restore_stress(self):
        many = self.root / "many-files"
        many.mkdir()
        for index in range(60):
            content = f"record-{index % 12}-common-payload\n".encode("ascii") * 64
            (many / f"item-{index:03d}.bin").write_bytes(content)
        snapshot = self.repository.create_snapshot([many])
        self.assertEqual(snapshot["files"], 60)
        self.assertLess(snapshot["new_objects"], 60)
        self.assertTrue(self.repository.verify(snapshot["id"])["valid"])
        restored = self.root / "many-restored"
        self.repository.restore_snapshot(snapshot["id"], restored)
        for index in range(60):
            expected = f"record-{index % 12}-common-payload\n".encode("ascii") * 64
            self.assertEqual((restored / "many-files" / f"item-{index:03d}.bin").read_bytes(), expected)

    def test_restore_quotas_are_checked_before_destination_creation(self):
        (self.source / "data.bin").write_bytes(b"x" * 2048)
        snapshot = self.repository.create_snapshot([self.source])
        destination = self.root / "quota-restore"
        with self.assertRaises(SafetyError):
            self.repository.restore_snapshot(snapshot["id"], destination, max_total_bytes=1)
        self.assertFalse(destination.exists())
        with self.assertRaises(SafetyError):
            self.repository.restore_snapshot(snapshot["id"], destination, max_files=0)
        self.assertFalse(destination.exists())

    def test_corrupt_content_object_is_detected(self):
        (self.source / "data.bin").write_bytes(b"integrity protected")
        snapshot = self.repository.create_snapshot([self.source])
        record = self.repository._read_catalog()["snapshots"][0]
        _, entries = self.repository._load_snapshot(snapshot["id"])
        digest = next(item["sha256"] for item in entries if item["type"] == "file")
        blob = self.repository._object_path(digest)
        blob.write_bytes(b"tampered object")
        with self.assertRaises(RepositoryIntegrityError):
            self.repository.verify(snapshot["id"])

    def test_object_store_shard_symlink_is_rejected(self):
        (self.source / "data.bin").write_bytes(b"symlink guard")
        snapshot = self.repository.create_snapshot([self.source])
        _, entries = self.repository._load_snapshot(snapshot["id"])
        digest = next(item["sha256"] for item in entries if item["type"] == "file")
        shard = self.repository.objects_path / digest[:2]
        outside = self.root / "outside-shard"
        shard.rename(outside)
        try:
            shard.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError):
            outside.rename(shard)
            self.skipTest("Directory symlinks are not available on this platform")
        with self.assertRaises(SafetyError):
            self.repository.verify(snapshot["id"])

    def test_existing_restore_files_are_not_overwritten_by_default(self):
        (self.source / "data.txt").write_text("original", encoding="utf-8")
        snapshot = self.repository.create_snapshot([self.source])
        output = self.root / "restore"
        target = output / "project" / "data.txt"
        target.parent.mkdir(parents=True)
        target.write_text("keep", encoding="utf-8")
        with self.assertRaises(QamzorError):
            self.repository.restore_snapshot(snapshot["id"], output)
        self.assertEqual(target.read_text(encoding="utf-8"), "keep")
        self.repository.restore_snapshot(snapshot["id"], output, overwrite=True)
        self.assertEqual(target.read_text(encoding="utf-8"), "original")

    def test_cli_repository_lifecycle(self):
        from qamzor.cli import main
        source = self.root / "cli-source"
        source.mkdir()
        (source / "hello.txt").write_text("hello", encoding="utf-8")
        repo_path = self.root / "cli-repo"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(["repo", "init", str(repo_path)]), 0)
            self.assertEqual(main(["repo", "snapshot", str(repo_path), str(source)]), 0)
            base_id = Repository(repo_path).list_snapshots()[0]["id"]
            clone_path = self.root / "cli-repo-clone"
            shutil.copytree(repo_path, clone_path)
            (source / "hello.txt").write_text("hello delta", encoding="utf-8")
            (source / "new.txt").write_text("new", encoding="utf-8")
            self.assertEqual(main(["repo", "snapshot", str(repo_path), str(source)]), 0)
            target_id = Repository(repo_path).list_snapshots()[-1]["id"]
            delta_path = self.root / "cli-update.qzd"
            self.assertEqual(main(["repo", "export-delta", str(repo_path), base_id, target_id, "--output", str(delta_path)]), 0)
            self.assertEqual(main(["repo", "apply-delta", str(clone_path), str(delta_path)]), 0)
            self.assertEqual(main(["repo", "verify", str(clone_path)]), 0)
            restored = self.root / "cli-restored"
            self.assertEqual(main(["repo", "restore", str(clone_path), target_id, "--output", str(restored)]), 0)
            self.assertEqual(main(["repo", "prune", str(clone_path), "--keep-last", "1"]), 0)
        self.assertIn('"initialized": true', output.getvalue())
        self.assertIn('"valid": true', output.getvalue())
        self.assertEqual((restored / "cli-source" / "hello.txt").read_text(encoding="utf-8"), "hello delta")
        self.assertEqual((restored / "cli-source" / "new.txt").read_text(encoding="utf-8"), "new")


if __name__ == "__main__":
    unittest.main()
