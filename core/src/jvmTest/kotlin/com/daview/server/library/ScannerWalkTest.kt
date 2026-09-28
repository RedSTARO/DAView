package com.daview.server.library

import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.daview.server.storage.DavEntry
import com.daview.server.storage.DirectoryLister
import com.daview.server.storage.WebDavException
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.StreamType
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walk goes one level at a time and compares per folder. What matters is
 * that new folders are written before known ones are looked at, that a known
 * folder is only ever changed by what its own listing says, and that what a
 * probe found inside a file outlives a rescan of the same file.
 */
class ScannerWalkTest {

    private val dir = createTempDirectory("daview-walk-test")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val tree = MemoryTree()
    private val anime = LibraryDto(id = "lib", name = "番剧", kind = LibraryKind.ANIME, path = "/Ani")
    private val quiet = Scanner.ProgressSink { _, _, _, _ -> }

    @AfterTest
    fun tearDown() = database.close()

    /** A directory tree held in memory, listed the way the share would list it. */
    private class MemoryTree : DirectoryLister {
        private val dirs = HashMap<String, MutableList<DavEntry>>()
        val failing = HashSet<String>()

        fun file(path: String, size: Long = 1, etag: String? = null) {
            val parent = path.substringBeforeLast('/')
            dir(parent)
            val entries = dirs.getValue(parent)
            entries.removeAll { it.path == path }
            entries += DavEntry(path.substringAfterLast('/'), path, isDirectory = false, size = size, lastModified = null, etag = etag)
        }

        fun dir(path: String) {
            if (path in dirs) return
            dirs[path] = ArrayList()
            if (path.count { it == '/' } > 1) {
                val parent = path.substringBeforeLast('/')
                dir(parent)
                dirs.getValue(parent) += DavEntry(path.substringAfterLast('/'), path, isDirectory = true, size = null, lastModified = null, etag = null)
            }
        }

        fun remove(path: String) {
            dirs.remove(path)
            dirs.keys.filter { it.startsWith("$path/") }.forEach { dirs.remove(it) }
            dirs.getValue(path.substringBeforeLast('/')).removeAll { it.path == path }
        }

        override fun list(relativePath: String): List<DavEntry> {
            if (relativePath in failing) throw WebDavException("读取失败", 500)
            return dirs[relativePath]?.toList() ?: emptyList()
        }
    }

    private data class Walked(val fresh: List<String>, val changes: Scanner.Changes, val result: Scanner.Result)

    private fun walk(library: LibraryDto = anime, progress: Scanner.ProgressSink = quiet): Walked {
        val walk = Scanner(tree, repository).begin(library, progress)
        val fresh = walk.scanNew()
        val changes = walk.scanExisting()
        return Walked(fresh, changes, walk.finish())
    }

    private fun id(path: String) = Scanner.itemId("lib", path)

    private val ep1 = "/Ani/Show (2020)/Season 01/Show - S01E01.mkv"
    private val ep2 = "/Ani/Show (2020)/Season 01/Show - S01E02.mkv"

    @Test
    fun `the first walk counts titles, not their episodes`() {
        tree.file(ep1)
        tree.file(ep2)
        tree.file("/Ani/Other (2021)/Season 01/Other - S01E01.mkv")

        val walked = walk()

        assertEquals(listOf(id("/Ani/Other (2021)"), id("/Ani/Show (2020)")), walked.fresh.sorted())
        assertEquals(2, walked.result.newTitles)
        assertEquals(0, walked.result.newEpisodes, "episodes of a new show are not 'new episodes'")
        assertEquals(0, walked.result.removed)
        assertEquals(ItemKind.EPISODE, repository.item(id(ep2))?.kind)
    }

    @Test
    fun `new folders are written first, known folders are checked after`() {
        tree.file(ep1)
        walk()

        tree.file(ep2)
        tree.file("/Ani/Other (2021)/Season 01/Other - S01E01.mkv")
        val phases = mutableListOf<String>()
        val walk = Scanner(tree, repository).begin(anime) { phase, _, _, message -> phases += "$phase:$message" }

        val fresh = walk.scanNew()
        assertEquals(listOf(id("/Ani/Other (2021)")), fresh)
        assertNotNull(repository.item(fresh.single()), "written before the known folders are read")
        assertNull(repository.item(id(ep2)), "the known folder has not been read yet")

        val changes = walk.scanExisting()
        val result = walk.finish()
        assertEquals(setOf(id("/Ani/Show (2020)")), changes.seriesWithNewEpisodes)
        assertEquals(emptyList(), changes.newTitleIds)
        assertEquals(1, result.newTitles)
        assertEquals(1, result.newEpisodes)
        assertTrue(
            phases.indexOf("scanning:Other (2021)") < phases.indexOf("checking:Show (2020)"),
            "phases were $phases"
        )
    }

    @Test
    fun `what a probe found survives a rescan until the file changes`() {
        tree.file(ep1, size = 10, etag = "a")
        walk()
        val record = repository.itemRecord(id(ep1))!!
        val embedded = MediaStreamDto(index = 1, type = StreamType.AUDIO, codec = "aac", language = "ja")
        repository.upsertItem(record.copy(dto = record.dto.copy(mediaStreams = listOf(embedded)), probedAt = 123L))

        walk()
        val unchanged = repository.itemRecord(id(ep1))!!
        assertEquals(123L, unchanged.probedAt)
        assertEquals(listOf(embedded), unchanged.dto.mediaStreams)

        // A subtitle put beside it is picked up without the probe being lost.
        tree.file("/Ani/Show (2020)/Season 01/Show - S01E01.zh-Hans.ass")
        walk()
        val withSubtitle = repository.itemRecord(id(ep1))!!
        assertEquals(123L, withSubtitle.probedAt)
        assertEquals(listOf(false, true), withSubtitle.dto.mediaStreams.map { it.isExternal })
        assertEquals("zh-Hans", withSubtitle.dto.mediaStreams.last().language)

        // A different file under the same name is probed again.
        tree.file(ep1, size = 11, etag = "b")
        walk()
        val replaced = repository.itemRecord(id(ep1))!!
        assertNull(replaced.probedAt)
        assertEquals(listOf(true), replaced.dto.mediaStreams.map { it.isExternal })
    }

    @Test
    fun `a folder whose listing fails keeps what it had`() {
        tree.file(ep1)
        walk()

        tree.failing += "/Ani/Show (2020)"
        val walked = walk()

        assertEquals(0, walked.result.removed)
        assertNotNull(repository.item(id(ep1)))
        assertEquals(1, walked.result.warnings.size)
    }

    @Test
    fun `a folder gone from the share is removed, an empty root is not believed`() {
        tree.file(ep1)
        tree.file("/Ani/Other (2021)/Season 01/Other - S01E01.mkv")
        walk()

        tree.remove("/Ani/Other (2021)")
        val gone = walk()
        assertEquals(3, gone.result.removed, "the show, its season and its episode")
        assertNull(repository.item(id("/Ani/Other (2021)")))
        assertNotNull(repository.item(id(ep1)))

        tree.remove("/Ani/Show (2020)")
        val empty = walk()
        assertEquals(0, empty.result.removed, "a listing with nothing in it is not acted on")
        assertNotNull(repository.item(id(ep1)))
        assertEquals(1, empty.result.warnings.size)
    }

    @Test
    fun `an episode gone from a known folder is removed`() {
        tree.file(ep1)
        tree.file(ep2)
        walk()

        tree.remove(ep2)
        val walked = walk()

        assertEquals(1, walked.result.removed)
        assertNull(repository.item(id(ep2)))
        assertNotNull(repository.item(id(ep1)))
        assertEquals(emptySet(), walked.changes.seriesWithNewEpisodes)
    }

    @Test
    fun `a film added to a known collection folder is a new title`() {
        val movies = LibraryDto(id = "lib", name = "电影", kind = LibraryKind.MOVIE, path = "/Movie")
        tree.file("/Movie/Marvel/Iron Man (2008).mkv")
        walk(movies)

        tree.file("/Movie/Marvel/Thor (2011).mkv")
        val walked = walk(movies)

        assertEquals(emptyList(), walked.fresh, "the folder itself was known")
        assertEquals(listOf(id("/Movie/Marvel/Thor (2011).mkv")), walked.changes.newTitleIds)
        assertEquals(1, walked.result.newTitles)
        assertEquals(0, walked.result.newEpisodes)
    }

    @Test
    fun `a merge is laid back over each folder as it is rewritten`() {
        tree.file("/Ani/SHOW (2015)/Season 01/SHOW - S01E01.mkv")
        tree.file("/Ani/Show (2015)/Season 01/Show - S01E01.mkv")
        walk()
        val keep = id("/Ani/SHOW (2015)")
        val dupe = id("/Ani/Show (2015)")
        repository.mergeItems(keep, listOf(dupe))
        assertEquals(2, repository.children(keep).size)

        walk()

        assertEquals(2, repository.children(keep).size, "both seasons still hang off the kept show")
        assertEquals(keep, repository.item(dupe)?.mergedInto)
    }
}
