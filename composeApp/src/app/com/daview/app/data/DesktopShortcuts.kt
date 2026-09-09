package com.daview.app.data

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key

/**
 * Keyboard shortcuts for the desktop window.
 *
 * The window is created outside the composition, so it cannot reach the state
 * the shortcuts act on; the app hands it over here when it starts. Registered
 * rather than passed because the window outlives any one composition.
 */
object DesktopShortcuts {

    @Volatile
    private var state: AppState? = null

    fun bind(appState: AppState) {
        state = appState
    }

    /** True when the key was ours, which stops it reaching the focused control. */
    fun handle(event: KeyEvent): Boolean {
        val current = state ?: return false
        return when {
            // Back, the two ways a desktop application usually spells it.
            event.key == Key.Backspace && !typing() -> current.back()
            event.key == Key.AltLeft -> false
            event.isCtrlPressed && event.key == Key.F -> {
                current.switchTo(Screen.Search)
                true
            }
            event.key == Key.F5 -> {
                current.refreshCurrent()
                true
            }
            else -> false
        }
    }

    /**
     * Backspace belongs to whatever is being typed into, and there is no way to
     * ask the focus system that from here, so the shortcut is only offered on
     * screens with no text field on them.
     */
    private fun typing(): Boolean {
        val current = state?.current
        return current is Screen.Search || current is Screen.Settings
    }
}
