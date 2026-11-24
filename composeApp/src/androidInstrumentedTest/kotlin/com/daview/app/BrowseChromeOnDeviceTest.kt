package com.daview.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daview.app.ui.BrowseHeroBackdrop
import com.daview.app.ui.browseSafeTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The decorative strip may move with the measured hero; the hero's hit area must never follow it there. */
@RunWith(AndroidJUnit4::class)
class BrowseChromeOnDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun heroArtworkTracksScrollButOnlySafeContentCanBeClicked() {
        val list = LazyListState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.fillMaxSize().background(Color.Black).testTag(Fixture)) {
                            Box(Modifier.fillMaxSize().browseSafeTop(24.dp)) {
                                BrowseHeroBackdrop(list, Hero, 24.dp, Modifier.matchParentSize().zIndex(-1f)) {
                                    Box(Modifier.matchParentSize().background(Color.Red))
                                }
                                LazyColumn(Modifier.fillMaxSize().clipToBounds(), state = list) {
                                    heroRow()
                                    items(count = 100) { Box(Modifier.height(24.dp)) }
                                }
                            }
                        }
                    }
                }
            }

            val fixture = compose.onNodeWithTag(Fixture)
            val hero = compose.onNodeWithTag(Hero).assertHasClickAction()
            val top = fixture.fetchSemanticsNode().boundsInRoot.top
            assertEquals(24f, hero.fetchSemanticsNode().boundsInRoot.top - top, 1f)
            fun pixels() = fixture.captureToImage().toPixelMap()
            assertEquals("artwork reaches the top edge", Color.Red, pixels()[8, 2])

            compose.runOnIdle { list.requestScrollToItem(0, 36) }
            assertEquals("artwork still fills the strip while its row is sliding past", Color.Red, pixels()[8, 2])
            assertEquals("artwork shares the live row's measured bottom", Color.Black, pixels()[8, 73])
            assertTrue("the clipped hero hit area never enters the strip", hero.fetchSemanticsNode().boundsInRoot.top >= top + 23f)

            compose.runOnIdle { list.requestScrollToItem(1) }
            assertEquals("no stale artwork once the keyed hero scrolls away", Color.Black, pixels()[8, 2])
            hero.assertDoesNotExist()
        }
    }

    private fun LazyListScope.heroRow() {
        item(key = Hero) {
            Box(Modifier.fillMaxWidth().height(80.dp).clickable {}.testTag(Hero))
        }
    }

    private companion object {
        const val Fixture = "browse chrome fixture"
        const val Hero = "live browse hero"
    }
}
