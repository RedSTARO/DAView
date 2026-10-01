package com.daview.server.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigStoreTest {

    private fun withDataDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("daview-config")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun Path.setAside(): List<Path> =
        Files.list(this).use { files ->
            files.filter { it.name.startsWith("config.json.unreadable-") }.toList()
        }

    @Test
    fun `a config that cannot be parsed is kept aside, not overwritten by the next save`() = withDataDir { dir ->
        // Cut short, the way a full disk or a crash leaves it. The password is
        // still there to be read by hand.
        val damaged = """{"serverName":"Living room","storage":{"url":"https://dav.example/","password":"hunter2""""
        dir.resolve("config.json").writeText(damaged)

        val store = ConfigStore(dir)
        assertEquals("DAView", store.current.serverName)

        store.update { it.copy(serverName = "Renamed") }

        val kept = dir.setAside()
        assertEquals(1, kept.size)
        assertEquals(damaged, kept.single().readText())
        assertTrue("Renamed" in dir.resolve("config.json").readText())
    }

    @Test
    fun `a readable config is loaded and nothing is set aside`() = withDataDir { dir ->
        dir.resolve("config.json").writeText("""{"serverName":"Living room","futureField":1}""")

        val store = ConfigStore(dir)

        assertEquals("Living room", store.current.serverName)
        assertTrue(dir.setAside().isEmpty())
    }
}
