package dev.local.sunwindowdemo.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp

private val iconCache = LruCache<String, ImageBitmap>(128)

@Composable
fun AppListIcon(app: LaunchableApp) {
    val context = LocalContext.current
    val state = produceState(iconCache.get(app.packageName), app.packageName, app.icon) {
        value = withContext(Dispatchers.IO) {
            iconCache.get(app.packageName) ?: runCatching {
                val drawable = (app.icon ?: context.packageManager.getApplicationIcon(app.packageName))
                    .constantState?.newDrawable()?.mutate()
                    ?: context.packageManager.getApplicationIcon(app.packageName).mutate()
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, 96, 96)
                    drawable.draw(Canvas(it))
                }.asImageBitmap().also { iconCache.put(app.packageName, it) }
            }.getOrNull()
        }
    }
    val bitmap = state.value
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}
