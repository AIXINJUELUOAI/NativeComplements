package dev.local.sunwindowdemo.core

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Run a fixed, caller-built root command off the UI thread. */
internal object RootCommand {
    private val permissions = RootPermissionRequests(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** A fresh UID check for each user request; only simultaneous requests share a result. */
    suspend fun requestRoot(context: Context): Boolean = request(context, startup = false) == true

    /** Activity/page recreation must not repeatedly open the root manager's authorization dialog. */
    suspend fun requestStartupRoot(context: Context) { request(context, startup = true) }

    private suspend fun request(context: Context, startup: Boolean): Boolean? {
        val application = context.applicationContext
        return permissions.request(startup) {
            try { isRootUid(run(application, "id -u", 60)) } catch (_: Exception) { false }
        }
    }

    internal fun isRootUid(output: String): Boolean = output.trim() == "0"

    fun run(context: Context, script: String, timeoutSeconds: Long = 20): String {
        val output = File.createTempFile("root-command-", ".tmp", context.cacheDir)
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
            process.outputStream.close()
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "root 操作超时，请检查授权" }
            check(process.exitValue() == 0) { "root 命令未完成；若已开始切换，可能仅部分生效，请重新读取状态" }
            check(output.length() <= 65536) { "系统返回内容异常" }
            return output.readText()
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            output.delete()
        }
    }
}

/** Keep an in-flight prompt alive across Activity cancellation, but never cache granted access. */
internal class RootPermissionRequests(private val scope: CoroutineScope) {
    private var startupRequested = false
    private var inFlight: Deferred<Boolean>? = null

    suspend fun request(startup: Boolean = false, probe: suspend () -> Boolean): Boolean? {
        val pending = synchronized(this) {
            if (startup && startupRequested) return null
            if (startup) startupRequested = true
            inFlight?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) { probe() }
                .also { inFlight = it; it.start() }
        }
        return pending.await()
    }
}
