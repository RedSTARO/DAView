package com.daview.server.db

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [SqlDatabase] over Android's own SQLite.
 *
 * Goes through androidx.sqlite rather than SQLiteDatabase directly because
 * `rawQuery` only takes `String[]` arguments, which cannot express a bound null
 * or a real number; `SupportSQLiteQuery` binds by type, matching what JDBC does
 * on the other side.
 *
 * The schema, the queries and the row mapping are the shared ones — only the
 * driver differs.
 */
class AndroidSqlDatabase(
    context: Context,
    dataDir: File,
    fileName: String = "daview.db"
) : SqlDatabase {

    private val lock = ReentrantLock()

    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(File(dataDir, fileName).absolutePath)
            // Migrations are driven by the shared Database class, so the helper
            // itself has nothing to do on create or upgrade.
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
    )

    private val db: SupportSQLiteDatabase by lazy {
        helper.writableDatabase.apply {
            query(SimpleSQLiteQuery("PRAGMA journal_mode=WAL")).use { it.moveToFirst() }
            execSQL("PRAGMA synchronous=NORMAL")
            execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private val connection by lazy { AndroidConnection(db) }

    override fun <T> read(block: (SqlConnection) -> T): T = lock.withLock { block(connection) }

    override fun <T> transaction(block: (SqlConnection) -> T): T = lock.withLock {
        db.beginTransaction()
        try {
            val result = block(connection)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = lock.withLock { helper.close() }
}

private class AndroidConnection(private val db: SupportSQLiteDatabase) : SqlConnection {
    override fun statement(sql: String): SqlStatement = AndroidStatement(db, sql)
}

/**
 * Holds the bindings until execution, because Android needs a compiled
 * statement to write and a query object to read, and which one this is only
 * becomes clear when the caller asks.
 */
private class AndroidStatement(
    private val db: SupportSQLiteDatabase,
    private val sql: String
) : SqlStatement {

    private val bindings = ArrayList<Any?>()
    private val batch = ArrayList<Array<Any?>>()

    private fun put(index: Int, value: Any?) {
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

    private fun apply(args: Array<Any?>): Int {
        val statement = db.compileStatement(sql)
        statement.use {
            args.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> it.bindNull(position)
                    is Long -> it.bindLong(position, value)
                    is Int -> it.bindLong(position, value.toLong())
                    is Double -> it.bindDouble(position, value)
                    is ByteArray -> it.bindBlob(position, value)
                    else -> it.bindString(position, value.toString())
                }
            }
            return when (sql.trimStart().take(6).uppercase()) {
                "INSERT" -> if (it.executeInsert() >= 0) 1 else 0
                "UPDATE", "DELETE" -> it.executeUpdateDelete()
                else -> {
                    it.execute()
                    0
                }
            }
        }
    }

    override fun <T> useQuery(block: (SqlCursor) -> T): T {
        val cursor = db.query(SimpleSQLiteQuery(sql, bindings.toTypedArray()))
        return cursor.use { block(AndroidCursor(it)) }
    }

    override fun close() = Unit
}

private class AndroidCursor(private val cursor: Cursor) : SqlCursor {

    override fun next(): Boolean = cursor.moveToNext()

    private fun index(column: String): Int = cursor.getColumnIndexOrThrow(column)

    override fun getString(column: String): String? =
        index(column).let { if (cursor.isNull(it)) null else cursor.getString(it) }

    override fun getLong(column: String): Long =
        index(column).let { if (cursor.isNull(it)) 0L else cursor.getLong(it) }

    override fun getLongOrNull(column: String): Long? =
        index(column).let { if (cursor.isNull(it)) null else cursor.getLong(it) }

    override fun getInt(column: String): Int =
        index(column).let { if (cursor.isNull(it)) 0 else cursor.getInt(it) }

    override fun getIntOrNull(column: String): Int? =
        index(column).let { if (cursor.isNull(it)) null else cursor.getInt(it) }

    override fun getDoubleOrNull(column: String): Double? =
        index(column).let { if (cursor.isNull(it)) null else cursor.getDouble(it) }

    // JDBC counts columns from 1; Android's Cursor counts from 0.
    override fun getStringAt(index: Int): String? =
        if (cursor.isNull(index - 1)) null else cursor.getString(index - 1)

    override fun getLongAt(index: Int): Long =
        if (cursor.isNull(index - 1)) 0L else cursor.getLong(index - 1)

    override fun getIntAt(index: Int): Int =
        if (cursor.isNull(index - 1)) 0 else cursor.getInt(index - 1)
}
