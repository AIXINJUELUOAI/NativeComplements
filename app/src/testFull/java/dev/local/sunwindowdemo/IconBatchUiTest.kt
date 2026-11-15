package dev.local.sunwindowdemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore
import dev.local.sunwindowdemo.features.iconlayers.IconPolicy
import dev.local.sunwindowdemo.navigation.ModuleApp
import dev.local.sunwindowdemo.navigation.Page
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconBatchUiTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun topMenuSelectionCancelAndThreeIndependentModes() = batchFlow()

    @Test @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night-420dpi")
    fun landscapeBatchKeepsActionsReachable() = batchFlow()

    private fun batchFlow() {
        val context = compose.activity
        IconLayerStore.prefs(context).edit().clear().commit()
        val controller = WindowController(context)
        try {
            compose.setContent {
                ComplementsTheme(dynamic = false) {
                    ModuleApp(controller, Page.IconLayers, {}, {})
                }
            }
            compose.waitUntil(10_000) { controller.catalogLoaded }
            compose.runOnIdle {
                ReflectionHelpers.getField<MutableState<List<LaunchableApp>>>(controller, "apps\$delegate").value =
                    listOf(LaunchableApp("test.icons.one", "示例一"), LaunchableApp("test.icons.two", "示例二"))
            }
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("批量设置").performClick()
            compose.onNodeWithText("启用图标适配").assertDoesNotExist()
            compose.onNodeWithText("下一步").assertIsNotEnabled()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithTag("page-title-已选择 2 个应用").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("启用图标适配").assertIsDisplayed()
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("批量设置").performClick()
            compose.onNodeWithText("示例一").performScrollTo().performClick()
            compose.onNodeWithText("下一步").performClick()
            compose.onNodeWithText("应用").assertIsNotEnabled()
            compose.onNodeWithText("算法补全").performScrollTo().performClick()
            compose.onAllNodesWithText("跟随前景").onFirst().performScrollTo().performClick()
            compose.runOnIdle {
                val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
                val roots = ReflectionHelpers.callInstanceMethod<List<View>>(global, "getWindowViews")
                val root = roots.last()
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val orientation = if (root.width > root.height) "landscape" else "portrait"
                File(requireNotNull(System.getProperty("nativecomplements.screenshots")), "icon-batch-$orientation.png")
                    .also { it.parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("应用").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("启用图标适配").fetchSemanticsNodes().isNotEmpty() }
            val policy = IconPolicy.read(context, "test.icons.one")
            assertEquals(IconPolicy.ALGORITHM, policy.color)
            assertEquals(IconPolicy.FOLLOW, policy.monochrome)
            assertEquals(IconPolicy.UNCHANGED, policy.notification)
            assertEquals(IconPolicy.AUTO, IconPolicy.read(context, "test.icons.two").color)
        } finally { compose.runOnIdle { controller.close() } }
    }
}
