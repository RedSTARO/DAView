package com.daview.server.db

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What happens when one unit of work is started from inside another. On
 * Android there is one connection and all of this falls out of that; over JDBC
 * there is a writer and a pool of readers, and each case used to go wrong in
 * its own way.
 */
class JdbcSqlDatabaseTest {

    private val dir = createTempDirectory("daview-jdbc-test")
    private val sql = JdbcSqlDatabase(dir)

    @BeforeTest
    fun table() {
        sql.transaction { it.statement("CREATE TABLE t (v INTEGER)").use { statement -> statement.executeUpdate() } }
    }

    @AfterTest
    fun tearDown() = sql.close()

    private fun insert(connection: SqlConnection, value: Int) =
        connection.statement("INSERT INTO t(v) VALUES ($value)").use { it.executeUpdate() }

    private fun count(connection: SqlConnection): Int =
        connection.statement("SELECT COUNT(*) FROM t").useQuery { if (it.next()) it.getIntAt(1) else -1 }

    @Test
    fun `a read inside a read does not take a second slot`() {
        // Five deep; the pool holds four. Each level used to hold one reader
        // while waiting for the next.
        val finished = AtomicBoolean(false)
        val worker = thread(isDaemon = true) {
            sql.read { sql.read { sql.read { sql.read { sql.read { connection -> count(connection) } } } } }
            finished.set(true)
        }
        worker.join(10_000)
        assertTrue(finished.get(), "a nested read waited for a slot its own thread was holding")
    }

    @Test
    fun `a read inside a transaction sees what the transaction has written`() {
        val seen = sql.transaction { connection ->
            insert(connection, 1)
            sql.read { count(it) }
        }
        assertEquals(1, seen)
    }

    @Test
    fun `a transaction inside a transaction joins it`() {
        assertFailsWith<IllegalStateException> {
            sql.transaction { outer ->
                sql.transaction { inner -> insert(inner, 1) }
                insert(outer, 2)
                error("the outer one fails after the inner one has returned")
            }
        }
        // The inner one used to commit on its way out, which kept its row and
        // left the outer one's in auto-commit, so both survived the failure.
        assertEquals(0, sql.read { count(it) })
    }

    @Test
    fun `the connection is handed back after nested work`() {
        sql.transaction { connection ->
            insert(connection, 1)
            sql.read { count(it) }
        }
        // Outside any transaction again, a read goes to a reader and sees the
        // committed row.
        assertEquals(1, sql.read { count(it) })
    }
}
