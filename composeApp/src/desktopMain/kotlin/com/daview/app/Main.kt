package com.daview.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.daview.app.data.ActivePlayback
import com.daview.app.platform.SettingsStore
import com.daview.app.platform.createSettingsStore
import com.daview.app.ui.DaViewIcon
import com.daview.app.ui.LocalWindowFullscreen
import java.awt.Dimension
import kotlin.system.exitProcess

/**
 * Desktop entry point.
 *
 * Nothing is started here any more. The library is created with the UI and
 * lives in this process; the only socket the app ever opens is the playback
 * pipe, and only while a player is reading from it.
 */
fun main() {
    application {
        val store = remember { createSettingsStore() }

        // Published into the composition so the player can ask for the whole
        // screen without the window being threaded down to it.
        val fullscreen = remember { mutableStateOf(false) }

        val windowState = rememberWindowState(
            size = store.savedSize() ?: DEFAULT_SIZE,
            // Centre rather than the platform default, which put the window at
            // the top left of the primary display every single launch.
            position = WindowPosition(Alignment.Center)
        )

        LaunchedEffect(fullscreen.value) {
            windowState.placement =
                if (fullscreen.value) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }

        // Bytes for an external player come from a socket inside this process,
        // so quitting kills the film that player is showing. Asking first is the
        // least that can be done about it.
        var confirmClose by remember { mutableStateOf(false) }

        fun quit() {
            store.remember(windowState)
            exitApplication()
            exitProcess(0)
        }

        Window(
            onCloseRequest = {
                if (ActivePlayback.externalRunning) confirmClose = true else quit()
            },
            title = "DAView",
            icon = DaViewIcon,
            state = windowState
        ) {
            // Below this the layout has nowhere left to go: the compact
            // skeleton needs room for a bottom bar and one column of posters.
            LaunchedEffect(window) {
                window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            }

            CompositionLocalProvider(LocalWindowFullscreen provides fullscreen) {
                App()
            }

            if (confirmClose) {
                AlertDialog(
                    onDismissRequest = { confirmClose = false },
                    title = { Text("还有正在播放的内容") },
                    text = {
                        Text(
                            "外部播放器的画面是从 DAView 取的字节，现在退出会让它当场断流。" +
                                "已经看到的进度已经记下了。"
                        )
                    },
                    confirmButton = { TextButton(onClick = { quit() }) { Text("仍然退出") } },
                    dismissButton = {
                        TextButton(onClick = { confirmClose = false }) { Text("取消") }
                    }
                )
            }
        }
    }
}

/**
 * `DpSize` here is not scaled by the display: Compose Desktop rounds the value
 * and hands it to AWT as pixels. So this is 1360x900 physical pixels on every
 * machine, and the app's own layout sees 1360 / display-scaling dp — which is
 * why the size is kept modest enough to fit a 1366x768 laptop after the saved
 * value has been read back.
 */
private val DEFAULT_SIZE = DpSize(1280.dp, 800.dp)
private const val MIN_WIDTH = 480
private const val MIN_HEIGHT = 640

private const val KEY_WIDTH = "window.width"
private const val KEY_HEIGHT = "window.height"

/**
 * The size the window had when it was last closed, clamped to something that
 * fits on this machine — a window saved on a 4K display should not open off the
 * edge of a laptop screen.
 */
private fun SettingsStore.savedSize(): DpSize? {
    val width = getString(KEY_WIDTH)?.toFloatOrNull() ?: return null
    val height = getString(KEY_HEIGHT)?.toFloatOrNull() ?: return null
    if (width < MIN_WIDTH || height < MIN_HEIGHT) return null

    val screen = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull()
    val maxWidth = screen?.width?.toFloat() ?: width
    val maxHeight = screen?.height?.toFloat() ?: height
    return DpSize(width.coerceAtMost(maxWidth).dp, height.coerceAtMost(maxHeight).dp)
}

/** Keeps the size across launches. A full-screen window's size is not its own. */
private fun SettingsStore.remember(state: WindowState) {
    if (state.placement != WindowPlacement.Floating) return
    putString(KEY_WIDTH, state.size.width.value.toString())
    putString(KEY_HEIGHT, state.size.height.value.toString())
}
