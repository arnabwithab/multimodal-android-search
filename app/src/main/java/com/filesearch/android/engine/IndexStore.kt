package com.filesearch.android.engine

import java.sql.DriverManager
import java.sql.SQLException

/** Fingerprint `model@rev runtime dtype dim budgets`; any change forces full re-embed (never mix dims). */
data class Fingerprint(
    val model: String,
    val rev: String,
    val runtime: String,
    val dtype: String,
    val dim: Int,
    val budgets: String,
) {
    override fun toString(): String = "$model@$rev $runtime $dtype $dim $budgets"

    /** Single-string form for callers holding the stored text (e.g. older tests). */
    constructor(text: String) : this(
        parse(text)?.model ?: throw IllegalArgumentException("malformed fingerprint: $text"),
        parse(text)?.rev ?: throw IllegalArgumentException("malformed fingerprint: $text"),
        parse(text)?.runtime ?: throw IllegalArgumentException("malformed fingerprint: $text"),
        parse(text)?.dtype ?: throw IllegalArgumentException("malformed fingerprint: $text"),
        parse(text)?.dim ?: throw IllegalArgumentException("malformed fingerprint: $text"),
        parse(text)?.budgets ?: throw IllegalArgumentException("malformed fingerprint: $text"),
    )

    companion object {
        private const val FINGERPRINT_PART_COUNT = 5
        private const val HEAD_PART = 0
        private const val RUNTIME_PART = 1
        private const val DTYPE_PART = 2
        private const val DIM_PART = 3
        private const val BUDGETS_PART = 4

        fun parse(text: String): Fingerprint? {
            val parts = text.split(" ")
            if (parts.size != FINGERPRINT_PART_COUNT || parts.any { it.isEmpty() }) return null
            return fromParts(parts)
        }

        private fun fromParts(parts: List<String>): Fingerprint? {
            val modelRev = splitModelRev(parts[HEAD_PART])
            val dim = parts[DIM_PART].toIntOrNull()?.takeIf { it > 0 }
            if (modelRev == null || dim == null) return null
            return Fingerprint(modelRev.first, modelRev.second, parts[RUNTIME_PART], parts[DTYPE_PART], dim, parts[BUDGETS_PART])
        }

        private fun splitModelRev(head: String): Pair<String, String>? {
            val at = head.indexOf('@')
            if (at <= 0 || at == head.length - 1) return null
            return head.substring(0, at).takeIf { it.isNotEmpty() }?.let { model ->
                head.substring(at + 1).takeIf { it.isNotEmpty() }?.let { model to it }
            }
        }
    }
}

data class PendingFile(val id: Long, val path: String, val kind: String, val state: String)

// Positional bind indices for the upsertFile statement.
private const val BIND_PATH = 1
private const val BIND_KIND = 2
private const val BIND_SIZE = 3
private const val BIND_MTIME = 4
private const val BIND_INODE = 5
private const val BIND_DEVICE = 6
private const val BIND_STATE = 7
private const val BIND_DETAIL = 8
private const val BIND_INFO = 9
private const val BIND_INDEXED_AT = 10

// Positional bind indices for the segment + FTS inserts.
private const val SEG_BIND_FILE = 1
private const val SEG_BIND_KIND = 2
private const val SEG_BIND_MODALITY = 3
private const val SEG_BIND_LOC = 4
private const val SEG_BIND_LOC_END = 5
private const val SEG_BIND_EXCERPT = 6
private const val SEG_BIND_VEC = 7
private const val FTS_BIND_ROWID = 1
private const val FTS_BIND_NAME = 2
private const val FTS_BIND_BODY = 3
private const val FTS_BIND_ORIGINAL = 4

// SELECT id,path,kind,state column positions in pending().
private const val PENDING_COL_ID = 1
private const val PENDING_COL_PATH = 2
private const val PENDING_COL_KIND = 3
private const val PENDING_COL_STATE = 4

/** One file row: identity carries the inode so Sync can re-path moves without re-embed. */
data class FileRow(
    val path: String,
    val kind: String,
    val size: Long,
    val mtime: Long,
    val inode: Long,
    val device: Long,
    val state: String = "pending",
    val detail: String? = null,
    val info: String? = null,
)

/** One segment plus its FTS text, inserted in a single transaction. */
data class SegmentRow(
    val fileId: Long,
    val kind: String,
    val modality: String,
    val loc: Double?,
    val locEnd: Double?,
    val excerpt: String?,
    val vec: ByteArray?,
    val name: String,
    val body: String,
    val original: String,
)

/**
 * SQLite index: files + segments + FTS5 + meta. Device maps these tables to Room @Entity
 * later; the SQL strings below are kept identical so the mapping is mechanical.
 * Single connection: callers serialise (same rule as the single embed mutex).
 */
class IndexStore(val dbPath: String) : AutoCloseable {
    private val db = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        exec("PRAGMA foreign_keys=ON")
        exec("PRAGMA journal_mode=WAL")
        exec(
            "CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY, path TEXT UNIQUE, kind TEXT, " +
                "size INTEGER, mtime INTEGER, inode INTEGER, device INTEGER, state TEXT, detail TEXT, info TEXT, indexed_at INTEGER)",
        )
        exec(
            "CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, kind TEXT, modality TEXT, " +
                "loc REAL, loc_end REAL, excerpt TEXT, vec BLOB, FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE)",
        )
        exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        try {
            exec(
                "CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, " +
                    "original UNINDEXED, tokenize=\"porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'\")",
            )
        } catch (_: SQLException) {
            // Old SQLite without remove_diacritics 2 / categories args: fall back to plain porter+unicode61.
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, original UNINDEXED, tokenize=\"porter unicode61\")")
        }
    }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    @Synchronized
    fun upsertFile(row: FileRow): Long {
        db.prepareStatement(
            "INSERT INTO files(path,kind,size,mtime,inode,device,state,detail,info,indexed_at) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(path) DO UPDATE SET kind=excluded.kind,size=excluded.size,mtime=excluded.mtime,inode=excluded.inode," +
                "device=excluded.device,state=CASE WHEN files.size=excluded.size AND files.mtime=excluded.mtime AND " +
                "files.inode=excluded.inode THEN files.state ELSE excluded.state END,detail=excluded.detail,info=excluded.info",
        ).use {
            it.setString(BIND_PATH, row.path)
            it.setString(BIND_KIND, row.kind)
            it.setLong(BIND_SIZE, row.size)
            it.setLong(BIND_MTIME, row.mtime)
            it.setLong(BIND_INODE, row.inode)
            it.setLong(BIND_DEVICE, row.device)
            it.setString(BIND_STATE, row.state)
            it.setString(BIND_DETAIL, row.detail)
            it.setString(BIND_INFO, row.info)
            it.setLong(BIND_INDEXED_AT, System.currentTimeMillis())
            it.executeUpdate()
        }
        db.prepareStatement("SELECT id FROM files WHERE path=?").use {
            it.setString(1, row.path)
            it.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /** Inserts the segment plus its FTS row (rowid = segment id) in one txn. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // rollback safety net: any failure mid-txn must roll back, never half-write.
    fun addSegment(row: SegmentRow): Long {
        db.autoCommit = false
        try {
            val segId =
                db.prepareStatement(
                    "INSERT INTO segments(file_id,kind,modality,loc,loc_end,excerpt,vec) VALUES (?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use {
                    it.setLong(SEG_BIND_FILE, row.fileId)
                    it.setString(SEG_BIND_KIND, row.kind)
                    it.setString(SEG_BIND_MODALITY, row.modality)
                    row.loc?.let { v -> it.setDouble(SEG_BIND_LOC, v) } ?: it.setNull(SEG_BIND_LOC, java.sql.Types.REAL)
                    row.locEnd?.let { v -> it.setDouble(SEG_BIND_LOC_END, v) } ?: it.setNull(SEG_BIND_LOC_END, java.sql.Types.REAL)
                    it.setString(SEG_BIND_EXCERPT, row.excerpt)
                    it.setBytes(SEG_BIND_VEC, row.vec)
                    it.executeUpdate()
                    it.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            db.prepareStatement("INSERT INTO fts(rowid,name,body,original) VALUES (?,?,?,?)").use {
                it.setLong(FTS_BIND_ROWID, segId)
                it.setString(FTS_BIND_NAME, row.name)
                it.setString(FTS_BIND_BODY, row.body)
                it.setString(FTS_BIND_ORIGINAL, row.original)
                it.executeUpdate()
            }
            db.commit()
            return segId
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun matchTerms(query: String): List<Long> =
        db.prepareStatement("SELECT rowid FROM fts WHERE fts MATCH ?").use {
            it.setString(1, query)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }

    /** Re-path without re-embed (Sync move). */
    @Synchronized
    fun moveFile(
        oldPath: String,
        newPath: String,
    ): Boolean =
        try {
            db.prepareStatement("UPDATE files SET path=? WHERE path=?").use {
                it.setString(1, newPath)
                it.setString(2, oldPath)
                it.executeUpdate() > 0
            }
        } catch (_: SQLException) {
            // Move onto an existing path violates the UNIQUE constraint: report false, never throw.
            false
        }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // rollback safety net: any failure mid-txn must roll back, never half-write.
    fun deleteFile(path: String): Boolean {
        val id =
            db.prepareStatement("SELECT id FROM files WHERE path=?").use {
                it.setString(1, path)
                it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else return false }
            }
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file_id=?)").use {
                it.setLong(1, id)
                it.executeUpdate()
            }
            db.prepareStatement("DELETE FROM files WHERE id=?").use {
                it.setLong(1, id)
                it.executeUpdate()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        return true
    }

    /** Deletes files outside [knownPaths]. Caller passes this only when the root still exists. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // rollback safety net: any failure mid-txn must roll back, never half-write.
    fun pruneMissing(knownPaths: Set<String>): Int {
        val stale =
            db.createStatement().use { st ->
                st.executeQuery("SELECT id, path FROM files").use { rs ->
                    buildList { while (rs.next()) if (rs.getString(2) !in knownPaths) add(rs.getLong(1)) }
                }
            }
        db.autoCommit = false
        try {
            for (id in stale) {
                db.prepareStatement("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file_id=?)").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
                db.prepareStatement("DELETE FROM files WHERE id=?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        return stale.size
    }

    /** Queued work (pending + partial two-pass resumes), screenshots first through video last. */
    fun pending(limit: Int): List<PendingFile> =
        db.prepareStatement(
            "SELECT id,path,kind,state FROM files WHERE state IN ('pending','partial') ORDER BY CASE kind " +
                "WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2 WHEN 'doc' THEN 3 WHEN 'audio' THEN 4 " +
                "WHEN 'video' THEN 5 ELSE 6 END, id ASC LIMIT ?",
        ).use {
            it.setInt(1, limit)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PendingFile(
                                rs.getLong(PENDING_COL_ID),
                                rs.getString(PENDING_COL_PATH),
                                rs.getString(PENDING_COL_KIND),
                                rs.getString(PENDING_COL_STATE),
                            ),
                        )
                    }
                }
            }
        }

    /** Non-done counts by state for two-pass progress math. */
    fun unfinishedCounts(): Map<String, Int> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT state, COUNT(*) FROM files WHERE state!='done' GROUP BY state").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
            }
        }

    /** True when stored fingerprint matches; first store skips the wipe (fresh index, nothing to mix).
     * A changed fingerprint wipes the index, stores [fp], returns false. */
    @Synchronized
    fun checkFingerprint(fp: Fingerprint): Boolean = checkFingerprint(fp.toString())

    @Synchronized
    fun checkFingerprint(fpText: String): Boolean {
        val stored = getMeta("fingerprint")
        if (stored == fpText) return true
        if (stored != null) resetAll()
        setMeta("fingerprint", fpText)
        return false
    }

    @Synchronized
    fun setMeta(
        key: String,
        value: String,
    ) = db.prepareStatement("INSERT INTO meta(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
        it.setString(1, key)
        it.setString(2, value)
        it.executeUpdate()
        Unit
    }

    fun getMeta(key: String): String? =
        db.prepareStatement("SELECT value FROM meta WHERE key=?").use {
            it.setString(1, key)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun countFiles(): Int =
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM files").use {
                it.next()
                it.getInt(1)
            }
        }

    fun countSegments(fileId: Long): Int =
        db.prepareStatement("SELECT COUNT(*) FROM segments WHERE file_id=?").use {
            it.setLong(1, fileId)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // rollback safety net: any failure mid-txn must roll back, never half-write.
    fun resetAll() {
        db.autoCommit = false
        try {
            exec("DELETE FROM fts")
            exec("DELETE FROM segments")
            exec("DELETE FROM files")
            exec("DELETE FROM meta")
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    override fun close() = db.close()
}
