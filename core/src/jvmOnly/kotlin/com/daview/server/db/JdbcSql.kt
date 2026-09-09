package com.daview.server.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/**
 * [SqlDatabase] over sqlite-jdbc, used by the desktop app.
 *
 * Writes go through one connection under a lock — they are batched inside
 * short transactions during a scan, so contention stays low and SQLITE_BUSY
 * never comes up. Reads come from a small pool instead, because WAL lets any
 * number of readers work while a writer is busy, and one connection made them
 * queue behind each other: the home screen asks five questions at once and got
 * them answered one at a time.
 *
 * Readers are opened on demand rather than up front. A database that is only
 * ever read from one thread — every test here, and the app before anyone has
 * touched anything — then costs one connection instead of four, which matters
 * because each one is a file handle and a chunk of SQLite's own memory, and
 * the test suite builds a fresh database per test method.
 */
class JdbcSqlDatabase(dataDir: Path, fileName: String = "daview.db") : SqlDatabase {

    private val file = dataDir.also { it.createDirectories() }.resolve(fileName).toAbsolutePath()

    private val writeLock = ReentrantLock()
    private val writer: Connection

    /** Caps how many readers exist at once; a caller waits here for a turn. */
    private val slots = Semaphore(READERS)
    private val idle = ConcurrentLinkedQueue<JdbcConnection>()
    private val opened = CopyOnWriteArrayList<JdbcConnection>()

    init {
        Class.forName("org.sqlite.JDBC")
        // WAL is a property of the file, not of the connection, so the first
        // one to open sets it for every reader that follows.
        writer = connect().apply {
            createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        autoCommit = true
        createStatement().use { statement ->
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=10000")
        }
    }

    private val writeWrapper = JdbcConnection(writer)

    override fun <T> read(block: (SqlConnection) -> T): T {
        slots.acquire()
        val connection = idle.poll() ?: JdbcConnection(connect()).also { opened += it }
        try {
            return block(connection)
        } finally {
            idle.offer(connection)
            slots.release()
        }
    }

    override fun <T> transaction(block: (SqlConnection) -> T): T = writeLock.withLock {
        writer.autoCommit = false
        try {
            val result = block(writeWrapper)
            writer.commit()
            result
        } catch (t: Throwable) {
            runCatching { writer.rollback() }
            throw t
        } finally {
            writer.autoCommit = true
        }
    }

    override fun close() {
        writeLock.withLock { writer.close() }
        // Closes what was actually opened, and says so once: draining the idle
        // queue instead would block forever the second time round, and on a
        // reader another thread still holds.
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        idle.clear()
    }

    private companion object {
        /**
         * The ceiling, not the count. Enough for the home screen to ask
         * everything it needs at once; going wider does not help, because the
         * reads are short and past a handful they contend on the same pages
         * rather than finishing sooner.
         */
        const val READERS = 4
    }
}

private class JdbcConnection(private val connection: Connection) : SqlConnection {
    override fun statement(sql: String): SqlStatement = JdbcStatement(connection.prepareStatement(sql))
    fun close() = connection.close()
}

private class JdbcStatement(private val statement: PreparedStatement) : SqlStatement {
    override fun setString(index: Int, value: String?) = statement.setString(index, value)
    override fun setInt(index: Int, value: Int) = statement.setInt(index, value)
    override fun setLong(index: Int, value: Long) = statement.setLong(index, value)
    override fun setDouble(index: Int, value: Double) = statement.setDouble(index, value)
    override fun setNull(index: Int) = statement.setNull(index, Types.NULL)

    override fun executeUpdate(): Int = statement.executeUpdate()
    override fun addBatch() = statement.addBatch()
    override fun executeBatch() {
        statement.executeBatch()
    }

    override fun <T> useQuery(block: (SqlCursor) -> T): T =
        statement.use { it.executeQuery().use { rs -> block(JdbcCursor(rs)) } }

    override fun close() = statement.close()
}

private class JdbcCursor(private val rs: ResultSet) : SqlCursor {
    override fun next(): Boolean = rs.next()

    override fun getString(column: String): String? = rs.getString(column)
    override fun getLong(column: String): Long = rs.getLong(column)
    override fun getInt(column: String): Int = rs.getInt(column)

    override fun getLongOrNull(column: String): Long? {
        val value = rs.getLong(column)
        return if (rs.wasNull()) null else value
    }

    override fun getIntOrNull(column: String): Int? {
        val value = rs.getInt(column)
        return if (rs.wasNull()) null else value
    }

    override fun getDoubleOrNull(column: String): Double? {
        val value = rs.getDouble(column)
        return if (rs.wasNull()) null else value
    }

    override fun getStringAt(index: Int): String? = rs.getString(index)
    override fun getLongAt(index: Int): Long = rs.getLong(index)
    override fun getIntAt(index: Int): Int = rs.getInt(index)
}
