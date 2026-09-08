package com.daview.server.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/**
 * The scraped catalogue lives in a single SQLite file so that it can be backed
 * up, inspected and moved with the rest of the server data directory.
 *
 * Access is funnelled through one connection guarded by a lock: writes during a
 * scan are batched inside short transactions, so contention stays low and we
 * avoid SQLITE_BUSY entirely.
 */
class Database(dataDir: Path) : AutoCloseable {

    private val lock = ReentrantLock()
    private val connection: Connection

    init {
        dataDir.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val file = dataDir.resolve("daview.db").toAbsolutePath()
        connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
            autoCommit = true
            createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=10000")
            }
        }
        migrate()
    }

    fun <T> read(block: (Connection) -> T): T = lock.withLock { block(connection) }

    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrate() = lock.withLock {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)
                """.trimIndent()
            )
            val current = statement.executeQuery("SELECT version FROM schema_version LIMIT 1").use {
                if (it.next()) it.getInt(1) else -1
            }
            if (current < 0) {
                statement.executeUpdate("INSERT INTO schema_version(version) VALUES (0)")
            }
            var version = maxOf(current, 0)
            while (version < MIGRATIONS.size) {
                MIGRATIONS[version].forEach { statement.executeUpdate(it) }
                version++
                statement.executeUpdate("UPDATE schema_version SET version = $version")
            }
        }
    }

    override fun close() = lock.withLock { connection.close() }

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
            )
        )
    }
}

// ---------------------------------------------------------------- helpers

fun <T> PreparedStatement.useQuery(block: (ResultSet) -> T): T = use { executeQuery().use(block) }

fun ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

fun ResultSet.getIntOrNull(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

fun ResultSet.getDoubleOrNull(column: String): Double? {
    val value = getDouble(column)
    return if (wasNull()) null else value
}

fun <T> ResultSet.map(block: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += block(this)
    return out
}
