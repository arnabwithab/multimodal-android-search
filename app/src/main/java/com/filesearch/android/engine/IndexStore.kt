package com.filesearch.android.engine

import java.sql.DriverManager
import java.sql.SQLException

/** Fingerprint `model@rev runtime dtype dim budgets`; any change forces full re-embed (never mix dims).
 * shortcut: opaque string until Embedder lands; structured fields then. */
data class Fingerprint(val text: String)

data class PendingFile(val id: Long, val path: String, val kind: String, val state: String)

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
        exec("CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY, path TEXT UNIQUE, kind TEXT, size INTEGER, mtime INTEGER, inode INTEGER, device INTEGER, state TEXT, detail TEXT, info TEXT, indexed_at INTEGER)")
        exec("CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, kind TEXT, modality TEXT, loc REAL, loc_end REAL, excerpt TEXT, vec BLOB, FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE)")
        exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        try {
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, original UNINDEXED, tokenize=\"porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'\")")
        } catch (e: Exception) {
            // shortcut: older SQLite without remove_diacritics/categories args; upgrade when min SQLite is known.
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, original UNINDEXED, tokenize=\"porter unicode61\")")
        }
    }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    @Synchronized
    fun upsertFile(path: String, kind: String, size: Long, mtime: Long, inode: Long, device: Long, state: String = "pending", detail: String? = null, info: String? = null): Long {
        db.prepareStatement("INSERT INTO files(path,kind,size,mtime,inode,device,state,detail,info,indexed_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET kind=excluded.kind,size=excluded.size,mtime=excluded.mtime,inode=excluded.inode,device=excluded.device,state=CASE WHEN files.size=excluded.size AND files.mtime=excluded.mtime AND files.inode=excluded.inode THEN files.state ELSE excluded.state END,detail=excluded.detail,info=excluded.info").use {
            it.setString(1, path); it.setString(2, kind); it.setLong(3, size); it.setLong(4, mtime)
            it.setLong(5, inode); it.setLong(6, device); it.setString(7, state)
            it.setString(8, detail); it.setString(9, info); it.setLong(10, System.currentTimeMillis())
            it.executeUpdate()
        }
        db.prepareStatement("SELECT id FROM files WHERE path=?").use {
            it.setString(1, path); it.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    /** Inserts the segment plus its FTS row (rowid = segment id) in one txn. */
    @Synchronized
    fun addSegment(fileId: Long, kind: String, modality: String, loc: Double?, locEnd: Double?, excerpt: String?, vec: ByteArray?, name: String, body: String, original: String): Long {
        db.autoCommit = false
        try {
            val segId = db.prepareStatement("INSERT INTO segments(file_id,kind,modality,loc,loc_end,excerpt,vec) VALUES (?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
                it.setLong(1, fileId); it.setString(2, kind); it.setString(3, modality)
                loc?.let { v -> it.setDouble(4, v) } ?: it.setNull(4, java.sql.Types.REAL)
                locEnd?.let { v -> it.setDouble(5, v) } ?: it.setNull(5, java.sql.Types.REAL)
                it.setString(6, excerpt); it.setBytes(7, vec)
                it.executeUpdate()
                it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
            }
            db.prepareStatement("INSERT INTO fts(rowid,name,body,original) VALUES (?,?,?,?)").use {
                it.setLong(1, segId); it.setString(2, name); it.setString(3, body); it.setString(4, original)
                it.executeUpdate()
            }
            db.commit()
            return segId
        } catch (e: Exception) {
            db.rollback(); throw e
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
    fun moveFile(oldPath: String, newPath: String): Boolean = try {
        db.prepareStatement("UPDATE files SET path=? WHERE path=?").use {
            it.setString(1, newPath); it.setString(2, oldPath); it.executeUpdate() > 0
        }
    } catch (e: SQLException) {
        false
    }

    @Synchronized
    fun deleteFile(path: String): Boolean {
        val id = db.prepareStatement("SELECT id FROM files WHERE path=?").use {
            it.setString(1, path)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else return false }
        }
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file_id=?)").use { it.setLong(1, id); it.executeUpdate() }
            db.prepareStatement("DELETE FROM files WHERE id=?").use { it.setLong(1, id); it.executeUpdate() }
            db.commit()
        } catch (e: Exception) {
            db.rollback(); throw e
        } finally {
            db.autoCommit = true
        }
        return true
    }

    /** Deletes files outside [knownPaths]. Caller passes this only when the root still exists. */
    @Synchronized
    fun pruneMissing(knownPaths: Set<String>): Int {
        val stale = db.createStatement().use { st ->
            st.executeQuery("SELECT id, path FROM files").use { rs ->
                buildList { while (rs.next()) if (rs.getString(2) !in knownPaths) add(rs.getLong(1)) }
            }
        }
        db.autoCommit = false
        try {
            for (id in stale) {
                db.prepareStatement("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file_id=?)").use { it.setLong(1, id); it.executeUpdate() }
                db.prepareStatement("DELETE FROM files WHERE id=?").use { it.setLong(1, id); it.executeUpdate() }
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback(); throw e
        } finally {
            db.autoCommit = true
        }
        return stale.size
    }

    /** Queued work (pending + partial two-pass resumes), screenshots first through video last. */
    fun pending(limit: Int): List<PendingFile> =
        db.prepareStatement("SELECT id,path,kind,state FROM files WHERE state IN ('pending','partial') ORDER BY CASE kind WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2 WHEN 'doc' THEN 3 WHEN 'audio' THEN 4 WHEN 'video' THEN 5 ELSE 6 END, id ASC LIMIT ?").use {
            it.setInt(1, limit)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(PendingFile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))) } }
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
    fun checkFingerprint(fp: Fingerprint): Boolean {
        val stored = getMeta("fingerprint")
        if (stored == fp.text) return true
        if (stored != null) resetAll()
        setMeta("fingerprint", fp.text)
        return false
    }

    @Synchronized
    fun setMeta(key: String, value: String) =
        db.prepareStatement("INSERT INTO meta(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate(); Unit
        }

    fun getMeta(key: String): String? =
        db.prepareStatement("SELECT value FROM meta WHERE key=?").use {
            it.setString(1, key)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun countFiles(): Int = db.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM files").use { it.next(); it.getInt(1) } }

    fun countSegments(fileId: Long): Int =
        db.prepareStatement("SELECT COUNT(*) FROM segments WHERE file_id=?").use {
            it.setLong(1, fileId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Synchronized
    fun resetAll() {
        db.autoCommit = false
        try {
            exec("DELETE FROM fts"); exec("DELETE FROM segments"); exec("DELETE FROM files"); exec("DELETE FROM meta")
            db.commit()
        } catch (e: Exception) {
            db.rollback(); throw e
        } finally {
            db.autoCommit = true
        }
    }

    override fun close() = db.close()
}
