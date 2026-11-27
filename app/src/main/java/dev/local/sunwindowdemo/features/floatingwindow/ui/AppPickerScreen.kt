package dev.local.sunwindowdemo.features.floatingwindow.ui

import android.content.pm.ApplicationInfo
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

/** Shared picker for gesture suppression and target-window exclusions; the two lists stay separate. */
@Composable
fun AppPickerScreen(controller: WindowController, bottomInset: Dp = 0.dp, windowExclusions: Boolean = false, showSystemApps: Boolean = false) {
    val excluded = if (windowExclusions) controller.preferences.windowExcludedPackages else controller.preferences.blocked
    val save: (Set<String>) -> Unit = if (windowExclusions) controller::windowExcludedPackages else controller::blocked
    val context = LocalContext.current
    val systemApps by produceState<List<LaunchableApp>?>(null, windowExclusions, showSystemApps) {
        value = if (windowExclusions && showSystemApps) withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName &&
                    it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .mapNotNull { info ->
                    runCatching { LaunchableApp(info.packageName, info.loadLabel(context.packageManager).toString(),
                        info.loadIcon(context.packageManager)) }.getOrNull()
                }
        } else emptyList()
    }
    val catalog = remember(controller.apps, systemApps, windowExclusions, showSystemApps) {
        if (!(windowExclusions && showSystemApps)) controller.apps else (controller.apps + systemApps.orEmpty()).distinctBy { it.packageName }
            .sortedWith(compareBy<LaunchableApp> { dev.local.sunwindowdemo.AppCatalog.sectionForLabel(it.label) }
                .thenBy { it.label }.thenBy { it.packageName })
    }
    dev.local.sunwindowdemo.features.gamemode.AppListPicker(catalog, "已选择 ${excluded.size} 个应用", bottomInset,
        modifier = Modifier.widthIn(max = 680.dp), loaded = controller.catalogLoaded && (!(windowExclusions && showSystemApps) || systemApps != null)) { app ->
        val checked = app.packageName in excluded
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().clickable {
                save(if (checked) excluded - app.packageName else excluded + app.packageName)
            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(app)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked, { save(if (checked) excluded - app.packageName else excluded + app.packageName) },
                    modifier = Modifier.semantics { contentDescription = app.label })
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
