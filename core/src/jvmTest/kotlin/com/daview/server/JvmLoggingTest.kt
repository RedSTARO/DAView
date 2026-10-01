package com.daview.server

import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmLoggingTest {

    @Test
    fun `the log reaches a file under the data directory, in UTF-8`() {
        val dir = Files.createTempDirectory("daview-log")
        try {
            val file = assertNotNull(enableFileLogging(dir))
            assertEquals(dir.resolve("logs").resolve("daview.log"), file)
            // Asking again does not add a second appender writing every line twice.
            assertEquals(file, enableFileLogging(dir))

            LoggerFactory.getLogger("com.daview.test").warn("扫描失败：存储拒绝了请求")

            val text = file.readText(Charsets.UTF_8)
            assertTrue("扫描失败：存储拒绝了请求" in text, text)
            assertEquals(1, text.lines().count { "扫描失败" in it })
        } finally {
            disableFileLogging()
            dir.toFile().deleteRecursively()
        }
    }
}
