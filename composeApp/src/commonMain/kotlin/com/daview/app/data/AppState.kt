package com.daview.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.daview.app.platform.SettingsStore
import com.daview.app.platform.ambientServerUrl
import com.daview.app.platform.ambientToken
import com.daview.app.platform.createSettingsStore
import com.daview.shared.api.DaViewApiException
import com.daview.shared.api.DaViewClient
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
    data object Connect : Screen
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
 */
class AppState(private val scope: CoroutineScope) {

    private val settings: SettingsStore = createSettingsStore()

    var serverUrl by mutableStateOf(
        settings.getString(KEY_SERVER) ?: ambientServerUrl() ?: "http://127.0.0.1:8096"
    )
    var token by mutableStateOf(settings.getString(KEY_TOKEN) ?: ambientToken() ?: "")

    // Snapshot-backed: screens and LaunchedEffects key off the connection.
    var client by mutableStateOf<DaViewClient?>(null)
        private set

    var connecting by mutableStateOf(false)
        private set
    var connectionError by mutableStateOf<String?>(null)
    var serverInfo by mutableStateOf<ServerInfoDto?>(null)
    var serverSettings by mutableStateOf<ServerSettingsDto?>(null)

    val backStack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Connect)
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

    // ------------------------------------------------------------ connection

    fun setTheme(dark: Boolean) {
        darkTheme = dark
        settings.putString(KEY_THEME, if (dark) "dark" else "light")
    }

    fun connect(url: String = serverUrl, accessToken: String = token, remember: Boolean = true) {
        scope.launch {
            connecting = true
            connectionError = null
            try {
                val normalised = url.trim().trimEnd('/').ifBlank { "http://127.0.0.1:8096" }
                val candidate = DaViewClient(
                    baseUrl = normalised,
                    tokenProvider = { accessToken.trim().ifBlank { null } }
                )
                val info = candidate.info()
                client?.close()
                client = candidate
                serverInfo = info
                serverUrl = normalised
                token = accessToken.trim()
                if (remember) {
                    settings.putString(KEY_SERVER, normalised)
                    settings.putString(KEY_TOKEN, token)
                }
                refreshLibraries()
                replaceAll(Screen.Home)
            } catch (e: DaViewApiException) {
                connectionError = if (e.status == 401) "令牌无效，请检查服务器令牌" else e.message
            } catch (e: Throwable) {
                connectionError = "无法连接到服务器: ${e.message ?: e::class.simpleName}"
            } finally {
                connecting = false
            }
        }
    }

    fun disconnect() {
        client?.close()
        client = null
        serverInfo = null
        settings.putString(KEY_TOKEN, null)
        replaceAll(Screen.Connect)
    }

    /** Attempts a silent reconnect on start-up when credentials are remembered. */
    fun tryAutoConnect() {
        if (token.isNotBlank() || ambientToken() != null) connect(remember = true)
    }

    // ------------------------------------------------------------ data

    private inline fun run(crossinline block: suspend (DaViewClient) -> Unit) {
        val active = client ?: return
        scope.launch {
            try {
                block(active)
            } catch (e: Throwable) {
                toast = e.message ?: "请求失败"
            }
        }
    }

    fun refreshLibraries() = run { libraries = it.libraries() }

    fun loadServerSettings() = run { serverSettings = it.settings() }

    fun refreshHome() = run { api ->
        val libs = api.libraries()
        libraries = libs
        home = HomeData(
            resume = api.resume(20),
            nextUp = api.nextUp(20),
            latest = api.latest(limit = 24)
        )
        // One request per library, and they only fill in the bottom of the page,
        // so they run after the rest of it is already on screen.
        home = home.copy(
            unwatched = libs.associate { it.id to api.unwatched(it.id, 24) }
                .filterValues { it.isNotEmpty() }
        )
    }

    fun loadLibrary(libraryId: String) = run { api ->
        libraryLoading = true
        try {
            val kind = when (libraries.firstOrNull { it.id == libraryId }?.kind?.isSeriesLike) {
                true -> ItemKind.SERIES
                else -> ItemKind.MOVIE
            }
            val page = api.items(libraryId = libraryId, kind = kind, sort = librarySort, limit = 500)
            // A series library can still contain stand-alone films (a spin-off
            // movie folder inside a show); include them so nothing disappears.
            val extra = if (kind == ItemKind.SERIES) {
                api.items(libraryId = libraryId, kind = ItemKind.MOVIE, sort = librarySort, limit = 200).items
                    .filter { it.parentId == null }
            } else emptyList()
            libraryItems = (page.items + extra).sortedBy { it.sortName }
        } finally {
            libraryLoading = false
        }
    }

    fun setSort(sort: String, libraryId: String) {
        librarySort = sort
        loadLibrary(libraryId)
    }

    fun loadDetail(itemId: String) = run { api ->
        detailLoading = true
        try {
            val item = api.item(itemId)
            detailItem = item
            detailChildren = if (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON) {
                api.children(itemId)
            } else emptyList()
            // An episode has no children of its own. What its page wants is the
            // rest of the run it sits in, and that hangs off its season — the
            // same list a series page shows for the season it has selected.
            val seasonId = detailChildren.firstOrNull { it.kind == ItemKind.SEASON }?.id
                ?: item.parentId?.takeIf { item.kind == ItemKind.EPISODE }
            detailSeasonId = seasonId
            detailEpisodes = seasonId?.let { api.children(it) } ?: emptyList()
        } finally {
            detailLoading = false
        }
    }

    fun selectSeason(seasonId: String) = run { api ->
        detailSeasonId = seasonId
        detailEpisodes = api.children(seasonId)
    }

    fun search(query: String) = run { api ->
        searchQuery = query
        searchResults = if (query.isBlank()) emptyList() else api.items(search = query, limit = 60).items
    }

    fun toggleFavorite(item: MediaItemDto) = run { api ->
        api.setFavorite(item.id, !item.userData.favorite)
        if (detailItem?.id == item.id) detailItem = api.item(item.id)
        toast = if (item.userData.favorite) "已取消收藏" else "已收藏"
    }

    /**
     * Works for any kind. A series or season has no watched flag of its own, so
     * the decision comes from its episodes — and marking one flips all of them,
     * which is why the whole open detail is reloaded rather than one row.
     */
    fun togglePlayed(item: MediaItemDto) = run { api ->
        val markPlayed = item.playedState != PlayedState.PLAYED
        api.setPlayed(item.id, markPlayed)
        detailItem?.id?.let { openId ->
            detailItem = api.item(openId)
            if (detailChildren.isNotEmpty()) detailChildren = api.children(openId)
            detailSeasonId?.let { detailEpisodes = api.children(it) }
        }
        toast = if (markPlayed) "已标记为已观看" else "已标记为未观看"
    }

    fun startScan(libraryId: String, mode: ScanMode = ScanMode.FULL) = run { api ->
        api.scanLibrary(libraryId, mode)
        toast = when (mode) {
            ScanMode.MISSING -> "已开始刮削未刮削的条目"
            ScanMode.REFRESH -> "已开始重新刮削全部"
            ScanMode.FULL -> "已开始扫描"
        }
        pollScanStatus()
    }

    fun pollScanStatus() {
        scope.launch {
            while (isActive) {
                val api = client ?: return@launch
                val status = runCatching { api.scanStatus() }.getOrNull() ?: return@launch
                scanStatus = status
                if (status.none { it.running }) {
                    refreshLibraries()
                    return@launch
                }
                delay(2000)
            }
        }
    }

    fun saveServerSettings(updated: ServerSettingsDto, onDone: (Boolean) -> Unit = {}) = run { api ->
        serverSettings = api.updateSettings(updated)
        toast = "设置已保存"
        onDone(true)
    }

    fun createLibrary(library: LibraryDto, onDone: () -> Unit = {}) = run { api ->
        api.createLibrary(library)
        refreshLibraries()
        toast = "媒体库已创建"
        onDone()
    }

    fun deleteLibrary(id: String) = run { api ->
        api.deleteLibrary(id)
        refreshLibraries()
        toast = "媒体库已删除"
    }

    private companion object {
        const val KEY_SERVER = "serverUrl"
        const val KEY_TOKEN = "token"
        const val KEY_THEME = "theme"
    }
}
