package com.daview.app.data

import com.daview.shared.api.DaViewJson
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `startup read removes temporaries abandoned by an interrupted write`() {
        val dir = createTempDirectory("daview-home-cache")
        try {
            val cacheDir = Files.createDirectories(dir.resolve("cache"))
            val abandoned = Files.write(cacheDir.resolve("home-123.tmp"), byteArrayOf(1, 2, 3))
            Files.setLastModifiedTime(abandoned, FileTime.fromMillis(System.currentTimeMillis() - 10 * 60_000L))
            // Another instance may be mid-write; a fresh file is left alone.
            val inFlight = Files.write(cacheDir.resolve("home-456.tmp"), byteArrayOf(4))
            val unrelated = Files.write(cacheDir.resolve("other.tmp"), byteArrayOf(5))
            Files.setLastModifiedTime(unrelated, FileTime.fromMillis(0))

            assertNull(HomeSnapshotCache(dir).read())

            assertFalse(Files.exists(abandoned))
            assertTrue(Files.exists(inFlight))
            assertTrue(Files.exists(unrelated))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
