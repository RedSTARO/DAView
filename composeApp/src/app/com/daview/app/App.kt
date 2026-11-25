package com.daview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.daview.app.ui.browseSource
import com.daview.app.ui.DetailScreen
import com.daview.app.ui.EmptyState
import com.daview.app.ui.HomeScreen
import com.daview.app.ui.LibraryScreen
import com.daview.app.ui.LoadingPane
import com.daview.app.ui.PlatformBackHandler
import com.daview.app.ui.PlayerScreen
import com.daview.app.ui.SearchScreen
import com.daview.app.ui.SettingsScreen
import com.daview.app.ui.ShelfScreen
import com.daview.app.ui.SystemBarAppearance
import com.daview.app.ui.formatDuration

@Composable
fun App(windowDialogs: @Composable () -> Unit = {}) {
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
        windowDialogs()
    }
}

/** The window while the library is being opened, or if it could not be. */
@Composable
private fun Opening(error: String?) {
    if (error == null) {
        LoadingPane()
        return
    }
    EmptyState("无法打开媒体库", error, icon = Icons.Outlined.ErrorOutline)
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
    var preferExpandedRail by rememberSaveable { mutableStateOf(true) }
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
            preferExpandedRail = preferExpandedRail,
            onToggleRail = { preferExpandedRail = !preferExpandedRail },
            page = { entry ->
                holder.SaveableStateProvider(entry.key) {
                    Page(state, playback, entry, showBack = entry == state.currentEntry && state.canGoBack)
                }
            },
            navigation = { rail, expanded, toggleRail, sourceVisible ->
                AppNavigation(state, rail, expanded, toggleRail) { screen ->
                    state.selectBrowseDestination(screen, sourceVisible)
                }
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
            icon = { Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null) },
            title = { Text("继续播放？", textAlign = TextAlign.Center) },
            text = {
                Text("${item.seriesName?.let { "$it · ${item.name}" } ?: item.name}\n上次看到 ${formatDuration(item.userData.positionMs)}。")
            },
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
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 360.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContainedLoadingIndicator()
                Text("正在准备播放", style = MaterialTheme.typography.titleLargeEmphasized,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                playback.startingName?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { playback.cancelStart() }) { Text("取消") }
            }
        }
    }
}

/**
 * An external player still running while the viewer browses. Leaving its panel
 * no longer ends it, so something has to say it is there and lead back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExternalPlayingBanner(state: AppState, playback: PlaybackController) {
    val label = playback.externalPlayerLabel ?: return
    val info = playback.info ?: return
    if (state.current is Screen.Player) return
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(bottom = 8.dp).widthIn(max = 420.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("$label 正在播放", style = MaterialTheme.typography.labelMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info.item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { playback.showExternalPanel() }) {
                Text("查看", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
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
