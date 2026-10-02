package com.daview.app.platform

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One DAView per data directory, and never a reason not to start at all. */
class SingleInstanceTest {

    private val dir = Files.createTempDirectory("daview-single-instance")

    @AfterTest
    fun tearDown() {
        SingleInstance.release()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the first to ask has the directory, and asking again does not lose it`() {
        assertFalse(SingleInstance.takenByAnother(dir))
        assertTrue(Files.exists(dir.resolve("daview.lock")))
        assertFalse(SingleInstance.takenByAnother(dir), "this process already holds it")
    }

    @Test
    fun `a lock somebody else holds means the directory is taken`() {
        // Another holder, stood in for by a lock taken directly: within one
        // process the file system refuses the second lock the same way it
        // refuses another process's.
        val other = java.nio.channels.FileChannel.open(
            dir.resolve("daview.lock"),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE
        )
        val lock = other.lock()
        try {
            assertTrue(SingleInstance.takenByAnother(dir))
        } finally {
            lock.release()
            other.close()
        }
        // Once it is let go the directory can be claimed.
        assertFalse(SingleInstance.takenByAnother(dir))
    }

    @Test
    fun `a directory that cannot hold a lock file does not stop the app`() {
        // A file where the directory should be: nothing can be created under it.
        val notADirectory = Files.createFile(dir.resolve("plain-file"))
        assertFalse(SingleInstance.takenByAnother(notADirectory))
    }
}
