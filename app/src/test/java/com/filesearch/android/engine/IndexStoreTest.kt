package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexStoreTest {

    private fun open(dir: File, name: String = "idx-${System.nanoTime()}.sqlite"): IndexStore =
        IndexStore(File(dir, name).absolutePath)

    private fun fileId(store: IndexStore, path: String, kind: String = "doc"): Long =
        store.upsertFile(path = path, kind = kind, size = 100, mtime = 1, inode = 42, device = 7)

    @Test
    fun `insert plus segments round-trips with fts match on body`(@TempDir dir: File) {
        open(dir).use { store ->
            val id = fileId(store, "/a/note.txt")
            val s1 = store.addSegment(id, "chunk", "text", 0.0, 1.0, "quick fox", null, "note.txt", "the quick brown fox", "the quick brown fox")
            val s2 = store.addSegment(id, "chunk", "text", 1.0, 2.0, "slow dog", null, "note.txt", "a slow lazy dog", "a slow lazy dog")
            assertTrue(store.matchTerms("body : quick").contains(s1))
            assertFalse(store.matchTerms("body : quick").contains(s2))
            assertEquals(2, store.countSegments(id))
        }
    }

    @Test
    fun `move re-path keeps segments and fts hits`(@TempDir dir: File) {
        open(dir).use { store ->
            val id = fileId(store, "/a/photo.jpg", "image")
            store.addSegment(id, "name", "none", null, null, "beach sunset", null, "photo.jpg", "beach sunset", "beach sunset")
            assertTrue(store.moveFile("/a/photo.jpg", "/b/photo.jpg"))
            assertEquals(1, store.countSegments(store.upsertFile("/b/photo.jpg", "image", 100, 1, 42, 7)))
            assertEquals(1, store.matchTerms("body : sunset").size)
            assertEquals(0, store.pending(10).count { it.path == "/a/photo.jpg" })
        }
    }

    @Test
    fun `delete cascades segments and fts`(@TempDir dir: File) {
        open(dir).use { store ->
            val id = fileId(store, "/gone.txt")
            store.addSegment(id, "chunk", "text", null, null, "vanishing words", null, "gone.txt", "vanishing words here", "vanishing words here")
            assertTrue(store.deleteFile("/gone.txt"))
            assertEquals(0, store.countSegments(id))
            assertTrue(store.matchTerms("body : vanishing").isEmpty())
            assertEquals(0, store.countFiles())
        }
    }

    @Test
    fun `pruneMissing deletes only paths outside the known set`(@TempDir dir: File) {
        open(dir).use { store ->
            fileId(store, "/keep1.txt")
            fileId(store, "/keep2.txt")
            val gone = fileId(store, "/gone.txt")
            store.addSegment(gone, "chunk", "text", null, null, "doomed", null, "gone.txt", "doomed words", "doomed words")
            assertEquals(1, store.pruneMissing(setOf("/keep1.txt", "/keep2.txt")))
            assertEquals(2, store.countFiles())
            assertTrue(store.matchTerms("body : doomed").isEmpty())
        }
    }

    @Test
    fun `pending orders screenshots first through video last`(@TempDir dir: File) {
        open(dir).use { store ->
            for (k in listOf("video", "audio", "doc", "pdf", "image", "screenshot")) {
                store.upsertFile("/f.$k", k, 100, 1, k.hashCode().toLong(), 7)
            }
            store.upsertFile("/done.txt", "doc", 100, 1, 99, 7, state = "done")
            val kinds = store.pending(10).map { it.kind }
            assertEquals(listOf("screenshot", "image", "pdf", "doc", "audio", "video"), kinds)
        }
    }

    @Test
    fun `upsert keeps done state when fingerprint unchanged, pending when changed`(@TempDir dir: File) {
        open(dir).use { store ->
            store.upsertFile("/a.txt", "doc", 100, 1, 42, 7, state = "done")
            store.upsertFile("/a.txt", "doc", 100, 1, 42, 7)
            assertTrue(store.pending(10).none { it.path == "/a.txt" })
            store.upsertFile("/a.txt", "doc", 200, 1, 42, 7)
            assertTrue(store.pending(10).any { it.path == "/a.txt" })
        }
    }

    @Test
    fun `move onto existing path returns false instead of throwing`(@TempDir dir: File) {
        open(dir).use { store ->
            fileId(store, "/a.txt")
            fileId(store, "/b.txt")
            assertFalse(store.moveFile("/a.txt", "/b.txt"))
        }
    }

    @Test
    fun `upsert same path updates state without duplicates`(@TempDir dir: File) {
        open(dir).use { store ->
            fileId(store, "/a.txt")
            store.upsertFile("/a.txt", "doc", 100, 2, 42, 7, state = "done")
            assertEquals(1, store.countFiles())
            assertTrue(store.pending(10).none { it.path == "/a.txt" })
        }
    }

    @Test
    fun `fingerprint match returns true and keeps data`(@TempDir dir: File) {
        open(dir).use { store ->
            fileId(store, "/a.txt")
            val fp = Fingerprint("embgemma2@r1 cpu fp16 256 budgets1")
            assertFalse(store.checkFingerprint(fp))
            assertEquals(1, store.countFiles())
            assertTrue(store.checkFingerprint(fp))
            assertEquals(1, store.countFiles())
        }
    }

    @Test
    fun `fingerprint mismatch resets index and returns false`(@TempDir dir: File) {
        open(dir).use { store ->
            val id = fileId(store, "/a.txt")
            store.addSegment(id, "chunk", "text", null, null, "old", null, "a.txt", "old words", "old words")
            assertFalse(store.checkFingerprint(Fingerprint("embgemma2@r1 cpu fp16 256 budgets1")))
            assertFalse(store.checkFingerprint(Fingerprint("embgemma2@r2 npu int8 256 budgets2")))
            assertEquals(0, store.countFiles())
            assertTrue(store.matchTerms("body : old").isEmpty())
            assertTrue(store.checkFingerprint(Fingerprint("embgemma2@r2 npu int8 256 budgets2")))
        }
    }

    @Test
    fun `bengali word matches fts with no language gate`(@TempDir dir: File) {
        open(dir).use { store ->
            val id = fileId(store, "/notes/bn.txt")
            val seg = store.addSegment(id, "chunk", "text", null, null, "বাংলা নোট", null, "bn.txt", "আমার বাংলা নোট খুঁজুন", "আমার বাংলা নোট খুঁজুন")
            assertTrue(store.matchTerms("বাংলা").contains(seg))
        }
    }

    @Test
    fun `fts uses the unicode61 tokenizer from arch schema`(@TempDir dir: File) {
        open(dir).use { store ->
            DriverManager.getConnection("jdbc:sqlite:${store.dbPath}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT sql FROM sqlite_master WHERE name='fts'").use { rs ->
                        assertTrue(rs.next())
                        val sql = rs.getString(1)
                        // Exact arch §5 string; fallback is `porter unicode61` (see IndexStore KDoc).
                        assertTrue(sql.contains("remove_diacritics 2"), "unexpected fts sql: $sql")
                    }
                }
            }
        }
    }

    @Test
    fun `foreign keys are enforced on segments`(@TempDir dir: File) {
        open(dir).use { store ->
            assertThrows<SQLException> {
                store.addSegment(999999, "chunk", "text", null, null, "orphan", null, "x", "orphan body", "orphan body")
            }
        }
    }

    @Test
    fun `unfinishedCounts covers two-pass math`(@TempDir dir: File) {
        open(dir).use { store ->
            store.upsertFile("/p1.txt", "pdf", 100, 1, 1, 7, state = "pending")
            store.upsertFile("/p2.txt", "pdf", 100, 1, 2, 7, state = "partial")
            store.upsertFile("/d.txt", "doc", 100, 1, 3, 7, state = "done")
            val counts = store.unfinishedCounts()
            assertEquals(1, counts["pending"])
            assertEquals(1, counts["partial"])
            assertFalse(counts.containsKey("done"))
        }
    }
}
