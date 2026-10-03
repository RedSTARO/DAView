package com.daview.server.config

import com.daview.server.ServerContext
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.storage.WebDavClient
import com.daview.server.storage.WebDavException
import com.daview.shared.model.StorageSettingsDto
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StorageSettingsTest {
    @Test
    fun `invalid storage does not replace either saved settings or the active client`() {
        val dir = createTempDirectory("daview-settings-validation")
        val sql = JdbcSqlDatabase(dir)
        try {
            ServerContext(dir, sql).use { context ->
                context.updateConfig { it.copy(storage = StorageConfig("https://example.invalid/dav", "user", "test-secret")) }
                val before = context.config
                val client = context.webdav()
                val file = dir.resolve("config.json")
                val bytes = file.readText()
                assertFailsWith<WebDavException> {
                    context.updateConfig { it.copy(serverName = "Uncommitted", storage = it.storage.copy(url = "http://[bad")) }
                }
                assertEquals(before, context.config)
                assertSame(client, context.webdav())
                assertEquals(bytes, file.readText())
                assertEquals(before, ConfigStore(dir).current)
            }
        } finally { sql.close(); dir.toFile().deleteRecursively() }
    }

    @Test
    fun `a malformed address saved by an older build can be repaired without losing credentials`() = runBlocking {
        val dir = createTempDirectory("daview-settings-repair")
        val file = dir.resolve("config.json")
        val original = """{"storage":{"url":"http://[bad","username":"user","password":"test-secret"}}"""
        file.writeText(original)
        val sql = JdbcSqlDatabase(dir)
        try {
            ServerContext(dir, sql).use { context ->
                assertNull(context.webdav())
                assertEquals(original, file.readText())
                assertFalse(context.media.info().storageConfigured)
                val settings = context.media.settings()
                assertEquals("http://[bad", settings.storage.url)
                assertTrue(settings.storage.passwordSet)
                context.media.updateSettings(settings.copy(storage = StorageSettingsDto(url = "https://example.invalid/repaired", username = "user")))
                assertNotNull(context.webdav())
                assertTrue(context.media.info().storageConfigured)
                assertEquals("test-secret", context.config.storage.password)
                assertEquals("https://example.invalid/repaired/", context.webdav()?.absoluteUrl("/"))
            }
        } finally { sql.close(); dir.toFile().deleteRecursively() }
    }

    @Test
    fun `URL whitespace and path spaces are normalized by the same parser as requests`() {
        val dav = WebDavClient(StorageConfig("  https://example.invalid/my folder/  "))
        assertEquals("https://example.invalid/my%20folder/A%2BB.mkv", dav.absoluteUrl("/A+B.mkv"))
    }

    @Test
    fun `HTTP path characters are escaped when converted to a Java URI`() {
        val dav = WebDavClient(StorageConfig("https://example.invalid/[archive]/"))
        assertEquals("https://example.invalid/%5Barchive%5D/movie.mkv", dav.absoluteUrl("/movie.mkv"))
    }

    @Test
    fun `unsupported schemes and addresses without a host fail before making requests`() {
        for (url in listOf("file:///tmp/media", "ftp://example.invalid", "example.invalid/dav", "http://")) {
            val error = assertFailsWith<WebDavException> { WebDavClient(StorageConfig(url)) }
            assertTrue(error.message.orEmpty().contains("WebDAV 地址"))
        }
    }
}
