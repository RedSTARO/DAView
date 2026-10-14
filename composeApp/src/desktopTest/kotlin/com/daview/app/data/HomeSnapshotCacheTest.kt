package com.daview.app.data

import com.daview.shared.api.DaViewJson
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSnapshotCacheTest {
    @Test
    fun `cache misses and future versions do not prevent a later valid write`() {
        val dir = createTempDirectory("daview-home-cache")
        try {
            val cache = HomeSnapshotCache(dir)
            assertNull(cache.read())
            val future = HomeSnapshot(version = 2, libraries = emptyList(), home = HomeData(), info = null)
            Files.createDirectories(dir.resolve("cache"))
            Files.write(dir.resolve("cache/home-v1.json"),
                DaViewJson.encodeToString(HomeSnapshot.serializer(), future).toByteArray())
            assertNull(cache.read())

            val current = future.copy(version = 1, home = HomeData(latest = listOf(media("latest"))))
            cache.write(current)
            assertEquals(current, HomeSnapshotCache(dir).read(), "The next instance must read the persisted snapshot")
            assertTrue(Files.list(dir.resolve("cache")).use { files -> files.noneMatch { it.toString().endsWith(".tmp") } })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
