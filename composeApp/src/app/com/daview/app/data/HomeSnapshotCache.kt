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

    fun read(): HomeSnapshot? = runCatching {
        // Bound allocations even if a partial or unrelated file replaces the cache.
        if (Files.size(file) > MAX_BYTES) return null
        DaViewJson.decodeFromString(HomeSnapshot.serializer(), String(Files.readAllBytes(file), Charsets.UTF_8))
            .takeIf { it.version == 1 }
    }.getOrNull()

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
    }
}
