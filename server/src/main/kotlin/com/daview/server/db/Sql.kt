package com.daview.server.db

/**
 * The slice of SQL access this app needs, small enough to sit on top of either
 * JDBC or Android's SQLite.
 *
 * The method names deliberately match the JDBC helpers this replaced
 * (`getString(column)`, `getLongOrNull(column)`, …) so the queries and row
 * mappers read the same as before. Columns are addressed by name rather than
 * index because `SELECT i.*` is used throughout and positional access would
 * break the moment a column is added.
 */
interface SqlConnection {
    fun statement(sql: String): SqlStatement
}

interface SqlStatement : AutoCloseable {
    fun setString(index: Int, value: String?)
    fun setInt(index: Int, value: Int)
    fun setLong(index: Int, value: Long)
    fun setDouble(index: Int, value: Double)
    fun setNull(index: Int)

    fun executeUpdate(): Int

    /** Queues the current bindings; [executeBatch] applies them in one go. */
    fun addBatch()
    fun executeBatch()

    fun <T> useQuery(block: (SqlCursor) -> T): T
}

interface SqlCursor {
    fun next(): Boolean

    fun getString(column: String): String?

    /** For columns the schema declares NOT NULL; a null there means the file is corrupt. */
    fun requireString(column: String): String =
        getString(column) ?: error("column $column is null but the schema says NOT NULL")

    fun getLong(column: String): Long
    fun getLongOrNull(column: String): Long?
    fun getInt(column: String): Int
    fun getIntOrNull(column: String): Int?
    fun getDoubleOrNull(column: String): Double?

    /** Positional access, for aggregates like `SELECT COUNT(*)` that have no column name. */
    fun getStringAt(index: Int): String?
    fun getLongAt(index: Int): Long
    fun getIntAt(index: Int): Int
}

/**
 * Owns the connection and the write lock. Implementations are per platform:
 * JDBC on the desktop and server, Android's own SQLite on the phone.
 */
interface SqlDatabase : AutoCloseable {
    fun <T> read(block: (SqlConnection) -> T): T
    fun <T> transaction(block: (SqlConnection) -> T): T
}

/** Reads every remaining row. */
fun <T> SqlCursor.map(block: (SqlCursor) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += block(this)
    return out
}
