package com.daview.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.daview.app.data.AppState
import com.daview.app.data.DesktopShortcuts
import com.daview.app.data.LocalImageFetcher
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.theme.DaViewTheme
import com.daview.shared.model.LibraryKind
import com.daview.app.ui.DetailScreen
import com.daview.app.ui.HomeScreen
import com.daview.app.ui.LoadingPane
import com.daview.app.ui.LibraryScreen
import com.daview.app.ui.PlatformBackHandler
import com.daview.app.ui.PlayerScreen
import com.daview.app.ui.SearchScreen
import com.daview.app.ui.SettingsScreen
import com.daview.app.ui.SystemBarAppearance
import kotlinx.coroutines.CoroutineScope

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }

    // Opening the library is disk work — the SQLite driver unpacks itself, the
    // migrations run — and it used to happen inside composition, which is to
    // say before the window had anything to show. Now the window comes up
    // first and this fills it in.
    LaunchedEffect(Unit) { state.open() }

    // The desktop window handles keys outside the composition and needs a
    // way to reach the state they act on.
    LaunchedEffect(state) { DesktopShortcuts.bind(state) }

    DaViewTheme(darkTheme = state.darkTheme) {
        // The app's own switch, not the system's night setting, decides what
        // the status bar sits on — so it is what decides the icons' colour too.
        SystemBarAppearance(state.darkTheme)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.ready) Library(state, scope) else Opening(state.startupError)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

@Composable
private fun Library(state: AppState, scope: CoroutineScope) {
    val playback = remember { PlaybackController(state, scope) }

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
    // running before this process was.
    LaunchedEffect(Unit) { state.pollScanStatus() }

    // A host rather than a hand-placed Snackbar: it animates in and out, holds
    // the message for the platform's own reading time, and announces itself to
    // a screen reader — none of which a bare Snackbar pinned to a Box does.
    // The sequence number is what makes the same message twice show twice.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.toastSeq) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // M3's medium window class: a rail earns its place from 600dp, and
        // waiting until 720 left 600-719dp phones in landscape on the bottom
        // bar, which is the one layout that cannot reach a library.
        val wide = maxWidth >= 600.dp
        val immersive = state.current is Screen.Player

        // Android's back gesture is the primary way back; unhandled it finishes
        // the activity from whatever screen the user is on.
        PlatformBackHandler(enabled = state.backStack.size > 1) {
            if (immersive) playback.stopAndLeave() else state.back()
        }

        Box(Modifier.fillMaxSize()) {
            when {
                // The player asked for the screen, so it gets all of it: no bar
                // above, no navigation below, and no insets carved out of it.
                immersive -> Content(state, playback)

                wide -> Row(Modifier.fillMaxSize()) {
                    SideNavigation(state)
                    Column(Modifier.fillMaxSize()) {
                        TopRow(state)
                        // A cap on how wide the content runs. Nothing in the app
                        // had one, so a maximised window put a synopsis on lines
                        // of over a hundred Chinese characters and stretched a
                        // single-line URL field across the whole desktop.
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(Modifier.widthIn(max = 1180.dp)) {
                                Content(state, playback)
                            }
                        }
                    }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    TopRow(state)
                    Box(Modifier.weight(1f)) { Content(state, playback) }
                    BottomNavigation(state)
                }
            }

            if (!immersive) {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        // Clear of the bottom bar rather than on top of it: it
                        // used to cover all three destinations and swallow taps
                        // meant for them.
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (wide) 24.dp else 96.dp
                        )
                )
            }
        }
    }
}

@Composable
private fun Content(state: AppState, playback: PlaybackController) {
    AnimatedContent(
        targetState = state.current,
        // Going in and coming back played the same animation, so the two
        // directions were indistinguishable and the middle of every transition
        // had both screens half-drawn over each other. A short slide says which
        // way the stack moved; the fade is offset so they do not overlap.
        transitionSpec = {
            val forward = state.movingForward
            val offset = { size: Int -> if (forward) size / 12 else -size / 12 }
            (
                fadeIn(animationSpec = tween(180, delayMillis = 90)) +
                    slideInHorizontally(animationSpec = tween(240), initialOffsetX = offset)
                ) togetherWith fadeOut(animationSpec = tween(120))
        },
        label = "screen"
    ) { screen ->
        when (screen) {
            is Screen.Home -> HomeScreen(state, playback)
            is Screen.Library -> LibraryScreen(state, playback, screen.libraryId)
            is Screen.Detail -> DetailScreen(state, playback)
            is Screen.Search -> SearchScreen(state, playback)
            is Screen.Settings -> SettingsScreen(state)
            is Screen.Player -> PlayerScreen(state, playback)
        }
    }
}

/**
 * The bar above every screen. Its height is fixed whether or not there is
 * anywhere to go back to: it used to collapse to 8dp on a root screen, so every
 * step in and out of a page shoved the whole content area 48dp while the two
 * screens were still cross-fading — a jump the fade had no way to cover.
 *
 * It also carries the status bar inset for the whole app. The window is drawn
 * edge to edge, and this is the only thing between the top of the screen and
 * the first line of content.
 */
@Composable
private fun TopRow(state: AppState) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        AnimatedVisibility(
            visible = state.backStack.size > 1,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IconButton(onClick = { state.back() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
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

@Composable
private fun SideNavigation(state: AppState) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(
            selected = state.current is Screen.Home,
            onClick = { state.switchTo(Screen.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("首页") },
            colors = railColors()
        )
        NavigationRailItem(
            selected = state.current is Screen.Search,
            onClick = { state.switchTo(Screen.Search) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("搜索") },
            colors = railColors()
        )
        NavigationRailItem(
            selected = state.current is Screen.Settings,
            onClick = { state.switchTo(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
            colors = railColors()
        )
        Spacer(Modifier.height(16.dp))
        // Scrollable, because the libraries are unbounded: a fourth one used to
        // be measured to zero height and simply not exist on a landscape phone.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
        ) {
            state.libraries.forEach { library ->
                NavigationRailItem(
                    selected = (state.current as? Screen.Library)?.libraryId == library.id,
                    onClick = { state.switchTo(Screen.Library(library.id)) },
                    icon = { Icon(libraryIcon(library.kind), contentDescription = null) },
                    label = {
                        // Capped, or one long library name drags the whole rail
                        // wider and takes the width out of the content beside it.
                        Text(
                            library.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 72.dp)
                        )
                    },
                    colors = railColors()
                )
            }
        }
    }
}

@Composable
private fun BottomNavigation(state: AppState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        NavigationBarItem(
            selected = state.current is Screen.Home,
            onClick = { state.switchTo(Screen.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("首页") },
            colors = barColors()
        )
        LibrariesItem(state)
        NavigationBarItem(
            selected = state.current is Screen.Search,
            onClick = { state.switchTo(Screen.Search) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("搜索") },
            colors = barColors()
        )
        NavigationBarItem(
            selected = state.current is Screen.Settings,
            onClick = { state.switchTo(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
            colors = barColors()
        )
    }
}

/**
 * The bottom bar's way into the libraries, which the rail has one item per and
 * this bar had none of — so a phone could only reach a library through the home
 * screen's shortcut row, and the two navigation skeletons did not offer the
 * same destinations.
 *
 * One library goes straight there; several open a menu, because a bar item per
 * library would not fit and would change width every time a library is added.
 */
@Composable
private fun RowScope.LibrariesItem(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val libraries = state.libraries

    NavigationBarItem(
        selected = state.current is Screen.Library,
        onClick = {
            when (libraries.size) {
                0 -> state.switchTo(Screen.Settings)
                1 -> state.switchTo(Screen.Library(libraries.first().id))
                else -> open = true
            }
        },
        icon = {
            Box {
                Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    libraries.forEach { library ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(libraryIcon(library.kind), contentDescription = null)
                            },
                            text = { Text(library.name) },
                            onClick = {
                                open = false
                                state.switchTo(Screen.Library(library.id))
                            }
                        )
                    }
                }
            }
        },
        label = { Text("媒体库") },
        colors = barColors()
    )
}
