package com.daview.server.scraper

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.slf4j.LoggerFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HttpScraperTest {
    private val dir = createTempDirectory("daview-http-scraper")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}/api?api_key=secret-test-key"
    private val scraper = TestScraper(repository)

    private class TestScraper(repository: Repository) : HttpScraper(repository) {
        fun fetch(url: String) = getJson(url, cacheKey = "test").obj
    }

    @AfterTest
    fun close() {
        server.stop(0)
        database.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `an invalid success response does not poison subsequent requests`() {
        var requests = 0
        server.createContext("/api") { exchange ->
            // The lenient parser accepts a bare word as a JSON string. The
            // provider contract still requires an object/array, so do not cache it.
            val body = if (++requests == 1) "Unavailable" else """{"ok":true}"""
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.write(body.toByteArray())
            exchange.close()
        }
        assertNull(scraper.fetch(url))
        assertNotNull(scraper.fetch(url))
        assertNotNull(scraper.fetch(url))
        assertEquals(2, requests, "only a valid JSON response can satisfy the cache")
    }

    @Test
    fun `an old malformed cache entry is replaced by a valid remote response`() {
        repository.cachePut("test", "<html>old error</html>")
        server.createContext("/api") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("{}".toByteArray())
            exchange.close()
        }
        assertNotNull(scraper.fetch(url))
        assertEquals("{}", repository.cacheGet("test", 60_000))
    }

    @Test
    fun `HTTP errors never put the API key in diagnostic logs`() {
        server.createContext("/api") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        val logger = LoggerFactory.getLogger(TestScraper::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertNull(scraper.fetch(url))
            val messages = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse("secret-test-key" in messages, messages)
            assertFalse(messages.isEmpty(), "the failure remains diagnosable")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
