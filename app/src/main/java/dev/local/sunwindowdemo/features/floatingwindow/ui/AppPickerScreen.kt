package dev.local.sunwindowdemo.features.floatingwindow.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.WindowController
import dev.local.sunwindowdemo.core.ui.*

/** The only remaining application picker: apps that suppress new window gestures. */
@Composable
fun AppPickerScreen(controller: WindowController, bottomInset: Dp = 0.dp) {
    val excluded = controller.preferences.blocked
    val catalog = controller.apps
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { GroupTitle("已禁用 ${excluded.size} 个应用") }
            if (!controller.catalogLoaded) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            else if (catalog.isEmpty()) item { Hint("暂无可显示的应用") }
            items(catalog, key = { "app:${it.packageName}" }) { app ->
                val checked = app.packageName in excluded
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().clickable {
                        controller.blocked(if (checked) excluded - app.packageName else excluded + app.packageName)
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(app)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked, { controller.blocked(if (checked) excluded - app.packageName else excluded + app.packageName) },
                            modifier = Modifier.semantics { contentDescription = app.label })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: LaunchableApp?) {
    val bitmap = remember(app?.packageName, app?.icon) {
        app?.icon?.let { drawable ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(0, 0, 96, 96); drawable.draw(Canvas(it)); drawable.bounds = old
            }.asImageBitmap()
        }
    }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}
