package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PersonDto
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search by cast reads a column of names that databases made before it do not
 * have. Opening one of those has to add the column and fill it from the cast
 * lists already stored — and a search for a word that only appears in the
 * JSON around the names must not match.
 */
class PeopleSearchUpgradeTest {

    private val dir = createTempDirectory("daview-upgrade-test")
    private var database: Database? = null

    @AfterTest
    fun tearDown() {
        database?.close()
    }

    private fun movie(id: String, name: String, vararg cast: String) = MediaItemDto(
        id = id,
        libraryId = "lib",
        kind = ItemKind.MOVIE,
        name = name,
        people = cast.map { PersonDto(name = it, role = "角色", type = "Actor", imageUrl = "https://image.tmdb.org/$it.jpg") }
    )

    private fun search(repository: Repository, text: String) = repository.query(
        Repository.Query(libraryId = "lib", topLevelOnly = true, search = text, searchPeople = true)
    ).first.map { it.id }.sorted()

    @Test
    fun `a database from before the column is filled in when it is opened`() {
        // Written with the current schema, then taken back to the old one:
        // what a user upgrading from the previous release has on disk.
        Database(JdbcSqlDatabase(dir)).also { old ->
            val repository = Repository(old)
            repository.upsertItem(ItemRecord(dto = movie("a", "花样年华", "梁朝伟", "张曼玉")))
            repository.upsertItem(ItemRecord(dto = movie("b", "卧虎藏龙", "周润发")))
            repository.upsertItem(ItemRecord(dto = movie("c", "无人之境")))
            old.transaction { connection ->
                connection.statement("ALTER TABLE items DROP COLUMN people_names").use { it.executeUpdate() }
            }
            old.close()
        }

        val reopened = Database(JdbcSqlDatabase(dir)).also { database = it }
        val repository = Repository(reopened)

        assertEquals(listOf("a"), search(repository, "梁朝伟"))
        assertEquals(listOf("b"), search(repository, "周润发"))
        // Words from the JSON the names used to be searched in.
        assertEquals(emptyList(), search(repository, "Actor"))
        assertEquals(emptyList(), search(repository, "tmdb"))
        assertEquals(emptyList(), search(repository, "name"))
    }

    @Test
    fun `items written now carry their cast names`() {
        val current = Database(JdbcSqlDatabase(dir)).also { database = it }
        val repository = Repository(current)
        repository.upsertItem(ItemRecord(dto = movie("a", "花样年华", "梁朝伟")))

        assertEquals(listOf("a"), search(repository, "梁朝"))
        assertEquals(listOf("a"), search(repository, "花样"))
    }
}
