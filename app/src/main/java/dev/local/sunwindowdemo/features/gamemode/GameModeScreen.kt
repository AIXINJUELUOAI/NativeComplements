package dev.local.sunwindowdemo.features.gamemode

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.QuickActionCatalog
import dev.local.sunwindowdemo.R
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.features.barrage.BarrageStore

@Composable
private fun gameConfig(): Bundle {
    val context = LocalContext.current
    var config by remember { mutableStateOf(GameModeStore.read(context)) }
    DisposableEffect(context) {
        val prefs = GameModeStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = GameModeStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return config
}

@Composable
fun GameModeScreen(apps: List<LaunchableApp>, loaded: Boolean, bottomInset: Dp = 0.dp,
                   onQuick: () -> Unit = {}, onBarrage: () -> Unit = {}, onCorner: () -> Unit = {}) {
    val context = LocalContext.current
    val config = gameConfig()
    val chosen = config.getStringArrayList("apps").orEmpty().toSet()
    var barrage by remember { mutableStateOf(BarrageStore.read(context)) }
    DisposableEffect(context) {
        val prefs = BarrageStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> barrage = BarrageStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    AppListPicker(availableApps(apps), "游戏适用应用", bottomInset, loaded = loaded, header = {
        SettingsGroup { ToggleRow("启用游戏模式", value = config.getBoolean("enabled")) { GameModeStore.enabled(context, it) } }
        GroupTitle("游戏设置")
        SettingsGroup {
            SettingRow("小白条位置", if (config.getString("side") == GameModeStore.SIDE_RIGHT) "屏幕右侧" else "屏幕左侧",
                onClick = { GameModeStore.side(context, if (config.getString("side") == GameModeStore.SIDE_RIGHT) GameModeStore.SIDE_LEFT else GameModeStore.SIDE_RIGHT) })
            SplitSwitchRow("快捷应用与功能", value = config.getBoolean("panel"), onToggle = { GameModeStore.panel(context, it) }, onDetails = onQuick, leading = { Glyph("apps") })
            SplitSwitchRow("弹幕通知", value = barrage.getBoolean("enabled"), onToggle = { BarrageStore.enabled(context, it) }, onDetails = onBarrage, leading = { Glyph("barrage") })
            SplitSwitchRow("角落防误触", value = config.getBoolean("corner_rejection"), onToggle = { GameModeStore.cornerRejection(context, it) }, onDetails = onCorner, leading = { Glyph("edge") })
        }
    }) { app ->
        AppChoice(app, app.packageName in chosen, { GameModeStore.select(context, app.packageName, it) },
            app.packageName in chosen || chosen.size < GameModeStore.APP_LIMIT)
    }
}

@Composable
fun GameQuickScreen(apps: List<LaunchableApp>, loaded: Boolean, bottomInset: Dp = 0.dp,
                    onAppTabChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val config = gameConfig()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab, onAppTabChanged) { onAppTabChanged(tab == 0) }
    val quickApps = config.getStringArrayList("quick_apps").orEmpty()
    val quick = config.getStringArrayList("quick").orEmpty()
    val catalog = QuickActionCatalog.described().toList()
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("应用") })
            Tab(tab == 1, { tab = 1 }, text = { Text("功能") })
        }
        if (tab == 0) {
            AppListPicker(availableApps(apps).filter { it.packageName !in quickApps }, "添加应用", bottomInset,
                modifier = Modifier.weight(1f), loaded = loaded, header = {
                    GroupTitle("已选应用")
                    SelectedGrid(quickApps, { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg },
                        onRemove = { GameModeStore.quickApp(context, it, false) },
                        onMove = { id, direction -> GameModeStore.moveQuickApp(context, id, direction) }) { pkg ->
                        GameIcon(apps.firstOrNull { it.packageName == pkg } ?: LaunchableApp(pkg, pkg))
                    }
                }) { app -> AppChoice(app, false, { GameModeStore.quickApp(context, app.packageName, it) }, quickApps.size < GameModeStore.APP_LIMIT) }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp + bottomInset),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { GroupTitle("已选功能") }
                item { SelectedGrid(quick, { id -> catalog.firstOrNull { it[0] == id }?.get(1) ?: id },
                    onRemove = { GameModeStore.quick(context, it, false) },
                    onMove = { id, direction -> GameModeStore.moveQuick(context, id, direction) }) { QuickIcon(it) } }
                item { GroupTitle("添加功能") }
                items(catalog.filter { it[0] !in quick }, key = { it[0] }) { action ->
                    SettingRow(action[1], leading = { QuickIcon(action[0]) }, trailing = {
                        Switch(false, { GameModeStore.quick(context, action[0], it) }, enabled = quick.size < GameModeStore.QUICK_LIMIT,
                            modifier = Modifier.semantics { contentDescription = action[1] })
                    })
                }
            }
        }
    }
}

private fun availableApps(apps: List<LaunchableApp>) = apps.filter { it.packageName != "dev.nativecomplements.module" }

@Composable
private fun AppChoice(app: LaunchableApp, checked: Boolean, onSelect: (Boolean) -> Unit, enabled: Boolean = true) {
    SettingRow(app.label, app.packageName, onClick = if (enabled) ({ onSelect(!checked) }) else null,
        leading = { GameIcon(app) }, trailing = { Switch(checked, onSelect, enabled = enabled, modifier = Modifier.semantics { contentDescription = app.label }) })
}

@Composable
private fun SelectedGrid(ids: List<String>, label: (String) -> String, onRemove: (String) -> Unit,
                         onMove: (String, Int) -> Unit, icon: @Composable (String) -> Unit) {
    if (ids.isEmpty()) return
    val state = rememberLazyGridState()
    var dragged by remember { mutableStateOf<String?>(null) }
    var distance by remember { mutableStateOf(Offset.Zero) }
    val currentMove by rememberUpdatedState(onMove)
    LazyVerticalGrid(GridCells.Fixed(4), state = state, userScrollEnabled = dragged == null,
        modifier = Modifier.fillMaxWidth().heightIn(max = 288.dp).height((((ids.size + 3) / 4) * 96).dp)) {
        items(ids, key = { it }) { id ->
            val name = label(id)
            val index = ids.indexOf(id)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.height(96.dp).padding(2.dp).zIndex(if (dragged == id) 1f else 0f)
                    .graphicsLayer { translationX = if (dragged == id) distance.x else 0f; translationY = if (dragged == id) distance.y else 0f; alpha = if (dragged == id) .7f else 1f }
                    .semantics {
                        contentDescription = "$name，点击移除，长按拖动调整顺序"
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("前移") { currentMove(id, -1); true })
                            if (index < ids.lastIndex) add(CustomAccessibilityAction("后移") { currentMove(id, 1); true })
                        }
                    }.pointerInput(ids, id) {
                        detectDragGesturesAfterLongPress(onDragStart = { dragged = id; distance = Offset.Zero },
                            onDragCancel = { dragged = null; distance = Offset.Zero }, onDragEnd = {
                                val visible = state.layoutInfo.visibleItemsInfo
                                val source = visible.firstOrNull { it.key == id }
                                if (source != null) {
                                    val center = Offset(source.offset.x + source.size.width / 2f, source.offset.y + source.size.height / 2f) + distance
                                    val target = visible.minByOrNull { cell ->
                                        (center - Offset(cell.offset.x + cell.size.width / 2f, cell.offset.y + cell.size.height / 2f)).getDistanceSquared()
                                    }?.index ?: index
                                    repeat(kotlin.math.abs(target - index)) { currentMove(id, if (target > index) 1 else -1) }
                                }
                                dragged = null; distance = Offset.Zero
                            }, onDrag = { change, amount -> change.consume(); distance += amount })
                    }.clickable { onRemove(id) }) {
                Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    icon(id)
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun QuickIcon(id: String) {
    val resource = when (id) {
        "dnd" -> R.drawable.ic_game_do_not_disturb_on; "wifi" -> R.drawable.ic_game_wifi
        "rotation" -> R.drawable.ic_game_screen_rotation; "mute" -> R.drawable.ic_game_volume_off
        "screenshot" -> R.drawable.ic_game_screenshot; "record" -> R.drawable.ic_game_screen_record
        "flashlight" -> R.drawable.ic_qs_flashlight; "airplane" -> R.drawable.ic_qs_airplane
        "current" -> R.drawable.ic_qs_current_window; "autobrightness" -> R.drawable.ic_game_brightness_6
        "batterysaver" -> R.drawable.ic_qs_battery_saver; "darkmode" -> R.drawable.ic_qs_dark_mode
        else -> return Glyph("apps")
    }
    Icon(painterResource(resource), null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun GameIcon(app: LaunchableApp) {
    val bitmap = remember(app.packageName, app.icon) {
        app.icon?.let { drawable ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                val old = android.graphics.Rect(drawable.bounds); drawable.setBounds(0, 0, 96, 96)
                drawable.draw(Canvas(it)); drawable.bounds = old
            }.asImageBitmap()
        }
    }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}
