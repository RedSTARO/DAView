package com.daview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.daview.app.data.AppState
import com.daview.app.data.BackStackEntry
import com.daview.app.data.DesktopShortcuts
import com.daview.app.data.LocalImageFetcher
import com.daview.app.data.ModalMarker
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.data.ThemeMode
import com.daview.app.theme.DaViewTheme
import com.daview.app.ui.AdaptiveAlertDialog
import com.daview.app.ui.AdaptiveBrowse
import com.daview.app.ui.AppMenu
import com.daview.app.ui.browseSource
import com.daview.app.ui.DetailScreen
import com.daview.app.ui.HomeScreen
import com.daview.app.ui.LibraryScreen
import com.daview.app.ui.LoadingPane
import com.daview.app.ui.PlatformBackHandler
import com.daview.app.ui.PlayerScreen
import com.daview.app.ui.SearchScreen
import com.daview.app.ui.SettingsScreen
import com.daview.app.ui.ShelfScreen
import com.daview.app.ui.SystemBarAppearance
import com.daview.app.ui.Tooltip
import com.daview.app.ui.formatDuration
import com.daview.shared.model.LibraryKind

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }

    // The screens on the stack, kept where Android keeps an activity's state.
    // A process reclaimed in the background used to come back on the home
    // page, whatever the viewer had been looking at.
    var savedStack by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // Opening the library is disk work — the SQLite driver unpacks itself, the
    // migrations run — and it used to happen inside composition, which is to
    // say before the window had anything to show. Now the window comes up
    // first and this fills it in.
    LaunchedEffect(Unit) { state.open() }

    val systemDark = isSystemInDarkTheme()
    val dark = when (state.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    DaViewTheme(darkTheme = dark) {
        // The app's own switch, not the system's night setting, decides what
        // the status bar sits on — so it is what decides the icons' colour too.
        SystemBarAppearance(dark)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.ready) {
                LaunchedEffect(Unit) {
                    state.restoreStack(savedStack)
                    snapshotFlow { state.encodeStack() }.collect { savedStack = it }
                }
                Library(state)
            } else {
                Opening(state.startupError)
            }
        }
    }
}

/** The window while the library is being opened, or if it could not be. */
@Composable
private fun Opening(error: String?) {
    if (error == null) {
        LoadingPane()
        return
    }
    LoadingPane {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("无法打开媒体库", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Library(state: AppState) {
    val scope = rememberCoroutineScope()
    val playback = remember { PlaybackController(state, scope) }

    // The desktop window handles keys outside the composition and needs a
    // way to reach the state they act on.
    LaunchedEffect(state) { DesktopShortcuts.bind(state, playback) }

    // Artwork resolves against the on-disk cache rather than a URL: the file is
    // already on this device, and there is no longer anything listening to hand
    // it back over a socket.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(LocalImageFetcher.Factory(state.library)) }
            .crossfade(true)
            .build()
    }

    // A scan outlives the screen that started it, and may well have been
    // running before this process was. So does a download — and the queue it
    // was on died with that process, so it is put back here.
    LaunchedEffect(Unit) {
        state.pollScanStatus()
        state.resumeDownloads()
    }

    // A host rather than a hand-placed Snackbar: it animates in and out, holds
    // the message for the platform's own reading time, and announces itself to
    // a screen reader — none of which a bare Snackbar pinned to a Box does.
    // The sequence number is what makes the same message twice show twice.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.toastSeq) {
        val toast = state.toast ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = toast.message,
            actionLabel = toast.actionLabel,
            duration = if (toast.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) toast.action?.invoke()
    }

    // The holder must live above both the browsing layout and immersive player.
    val holder = rememberSaveableStateHolder()
    val live = state.backStack.map { it.key }.toSet()
    val known = remember { mutableSetOf<Long>() }
    LaunchedEffect(live) {
        (known - live).forEach { holder.removeState(it) }
        known.retainAll(live)
        known.addAll(live)
    }

    val immersive = state.current is Screen.Player
    PlatformBackHandler(enabled = state.canGoBack) {
        if (immersive) playback.stopAndLeave() else state.back()
    }
    if (immersive) {
        key(state.currentEntry.key) {
            holder.SaveableStateProvider(state.currentEntry.key) { PlayerScreen(state, playback) }
        }
        AppDialogs(state, playback)
    } else {
        val source = browseSource(state.backStack)
        AdaptiveBrowse(
            current = state.currentEntry,
            source = source,
            page = { entry ->
                holder.SaveableStateProvider(entry.key) {
                    Page(state, playback, entry, showBack = entry == state.currentEntry && state.canGoBack)
                }
            },
            navigation = { rail, labels, sourceVisible ->
                val select = { screen: Screen -> state.selectBrowseDestination(screen, sourceVisible) }
                if (rail) SideNavigation(state, labels, select) else BottomNavigation(state, labels, select)
            },
            messages = {
                Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ExternalPlayingBanner(state, playback)
                    SnackbarHost(hostState = snackbar)
                }
            },
            blocking = { if (playback.starting) StartingOverlay(playback) },
            dialogs = { AppDialogs(state, playback) }
        )
    }

}

@Composable
private fun AppDialogs(state: AppState, playback: PlaybackController) {
    // Each dialog owns the keyboard, not the page behind it.
    playback.resumePrompt?.let { item ->
        ModalMarker()
        AdaptiveAlertDialog(
            onDismissRequest = { playback.dismissResumePrompt() },
            title = { Text(item.seriesName?.let { "$it · ${item.name}" } ?: item.name) },
            text = { Text("上次看到 ${formatDuration(item.userData.positionMs)}。") },
            confirmButton = {
                TextButton(onClick = { playback.answerResumePrompt(fromStart = false) }) {
                    Text("继续播放")
                }
            },
            dismissButton = {
                TextButton(onClick = { playback.answerResumePrompt(fromStart = true) }) { Text("从头播放") }
            }
        )
    }

    state.confirmation?.let { question ->
        ModalMarker()
        AdaptiveAlertDialog(
            onDismissRequest = { state.answerConfirmation(false) },
            title = { Text(question.title) },
            text = { Text(question.text) },
            confirmButton = {
                TextButton(onClick = { state.answerConfirmation(true) }) {
                    Text(
                        question.confirmLabel,
                        color = if (question.destructive) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }
            },
            dismissButton = { TextButton(onClick = { state.answerConfirmation(false) }) { Text("取消") } }
        )
    }

    if (state.pendingLeave != null) {
        ModalMarker()
        AdaptiveAlertDialog(
            onDismissRequest = { state.cancelLeave() },
            title = { Text("有未保存的设置") },
            text = { Text("离开后，这一页上改了但没保存的内容会丢失。") },
            confirmButton = {
                TextButton(onClick = { state.confirmLeave() }) {
                    Text("放弃修改并离开", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { state.cancelLeave() }) { Text("继续编辑") } }
        )
    }
}

/**
 * Between pressing play and the player appearing: the file is probed and a
 * link resolved, which can take a few seconds on the share, and nothing on
 * screen used to change in the meantime — so play got pressed again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartingOverlay(playback: PlaybackController) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // The page underneath is not to be used while the film opens: a
            // second play pressed through the scrim cancelled the first.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center
    ) {
        ModalMarker()
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(Modifier.size(36.dp))
                Spacer(Modifier.height(14.dp))
                Text("正在准备播放", style = MaterialTheme.typography.titleSmall)
                playback.startingName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { playback.cancelStart() }) { Text("取消") }
            }
        }
    }
}

/**
 * An external player still running while the viewer browses. Leaving its panel
 * no longer ends it, so something has to say it is there and lead back.
 */
@Composable
private fun ExternalPlayingBanner(state: AppState, playback: PlaybackController) {
    val label = playback.externalPlayerLabel ?: return
    val info = playback.info ?: return
    if (state.current is Screen.Player) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "$label 正在播放「${info.item.name}」",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp)
            )
            TextButton(onClick = { playback.showExternalPanel() }) { Text("查看") }
        }
    }
}

@Composable
private fun Page(state: AppState, playback: PlaybackController, entry: BackStackEntry, showBack: Boolean) {
    when (val screen = entry.screen) {
        is Screen.Home -> HomeScreen(state, playback)
        is Screen.Library -> LibraryScreen(state, playback, screen.libraryId, showBack)
        is Screen.Detail -> DetailScreen(state, playback, screen.itemId, entry.key)
        is Screen.Search -> SearchScreen(state, playback, showBack)
        is Screen.Settings -> SettingsScreen(state)
        is Screen.Shelf -> ShelfScreen(state, playback, screen, showBack)
        is Screen.Player -> Unit // Owned by the immersive host, not a browse pane.
    }
}

/**
 * The label sits outside the indicator pill, so it takes the surface's own
 * foreground; only the icon, which sits inside, takes the container's. Giving
 * the label `primary` as well put three different purples in one 56dp item and
 * left the label reading as a second, weaker selection cue.
 */
@Composable
private fun railColors() = NavigationRailItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSurface
)

@Composable
private fun barColors() = NavigationBarItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSurface
)

@Composable
internal fun libraryIcon(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIE -> Icons.Filled.Movie
    LibraryKind.SERIES -> Icons.Filled.Tv
    LibraryKind.ANIME -> Icons.Filled.Animation
    // Not the film reel again: sharing MOVIE's icon made the two kinds
    // indistinguishable wherever the label is truncated.
    LibraryKind.OTHER -> Icons.Filled.FolderOpen
}

/** A small dot on 设置 while a scan runs, the one background task with no page of its own. */
@Composable
private fun SettingsIcon(state: AppState) {
    if (state.scanStatus.any { it.running }) {
        BadgedBox(badge = { Badge() }) { Icon(Icons.Filled.Settings, contentDescription = null) }
    } else {
        Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

@Composable
private fun SideNavigation(state: AppState, labels: Boolean, select: (Screen) -> Unit) {
    val active = state.browseDestination
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // All destinations must be reachable even when a freeform or split window
        // is too short for four items. Scroll the whole rail, not only the libraries.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            RailDestination("首页", labels, active is Screen.Home, { select(Screen.Home) }) {
                Icon(Icons.Filled.Home, contentDescription = null)
            }
            RailDestination("搜索", labels, active is Screen.Search, { select(Screen.Search) }) {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
            if (state.libraries.isEmpty()) {
                RailDestination("媒体库", labels, false, { select(Screen.Settings) }) {
                    Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                }
            }
            state.libraries.forEach { library ->
                val target = Screen.Library(library.id)
                RailDestination(library.name, labels, active == target, { select(target) }) {
                    Icon(libraryIcon(library.kind), contentDescription = null)
                }
            }
            RailDestination("设置", labels, active is Screen.Settings, { select(Screen.Settings) }) {
                SettingsIcon(state)
            }
        }
    }
}

@Composable
private fun RailDestination(name: String, labels: Boolean, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Tooltip(name) {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = icon,
            label = if (labels) ({ NavigationLabel(name) }) else null,
            modifier = if (labels) Modifier else Modifier.semantics { contentDescription = name },
            colors = railColors()
        )
    }
}

@Composable
private fun NavigationLabel(name: String) {
    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
}

@Composable
private fun BottomNavigation(state: AppState, labels: Boolean, select: (Screen) -> Unit) {
    val active = state.browseDestination
    NavigationBar(
        modifier = Modifier.fillMaxHeight(),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        BarDestination("首页", labels, active is Screen.Home, { select(Screen.Home) }) {
            Icon(Icons.Filled.Home, contentDescription = null)
        }
        LibrariesItem(state, labels, select)
        BarDestination("搜索", labels, active is Screen.Search, { select(Screen.Search) }) {
            Icon(Icons.Filled.Search, contentDescription = null)
        }
        BarDestination("设置", labels, active is Screen.Settings, { select(Screen.Settings) }) {
            SettingsIcon(state)
        }
    }
}

@Composable
private fun RowScope.BarDestination(name: String, labels: Boolean, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = if (labels) ({ NavigationLabel(name) }) else null,
        modifier = if (labels) Modifier else Modifier.semantics { contentDescription = name },
        colors = barColors()
    )
}

/**
 * The bottom bar's way into the libraries, which the rail has one item per and
 * this bar had none of — so a phone could only reach a library through the home
 * screen's shortcut row, and the two navigation skeletons did not offer the
 * same destinations.
 *
 * One library goes straight there; several open a menu, because a bar item per
 * library would not fit and would change width every time a library is added.
 * With none, the menu says so and offers the way to add one, instead of
 * quietly switching to another tab.
 */
@Composable
private fun RowScope.LibrariesItem(state: AppState, labels: Boolean, select: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val libraries = state.libraries

    BarDestination(
        name = "媒体库",
        labels = labels,
        selected = state.browseDestination is Screen.Library,
        onClick = {
            if (libraries.size == 1) select(Screen.Library(libraries.first().id)) else open = true
        },
        icon = {
            Box {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                AppMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (libraries.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有媒体库") },
                            enabled = false,
                            onClick = {}
                        )
                        DropdownMenuItem(
                            text = { Text("去设置里添加…") },
                            onClick = {
                                open = false
                                select(Screen.Settings)
                            }
                        )
                    }
                    libraries.forEach { library ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(libraryIcon(library.kind), contentDescription = null)
                            },
                            text = { Text(library.name) },
                            onClick = {
                                open = false
                                select(Screen.Library(library.id))
                            }
                        )
                    }
                }
            }
        }
    )
}
