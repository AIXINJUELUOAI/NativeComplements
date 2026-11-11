package dev.local.sunwindowdemo.features.barrage

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun BarrageScreen(apps: List<LaunchableApp>,loaded: Boolean,bottomInset: Dp=0.dp) {
    val context=LocalContext.current
    var config by remember {mutableStateOf(BarrageStore.read(context))}
    var query by remember {mutableStateOf("")}
    DisposableEffect(context) {
        val prefs=BarrageStore.prefs(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> config=BarrageStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val selected=config.getStringArrayList("apps").orEmpty().toSet()
    val visible=remember(apps,query,selected) {apps.filter {it.packageName!="dev.nativecomplements.module"&&(it.label.contains(query,true)||it.packageName.contains(query,true))}.sortedByDescending {it.packageName in selected}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,32.dp+bottomInset),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        item {SettingsGroup {ToggleRow("启用弹幕通知","在选定应用前台时短暂显示新通知",config.getBoolean("enabled")) {BarrageStore.enabled(context,it)}}}
        item {Hint("选择显示弹幕的应用。通知仍保留在系统通知栏；锁屏、键盘或系统面板出现时暂停显示，私密通知和通话通知保持原生处理。")}
        item {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(vertical=12.dp),singleLine=true,placeholder={Text("搜索应用或包名")})}
        if(!loaded)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(visible,key={it.packageName}) {app ->
            SettingRow(app.label,onClick={BarrageStore.select(context,app.packageName,app.packageName !in selected)},
                trailing={Checkbox(app.packageName in selected,{BarrageStore.select(context,app.packageName,it)})})
        }
        if(loaded&&visible.isEmpty())item {Hint("没有匹配的应用")}
    }
}
