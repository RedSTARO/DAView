package com.daview.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.daview.app.platform.SettingsStore
import com.daview.app.platform.createCoreContext
import com.daview.app.platform.createSettingsStore
import com.daview.app.platform.onScanStarted
import com.daview.server.ServerContext
import com.daview.server.api.AssetLinks
import com.daview.server.api.MediaFacade
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState
import com.daview.shared.model.ScanMode
import com.daview.shared.model.ScanProgressDto
import com.daview.shared.model.ServerInfoDto
import com.daview.shared.model.ServerSettingsDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Library(val libraryId: String) : Screen
    data class Detail(val itemId: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Player(val itemId: String) : Screen
}

data class HomeData(
    val resume: List<MediaItemDto> = emptyList(),
    val nextUp: List<MediaItemDto> = emptyList(),
    val latest: List<MediaItemDto> = emptyList(),
    val favourites: List<MediaItemDto> = emptyList(),
    /** Unwatched entries keyed by library id. Libraries with none are absent. */
    val unwatched: Map<String, List<MediaItemDto>> = emptyMap()
)

/**
 * Single mutable holder for the whole client. The app is small enough that a
 * per-screen ViewModel layer would add indirection without buying anything, so
 * screens read this directly and call back into it.
 *
 * The library it talks to is in this process. There is no connection to make,
 * nothing to authenticate against and no address to get wrong — the app opens
 * on the home screen.
 */
class AppState(private val scope: CoroutineScope) {

    private val settings: SettingsStore = createSettingsStore()

    /** Everything that only exists once the library has been opened. */
    private class Opened(val core: ServerContext) {
        val media = core.media
        val links = LocalAssetLinks(core)
    }

    private var opened by mutableStateOf<Opened?>(null)

    /**
     * Whether the library is open yet. Screens are composed only once it is,
     * which is what lets them reach [library] without checking.
     */
    val ready: Boolean get() = opened != null

    /** Set instead of [ready] when the library could not be opened at all. */
    var startupError by mutableStateOf<String?>(null)
        private set

    val core: ServerContext get() = requireOpen().core
    val library: MediaFacade get() = requireOpen().media
    val links: AssetLinks get() = requireOpen().links

    private fun requireOpen(): Opened = opened
        ?: error("the library is not open yet; screens compose only once ready is true")

    var serverInfo by mutableStateOf<ServerInfoDto?>(null)
    var serverSettings by mutableStateOf<ServerSettingsDto?>(null)

    val backStack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Home)
    val current: Screen get() = backStack.last()

    var libraries by mutableStateOf<List<LibraryDto>>(emptyList())
    var home by mutableStateOf(HomeData())
    var scanStatus by mutableStateOf<List<ScanProgressDto>>(emptyList())
    var darkTheme by mutableStateOf(settings.getString(KEY_THEME) != "light")

    var libraryItems by mutableStateOf<List<MediaItemDto>>(emptyList())

    /**
     * Which library [libraryItems] holds, so a screen can tell "still loading"
     * from "loaded and empty" without a second flag — and, more to the point,
     * so moving between libraries does not briefly render the previous one's
     * entries under the new one's name.
     */
    var libraryItemsOf by mutableStateOf<String?>(null)
    var libraryLoading by mutableStateOf(false)

    /** How many entries the library holds, as opposed to how many are loaded. */
    var libraryTotal by mutableStateOf(0)
        private set

    /** True while another page is on its way in behind the ones on screen. */
    var libraryLoadingMore by mutableStateOf(false)
        private set

    var librarySort by mutableStateOf(settings.getString(KEY_SORT) ?: "sortName")
        private set
    var librarySortDescending by mutableStateOf(settings.getString(KEY_SORT_DESC) == "1")
        private set

    /** Narrowing applied to the library page. Null in both means everything. */
    var libraryOnlyUnwatched by mutableStateOf(false)
        private set
    var libraryOnlyFavourite by mutableStateOf(false)
        private set

    /** Search within the library page, as opposed to the global search screen. */
    var librarySearch by mutableStateOf("")
        private set

    var detailItem by mutableStateOf<MediaItemDto?>(null)
    var detailChildren by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailEpisodes by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailSeasonId by mutableStateOf<String?>(null)
    var detailLoading by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<MediaItemDto>>(emptyList())

    /** True between a query being asked for and its answer arriving. */
    var searchLoading by mutableStateOf(false)
        private set

    /** The search in flight, so a newer one can cancel it. */
    private var searchJob: Job? = null

    var toast by mutableStateOf<String?>(null)
        private set

    /**
     * Bumped with every message. The snackbar host is driven off this rather
     * than off [toast], so saying the same thing twice in a row — two failed
     * scans, two "已收藏" — shows twice instead of silently once.
     */
    var toastSeq by mutableStateOf(0)
        private set

    /** Puts one line in front of the user. */
    fun notify(message: String) {
        toast = message
        toastSeq++
    }

    // ------------------------------------------------------------ navigation

    fun navigate(screen: Screen) {
        backStack.add(screen)
        onEnter(screen)
    }

    fun replaceAll(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
        onEnter(screen)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        onEnter(current)
        return true
    }

    private fun onEnter(screen: Screen) {
        when (screen) {
            is Screen.Home -> refreshHome()
            is Screen.Library -> loadLibrary(screen.libraryId)
            is Screen.Detail -> loadDetail(screen.itemId)
            is Screen.Settings -> {
                refreshLibraries()
                loadServerSettings()
            }

            else -> Unit
        }
    }

    /**
     * Opens the library, then reads what it already knows.
     *
     * Opening costs about a third of a second on a warm machine — the SQLite
     * driver unpacks its native library, the migrations run, the planner's
     * statistics are refreshed — and it used to happen while the window was
     * being composed, so nothing was on screen until it finished. It runs off
     * the UI thread now and the window is up while it does.
     */
    fun open() {
        if (opened != null) return
        scope.launch {
            val context = runCatching { withContext(Dispatchers.IO) { createCoreContext() } }
                .getOrElse {
                    startupError = it.message ?: "无法打开媒体库"
                    return@launch
                }
            opened = Opened(context)
            start()
        }
    }

    /** Reads what the library already knows, before anything has been asked of it. */
    fun start() = run {
        serverInfo = library.info()
        libraries = library.libraries()
        refreshHome()
    }

    fun setTheme(dark: Boolean) {
        darkTheme = dark
        settings.putString(KEY_THEME, if (dark) "dark" else "light")
    }

    // ------------------------------------------------------------ data

    /**
     * Every call goes through here so a failure lands in the snackbar instead
     * of taking the coroutine down. The facade reports what went wrong in its
     * own terms, which is already the message worth showing.
     */
    private inline fun run(crossinline block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: MediaFacade.FacadeException) {
                notify(listOfNotNull(e.message, e.detail).joinToString("："))
            } catch (e: Throwable) {
                notify(e.message ?: "操作失败")
            }
        }
    }

    fun refreshLibraries() = run { libraries = library.libraries() }

    fun loadServerSettings() = run { serverSettings = library.settings() }

    /**
     * The rows are independent questions, so they are asked at once rather than
     * one after another: the library serves reads from a pool, and asking in
     * sequence made the page take the sum of them instead of the longest.
     */
    fun refreshHome() = run {
        val libs = library.libraries()
        libraries = libs
        coroutineScope {
            val resume = async { library.resume(20, links) }
            val nextUp = async { library.nextUp(20, links) }
            val latest = async { library.latest(null, 24, links) }
            // Favourites could be set from two places and read from none, so
            // marking one was a one-way trip.
            val favourites = async {
                library.items(links, favorite = true, sort = "sortName", limit = 24).items
            }
            val resumeRows = resume.await()
            // A series with a part-watched episode is already on the resume
            // shelf; offering its successor on the next shelf put the same show
            // in two rows at once, one episode apart, under the same artwork.
            // The repository keeps answering "the next unwatched episode of a
            // started series" — which shelf shows it is this screen's call.
            val onResume = resumeRows.mapNotNullTo(HashSet()) { it.seriesId ?: it.id }
            home = HomeData(
                resume = resumeRows,
                nextUp = nextUp.await().filterNot { (it.seriesId ?: it.id) in onResume },
                latest = latest.await(),
                favourites = favourites.await()
            )
        }
        // One row per library, and they only fill in the bottom of the page, so
        // they are gathered after the rest of it is already on screen.
        val rows = coroutineScope {
            libs.map { entry -> async { entry.id to library.unwatched(entry.id, 24, links) } }.awaitAll()
        }
        home = home.copy(unwatched = rows.toMap().filterValues { it.isNotEmpty() })
    }

    fun loadLibrary(libraryId: String) = run {
        libraryLoading = true
        if (libraryItemsOf != libraryId) libraryItemsOf = null
        try {
            val page = libraryPage(libraryId, offset = 0)
            libraryItems = page.items
            libraryTotal = page.total
            libraryItemsOf = libraryId
        } finally {
            libraryLoading = false
        }
    }

    /**
     * Adds the next page to what is already on screen.
     *
     * The page used to ask for 500 entries once and stop there, so a library
     * with more than that simply did not contain the rest as far as the
     * interface was concerned — and the header said 500 while the home screen
     * said the real number.
     */
    fun loadMoreLibrary(libraryId: String) {
        if (libraryLoadingMore || libraryLoading) return
        if (libraryItemsOf != libraryId) return
        if (libraryItems.size >= libraryTotal) return
        libraryLoadingMore = true
        run {
            try {
                val page = libraryPage(libraryId, offset = libraryItems.size)
                // Guard against the page having moved under us — a re-sort or a
                // filter change while this was in flight.
                if (libraryItemsOf == libraryId) {
                    libraryItems = libraryItems + page.items
                    libraryTotal = page.total
                }
            } finally {
                libraryLoadingMore = false
            }
        }
    }

    private suspend fun libraryPage(libraryId: String, offset: Int) = library.items(
        links,
        libraryId = libraryId,
        // Series and stand-alone films together, ordered as one list. Asking for
        // a kind meant a series library ran two queries with two limits and the
        // films inside it were pinned to the end.
        topLevelOnly = true,
        search = librarySearch.takeIf { it.isNotBlank() },
        favorite = true.takeIf { libraryOnlyFavourite },
        played = false.takeIf { libraryOnlyUnwatched },
        sort = librarySort,
        descending = librarySortDescending,
        limit = PAGE_SIZE,
        offset = offset
    )

    fun setSort(sort: String, libraryId: String) {
        // Pressing the field that is already chosen flips the direction, which
        // is how every other catalogue behaves.
        if (sort == librarySort) librarySortDescending = !librarySortDescending
        else {
            librarySort = sort
            librarySortDescending = sort in DESCENDING_BY_DEFAULT
        }
        settings.putString(KEY_SORT, librarySort)
        settings.putString(KEY_SORT_DESC, if (librarySortDescending) "1" else "0")
        loadLibrary(libraryId)
    }

    fun setLibraryFilter(
        libraryId: String,
        onlyUnwatched: Boolean = libraryOnlyUnwatched,
        onlyFavourite: Boolean = libraryOnlyFavourite
    ) {
        libraryOnlyUnwatched = onlyUnwatched
        libraryOnlyFavourite = onlyFavourite
        loadLibrary(libraryId)
    }

    fun setLibrarySearch(libraryId: String, query: String) {
        librarySearch = query
        loadLibrary(libraryId)
    }

    fun loadDetail(itemId: String) = run {
        detailLoading = true
        try {
            val item = library.item(itemId, links)
            detailItem = item
            detailChildren = if (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON) {
                library.children(itemId, links)
            } else emptyList()
            // An episode has no children of its own. What its page wants is the
            // rest of the run it sits in, and that hangs off its season — the
            // same list a series page shows for the season it has selected.
            val seasonId = detailChildren.firstOrNull { it.kind == ItemKind.SEASON }?.id
                ?: item.parentId?.takeIf { item.kind == ItemKind.EPISODE }
            detailSeasonId = seasonId
            detailEpisodes = seasonId?.let { library.children(it, links) } ?: emptyList()
        } finally {
            detailLoading = false
        }
    }

    fun selectSeason(seasonId: String) = run {
        detailSeasonId = seasonId
        detailEpisodes = library.children(seasonId, links)
    }

    /**
     * Runs one search, and cancels whatever search was still in flight.
     *
     * Every keystroke used to launch a coroutine that nobody could stop, so the
     * answer to a shorter, slower query could land after the answer to what the
     * user had actually finished typing.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            searchLoading = false
            return
        }
        searchLoading = true
        searchJob = scope.launch {
            try {
                searchResults = library.items(links, search = query, limit = SEARCH_LIMIT).items
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaFacade.FacadeException) {
                notify(listOfNotNull(e.message, e.detail).joinToString("："))
            } catch (e: Throwable) {
                notify(e.message ?: "搜索失败")
            } finally {
                if (currentCoroutineContext().isActive) searchLoading = false
            }
        }
    }

    /**
     * Stops offering an item on the continue-watching shelf. The alternative
     * was claiming to have finished it, which threw the position away too.
     */
    fun hideFromResume(item: MediaItemDto) = run {
        library.setHiddenFromResume(item.id, true)
        refreshHome()
        notify("已从继续观看中移除")
    }

    fun toggleFavorite(item: MediaItemDto) = run {
        library.setFavorite(item.id, !item.userData.favorite)
        refreshAfterWatchChange(item.id)
        notify(if (item.userData.favorite) "已取消收藏" else "已收藏")
    }

    /**
     * Works for any kind. A series or season has no watched flag of its own, so
     * the decision comes from its episodes — and marking one flips all of them,
     * which is why the whole open detail is reloaded rather than one row.
     */
    fun togglePlayed(item: MediaItemDto) = run {
        val markPlayed = item.playedState != PlayedState.PLAYED
        library.setPlayed(item.id, markPlayed)
        refreshAfterWatchChange(item.id)
        notify(if (markPlayed) "已标记为已观看" else "已标记为未观看")
    }

    /**
     * Puts a changed watch state back in front of the user, wherever the item is
     * currently on screen. The grids and the search results only ever show that
     * one row, so it is swapped in place and the scroll position survives; the
     * open detail is reloaded whole, because marking a series watched flips every
     * episode under it; and the home screen is rebuilt, because its rows are
     * *selected* by watch state — an entry that just got watched has to leave
     * "继续观看", not merely redraw with a tick.
     */
    private suspend fun refreshAfterWatchChange(itemId: String) {
        val fresh = library.item(itemId, links)
        fun List<MediaItemDto>.withFresh() = map { if (it.id == itemId) fresh else it }
        libraryItems = libraryItems.withFresh()
        searchResults = searchResults.withFresh()
        detailChildren = detailChildren.withFresh()
        detailEpisodes = detailEpisodes.withFresh()

        detailItem?.id?.let { openId ->
            detailItem = library.item(openId, links)
            if (detailChildren.isNotEmpty()) detailChildren = library.children(openId, links)
            detailSeasonId?.let { detailEpisodes = library.children(it, links) }
        }
        if (current is Screen.Home) refreshHome()
    }

    fun startScan(libraryId: String, mode: ScanMode = ScanMode.FULL) = run {
        library.scan(libraryId, mode)
        // A scan is minutes of round trips against the share, and on a phone the
        // process is reclaimed the moment the user switches away unless
        // something says otherwise.
        onScanStarted()
        notify(
            when (mode) {
                ScanMode.MISSING -> "已开始刮削未刮削的条目"
                ScanMode.REFRESH -> "已开始重新刮削全部"
                ScanMode.FULL -> "已开始扫描"
            }
        )
        pollScanStatus()
    }

    /**
     * Follows a scan that is already running, whoever started it. Scans outlive
     * the screen that kicked them off, so this is called on start-up too.
     */
    fun pollScanStatus() {
        scope.launch {
            while (isActive) {
                scanStatus = library.scanStatus()
                if (scanStatus.none { it.running }) {
                    refreshLibraries()
                    // The shelves are built from what the scan just wrote. Only
                    // the library counts used to be refreshed, so a first scan
                    // finished into a home screen that still looked empty.
                    refreshHome()
                    return@launch
                }
                delay(2000)
            }
        }
    }

    fun saveServerSettings(updated: ServerSettingsDto, onDone: (Boolean) -> Unit = {}) = run {
        serverSettings = library.updateSettings(updated)
        notify("设置已保存")
        onDone(true)
    }

    fun createLibrary(entry: LibraryDto, onDone: () -> Unit = {}) = run {
        library.createLibrary(entry)
        refreshLibraries()
        notify("媒体库已创建")
        onDone()
    }

    fun cancelScan(libraryId: String) = run {
        library.cancelScan(libraryId)
        notify("正在停止扫描")
    }

    fun deleteLibrary(id: String) = run {
        library.deleteLibrary(id)
        refreshLibraries()
        notify("媒体库已删除")
    }

    private companion object {
        /** One page of search results. The facade caps a page at 500 anyway. */
        const val SEARCH_LIMIT = 60

        /** One page of a library. The facade caps a page at 500. */
        const val PAGE_SIZE = 200

        /** Sorts whose useful end is the high one. */
        val DESCENDING_BY_DEFAULT = setOf("year", "added", "rating", "played")

        const val KEY_THEME = "theme"
        const val KEY_SORT = "library.sort"
        const val KEY_SORT_DESC = "library.sortDescending"
    }
}
