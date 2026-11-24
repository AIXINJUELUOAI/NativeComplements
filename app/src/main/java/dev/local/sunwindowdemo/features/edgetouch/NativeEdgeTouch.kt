package dev.local.sunwindowdemo.features.edgetouch

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import dev.local.sunwindowdemo.core.RootCommand
import java.util.concurrent.Executors

internal object NativeEdgeTouch {
    private const val SERVICE = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    private const val WIDTH = 1200
    private const val HEIGHT = 2670
    private const val EDGE_WIDTH = 50
    private const val TABLE_SIZE = 96
    private val reply = Regex("^Result: Parcel\\(\\s*00000000 ([0-9a-f]{8})\\s+'[^']*'\\)$")
    private val worker = Executors.newSingleThreadExecutor()
    private class WriteSkipped(message: String) : IllegalStateException(message)

    private val hardwareGuard = """
        [ "${'$'}(/system/bin/id -u)" = 0 ] || { echo NC_ROOT_REQUIRED; exit 0; }
        [ "${'$'}(/system/bin/getprop ro.product.device)" = dada ] && [ -c /dev/xiaomi-touch ] || { echo NC_UNSUPPORTED; exit 0; }
        [ ! -f /system_ext/framework/miui-services.jar ] && [ ! -f /system/framework/miui-services.jar ] || { echo NC_FACTORY_OWNER; exit 0; }
        flags=${'$'}(/system/bin/getprop ro.vendor.touchfeature.type)
        case "${'$'}flags" in ''|*[!0-9]*) echo NC_UNSUPPORTED; exit 0;; esac
        [ "${'$'}((flags & 4))" -ne 0 ] || { echo NC_UNSUPPORTED; exit 0; }
    """.trimIndent()

    internal fun parcelValue(output: String): Int? = output.lineSequence()
        .mapNotNull { reply.matchEntire(it.trim())?.groupValues?.get(1)?.toUIntOrNull(16)?.toInt() }
        .singleOrNull()

    internal fun portraitTable(): IntArray = IntArray(TABLE_SIZE).also { table ->
        for ((group, type) in listOf(2, 1, 0).withIndex()) {
            for (position in 0..3) {
                val index = (group * 4 + position) * 8
                table[index] = type
                table[index + 1] = position
                if (type != 1 || position < 2) continue
                val right = position == 3
                table[index + 2] = if (right) WIDTH - 1 - EDGE_WIDTH else 0
                table[index + 3] = (HEIGHT - 1) / 2
                table[index + 4] = if (right) WIDTH - 1 else EDGE_WIDTH
                table[index + 5] = HEIGHT - 1
            }
        }
    }

    private fun display(context: Context) = context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun supportedDisplay(context: Context): Boolean = display(context)?.mode?.let {
        minOf(it.physicalWidth, it.physicalHeight) == WIDTH && maxOf(it.physicalWidth, it.physicalHeight) == HEIGHT
    } == true

    private fun requireSupported(context: Context, timeout: Long = 20) {
        check(Process.myUid() / 100000 == 0 && Build.DEVICE == "dada" && supportedDisplay(context)) {
            "当前机型或物理分辨率尚未适配"
        }
        val output = RootCommand.run(context, "$hardwareGuard\n/system/bin/service call $SERVICE 16777215", timeout)
        check(parcelValue(output) == 1) {
            when {
                "NC_FACTORY_OWNER" in output -> "系统已自带原厂防误触"
                "NC_ROOT_REQUIRED" in output -> "请为模块 App 授予 root 权限"
                "NC_UNSUPPORTED" in output -> "当前系统未提供小米边缘防误触能力"
                else -> "未读到兼容的小米触控接口"
            }
        }
    }

    @Synchronized
    fun check(context: Context) {
        val prefs = EdgeTouchStore.preferences(context)
        try {
            requireSupported(context)
            prefs.edit().putString(EdgeTouchStore.NATIVE_CHECKED, Build.FINGERPRINT).apply()
        } catch (failure: Exception) {
            prefs.edit().remove(EdgeTouchStore.NATIVE_CHECKED).apply()
            throw failure
        }
    }

    private fun activeProfile(context: Context, suspended: Boolean): Boolean = !suspended &&
        supportedDisplay(context) && display(context)?.rotation == Surface.ROTATION_0 &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    private fun write(context: Context, enable: Boolean, timeout: Long = 20) {
        val values = if (enable) portraitTable() else IntArray(TABLE_SIZE)
        val columns = values.joinToString(" ") { "i32 $it" }
        val guard = if (enable) """
            [ "${'$'}(/system/bin/cmd activity get-current-user)" = 0 ] || { echo NC_USER; exit 0; }
            mode=${'$'}(/system/bin/service call $SERVICE 1 i32 0 i32 0)
            case "${'$'}mode" in *"00000000 00000000"*) ;; *) echo NC_GAME; exit 0;; esac
        """.trimIndent() else ""
        val output = RootCommand.run(context, """
            $hardwareGuard
            $guard
            /system/bin/service call $SERVICE 8 i32 0 i32 15 i32 $TABLE_SIZE i32 $TABLE_SIZE $columns
        """.trimIndent(), timeout)
        if ("NC_USER" in output) throw WriteSkipped("请切回主用户后启用")
        if ("NC_GAME" in output) throw WriteSkipped("系统游戏触控模式正在生效或状态未知")
        if (output.lineSequence().any { it.startsWith("NC_") }) throw WriteSkipped("原厂触控环境已变化，请重新检验")
        check(parcelValue(output) == 0) { if (enable) "触控服务未确认下发" else "触控服务未确认清除，请重试" }
    }

    private fun setState(context: Context, enabled: Boolean, owned: Boolean) {
        check(EdgeTouchStore.preferences(context).edit().putBoolean(EdgeTouchStore.NATIVE_ENABLED, enabled)
            .putBoolean(EdgeTouchStore.NATIVE_OWNED, owned).commit()) { "保存原厂防误触状态失败" }
        EdgeTouchStore.changed(context)
    }

    @Synchronized
    fun set(context: Context, enabled: Boolean) {
        val prefs = EdgeTouchStore.preferences(context)
        val owned = prefs.getBoolean(EdgeTouchStore.NATIVE_OWNED, false)
        if (!enabled) {
            setState(context, false, owned)
            if (owned) {
                // Retain ownership until the vendor explicitly acknowledges the clear command.
                write(context, false)
                setState(context, false, false)
            }
            return
        }
        requireSupported(context)
        check(activeProfile(context, false)) { "请解锁并保持正向竖屏后启用" }
        setState(context, true, true)
        try {
            write(context, true)
        } catch (failure: Exception) {
            val mightOwnRule = owned || failure !is WriteSkipped
            setState(context, false, mightOwnRule)
            if (mightOwnRule) runCatching {
                write(context, false, 4)
                setState(context, false, false)
            }
            throw failure
        }
    }

    @Synchronized
    private fun reconcile(context: Context, suspended: Boolean) {
        val prefs = EdgeTouchStore.preferences(context)
        val enabled = prefs.getBoolean(EdgeTouchStore.NATIVE_ENABLED, false)
        val owned = prefs.getBoolean(EdgeTouchStore.NATIVE_OWNED, false)
        if (!enabled && !owned) return
        val apply = enabled && activeProfile(context, suspended)
        if (apply) {
            if (!owned) setState(context, true, true)
            try { write(context, true, 4) }
            catch (failure: WriteSkipped) {
                if (!owned) setState(context, true, false)
                throw failure
            }
        } else if (owned) {
            write(context, false, 4)
            setState(context, enabled, false)
        }
    }

    class LifecycleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val suspended = when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> false
                EdgeTouchStore.NATIVE_SYNC -> {
                    val systemUiUid = runCatching {
                        context.packageManager.getApplicationInfo(EdgeTouchStore.SYSTEM_UI, 0).uid
                    }.getOrNull() ?: return
                    if (sentFromUid != systemUiUid) return
                    intent.getBooleanExtra("suspended", true)
                }
                else -> return
            }
            if (Process.myUid() / 100000 != 0 || !EdgeTouchStore.read(context).getBoolean("native_managed")) return
            val pending = goAsync()
            worker.execute {
                try { reconcile(context.applicationContext, suspended) }
                catch (failure: Exception) { Log.w("NativeComplements", "EDGE_TOUCH_NATIVE_SYNC ${failure.message}") }
                finally { pending.finish() }
            }
        }
    }
}
