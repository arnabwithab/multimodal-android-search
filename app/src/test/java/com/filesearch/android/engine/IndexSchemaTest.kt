package com.filesearch.android.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F022: the device SQLite backend must run the SAME schema as [IndexStore].
 * [IndexSchema] is that single source of truth — both backends reference these
 * strings, so this test pins the tables + the exact FTS5 tokenizer line.
 */
class IndexSchemaTest {
    @Test
    fun `schema creates files segments and meta tables`() {
        val ddl = IndexSchema.DDL_FILES + IndexSchema.DDL_SEGMENTS + IndexSchema.DDL_META
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY, path TEXT UNIQUE"))
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS segments("))
        assertTrue(ddl.contains("FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE"))
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)"))
    }

    @Test
    fun `fts uses the exact arch section-5 tokenizer`() {
        assertTrue(IndexSchema.DDL_FTS_FULL.contains("fts5(name, body,"))
        assertTrue(IndexSchema.DDL_FTS_FULL.contains("original UNINDEXED"))
        assertTrue(
            IndexSchema.DDL_FTS_FULL.contains(
                "tokenize=\"porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'\"",
            ),
        )
        // Old-SQLite fallback keeps porter+unicode61 so queries still stem.
        assertTrue(IndexSchema.DDL_FTS_FALLBACK.contains("tokenize=\"porter unicode61\""))
    }
}
