package com.daview.server.db

import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.LibraryKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.MediaStreamDto
import com.daview.shared.model.MetadataProvider
import com.daview.shared.model.PersonDto
import com.daview.shared.model.ScrapeStatus
import com.daview.shared.model.UserDataDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private val stringListSerializer = ListSerializer(String.serializer())
private val personListSerializer = ListSerializer(PersonDto.serializer())
private val streamListSerializer = ListSerializer(MediaStreamDto.serializer())
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
private val providerListSerializer = ListSerializer(MetadataProvider.serializer())

/** A watch-state row with the timestamp the API does not expose. */
data class UserDataRow(val itemId: String, val data: UserDataDto, val updatedAt: Long)

/**
 * A hand-picked scrape entry. The provider is stored as the enum name so an
 * unknown value from a newer build can be skipped rather than crashing the read.
 */
data class PinRow(
    val itemId: String,
    val provider: String,
    val providerId: String,
    val updatedAt: Long
)

/** Row shape for [items], including bookkeeping columns the API never exposes. */
data class ItemRecord(
    val dto: MediaItemDto,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    val etag: String? = null,
    val scrapedAt: Long? = null,
    val probedAt: Long? = null
)

class Repository(private val db: Database) {

    // ------------------------------------------------------------ libraries

    fun libraries(): List<LibraryDto> = db.read { connection ->
        connection.statement(
            "SELECT l.*, (SELECT COUNT(*) FROM items i WHERE i.library_id = l.id AND i.kind IN ('MOVIE','SERIES')) AS item_count " +
                "FROM libraries l ORDER BY l.created_at"
        ).useQuery { it.map(::readLibrary) }
    }

    fun library(id: String): LibraryDto? = db.read { connection ->
        connection.statement(
            "SELECT l.*, (SELECT COUNT(*) FROM items i WHERE i.library_id = l.id AND i.kind IN ('MOVIE','SERIES')) AS item_count " +
                "FROM libraries l WHERE l.id = ?"
        ).apply { setString(1, id) }.useQuery { if (it.next()) readLibrary(it) else null }
    }

    fun upsertLibrary(library: LibraryDto) = db.transaction { connection ->
        connection.statement(
            """
            INSERT INTO libraries(id, name, kind, path, provider_order, language, last_scan_at, image_url, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name, kind = excluded.kind, path = excluded.path,
                provider_order = excluded.provider_order, language = excluded.language,
                last_scan_at = excluded.last_scan_at, image_url = excluded.image_url
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, library.id)
            statement.setString(2, library.name)
            statement.setString(3, library.kind.name)
            statement.setString(4, library.path)
            statement.setString(5, json.encodeToString(providerListSerializer, library.providerOrder))
            statement.setString(6, library.language)
            library.lastScanAt?.let { statement.setLong(7, it) } ?: statement.setNull(7)
            statement.setString(8, library.imageUrl)
            statement.setLong(9, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun deleteLibrary(id: String) = db.transaction { connection ->
        connection.statement("DELETE FROM user_data WHERE item_id IN (SELECT id FROM items WHERE library_id = ?)")
            .use { it.setString(1, id); it.executeUpdate() }
        connection.statement("DELETE FROM items WHERE library_id = ?")
            .use { it.setString(1, id); it.executeUpdate() }
        connection.statement("DELETE FROM libraries WHERE id = ?")
            .use { it.setString(1, id); it.executeUpdate() }
    }

    /** See [Database.refreshStatistics]; called once a scan has settled. */
    fun refreshStatistics() = db.refreshStatistics()

    fun markScanned(libraryId: String, at: Long) = db.transaction { connection ->
        connection.statement("UPDATE libraries SET last_scan_at = ? WHERE id = ?").use {
            it.setLong(1, at); it.setString(2, libraryId); it.executeUpdate()
        }
    }

    private fun readLibrary(rs: SqlCursor) = LibraryDto(
        id = rs.requireString("id"),
        name = rs.requireString("name"),
        kind = runCatching { LibraryKind.valueOf(rs.requireString("kind")) }.getOrDefault(LibraryKind.OTHER),
        path = rs.requireString("path"),
        providerOrder = runCatching {
            json.decodeFromString(providerListSerializer, rs.requireString("provider_order"))
        }.getOrDefault(emptyList()),
        language = rs.requireString("language"),
        itemCount = runCatching { rs.getInt("item_count") }.getOrDefault(0),
        lastScanAt = rs.getLongOrNull("last_scan_at"),
        imageUrl = rs.getString("image_url")
    )

    // ------------------------------------------------------------ items

    fun upsertItems(records: List<ItemRecord>) = writeItems(records, UPSERT_ITEM)

    fun upsertItem(record: ItemRecord) = upsertItems(listOf(record))

    /**
     * Write path for the scanner. Columns the scraper owns are left alone once
     * an item has been scraped, so a rescan re-checks files and paths without
     * throwing away titles, artwork or a manually pinned provider id.
     */
    fun upsertScannedItems(records: List<ItemRecord>) = writeItems(records, UPSERT_SCANNED_ITEM)

    private fun writeItems(records: List<ItemRecord>, sql: String) {
        if (records.isEmpty()) return
        db.transaction { connection ->
            connection.statement(sql).use { statement ->
                records.forEach { record ->
                    bindItem(statement, record)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun item(id: String): MediaItemDto? = db.read { connection ->
        connection.statement("$SELECT_ITEM WHERE i.id = ?")
            .apply { setString(1, id) }
            .useQuery { if (it.next()) readItem(it) else null }
    }

    fun itemRecord(id: String): ItemRecord? = db.read { connection ->
        connection.statement("$SELECT_ITEM WHERE i.id = ?")
            .apply { setString(1, id) }
            .useQuery { if (it.next()) readItemRecord(it) else null }
    }

    /**
     * The seasons of a series, or the episodes of a season. Season 0 goes to
     * the end of a series' list for the same reason it is last everywhere else
     * — it is the specials folder — and that also decides which season a
     * series page opens on, since it opens on the first one.
     */
    fun children(parentId: String): List<MediaItemDto> = db.read { connection ->
        connection.statement(
            "$SELECT_ITEM WHERE i.parent_id = ? AND i.merged_into IS NULL " +
                "ORDER BY CASE WHEN i.kind = 'SEASON' AND i.index_number = 0 THEN 1 ELSE 0 END, " +
                "COALESCE(i.index_number, 99999), i.sort_name"
        ).apply { setString(1, parentId) }.useQuery { it.map(::readItem) }
    }

    fun episodesOfSeries(seriesId: String): List<MediaItemDto> = db.read { connection ->
        connection.statement(
            "$SELECT_ITEM WHERE i.series_id = ? AND i.kind = 'EPISODE' " +
                "ORDER BY COALESCE(i.parent_index_number, 0), COALESCE(i.index_number, 99999)"
        ).apply { setString(1, seriesId) }.useQuery { it.map(::readItem) }
    }

    fun idsInLibrary(libraryId: String): Set<String> = db.read { connection ->
        connection.statement("SELECT id FROM items WHERE library_id = ?")
            .apply { setString(1, libraryId) }
            .useQuery { rs -> rs.map { it.requireString("id") }.toSet() }
    }

    // ------------------------------------------------------------ merging

    /**
     * Folds [sourceIds] into [targetId]: their seasons and episodes are
     * re-parented, and the duplicate rows are flagged rather than deleted so
     * the merge can be undone and re-applied after a rescan.
     */
    fun mergeItems(targetId: String, sourceIds: List<String>) = db.transaction { connection ->
        sourceIds.filter { it != targetId }.forEach { sourceId ->
            connection.statement("UPDATE items SET series_id = ? WHERE series_id = ?")
                .use { it.setString(1, targetId); it.setString(2, sourceId); it.executeUpdate() }
            connection.statement("UPDATE items SET parent_id = ? WHERE parent_id = ?")
                .use { it.setString(1, targetId); it.setString(2, sourceId); it.executeUpdate() }
            connection.statement("UPDATE items SET merged_into = ? WHERE id = ?")
                .use { it.setString(1, targetId); it.setString(2, sourceId); it.executeUpdate() }
        }
    }

    /**
     * Undoes one merge. The children go back by path: everything under the
     * source's own directory belonged to it, which is the same rule the scanner
     * used to build the tree in the first place.
     */
    fun unmergeItem(sourceId: String) {
        val source = item(sourceId) ?: return
        val prefix = source.path?.trimEnd('/')?.plus("/") ?: return
        // substr(...) = ? rather than LIKE: SQLite's LIKE ignores ASCII case, and
        // two folders differing only in case is exactly the kind of duplicate
        // people merge. LIKE would drag the target's own children back too.
        db.transaction { connection ->
            connection.statement(
                "UPDATE items SET series_id = ? WHERE series_id = ? AND substr(path, 1, length(?)) = ?"
            ).use {
                it.setString(1, sourceId); it.setString(2, source.mergedInto)
                it.setString(3, prefix); it.setString(4, prefix); it.executeUpdate()
            }
            connection.statement(
                "UPDATE items SET parent_id = ? WHERE parent_id = ? AND substr(path, 1, length(?)) = ?"
            ).use {
                it.setString(1, sourceId); it.setString(2, source.mergedInto)
                it.setString(3, prefix); it.setString(4, prefix); it.executeUpdate()
            }
            connection.statement("UPDATE items SET merged_into = NULL WHERE id = ?")
                .use { it.setString(1, sourceId); it.executeUpdate() }
        }
    }

    /** The duplicates folded into [targetId]. */
    fun mergedSources(targetId: String): List<MediaItemDto> = db.read { connection ->
        connection.statement("$SELECT_ITEM WHERE i.merged_into = ? ORDER BY i.sort_name")
            .apply { setString(1, targetId) }
            .useQuery { it.map(::readItem) }
    }

    /**
     * Re-applies every recorded merge. The scanner rebuilds parentage from the
     * folder tree on each run, which would otherwise split merged series apart
     * again the moment a library is rescanned.
     */
    fun reapplyMerges() {
        val pairs = db.read { connection ->
            connection.statement("SELECT id, merged_into FROM items WHERE merged_into IS NOT NULL")
                .useQuery { rs -> rs.map { it.requireString("id") to it.requireString("merged_into") } }
        }
        pairs.groupBy({ it.second }, { it.first })
            .forEach { (target, sources) -> mergeItems(target, sources) }
    }

    fun deleteItems(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { connection ->
            connection.statement("DELETE FROM items WHERE id = ?").use { statement ->
                ids.forEach { statement.setString(1, it); statement.addBatch() }
                statement.executeBatch()
            }
        }
    }

    data class Query(
        val libraryId: String? = null,
        val parentId: String? = null,
        val kind: ItemKind? = null,
        val search: String? = null,
        val favorite: Boolean? = null,
        val sort: String = "sortName",
        val limit: Int = 100,
        val offset: Int = 0
    )

    fun query(query: Query): Pair<List<MediaItemDto>, Int> = db.read { connection ->
        // A merged-away duplicate is not a separate entry any more.
        val where = StringBuilder("WHERE i.merged_into IS NULL")
        val binds = ArrayList<Any?>()
        query.libraryId?.let { where.append(" AND i.library_id = ?"); binds += it }
        query.parentId?.let { where.append(" AND i.parent_id = ?"); binds += it }
        query.kind?.let { where.append(" AND i.kind = ?"); binds += it.name }
        query.favorite?.let { where.append(" AND COALESCE(u.favorite, 0) = ?"); binds += if (it) 1 else 0 }
        query.search?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND (i.name LIKE ? OR i.original_name LIKE ? OR i.sort_name LIKE ?)")
            val pattern = "%${it.trim()}%"
            binds += pattern; binds += pattern; binds += pattern
        }

        val order = when (query.sort) {
            "name" -> "i.name COLLATE NOCASE"
            "year" -> "i.year DESC, i.sort_name"
            "added" -> "i.date_created DESC"
            "played" -> "u.last_played_at DESC"
            "rating" -> "i.community_rating DESC NULLS LAST, i.sort_name"
            "index" -> "COALESCE(i.parent_index_number, 0), COALESCE(i.index_number, 99999)"
            else -> "i.sort_name"
        }

        val total = connection.statement(
            "SELECT COUNT(*) FROM items i LEFT JOIN user_data u ON u.item_id = i.id $where"
        ).apply { bind(binds) }.useQuery { if (it.next()) it.getIntAt(1) else 0 }

        val items = connection.statement(
            "$SELECT_ITEM $where ORDER BY $order LIMIT ? OFFSET ?"
        ).apply {
            bind(binds)
            setInt(binds.size + 1, query.limit)
            setInt(binds.size + 2, query.offset)
        }.useQuery { it.map(::readItem) }

        items to total
    }

    /**
     * Season 0 is the specials folder in the Emby / Jellyfin layout, and it is
     * the one season whose number does not say where it sits in the run: it
     * sorts first as an integer while belonging last as a run order. Ordering
     * by the season number alone is why pressing play on a series started its
     * SP instead of `S01E01`.
     *
     * A null season is *not* season 0. Episodes that sit directly under the
     * series carry no season at all, and they are the main run, not extras.
     */
    private fun specialsLast(alias: String) =
        "CASE WHEN $alias.parent_index_number = 0 THEN 1 ELSE 0 END"

    /**
     * The same run order as one sortable integer.
     *
     * SQLite has no MIN over a tuple, so a query that wants the earliest slot
     * in a series has to compare a single value. The multipliers sit four
     * orders of magnitude clear of anything a real run order produces —
     * episode numbers reach the dozens, season numbers the tens.
     */
    private fun runOrderSlot(alias: String) =
        "(${specialsLast(alias)}) * 100000000 " +
            "+ COALESCE($alias.parent_index_number, 0) * 100000 " +
            "+ COALESCE($alias.index_number, 0)"

    /** Partially watched playable items, most recent first. */
    fun resume(limit: Int): List<MediaItemDto> = db.read { connection ->
        connection.statement(
            "$SELECT_ITEM WHERE u.position_ms > 0 AND COALESCE(u.played, 0) = 0 " +
                "AND i.kind IN ('MOVIE','EPISODE') ORDER BY u.last_played_at DESC LIMIT ?"
        ).apply { setInt(1, limit) }.useQuery { it.map(::readItem) }
    }

    /**
     * First unwatched episode of every series that has been started. A special
     * only qualifies once nothing in the regular seasons is still unwatched,
     * so a series with an untouched `Season 00` does not sit in "next up"
     * offering its SP while the viewer is half way through season one.
     *
     * "First" used to be asked one row at a time — for each unwatched episode,
     * is there an earlier unwatched one in the same series — which re-read the
     * series once per candidate and cost 91 ms on a 3000 item catalogue. One
     * grouped pass answers it for every series at once and the join keeps the
     * rows sitting in that slot: 14 ms, same rows in the same order.
     *
     * Ties are kept rather than broken. A library can hold two rows for the
     * same season and episode — a duplicate folder, or a season scanned twice,
     * of which this one has 32 — and comparing strictly earlier returned both.
     * Picking one here would quietly change what the home screen shows.
     */
    fun nextUp(limit: Int): List<MediaItemDto> = db.read { connection ->
        connection.statement(
            """
            $SELECT_ITEM
            WHERE i.id IN (
                WITH earliest AS (
                    SELECT e.series_id AS series_id, MIN(${runOrderSlot("e")}) AS slot
                    FROM items e LEFT JOIN user_data ue ON ue.item_id = e.id
                    WHERE e.kind = 'EPISODE'
                      AND COALESCE(ue.played, 0) = 0 AND COALESCE(ue.position_ms, 0) = 0
                    GROUP BY e.series_id
                )
                SELECT i.id AS id FROM items i
                JOIN earliest ON earliest.series_id = i.series_id
                LEFT JOIN user_data u ON u.item_id = i.id
                WHERE i.kind = 'EPISODE'
                  AND COALESCE(u.played, 0) = 0 AND COALESCE(u.position_ms, 0) = 0
                  AND ${runOrderSlot("i")} = earliest.slot
                  AND i.series_id IN (
                        SELECT DISTINCT e2.series_id FROM items e2
                        JOIN user_data ue2 ON ue2.item_id = e2.id
                        WHERE e2.kind = 'EPISODE' AND (ue2.played = 1 OR ue2.position_ms > 0)
                  )
                ORDER BY i.sort_name LIMIT ?
            )
            ORDER BY i.sort_name
            """.trimIndent()
        ).apply { setInt(1, limit) }.useQuery { it.map(::readItem) }
    }

    fun latest(libraryId: String?, limit: Int): List<MediaItemDto> = db.read { connection ->
        val filter = if (libraryId == null) "" else "AND i.library_id = ?"
        connection.statement(
            "$SELECT_ITEM WHERE i.kind IN ('MOVIE','SERIES') AND i.merged_into IS NULL $filter " +
                "ORDER BY i.date_created DESC LIMIT ?"
        ).apply {
            if (libraryId == null) setInt(1, limit) else { setString(1, libraryId); setInt(2, limit) }
        }.useQuery { it.map(::readItem) }
    }

    /**
     * Top-level entries nobody has started. A film qualifies on its own row; a
     * series qualifies only when no episode under it has been watched or left
     * part-way, which is the same pair of columns [resume] reads one row at a
     * time. `played_episode_count` cannot answer this — it is a select alias,
     * and SQLite cannot filter on one.
     */
    fun unwatched(libraryId: String?, limit: Int): List<MediaItemDto> = db.read { connection ->
        val filter = if (libraryId == null) "" else "AND i.library_id = ?"
        connection.statement(
            """
            $SELECT_ITEM
            WHERE i.kind IN ('MOVIE','SERIES') $filter
              AND COALESCE(u.played, 0) = 0
              AND COALESCE(u.position_ms, 0) = 0
              AND NOT EXISTS (
                    SELECT 1 FROM items e JOIN user_data ue ON ue.item_id = e.id
                    WHERE e.kind = 'EPISODE' AND (e.series_id = i.id OR e.parent_id = i.id)
                      AND (ue.played = 1 OR ue.position_ms > 0)
              )
            ORDER BY i.sort_name LIMIT ?
            """.trimIndent()
        ).apply {
            if (libraryId == null) setInt(1, limit) else { setString(1, libraryId); setInt(2, limit) }
        }.useQuery { it.map(::readItem) }
    }

    fun itemsNeedingScrape(libraryId: String, force: Boolean): List<MediaItemDto> = db.read { connection ->
        val condition = if (force) "" else "AND i.scraped_at IS NULL"
        connection.statement(
            "$SELECT_ITEM WHERE i.library_id = ? AND i.kind IN ('MOVIE','SERIES') AND i.merged_into IS NULL " +
                "$condition ORDER BY i.sort_name"
        ).apply { setString(1, libraryId) }.useQuery { it.map(::readItem) }
    }

    fun itemsNeedingProbe(libraryId: String, limit: Int): List<MediaItemDto> = db.read { connection ->
        connection.statement(
            "$SELECT_ITEM WHERE i.library_id = ? AND i.kind IN ('MOVIE','EPISODE') AND i.probed_at IS NULL LIMIT ?"
        ).apply { setString(1, libraryId); setInt(2, limit) }.useQuery { it.map(::readItem) }
    }

    /** One page of the whole catalogue, ordered so paging is stable. Used by the backup export. */
    fun itemRecordsPage(limit: Int, offset: Int): List<ItemRecord> = db.read { connection ->
        connection.statement("$SELECT_ITEM ORDER BY i.id LIMIT ? OFFSET ?")
            .apply { setInt(1, limit); setInt(2, offset) }
            .useQuery { it.map(::readItemRecord) }
    }

    fun totalItemCount(): Int = db.read { connection ->
        connection.statement("SELECT COUNT(*) FROM items").useQuery { if (it.next()) it.getIntAt(1) else 0 }
    }

    // ------------------------------------------------------------ user data

    fun userData(itemId: String): UserDataDto = db.read { connection ->
        connection.statement("SELECT * FROM user_data WHERE item_id = ?")
            .apply { setString(1, itemId) }
            .useQuery { if (it.next()) readUserData(it, null) else UserDataDto() }
    }

    /** Every watch-state row, for the backup export and for sync. */
    fun allUserData(): List<UserDataRow> = db.read { connection ->
        connection.statement("SELECT * FROM user_data ORDER BY item_id")
            .useQuery { rs ->
                rs.map {
                    UserDataRow(
                        itemId = it.requireString("item_id"),
                        data = readUserData(it, null),
                        updatedAt = it.getLongOrNull("updated_at") ?: 0L
                    )
                }
            }
    }

    // ------------------------------------------------------------ scrape pins

    /**
     * Every entry the user pinned by hand.
     *
     * Kept in its own table rather than read back off `items` so a pin can
     * arrive before the item does: a device that has not scanned yet still has
     * somewhere to put one, and the scan then finds it waiting.
     */
    fun allPins(): List<PinRow> = db.read { connection ->
        connection.statement("SELECT * FROM scrape_pins ORDER BY item_id").useQuery { rs ->
            rs.map {
                PinRow(
                    itemId = it.requireString("item_id"),
                    provider = it.requireString("provider"),
                    providerId = it.requireString("provider_id"),
                    updatedAt = it.getLongOrNull("updated_at") ?: 0L
                )
            }
        }
    }

    fun pin(itemId: String): PinRow? = db.read { connection ->
        connection.statement("SELECT * FROM scrape_pins WHERE item_id = ?")
            .apply { setString(1, itemId) }
            .useQuery {
                if (!it.next()) null
                else PinRow(
                    itemId = it.requireString("item_id"),
                    provider = it.requireString("provider"),
                    providerId = it.requireString("provider_id"),
                    updatedAt = it.getLongOrNull("updated_at") ?: 0L
                )
            }
    }

    fun savePin(
        itemId: String,
        provider: String,
        providerId: String,
        updatedAt: Long = System.currentTimeMillis()
    ) = db.transaction { connection ->
        connection.statement(
            """
            INSERT INTO scrape_pins(item_id, provider, provider_id, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(item_id) DO UPDATE SET
                provider = excluded.provider,
                provider_id = excluded.provider_id,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use {
            it.setString(1, itemId)
            it.setString(2, provider)
            it.setString(3, providerId)
            it.setLong(4, updatedAt)
            it.executeUpdate()
        }
    }

    /**
     * Cheap summary of the watch state, used to notice that something changed
     * without diffing every row: the newest timestamp plus the row count.
     */
    fun userDataFingerprint(): Pair<Long, Int> = db.read { connection ->
        connection.statement("SELECT COALESCE(MAX(updated_at), 0), COUNT(*) FROM user_data")
            .useQuery { if (it.next()) it.getLongAt(1) to it.getIntAt(2) else 0L to 0 }
    }

    /** When a watch-state row last changed, or null when there is no such row. */
    fun userDataUpdatedAt(itemId: String): Long? = db.read { connection ->
        connection.statement("SELECT updated_at FROM user_data WHERE item_id = ?")
            .apply { setString(1, itemId) }
            .useQuery { if (it.next()) it.getLongAt(1) else null }
    }

    /**
     * Writes a watch-state row exactly as given. Unlike [saveProgress] it does
     * not re-derive `played` or bump the play count: restoring a backup must
     * reproduce what was there, not re-interpret it.
     */
    fun restoreUserData(
        itemId: String,
        data: UserDataDto,
        updatedAt: Long = System.currentTimeMillis()
    ) = db.transaction { connection ->
        connection.statement(
            """
            INSERT INTO user_data(item_id, position_ms, played, play_count, favorite,
                                  last_played_at, audio_stream_index, subtitle_stream_index, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(item_id) DO UPDATE SET
                position_ms = excluded.position_ms, played = excluded.played,
                play_count = excluded.play_count, favorite = excluded.favorite,
                last_played_at = excluded.last_played_at,
                audio_stream_index = excluded.audio_stream_index,
                subtitle_stream_index = excluded.subtitle_stream_index,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, itemId)
            statement.setLong(2, data.positionMs)
            statement.setInt(3, if (data.played) 1 else 0)
            statement.setInt(4, data.playCount)
            statement.setInt(5, if (data.favorite) 1 else 0)
            data.lastPlayedAt?.let { statement.setLong(6, it) } ?: statement.setNull(6)
            data.audioStreamIndex?.let { statement.setInt(7, it) } ?: statement.setNull(7)
            data.subtitleStreamIndex?.let { statement.setInt(8, it) } ?: statement.setNull(8)
            statement.setLong(9, updatedAt)
            statement.executeUpdate()
        }
    }

    fun saveProgress(
        itemId: String,
        positionMs: Long,
        runtimeMs: Long?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        markPlayed: Boolean? = null
    ): UserDataDto {
        val existing = userData(itemId)
        val percentage = if (runtimeMs != null && runtimeMs > 0) {
            (positionMs.toDouble() / runtimeMs.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        // Emby's rule: past 90% counts as watched, and the resume point is cleared.
        val played = markPlayed ?: (percentage >= 0.9)
        val storedPosition = if (played) 0L else positionMs
        val playCount = if (played && !existing.played) existing.playCount + 1 else existing.playCount

        db.transaction { connection ->
            connection.statement(
                """
                INSERT INTO user_data(item_id, position_ms, played, play_count, favorite,
                                      last_played_at, audio_stream_index, subtitle_stream_index, updated_at)
                VALUES (?, ?, ?, ?, COALESCE((SELECT favorite FROM user_data WHERE item_id = ?), 0), ?, ?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET
                    position_ms = excluded.position_ms,
                    played = excluded.played,
                    play_count = excluded.play_count,
                    last_played_at = excluded.last_played_at,
                    audio_stream_index = COALESCE(excluded.audio_stream_index, user_data.audio_stream_index),
                    subtitle_stream_index = COALESCE(excluded.subtitle_stream_index, user_data.subtitle_stream_index),
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                val now = System.currentTimeMillis()
                statement.setString(1, itemId)
                statement.setLong(2, storedPosition)
                statement.setInt(3, if (played) 1 else 0)
                statement.setInt(4, playCount)
                statement.setString(5, itemId)
                statement.setLong(6, now)
                audioStreamIndex?.let { statement.setInt(7, it) } ?: statement.setNull(7)
                subtitleStreamIndex?.let { statement.setInt(8, it) } ?: statement.setNull(8)
                statement.setLong(9, now)
                statement.executeUpdate()
            }
        }
        return userData(itemId)
    }

    fun setFavorite(itemId: String, favorite: Boolean): UserDataDto {
        db.transaction { connection ->
            connection.statement(
                """
                INSERT INTO user_data(item_id, favorite, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET favorite = excluded.favorite, updated_at = excluded.updated_at
                """.trimIndent()
            ).use {
                it.setString(1, itemId)
                it.setInt(2, if (favorite) 1 else 0)
                it.setLong(3, System.currentTimeMillis())
                it.executeUpdate()
            }
        }
        return userData(itemId)
    }

    /**
     * Every episode under an item, whether it hangs off a series or a season.
     * Used to mark a whole run watched in one go.
     */
    /**
     * The episode to play when someone presses play on a series or a season:
     * whatever was left part-watched, else the first unwatched one, else the
     * first. Mirrors what "continue watching" means everywhere else.
     *
     * The specials rank comes before the watch state, not after it, so an SP
     * that got five seconds of play cannot outrank the season the viewer is
     * actually working through. Pressing play on a series is a request for the
     * main run; the extras are one tap away on the season list.
     */
    fun nextEpisodeUnder(itemId: String): MediaItemDto? = db.read { connection ->
        connection.statement(
            """
            $SELECT_ITEM
            WHERE i.kind = 'EPISODE' AND (i.series_id = ? OR i.parent_id = ?)
            ORDER BY
                ${specialsLast("i")},
                CASE WHEN COALESCE(u.position_ms, 0) > 0 AND COALESCE(u.played, 0) = 0 THEN 0
                     WHEN COALESCE(u.played, 0) = 0 THEN 1
                     ELSE 2 END,
                COALESCE(i.parent_index_number, 0),
                COALESCE(i.index_number, 99999)
            LIMIT 1
            """.trimIndent()
        ).apply { setString(1, itemId); setString(2, itemId) }
            .useQuery { if (it.next()) readItem(it) else null }
    }

    fun episodeIdsUnder(itemId: String): List<String> = db.read { connection ->
        connection.statement(
            "SELECT id FROM items WHERE kind = 'EPISODE' AND (series_id = ? OR parent_id = ?)"
        ).apply { setString(1, itemId); setString(2, itemId) }
            .useQuery { rs -> rs.map { it.requireString("id") } }
    }

    fun setPlayed(itemId: String, played: Boolean): UserDataDto {
        db.transaction { connection ->
            connection.statement(
                """
                INSERT INTO user_data(item_id, played, position_ms, play_count, last_played_at, updated_at)
                VALUES (?, ?, 0, COALESCE((SELECT play_count FROM user_data WHERE item_id = ?), 0) + ?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET
                    played = excluded.played, position_ms = 0,
                    play_count = excluded.play_count,
                    last_played_at = excluded.last_played_at, updated_at = excluded.updated_at
                """.trimIndent()
            ).use {
                val now = System.currentTimeMillis()
                it.setString(1, itemId)
                it.setInt(2, if (played) 1 else 0)
                it.setString(3, itemId)
                it.setInt(4, if (played) 1 else 0)
                it.setLong(5, now)
                it.setLong(6, now)
                it.executeUpdate()
            }
        }
        return userData(itemId)
    }

    // ------------------------------------------------------------ scrape cache

    fun cacheGet(key: String, maxAgeMs: Long): String? = db.read { connection ->
        connection.statement("SELECT payload, fetched_at FROM scrape_cache WHERE key = ?")
            .apply { setString(1, key) }
            .useQuery {
                if (!it.next()) return@useQuery null
                val age = System.currentTimeMillis() - it.getLong("fetched_at")
                if (age > maxAgeMs) null else it.getString("payload")
            }
    }

    fun cachePut(key: String, payload: String) = db.transaction { connection ->
        connection.statement(
            "INSERT INTO scrape_cache(key, payload, fetched_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, fetched_at = excluded.fetched_at"
        ).use {
            it.setString(1, key)
            it.setString(2, payload)
            it.setLong(3, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    // ------------------------------------------------------------ mapping

    private fun SqlStatement.bind(values: List<Any?>) {
        values.forEachIndexed { index, value ->
            when (value) {
                is String -> setString(index + 1, value)
                is Int -> setInt(index + 1, value)
                is Long -> setLong(index + 1, value)
                null -> setNull(index + 1)
                else -> setString(index + 1, value.toString())
            }
        }
    }

    private fun bindItem(statement: SqlStatement, record: ItemRecord) {
        val dto = record.dto
        var i = 0
        statement.setString(++i, dto.id)
        statement.setString(++i, dto.libraryId)
        statement.setString(++i, dto.kind.name)
        statement.setString(++i, dto.parentId)
        statement.setString(++i, dto.seriesId)
        statement.setString(++i, dto.name)
        statement.setString(++i, dto.originalName)
        statement.setString(++i, dto.sortName)
        statement.setString(++i, dto.overview)
        dto.year?.let { statement.setInt(++i, it) } ?: statement.setNull(++i)
        statement.setString(++i, dto.premiereDate)
        dto.runtimeMs?.let { statement.setLong(++i, it) } ?: statement.setNull(++i)
        dto.communityRating?.let { statement.setDouble(++i, it) } ?: statement.setNull(++i)
        statement.setString(++i, dto.officialRating)
        statement.setString(++i, json.encodeToString(stringListSerializer, dto.genres))
        statement.setString(++i, json.encodeToString(stringListSerializer, dto.studios))
        statement.setString(++i, json.encodeToString(personListSerializer, dto.people))
        dto.indexNumber?.let { statement.setInt(++i, it) } ?: statement.setNull(++i)
        dto.parentIndexNumber?.let { statement.setInt(++i, it) } ?: statement.setNull(++i)
        statement.setString(++i, dto.posterUrl)
        statement.setString(++i, dto.backdropUrl)
        statement.setString(++i, dto.logoUrl)
        statement.setString(++i, json.encodeToString(stringMapSerializer, dto.providerIds))
        statement.setString(++i, dto.lockedProvider?.name)
        statement.setString(++i, dto.scrapeStatus.name)
        statement.setString(++i, dto.mergedInto)
        statement.setString(++i, dto.path)
        dto.sizeBytes?.let { statement.setLong(++i, it) } ?: statement.setNull(++i)
        statement.setString(++i, json.encodeToString(streamListSerializer, dto.mediaStreams))
        statement.setLong(++i, record.dateCreated)
        statement.setLong(++i, record.dateModified)
        statement.setString(++i, record.etag)
        record.scrapedAt?.let { statement.setLong(++i, it) } ?: statement.setNull(++i)
        record.probedAt?.let { statement.setLong(++i, it) } ?: statement.setNull(++i)
    }

    private fun readItem(rs: SqlCursor): MediaItemDto = MediaItemDto(
        id = rs.requireString("id"),
        libraryId = rs.requireString("library_id"),
        kind = runCatching { ItemKind.valueOf(rs.requireString("kind")) }.getOrDefault(ItemKind.MOVIE),
        parentId = rs.getString("parent_id"),
        seriesId = rs.getString("series_id"),
        seriesName = runCatching { rs.getString("series_name") }.getOrNull(),
        name = rs.requireString("name"),
        originalName = rs.getString("original_name"),
        sortName = rs.requireString("sort_name") ?: "",
        overview = rs.getString("overview"),
        year = rs.getIntOrNull("year"),
        premiereDate = rs.getString("premiere_date"),
        runtimeMs = rs.getLongOrNull("runtime_ms"),
        communityRating = rs.getDoubleOrNull("community_rating"),
        officialRating = rs.getString("official_rating"),
        genres = decodeList(rs.requireString("genres")),
        studios = decodeList(rs.requireString("studios")),
        people = runCatching { json.decodeFromString(personListSerializer, rs.requireString("people")) }
            .getOrDefault(emptyList()),
        indexNumber = rs.getIntOrNull("index_number"),
        parentIndexNumber = rs.getIntOrNull("parent_index_number"),
        // Episode stills only exist on providers that publish them; bangumi.tv
        // never does. Falling back to the series artwork beats a grid of empty
        // placeholders, and matches what Emby shows for a still-less episode.
        posterUrl = rs.getString("poster_url")
            ?: runCatching { rs.getString("series_poster") }.getOrNull(),
        backdropUrl = rs.getString("backdrop_url"),
        logoUrl = rs.getString("logo_url"),
        providerIds = runCatching { json.decodeFromString(stringMapSerializer, rs.requireString("provider_ids")) }
            .getOrDefault(emptyMap()),
        lockedProvider = rs.getString("locked_provider")
            ?.let { name -> MetadataProvider.entries.firstOrNull { it.name == name } },
        scrapeStatus = rs.getString("scrape_status")
            ?.let { name -> ScrapeStatus.entries.firstOrNull { it.name == name } }
            ?: ScrapeStatus.NONE,
        scrapedAt = rs.getLongOrNull("scraped_at"),
        mergedInto = rs.getString("merged_into"),
        childCount = rs.getIntOrNull("child_count"),
        episodeCount = rs.getIntOrNull("episode_count"),
        playedEpisodeCount = rs.getIntOrNull("played_episode_count"),
        path = rs.getString("path"),
        sizeBytes = rs.getLongOrNull("size_bytes"),
        mediaStreams = runCatching { json.decodeFromString(streamListSerializer, rs.requireString("media_streams")) }
            .getOrDefault(emptyList()),
        userData = readUserData(rs, rs.getLongOrNull("runtime_ms"))
    )

    private fun readItemRecord(rs: SqlCursor) = ItemRecord(
        dto = readItem(rs),
        dateCreated = rs.getLong("date_created"),
        dateModified = rs.getLong("date_modified"),
        etag = rs.getString("etag"),
        scrapedAt = rs.getLongOrNull("scraped_at"),
        probedAt = rs.getLongOrNull("probed_at")
    )

    private fun readUserData(rs: SqlCursor, runtimeMs: Long?): UserDataDto {
        val position = rs.getLongOrNull("position_ms") ?: 0L
        return UserDataDto(
            positionMs = position,
            playedPercentage = if (runtimeMs != null && runtimeMs > 0) {
                (position.toDouble() / runtimeMs.toDouble()).coerceIn(0.0, 1.0)
            } else 0.0,
            played = (rs.getIntOrNull("played") ?: 0) == 1,
            playCount = rs.getIntOrNull("play_count") ?: 0,
            favorite = (rs.getIntOrNull("favorite") ?: 0) == 1,
            lastPlayedAt = rs.getLongOrNull("last_played_at"),
            audioStreamIndex = rs.getIntOrNull("audio_stream_index"),
            subtitleStreamIndex = rs.getIntOrNull("subtitle_stream_index")
        )
    }

    private fun decodeList(raw: String?): List<String> =
        runCatching { json.decodeFromString(stringListSerializer, raw ?: "[]") }.getOrDefault(emptyList())

    private companion object {
        const val SELECT_ITEM = """
            SELECT i.*,
                   u.position_ms, u.played, u.play_count, u.favorite, u.last_played_at,
                   u.audio_stream_index, u.subtitle_stream_index,
                   (SELECT COUNT(*) FROM items c WHERE c.parent_id = i.id) AS child_count,
                   -- Episodes hang off a series by series_id and off a season by
                   -- parent_id, so one pair of subqueries answers for both.
                   (SELECT COUNT(*) FROM items e
                     WHERE e.kind = 'EPISODE' AND (e.series_id = i.id OR e.parent_id = i.id)) AS episode_count,
                   (SELECT COUNT(*) FROM items e
                     LEFT JOIN user_data ue ON ue.item_id = e.id
                     WHERE e.kind = 'EPISODE' AND (e.series_id = i.id OR e.parent_id = i.id)
                       AND COALESCE(ue.played, 0) = 1) AS played_episode_count,
                   (SELECT s.name FROM items s WHERE s.id = i.series_id) AS series_name,
                   (SELECT s.poster_url FROM items s WHERE s.id = i.series_id) AS series_poster
            FROM items i
            LEFT JOIN user_data u ON u.item_id = i.id
        """

        const val ITEM_COLUMNS = """
                id, library_id, kind, parent_id, series_id, name, original_name, sort_name, overview,
                year, premiere_date, runtime_ms, community_rating, official_rating, genres, studios, people,
                index_number, parent_index_number, poster_url, backdrop_url, logo_url, provider_ids,
                locked_provider, scrape_status, merged_into, path, size_bytes, media_streams,
                date_created, date_modified, etag, scraped_at, probed_at
        """

        const val ITEM_PLACEHOLDERS =
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"

        const val UPSERT_ITEM = """
            INSERT INTO items($ITEM_COLUMNS) VALUES ($ITEM_PLACEHOLDERS)
            ON CONFLICT(id) DO UPDATE SET
                library_id = excluded.library_id, kind = excluded.kind, parent_id = excluded.parent_id,
                series_id = excluded.series_id, name = excluded.name, original_name = excluded.original_name,
                sort_name = excluded.sort_name, overview = excluded.overview, year = excluded.year,
                premiere_date = excluded.premiere_date, runtime_ms = excluded.runtime_ms,
                community_rating = excluded.community_rating, official_rating = excluded.official_rating,
                genres = excluded.genres, studios = excluded.studios, people = excluded.people,
                index_number = excluded.index_number, parent_index_number = excluded.parent_index_number,
                poster_url = excluded.poster_url, backdrop_url = excluded.backdrop_url, logo_url = excluded.logo_url,
                provider_ids = excluded.provider_ids, locked_provider = excluded.locked_provider,
                scrape_status = excluded.scrape_status, merged_into = excluded.merged_into,
                path = excluded.path, size_bytes = excluded.size_bytes,
                media_streams = excluded.media_streams, date_modified = excluded.date_modified,
                etag = excluded.etag, scraped_at = excluded.scraped_at, probed_at = excluded.probed_at
        """

        /**
         * The scanner only knows what the file names say, so on an item that has
         * already been scraped it may update file-derived columns only. Everything
         * the scraper wrote stays put, including `scraped_at` and `locked_provider`,
         * which are deliberately absent from the SET list.
         */
        const val UPSERT_SCANNED_ITEM = """
            INSERT INTO items($ITEM_COLUMNS) VALUES ($ITEM_PLACEHOLDERS)
            ON CONFLICT(id) DO UPDATE SET
                library_id = excluded.library_id, kind = excluded.kind, parent_id = excluded.parent_id,
                series_id = excluded.series_id,
                name = CASE WHEN items.scraped_at IS NULL THEN excluded.name ELSE items.name END,
                original_name = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.original_name ELSE items.original_name END,
                sort_name = CASE WHEN items.scraped_at IS NULL THEN excluded.sort_name ELSE items.sort_name END,
                overview = CASE WHEN items.scraped_at IS NULL THEN excluded.overview ELSE items.overview END,
                year = CASE WHEN items.scraped_at IS NULL THEN excluded.year ELSE items.year END,
                premiere_date = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.premiere_date ELSE items.premiere_date END,
                community_rating = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.community_rating ELSE items.community_rating END,
                official_rating = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.official_rating ELSE items.official_rating END,
                genres = CASE WHEN items.scraped_at IS NULL THEN excluded.genres ELSE items.genres END,
                studios = CASE WHEN items.scraped_at IS NULL THEN excluded.studios ELSE items.studios END,
                people = CASE WHEN items.scraped_at IS NULL THEN excluded.people ELSE items.people END,
                poster_url = CASE WHEN items.scraped_at IS NULL THEN excluded.poster_url ELSE items.poster_url END,
                backdrop_url = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.backdrop_url ELSE items.backdrop_url END,
                logo_url = CASE WHEN items.scraped_at IS NULL THEN excluded.logo_url ELSE items.logo_url END,
                provider_ids = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.provider_ids ELSE items.provider_ids END,
                scrape_status = CASE WHEN items.scraped_at IS NULL
                    THEN excluded.scrape_status ELSE items.scrape_status END,
                index_number = excluded.index_number, parent_index_number = excluded.parent_index_number,
                path = excluded.path, size_bytes = excluded.size_bytes,
                media_streams = excluded.media_streams, date_modified = excluded.date_modified,
                etag = excluded.etag, probed_at = excluded.probed_at
        """
    }
}
