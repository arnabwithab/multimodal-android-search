package com.filesearch.android.engine

/**
 * F022 single source of truth for the index schema.
 *
 * Engine [IndexStore] (JDBC, Linux tests) and the Android backend
 * (android.database.sqlite, on device) both execute these exact strings, so a
 * schema change lands once here and both sides stay identical. The FTS5 line
 * is the arch §5 tokenizer; [DDL_FTS_FALLBACK] is the old-SQLite path both
 * backends take when the full tokenizer throws at CREATE time.
 */
object IndexSchema {
    const val DDL_FILES =
        "CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY, path TEXT UNIQUE, kind TEXT, " +
            "size INTEGER, mtime INTEGER, inode INTEGER, device INTEGER, state TEXT, detail TEXT, info TEXT, indexed_at INTEGER)"

    const val DDL_SEGMENTS =
        "CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, kind TEXT, modality TEXT, " +
            "loc REAL, loc_end REAL, excerpt TEXT, vec BLOB, FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE)"

    const val DDL_META = "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)"

    const val DDL_FTS_FULL =
        "CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, " +
            "original UNINDEXED, tokenize=\"porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'\")"

    const val DDL_FTS_FALLBACK =
        "CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, original UNINDEXED, tokenize=\"porter unicode61\")"
}
