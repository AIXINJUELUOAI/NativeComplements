package dev.local.sunwindowdemo.features.suggestions

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.R
import dev.local.sunwindowdemo.WindowController
import dev.local.sunwindowdemo.core.ui.*

private enum class SuggestionSheet { Bluetooth, Otp, Button }

@Composable
private fun SuggestionIcon(@DrawableRes icon: Int, tone: Color) {
    Box(Modifier.size(44.dp).background(tone.copy(alpha = .16f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tone)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var sheet by remember { mutableStateOf<SuggestionSheet?>(null) }
    val selected = config.getString("bluetooth_app", "")
    val selectedLabel = controller.apps.firstOrNull { it.packageName == selected }?.label
    val bluetoothSubtitle = when {
        selected.isBlank() -> null
        selectedLabel != null -> "已选择 $selectedLabel"
        else -> "已选择 $selected"
    }
    val dark = isSystemInDarkTheme()
    fun tone(light: Long, night: Long) = Color(if (dark) night else light)
    val torchTone = tone(0xFFB26A00, 0xFFFFB74D)
    val awakeTone = tone(0xFFC58A00, 0xFFFFD54F)
    val bluetoothTone = tone(0xFF1A73E8, 0xFF8AB4F8)
    val otpTone = tone(0xFF00897B, 0xFF4DB6AC)
    val linkTone = tone(0xFF7B4DBD, 0xFFC4A7FF)
    val locationTone = tone(0xFF2E7D32, 0xFF81C784)
    val buttonTone = tone(0xFF6750A4, 0xFFD0BCFF)
    val windowTone = tone(0xFF00639B, 0xFF8ECDF8)

    SettingsGroup {
        IconToggleRow("启用快捷建议", value = config.getBoolean("enabled"), onChange = { toggle("enabled", it) },
            leading = { Glyph("spark") })
    }
    GroupTitle("建议内容")
    SettingsGroup {
        IconToggleRow("手电筒", value = config.getBoolean("torch"), onChange = { toggle("torch", it) },
            leading = { SuggestionIcon(R.drawable.ic_suggestion_torch, torchTone) })
        IconToggleRow("本次常亮", value = config.getBoolean("awake"), onChange = { toggle("awake", it) },
            leading = { SuggestionIcon(R.drawable.ic_suggestion_awake, awakeTone) })
        SplitSwitchRow("蓝牙音频设备", bluetoothSubtitle, config.getBoolean("bluetooth"), { toggle("bluetooth", it) },
            { sheet = SuggestionSheet.Bluetooth }, leading = { SuggestionIcon(R.drawable.ic_suggestion_bluetooth, bluetoothTone) })
        SplitSwitchRow("验证码填充", value = config.getBoolean("otp"), onToggle = { toggle("otp", it) },
            onDetails = { sheet = SuggestionSheet.Otp }, leading = { SuggestionIcon(R.drawable.ic_suggestion_otp, otpTone) })
        IconToggleRow("链接与分享口令", value = config.getBoolean("links"), onChange = { toggle("links", it) },
            leading = { SuggestionIcon(R.drawable.ic_suggestion_link, linkTone) })
        IconToggleRow("地址与地点", value = config.getBoolean("maps"), onChange = { toggle("maps", it) },
            leading = { SuggestionIcon(R.drawable.ic_suggestion_location, locationTone) })
    }
    GroupTitle("按钮与打开方式")
    SettingsGroup {
        SettingRow("按钮显示", onClick = { sheet = SuggestionSheet.Button },
            leading = { SuggestionIcon(R.drawable.ic_suggestion_tune, buttonTone) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        IconToggleRow("以小窗打开", value = config.getBoolean("window"), onChange = { toggle("window", it) },
            leading = { SuggestionIcon(R.drawable.ic_qs_current_window, windowTone) })
    }

    val active = sheet
    if (active != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                when (active) {
                    SuggestionSheet.Bluetooth -> {
                        Text("蓝牙连接后打开", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                        if (!controller.catalogLoaded) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                        else if (controller.apps.isEmpty()) Text("暂无可显示的应用", Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(controller.apps, key = { it.packageName }) { app ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    SuggestionStore.setApp(context, app.packageName); sheet = null
                                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected == app.packageName, onClick = null)
                                    Text(app.label, Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                    SuggestionSheet.Otp -> {
                        Text("验证码填充", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        SettingsGroup {
                            val requestSms: (() -> Unit)? = if (smsGranted) null else ({ permission.launch(Manifest.permission.RECEIVE_SMS) })
                            SettingRow("短信读取", if (smsGranted) "已允许" else null, onClick = requestSms,
                                leading = { SuggestionIcon(R.drawable.ic_suggestion_otp, otpTone) },
                                trailing = { if (smsGranted) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
                            IconSliderRow("逐位输入间隔", value = config.getInt("delay"), range = 50..500, unit = " ms",
                                onCommit = { SuggestionStore.setDelay(context, it) },
                                leading = { SuggestionIcon(R.drawable.ic_suggestion_tune, buttonTone) })
                            IconToggleRow("隐藏对应验证码通知", value = config.getBoolean("hide_sms"), onChange = { toggle("hide_sms", it) },
                                leading = { SuggestionIcon(R.drawable.ic_suggestion_otp, otpTone) })
                        }
                    }
                    SuggestionSheet.Button -> {
                        Text("按钮显示", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        SettingsGroup {
                            IconSliderRow("显示时长", value = config.getInt("duration", 60), range = 5..300, unit = " 秒",
                                onCommit = { SuggestionStore.setDuration(context, it) },
                                leading = { SuggestionIcon(R.drawable.ic_suggestion_tune, buttonTone) })
                            IconSliderRow("按钮大小", value = config.getInt("size", 48), range = 32..80, unit = " dp",
                                onCommit = { SuggestionStore.setSize(context, it) },
                                leading = { SuggestionIcon(R.drawable.ic_qs_current_window, windowTone) })
                            IconToggleRow("显示在右下角", value = config.getBoolean("right"), onChange = { toggle("right", it) },
                                leading = { SuggestionIcon(R.drawable.ic_suggestion_tune, buttonTone) })
                        }
                    }
                }
            }
        }
    }
}
