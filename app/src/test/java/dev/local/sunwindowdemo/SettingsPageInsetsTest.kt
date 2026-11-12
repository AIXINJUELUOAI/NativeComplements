package dev.local.sunwindowdemo

import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class SettingsPageInsetsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var view: View

    private fun showPage() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            view = LocalView.current
            ComplementsTheme(dynamic = false) {
                SettingsPage(title = "Insets") { bottomInset ->
                    LazyColumn(Modifier.fillMaxSize().testTag("page-content"), contentPadding = PaddingValues(bottom = bottomInset)) {
                        items(20) { index -> Box(Modifier.fillMaxWidth().height(80.dp).testTag("row-$index")) }
                    }
                }
            }
        }
    }

    private fun insets(navigation: Insets, imeBottom: Int = 0) {
        compose.runOnIdle {
            val values = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, values)
        }
    }

    @Test fun scrollingReachesBehindNavigationAndFinalItemStaysAccessible() {
        showPage()
        insets(Insets.of(0, 0, 0, 96))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("page-title-Insets").fetchSemanticsNode().boundsInRoot
        assertEquals(root.bottom, compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        compose.onNodeWithTag("page-content").performScrollToIndex(19)
        assertEquals(root.bottom - 96, compose.onNodeWithTag("row-19").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        insets(Insets.of(0, 0, 0, 96), imeBottom = 384)
        assertEquals(root.bottom - 384, compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        assertEquals(title.top, compose.onNodeWithTag("page-title-Insets").fetchSemanticsNode().boundsInRoot.top, 1f)
        insets(Insets.of(0, 0, 0, 96))
        assertEquals(root.bottom, compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
    }

    @Test @Config(qualifiers = "w915dp-h412dp-land-420dpi")
    fun landscapeContentAvoidsSideNavigation() {
        showPage()
        insets(Insets.NONE)
        val before = compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot
        insets(Insets.of(0, 0, 84, 0))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot
        // Wide pages stay capped at 680dp and centered inside the safe drawing area.
        assertEquals(before.width, content.width, 1f)
        assertEquals(before.center.x - 42, content.center.x, 1f)
        org.junit.Assert.assertTrue(content.right <= root.right - 84)
        assertEquals(root.bottom, content.bottom, 1f)
    }

    @Test fun largeTitleCollapsesAndExpandsWithScrolling() {
        showPage()
        val expanded=compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("page-content").performTouchInput {swipeUp()}
        val collapsed=compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(collapsed < expanded - 40)
        compose.onNodeWithTag("page-content").performScrollToIndex(0)
        compose.onNodeWithTag("page-content").performTouchInput {swipeDown()}
        assertEquals(expanded,compose.onNodeWithTag("page-content").fetchSemanticsNode().boundsInRoot.top,2f)
    }

    @Test fun nativeMenuScrollDispatchDrivesTheLargeTitle() {
        lateinit var embedded: View
        compose.setContent {
            ComplementsTheme(dynamic=false) {
                SettingsPage("Menu",onBack={}) {
                    AndroidView(factory={ context ->
                        val apps=(0..49).map {AppCatalog.App("example.app$it","App $it",null)}
                        AppList.createPage(context,apps,emptyList(),{},{_,done ->done.accept(true)},intArrayOf(0xffeeeeee.toInt(),0xff111111.toInt(),0xff555555.toInt(),0xff336699.toInt())).also {embedded=it}
                    },modifier=Modifier.fillMaxSize().testTag("native-menu"))
                }
            }
        }
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        val expanded=compose.onNodeWithTag("native-menu").fetchSemanticsNode().boundsInRoot.top
        val list=embedded.javaClass.getDeclaredField("list").apply {isAccessible=true}.get(embedded) as android.widget.ListView
        compose.runOnIdle {
            val consumed=IntArray(2)
            // Exercise the real View -> AndroidViewHolder -> M3 dispatch chain.
            org.junit.Assert.assertTrue(list.startNestedScroll(2))
            list.dispatchNestedPreScroll(0,400,consumed,null)
            org.junit.Assert.assertTrue(consumed[1]>0)
        }
        val collapsed=compose.onNodeWithTag("native-menu").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(collapsed < expanded - 40)
        compose.runOnIdle {list.dispatchNestedScroll(0,0,0,-400,null);list.stopNestedScroll()}
        assertEquals(expanded,compose.onNodeWithTag("native-menu").fetchSemanticsNode().boundsInRoot.top,2f)
    }
}
