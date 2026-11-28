package com.daview.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daview.app.theme.DaViewDarkColors
import com.daview.app.theme.DaViewTheme
import com.daview.app.ui.DetailEpisodeCard
import com.daview.app.ui.DetailEpisodeRow
import com.daview.shared.model.ItemKind
import com.daview.shared.model.MediaItemDto
import com.daview.shared.model.UserDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailEpisodesOnDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun siblingCardKeepsPlayOverTheArtworkAndDetailsSeparate() = withEpisode(Variant.Shelf, 232.dp) { actions ->
        val episode = target()
        val play = play()
        val title = title()
        val menu = menu()
        assertSeparateActions(play, title, menu)
        listOf(play, title, menu).forEach { assertTargetFits(episode, it) }

        val artwork = play.getUnclippedBoundsInRoot()
        val copy = title.getUnclippedBoundsInRoot()
        assertTrue("The thumbnail play target must sit above the title: $artwork, $copy", artwork.bottom <= copy.top)
        val card = episode.getUnclippedBoundsInRoot()
        assertEquals("Artwork and card share the same horizontal centre", centreX(card), centreX(artwork), 1f)
        assertPlayGlyphIsDrawnAtArtworkCentre(play)

        tapActions(play, title, menu, actions)
    }

    @Test
    fun narrowLargeTextShelfKeepsAllTouchTargetsInsideItsCard() = withEpisode(
        Variant.Shelf, 148.dp, fontScale = 2f
    ) { actions ->
        val episode = target()
        val play = play()
        val title = title()
        val menu = menu()
        assertSeparateActions(play, title, menu)
        listOf(play, title, menu).forEach { assertTargetFits(episode, it) }
        assertTrue(play.getUnclippedBoundsInRoot().bottom <= title.getUnclippedBoundsInRoot().top)
        tapActions(play, title, menu, actions)
    }

    @Test
    fun narrowLargeTextRowKeepsPlayDetailsAndOverflowUsable() = withEpisode(
        Variant.CompactRow, 136.dp, fontScale = 2f
    ) { actions ->
        val episode = target()
        val play = play()
        val title = title()
        val menu = menu()
        assertSeparateActions(play, title, menu)
        listOf(play, title, menu).forEach { assertTargetFits(episode, it) }
        tapActions(play, title, menu, actions)
    }

    @Test
    fun roomyRowExposesCurrentEpisodeAndDeterminateWatchProgress() = withEpisode(
        Variant.RoomyRow, 540.dp
    ) { _ ->
        val episode = target()
        val play = play()
        val title = title()
        val menu = menu()
        assertSeparateActions(play, title, menu)
        listOf(play, title, menu).forEach { assertTargetFits(episode, it) }
        title.assert(hasStateDescription("当前剧集"))
        title.assert(hasText("看到 6:00", substring = true))
        compose.onNode(
            inEpisode() and SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)
            ),
            useUnmergedTree = true
        ).assertExists()
    }

    private fun target() = compose.onNodeWithTag(EpisodeTag, useUnmergedTree = true)
    private fun inEpisode() = hasAnyAncestor(hasTestTag(EpisodeTag))

    private fun play() = compose.onNode(
        inEpisode() and hasContentDescription("播放 S02E03") and hasClickAction()
    ).assertHasClickAction()

    private fun title() = compose.onNode(
        inEpisode() and hasText("S02E03 · $EpisodeTitle") and hasClickAction()
    ).assertHasClickAction()

    private fun menu() = compose.onNode(
        inEpisode() and hasContentDescription("剧集操作") and hasClickAction()
    ).assertHasClickAction()

    private fun assertSeparateActions(vararg actions: SemanticsNodeInteraction) {
        assertEquals("Each action must have its own semantics node", actions.size, actions.map { it.fetchSemanticsNode().id }.toSet().size)
    }

    private fun assertTargetFits(episode: SemanticsNodeInteraction, target: SemanticsNodeInteraction) {
        // Inspect the untrimmed layout (clipping must not hide overflow) and the actual hit box.
        val card = episode.getUnclippedBoundsInRoot()
        val bounds = target.getUnclippedBoundsInRoot()
        assertTrue("$bounds sits outside $card", bounds.left >= card.left - 1.dp && bounds.top >= card.top - 1.dp &&
            bounds.right <= card.right + 1.dp && bounds.bottom <= card.bottom + 1.dp)
        val visibleCard = episode.fetchSemanticsNode().boundsInRoot
        val touch = target.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$touch is not wholly within the visible card $visibleCard", contains(visibleCard, touch))
        // The host uses 1 px/dp, so the usual 48 dp Material hit target is measured in pixels here.
        assertTrue("$touch is smaller than a 48 dp hit target", touch.width >= 48f && touch.height >= 48f)
    }

    private fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
        inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f

    private fun centreX(bounds: DpRect) = (bounds.left.value + bounds.right.value) * 0.5f

    private fun assertPlayGlyphIsDrawnAtArtworkCentre(play: SemanticsNodeInteraction) {
        // A correct accessibility hit box alone can miss a displaced or invisible triangle.
        // With the placeholder (no external image) both the glyph and its container are theme colours.
        val image = play.captureToImage()
        val pixels = image.toPixelMap()
        val x = image.width / 2
        val y = image.height / 2
        val colors = DaViewDarkColors
        fun centreHas(color: Color) = (y - 16..y + 16).any { row ->
            (x - 16..x + 16).any { column -> pixels[column, row] == color }
        }
        assertTrue("The play glyph must be drawn near the centre of the thumbnail", centreHas(colors.onPrimary))
        assertTrue("The play glyph needs its contrasting container", centreHas(colors.primary))
    }

    private fun tapActions(
        play: SemanticsNodeInteraction,
        title: SemanticsNodeInteraction,
        menu: SemanticsNodeInteraction,
        actions: List<String>
    ) {
        // Inject taps rather than just invoking OnClick, to catch a misplaced or overlapping hit area.
        play.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("play"), actions.toList()) }
        title.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("play", "detail"), actions.toList()) }
        menu.performTouchInput { click() }
        val command = compose.onNodeWithText(MenuLabel).assertHasClickAction()
        compose.runOnIdle { assertEquals(listOf("play", "detail"), actions.toList()) }
        command.performClick()
        command.assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("play", "detail", "menu"), actions.toList()) }
    }

    private fun withEpisode(variant: Variant, width: Dp, fontScale: Float = 1f, test: (List<String>) -> Unit) {
        val actions = mutableListOf<String>()
        val episode = MediaItemDto(
            id = "episode-fixture", libraryId = "fixture", kind = ItemKind.EPISODE, name = EpisodeTitle,
            parentIndexNumber = 2, indexNumber = 3, runtimeMs = 1_440_000,
            userData = UserDataDto(positionMs = 360_000, playedPercentage = 0.25)
        )
        val menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = { dismiss ->
            DropdownMenuItem(text = { Text(MenuLabel) }, onClick = { actions += "menu"; dismiss() })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalLayoutDirection provides LayoutDirection.Ltr
                    ) {
                        DaViewTheme(darkTheme = true) {
                            // The fixture is a local pane, not a screen: preserve Android's real insets
                            // and fit its width to the available window as the adaptive harness does.
                            val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            BoxWithConstraints(Modifier.windowInsetsPadding(safe).padding(8.dp)) {
                                val available = minOf(width, maxWidth)
                                val modifier = Modifier.width(available).testTag(EpisodeTag)
                                val open: () -> Unit = { actions += "detail" }
                                val play: () -> Unit = { actions += "play" }
                                if (variant == Variant.Shelf) {
                                    LazyRow {
                                        item {
                                            DetailEpisodeCard(
                                                episode, true, null, play, open, modifier, menu
                                            )
                                        }
                                    }
                                } else {
                                    Box(Modifier.width(available)) {
                                        DetailEpisodeRow(
                                            episode = episode, isCurrent = true, isNext = false,
                                            roomy = variant == Variant.RoomyRow, download = null, index = 0, count = 1,
                                            onPlay = play, onOpen = open, modifier = modifier, menu = menu
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            test(actions)
        }
    }

    private enum class Variant { Shelf, CompactRow, RoomyRow }

    private companion object {
        const val EpisodeTag = "detail episode fixture"
        const val EpisodeTitle = "一次很长的夜间旅程与下一站的约定"
        const val MenuLabel = "测试剧集操作"
    }
}
