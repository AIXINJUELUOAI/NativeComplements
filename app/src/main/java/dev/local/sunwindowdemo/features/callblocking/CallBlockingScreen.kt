package dev.local.sunwindowdemo.features.callblocking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.*
import java.text.DateFormat
import java.util.Date

private data class SimEntry(val slot: Int, val name: String, val subscriptionId: Int)

@Suppress("MissingPermission")
private fun activeSims(context: Context): List<SimEntry> {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .filter { it.simSlotIndex in 0..1 }.map { SimEntry(it.simSlotIndex, it.displayName.toString(), it.subscriptionId) }
    }.getOrDefault(emptyList())
}

@Composable
fun CallBlockingScreen(showRecords: Boolean = false, bottomInset: Dp = 0.dp, onRecords: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var config by remember { mutableStateOf(CallBlockingStore.readConfig(context)) }
    var records by remember { mutableStateOf(CallBlockingStore.readRecords(context).toList()) }
    var sims by remember { mutableStateOf(activeSims(context)) }
    var permission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    fun refresh() {
        config = CallBlockingStore.readConfig(context)
        records = CallBlockingStore.readRecords(context).toList()
        sims = activeSims(context)
        permission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDenied = !it
        refresh()
    }
    DisposableEffect(lifecycle, context, permission) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            config = CallBlockingStore.readConfig(context)
            if (key == null || key == "records") records = CallBlockingStore.readRecords(context).toList()
        }
        val prefs = CallBlockingStore.preferences(context)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val simListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() { refresh() }
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val listening = permission && runCatching {
            subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, simListener)
        }.isSuccess
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            if (listening) runCatching { subscriptions.removeOnSubscriptionsChangedListener(simListener) }
            lifecycle.removeObserver(observer)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        if (showRecords) {
            if (records.isEmpty()) Hint("暂无拦截记录") else {
                TextButton(onClick = { confirmClear = true }) { Text("清空记录") }
                SettingsGroup {
                    records.forEach { item ->
                        val sim = item.getString("sim_label").orEmpty().ifBlank { "SIM ${item.getInt("slot_index") + 1}" }
                        val reason = when (item.getString("reason")) {
                            "all_calls" -> "拦截全部来电"
                            "pattern" -> "匹配规则 ${item.getString("matched_pattern").orEmpty()}"
                            else -> item.getString("reason").orEmpty()
                        }
                        SettingRow(item.getString("number").orEmpty().ifBlank { "未知号码" },
                            "$sim · $reason\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(item.getLong("time")))}")
                    }
                }
                Hint("仅保存在本机，最多保留最近 200 条。")
            }
        } else {
            SettingsGroup {
                ToggleRow("启用来电拦截", "按 SIM 卡和号码规则拦截来电", config.getBoolean("enabled")) { CallBlockingStore.setEnabled(context, it) }
            }
            if (!permission) SettingsGroup {
                SettingRow("识别 SIM 卡", "允许电话权限以显示两张卡的名称和状态", {
                    requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
                })
                if (permissionDenied) SettingRow("打开应用权限设置", "在权限中允许“电话”后返回", {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                })
            }
            for (slot in 0..1) {
                val entry = sims.firstOrNull { it.slot == slot }
                val follows = slot == 1 && config.getBoolean("sim2_follow_sim1")
                val ownPrefix = "sim${slot + 1}_"
                val effectivePrefix = if (follows) "sim1_" else ownPrefix
                val patterns = config.getStringArray(effectivePrefix + "patterns").orEmpty()
                GroupTitle("SIM ${slot + 1}")
                SettingsGroup {
                    SettingRow(entry?.name ?: "SIM ${slot + 1}", when {
                        !permission -> "等待电话权限"
                        entry == null -> "此卡槽暂无已启用的 SIM 卡"
                        else -> "已识别 · 卡槽 ${slot + 1}"
                    })
                    if (slot == 1) ToggleRow("跟随 SIM 1", "实时使用 SIM 1 的设置；关闭后恢复 SIM 2 的独立设置", follows) {
                        CallBlockingStore.setFollowSim1(context, it)
                    }
                    if (!follows) {
                        ToggleRow("拦截全部来电", "保留移动数据与短信，仅挂断此卡的来电", config.getBoolean(ownPrefix + "reject_all")) {
                            CallBlockingStore.setRejectAll(context, slot, it)
                        }
                        SettingRow("号码过滤规则", if (patterns.isEmpty()) "未设置" else "${patterns.size} 条 · ${patterns.take(3).joinToString("、")}", { editingSlot = slot })
                    } else {
                        SettingRow("当前生效设置", if (config.getBoolean("sim1_reject_all")) "跟随 SIM 1：拦截全部来电"
                            else if (patterns.isEmpty()) "跟随 SIM 1：暂无号码规则" else "跟随 SIM 1：${patterns.size} 条号码规则")
                    }
                }
            }
            GroupTitle("记录")
            SettingsGroup { SettingRow("拦截记录", "${records.size} 条 · 查看号码、SIM 卡与拦截原因", onRecords) }
        }
    }
    editingSlot?.let { slot ->
        PatternEditor(config.getStringArray("sim${slot + 1}_patterns").orEmpty(), slot,
            onDismiss = { editingSlot = null }, onSave = {
                CallBlockingStore.setPatterns(context, slot, it)
                editingSlot = null
            })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空拦截记录？") },
        text = { Text("号码规则和 SIM 卡设置会保留。") },
        confirmButton = { TextButton(onClick = { CallBlockingStore.clearRecords(context); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun PatternEditor(initial: Array<out String>, slot: Int, onDismiss: () -> Unit, onSave: (Array<String>) -> Unit) {
    var input by remember { mutableStateOf(initial.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("SIM ${slot + 1} 号码规则") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("每行一条。* 匹配任意长度，例如 952*；空白保存会清除全部规则。")
            OutlinedTextField(value = input, onValueChange = { input = it; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                label = { Text("号码或通配规则") }, isError = error != null,
                supportingText = { error?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val values = input.lineSequence().map(String::trim).filter(String::isNotEmpty)
                .map(CallBlockingRules::normalizePattern).distinct().toList()
            require(values.size <= 100) { "每张卡最多保存 100 条规则" }
            onSave(values.toTypedArray())
        }.onFailure { error = it.message ?: "规则格式有误" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
