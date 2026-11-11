package dev.local.sunwindowdemo.features.floatingwindow.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun AppPickerScreen(controller: WindowController, blocked: Boolean, bottomInset: Dp = 0.dp) {
    var query by rememberSaveable { mutableStateOf("") }
    val selected = controller.preferences.apps
    val excluded = controller.preferences.blocked
    val catalog = controller.apps
    val visible = remember(query, catalog) { catalog.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    placeholder = { Text("搜索应用或包名") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(50), singleLine = true)
            }
            if (!blocked && query.isBlank()) {
                item { GroupTitle("菜单顺序 · ${selected.size} 项") }
                if (selected.isEmpty()) item { Hint("从下方选择快捷应用，固定的全部应用入口始终保留。") }
                items(selected, key = { "selected:$it" }) { pkg ->
                    val app = catalog.firstOrNull { it.packageName == pkg }
                    val label = app?.label ?: pkg
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppIcon(app)
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                val index = selected.indexOf(pkg)
                                TextButton(enabled = index > 0, onClick = {
                                    val next = selected.toMutableList(); java.util.Collections.swap(next, index, index - 1); controller.selected(next)
                                }, modifier = Modifier.semantics { contentDescription = "上移 ${label}" }) { Text("上移") }
                                TextButton(enabled = index < selected.lastIndex, onClick = {
                                    val next = selected.toMutableList(); java.util.Collections.swap(next, index, index + 1); controller.selected(next)
                                }, modifier = Modifier.semantics { contentDescription = "下移 ${label}" }) { Text("下移") }
                                TextButton(onClick = { controller.selected(selected - pkg) }, modifier = Modifier.semantics { contentDescription = "移除 ${label}" }) { Text("移除") }
                            }
                        }
                    }
                }
            }
            item { GroupTitle(if (blocked) "已禁用 ${excluded.size} 个应用" else "全部应用") }
            if (!controller.catalogLoaded) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            else if (visible.isEmpty()) item { Hint(if (query.isBlank()) "未找到可启动的应用" else "没有匹配的应用") }
            items(visible, key = { "app:${it.packageName}" }) { app ->
                val checked = if (blocked) app.packageName in excluded else app.packageName in selected
                val enabled = blocked || checked || selected.size < WindowPreferenceStore.MAX_TARGETS
                fun change() {
                    if (blocked) controller.blocked(if (checked) excluded - app.packageName else excluded + app.packageName)
                    else if (enabled) controller.selected(if (checked) selected - app.packageName else selected + app.packageName)
                }
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { change() }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(app)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked, { change() }, enabled = enabled, modifier = Modifier.semantics { contentDescription = app.label })
                    }
                }
            }
            item { Hint(if (blocked) "选中后，这些应用处于前台时不触发新的手势唤起。更改立即保存。" else "菜单顶部可保存多个应用；扇区显示前 ${controller.preferences.fanCount} 项。可在上方调整顺序，点击后直接打开应用小窗。") }
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
