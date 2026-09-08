package com.daview.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.daview.app.data.AppState
import com.daview.app.data.LocalImageFetcher
import com.daview.app.data.PlaybackController
import com.daview.app.data.Screen
import com.daview.app.theme.DaViewTheme
import com.daview.shared.model.LibraryKind
import com.daview.app.ui.DetailScreen
import com.daview.app.ui.HomeScreen
import com.daview.app.ui.LibraryScreen
import com.daview.app.ui.PlayerScreen
import com.daview.app.ui.SearchScreen
import com.daview.app.ui.SettingsScreen
import kotlinx.coroutines.delay

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
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


    LaunchedEffect(Unit) {
        state.start()
        // A scan outlives the screen that started it, and may well have been
        // running before this process was.
        state.pollScanStatus()
    }


    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2600)
            state.toast = null
        }
    }

    DaViewTheme(darkTheme = state.darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                Box(Modifier.fillMaxSize()) {
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            SideNavigation(state)
                            Column(Modifier.fillMaxSize()) {
                                TopRow(state)
                                Content(state, playback)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            TopRow(state)
                            Box(Modifier.weight(1f)) { Content(state, playback) }
                            BottomNavigation(state)
                        }
                    }

                    state.toast?.let { message ->
                        Snackbar(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        ) { Text(message) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(state: AppState, playback: PlaybackController) {
    AnimatedContent(
        targetState = state.current,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen"
    ) { screen ->
        when (screen) {
            is Screen.Home -> HomeScreen(state, playback)
            is Screen.Library -> LibraryScreen(state, screen.libraryId)
            is Screen.Detail -> DetailScreen(state, playback)
            is Screen.Search -> SearchScreen(state)
            is Screen.Settings -> SettingsScreen(state)
            is Screen.Player -> PlayerScreen(state, playback)
        }
    }
}

@Composable
private fun TopRow(state: AppState) {
    if (state.backStack.size <= 1) {
        Spacer(Modifier.height(8.dp))
        return
    }
    Row(
        Modifier.padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { state.back() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
    }
}

@Composable
private fun railColors() = NavigationRailItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary
)

@Composable
private fun barColors() = NavigationBarItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary
)

@Composable
private fun libraryIcon(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIE -> Icons.Filled.Movie
    LibraryKind.SERIES -> Icons.Filled.Tv
    LibraryKind.ANIME -> Icons.AutoMirrored.Filled.PlaylistPlay
    LibraryKind.OTHER -> Icons.Filled.Movie
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
            onClick = { state.replaceAll(Screen.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("首页") },
            colors = railColors()
        )
        NavigationRailItem(
            selected = state.current is Screen.Search,
            onClick = { state.navigate(Screen.Search) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("搜索") },
            colors = railColors()
        )
        NavigationRailItem(
            selected = state.current is Screen.Settings,
            onClick = { state.navigate(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
            colors = railColors()
        )
        Spacer(Modifier.height(16.dp))
        state.libraries.forEach { library ->
            NavigationRailItem(
                selected = (state.current as? Screen.Library)?.libraryId == library.id,
                onClick = { state.navigate(Screen.Library(library.id)) },
                icon = { Icon(libraryIcon(library.kind), contentDescription = null) },
                label = { Text(library.name, maxLines = 1) },
                colors = railColors()
            )
        }
    }
}

@Composable
private fun BottomNavigation(state: AppState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        NavigationBarItem(
            selected = state.current is Screen.Home,
            onClick = { state.replaceAll(Screen.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("首页") },
            colors = barColors()
        )
        NavigationBarItem(
            selected = state.current is Screen.Search,
            onClick = { state.navigate(Screen.Search) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("搜索") },
            colors = barColors()
        )
        NavigationBarItem(
            selected = state.current is Screen.Settings,
            onClick = { state.navigate(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
            colors = barColors()
        )
    }
}
