package com.daview.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import com.daview.app.theme.DaViewDarkColors
import com.daview.app.theme.DaViewLightColors
import com.daview.app.theme.DaViewTheme
import com.daview.app.ui.HomeSpotlight
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.UserDataDto
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSpotlightOnDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @After
    fun releaseStandaloneLoader() {
        // Full-App tests install their own catalogue-aware singleton later in this process.
        SingletonImageLoader.reset()
    }

    @Test
    fun wideArtworkFillsItsEdgeWithoutPaintingUnderCopyOrOutsideTheCard() = withSpotlight { _, _ ->
        val fixture = compose.onNodeWithTag(Fixture)
        compose.waitUntil(timeoutMillis = 5_000) {
            val image = fixture.captureToImage()
            image.toPixelMap()[image.width - 48, 6] == Artwork
        }
        val image = fixture.captureToImage()
        val pixels = image.toPixelMap()
        assertEquals(DaViewDarkColors.surfaceContainer, pixels[80, 6])
        assertEquals(Artwork, pixels[image.width - 48, image.height - 7])
        assertEquals(DaViewDarkColors.surface, pixels[0, 0])

        val play = compose.onNodeWithText("继续播放").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithText("详情").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val series = compose.onNodeWithText("整部剧").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertEquals("actions share one baseline", play.center.y, details.center.y, 1f)
        assertEquals("actions share one baseline", play.center.y, series.center.y, 1f)
        assertTrue(play.right <= details.left && details.right <= series.left)
    }

    @Test
    fun mediaActionsStayReachableWhenLargeTextNeedsTheOverflow() = withSpotlight(
        Scene(width = 300.dp, fontScale = 2f, short = true, dark = false, art = false)
    ) { selected, _ ->
        val bounds = compose.onNodeWithTag(Fixture).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText(LongTitle).assertHasClickAction()
        val buttons = listOf(compose.onNodeWithText("继续播放"), compose.onNodeWithText("详情"), more())
        buttons.forEach {
            val button = it.assertHasClickAction().fetchSemanticsNode().touchBoundsInRoot
            assertTrue("tap target stays inside the card: $button", button.left >= bounds.left && button.right <= bounds.right)
            assertTrue("minimum touch target: $button", button.width >= 48f && button.height >= 48f)
        }
        compose.onNodeWithText("整部剧").assertDoesNotExist()
        compose.onNodeWithContentDescription("观看进度").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f))
        )
        buttons[0].performClick()
        buttons[1].performClick()
        title.performClick()
        more().performClick()
        compose.onNodeWithText("整部剧").assertHasClickAction().performClick()
        more().performClick()
        compose.onNodeWithText("从头播放").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf("play", "details", "details", "series", "restart"), selected) }
    }

    @Test
    fun unavailableArtworkAndUnknownDurationLeaveNoPlaceholder() = withSpotlight(
        Scene(width = 330.dp, duration = null, dark = false)
    ) { _, scene ->
        val fixture = compose.onNodeWithTag(Fixture)
        compose.waitUntil(timeoutMillis = 5_000) {
            val image = fixture.captureToImage()
            image.toPixelMap()[image.width / 2, 6] == Artwork
        }
        val withArt = fixture.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { scene.value = scene.value.copy(brokenArt = true) }
        compose.waitUntil(timeoutMillis = 5_000) {
            val image = fixture.captureToImage()
            image.height < withArt && image.toPixelMap()[image.width / 2, 6] == DaViewLightColors.surfaceContainer
        }
        compose.onNodeWithText("已看 2:30").assertExists()
        compose.onNodeWithContentDescription("观看进度").assertDoesNotExist()
    }

    private fun more() = compose.onNodeWithContentDescription("更多操作")

    private fun withSpotlight(initial: Scene = Scene(), test: (List<String>, MutableState<Scene>) -> Unit) {
        val scene = mutableStateOf(initial)
        val actions = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val file = File.createTempFile("spotlight", ".png", activity.cacheDir)
                val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.rgb(232, 233, 144))
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                activity.setContent {
                    val current = scene.value
                    DaViewTheme(darkTheme = current.dark) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(1f, current.fontScale),
                            LocalLayoutDirection provides LayoutDirection.Ltr
                        ) {
                            val colors = if (current.dark) DaViewDarkColors else DaViewLightColors
                            Box(Modifier.width(current.width).background(colors.surface).testTag(Fixture)) {
                                HomeSpotlight(
                                    item = MediaItemDto(
                                        "episode", "shows", ItemKind.EPISODE,
                                        seriesName = if (current.fontScale > 1f) LongTitle else "Demo Series",
                                        name = "The opening, but not the ending", parentIndexNumber = 2, indexNumber = 1,
                                        runtimeMs = current.duration, userData = UserDataDto(positionMs = 150_000),
                                        backdropUrl = when {
                                            !current.art -> null
                                            current.brokenArt -> file.resolveSibling("missing-${file.name}").absolutePath
                                            else -> file.absolutePath
                                        }
                                    ),
                                    label = "继续观看", shortPane = current.short,
                                    onPlay = { actions += "play" }, onOpen = { actions += "details" }, onSeries = { actions += "series" }
                                ) { dismiss ->
                                    DropdownMenuItem(text = { Text("从头播放") }, onClick = { dismiss(); actions += "restart" })
                                }
                            }
                        }
                    }
                }
            }
            test(actions, scene)
        }
    }

    private data class Scene(
        val width: Dp = 728.dp,
        val fontScale: Float = 1f,
        val short: Boolean = false,
        val dark: Boolean = true,
        val art: Boolean = true,
        val brokenArt: Boolean = false,
        val duration: Long? = 600_000
    )

    private companion object {
        const val Fixture = "Home spotlight fixture"
        const val LongTitle = "这是一个很长的剧名，用来验证字号放大时仍然可以查看完整详情"
        val Artwork = Color(0xFFE8E990)
    }
}
