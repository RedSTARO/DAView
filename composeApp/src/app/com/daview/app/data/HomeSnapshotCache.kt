package com.daview.app.data

import com.daview.shared.api.DaViewJson
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.ServerInfoDto
import kotlinx.serialization.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A disposable view of the local catalogue, never a source for database writes. */
@Serializable
internal data class HomeSnapshot(
    val version: Int = 1,
    val libraries: List<LibraryDto>,
    val home: HomeData,
    val info: ServerInfoDto?
)

/** Disk IO belongs on Dispatchers.IO. A broken/old cache is just a cache miss. */
internal class HomeSnapshotCache(dataDir: Path) {
    private val file = dataDir.resolve("cache").resolve("home-v1.json")

    fun read(): HomeSnapshot? {
        deleteAbandonedTemporaries()
        return runCatching {
            // Bound allocations even if a partial or unrelated file replaces the cache.
            if (Files.size(file) > MAX_BYTES) return null
            DaViewJson.decodeFromString(HomeSnapshot.serializer(), String(Files.readAllBytes(file), Charsets.UTF_8))
                .takeIf { it.version == 1 }
        }.getOrNull()
    }

    /**
     * A write cut short by the process ending leaves its temporary file
     * behind, as large as the snapshot, under a new name every time. The
     * read runs once at startup; a file a minute old is no longer being
     * written by anyone.
     */
    private fun deleteAbandonedTemporaries() {
        val cutoff = System.currentTimeMillis() - ABANDONED_AFTER_MS
        runCatching {
            Files.newDirectoryStream(file.parent, "home-*.tmp").use { files ->
                files.forEach { path ->
                    runCatching {
                        if (Files.getLastModifiedTime(path).toMillis() < cutoff) Files.deleteIfExists(path)
                    }
                }
            }
        }
    }

    fun write(snapshot: HomeSnapshot) {
        val bytes = DaViewJson.encodeToString(HomeSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "home-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val ABANDONED_AFTER_MS = 60_000L
    }
}
