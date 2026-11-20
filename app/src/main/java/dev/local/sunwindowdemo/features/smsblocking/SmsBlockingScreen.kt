package dev.local.sunwindowdemo.features.smsblocking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.*
import java.text.DateFormat
import java.util.Date

private data class SimEntry(val slot: Int, val name: String, val subscriptionId: Int)
private data class RuleEdit(val slot: Int, val index: Int?, val value: String, val keyword: Boolean)

@Suppress("MissingPermission")
private fun activeSims(context: Context): List<SimEntry> {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .filter { it.simSlotIndex in 0..1 }.map { SimEntry(it.simSlotIndex, it.displayName.toString(), it.subscriptionId) }
    }.getOrDefault(emptyList())
}

@Composable
fun SmsBlockingScreen(showRecords: Boolean = false, bottomInset: Dp = 0.dp, onRecords: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var config by remember { mutableStateOf(SmsBlockingStore.readConfig(context)) }
    var records by remember { mutableStateOf(SmsBlockingStore.readRecords(context).toList()) }
    var sims by remember { mutableStateOf(activeSims(context)) }
    var permission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
    var contactsPermission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var ruleEdit by remember { mutableStateOf<RuleEdit?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf<Int?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    fun refresh() {
        config = SmsBlockingStore.readConfig(context)
        records = SmsBlockingStore.readRecords(context).toList()
        sims = activeSims(context)
        contactsPermission = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        permission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    fun saveList(slot: Int, keyword: Boolean, values: List<String>) {
        if (keyword) SmsBlockingStore.setKeywords(context, slot, values.toTypedArray())
        else SmsBlockingStore.setPatterns(context, slot, values.toTypedArray())
    }
    fun deleteRule(slot: Int, keyword: Boolean, index: Int) {
        val key = if (keyword) "sim${slot + 1}_keywords" else "sim${slot + 1}_patterns"
        val current = config.getStringArray(key).orEmpty().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        saveList(slot, keyword, current)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDenied = !it
        refresh()
    }
    val requestContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        context.contentResolver.notifyChange(SmsBlockingStore.CONFIG_URI, null)
    }
    DisposableEffect(lifecycle, context, permission) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            config = SmsBlockingStore.readConfig(context)
            if (key == null || key == "records") records = SmsBlockingStore.readRecords(context).toList()
        }
        val prefs = SmsBlockingStore.preferences(context)
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
            Hint("仅记录已同步确认关联短信被系统永久删除且影响行数大于零的拦截。异步过滤可能不记；没有记录不代表短信未被拦截。")
            if (records.isEmpty()) Hint("暂无拦截记录") else {
                TextButton(onClick = { confirmClear = true }) { Text("清空记录") }
                SettingsGroup {
                    records.forEach { item ->
                        val sim = item.getString("sim_label").orEmpty().ifBlank { "SIM ${item.getInt("slot_index") + 1}" }
                        val reason = when (item.getString("reason")) {
                            "all_sms" -> "拦截该卡全部短信"
                            "stranger" -> "陌生发送方（不在系统通讯录）"
                            "pattern" -> "匹配规则 ${item.getString("matched_pattern").orEmpty()}"
                            "keyword" -> "命中关键词 ${item.getString("matched_pattern").orEmpty()}"
                            else -> item.getString("reason").orEmpty()
                        }
                        SettingRow(item.getString("sender").orEmpty().ifBlank { "未知发送方" },
                            "$sim · $reason\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(item.getLong("time")))}")
                    }
                }
            }
        } else {
            SettingsGroup {
                ToggleRow("启用短信拦截", value = config.getBoolean("enabled")) { SmsBlockingStore.setEnabled(context, it) }
            }
            Hint("拦截在系统电话进程中判定，交由系统原生屏蔽流程执行。规则更新需等待电话进程读取；锁定状态与运营商过滤等仍由系统处理。")
            if (!permission) SettingsGroup {
                SettingRow("识别 SIM 卡", onClick = {
                    requestPermission.launch(Manifest.permission.READ_PHONE_STATE)
                })
                if (permissionDenied) SettingRow("打开应用权限设置", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                })
            }
            if (!contactsPermission) SettingsGroup {
                SettingRow("允许读取系统通讯录", onClick = {
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
                GroupTitle("SIM ${slot + 1}")
                SettingsGroup {
                    SettingRow(entry?.name ?: "SIM ${slot + 1}", when {
                        !permission -> "等待电话权限"
                        entry == null -> "此卡槽暂无已启用的 SIM 卡"
                        else -> "已识别 · 卡槽 ${slot + 1}"
                    })
                    if (slot == 1) ToggleRow("跟随 SIM 1", value = follows) {
                        SmsBlockingStore.setFollowSim1(context, it)
                    }
                    if (!follows) {
                        val rejectAll = config.getBoolean(ownPrefix + "reject_all")
                        ToggleRow("拦截该卡全部短信", value = rejectAll) {
                            if (it) confirmAll = slot else SmsBlockingStore.setRejectAll(context, slot, false)
                        }
                        if (!rejectAll) {
                            ToggleRow("拦截陌生发送方", value = config.getBoolean(ownPrefix + "reject_strangers")) {
                                SmsBlockingStore.setRejectStrangers(context, slot, it)
                            }
                            ToggleRow("验证码短信放行", value = config.getBoolean(ownPrefix + "allow_verification")) {
                                SmsBlockingStore.setAllowVerification(context, slot, it)
                            }
                            config.getStringArray(effectivePrefix + "patterns").orEmpty().forEachIndexed { index, rule ->
                                SettingRow(rule, onClick = { ruleEdit = RuleEdit(slot, index, rule, false) },
                                    trailing = {
                                        IconButton(onClick = { deleteRule(slot, false, index) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除 $rule")
                                        }
                                    })
                            }
                            SettingRow("添加号码或发送方规则", onClick = { ruleEdit = RuleEdit(slot, null, "", false) },
                                leading = { Icon(Icons.Default.Add, null) })
                            config.getStringArray(effectivePrefix + "keywords").orEmpty().forEachIndexed { index, rule ->
                                SettingRow("关键词：$rule", onClick = { ruleEdit = RuleEdit(slot, index, rule, true) },
                                    trailing = {
                                        IconButton(onClick = { deleteRule(slot, true, index) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除 $rule")
                                        }
                                    })
                            }
                            SettingRow("添加关键词", onClick = { ruleEdit = RuleEdit(slot, null, "", true) },
                                leading = { Icon(Icons.Default.Add, null) })
                        }
                    } else {
                        val patternCount = config.getStringArray("sim1_patterns").orEmpty().size
                        val keywordCount = config.getStringArray("sim1_keywords").orEmpty().size
                        SettingRow("当前生效设置", if (config.getBoolean("sim1_reject_all")) "跟随 SIM 1：拦截该卡全部短信"
                            else "跟随 SIM 1：$patternCount 条号码规则、$keywordCount 条关键词；陌生拦截${if (config.getBoolean("sim1_reject_strangers")) "开" else "关"}；验证码放行${if (config.getBoolean("sim1_allow_verification")) "开" else "关"}")
                    }
                }
            }
            GroupTitle("记录")
            SettingsGroup { SettingRow("拦截记录", "${records.size} 条", onRecords) }
        }
    }
    ruleEdit?.let { edit ->
        RuleDialog(edit.slot, edit.keyword, edit.index, edit.value,
            onDismiss = { ruleEdit = null },
            onSave = { normalized: String ->
                val key = if (edit.keyword) "sim${edit.slot + 1}_keywords" else "sim${edit.slot + 1}_patterns"
                val current = config.getStringArray(key).orEmpty().toMutableList()
                val duplicate = current.indexOf(normalized)
                when {
                    duplicate >= 0 && duplicate != edit.index -> "规则已存在"
                    edit.index == null && current.size >= 100 -> "每张卡最多保存 100 条"
                    edit.index == null -> {
                        current.add(normalized)
                        saveList(edit.slot, edit.keyword, current)
                        null
                    }
                    edit.index in current.indices -> {
                        current[edit.index] = normalized
                        saveList(edit.slot, edit.keyword, current)
                        null
                    }
                    else -> "规则已不存在"
                }
            })
    }
    confirmAll?.let { slot ->
        AlertDialog(onDismissRequest = { confirmAll = null }, title = { Text("拦截 SIM ${slot + 1} 的全部短信？") },
            text = { Text("该卡的短信将提交系统原生屏蔽流程，包括验证码、银行与运营商通知。记录只包含已同步确认原生永久删除影响行数的短信；异步过滤可能不记。") },
            confirmButton = {
                TextButton(onClick = {
                    SmsBlockingStore.setRejectAll(context, slot, true)
                    confirmAll = null
                }) { Text("拦截全部") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = null }) { Text("取消") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空拦截记录？") },
        confirmButton = { TextButton(onClick = { SmsBlockingStore.clearRecords(context); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun RuleDialog(slot: Int, keyword: Boolean, index: Int?, initial: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var input by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val raw = input.trim()
        if (raw.isEmpty()) { error = if (keyword) "请输入关键词" else "请输入号码或发送方规则"; return }
        val normalized = runCatching {
            if (keyword) SmsBlockingRules.normalizeKeyword(raw) else SmsBlockingRules.normalizePattern(raw)
        }.getOrElse { error = it.message ?: "规则格式有误"; return }
        val failure = onSave(normalized)
        if (failure == null) onDismiss() else error = failure
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (index == null) "添加" else "编辑"} SIM ${slot + 1} ${if (keyword) "关键词" else "号码规则"}") },
        text = {
            TextField(
                value = input,
                onValueChange = { input = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (keyword) "正文关键词" else "号码或发送方") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = if (keyword) KeyboardType.Text else KeyboardType.Ascii),
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
