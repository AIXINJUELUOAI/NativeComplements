package dev.local.sunwindowdemo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.ToggleRow

@Composable
fun WindowMenuScreen(controller: WindowController, bottomInset: Dp) {
    val colors = MaterialTheme.colorScheme
    val palette = intArrayOf(colors.surface.toArgb(), colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb(), colors.primary.toArgb())
    val bottom = with(LocalDensity.current) { bottomInset.roundToPx() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SettingsGroup {
            ToggleRow("启用小窗", "角落菜单与长侧滑", controller.preferences.enabled) { controller.toggleControl() }
        }
        Spacer(Modifier.height(8.dp))
        if (!controller.catalogLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        else key(controller, colors) {
            AndroidView(
                factory = { controller.menuView(it, palette) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                update = { controller.menuInsets(it, bottom) },
                onRelease = controller::releaseMenu,
            )
        }
    }
}
