package com.daview.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.daview.app.data.ActivePlayback
import com.daview.app.data.DesktopShortcuts
import com.daview.app.platform.SettingsStore
import com.daview.app.platform.createSettingsStore
import com.daview.app.ui.DaViewIcon
import com.daview.app.ui.LocalWindowFullscreen
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
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
            placement = if (store.getString(KEY_MAXIMIZED) == "1") WindowPlacement.Maximized else WindowPlacement.Floating,
            size = store.savedSize() ?: DEFAULT_SIZE,
            // Where it was left, if that is still on a screen; centred otherwise,
            // rather than the platform default at the top left of the primary
            // display.
            position = store.savedPosition() ?: WindowPosition(Alignment.Center)
        )

        // What to return to when full screen ends: a window that was maximised
        // before the film should be maximised after it.
        var beforeFullscreen by remember { mutableStateOf(windowState.placement) }
        LaunchedEffect(fullscreen.value) {
            if (fullscreen.value) {
                if (windowState.placement != WindowPlacement.Fullscreen) beforeFullscreen = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = beforeFullscreen
            }
        }

        // Bytes for an external player come from a socket inside this process,
        // so quitting kills the film that player is showing. Asking first is the
        // least that can be done about it.
        var confirmClose by remember { mutableStateOf(false) }

        fun quit() {
            store.remember(windowState, beforeFullscreen)
            exitApplication()
            exitProcess(0)
        }

        Window(
            onCloseRequest = {
                if (ActivePlayback.externalRunning) confirmClose = true else quit()
            },
            title = "DAView",
            icon = DaViewIcon,
            state = windowState,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when {
                    // Whatever is open inside the full-screen player closes
                    // first; only then does Esc leave full screen.
                    event.key == Key.Escape && fullscreen.value -> {
                        if (!com.daview.app.data.InputTracker.handleEscape()) fullscreen.value = false
                        true
                    }
                    event.key == Key.F11 -> {
                        fullscreen.value = !fullscreen.value
                        true
                    }
                    else -> DesktopShortcuts.handle(event)
                }
            }
        ) {
            // Below this the layout has nowhere left to go: the compact
            // skeleton needs room for a bottom bar and one column of posters.
            LaunchedEffect(window) {
                window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            }

            // The mouse's back button. Compose sees only the three it knows
            // about, so the window listens for the fourth itself.
            DisposableEffect(window) {
                val listener = AWTEventListener { event ->
                    val mouse = event as? MouseEvent ?: return@AWTEventListener
                    if (mouse.id == MouseEvent.MOUSE_RELEASED && mouse.button == BACK_BUTTON &&
                        javax.swing.SwingUtilities.getWindowAncestor(mouse.component) == window
                    ) {
                        DesktopShortcuts.mouseBack()
                    }
                }
                Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
            }

            CompositionLocalProvider(LocalWindowFullscreen provides fullscreen) {
                App()
            }

            if (confirmClose) {
                com.daview.app.data.ModalMarker()
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
 * and hands it to AWT as pixels. So this is 1280x800 physical pixels on every
 * machine, and the app's own layout sees 1280 / display-scaling dp — which is
 * why the size is kept modest enough to fit a 1366x768 laptop after the saved
 * value has been read back.
 */
private val DEFAULT_SIZE = DpSize(1280.dp, 800.dp)
private const val MIN_WIDTH = 480
private const val MIN_HEIGHT = 640

/** AWT's number for the mouse's "back" side button on Windows and Linux. */
private const val BACK_BUTTON = 4

private const val KEY_WIDTH = "window.width"
private const val KEY_HEIGHT = "window.height"
private const val KEY_X = "window.x"
private const val KEY_Y = "window.y"
private const val KEY_MAXIMIZED = "window.maximized"

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
        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull()
    val maxWidth = screen?.width?.toFloat() ?: width
    val maxHeight = screen?.height?.toFloat() ?: height
    return DpSize(width.coerceAtMost(maxWidth).dp, height.coerceAtMost(maxHeight).dp)
}

/**
 * Where the window was, as long as enough of its title bar would still land on
 * one of the screens attached now — a monitor unplugged since must not leave
 * the window somewhere nobody can reach it.
 */
private fun SettingsStore.savedPosition(): WindowPosition? {
    val x = getString(KEY_X)?.toFloatOrNull() ?: return null
    val y = getString(KEY_Y)?.toFloatOrNull() ?: return null
    val titleBar = Rectangle(x.toInt(), y.toInt(), 200, 40)
    val visible = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.intersects(titleBar) }
    }.getOrDefault(false)
    return if (visible) WindowPosition(x.dp, y.dp) else null
}

/**
 * Keeps size, place and maximised state across launches. A full-screen window's
 * size is not its own, so what it had before going full screen is kept instead.
 */
private fun SettingsStore.remember(state: WindowState, beforeFullscreen: WindowPlacement) {
    val placement = if (state.placement == WindowPlacement.Fullscreen) beforeFullscreen else state.placement
    putString(KEY_MAXIMIZED, if (placement == WindowPlacement.Maximized) "1" else "0")
    if (state.placement != WindowPlacement.Floating) return
    putString(KEY_WIDTH, state.size.width.value.toString())
    putString(KEY_HEIGHT, state.size.height.value.toString())
    (state.position as? WindowPosition.Absolute)?.let {
        putString(KEY_X, it.x.value.toString())
        putString(KEY_Y, it.y.value.toString())
    }
}
