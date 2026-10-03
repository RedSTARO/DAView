package com.daview.server.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bundled engine keeps the shared UPSERT queries working on API 26, whose
 * system SQLite predates UPSERT. Existing SQLite files keep the same format.
 *
 * The native connection is not thread-safe. Hold one reentrant lock for the
 * entire callback, including cursor consumption and transactions, so other
 * threads cannot read uncommitted writes or close an active native statement.
 */
class AndroidSqlDatabase(dataDir: File, fileName: String = "daview.db") : SqlDatabase {
    private val lock = ReentrantLock()
    private var closed = false
    private var transactionDepth = 0
    private var rollbackOnly = false
    private val db: SQLiteConnection = run {
        check(dataDir.isDirectory || dataDir.mkdirs()) { "Cannot create database directory" }
        val opened = BundledSQLiteDriver().open(File(dataDir, fileName).absolutePath)
        try {
            opened.execute("PRAGMA journal_mode=WAL")
            opened.execute("PRAGMA synchronous=NORMAL")
            opened.execute("PRAGMA foreign_keys=ON")
            opened.execute("PRAGMA busy_timeout=10000")
            opened
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }
    private val connection = AndroidConnection(db)

    override fun <T> read(block: (SqlConnection) -> T): T = lock.withLock {
        check(!closed) { "Database is closed" }
        block(connection)
    }

    override fun <T> transaction(block: (SqlConnection) -> T): T = lock.withLock {
        check(!closed) { "Database is closed" }
        if (transactionDepth > 0) {
            transactionDepth++
            try {
                return@withLock block(connection)
            } catch (failure: Throwable) {
                rollbackOnly = true
                throw failure
            } finally {
                transactionDepth--
            }
        }
        db.execute("BEGIN IMMEDIATE")
        transactionDepth = 1
        rollbackOnly = false
        try {
            val result = block(connection)
            check(!rollbackOnly) { "A nested transaction failed" }
            db.execute("COMMIT")
            result
        } catch (failure: Throwable) {
            runCatching { db.execute("ROLLBACK") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            transactionDepth = 0
            rollbackOnly = false
        }
    }

    override fun close() = lock.withLock {
        check(lock.holdCount == 1) { "Cannot close a database inside its callback" }
        if (!closed) {
            db.close()
            closed = true
        }
    }
}

private fun SQLiteConnection.execute(sql: String) = prepare(sql).use {
    while (it.step()) { /* Consume PRAGMA result rows as well as writes. */ }
}

private class AndroidConnection(private val db: SQLiteConnection) : SqlConnection {
    override fun statement(sql: String): SqlStatement = AndroidStatement(db, sql)
}

private class AndroidStatement(private val db: SQLiteConnection, private val sql: String) : SqlStatement {
    private val bindings = ArrayList<Any?>()
    private val batch = ArrayList<Array<Any?>>()

    private fun put(index: Int, value: Any?) {
        require(index > 0) { "SQL parameters are one-based" }
        while (bindings.size < index) bindings.add(null)
        bindings[index - 1] = value
    }

    override fun setString(index: Int, value: String?) = put(index, value)
    override fun setInt(index: Int, value: Int) = put(index, value.toLong())
    override fun setLong(index: Int, value: Long) = put(index, value)
    override fun setDouble(index: Int, value: Double) = put(index, value)
    override fun setNull(index: Int) = put(index, null)

    override fun executeUpdate(): Int = apply(bindings.toTypedArray())

    override fun addBatch() {
        batch += bindings.toTypedArray()
        bindings.clear()
    }

    override fun executeBatch() {
        batch.forEach { apply(it) }
        batch.clear()
    }

    private fun prepare(args: Array<Any?>): SQLiteStatement {
        val statement = db.prepare(sql)
        try {
            args.forEachIndexed { index, value ->
                when (value) {
                    null -> statement.bindNull(index + 1)
                    is Long -> statement.bindLong(index + 1, value)
                    is Double -> statement.bindDouble(index + 1, value)
                    is String -> statement.bindText(index + 1, value)
                    else -> error("Unsupported SQL binding")
                }
            }
            return statement
        } catch (failure: Throwable) {
            statement.close()
            throw failure
        }
    }

    private fun apply(args: Array<Any?>): Int {
        prepare(args).use { while (it.step()) { /* Finish the statement. */ } }
        // changes() excludes trigger writes and reports zero for ignored
        // inserts. DDL and PRAGMAs must not return a previous write's count.
        return when (sql.trimStart().take(6).uppercase()) {
            "INSERT", "UPDATE", "DELETE", "REPLAC" -> db.prepare("SELECT changes()").use {
                check(it.step())
                it.getLong(0).toInt()
            }
            else -> 0
        }
    }

    override fun <T> useQuery(block: (SqlCursor) -> T): T =
        prepare(bindings.toTypedArray()).use { block(AndroidCursor(it)) }

    override fun close() = Unit
}

private class AndroidCursor(private val statement: SQLiteStatement) : SqlCursor {
    private var exhausted = false
    private val columns by lazy { statement.getColumnNames() }

    override fun next(): Boolean {
        if (exhausted) return false
        return statement.step().also { if (!it) exhausted = true }
    }

    private fun index(column: String): Int = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        .also { require(it >= 0) { "No such column: $column" } }

    override fun getString(column: String): String? =
        index(column).let { if (statement.isNull(it)) null else statement.getText(it) }

    override fun getLong(column: String): Long = getLongOrNull(column) ?: 0L
    override fun getLongOrNull(column: String): Long? =
        index(column).let { if (statement.isNull(it)) null else statement.getLong(it) }

    override fun getInt(column: String): Int = getIntOrNull(column) ?: 0
    override fun getIntOrNull(column: String): Int? = getLongOrNull(column)?.toInt()

    override fun getDoubleOrNull(column: String): Double? =
        index(column).let { if (statement.isNull(it)) null else statement.getDouble(it) }

    // The shared interface uses JDBC's one-based column positions.
    override fun getStringAt(index: Int): String? =
        if (statement.isNull(index - 1)) null else statement.getText(index - 1)
    override fun getLongAt(index: Int): Long =
        if (statement.isNull(index - 1)) 0L else statement.getLong(index - 1)
    override fun getIntAt(index: Int): Int = getLongAt(index).toInt()
}
