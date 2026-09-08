package com.daview.server.db

import java.nio.file.Path

/**
 * The scraped catalogue lives in a single SQLite file so that it can be backed
 * up, inspected and moved with the rest of the server data directory.
 *
 * The connection itself comes from a [SqlDatabase], which is where the platform
 * difference lives: JDBC on the desktop and server, Android's own SQLite on the
 * phone. Everything above this line — schema, queries, row mapping — is shared.
 */
class Database(private val sql: SqlDatabase) : AutoCloseable {

    /** Convenience for the JVM callers, which all want the JDBC driver. */
    constructor(dataDir: Path) : this(JdbcSqlDatabase(dataDir))

    init {
        migrate()
    }

    fun <T> read(block: (SqlConnection) -> T): T = sql.read(block)

    fun <T> transaction(block: (SqlConnection) -> T): T = sql.transaction(block)

    private fun migrate() = sql.transaction { connection ->
        connection.statement(
            "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)"
        ).use { it.executeUpdate() }

        val current = connection.statement("SELECT version FROM schema_version LIMIT 1")
            .useQuery { if (it.next()) it.getIntAt(1) else -1 }
        if (current < 0) {
            connection.statement("INSERT INTO schema_version(version) VALUES (0)").use { it.executeUpdate() }
        }

        var version = maxOf(current, 0)
        while (version < MIGRATIONS.size) {
            MIGRATIONS[version].forEach { sqlText ->
                connection.statement(sqlText).use { it.executeUpdate() }
            }
            version++
            connection.statement("UPDATE schema_version SET version = $version").use { it.executeUpdate() }
        }
    }

    override fun close() = sql.close()

    companion object {
        private val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                """
                CREATE TABLE IF NOT EXISTS libraries (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    path TEXT NOT NULL,
                    provider_order TEXT NOT NULL DEFAULT '[]',
                    language TEXT NOT NULL DEFAULT 'zh-CN',
                    last_scan_at INTEGER,
                    image_url TEXT,
                    created_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS items (
                    id TEXT PRIMARY KEY,
                    library_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    parent_id TEXT,
                    series_id TEXT,
                    name TEXT NOT NULL,
                    original_name TEXT,
                    sort_name TEXT NOT NULL DEFAULT '',
                    overview TEXT,
                    year INTEGER,
                    premiere_date TEXT,
                    runtime_ms INTEGER,
                    community_rating REAL,
                    official_rating TEXT,
                    genres TEXT NOT NULL DEFAULT '[]',
                    studios TEXT NOT NULL DEFAULT '[]',
                    people TEXT NOT NULL DEFAULT '[]',
                    index_number INTEGER,
                    parent_index_number INTEGER,
                    poster_url TEXT,
                    backdrop_url TEXT,
                    logo_url TEXT,
                    provider_ids TEXT NOT NULL DEFAULT '{}',
                    path TEXT,
                    size_bytes INTEGER,
                    media_streams TEXT NOT NULL DEFAULT '[]',
                    date_created INTEGER NOT NULL DEFAULT 0,
                    date_modified INTEGER NOT NULL DEFAULT 0,
                    etag TEXT,
                    scraped_at INTEGER,
                    probed_at INTEGER
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS user_data (
                    item_id TEXT PRIMARY KEY,
                    position_ms INTEGER NOT NULL DEFAULT 0,
                    played INTEGER NOT NULL DEFAULT 0,
                    play_count INTEGER NOT NULL DEFAULT 0,
                    favorite INTEGER NOT NULL DEFAULT 0,
                    last_played_at INTEGER,
                    audio_stream_index INTEGER,
                    subtitle_stream_index INTEGER,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_items_library ON items(library_id)",
                "CREATE INDEX IF NOT EXISTS idx_items_parent ON items(parent_id)",
                "CREATE INDEX IF NOT EXISTS idx_items_series ON items(series_id)",
                "CREATE INDEX IF NOT EXISTS idx_items_kind ON items(kind)",
                "CREATE INDEX IF NOT EXISTS idx_items_sort ON items(sort_name)",
                "CREATE INDEX IF NOT EXISTS idx_items_path ON items(path)",
                "CREATE INDEX IF NOT EXISTS idx_userdata_played ON user_data(last_played_at)"
            ),
            listOf(
                """
                CREATE TABLE IF NOT EXISTS scrape_cache (
                    key TEXT PRIMARY KEY,
                    payload TEXT NOT NULL,
                    fetched_at INTEGER NOT NULL
                )
                """.trimIndent()
            ),
            listOf(
                // Holds the provider the user pinned by hand for this item.
                "ALTER TABLE items ADD COLUMN locked_provider TEXT"
            )
        )
    }
}
