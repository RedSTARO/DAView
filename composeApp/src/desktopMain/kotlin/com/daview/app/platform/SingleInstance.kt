package com.daview.app.platform

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Makes one DAView the only one working on a data directory.
 *
 * Two of them share one database, one config file and one download queue. Each
 * runs its own sync loop against the same file, each takes up the same
 * unfinished downloads and writes the same `.part` files, and whichever saves
 * its settings last drops what the other had changed, because each holds its
 * own copy of the config in memory. Nothing prevented it: a second double-click
 * on the shortcut while the first window was minimised was enough.
 *
 * The claim is a lock on `daview.lock` in the directory, which the operating
 * system releases when the process ends however it ends, so a crash leaves
 * nothing behind to clear by hand. A different `DAVIEW_DATA` is a different
 * directory and a different lock.
 */
object SingleInstance {

    /** Kept for the life of the process; letting go of it would release the lock. */
    private var held: FileLock? = null

    /**
     * True when another DAView already has [dataDir].
     *
     * A lock that cannot be taken for any other reason — a directory that
     * cannot be written, a file system without locking — is not a reason to
     * refuse to start: the check is what is optional, not the app.
     */
    @Synchronized
    fun takenByAnother(dataDir: Path): Boolean {
        if (held != null) return false
        return try {
            Files.createDirectories(dataDir)
            val channel = FileChannel.open(
                dataDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE
            )
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                true
            } else {
                held = lock
                false
            }
        } catch (e: OverlappingFileLockException) {
            // Held within this very process, which only a test does.
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Lets the directory go again. The app never does; it is here for tests. */
    @Synchronized
    internal fun release() {
        held?.let {
            runCatching { it.release() }
            runCatching { it.channel().close() }
        }
        held = null
    }

    private const val LOCK_FILE = "daview.lock"
}
