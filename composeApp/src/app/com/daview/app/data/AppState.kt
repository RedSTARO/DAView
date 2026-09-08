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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    val core: ServerContext = createCoreContext()
    val library: MediaFacade = core.media
    val links: AssetLinks = LocalAssetLinks(core)

    var serverInfo by mutableStateOf<ServerInfoDto?>(null)
    var serverSettings by mutableStateOf<ServerSettingsDto?>(null)

    val backStack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Home)
    val current: Screen get() = backStack.last()

    var libraries by mutableStateOf<List<LibraryDto>>(emptyList())
    var home by mutableStateOf(HomeData())
    var scanStatus by mutableStateOf<List<ScanProgressDto>>(emptyList())
    var darkTheme by mutableStateOf(settings.getString(KEY_THEME) != "light")

    var libraryItems by mutableStateOf<List<MediaItemDto>>(emptyList())
    var libraryLoading by mutableStateOf(false)
    var librarySort by mutableStateOf("sortName")

    var detailItem by mutableStateOf<MediaItemDto?>(null)
    var detailChildren by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailEpisodes by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailSeasonId by mutableStateOf<String?>(null)
    var detailLoading by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<MediaItemDto>>(emptyList())

    var toast by mutableStateOf<String?>(null)

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
                toast = listOfNotNull(e.message, e.detail).joinToString("：")
            } catch (e: Throwable) {
                toast = e.message ?: "操作失败"
            }
        }
    }

    fun refreshLibraries() = run { libraries = library.libraries() }

    fun loadServerSettings() = run { serverSettings = library.settings() }

    fun refreshHome() = run {
        val libs = library.libraries()
        libraries = libs
        home = HomeData(
            resume = library.resume(20, links),
            nextUp = library.nextUp(20, links),
            latest = library.latest(null, 24, links)
        )
        // One row per library, and they only fill in the bottom of the page, so
        // they are gathered after the rest of it is already on screen.
        home = home.copy(
            unwatched = libs.associate { it.id to library.unwatched(it.id, 24, links) }
                .filterValues { it.isNotEmpty() }
        )
    }

    fun loadLibrary(libraryId: String) = run {
        libraryLoading = true
        try {
            val kind = when (libraries.firstOrNull { it.id == libraryId }?.kind?.isSeriesLike) {
                true -> ItemKind.SERIES
                else -> ItemKind.MOVIE
            }
            val page = library.items(links, libraryId = libraryId, kind = kind, sort = librarySort, limit = 500)
            // A series library can still contain stand-alone films (a spin-off
            // movie folder inside a show); include them so nothing disappears.
            val extra = if (kind == ItemKind.SERIES) {
                library.items(links, libraryId = libraryId, kind = ItemKind.MOVIE, sort = librarySort, limit = 200)
                    .items.filter { it.parentId == null }
            } else emptyList()
            libraryItems = page.items + extra
        } finally {
            libraryLoading = false
        }
    }

    fun setSort(sort: String, libraryId: String) {
        librarySort = sort
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

    fun search(query: String) = run {
        searchResults = if (query.isBlank()) emptyList()
        else library.items(links, search = query, limit = 60).items
    }

    fun toggleFavorite(item: MediaItemDto) = run {
        library.setFavorite(item.id, !item.userData.favorite)
        refreshAfterWatchChange(item.id)
        toast = if (item.userData.favorite) "已取消收藏" else "已收藏"
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
        toast = if (markPlayed) "已标记为已观看" else "已标记为未观看"
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
        toast = when (mode) {
            ScanMode.MISSING -> "已开始刮削未刮削的条目"
            ScanMode.REFRESH -> "已开始重新刮削全部"
            ScanMode.FULL -> "已开始扫描"
        }
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
                    return@launch
                }
                delay(2000)
            }
        }
    }

    fun saveServerSettings(updated: ServerSettingsDto, onDone: (Boolean) -> Unit = {}) = run {
        serverSettings = library.updateSettings(updated)
        toast = "设置已保存"
        onDone(true)
    }

    fun createLibrary(entry: LibraryDto, onDone: () -> Unit = {}) = run {
        library.createLibrary(entry)
        refreshLibraries()
        toast = "媒体库已创建"
        onDone()
    }

    fun cancelScan(libraryId: String) = run {
        library.cancelScan(libraryId)
        toast = "正在停止扫描"
    }

    fun deleteLibrary(id: String) = run {
        library.deleteLibrary(id)
        refreshLibraries()
        toast = "媒体库已删除"
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
