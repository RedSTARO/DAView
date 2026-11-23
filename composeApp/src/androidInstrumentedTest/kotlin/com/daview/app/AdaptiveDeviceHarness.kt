package com.daview.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.daview.app.ui.AppFold
import com.daview.app.ui.AppWindowInfo
import com.daview.app.ui.FoldAxis
import com.daview.app.ui.FoldPosture
import com.daview.app.ui.LocalAppWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.floor
import kotlin.math.roundToInt

/** Sizes are usable dp, excluding the device's real system bars and cutout. */
internal enum class AdaptiveDeviceWindow(
    val width: Int,
    val height: Int,
    val axis: FoldAxis? = null,
    val hingeAt: Int = 0,
    val multiWindow: Boolean = false
) {
    SMALL(width = 336, height = 700),
    LARGE_PORTRAIT(width = 680, height = 940),
    LARGE_LANDSCAPE(width = 960, height = 624),
    ROOMY_DETAIL(width = 1080, height = 720),
    BOOK(width = 984, height = 708, axis = FoldAxis.Vertical, hingeAt = 480),
    TABLETOP(width = 696, height = 800, axis = FoldAxis.Horizontal, hingeAt = 300),
    SPLIT(width = 384, height = 508, multiWindow = true),
    TINY_PLAYBACK_SPLIT(width = 232, height = 192, multiWindow = true)
}

internal data class AdaptiveFrame(
    val profile: AdaptiveDeviceWindow,
    val physicalSize: String,
    val physicalDensity: Float,
    val density: Float,
    val fontScale: Float,
    val shell: Rect,
    val usable: Rect,
    val hinge: Rect?
) {
    fun describe(): String {
        val fit = if (physicalDensity == density) "native" else "fitted"
        return "${profile.name} ${profile.width}x${profile.height}dp physical=${physicalSize}px " +
            "fit=$fit density=$density/$physicalDensity fontScale=$fontScale " +
            "shell=${shell.toShortString()} safe=${usable.toShortString()} hinge=${hinge?.toShortString() ?: "none"}"
    }
}

/** Raw runner status and logcat use the same stable scenario IDs. There is no skip path. */
internal class AdaptiveMatrixReceipt(private val instrumentation: Instrumentation, private val id: String) {
    fun emit(status: String, detail: String = "") {
        val value = "$id:$status"
        val line = "DAVIEW_MATRIX $value${if (detail.isBlank()) "" else " $detail"}"
        Log.i("DAVIEW_MATRIX", line)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("daview_matrix", value)
            putString("stream", "\n$line\n")
        })
    }
}

/**
 * One running MainActivity, one real App composition. All controls and accessibility nodes are production UI.
 * The host changes only measurement, effective density when necessary, and the window/posture local. Fitting
 * a logical window never lays out oversized content and then crops it to the physical screen. This is not an
 * OS split-screen, Jetpack WindowManager, physical fold, IME, or touch-target-size test; measure those separately.
 */
internal class AdaptiveDeviceHarness private constructor(
    private val activity: ActivityScenario<MainActivity>,
    initial: AdaptiveDeviceWindow,
    private val receipt: AdaptiveMatrixReceipt
) {
    private val requested = mutableStateOf(initial)
    private val arguments = InstrumentationRegistry.getArguments()
    private val fitMode = (arguments.getString("daviewFit") ?: "fit").also {
        require(it == "fit" || it == "native") { "-e daviewFit must be fit or native" }
    }
    private val fontScale = (arguments.getString("daviewFontScale") ?: "1").let {
        requireNotNull(it.toFloatOrNull()?.takeIf { value -> value in 0.8f..2f }) {
            "-e daviewFontScale must be between 0.8 and 2"
        }
    }
    @Volatile private var measured: AdaptiveFrame? = null
    private var hostFailure: String? = null // UI thread only
    val frame: AdaptiveFrame get() = checkNotNull(measured) { "No measured device window" }

    private fun mount() {
        activity.onActivity { it.setContent { Host() } }
        settled(requested.value)
    }

    internal fun onActivity(block: (MainActivity) -> Unit) { activity.onActivity { block(it) } }

    /** Change geometry without resetting navigation, scroll positions, focus or the app composition. */
    fun resize(profile: AdaptiveDeviceWindow): AdaptiveFrame {
        activity.onActivity { requested.value = profile }
        return settled(profile)
    }

    private fun settled(profile: AdaptiveDeviceWindow): AdaptiveFrame {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            // SideEffect confirms updated inputs; two dispatched frames also let measurement, positioning,
            // and a layout-driven recomposition complete before reading the accessibility tree.
            val painted = CountDownLatch(1)
            activity.onActivity { current ->
                hostFailure?.let { throw AssertionError(it) }
                val decor = current.window.decorView
                decor.postOnAnimation { decor.postOnAnimation { painted.countDown() } }
            }
            check(painted.await(3, TimeUnit.SECONDS)) { "No UI frame after requesting ${profile.name}" }
            measured?.takeIf { it.profile == profile }?.let {
                receipt.emit("FRAME", it.describe())
                return it
            }
        }
        throw AssertionError("${profile.name} never measured (last=${measured?.describe()})")
    }

    @Composable
    private fun Host() {
        val origins = remember { mutableStateOf<Origins?>(null) }
        val real = LocalDensity.current
        val system = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val left = system.getLeft(real, LayoutDirection.Ltr)
        val top = system.getTop(real)
        val right = system.getRight(real, LayoutDirection.Ltr)
        val bottom = system.getBottom(real)
        val profile = requested.value
        BoxWithConstraints(
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val screen = coordinates.positionOnScreen()
                val window = coordinates.positionInWindow()
                if (screen.x.isFinite() && screen.y.isFinite() && window.x.isFinite() && window.y.isFinite()) {
                    origins.value = Origins(screen, window)
                }
            }
        ) {
            val physicalW = constraints.maxWidth
            val physicalH = constraints.maxHeight
            val availableW = physicalW - left - right
            val availableH = physicalH - top - bottom
            val origin = origins.value
            val native = (profile.width * real.density).roundToInt() <= availableW &&
                (profile.height * real.density).roundToInt() <= availableH
            if (fitMode == "native" && !native && origin != null) {
                SideEffect {
                    hostFailure = "${profile.name} requires ${profile.width}x${profile.height} usable dp at native " +
                        "density ${real.density}, but only ${availableW}x${availableH}px is available. " +
                        "Use a larger Android window or -e daviewFit fit."
                }
            } else if (availableW > 0 && availableH > 0 && origin != null) {
                val density = if (native) real.density else minOf(
                    real.density,
                    availableW.toFloat() / profile.width,
                    availableH.toFloat() / profile.height
                )
                val safeW = (profile.width * density).roundToInt().coerceAtMost(availableW)
                val safeH = (profile.height * density).roundToInt().coerceAtMost(availableH)
                val width = safeW + left + right
                val height = safeH + top + bottom
                val shell = Rect(0, 0, width, height)
                val safe = Rect(left, top, left + safeW, top + safeH)
                val hinge = when (profile.axis) {
                    FoldAxis.Vertical -> {
                        val edge = left + (profile.hingeAt * density).roundToInt()
                        Rect(edge, top, edge + (24 * density).roundToInt(), top + safeH)
                    }
                    FoldAxis.Horizontal -> {
                        val edge = top + (profile.hingeAt * density).roundToInt()
                        Rect(left, edge, left + safeW, edge + (24 * density).roundToInt())
                    }
                    null -> null
                }
                val fold = if (hinge != null && profile.axis != null) {
                    AppFold(
                        left = ((origin.window.x + hinge.left) / density).dp,
                        top = ((origin.window.y + hinge.top) / density).dp,
                        right = ((origin.window.x + hinge.right) / density).dp,
                        bottom = ((origin.window.y + hinge.bottom) / density).dp,
                        axis = profile.axis,
                        posture = FoldPosture.HalfOpened,
                        isSeparating = true,
                        isOccluding = true
                    )
                } else null
                val screen = origin.screen
                fun Rect.onScreen() = Rect(this).apply { offset(screen.x.roundToInt(), screen.y.roundToInt()) }
                val frame = AdaptiveFrame(
                    profile, "${physicalW}x$physicalH", real.density, density, fontScale,
                    shell.onScreen(), safe.onScreen(), hinge?.let { it.onScreen() }
                )
                SideEffect {
                    hostFailure = null
                    measured = frame
                }

                // Absolute top-left placement matches the absolute window coordinates fed to AppFold.
                // The app consumes the same Android inset pixels as it would without the host.
                Layout(content = {
                    CompositionLocalProvider(
                        LocalAppWindowInfo provides AppWindowInfo(fold = fold, isInMultiWindow = profile.multiWindow),
                        LocalDensity provides Density(density, fontScale),
                        LocalLayoutDirection provides LayoutDirection.Ltr
                    ) {
                        Box(Modifier.fillMaxSize().clipToBounds()) { App() }
                    }
                }) { children, outer ->
                    val child = children.single().measure(Constraints.fixed(width, height))
                    layout(outer.constrainWidth(width), outer.constrainHeight(height)) { child.place(0, 0) }
                }
            }
        }
    }

    private data class Origins(val screen: Offset, val window: Offset)

    val ui: AdaptiveDeviceSemantics = AdaptiveDeviceSemantics()

    fun assertUsable(label: String, node: AccessibilityNodeInfo) = assertUsable(label, node.screenBounds())

    fun assertUsable(label: String, bounds: Rect) {
        val geometry = frame
        // Android and Compose can round a dp/pixel edge in opposite directions.
        val safe = Rect(geometry.usable).apply { inset(-1, -1) }
        val minExtent = floor(4 * geometry.density).toInt().coerceAtLeast(1)
        check(bounds.width() >= minExtent && bounds.height() >= minExtent && safe.contains(bounds)) {
            "$label is clipped or outside the usable window: ${bounds.toShortString()}, ${geometry.describe()}"
        }
        val hinge = geometry.hinge ?: return
        check(!Rect.intersects(bounds, hinge)) {
            "$label intersects the occluding hinge: ${bounds.toShortString()}, ${geometry.describe()}"
        }
    }

    companion object {
        fun run(profile: AdaptiveDeviceWindow, receipt: AdaptiveMatrixReceipt, block: AdaptiveDeviceHarness.() -> Unit) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                AdaptiveDeviceHarness(scenario, profile, receipt).apply { mount(); block() }
            }
        }
    }
}

internal typealias DeviceNodeSelector = (AccessibilityNodeInfo) -> Boolean

/** Accessible labels/actions first; task-specific tests can add a predicate without production test tags. */
internal class AdaptiveDeviceSemantics {
    private val automation by lazy {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    fun awaitVisible(label: String): AccessibilityNodeInfo = awaitVisible("label '$label'") { it.hasLabel(label) }

    fun awaitVisible(description: String, matcher: DeviceNodeSelector): AccessibilityNodeInfo =
        await(description) { findVisible(matcher) }

    fun findVisible(matcher: DeviceNodeSelector): AccessibilityNodeInfo? =
        visibleNodes().firstOrNull { matcher(it) && it.refresh() && it.isVisibleToUser && matcher(it) }

    fun awaitAction(label: String): AccessibilityNodeInfo = awaitAction("action '$label'") { it.hasLabel(label) }

    fun awaitAction(description: String, matcher: DeviceNodeSelector): AccessibilityNodeInfo =
        await(description) { findAction(matcher) }

    fun findAction(matcher: DeviceNodeSelector): AccessibilityNodeInfo? = visibleNodes().asSequence()
        .filter(matcher).mapNotNull { it.clickableAncestor() }
        .firstOrNull { it.refresh() && it.isVisibleToUser && it.isEnabled && it.isClickable }

    fun click(label: String) = click(awaitAction(label), label)

    fun click(node: AccessibilityNodeInfo, description: String) {
        check(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Android rejected click on '$description' at ${node.screenBounds().toShortString()}\n${describe()}"
        }
    }

    /** Scrolls a real list until its semantics expose the target. Scope traversal with [scroller] or [touchRegion]. */
    fun reveal(
        description: String,
        maxScrolls: Int = 24,
        forward: Boolean = true,
        scroller: DeviceNodeSelector = { true },
        touchRegion: Rect? = null,
        matcher: DeviceNodeSelector
    ): AccessibilityNodeInfo {
        val action = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        var issued = 0
        repeat(maxScrolls + 1) { index ->
            findVisible(matcher)?.let { return it }
            if (index == maxScrolls) return@repeat
            val scrolled = if (touchRegion != null) {
                // Some devices/Compose versions omit directional AX actions for lazy grids. Stay strictly
                // inside the measured list (never on the rail, filters or system bars) for gesture input.
                swipeInside(touchRegion, forward)
                true
            } else {
                val target = visibleNodes().filter { n -> scroller(n) && n.actionList.any { it.id == action } }
                    .maxByOrNull { it.screenBounds().let { b -> b.width().toLong() * b.height() } }
                target != null && target.refresh() && target.performAction(action)
            }
            if (scrolled) {
                issued++
                // Compose sends a burst of subtree/scroll events while a page settles. Targets, not
                // this quiet period or an accepted action, are the assertion.
                try { automation.waitForIdle(160, 1_200) } catch (_: TimeoutException) { }
            }
        }
        return awaitVisible("$description after $issued Android scrolls (limit $maxScrolls)", matcher)
    }

    private fun swipeInside(region: Rect, forward: Boolean) {
        check(region.width() >= 24 && region.height() >= 80) { "Scroll region is too small: ${region.toShortString()}" }
        val x = region.left + region.width() * 0.55f
        val top = region.top + region.height() * 0.14f
        val bottom = region.bottom - region.height() * 0.14f
        val start = if (forward) bottom else top
        val end = if (forward) top else bottom
        val command = "input touchscreen swipe ${x.roundToInt()} ${start.roundToInt()} ${x.roundToInt()} ${end.roundToInt()} 460"
        // Android's input command fills the virtual touchscreen's event/device fields. A bare
        // injected MotionEvent could be accepted but ignored by the lazy grid on some images.
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        check(output.isBlank()) { "Android rejected scroll input in ${region.toShortString()}: $output" }
    }

    fun visibleNodes(): List<AccessibilityNodeInfo> {
        // Moving an existing keyed pane does not change its text/semantics. Don't read a
        // previous placement from Android's accessibility snapshot after a window reflow.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != AdaptiveCatalogueFixture.PACKAGE) return emptyList()
        return buildList {
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && !node.screenBounds().isEmpty) add(node)
                repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
            }
            visit(root)
        }
    }

    fun describe(): String = visibleNodes().mapNotNull { node ->
        val title = node.text ?: node.contentDescription ?: node.viewIdResourceName ?: return@mapNotNull null
        "${title.toString().replace('\n', ' ').take(100)}@${node.screenBounds().toShortString()}"
    }.distinct().take(40).joinToString("\n").ifEmpty { "No visible test-app nodes (active window may be elsewhere)." }

    fun <T : Any> await(description: String, condition: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        do {
            condition()?.let { return it }
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description\n${describe()}")
    }
}

private const val WAIT_MS = 12_000L

internal fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)

internal fun AccessibilityNodeInfo.hasLabel(label: String): Boolean =
    text?.lineSequence()?.any { it == label } == true || contentDescription?.lineSequence()?.any { it == label } == true

internal fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? =
    generateSequence(this) { it.parent }.firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }

internal fun AccessibilityNodeInfo.isDetailHeading(label: String): Boolean = hasLabel(label) &&
    !isEditable && if (Build.VERSION.SDK_INT >= 28) isHeading else clickableAncestor() == null
