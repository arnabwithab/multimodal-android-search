package com.filesearch.android

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.filesearch.android.engine.IndexSchema

/**
 * F022 on-device index backend (arch §5 tables).
 *
 * Executes the SAME DDL as engine IndexStore via [IndexSchema] (single source
 * of truth — a schema change lands once here-adjacent, both sides stay
 * identical). The driver is bundled SQLite, NOT the framework: the framework
 * SQLite ships no FTS5 module (recorded `no such module: fts5` on SM8635), and
 * arch §5 needs the exact porter/unicode61 FTS5 line. The old-SQLite fallback
 * mirrors the engine's (full FTS5 line first, plain porter+unicode61 on
 * SQLiteException). Single-connection discipline: every call is @Synchronized,
 * like the engine's serialised access.
 */
class DeviceIndexStore(ctx: Context) : AutoCloseable {
    companion object {
        const val DB_NAME = "filesearch-index.db"
    }

    private val driver: SQLiteDriver = BundledSQLiteDriver()
    private val conn: SQLiteConnection =
        driver.open(ctx.getDatabasePath(DB_NAME).absolutePath).also { c ->
            c.prepare("PRAGMA journal_mode=WAL").use { it.step() }
            c.prepare("PRAGMA foreign_keys=ON").use { it.step() }
            c.prepare(IndexSchema.DDL_FILES).use { it.step() }
            c.prepare(IndexSchema.DDL_SEGMENTS).use { it.step() }
            c.prepare(IndexSchema.DDL_META).use { it.step() }
            try {
                c.prepare(IndexSchema.DDL_FTS_FULL).use { it.step() }
            } catch (_: Exception) {
                // Same fallback as engine IndexStore (old SQLite without the full tokenizer args).
                c.prepare(IndexSchema.DDL_FTS_FALLBACK).use { it.step() }
            }
        }

    private fun exec(sql: String) {
        conn.prepare(sql).use { it.step() }
    }

    /** Upsert by path; returns the file id. Unchanged size+mtime keeps the row (no re-embed). */
    @Synchronized
    fun upsertFile(
        path: String,
        kind: String,
        size: Long,
        mtime: Long,
        state: String = "pending",
        detail: String? = null,
    ): Long {
        conn.prepare("SELECT id, size, mtime FROM files WHERE path=?").use { q ->
            q.bindText(1, path)
            if (q.step() && q.getLong(1) == size && q.getLong(2) == mtime) return q.getLong(0)
        }
        conn.prepare(
            "INSERT INTO files(path,kind,size,mtime,inode,device,state,detail,indexed_at) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(path) DO UPDATE SET kind=excluded.kind,size=excluded.size,mtime=excluded.mtime," +
                "inode=excluded.inode,device=excluded.device,state=excluded.state,detail=excluded.detail," +
                "indexed_at=excluded.indexed_at",
        ).use { ins ->
            ins.bindText(1, path)
            ins.bindText(2, kind)
            ins.bindLong(3, size)
            ins.bindLong(4, mtime)
            // shortcut: inode/device faked (hash/0) — real inode resume is F023.
            ins.bindLong(5, path.hashCode().toLong())
            ins.bindLong(6, 0L)
            ins.bindText(7, state)
            if (detail != null) ins.bindText(8, detail) else ins.bindNull(8)
            ins.bindLong(9, System.currentTimeMillis())
            ins.step()
        }
        conn.prepare("SELECT id FROM files WHERE path=?").use { q ->
            q.bindText(1, path)
            q.step()
            return q.getLong(0)
        }
    }

    /** Inserts the segment plus its FTS row (rowid = segment id) in one txn. loc = page/seconds (arch §5), null when meaningless. */
    @Synchronized
    fun addSegment(
        fileId: Long,
        kind: String,
        modality: String,
        excerpt: String?,
        vec: ByteArray?,
        name: String,
        body: String,
        original: String,
        loc: Double? = null,
        locEnd: Double? = null,
    ): Long {
        exec("BEGIN IMMEDIATE")
        try {
            conn.prepare(
                "INSERT INTO segments(file_id,kind,modality,loc,loc_end,excerpt,vec) VALUES (?,?,?,?,?,?,?)",
            ).use { ins ->
                ins.bindLong(1, fileId)
                ins.bindText(2, kind)
                ins.bindText(3, modality)
                if (loc != null) ins.bindDouble(4, loc) else ins.bindNull(4)
                if (locEnd != null) ins.bindDouble(5, locEnd) else ins.bindNull(5)
                if (excerpt != null) ins.bindText(6, excerpt) else ins.bindNull(6)
                if (vec != null) ins.bindBlob(7, vec) else ins.bindNull(7)
                ins.step()
            }
            val segId =
                conn.prepare("SELECT last_insert_rowid()").use { q ->
                    q.step()
                    q.getLong(0)
                }
            conn.prepare("INSERT INTO fts(rowid,name,body,original) VALUES (?,?,?,?)").use { fts ->
                fts.bindLong(1, segId)
                fts.bindText(2, name)
                fts.bindText(3, body)
                fts.bindText(4, original)
                fts.step()
            }
            exec("COMMIT")
            return segId
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
    }

    @Synchronized
    fun matchTerms(query: String): List<Long> {
        conn.prepare("SELECT rowid FROM fts WHERE fts MATCH ?").use { q ->
            q.bindText(1, query)
            return buildList {
                while (q.step()) add(q.getLong(0))
            }
        }
    }

    /** All segments + file path + FTS name for the query path (F024 brute-force read). */
    @Synchronized
    fun readSegments(): List<StoredSeg> {
        conn.prepare(
            "SELECT s.id, f.path, s.kind, s.modality, s.excerpt, s.vec, fts.name, s.loc " +
                "FROM segments s JOIN files f ON s.file_id = f.id LEFT JOIN fts ON fts.rowid = s.id",
        ).use { q ->
            return buildList {
                while (q.step()) {
                    add(
                        StoredSeg(
                            id = q.getLong(0),
                            path = q.getText(1),
                            kind = q.getText(2),
                            modality = q.getText(3),
                            excerpt = if (q.isNull(4)) null else q.getText(4),
                            vec = if (q.isNull(5)) null else q.getBlob(5),
                            ftsName = if (q.isNull(6)) "" else q.getText(6),
                            loc = if (q.isNull(7)) 0.0 else q.getDouble(7),
                        ),
                    )
                }
            }
        }
    }

    fun countFiles(): Int {
        conn.prepare("SELECT COUNT(*) FROM files").use { q ->
            q.step()
            return q.getLong(0).toInt()
        }
    }

    fun countSegments(fileId: Long): Int {
        conn.prepare("SELECT COUNT(*) FROM segments WHERE file_id=?").use { q ->
            q.bindLong(1, fileId)
            q.step()
            return q.getLong(0).toInt()
        }
    }

    /**
     * Drops a file's segments + FTS rows (re-embed after the bytes changed;
     * the file row itself stays, so state/detail continuity holds). Keeps
     * re-runs from stacking duplicate segments — the run-everything path
     * re-embeds, but never double-counts.
     */
    @Synchronized
    fun clearSegments(fileId: Long) {
        exec("BEGIN IMMEDIATE")
        try {
            conn.prepare("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file_id=?)").use { q ->
                q.bindLong(1, fileId)
                q.step()
            }
            conn.prepare("DELETE FROM segments WHERE file_id=?").use { q ->
                q.bindLong(1, fileId)
                q.step()
            }
            exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
    }

    @Synchronized
    fun setMeta(
        key: String,
        value: String,
    ) {
        conn.prepare("INSERT INTO meta(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { q ->
            q.bindText(1, key)
            q.bindText(2, value)
            q.step()
        }
    }

    fun getMeta(key: String): String? {
        conn.prepare("SELECT value FROM meta WHERE key=?").use { q ->
            q.bindText(1, key)
            return if (q.step()) q.getText(0) else null
        }
    }

    /** Stored fingerprint matches; first store skips the wipe, a change wipes then stores. */
    @Synchronized
    fun checkFingerprint(fp: String): Boolean {
        val stored = getMeta("fingerprint")
        if (stored == fp) return true
        if (stored != null) resetAll()
        setMeta("fingerprint", fp)
        return false
    }

    /**
     * Toggle-off purge (design §7 zero residue): deletes cloud transcript
     * boost rows — extra kind=chunk segments + their FTS rows — and nothing
     * else. Photo/screenshot segments stay.
     *
     * // shortcut: no origin column yet — doc chunks (F023+) must add one and
     * // narrow this delete, or text-chunk ingestion will be purged as well.
     */
    @Synchronized
    fun purgeTranscriptRows(): Int {
        var deleted = 0
        conn.prepare("SELECT COUNT(*) FROM segments WHERE kind='chunk'").use { q ->
            q.step()
            deleted = q.getLong(0).toInt()
        }
        if (deleted == 0) return 0
        exec("BEGIN IMMEDIATE")
        try {
            exec("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE kind='chunk')")
            exec("DELETE FROM segments WHERE kind='chunk'")
            exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
        return deleted
    }

    @Synchronized
    fun resetAll() {
        exec("BEGIN IMMEDIATE")
        try {
            exec("DELETE FROM fts")
            exec("DELETE FROM segments")
            exec("DELETE FROM files")
            exec("DELETE FROM meta")
            exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
    }

    override fun close() = conn.close()
}
