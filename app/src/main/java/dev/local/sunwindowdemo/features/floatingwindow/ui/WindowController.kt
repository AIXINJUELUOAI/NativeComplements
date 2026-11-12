package dev.local.sunwindowdemo

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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
    private val change = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> preferences = store.read() }
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
    fun boolean(key: String, value: Boolean) = store.boolean(key, value)
    fun number(key: String, value: Int) = store.number(key, value)
    fun blocked(packages: Set<String>) = store.blocked(packages)
    fun toggleControl() = store.boolean("enabled", !preferences.enabled)
    fun diagnostic() = TaskTools.openDiagnostic(activity)
    fun restore() = Wire.send(activity, "RESTORE")
    fun notifications() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        else toast("通知权限已开启")
    }
    fun chrome() {
        if (!preferences.enabled || !runtime.compatible || !runtime.hostConnected) { toast("请先启用小窗控制并连接 SystemUI 宿主"); return }
        if (activity.packageManager.getLaunchIntentForPackage("com.android.chrome")?.component == null) { toast("未找到 Chrome，可使用内置诊断页"); return }
        Wire.send(activity, "OPEN", "com.android.chrome", -1, Wire.token(), false)
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
        IconAssets.removeOnReload(iconsReloaded)
        handler.removeCallbacksAndMessages(null)
        activity.unregisterReceiver(receiver)
        store.preferences.unregisterOnSharedPreferenceChangeListener(change)
    }
}
