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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private data class RuleEdit(val slot: Int, val index: Int?, val value: String)

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
    var contactsPermission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var ruleEdit by remember { mutableStateOf<RuleEdit?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    fun refresh() {
        config = CallBlockingStore.readConfig(context)
        records = CallBlockingStore.readRecords(context).toList()
        sims = activeSims(context)
        contactsPermission = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        permission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    fun deleteRule(slot: Int, index: Int) {
        val current = config.getStringArray("sim${slot + 1}_patterns").orEmpty().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        CallBlockingStore.setPatterns(context, slot, current.toTypedArray())
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDenied = !it
        refresh()
    }
    val requestContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        context.contentResolver.notifyChange(CallBlockingStore.CONFIG_URI, null)
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
                            "stranger" -> "陌生号码（不在系统通讯录）"
                            "pattern" -> "匹配规则 ${item.getString("matched_pattern").orEmpty()}"
                            else -> item.getString("reason").orEmpty()
                        }
                        SettingRow(item.getString("number").orEmpty().ifBlank { "未知号码" },
                            "$sim · $reason\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(item.getLong("time")))}")
                    }
                }
            }
        } else {
            SettingsGroup {
                ToggleRow("启用来电拦截", value = config.getBoolean("enabled")) { CallBlockingStore.setEnabled(context, it) }
            }
            if (!permission) SettingsGroup {
                SettingRow("识别 SIM 卡", onClick = {
                    requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
                })
                if (permissionDenied) SettingRow("打开应用权限设置", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                })
            }
            if (!contactsPermission) SettingsGroup {
                SettingRow("允许读取系统通讯录", "未授权或查询失败时，陌生号码规则自动放行", onClick = {
                    requestContacts.launch(Manifest.permission.READ_CONTACTS)
                })
                SettingRow("打开应用权限设置", onClick = {
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
                    if (slot == 1) ToggleRow("跟随 SIM 1", value = follows) {
                        CallBlockingStore.setFollowSim1(context, it)
                    }
                    if (!follows) {
                        val rejectAll = config.getBoolean(ownPrefix + "reject_all")
                        ToggleRow("拦截全部来电", value = rejectAll) {
                            CallBlockingStore.setRejectAll(context, slot, it)
                        }
                        if (!rejectAll) {
                            ToggleRow("拦截陌生号码", "不在系统通讯录；未授权、查询失败、缓存过期或有效号码超过 4000 个时此规则放行",  value = config.getBoolean(ownPrefix + "reject_strangers")) {
                                CallBlockingStore.setRejectStrangers(context, slot, it)
                            }
                            ToggleRow("三分钟重复来电放行", "同卡同号码的第二通仅豁免陌生规则，不豁免手动号码规则", value = config.getBoolean(ownPrefix + "allow_repeat")) {
                                CallBlockingStore.setAllowRepeat(context, slot, it)
                            }
                            patterns.forEachIndexed { index, rule ->
                                SettingRow(rule, onClick = { ruleEdit = RuleEdit(slot, index, rule) },
                                    trailing = {
                                        IconButton(onClick = { deleteRule(slot, index) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除 $rule")
                                        }
                                    })
                            }
                            SettingRow("添加号码规则", onClick = { ruleEdit = RuleEdit(slot, null, "") },
                                leading = { Icon(Icons.Default.Add, null) })
                        }
                    } else {
                        SettingRow("当前生效设置", if (config.getBoolean("sim1_reject_all")) "跟随 SIM 1：拦截全部来电"
                            else "跟随 SIM 1：${patterns.size} 条号码规则；陌生拦截${if (config.getBoolean("sim1_reject_strangers")) "开" else "关"}；重复放行${if (config.getBoolean("sim1_allow_repeat")) "开" else "关"}")
                    }
                }
            }
            GroupTitle("记录")
            SettingsGroup { SettingRow("拦截记录", "${records.size} 条", onRecords) }
        }
    }
    ruleEdit?.let { edit ->
        RuleDialog(edit.slot, edit.index, edit.value,
            onDismiss = { ruleEdit = null },
            onSave = { normalized: String ->
                val current = config.getStringArray("sim${edit.slot + 1}_patterns").orEmpty().toMutableList()
                val duplicate = current.indexOf(normalized)
                when {
                    duplicate >= 0 && duplicate != edit.index -> "规则已存在"
                    edit.index == null && current.size >= 100 -> "每张卡最多保存 100 条规则"
                    edit.index == null -> {
                        current.add(normalized)
                        CallBlockingStore.setPatterns(context, edit.slot, current.toTypedArray())
                        null
                    }
                    edit.index in current.indices -> {
                        current[edit.index] = normalized
                        CallBlockingStore.setPatterns(context, edit.slot, current.toTypedArray())
                        null
                    }
                    else -> "规则已不存在"
                }
            })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空拦截记录？") },
        confirmButton = { TextButton(onClick = { CallBlockingStore.clearRecords(context); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun RuleDialog(slot: Int, index: Int?, initial: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var input by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val raw = input.trim()
        if (raw.isEmpty()) { error = "请输入号码或通配规则"; return }
        val normalized = runCatching { CallBlockingRules.normalizePattern(raw) }
            .getOrElse { error = it.message ?: "规则格式有误"; return }
        val failure = onSave(normalized)
        if (failure == null) onDismiss() else error = failure
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (index == null) "添加" else "编辑"} SIM ${slot + 1} 号码规则") },
        text = {
            TextField(
                value = input,
                onValueChange = { input = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("号码或通配规则") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
