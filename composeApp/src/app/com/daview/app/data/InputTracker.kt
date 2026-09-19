package com.daview.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * What the keyboard is busy with, for the shortcuts that act on the window.
 *
 * The window sees a key before whatever has focus does, and it used to take
 * Backspace as "go back" everywhere except two whole screens. In any other text
 * field — the library's own search box, a dialog on the detail page — deleting a
 * character left the page instead, and a dialog went with it, taking whatever
 * had been typed. So the text fields and dialogs say when they are there.
 */
object InputTracker {

    private var textFields = 0
    private var modals = 0

    /** A text field has focus: keys that edit text are its to take. */
    val typing: Boolean get() = textFields > 0

    /** A dialog is open: it owns the keyboard, Escape included. */
    val modalOpen: Boolean get() = modals > 0

    internal fun textFocusChanged(focused: Boolean) {
        textFields = (textFields + if (focused) 1 else -1).coerceAtLeast(0)
    }

    internal fun modalOpened() {
        modals++
    }

    internal fun modalClosed() {
        modals = (modals - 1).coerceAtLeast(0)
    }

    private val escapeHandlers = ArrayList<() -> Boolean>()

    /**
     * The innermost thing that wants Escape — an open search box — gets it
     * first; it returns false when it has nothing to close.
     */
    fun handleEscape(): Boolean = escapeHandlers.asReversed().any { it() }

    internal fun addEscapeHandler(handler: () -> Boolean) {
        escapeHandlers += handler
    }

    internal fun removeEscapeHandler(handler: () -> Boolean) {
        escapeHandlers -= handler
    }
}

/** Marks a text field, so window shortcuts leave its keys alone while it has focus. */
fun Modifier.tracksTextInput(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (focused) InputTracker.textFocusChanged(false) }
    }
    onFocusChanged { state ->
        if (state.isFocused != focused) {
            focused = state.isFocused
            InputTracker.textFocusChanged(state.isFocused)
        }
    }
}

/** Placed inside a dialog: while it is open, window shortcuts stay out of its way. */
@Composable
fun ModalMarker() {
    DisposableEffect(Unit) {
        InputTracker.modalOpened()
        onDispose { InputTracker.modalClosed() }
    }
}

/** Offers [onEscape] to the Escape key while [enabled]. */
@Composable
fun OnEscape(enabled: Boolean, onEscape: () -> Unit) {
    val latest by rememberUpdatedState(onEscape)
    DisposableEffect(enabled) {
        val handler: () -> Boolean = {
            if (enabled) {
                latest()
                true
            } else false
        }
        InputTracker.addEscapeHandler(handler)
        onDispose { InputTracker.removeEscapeHandler(handler) }
    }
}
