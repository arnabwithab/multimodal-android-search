package com.filesearch.android.engine

data class StoredFile(val path: String, val inode: Long, val size: Long, val mtime: Long)
data class MovedFile(val oldPath: String, val entry: CrawlEntry)
data class SyncResult(
    val added: List<CrawlEntry>,
    val modified: List<CrawlEntry>,
    val moved: List<MovedFile>,
    val deleted: List<StoredFile>,
    val keptMissing: List<StoredFile>,
)

/** Inode-aware reconcile: moves re-path without re-embed; vanished roots keep rows, never nuke. */
object Sync {
    fun reconcile(
        existing: List<StoredFile>,
        found: List<CrawlEntry>,
        rootStillExists: Boolean,
    ): SyncResult {
        val foundByPath = found.associateBy { it.path }
        val byInode = found.groupBy { it.inode }
        val consumed = mutableSetOf<String>()
        val added = mutableListOf<CrawlEntry>()
        val modified = mutableListOf<CrawlEntry>()
        val moved = mutableListOf<MovedFile>()
        val deleted = mutableListOf<StoredFile>()
        val keptMissing = mutableListOf<StoredFile>()
        for (s in existing) {
            val same = foundByPath[s.path]
            if (same != null) {
                consumed.add(same.path)
                if (same.size != s.size || same.mtime != s.mtime) modified.add(same)
                continue
            }
            val cand = byInode[s.inode]?.firstOrNull { it.path !in consumed && it.size == s.size }
            if (cand != null) {
                consumed.add(cand.path)
                moved.add(MovedFile(s.path, cand))
                if (cand.mtime != s.mtime) modified.add(cand) // moved AND edited: re-embed at new path
            } else if (rootStillExists) {
                deleted.add(s)
            } else {
                keptMissing.add(s)
            }
        }
        for (f in found) if (f.path !in consumed) added.add(f)
        return SyncResult(added, modified, moved, deleted, keptMissing)
    }
}
