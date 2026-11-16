package dev.local.sunwindowdemo

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.local.sunwindowdemo.features.iconlayers.IconAssets

data class WindowRuntime(
    val received: Boolean = false, val compatible: Boolean = false,
    val hostConnected: Boolean = false, val state: String = "unknown",
    val controlEnabled: Boolean = true,
    val hasJournal: Boolean = false, val report: String = "正在检查小窗后端…",
    val lastTouch: String = "尚无手势记录",
)
data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable? = null)

/** App-process-only bridge. Never referenced by the three libxposed entry points. */
class WindowController(private val activity: Activity) {
    private val store = WindowPreferenceStore(activity)
    var preferences by mutableStateOf(store.read()); private set
    var runtime by mutableStateOf(WindowRuntime()); private set
    var apps by mutableStateOf<List<LaunchableApp>>(emptyList()); private set
    var catalogLoaded by mutableStateOf(false); private set
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var catalogGeneration = 0
    private val iconsReloaded = Runnable { handler.post { if (!destroyed) loadCatalog() } }
    private var recovery: Intent? = null
    var enablePrompt by mutableStateOf<String?>(null); private set
    var enableChecking by mutableStateOf(false); private set
    private val enabledWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var enabledSaving = false
    private var enableRequest: String? = null
    private var enableRequestedAt = 0L
    private val enableTimeout = Runnable {
        if (enableRequest != null) finishEnable(FreeformEnablePolicy.Decision.BACKEND_UNKNOWN)
    }
    private val change = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> handler.post { if (!destroyed) preferences = store.read() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Wire.RESULT || sentFromUid != 1000) return
            val compatible = intent.getIntExtra("protocol", 0) == Wire.PROTOCOL
            runtime = WindowRuntime(
                received = true, compatible = compatible, hostConnected = intent.getBooleanExtra("hostConnected", false),
                controlEnabled = preferences.enabled,
                state = if (compatible) intent.getStringExtra("state") ?: "unknown" else "incompatible",
                hasJournal = intent.getBooleanExtra("hasJournal", false),
                report = if (compatible) intent.getStringExtra("report") ?: "后端已连接" else "请重启手机，使系统后端与当前模块版本一致。",
                lastTouch = intent.getStringExtra("lastTouch") ?: "尚未收到角落触摸",
            )
            acceptEnableStatus(intent, sentFromUid)
            if (!compatible) return
            recovery = Intent(intent)
            Wire.notification(context, runtime.state == "active" || runtime.state == "recovery")
            if (runtime.state == "idle" && !intent.getBooleanExtra("hasJournal", true)) TaskTools.trimIdle(activity)
            // Retain the Demo8.2 behavior: do not leave the management home below a launched window.
            if (intent.getStringExtra("event") == "ACTIVE" && activity.isTaskRoot) activity.finishAndRemoveTask()
        }
    }
    init {
        store.preferences.registerOnSharedPreferenceChangeListener(change)
        activity.registerReceiver(receiver, IntentFilter(Wire.RESULT), Wire.PERMISSION, null, Context.RECEIVER_EXPORTED)
        val context = activity.applicationContext
        dev.local.sunwindowdemo.core.WindowBackendEligibility.refreshAsync(context)
        IconAssets.onReload(iconsReloaded)
        IconAssets.connect(context)
        loadCatalog()
    }
    private fun loadCatalog() {
        val context = activity.applicationContext
        val generation = ++catalogGeneration
        Thread({
            val loaded = runCatching { AppCatalog.load(context) }.getOrDefault(emptyList())
            handler.post { if (!destroyed && generation == catalogGeneration) {
                apps = loaded.map { LaunchableApp(it.pkg, it.label, it.icon) }; catalogLoaded = true
            } }
        }, "settings-app-catalog").start()
    }
    fun resume() {
        preferences = store.read()
        runtime = runtime.copy(hostConnected = false)
        check()
    }
    fun check() {
        runtime = runtime.copy(received = false, compatible = false, report = "正在检查小窗后端…")
        handler.removeCallbacks(missing)
        Wire.send(activity, "STATUS")
        handler.postDelayed(missing, 2000)
    }
    private val missing = Runnable {
        if (!runtime.received) runtime = runtime.copy(report = "尚未收到系统后端回应。请在 LSPosed 启用模块，勾选系统框架、系统界面和 Pixel 启动器，然后重启手机。")
    }
    fun boolean(key: String, value: Boolean) {
        if (key == "enabled") setEnabled(value) else store.boolean(key, value)
    }
    fun number(key: String, value: Int) = store.number(key, value)
    fun blocked(packages: Set<String>) = store.blocked(packages)
    fun toggleControl() = setEnabled(!preferences.enabled && !enableChecking)
    fun dismissEnablePrompt() { enablePrompt = null }
    fun confirmEnablePrompt() {
        if (destroyed || enabledSaving || enablePrompt == null) return
        enablePrompt = null
        persistEnabled(true)
    }
    private fun persistEnabled(value: Boolean) {
        enabledSaving = true
        enableChecking = true
        enabledWorker.execute {
            val saved = runCatching { if (value) store.enable() else store.boolean("enabled", false) }
            handler.post {
                if (!destroyed) {
                    enabledSaving = false
                    enableChecking = false
                    preferences = store.read()
                    toast(if (saved.isSuccess) "已保存；接管资格在下次完整重启生效，当前不会跨版转移窗口。" else "保存失败，未确认更改：${saved.exceptionOrNull()?.message}")
                }
            }
        }
    }
    private fun globalFreeform(): Int? = try {
        Settings.Global.getInt(activity.contentResolver, "enable_freeform_support")
    } catch (_: Settings.SettingNotFoundException) { null }
      catch (_: SecurityException) { null }
    private fun cancelEnable() {
        enableRequest = null
        enableChecking = false
        handler.removeCallbacks(enableTimeout)
    }
    private fun setEnabled(value: Boolean) {
        if (destroyed || enabledSaving) return
        if (enableRequest != null && !value) { cancelEnable(); enablePrompt = null; return }
        cancelEnable()
        enablePrompt = null
        if (!value) {
            persistEnabled(false)
            return
        }
        val setting = globalFreeform()
        if (setting != 1) {
            finishEnable(FreeformEnablePolicy.evaluate(setting, null))
            return
        }
        enableRequest = UUID.randomUUID().toString()
        enableRequestedAt = SystemClock.elapsedRealtime()
        enableChecking = true
        handler.postDelayed(enableTimeout, 2000)
        runCatching { Wire.requestStatus(activity, enableRequest) }
            .onFailure { finishEnable(FreeformEnablePolicy.Decision.BACKEND_UNKNOWN) }
    }
    private fun acceptEnableStatus(intent: Intent, senderUid: Int) {
        if (!FreeformEnablePolicy.matchesStatus(senderUid, intent.getIntExtra("protocol", 0),
                intent.getStringExtra("event"), enableRequest, intent.getStringExtra("requestId"),
                enableRequestedAt, SystemClock.elapsedRealtime(), 2000)) return
        val extras = intent.extras
        val keys = listOf("capabilitiesKnown", "supportsMultiWindow", "supportsFreeform", "preflightReady", "ownershipConflict")
        @Suppress("DEPRECATION")
        val capability = if (extras != null && keys.all { extras.get(it) is Boolean })
            FreeformEnablePolicy.Capability(intent.getBooleanExtra(keys[0], false),
                intent.getBooleanExtra(keys[1], false), intent.getBooleanExtra(keys[2], false),
                intent.getBooleanExtra(keys[3], false), intent.getBooleanExtra(keys[4], true)) else null
        finishEnable(FreeformEnablePolicy.evaluate(globalFreeform(), capability))
    }
    private fun finishEnable(decision: FreeformEnablePolicy.Decision) {
        cancelEnable()
        if (decision == FreeformEnablePolicy.Decision.ALLOWED) {
            persistEnabled(true)
            return
        }
        val reason = when (decision) {
            FreeformEnablePolicy.Decision.SETTING_REQUIRED -> "未检测到系统 Freeform 开关已开启，但这不一定代表小窗不可用。可以继续开启并尝试；若无法使用，再按需手动执行："
            FreeformEnablePolicy.Decision.SETTING_UNKNOWN -> "未能读取系统 Freeform 设置，这不代表小窗一定不可用。可以继续开启并尝试；若无法使用，再按需手动执行："
            FreeformEnablePolicy.Decision.RUNTIME_UNAVAILABLE -> "本次未检测到运行时多窗口或 Freeform 能力。可以继续开启并尝试，但不保证当前系统可用，重启也不保证解决。"
            FreeformEnablePolicy.Decision.OWNERSHIP_CONFLICT -> "其他小窗版本已取得本次启动的接管权。可以保存开启，但不会强抢；请关闭不使用版本的小窗总开关，再完整重启。两版同时开启时保留先取得者，当前启动不会热转移窗口。"
            FreeformEnablePolicy.Decision.PREFLIGHT_FAILED -> "系统后端的接口预检尚未就绪。可以保存开启，但实际操作仍由后端安全检查决定，不会强行调用不兼容接口。"
            else -> "尚未收到本次可信完整的系统能力回应，或检查已超时。可以继续开启并尝试；如无反应，再检查模块作用域、后端状态和是否需要重启。"
        }
        enablePrompt = reason + if (decision == FreeformEnablePolicy.Decision.SETTING_REQUIRED ||
                decision == FreeformEnablePolicy.Decision.SETTING_UNKNOWN)
            "\n\nadb shell settings put global enable_freeform_support 1\n\n本模块不会自动修改设置。此检查仅作提醒，可选择继续开启；设置值也不等于实际兼容性保证。" else ""
    }
    fun diagnostic() = TaskTools.openDiagnostic(activity)
    fun restore() = Wire.send(activity, "RESTORE")
    fun notifications() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        else toast("通知权限已开启")
    }
    fun reopen() {
        val intent = recovery
        val pkg = intent?.getStringExtra("recoverPackage")
        val token = intent?.getStringExtra("recoverToken")
        val component = intent?.getStringExtra("recoverComponent")?.let(ComponentName::unflattenFromString)
        if (!Wire.allowedPackage(pkg) || !Wire.validToken(token) || component == null || component.packageName != pkg) {
            toast("没有可重新打开的恢复记录，请先检查后端状态"); return
        }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg == Wire.PKG) launch.identifier = token
        runCatching {
            activity.startActivity(launch)
            handler.postDelayed({ restore() }, 1400)
        }.onFailure { toast("待恢复应用暂时无法打开，请查看诊断信息") }
    }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    fun close() {
        destroyed = true
        enabledWorker.shutdown()
        cancelEnable()
        IconAssets.removeOnReload(iconsReloaded)
        handler.removeCallbacksAndMessages(null)
        activity.unregisterReceiver(receiver)
        store.preferences.unregisterOnSharedPreferenceChangeListener(change)
    }
}
