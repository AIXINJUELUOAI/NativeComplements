package dev.local.sunwindowdemo.navigation

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit

/** Only called after an explicit root action, off the UI thread. Never changes the AOD master switch. */
internal object AodWallpaperControl {
    private const val OVERLAY = "com.android.shell:dev.nativecomplements.aodwallpaper"
    private const val KEY = "doze_always_on_wallpaper_enabled"
    private val operation = java.util.concurrent.locks.ReentrantLock()
    data class State(val capability: Boolean, val wallpaper: Boolean) {
        val enabled: Boolean get() = capability && wallpaper
    }

    internal fun parse(output: String): State {
        val values = output.lineSequence().map { it.trim() }
        val capability = values.singleOrNull { it.startsWith("NC_CAP=") }?.removePrefix("NC_CAP=")
        val setting = output.lineSequence().map { it.trim() }
            .singleOrNull { it.startsWith("NC_SETTING=") }?.removePrefix("NC_SETTING=")
        require(capability == "true" || capability == "false") { "无法确认系统息屏壁纸能力" }
        require(setting == "1" || setting == "0" || setting == "null") { "无法确认息屏壁纸设置" }
        // The native WallpaperRepository defaults an absent wallpaper setting to 1.
        return State(capability == "true", setting != "0")
    }

    private fun userId() = Process.myUid() / 100000
    private fun query(user: Int) = """
        cap=${'$'}(cmd overlay lookup --user $user android android:bool/config_dozeSupportsAodWallpaper) || exit 1
        setting=${'$'}(settings --user $user get secure $KEY) || exit 1
        printf '\nNC_CAP=%s\nNC_SETTING=%s\n' "${'$'}cap" "${'$'}setting"
    """.trimIndent()

    internal fun changeScript(user: Int, enabled: Boolean): String {
        require(user >= 0)
        return if (enabled) """
            cmd overlay fabricate --target android --name dev.nativecomplements.aodwallpaper android:bool/config_dozeSupportsAodWallpaper 0x12 0x1 &&
            cmd overlay enable --user $user $OVERLAY &&
            settings --user $user put secure $KEY 1
        """.trimIndent() else """
            overlays=${'$'}(cmd overlay list --user $user android) || exit 1
            if printf '%s\n' "${'$'}overlays" | grep -Fq '$OVERLAY'; then
                cmd overlay disable --user $user $OVERLAY || exit 1
            fi
            settings --user $user put secure $KEY 0
        """.trimIndent()
    }

    private fun <T> exclusive(block: () -> T): T {
        check(operation.tryLock()) { "上一次操作尚未结束，请稍后重新读取" }
        try { return block() } finally { operation.unlock() }
    }

    fun read(context: Context): State = exclusive { parse(runRoot(context, query(userId()))) }

    fun set(context: Context, enabled: Boolean): State = exclusive {
        val user = userId()
        val state = parse(runRoot(context, "(\n${changeScript(user, enabled)}\n) && {\n${query(user)}\n}"))
        check(state.wallpaper == enabled && (!enabled || state.capability)) { "系统读回状态与请求不符" }
        state
    }

    private fun runRoot(context: Context, script: String): String {
        val output = File.createTempFile("aod-wallpaper-", ".tmp", context.cacheDir)
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { "root 操作超时，请检查授权" }
            check(process.exitValue() == 0) { "root 命令未完成；若已开始切换，可能仅部分生效，请重新读取状态" }
            check(output.length() <= 65536) { "系统返回内容异常" }
            return output.readText()
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            output.delete()
        }
    }
}
