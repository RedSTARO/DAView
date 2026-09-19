package com.daview.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.daview.app.platform.SettingsStore
import com.daview.app.platform.createCoreContext
import com.daview.app.platform.createSettingsStore
import com.daview.app.platform.onDownloadStarted
import com.daview.app.platform.onScanStarted
import com.daview.server.ServerContext
import com.daview.server.api.AssetLinks
import com.daview.server.api.MediaFacade
import com.daview.shared.api.DaViewJson
import com.daview.shared.model.DownloadDto
import com.daview.shared.model.DownloadState
import com.daview.shared.model.ItemKind
import com.daview.shared.model.LibraryDto
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.PlayedState
import com.daview.shared.model.ScanMode
import com.daview.shared.model.ScanProgressDto
import com.daview.shared.model.ServerInfoDto
import com.daview.shared.model.ServerSettingsDto
import com.daview.shared.model.UserDataDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

sealed interface Screen {
    data object Home : Screen
    data class Library(val libraryId: String) : Screen
    data class Detail(val itemId: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Player(val itemId: String) : Screen

    /** Everything on one of the home shelves, not only the first two dozen. */
    data class Shelf(val kind: ShelfKind) : Screen
}

enum class ShelfKind(val title: String) {
    RESUME("继续观看"),
    NEXT_UP("接下来"),
    LATEST("最近添加"),
    FAVOURITES("收藏")
}

/**
 * One page on the back stack. The key tells two visits to the same screen
 * apart, and it is what each page's remembered state — scroll position above
 * all — is filed under while the page is out of sight.
 */
data class BackStackEntry(val screen: Screen, val key: Long)

/** The home page's rows, in the order and with the visibility the viewer chose. */
enum class HomeSection(val key: String, val label: String) {
    HERO("hero", "顶部大图"),
    LIBRARIES("libraries", "媒体库入口"),
    RESUME("resume", "继续观看"),
    NEXT_UP("nextUp", "接下来"),
    LATEST("latest", "最近添加"),
    FAVOURITES("favourites", "收藏"),
    UNWATCHED("unwatched", "各库未观看")
}

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), DARK("深色"), LIGHT("浅色") }

/** What the play button does with something already part-watched. */
enum class ResumeBehavior(val label: String) {
    RESUME("直接续播"),
    ASK("每次询问")
}

enum class LibraryLayout { GRID, LIST }

/**
 * How one library is being looked at. Kept per library: the order that suits a
 * film library (newest first) is rarely the one that suits an anime library,
 * and a filter set in one used to follow the viewer into the next.
 */
@Serializable
data class LibraryView(
    val sort: String = "sortName",
    val descending: Boolean = false,
    val onlyUnwatched: Boolean = false,
    val onlyFavourite: Boolean = false,
    val inProgress: Boolean = false,
    val genre: String? = null,
    /** The first year of a decade, e.g. 1990. */
    val decade: Int? = null,
    val layout: LibraryLayout = LibraryLayout.GRID,
    /** 0 small, 1 medium, 2 large. */
    val density: Int = 1
) {
    val filtered: Boolean
        get() = onlyUnwatched || onlyFavourite || inProgress || genre != null || decade != null
}

data class HomeData(
    val resume: List<MediaItemDto> = emptyList(),
    val nextUp: List<MediaItemDto> = emptyList(),
    val latest: List<MediaItemDto> = emptyList(),
    val favourites: List<MediaItemDto> = emptyList(),
    /** Unwatched entries keyed by library id. Libraries with none are absent. */
    val unwatched: Map<String, List<MediaItemDto>> = emptyMap()
)

/** One line put in front of the user, optionally with a way to take it back. */
data class Toast(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * A question asked before something that cannot be taken back. The app hosts
 * the dialog, so a menu that is already gone by the time its entry runs can
 * still ask.
 */
data class Confirmation(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val action: () -> Unit
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
     * Whether the library is open and the first read of it has come back.
     * Flipping on the open alone drew one frame of the home page with no
     * libraries in it — "还没有媒体库" — for anyone who had several.
     */
    val ready: Boolean get() = opened != null && initialLoaded

    private var initialLoaded by mutableStateOf(false)

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

    private var nextKey = 1L
    private fun entry(screen: Screen) = BackStackEntry(screen, nextKey++)

    val backStack: SnapshotStateList<BackStackEntry> = mutableStateListOf(entry(Screen.Home))
    val currentEntry: BackStackEntry get() = backStack.last()
    val current: Screen get() = currentEntry.screen
    val canGoBack: Boolean get() = backStack.size > 1

    var libraries by mutableStateOf<List<LibraryDto>>(emptyList())
    var home by mutableStateOf(HomeData())

    /** True once the home rows have been read at least once. */
    var homeLoaded by mutableStateOf(false)
        private set

    var scanStatus by mutableStateOf<List<ScanProgressDto>>(emptyList())

    // ------------------------------------------------------------ preferences

    var themeMode by mutableStateOf(
        when (settings.getString(KEY_THEME)) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            // Absent means the app has never been told; an old install stored
            // "dark" or "light" and keeps what it had.
            else -> ThemeMode.SYSTEM
        }
    )
        private set

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        settings.putString(
            KEY_THEME,
            when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.DARK -> "dark"
                ThemeMode.LIGHT -> "light"
            }
        )
    }

    /**
     * Whether the end of an episode rolls straight into the next one. On by
     * default: an evening of a series otherwise meant repeating "close the
     * player, find the show, find the episode, press play" once every twenty
     * minutes.
     */
    var autoPlayNext by mutableStateOf(settings.getString(KEY_AUTOPLAY) != "0")
        private set

    fun setAutoPlay(value: Boolean) {
        autoPlayNext = value
        settings.putString(KEY_AUTOPLAY, if (value) "1" else "0")
    }

    /** Whether the desktop window goes full screen when playback starts. */
    var autoFullscreen by mutableStateOf(settings.getString(KEY_AUTO_FULLSCREEN) != "0")
        private set

    fun changeAutoFullscreen(value: Boolean) {
        autoFullscreen = value
        settings.putString(KEY_AUTO_FULLSCREEN, if (value) "1" else "0")
    }

    var resumeBehavior by mutableStateOf(
        if (settings.getString(KEY_RESUME) == "ask") ResumeBehavior.ASK else ResumeBehavior.RESUME
    )
        private set

    fun changeResumeBehavior(value: ResumeBehavior) {
        resumeBehavior = value
        settings.putString(KEY_RESUME, if (value == ResumeBehavior.ASK) "ask" else "resume")
    }

    /** A [com.daview.shared.model.languageGroup] key, or blank for "no preference". */
    var preferredAudioLanguage by mutableStateOf(settings.getString(KEY_AUDIO_LANGUAGE).orEmpty())
        private set

    fun changePreferredAudioLanguage(value: String) {
        preferredAudioLanguage = value
        settings.putString(KEY_AUDIO_LANGUAGE, value.ifBlank { null })
    }

    /** A language key, [com.daview.shared.model.SUBTITLE_PREF_OFF], or blank. */
    var preferredSubtitleLanguage by mutableStateOf(settings.getString(KEY_SUBTITLE_LANGUAGE).orEmpty())
        private set

    fun changePreferredSubtitleLanguage(value: String) {
        preferredSubtitleLanguage = value
        settings.putString(KEY_SUBTITLE_LANGUAGE, value.ifBlank { null })
    }

    /** How large subtitles are drawn, as a factor of the player's own default. */
    var subtitleScale by mutableStateOf(settings.getString(KEY_SUBTITLE_SCALE)?.toFloatOrNull() ?: 1f)
        private set

    fun changeSubtitleScale(value: Float) {
        subtitleScale = value
        settings.putString(KEY_SUBTITLE_SCALE, value.toString())
    }

    /**
     * Which player the plain play button uses, by id. Blank means "whichever
     * comes first", which is what it always did — an order written into the
     * detection list, so someone with both PotPlayer and mpv installed got
     * PotPlayer every time and had to go through the context menu to say
     * otherwise, once per episode.
     */
    var preferredPlayerId by mutableStateOf(settings.getString(KEY_PLAYER).orEmpty())
        private set

    fun setPreferredPlayer(id: String) {
        preferredPlayerId = id
        settings.putString(KEY_PLAYER, id.ifBlank { null })
    }

    /** The home rows, in order, with whether each is shown. */
    var homeSections by mutableStateOf(readHomeSections())
        private set

    private fun readHomeSections(): List<Pair<HomeSection, Boolean>> {
        val stored = settings.getString(KEY_HOME_SECTIONS).orEmpty()
            .split(',')
            .mapNotNull { token ->
                val key = token.substringBefore(':')
                val section = HomeSection.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
                section to (token.substringAfter(':', "1") != "0")
            }
        // Sections added in a later version go at the end, shown.
        val missing = HomeSection.entries.filter { section -> stored.none { it.first == section } }
        return stored + missing.map { it to true }
    }

    fun changeHomeSections(value: List<Pair<HomeSection, Boolean>>) {
        homeSections = value
        settings.putString(
            KEY_HOME_SECTIONS,
            value.joinToString(",") { (section, shown) -> "${section.key}:${if (shown) 1 else 0}" }
        )
    }

    // ------------------------------------------------------------ library page

    var libraryItems by mutableStateOf<List<MediaItemDto>>(emptyList())

    /**
     * Which library [libraryItems] holds, so a screen can tell "still loading"
     * from "loaded and empty" without a second flag — and, more to the point,
     * so moving between libraries does not briefly render the previous one's
     * entries under the new one's name.
     */
    var libraryItemsOf by mutableStateOf<String?>(null)

    /** True while the page has nothing of its own to show yet. */
    var libraryLoading by mutableStateOf(false)
        private set

    /**
     * True while a new order or filter is being fetched behind the entries
     * already on screen. They stay put until the answer arrives; replacing them
     * with a spinner made every click on a sort button blank the page.
     */
    var libraryRefreshing by mutableStateOf(false)
        private set

    /** How many entries the library holds, as opposed to how many are loaded. */
    var libraryTotal by mutableStateOf(0)
        private set

    /** True while another page is on its way in behind the ones on screen. */
    var libraryLoadingMore by mutableStateOf(false)
        private set

    /** Bumped when the page should go back to its top: a new order or filter. */
    var libraryScrollToTop by mutableStateOf(0)
        private set

    /** Set when a screen could not load, so it can offer to try again. */
    var libraryError by mutableStateOf<String?>(null)
        private set

    private val libraryViews = mutableStateMapOf<String, LibraryView>()

    /** Search within one library, which is not remembered between launches. */
    private val librarySearches = mutableStateMapOf<String, String>()

    fun viewOf(libraryId: String): LibraryView = libraryViews.getOrPut(libraryId) {
        settings.getString(KEY_LIBRARY_VIEW + libraryId)
            ?.let { runCatching { DaViewJson.decodeFromString(LibraryView.serializer(), it) }.getOrNull() }
            ?: LibraryView()
    }

    fun searchOf(libraryId: String): String = librarySearches[libraryId].orEmpty()

    fun updateView(libraryId: String, transform: (LibraryView) -> LibraryView) {
        val before = viewOf(libraryId)
        val after = transform(before)
        if (after == before) return
        libraryViews[libraryId] = after
        settings.putString(KEY_LIBRARY_VIEW + libraryId, DaViewJson.encodeToString(LibraryView.serializer(), after))
        // Layout and density change how the same entries are drawn; everything
        // else changes which entries there are.
        val requery = after.copy(layout = before.layout, density = before.density) != before
        if (requery) loadLibrary(libraryId, LoadMode.REQUERY)
    }

    fun setSort(sort: String, libraryId: String) = updateView(libraryId) { view ->
        // Pressing the field that is already chosen flips the direction, which
        // is how every other catalogue behaves.
        if (sort == view.sort) view.copy(descending = !view.descending)
        else view.copy(sort = sort, descending = sort in DESCENDING_BY_DEFAULT)
    }

    fun clearLibraryFilters(libraryId: String) {
        librarySearches[libraryId] = ""
        updateView(libraryId) {
            it.copy(onlyUnwatched = false, onlyFavourite = false, inProgress = false, genre = null, decade = null)
        }
        loadLibrary(libraryId, LoadMode.REQUERY)
    }

    fun setLibrarySearch(libraryId: String, query: String) {
        if (searchOf(libraryId) == query) return
        librarySearches[libraryId] = query
        loadLibrary(libraryId, LoadMode.REQUERY)
    }

    /** The genres and decades a library's filter menus offer. */
    var libraryGenres by mutableStateOf<List<String>>(emptyList())
        private set
    var libraryDecades by mutableStateOf<List<Int>>(emptyList())
        private set
    private var vocabularyOf: String? = null

    private fun loadVocabulary(libraryId: String) {
        if (vocabularyOf == libraryId) return
        vocabularyOf = libraryId
        run {
            libraryGenres = library.libraryGenres(libraryId)
            libraryDecades = library.libraryYears(libraryId).map { it / 10 * 10 }.distinct()
        }
    }

    // ------------------------------------------------------------ detail page

    var detailItem by mutableStateOf<MediaItemDto?>(null)
    var detailChildren by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailEpisodes by mutableStateOf<List<MediaItemDto>>(emptyList())
    var detailSeasonId by mutableStateOf<String?>(null)
    var detailLoading by mutableStateOf(false)

    /**
     * The episode "play" on a series or a season lands on: whatever was left
     * part-watched, else the first unwatched one, across every season. The
     * button used to look only inside whichever season happened to be selected.
     */
    var detailNextUp by mutableStateOf<MediaItemDto?>(null)
        private set

    var detailError by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ search

    var searchQuery by mutableStateOf("")

    /** Films and series that match, most relevant first. */
    var searchResults by mutableStateOf<List<MediaItemDto>>(emptyList())
        private set

    /** Episodes whose own title matches, kept apart from the works. */
    var searchEpisodes by mutableStateOf<List<MediaItemDto>>(emptyList())
        private set

    var searchTotal by mutableStateOf(0)
        private set

    /** The query [searchResults] answers, so a return to the page does not ask again. */
    private var searchedFor: String? = null

    /** True between a query being asked for and its answer arriving. */
    var searchLoading by mutableStateOf(false)
        private set

    var searchLoadingMore by mutableStateOf(false)
        private set

    /** The search in flight, so a newer one can cancel it. */
    private var searchJob: Job? = null

    var searchHistory by mutableStateOf(
        settings.getString(KEY_SEARCH_HISTORY)
            ?.let { runCatching { DaViewJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptyList()
    )
        private set

    fun rememberSearch(query: String) {
        val value = query.trim()
        if (value.isEmpty()) return
        searchHistory = (listOf(value) + searchHistory.filter { it != value }).take(10)
        settings.putString(KEY_SEARCH_HISTORY, DaViewJson.encodeToString(ListSerializer(String.serializer()), searchHistory))
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        settings.putString(KEY_SEARCH_HISTORY, null)
    }

    // ------------------------------------------------------------ messages

    var toast by mutableStateOf<Toast?>(null)
        private set

    /**
     * Bumped with every message. The snackbar host is driven off this rather
     * than off [toast], so saying the same thing twice in a row — two failed
     * scans, two "已收藏" — shows twice instead of silently once.
     */
    var toastSeq by mutableStateOf(0)
        private set

    var confirmation by mutableStateOf<Confirmation?>(null)
        private set

    fun confirm(question: Confirmation) {
        confirmation = question
    }

    fun answerConfirmation(yes: Boolean) {
        val question = confirmation ?: return
        confirmation = null
        if (yes) question.action()
    }

    /** Puts one line in front of the user, with an optional way to undo. */
    fun notify(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        toast = Toast(message, actionLabel, action)
        toastSeq++
    }

    // ------------------------------------------------------------ navigation

    /**
     * How to stop playback, registered by the controller. AppState owns the
     * navigation and the controller owns the engine, and the destinations in
     * the navigation bars are on the wrong side of that line.
     */
    var leavingPlayer: (() -> Unit)? = null

    /** Which way the last move went, so the transition can say so. */
    var movingForward by mutableStateOf(true)
        private set

    /**
     * Set by the settings page while it holds edits nobody saved. Leaving then
     * asks first instead of dropping them without a word.
     */
    private val dirtySettings = mutableStateMapOf<String, Boolean>()

    val unsavedSettings: Boolean get() = dirtySettings.values.any { it }

    /** Each settings form says whether it holds unsaved edits, under its own name. */
    fun markSettingsDirty(section: String, dirty: Boolean) {
        if (dirty) dirtySettings[section] = true else dirtySettings.remove(section)
    }

    /** A move held back until the user decides about [unsavedSettings]. */
    var pendingLeave by mutableStateOf<(() -> Unit)?>(null)
        private set

    private fun guarded(move: () -> Unit) {
        if (unsavedSettings && current is Screen.Settings) {
            pendingLeave = move
            return
        }
        move()
    }

    fun confirmLeave() {
        val move = pendingLeave ?: return
        dirtySettings.clear()
        pendingLeave = null
        move()
    }

    fun cancelLeave() {
        pendingLeave = null
    }

    fun navigate(screen: Screen) = guarded {
        // Already there: pressing a destination you are on should do nothing,
        // not push a second copy that the back arrow then has to walk out of.
        if (current == screen) return@guarded
        movingForward = true
        backStack.add(entry(screen))
        onEnter(screen, returning = false)
    }

    /**
     * Goes to one of the app's top-level destinations.
     *
     * These are tabs, not pages: the bar used to push each one, so bouncing
     * between 首页 and 搜索 a few times built a stack the back arrow had to be
     * pressed once per bounce to unwind, with no limit on how deep it went.
     */
    fun switchTo(screen: Screen) = guarded {
        if (current == screen) return@guarded
        // Anything that leaves the player has to stop it first; otherwise the
        // engine kept running under the new page, the transition went round the
        // houses, and the stack came to rest one level below where it started.
        leavingPlayer?.takeIf { current is Screen.Player }?.invoke()
        // A tab switch is a move sideways, not back: it slides the way a step
        // forward does, whichever way the move before it went.
        movingForward = true
        backStack.clear()
        backStack.add(entry(screen))
        onEnter(screen, returning = false)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        guarded {
            movingForward = false
            backStack.removeAt(backStack.lastIndex)
            onEnter(current, returning = true)
        }
        return true
    }

    /** The screens on the stack, for putting it back after the process was reclaimed. */
    fun encodeStack(): List<String> = backStack.mapNotNull { it.screen.encode() }

    fun restoreStack(encoded: List<String>) {
        val screens = encoded.mapNotNull(::decodeScreen)
        if (screens.isEmpty() || screens == listOf(Screen.Home)) return
        backStack.clear()
        screens.forEach { backStack.add(entry(it)) }
        onEnter(current, returning = false)
    }

    private fun onEnter(screen: Screen, returning: Boolean) {
        when (screen) {
            is Screen.Home -> refreshHome()
            is Screen.Library -> loadLibrary(
                screen.libraryId,
                if (returning && libraryItemsOf == screen.libraryId) LoadMode.REFRESH else LoadMode.FRESH
            )
            is Screen.Detail -> loadDetail(screen.itemId, quiet = returning)
            is Screen.Settings -> {
                refreshLibraries()
                loadServerSettings()
            }
            is Screen.Shelf -> loadShelf(screen.kind)
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
            runCatching {
                serverInfo = library.info()
                libraries = library.libraries()
            }
            initialLoaded = true
            refreshHome()
        }
    }

    /** Loads whatever the current screen shows, again. */
    fun refreshCurrent() {
        when (val screen = current) {
            is Screen.Home -> refreshHome()
            is Screen.Library -> loadLibrary(screen.libraryId, LoadMode.REFRESH)
            is Screen.Detail -> loadDetail(screen.itemId, quiet = true)
            is Screen.Settings -> {
                refreshLibraries()
                loadServerSettings()
            }
            is Screen.Shelf -> loadShelf(screen.kind)
            is Screen.Search -> {
                searchedFor = null
                search(searchQuery)
            }
            else -> Unit
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notify(describe(e))
            }
        }
    }

    /** The message worth showing for a failure from the facade. */
    fun describe(e: Throwable): String = when (e) {
        is MediaFacade.FacadeException -> listOfNotNull(e.message, e.detail).joinToString("：")
        else -> e.message ?: "操作失败"
    }

    fun refreshLibraries() = run {
        libraries = library.libraries()
        serverInfo = library.info()
    }

    fun loadServerSettings() = run { serverSettings = library.settings() }

    /**
     * The rows are independent questions, so they are asked at once rather than
     * one after another: the library serves reads from a pool, and asking in
     * sequence made the page take the sum of them instead of the longest.
     */
    /** True while the home rows are being read again, for the pull-to-refresh indicator. */
    var homeRefreshing by mutableStateOf(false)
        private set

    fun refreshHome() = run {
        homeRefreshing = true
        try {
            loadHome()
        } finally {
            homeRefreshing = false
        }
    }

    private suspend fun loadHome() {
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
            // The per-library rows are kept as they were until their own answer
            // comes back; clearing them here made the lower half of the page
            // collapse and grow back every time the page was refreshed.
            home = HomeData(
                resume = resumeRows,
                nextUp = nextUp.await().filterNot { (it.seriesId ?: it.id) in onResume },
                latest = latest.await(),
                favourites = favourites.await(),
                unwatched = home.unwatched.filterKeys { id -> libs.any { it.id == id } }
            )
        }
        homeLoaded = true
        // One row per library, and they only fill in the bottom of the page, so
        // they are gathered after the rest of it is already on screen.
        val rows = coroutineScope {
            libs.map { entry -> async { entry.id to library.unwatched(entry.id, 24, links) } }.awaitAll()
        }
        home = home.copy(unwatched = rows.toMap().filterValues { it.isNotEmpty() })
    }

    /** How a library page is being (re)loaded. */
    enum class LoadMode {
        /** Another library, or the first visit: nothing of this one is on screen. */
        FRESH,

        /** A new order, filter or search: the old entries stay until the new ones arrive. */
        REQUERY,

        /** The same entries again, updated in place — coming back, or a scan finishing. */
        REFRESH
    }

    private var libraryJob: Job? = null

    fun loadLibrary(libraryId: String, mode: LoadMode = LoadMode.FRESH) {
        libraryJob?.cancel()
        libraryError = null
        loadVocabulary(libraryId)
        val sameLibrary = libraryItemsOf == libraryId
        when {
            mode == LoadMode.FRESH || !sameLibrary -> {
                libraryLoading = true
                if (!sameLibrary) libraryItemsOf = null
            }
            else -> libraryRefreshing = true
        }
        // How much of the page is loaded, so a refresh in place asks for all of
        // it and the grid does not shrink back to the first page under the user.
        val keep = if (mode == LoadMode.REFRESH && sameLibrary) libraryItems.size.coerceAtLeast(PAGE_SIZE) else PAGE_SIZE
        libraryJob = scope.launch {
            val me = coroutineContext[Job]
            try {
                val collected = ArrayList<MediaItemDto>()
                var total: Int
                do {
                    val page = libraryPage(libraryId, offset = collected.size, limit = minOf(500, keep - collected.size))
                    collected += page.items
                    total = page.total
                } while (collected.size < keep && collected.size < total && page.items.isNotEmpty())
                libraryItems = collected
                libraryTotal = total
                libraryItemsOf = libraryId
                if (mode == LoadMode.REQUERY) libraryScrollToTop++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Kept on screen with a retry rather than only flashed past in a
                // snackbar: a library that cannot be read used to spin forever,
                // and after a few seconds even the message was gone.
                libraryError = describe(e)
            } finally {
                // A newer load may have replaced this one; only the current one
                // gets to say the page has stopped loading.
                if (libraryJob === me) {
                    libraryLoading = false
                    libraryRefreshing = false
                }
            }
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
        if (libraryLoadingMore || libraryLoading || libraryRefreshing) return
        if (libraryItemsOf != libraryId) return
        if (libraryItems.size >= libraryTotal) return
        libraryLoadingMore = true
        run {
            try {
                val page = libraryPage(libraryId, offset = libraryItems.size)
                // Guard against the page having moved under us — a re-sort or a
                // filter change while this was in flight.
                if (libraryItemsOf == libraryId) {
                    libraryItems = libraryItems + page.items.filter { new -> libraryItems.none { it.id == new.id } }
                    libraryTotal = page.total
                }
            } finally {
                libraryLoadingMore = false
            }
        }
    }

    /**
     * Makes sure the entry at [position] is loaded, so a jump can land on it.
     * Returns once it is, or when there is nothing more to load.
     */
    suspend fun ensureLibraryLoaded(libraryId: String, position: Int) {
        while (libraryItemsOf == libraryId && libraryItems.size <= position && libraryItems.size < libraryTotal) {
            val page = libraryPage(libraryId, offset = libraryItems.size, limit = 500)
            if (page.items.isEmpty()) return
            libraryItems = libraryItems + page.items
            libraryTotal = page.total
        }
    }

    /** Where the first entry filed under [boundary] sits, for the letter index. */
    suspend fun libraryPosition(libraryId: String, boundary: String): Int {
        val view = viewOf(libraryId)
        return library.libraryPosition(
            libraryId = libraryId,
            boundary = boundary,
            search = searchOf(libraryId).takeIf { it.isNotBlank() },
            favorite = true.takeIf { view.onlyFavourite },
            played = false.takeIf { view.onlyUnwatched },
            inProgress = view.inProgress,
            genre = view.genre,
            yearFrom = view.decade,
            yearTo = view.decade?.plus(9),
            descending = view.descending
        )
    }

    private suspend fun libraryPage(libraryId: String, offset: Int, limit: Int = PAGE_SIZE) = viewOf(libraryId).let { view ->
        library.items(
            links,
            libraryId = libraryId,
            // Series and stand-alone films together, ordered as one list. Asking
            // for a kind meant a series library ran two queries with two limits
            // and the films inside it were pinned to the end.
            topLevelOnly = true,
            search = searchOf(libraryId).takeIf { it.isNotBlank() },
            favorite = true.takeIf { view.onlyFavourite },
            played = false.takeIf { view.onlyUnwatched },
            inProgress = view.inProgress,
            genre = view.genre,
            yearFrom = view.decade,
            yearTo = view.decade?.plus(9),
            sort = view.sort,
            descending = view.descending,
            limit = limit.coerceAtLeast(1),
            offset = offset
        )
    }

    // ------------------------------------------------------------ shelves

    var shelfItems by mutableStateOf<List<MediaItemDto>>(emptyList())
        private set
    var shelfOf by mutableStateOf<ShelfKind?>(null)
        private set
    var shelfLoading by mutableStateOf(false)
        private set

    fun loadShelf(kind: ShelfKind) {
        if (shelfOf != kind) {
            shelfItems = emptyList()
            shelfOf = null
        }
        shelfLoading = true
        run {
            try {
                shelfItems = when (kind) {
                    ShelfKind.RESUME -> library.resume(200, links)
                    ShelfKind.NEXT_UP -> library.nextUp(200, links)
                    ShelfKind.LATEST -> library.latest(null, 200, links)
                    ShelfKind.FAVOURITES -> library.items(links, favorite = true, sort = "sortName", limit = 500).items
                }
                shelfOf = kind
            } finally {
                shelfLoading = false
            }
        }
    }

    // ------------------------------------------------------------ detail

    fun loadDetail(itemId: String, quiet: Boolean = false) = scope.launch {
        detailLoading = true
        detailError = null
        // Cleared so the page does not open showing the entry looked at before
        // it, complete with a live play button, until this one arrives.
        if (detailItem?.id != itemId) {
            detailItem = null
            detailChildren = emptyList()
            detailEpisodes = emptyList()
            detailSeasonId = null
            detailNextUp = null
        }
        try {
            val item = library.item(itemId, links)
            val children = if (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON) {
                library.children(itemId, links)
            } else emptyList()
            val nextUp = if (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON) {
                runCatching { library.nextEpisode(itemId, links) }.getOrNull()
            } else null
            val seasons = children.filter { it.kind == ItemKind.SEASON }
            // The season that was selected stays selected when the page is only
            // being refreshed; otherwise the one the viewer is working through,
            // which is where the next episode lives. It used to be the first
            // season every time, including on the way back from the player.
            val seasonId = when {
                item.kind == ItemKind.EPISODE -> item.parentId
                detailItem?.id == itemId && seasons.any { it.id == detailSeasonId } -> detailSeasonId
                else -> seasons.firstOrNull { it.id == nextUp?.parentId }?.id ?: seasons.firstOrNull()?.id
            }
            val episodes = seasonId?.let { library.children(it, links) } ?: emptyList()
            detailItem = item
            detailChildren = children
            detailNextUp = nextUp
            detailSeasonId = seasonId
            detailEpisodes = episodes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Kept on the page with a retry. It used to be a snackbar over a
            // blank screen, with a retry button wired to an id already cleared.
            if (quiet && detailItem?.id == itemId) notify(describe(e)) else detailError = describe(e)
        } finally {
            detailLoading = false
        }
    }

    fun selectSeason(seasonId: String) = run {
        detailSeasonId = seasonId
        detailEpisodes = library.children(seasonId, links)
    }

    // ------------------------------------------------------------ search

    /**
     * Runs one search, and cancels whatever search was still in flight.
     *
     * Every keystroke used to launch a coroutine that nobody could stop, so the
     * answer to a shorter, slower query could land after the answer to what the
     * user had actually finished typing.
     */
    fun search(query: String) {
        if (query == searchedFor && !searchLoading) return
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            searchEpisodes = emptyList()
            searchTotal = 0
            searchedFor = query
            searchLoading = false
            return
        }
        searchLoading = true
        searchJob = scope.launch {
            val me = coroutineContext[Job]
            try {
                coroutineScope {
                    val works = async {
                        library.items(
                            links, topLevelOnly = true, search = query, searchPeople = true,
                            sort = "relevance", limit = SEARCH_LIMIT
                        )
                    }
                    val episodes = async {
                        library.items(links, kind = ItemKind.EPISODE, search = query, sort = "relevance", limit = 30).items
                    }
                    val page = works.await()
                    searchResults = page.items
                    searchTotal = page.total
                    searchEpisodes = episodes.await()
                }
                searchedFor = query
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notify(describe(e))
            } finally {
                if (searchJob === me) searchLoading = false
            }
        }
    }

    fun loadMoreSearch() {
        if (searchLoading || searchLoadingMore || searchResults.size >= searchTotal) return
        val query = searchedFor ?: return
        searchLoadingMore = true
        run {
            try {
                val page = library.items(
                    links, topLevelOnly = true, search = query, searchPeople = true,
                    sort = "relevance", limit = SEARCH_LIMIT, offset = searchResults.size
                )
                if (searchedFor == query) searchResults = searchResults + page.items
            } finally {
                searchLoadingMore = false
            }
        }
    }

    /** Opens the search page on [query], for a genre or a name somewhere else. */
    fun searchFor(query: String) {
        searchQuery = query
        searchedFor = null
        navigate(Screen.Search)
        search(query)
    }

    // ------------------------------------------------------------ watch state

    /**
     * Stops offering an item on the continue-watching shelf. The alternative
     * was claiming to have finished it, which threw the position away too.
     */
    fun hideFromResume(item: MediaItemDto) = run {
        library.setHiddenFromResume(item.id, true)
        refreshHome()
        notify("已从继续观看中移除", "撤销") {
            run {
                library.setHiddenFromResume(item.id, false)
                refreshHome()
            }
        }
    }

    fun toggleFavorite(item: MediaItemDto) = run {
        val now = !item.userData.favorite
        library.setFavorite(item.id, now)
        refreshAfterWatchChange(item.id)
        notify(if (now) "已收藏" else "已取消收藏", "撤销") {
            run {
                library.setFavorite(item.id, !now)
                refreshAfterWatchChange(item.id)
            }
        }
    }

    /**
     * Works for any kind. A series or season has no watched flag of its own, so
     * the decision comes from its episodes — and marking one flips all of them,
     * which is why the whole open detail is reloaded rather than one row.
     *
     * The state from before is kept for the snackbar's undo: marking clears the
     * resume point (that is what "watched" and "not watched" both mean), and
     * one mistaken tap on a series used to take every episode's with it.
     */
    fun togglePlayed(item: MediaItemDto) = run {
        val markPlayed = item.playedState != PlayedState.PLAYED
        val before: Map<String, UserDataDto> = library.watchStateSnapshot(item.id)
        library.setPlayed(item.id, markPlayed)
        refreshAfterWatchChange(item.id)
        notify(if (markPlayed) "已标记为已观看" else "已标记为未观看", "撤销") {
            run {
                library.restoreWatchState(before)
                refreshAfterWatchChange(item.id)
                notify("已撤销")
            }
        }
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
        searchEpisodes = searchEpisodes.withFresh()
        shelfItems = shelfItems.withFresh()
        detailChildren = detailChildren.withFresh()
        detailEpisodes = detailEpisodes.withFresh()

        detailItem?.id?.let { openId ->
            detailItem = library.item(openId, links)
            if (detailChildren.isNotEmpty()) detailChildren = library.children(openId, links)
            detailSeasonId?.let { detailEpisodes = library.children(it, links) }
            detailItem?.takeIf { it.kind == ItemKind.SERIES || it.kind == ItemKind.SEASON }?.let {
                detailNextUp = runCatching { library.nextEpisode(openId, links) }.getOrNull()
            }
        }
        // Items in a library that is not the one on screen still need their
        // new state when the page is reached, which the refresh on entry does.
        (current as? Screen.Library)?.let { screen ->
            if (libraryItemsOf == screen.libraryId && libraryItems.none { it.id == itemId }) {
                loadLibrary(screen.libraryId, LoadMode.REFRESH)
            }
        }
        if (current is Screen.Home) refreshHome()
    }

    // ------------------------------------------------------------ several at once

    /** Marks every one of [items] watched or not, with one undo for the lot. */
    fun markMany(items: List<MediaItemDto>, played: Boolean) = run {
        val before = HashMap<String, UserDataDto>()
        items.forEach { before.putAll(library.watchStateSnapshot(it.id)) }
        items.forEach { library.setPlayed(it.id, played) }
        refreshAfterBatch()
        notify("已将 ${items.size} 项标记为${if (played) "已观看" else "未观看"}", "撤销") {
            run {
                library.restoreWatchState(before)
                refreshAfterBatch()
                notify("已撤销")
            }
        }
    }

    fun favouriteMany(items: List<MediaItemDto>, favourite: Boolean) = run {
        val before = items.associate { it.id to it.userData.favorite }
        items.forEach { library.setFavorite(it.id, favourite) }
        refreshAfterBatch()
        notify("已${if (favourite) "收藏" else "取消收藏"} ${items.size} 项", "撤销") {
            run {
                before.forEach { (id, value) -> library.setFavorite(id, value) }
                refreshAfterBatch()
            }
        }
    }

    fun downloadMany(items: List<MediaItemDto>) = run {
        var count = 0
        items.forEach { count += library.downloadAll(it.id) }
        onDownloadStarted()
        notify("已加入下载：$count 个文件")
        pollDownloads()
    }

    /** Folds [sources] into [target], the same as the merge dialog does. */
    fun mergeInto(target: MediaItemDto, sources: List<MediaItemDto>) = run {
        library.merge(target.id, sources.map { it.id }, links)
        refreshAfterBatch()
        refreshLibraries()
        notify("已将 ${sources.size} 项并入「${target.name}」")
    }

    private suspend fun refreshAfterBatch() {
        (current as? Screen.Library)?.let { loadLibrary(it.libraryId, LoadMode.REFRESH) }
        (current as? Screen.Detail)?.let { loadDetail(it.itemId, quiet = true) }
        if (current is Screen.Search) {
            searchedFor = null
            search(searchQuery)
        }
        (current as? Screen.Shelf)?.let { loadShelf(it.kind) }
        refreshHome()
    }

    // ------------------------------------------------------------ downloads

    /** What is on this device, and how far each one has got. */
    var downloads by mutableStateOf<List<DownloadDto>>(emptyList())
        private set

    var downloadedBytes by mutableStateOf(0L)
        private set

    private var downloadPoll: Job? = null

    fun refreshDownloads() = run {
        downloads = library.downloads()
        downloadedBytes = library.downloadedBytes()
    }

    /**
     * Keeps a copy of an item here, and follows it while it arrives.
     *
     * The poll stops on its own once nothing is moving; a download outlives the
     * screen that started it, so this is also called on start-up.
     */
    fun download(item: MediaItemDto) = run {
        if (item.isPlayable) {
            library.downloadItem(item.id)
            notify("已加入下载：${item.name}")
        } else {
            val count = library.downloadAll(item.id)
            notify("已加入下载：${item.name}（$count 集）")
        }
        onDownloadStarted()
        pollDownloads()
    }

    fun cancelDownload(item: MediaItemDto) = run {
        library.cancelDownload(item.id)
        refreshDownloadsNow()
    }

    fun removeDownload(itemId: String) = run {
        library.removeDownload(itemId)
        refreshDownloadsNow()
        notify("已删除本地文件")
    }

    private suspend fun refreshDownloadsNow() {
        downloads = library.downloads()
        downloadedBytes = library.downloadedBytes()
    }

    fun pollDownloads() {
        if (downloadPoll?.isActive == true) return
        downloadPoll = scope.launch {
            while (isActive) {
                downloads = runCatching { library.downloads() }.getOrDefault(downloads)
                downloadedBytes = runCatching { library.downloadedBytes() }.getOrDefault(downloadedBytes)
                if (downloads.none { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
                    return@launch
                }
                delay(1500)
            }
        }
    }

    fun downloadOf(itemId: String): DownloadDto? = downloads.firstOrNull { it.itemId == itemId }

    // ------------------------------------------------------------ metadata

    private fun replaceEverywhere(updated: MediaItemDto) {
        fun List<MediaItemDto>.withFresh() = map { if (it.id == updated.id) updated else it }
        libraryItems = libraryItems.withFresh()
        searchResults = searchResults.withFresh()
        shelfItems = shelfItems.withFresh()
        if (detailItem?.id == updated.id) detailItem = updated
    }

    /** Writes fields a person corrected by hand. Blank fields are cleared. */
    fun updateItem(
        item: MediaItemDto,
        name: String,
        originalName: String,
        overview: String,
        year: Int?,
        genres: List<String>
    ) = run {
        val updated = library.updateItem(
            id = item.id,
            links = links,
            name = name.takeIf { it.isNotBlank() },
            originalName = originalName,
            overview = overview,
            year = year,
            genres = genres
        )
        replaceEverywhere(updated)
        notify("已保存")
    }

    /** Hands hand-typed fields back to the scraper, and scrapes again. */
    fun revertManualEdits(item: MediaItemDto) = run {
        notify("正在恢复「${item.name}」的刮削信息…")
        val updated = library.revertManualEdits(item.id, links)
        replaceEverywhere(updated)
        if (detailItem?.id == item.id) loadDetail(item.id, quiet = true)
        notify("已恢复为刮削结果：${updated.name}")
    }

    /** Takes a manual identify off, so the item matches on its own again. */
    fun unpin(item: MediaItemDto) = run {
        notify("正在解除手动指定并重新匹配…")
        val updated = library.unpin(item.id, links)
        replaceEverywhere(updated)
        if (detailItem?.id == item.id) loadDetail(item.id, quiet = true)
        refreshLibraries()
        notify("已解除手动指定：${updated.name}")
    }

    fun setArtwork(item: MediaItemDto, type: String, bytes: ByteArray, extension: String) = run {
        val updated = library.setArtwork(item.id, type, bytes, extension, links)
        replaceEverywhere(updated)
        refreshHome()
        notify(if (type == "backdrop") "已更换背景图" else "已更换海报")
    }

    /** Scrapes one entry again, without re-scraping the library around it. */
    fun refreshMetadata(item: MediaItemDto) = run {
        notify("正在重新刮削「${item.name}」…")
        val updated = library.refreshItem(item.id, links)
        replaceEverywhere(updated)
        if (detailItem?.id == item.id) loadDetail(item.id, quiet = true) else refreshHome()
        notify("已重新刮削：${updated.name}")
    }

    fun setTrackSelection(item: MediaItemDto, audio: Int?, subtitle: Int?) = run {
        library.setTrackSelection(item.id, audio, subtitle)
        val fresh = library.item(item.id, links)
        replaceEverywhere(fresh)
        detailEpisodes = detailEpisodes.map { if (it.id == fresh.id) fresh else it }
    }

    // ------------------------------------------------------------ scanning

    fun startScan(libraryId: String, mode: ScanMode = ScanMode.FULL) = run {
        library.scan(libraryId, mode)
        // A scan is minutes of round trips against the share, and on a phone the
        // process is reclaimed the moment the user switches away unless
        // something says otherwise.
        onScanStarted()
        notify(
            when (mode) {
                ScanMode.MISSING -> "已开始补全缺失的元数据"
                ScanMode.REFRESH -> "已开始全部重新刮削"
                ScanMode.FULL -> "已开始扫描"
            }
        )
        pollScanStatus()
    }

    private var scanPoll: Job? = null

    /**
     * Follows a scan that is already running, whoever started it. Scans outlive
     * the screen that kicked them off, so this is called on start-up too.
     */
    fun pollScanStatus() {
        if (scanPoll?.isActive == true) return
        scanPoll = scope.launch {
            var sawRunning = false
            while (isActive) {
                scanStatus = runCatching { library.scanStatus() }.getOrDefault(scanStatus)
                if (scanStatus.any { it.running }) sawRunning = true
                if (scanStatus.none { it.running }) {
                    refreshLibraries()
                    // The shelves are built from what the scan just wrote. Only
                    // the library counts used to be refreshed, so a first scan
                    // finished into a home screen that still looked empty.
                    refreshHome()
                    if (sawRunning) {
                        (current as? Screen.Library)?.let { loadLibrary(it.libraryId, LoadMode.REFRESH) }
                    }
                    return@launch
                }
                delay(2000)
            }
        }
    }

    fun cancelScan(libraryId: String) = run {
        library.cancelScan(libraryId)
        notify("正在停止扫描")
    }

    // ------------------------------------------------------------ settings

    fun saveServerSettings(updated: ServerSettingsDto, onDone: (Boolean) -> Unit = {}) = run {
        serverSettings = library.updateSettings(updated)
        serverInfo = library.info()
        notify("设置已保存")
        onDone(true)
    }

    /**
     * Adds a library and scans it straight away. The button always said
     * 「创建并扫描」 and only ever created, which left a new user looking at an
     * empty library and a hint to go and find the scan button elsewhere.
     *
     * The scan is started here rather than inside the facade: a restored backup
     * and a pulled sync file create libraries through the same call, and neither
     * should set off a scan of every library it carries.
     */
    fun createLibrary(entry: LibraryDto, onDone: () -> Unit = {}) = run {
        val created = library.createLibrary(entry)
        refreshLibraries()
        onDone()
        library.scan(created.id, ScanMode.FULL)
        onScanStarted()
        notify("已添加「${created.name}」，正在扫描")
        pollScanStatus()
    }

    /** Renames a library, changes its kind or its scraping order. */
    fun updateLibrary(updated: LibraryDto, rescan: Boolean, onDone: () -> Unit = {}) = run {
        library.updateLibrary(updated.id, updated)
        refreshLibraries()
        onDone()
        if (rescan) {
            library.scan(updated.id, ScanMode.REFRESH)
            onScanStarted()
            pollScanStatus()
            notify("已保存，正在按新设置重新刮削")
        } else {
            notify("已保存媒体库设置")
        }
    }

    fun deleteLibrary(id: String) = run {
        library.deleteLibrary(id)
        refreshLibraries()
        libraryViews.remove(id)
        settings.putString(KEY_LIBRARY_VIEW + id, null)
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
        const val KEY_AUTOPLAY = "player.autoPlayNext"
        const val KEY_AUTO_FULLSCREEN = "player.autoFullscreen"
        const val KEY_RESUME = "player.resumeBehavior"
        const val KEY_AUDIO_LANGUAGE = "player.audioLanguage"
        const val KEY_SUBTITLE_LANGUAGE = "player.subtitleLanguage"
        const val KEY_SUBTITLE_SCALE = "player.subtitleScale"
        const val KEY_PLAYER = "player.preferred"
        const val KEY_HOME_SECTIONS = "home.sections"
        const val KEY_LIBRARY_VIEW = "library.view."
        const val KEY_SEARCH_HISTORY = "search.history"
    }
}

/** How a screen is written down to survive the process being reclaimed. */
fun Screen.encode(): String? = when (this) {
    Screen.Home -> "home"
    is Screen.Library -> "library:$libraryId"
    is Screen.Detail -> "detail:$itemId"
    Screen.Search -> "search"
    Screen.Settings -> "settings"
    is Screen.Shelf -> "shelf:${kind.name}"
    // A player cannot be put back: the session behind it is gone.
    is Screen.Player -> null
}

fun decodeScreen(value: String): Screen? {
    val kind = value.substringBefore(':')
    val arg = value.substringAfter(':', "")
    return when (kind) {
        "home" -> Screen.Home
        "library" -> arg.takeIf { it.isNotBlank() }?.let { Screen.Library(it) }
        "detail" -> arg.takeIf { it.isNotBlank() }?.let { Screen.Detail(it) }
        "search" -> Screen.Search
        "settings" -> Screen.Settings
        "shelf" -> ShelfKind.entries.firstOrNull { it.name == arg }?.let { Screen.Shelf(it) }
        else -> null
    }
}
