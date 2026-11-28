package dev.local.sunwindowdemo.features.manualhbm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.RootCommand
import dev.local.sunwindowdemo.core.ui.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HbmTileSettings() {
    if (Build.VERSION.SDK_INT != 37 || Build.DEVICE != "dada") return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var added by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() {
        if (!busy) scope.launch {
            runCatching { withContext(Dispatchers.IO) { HbmTiles.read(context) } }
                .onSuccess { added = it }
        }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("sysui_qs_tiles"), false, observer)
        refresh()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    ToggleRow("手动峰值亮度", value = added, enabled = !busy) { enabled ->
        busy = true
        scope.launch {
            val succeeded = runCatching {
                withContext(Dispatchers.IO) { HbmTiles.set(context, enabled) }
            }.getOrDefault(false)
            if (succeeded) added = enabled
            busy = false
            Toast.makeText(context, if (succeeded) {
                if (enabled) "已加入磁贴" else "已移除磁贴"
            } else "磁贴操作未完成", Toast.LENGTH_SHORT).show()
        }
    }
}

internal object HbmTiles {
    private fun component(context: Context) = ComponentName(context, HbmTileService::class.java)
    internal fun listed(tiles: String, target: ComponentName): Boolean = tiles.trim().split(',').any { spec ->
        spec.startsWith("custom(") && spec.endsWith(')') &&
            ComponentName.unflattenFromString(spec.substring(7, spec.length - 1)) == target
    }
    fun read(context: Context): Boolean = listed(RootCommand.run(context,
        "settings --user ${Process.myUid() / 100000} get secure sysui_qs_tiles"), component(context))

    suspend fun set(context: Context, enabled: Boolean): Boolean {
        val user = Process.myUid() / 100000
        val foreground = RootCommand.run(context, "am get-current-user").trim().toInt()
        check(foreground == user) { "当前用户不匹配" }
        if (!enabled) context.sendOrderedBroadcast(Intent(HbmControl.ACTION).setPackage("android")
            .putExtra(HbmControl.STOP, true), null)
        val operation = if (enabled) "add-tile" else "remove-tile"
        RootCommand.run(context, "cmd statusbar $operation '${component(context).flattenToString()}'")
        repeat(20) {
            if (read(context) == enabled) return true
            delay(100)
        }
        return false
    }
}
