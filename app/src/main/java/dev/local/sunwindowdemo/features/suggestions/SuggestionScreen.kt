package dev.local.sunwindowdemo.features.suggestions

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.WindowController
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun SuggestionScreen(controller: WindowController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var config by remember { mutableStateOf(SuggestionStore.read(context)) }
    var smsGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsGranted = it }
    DisposableEffect(context, lifecycle) {
        val prefs = SuggestionStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = SuggestionStore.read(context) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                config = SuggestionStore.read(context)
                smsGranted = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener); lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    fun toggle(key: String, value: Boolean) = SuggestionStore.setBoolean(context, key, value)
    var selectingApp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = config.getString("bluetooth_app", "")
    SettingsGroup {
        ToggleRow("开启建议", "在屏幕底部显示当前可用的圆形操作按钮", config.getBoolean("enabled")) { toggle("enabled", it) }
    }
    GroupTitle("设备")
    SettingsGroup {
        ToggleRow("手电筒", "手电筒开启时显示关闭按钮", config.getBoolean("torch")) { toggle("torch", it) }
        ToggleRow("本次常亮", "息屏前变暗后，触摸唤亮时显示按钮；点击保持亮屏，直到下次熄屏或锁屏", config.getBoolean("awake")) { toggle("awake", it) }
        ToggleRow("蓝牙音频设备", "耳机、音箱或车载音频连接后显示应用按钮", config.getBoolean("bluetooth")) { toggle("bluetooth", it) }
        SettingRow("蓝牙连接后打开", controller.apps.firstOrNull { it.packageName == selected }?.label ?: "选择一个应用", { selectingApp = true })
    }
    GroupTitle("验证码")
    SettingsGroup {
        ToggleRow("验证码填充", "从新短信或复制的内容提取验证码，点击按钮后逐位输入", config.getBoolean("otp")) { toggle("otp", it) }
        SettingRow("短信读取", if (smsGranted) "已允许 · 仅处理新收到的验证码，不读取短信历史" else "点击允许接收新短信，以便及时提取验证码", {
            if (!smsGranted) permission.launch(Manifest.permission.RECEIVE_SMS)
        })
        SliderRow("逐位输入间隔", "先选中输入框，再点击验证码按钮；切换窗口会停止输入", config.getInt("delay"), 50..500, " ms") { SuggestionStore.setDelay(context, it) }
        ToggleRow("隐藏对应验证码通知", "仅在按钮已显示且能与一条新短信准确对应时移除通知；普通或合并短信通知保留", config.getBoolean("hide_sms")) { toggle("hide_sms", it) }
    }
    Hint("验证码保留前导零。分格输入框交给应用自动换格，自绘输入框是否响应按键取决于应用。短信内容与验证码只短暂保存在内存中，不写入日志。")
    GroupTitle("复制内容")
    SettingsGroup {
        ToggleRow("链接与分享口令", "淘宝、京东、拼多多、抖音、快手、哔哩哔哩、小红书、知乎、微博、美团", config.getBoolean("links")) { toggle("links", it) }
        ToggleRow("地址与地点", "复制地址、店铺或地名后显示地图搜索；安装多个地图时点击选择", config.getBoolean("maps")) { toggle("maps", it) }
    }
    Hint("链接交给目标应用打开；纯分享口令会保留剪贴板并启动应用，由应用自行识别。识别地点后仅展示按钮，点击才搜索或跳转。")
    GroupTitle("打开方式")
    SettingsGroup {
        ToggleRow("以小窗打开", "应用、链接和地图使用现有小窗；关闭后全屏打开", config.getBoolean("window")) { toggle("window", it) }
    }
    Hint("按钮触发时轻震，操作完成后消失；手电筒操作后保留 5 秒，可再次点击反向切换。按钮使用桌面应用图标，跟随深浅模式并避让键盘。此功能独立于小窗手势总开关，不需要无障碍或普通应用作用域。")
    if (selectingApp) AlertDialog(onDismissRequest = { selectingApp = false }, title = { Text("选择一个应用") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(controller.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { SuggestionStore.setApp(context, app.packageName); selectingApp = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == app.packageName, onClick = null)
                        Text(app.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { selectingApp = false }) { Text("取消") } })
}
