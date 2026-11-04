package org.qamzor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SnapshotArchiveStoreTest {
    @TempDir lateinit var temp: Path

    @Test fun createsListsVerifiesAndRestoresImmutableSnapshots() {
        val source = temp.resolve("project").toFile().apply { mkdirs() }
        File(source, "value.txt").writeText("version one")
        val store = SnapshotArchiveStore(temp.resolve("snapshots").toFile())
        val first = store.createSnapshot(source)
        File(source, "value.txt").writeText("version two")
        val second = store.createSnapshot(source)
        assertEquals(2, store.listSnapshots().size)
        assertEquals(second.id, store.listSnapshots().first().id)
        assertTrue(store.verifySnapshot(first.id).valid)
        val restored = temp.resolve("restored").toFile()
        store.restoreSnapshot(first.id, restored)
        assertEquals("version one", File(restored, "project/value.txt").readText())
    }

    @Test fun rejectsModifiedSnapshotBeforeRestore() {
        val source = temp.resolve("source.txt").toFile().apply { writeText("safe") }
        val root = temp.resolve("store").toFile()
        val store = SnapshotArchiveStore(root)
        val snapshot = store.createSnapshot(source)
        File(root, snapshot.archiveName).appendText("tamper")
        val restored = temp.resolve("out").toFile()
        assertThrows(IllegalArgumentException::class.java) { store.restoreSnapshot(snapshot.id, restored) }
        assertFalse(restored.exists())
    }

    @Test fun pruneKeepsNewestRequestedCount() {
        val source = temp.resolve("source.txt").toFile().apply { writeText("one") }
        val store = SnapshotArchiveStore(temp.resolve("store").toFile())
        val first = store.createSnapshot(source)
        Thread.sleep(3)
        source.writeText("two")
        val second = store.createSnapshot(source)
        assertEquals(1, store.prune(1))
        assertEquals(listOf(second.id), store.listSnapshots().map { it.id })
        assertThrows(IllegalStateException::class.java) { store.verifySnapshot(first.id) }
    }
}
