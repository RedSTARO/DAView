package com.daview.app.platform

import com.daview.app.player.MpvLibrary
import com.daview.app.player.MpvNative
import com.daview.app.player.MpvPlayer
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsFullscreenTest {
    private fun supported() = assumeTrue(MpvNative.isWindows && !GraphicsEnvironment.isHeadless())
    private val native by lazy { Native.load("user32", FullscreenUser32::class.java) }

    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val result = AtomicReference<Result<T>>()
        SwingUtilities.invokeAndWait { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    private fun placement(frame: JFrame) = NativePlacement().also {
        assertTrue(native.GetWindowPlacement(Native.getWindowPointer(frame), it) != 0)
    }

    private fun rectangle(p: NativePlacement) = listOf(p.left, p.top, p.right, p.bottom)

    @Test
    fun `borderless transition keeps HWND and video surface and restores the original rectangle`() {
        supported()
        edt {
            val frame = JFrame("DAView hidden fullscreen test")
            val canvas = Canvas()
            frame.add(canvas)
            frame.setBounds(80, 90, 640, 480)
            frame.addNotify()
            frame.validate()
            val fullscreen = WindowsFullscreen(frame)
            try {
                val windowHandle = Native.getWindowPointer(frame)
                val surfaceHandle = Native.getComponentPointer(canvas)
                val before = placement(frame)
                val style = native.GetWindowLongW(windowHandle, -16)
                fullscreen.enter()
                fullscreen.enter() // Recomposition must not replace the restore point.
                assertEquals(0, native.GetWindowLongW(windowHandle, -16) and 0x00CF0000)
                assertEquals(windowHandle, Native.getWindowPointer(frame))
                assertEquals(surfaceHandle, Native.getComponentPointer(canvas))
                assertNull(frame.graphicsConfiguration.device.fullScreenWindow, "must not acquire exclusive display ownership")
                assertFalse(frame.isVisible, "the helper must not activate a hidden normal window")
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_LOST_FOCUS))
                assertEquals(0, frame.extendedState and Frame.ICONIFIED)
                fullscreen.close()
                fullscreen.close()
                assertEquals(style, native.GetWindowLongW(windowHandle, -16))
                assertEquals(rectangle(before), rectangle(placement(frame)))
                assertEquals(surfaceHandle, Native.getComponentPointer(canvas))
                assertFalse(frame.isVisible)
            } finally {
                fullscreen.close()
                frame.dispose()
            }
        }
    }

    @Test
    fun `disposing a fullscreen window does not attempt to recreate its peer`() {
        supported()
        edt {
            val frame = JFrame("DAView hidden disposal test")
            frame.setBounds(80, 90, 640, 480)
            frame.addNotify()
            val fullscreen = WindowsFullscreen(frame)
            fullscreen.enter()
            frame.dispose()
            fullscreen.close()
            assertFalse(frame.isDisplayable)
        }
    }

    /** Opt-in because this test briefly presents two windows and transfers real keyboard focus. */
    @Test
    fun `real mpv playback continues after another window takes focus and maximize state restores`() {
        supported()
        assumeTrue("Enable DAVIEW_FULLSCREEN_SMOKE=1 for the visible native playback check", System.getenv("DAVIEW_FULLSCREEN_SMOKE") == "1")
        System.getenv("DAVIEW_TEST_LIBMPV")?.let { MpvNative.overridePath = it }
        assertTrue(MpvNative.available, "the native smoke test needs libmpv: ${MpvNative.loadError}")
        val library = checkNotNull(MpvNative.library())
        val destroyed = CountDownLatch(1)
        val checkedLibrary = object : MpvLibrary by library {
            override fun mpv_terminate_destroy(ctx: Pointer) {
                try { library.mpv_terminate_destroy(ctx) } finally { destroyed.countDown() }
            }
        }
        val loaded = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val player = MpvPlayer(object : MpvPlayer.Listener {
            override fun onFileLoaded() { loaded.countDown() }
            override fun onEndFile(error: String?, reachedEnd: Boolean) {
                if (error != null) failure.set(error)
            }
        }, checkedLibrary)
        val frame = edt { JFrame("DAView fullscreen playback test").apply { setBounds(80, 90, 640, 480) } }
        val canvas = Canvas()
        val other = edt { JFrame("DAView focus transfer test").apply { setBounds(120, 130, 300, 200) } }
        val otherFocused = CountDownLatch(1)
        val fullscreen = WindowsFullscreen(frame)
        try {
            edt {
                frame.add(canvas)
                frame.isVisible = true
                frame.extendedState = Frame.MAXIMIZED_BOTH
                other.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) { otherFocused.countDown() }
                })
            }
            awaitCondition { edt { placement(frame).showCmd == 3 } }
            val before = edt { placement(frame) }
            val surface = edt { Native.getComponentPointer(canvas) }
            player.open(Pointer.nativeValue(surface), MpvPlayer.Config(muted = true))
            player.play("av://lavfi:testsrc2=size=320x180:rate=30")
            assertTrue(loaded.await(15, TimeUnit.SECONDS), "libmpv did not load the synthetic video: ${failure.get()}")
            awaitCondition { (player.positionMs ?: 0) > 200 }
            edt {
                fullscreen.enter()
                assertNull(frame.graphicsConfiguration.device.fullScreenWindow)
                other.isVisible = true
                other.toFront()
                other.requestFocus()
            }
            assertTrue(otherFocused.await(10, TimeUnit.SECONDS), "the second window did not receive focus")
            val position = checkNotNull(player.positionMs)
            awaitCondition { (player.positionMs ?: 0) >= position + 700 }
            assertNull(failure.get())
            assertFalse(player.paused)
            edt {
                assertTrue(other.isFocused)
                assertTrue(frame.isShowing)
                assertEquals(0, frame.extendedState and Frame.ICONIFIED)
                assertEquals(surface, Native.getComponentPointer(canvas))
                fullscreen.close()
            }
            awaitCondition { edt { placement(frame).showCmd == 3 } }
            edt { assertEquals(rectangle(before), rectangle(placement(frame))) }
        } finally {
            player.close()
            assertTrue(destroyed.await(10, TimeUnit.SECONDS), "native player did not release before its window")
            edt { fullscreen.close(); other.dispose(); frame.dispose() }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for native window/playback state" }
            Thread.sleep(20)
        }
    }
}
