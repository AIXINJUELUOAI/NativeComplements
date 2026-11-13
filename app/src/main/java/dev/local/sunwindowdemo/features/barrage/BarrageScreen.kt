package dev.local.sunwindowdemo.features.barrage

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun BarrageScreen(apps: List<LaunchableApp>,loaded: Boolean,bottomInset: Dp=0.dp,onHistory:()->Unit={}) {
    val context=LocalContext.current
    var config by remember {mutableStateOf(BarrageStore.read(context))}
    var historyCount by remember {mutableStateOf(BarrageStore.historyCount(context))}
    var showLines by remember {mutableStateOf(false)}
    DisposableEffect(context) {
        val prefs=BarrageStore.prefs(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> config=BarrageStore.read(context);historyCount=BarrageStore.historyCount(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val selected=config.getStringArrayList("apps").orEmpty().toSet()
    val visible=remember(apps,selected) {apps.filter {it.packageName!="dev.nativecomplements.module"}.sortedByDescending {it.packageName in selected}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,32.dp+bottomInset),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        item {SettingsGroup {
            ToggleRow("启用弹幕通知", value=config.getBoolean("enabled")) {BarrageStore.enabled(context,it)}
            SettingRow("同时显示条数", when(config.getInt("lines",3)){0->"无限";5->"五条";else->"三条"}, onClick={showLines=true})
        }}
        item {SettingsGroup {SettingRow("弹幕记录","${historyCount} 条",onHistory)}}
        item {GroupTitle("逐应用设置")}
        if(!loaded)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(visible,key={it.packageName}) {app ->
            SettingRow(app.label,onClick={BarrageStore.select(context,app.packageName,app.packageName !in selected)},
                leading={BarrageAppIcon(app)},
                trailing={Switch(app.packageName in selected, {BarrageStore.select(context,app.packageName,it)})})
        }
        if(loaded&&visible.isEmpty())item {Hint("暂无可显示的应用")}
    }
    if(showLines) AlertDialog(onDismissRequest={showLines=false},title={Text("同时显示条数")},text={
        Column {
            listOf(3 to "三条",5 to "五条",0 to "无限").forEach { (count,label) ->
                SettingRow(label,onClick={BarrageStore.lines(context,count);showLines=false},
                    trailing={RadioButton(config.getInt("lines",3)==count,null)})
            }
            Hint("无限档在屏幕轨道用满后复用轨道，消息密集时可能重叠。")
        }
    },confirmButton={TextButton(onClick={showLines=false}){Text("关闭")}})
}

@Composable
internal fun BarrageAppIcon(app: LaunchableApp?) {
    val bitmap = remember(app?.packageName, app?.icon) {
        app?.icon?.let { drawable ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(Canvas(it))
                drawable.bounds = old
            }.asImageBitmap()
        }
    }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}
