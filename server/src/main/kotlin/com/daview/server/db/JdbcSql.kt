package com.daview.server.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/**
 * [SqlDatabase] over sqlite-jdbc, used by the server and the desktop app.
 *
 * Access is funnelled through one connection guarded by a lock: writes during a
 * scan are batched inside short transactions, so contention stays low and
 * SQLITE_BUSY never comes up.
 */
class JdbcSqlDatabase(dataDir: Path, fileName: String = "daview.db") : SqlDatabase {

    private val lock = ReentrantLock()
    private val connection: Connection

    init {
        dataDir.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val file = dataDir.resolve(fileName).toAbsolutePath()
        connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
            autoCommit = true
            createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=10000")
            }
        }
    }

    private val wrapper = JdbcConnection(connection)

    override fun <T> read(block: (SqlConnection) -> T): T = lock.withLock { block(wrapper) }

    override fun <T> transaction(block: (SqlConnection) -> T): T = lock.withLock {
        connection.autoCommit = false
        try {
            val result = block(wrapper)
            connection.commit()
            result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = lock.withLock { connection.close() }
}

private class JdbcConnection(private val connection: Connection) : SqlConnection {
    override fun statement(sql: String): SqlStatement = JdbcStatement(connection.prepareStatement(sql))
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
