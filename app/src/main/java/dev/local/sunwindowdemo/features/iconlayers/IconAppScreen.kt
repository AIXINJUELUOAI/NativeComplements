package dev.local.sunwindowdemo.features.iconlayers

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAppScreen(app: LaunchableApp?, bottomInset: Dp = 0.dp) {
    if (app == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) { Hint("应用不可用") }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = remember(app.packageName) { IconLayerStore.read(context) }
    val generation = layer.getString("generation", "")
    var matched by remember(app.packageName, generation) { mutableStateOf(false) }
    LaunchedEffect(app.packageName, generation) {
        matched = withContext(Dispatchers.IO) {
            IconLibrary.packages(context, generation).contains(app.packageName)
        }
    }
    val policy = remember(app.packageName) { IconPolicy.read(context, app.packageName) }
    var revision by remember { mutableIntStateOf(0) }
    var field by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var manualSheet by remember { mutableStateOf(false) }
    fun save() {
        IconPolicy.save(context, app.packageName, policy)
        revision++
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                val selectedField = field
                val id = withContext(Dispatchers.IO) {
                    // ponytail: serialize import through publication; split locks only if throughput matters.
                    synchronized(IconPolicy::class.java) {
                        val imported = IconOverrides.importImage(context, uri)
                        val updated = IconPolicy.read(context, app.packageName)
                        when (selectedField) {
                            0 -> { updated.background = imported; updated.color = IconPolicy.MANUAL }
                            1 -> { updated.foreground = imported; updated.color = IconPolicy.MANUAL }
                            2 -> { updated.monoImage = imported; updated.monochrome = IconPolicy.UPLOAD }
                            3 -> { updated.notificationImage = imported; updated.notification = IconPolicy.UPLOAD }
                        }
                        IconPolicy.save(context, app.packageName, updated)
                        imported
                    }
                }
                when (selectedField) {
                    0 -> { policy.background = id; policy.color = IconPolicy.MANUAL }
                    1 -> { policy.foreground = id; policy.color = IconPolicy.MANUAL }
                    2 -> { policy.monoImage = id; policy.monochrome = IconPolicy.UPLOAD }
                    3 -> { policy.notificationImage = id; policy.notification = IconPolicy.UPLOAD }
                }
                error = ""
                revision++
            } catch (e: Exception) {
                error = e.message ?: "导入图片失败"
            } finally {
                loading = false
            }
        }
    }
    fun upload(which: Int) {
        field = which
        picker.launch(arrayOf("image/*"))
    }
    val entry by produceState<IconAssets.Entry?>(null, app.packageName, generation, revision, matched) {
        value = withContext(Dispatchers.IO) {
            runCatching { IconAssets.resolve(context, app.packageName, policy, if (matched) generation else "", 192, true) }.getOrNull()
        }
    }
    val primary = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val previews by produceState<List<ImageBitmap?>>(listOf(null, null, null), entry, primary, revision) {
        value = withContext(Dispatchers.IO) {
            fun draw(drawable: Drawable?): ImageBitmap? = drawable?.let { IconImages.render(it, 192).asImageBitmap() }
            val current = entry
            listOf(
                draw(if (current != null && current.colorChanged() && !current.nativeColor()) ImportedIcon(context.resources, current.background(), current.foreground(), current.mono()) else context.packageManager.getApplicationIcon(app.packageName)),
                draw(current?.mono()?.let { BitmapDrawable(context.resources, it).apply { setColorFilter(primary, PorterDuff.Mode.SRC_IN) } }),
                draw(current?.notification()?.let { BitmapDrawable(context.resources, it).apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) } }),
            )
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("彩色", "莫奈", "通知").forEachIndexed { index, title ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            color = if (index == 2) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                                previews[index]?.let { Image(it, null, Modifier.size(if (index == 2) 44.dp else 78.dp)) } ?: Text("—")
                            }
                        }
                        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item { GroupTitle("彩色图标") }
        item {
            SettingsGroup {
                ModeRows(listOf("自动", "手动前后景", "算法补全", "不处理"), policy.color, !loading) { choice ->
                    policy.color = choice; save()
                    if (choice == IconPolicy.MANUAL) manualSheet = true
                }
            }
        }
        item { GroupTitle("莫奈图标") }
        item {
            SettingsGroup {
                ModeRows(listOf("跟随前景", "上传单色图片", "不处理"), policy.monochrome, !loading) { choice ->
                    policy.monochrome = choice; save()
                    if (choice == IconPolicy.UPLOAD) upload(2)
                }
            }
        }
        item { GroupTitle("状态栏通知小图标") }
        item {
            SettingsGroup {
                ModeRows(listOf("跟随前景", "上传图片", "不处理"), policy.notification, !loading) { choice ->
                    policy.notification = choice; save()
                    if (choice == IconPolicy.UPLOAD) upload(3)
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
        if (error.isNotBlank()) item { Hint(error) }
    }
    if (manualSheet) {
        ModalBottomSheet(onDismissRequest = { manualSheet = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("手动前后景", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                SettingsGroup {
                    SettingRow("背景图", if (policy.background.isEmpty()) "未选择" else "已选择",
                        onClick = if (loading) null else { { manualSheet = false; upload(0) } })
                    SettingRow("透明前景", if (policy.foreground.isEmpty()) "未选择" else "已选择",
                        onClick = if (loading) null else { { manualSheet = false; upload(1) } })
                }
            }
        }
    }
}

@Composable
private fun ModeRows(labels: List<String>, selected: Int, enabled: Boolean, choose: (Int) -> Unit) {
    labels.forEachIndexed { index, label ->
        SettingRow(label, onClick = { choose(index) }, trailing = {
            RadioButton(selected == index, onClick = null, enabled = enabled)
        })
    }
}
