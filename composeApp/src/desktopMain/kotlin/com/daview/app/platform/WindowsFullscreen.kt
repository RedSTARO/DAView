package com.daview.app.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import javax.swing.SwingUtilities

/** Borderless fullscreen on the existing HWND, without AWT's exclusive-display ownership. */
internal class WindowsFullscreen(private val window: Window) : AutoCloseable {
    private data class Saved(val style: Int, val placement: NativePlacement, val visible: Boolean)
    private var saved: Saved? = null

    fun enter() {
        check(SwingUtilities.isEventDispatchThread())
        if (saved != null) return
        check(window.isDisplayable) { "The playback window has no native peer" }
        val handle = Native.getWindowPointer(window)
        val placement = NativePlacement()
        checkCall(user32.GetWindowPlacement(handle, placement), "GetWindowPlacement")
        val original = Saved(user32.GetWindowLongW(handle, GWL_STYLE), placement, window.isVisible)
        val monitor = user32.MonitorFromWindow(handle, MONITOR_DEFAULTTONEAREST)
            ?: error("The playback display is unavailable")
        val info = NativeMonitorInfo()
        checkCall(user32.GetMonitorInfoW(monitor, info), "GetMonitorInfo")
        try {
            // Undo maximization before sizing to the *monitor*, not its work
            // area. WINDOWPLACEMENT retains the normal rectangle for exit.
            if (original.style and WS_MAXIMIZE != 0) user32.ShowWindow(handle, SW_RESTORE)
            setStyle(handle, original.style and (WS_OVERLAPPEDWINDOW or WS_MAXIMIZE).inv())
            checkCall(user32.SetWindowPos(
                handle, null, info.left, info.top, info.right - info.left, info.bottom - info.top,
                SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOACTIVATE
            ), "SetWindowPos")
            saved = original
        } catch (failure: Throwable) {
            runCatching { restore(handle, original) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        val original = saved ?: return
        if (window.isDisplayable) restore(Native.getWindowPointer(window), original)
        saved = null
    }

    private fun restore(handle: Pointer, original: Saved) {
        setStyle(handle, original.style)
        // A hidden window must remain hidden (also useful for native smoke tests).
        if (!original.visible) original.placement.showCmd = SW_HIDE
        checkCall(user32.SetWindowPlacement(handle, original.placement), "SetWindowPlacement")
        checkCall(user32.SetWindowPos(
            handle, null, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE
        ), "SetWindowPos")
    }

    private fun setStyle(handle: Pointer, style: Int) {
        Native.setLastError(0)
        val previous = user32.SetWindowLongW(handle, GWL_STYLE, style)
        check(previous != 0 || Native.getLastError() == 0) { "SetWindowLong failed: ${Native.getLastError()}" }
    }

    private fun checkCall(result: Int, operation: String) {
        check(result != 0) { "$operation failed: ${Native.getLastError()}" }
    }

    private companion object {
        val user32: FullscreenUser32 by lazy { Native.load("user32", FullscreenUser32::class.java) }
        const val GWL_STYLE = -16
        const val WS_OVERLAPPEDWINDOW = 0x00CF0000
        const val WS_MAXIMIZE = 0x01000000
        const val SW_HIDE = 0
        const val SW_RESTORE = 9
        const val MONITOR_DEFAULTTONEAREST = 2
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
    }
}

internal interface FullscreenUser32 : StdCallLibrary {
    fun GetWindowLongW(window: Pointer, index: Int): Int
    fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
    fun GetWindowPlacement(window: Pointer, placement: NativePlacement): Int
    fun SetWindowPlacement(window: Pointer, placement: NativePlacement): Int
    fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Int
    fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
    fun GetMonitorInfoW(monitor: Pointer, info: NativeMonitorInfo): Int
    fun ShowWindow(window: Pointer, command: Int): Int
}

@Structure.FieldOrder("length", "flags", "showCmd", "minX", "minY", "maxX", "maxY", "left", "top", "right", "bottom")
internal class NativePlacement : Structure() {
    @JvmField var length = 0
    @JvmField var flags = 0
    @JvmField var showCmd = 0
    @JvmField var minX = 0
    @JvmField var minY = 0
    @JvmField var maxX = 0
    @JvmField var maxY = 0
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
    init { length = size() }
}

@Structure.FieldOrder("length", "left", "top", "right", "bottom", "workLeft", "workTop", "workRight", "workBottom", "flags")
internal class NativeMonitorInfo : Structure() {
    @JvmField var length = 0
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
    @JvmField var workLeft = 0
    @JvmField var workTop = 0
    @JvmField var workRight = 0
    @JvmField var workBottom = 0
    @JvmField var flags = 0
    init { length = size() }
}
