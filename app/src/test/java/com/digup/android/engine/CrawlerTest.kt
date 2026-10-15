package com.digup.android.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrawlerTest {

    private fun makeFile(dir: File, rel: String, size: Int = 2048): File {
        val f = File(dir, rel)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size) { 'x'.code.toByte() })
        return f
    }

    // ---- Rules: secrets ----

    @Test
    fun `secret key material and env files are skipped`() {
        for (name in listOf("id.pem", "id.key", "cert.p12", "key.pfx", "debug.keystore", ".env", "a/.env.local", "passwords.txt", "my-Password-backup.csv")) {
            assertEquals(FileDecision.SKIP_SECRET, Rules.classify("/root/$name", 5000, false, false), name)
        }
        assertEquals(FileDecision.INDEX, Rules.classify("/root/notes.txt", 5000, false, false))
    }

    // ---- Rules: hidden + app-private ----

    @Test
    fun `hidden files and app-private dirs are skipped`() {
        assertEquals(FileDecision.SKIP_HIDDEN, Rules.classify("/root/photo.jpg", 5000, true, false))
        assertEquals(FileDecision.SKIP_HIDDEN, Rules.classify("/root/.hidden/note.txt", 5000, false, false))
        assertEquals(FileDecision.SKIP_HIDDEN, Rules.classify("/sdcard/Android/data/com.x/f.txt", 5000, false, false))
        assertEquals(FileDecision.SKIP_HIDDEN, Rules.classify("/root/.cache/f.txt", 5000, false, false))
    }

    // ---- Rules: cloud / icon / vendored ----

    @Test
    fun `cloud placeholders skip only when flagged`() {
        assertEquals(FileDecision.SKIP_CLOUD_PLACEHOLDER, Rules.classify("/root/a.jpg", 5000, false, true))
        assertEquals(FileDecision.INDEX, Rules.classify("/root/a.jpg", 5000, false, false))
    }

    @Test
    fun `tiny images skip as icons but tiny text still indexes`() {
        assertEquals(FileDecision.SKIP_SMALL_ICON, Rules.classify("/root/icon.png", 500, false, false))
        assertEquals(FileDecision.INDEX, Rules.classify("/root/icon.png", 5000, false, false))
        assertEquals(FileDecision.INDEX, Rules.classify("/root/note.txt", 10, false, false))
    }

    @Test
    fun `vendored dirs are skipped`() {
        for (p in listOf("/r/node_modules/a.js", "/r/.venv/b.py", "/r/vendor/c.php", "/r/build/d.txt")) {
            assertEquals(FileDecision.SKIP_VENDORED, Rules.classify(p, 5000, false, false), p)
        }
        assertEquals(FileDecision.INDEX, Rules.classify("/r/src/a.js", 5000, false, false))
    }

    @Test
    fun `dataset boundary is 300`() {
        assertEquals(false, Rules.isDatasetDir(299))
        assertEquals(true, Rules.isDatasetDir(300))
    }

    // ---- Crawler: walk ----

    @Test
    fun `walk indexes normal and multilingual files, skips the rest`(@TempDir root: File) {
        makeFile(root, "notes.txt")
        makeFile(root, "নোট-বাংলা.txt")
        makeFile(root, "ملاحظة-عربية.txt")
        makeFile(root, "笔记-中文.txt")
        makeFile(root, "บันทึก-ไทย.txt")
        makeFile(root, "नोट-हिन्दी.txt")
        makeFile(root, "photo.jpg", 5000)
        makeFile(root, ".env", 100)
        makeFile(root, "keys/id.pem", 500)
        makeFile(root, ".hidden/secret.txt")
        makeFile(root, "node_modules/lib.js")
        makeFile(root, "icon.png", 100)

        val crawler = Crawler()
        val found = crawler.walk(root).map { File(it.path).name }.toSet()

        for (n in listOf("notes.txt", "নোট-বাংলা.txt", "ملاحظة-عربية.txt", "笔记-中文.txt", "บันทึก-ไทย.txt", "नोट-हिन्दी.txt", "photo.jpg")) {
            assertTrue(n in found, "missing $n in $found")
        }
        for (n in listOf(".env", "id.pem", "secret.txt", "lib.js", "icon.png")) {
            assertTrue(n !in found, "should be skipped: $n")
        }
        val reasons = crawler.skipped.map { it.reason }.toSet()
        assertTrue(FileDecision.SKIP_SECRET in reasons)
        assertTrue(FileDecision.SKIP_HIDDEN in reasons)
        assertTrue(FileDecision.SKIP_VENDORED in reasons)
        assertTrue(FileDecision.SKIP_SMALL_ICON in reasons)
    }

    @Test
    fun `walk flags machine-named dir as dataset candidate`(@TempDir root: File) {
        val ds = File(root, "dataset")
        for (i in 1..300) makeFile(root, "dataset/%06d.jpg".format(i), 2048)
        makeFile(root, "keep.txt")
        val crawler = Crawler()
        val found = crawler.walk(root).map { it.path }
        assertTrue(found.any { it.endsWith("keep.txt") })
        assertTrue(found.none { it.startsWith(ds.absolutePath) }, "dataset files must be skipped")
        assertTrue(crawler.skipped.any { it.reason == FileDecision.SKIP_DATASET_CANDIDATE })
    }

    @Test
    fun `walk ignores machine-named non-media files for dataset check`(@TempDir root: File) {
        for (i in 1..300) makeFile(root, "logs/%06d.log".format(i), 2048)
        val crawler = Crawler()
        val found = crawler.walk(root).map { it.path }
        assertEquals(300, found.size)
        assertTrue(crawler.skipped.none { it.reason == FileDecision.SKIP_DATASET_CANDIDATE })
    }

    @Test
    fun `walk does not throw on missing root`() {
        val crawler = Crawler()
        assertEquals(emptyList(), crawler.walk(File("/nonexistent-dir-xyz-123")))
        assertTrue(crawler.errors.isNotEmpty())
    }

    @Test
    fun `walk inode is stable across passes`(@TempDir root: File) {
        makeFile(root, "a.txt")
        val crawler = Crawler()
        val first = crawler.walk(root).associateBy({ it.path }, { it.inode })
        val second = Crawler().walk(root).associateBy({ it.path }, { it.inode })
        assertEquals(first, second)
    }

    // ---- Sync ----

    @Test
    fun `sync detects move by inode without re-embed`() {
        val existing = listOf(StoredFile("/a/photo.jpg", inode = 11, size = 100, mtime = 5))
        val found = listOf(CrawlEntry("/b/photo.jpg", size = 100, mtime = 5, inode = 11))
        val r = Sync.reconcile(existing, found, rootStillExists = true)
        assertEquals(1, r.moved.size)
        assertEquals("/a/photo.jpg", r.moved[0].oldPath)
        assertEquals("/b/photo.jpg", r.moved[0].entry.path)
        assertTrue(r.added.isEmpty() && r.deleted.isEmpty() && r.modified.isEmpty())
    }

    @Test
    fun `sync re-embeds a file that moved and changed`() {
        val existing = listOf(StoredFile("/a/photo.jpg", inode = 11, size = 100, mtime = 5))
        val found = listOf(CrawlEntry("/b/photo.jpg", size = 100, mtime = 9, inode = 11))
        val r = Sync.reconcile(existing, found, rootStillExists = true)
        assertEquals(1, r.moved.size)
        assertEquals(listOf("/b/photo.jpg"), r.modified.map { it.path })
        assertTrue(r.added.isEmpty() && r.deleted.isEmpty())
    }

    @Test
    fun `sync re-queues changed files`() {
        val existing = listOf(StoredFile("/a.txt", inode = 7, size = 100, mtime = 5))
        val found = listOf(CrawlEntry("/a.txt", size = 120, mtime = 9, inode = 7))
        val r = Sync.reconcile(existing, found, rootStillExists = true)
        assertEquals(listOf("/a.txt"), r.modified.map { it.path })
        assertTrue(r.moved.isEmpty() && r.deleted.isEmpty() && r.added.isEmpty())
    }

    @Test
    fun `sync prunes deleted only when root still exists`() {
        val existing = listOf(StoredFile("/gone.txt", inode = 9, size = 10, mtime = 1))
        val pruned = Sync.reconcile(existing, emptyList(), rootStillExists = true)
        assertEquals(listOf("/gone.txt"), pruned.deleted.map { it.path })
        assertTrue(pruned.keptMissing.isEmpty())
        val kept = Sync.reconcile(existing, emptyList(), rootStillExists = false)
        assertEquals(listOf("/gone.txt"), kept.keptMissing.map { it.path })
        assertTrue(kept.deleted.isEmpty())
    }

    @Test
    fun `sync marks brand-new files added`() {
        val r = Sync.reconcile(emptyList(), listOf(CrawlEntry("/n.txt", 10, 1, 42)), rootStillExists = true)
        assertEquals(listOf("/n.txt"), r.added.map { it.path })
    }
}
