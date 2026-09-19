package com.daview.app.data

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
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

    @Volatile
    private var playback: PlaybackController? = null

    fun bind(appState: AppState, controller: PlaybackController) {
        state = appState
        playback = controller
    }

    /** What the keys do, for the settings page to list. */
    val reference: List<Pair<String, String>> = listOf(
        "Alt + ← / 鼠标后退键" to "返回上一页",
        "Backspace" to "返回上一页（输入框外）",
        "Esc" to "关闭菜单或库内搜索，或返回上一页；播放时先关列表，再退出全屏",
        "Ctrl + F" to "搜索",
        "F5" to "刷新当前页",
        "F11" to "全屏 / 退出全屏",
        "空格" to "播放 / 暂停（播放器）",
        "← / →" to "后退 / 前进 5 秒（播放器）",
        "↑ / ↓" to "音量 +5 / −5（播放器）",
        "M" to "静音（播放器）",
        "[ / ]" to "减速 / 加速（播放器）",
        "# / J" to "切换音轨 / 字幕（播放器）",
        "PageUp / PageDown" to "上一章 / 下一章（播放器）",
        "< / >" to "上一集 / 下一集（播放器）",
        "Enter" to "跳过片头（播放器，有片头章节时）",
        "F" to "全屏（播放器）",
        "Q" to "结束播放（播放器）"
    )

    /** True when the key was ours, which stops it reaching the focused control. */
    fun handle(event: KeyEvent): Boolean {
        val current = state ?: return false
        return when {
            // Back, the ways a desktop application usually spells it. A dialog
            // owns the keyboard while it is open, and a text field owns the
            // keys that edit text.
            // In a text field Alt+← moves by word (Option+← on a Mac).
            event.isAltPressed && event.key == Key.DirectionLeft ->
                !InputTracker.typing && !InputTracker.modalOpen && back(current)
            // The external player's panel is a page like any other: the keys
            // that leave a page leave it, and the player keeps going.
            (event.key == Key.Escape || event.key == Key.Backspace) &&
                current.current is Screen.Player && playback?.externalPlayerLabel != null ->
                !InputTracker.modalOpen && !InputTracker.typing && run {
                    playback?.leaveExternalPanel()
                    true
                }
            event.key == Key.Backspace ->
                !InputTracker.typing && !InputTracker.modalOpen && current.current !is Screen.Player && back(current)
            event.key == Key.Escape -> when {
                InputTracker.modalOpen || current.current is Screen.Player -> false
                InputTracker.handleEscape() -> true
                else -> back(current)
            }
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

    /** The mouse's own back button, reported by the window. */
    fun mouseBack(): Boolean {
        val current = state ?: return false
        if (InputTracker.modalOpen) return false
        return back(current)
    }

    /**
     * Leaving the player is ending playback; everywhere else it is a step back
     * through the stack.
     */
    private fun back(current: AppState): Boolean {
        if (!current.canGoBack) return false
        val controller = playback
        if (current.current is Screen.Player && controller != null) {
            controller.stopAndLeave()
            return true
        }
        return current.back()
    }
}
