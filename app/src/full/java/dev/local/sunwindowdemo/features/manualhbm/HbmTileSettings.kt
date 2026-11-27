package dev.local.sunwindowdemo.features.manualhbm

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.R
import dev.local.sunwindowdemo.core.ui.SettingRow

@Composable
fun HbmTileSettings() {
    if (Build.VERSION.SDK_INT != 37 || Build.DEVICE != "dada") return
    val context = LocalContext.current
    SettingRow("手动峰值亮度", "关闭自动亮度后使用快捷磁贴临时开启，最长 10 分钟，息屏退出")
    SettingRow("添加峰值亮度磁贴", onClick = {
        context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(context, HbmTileService::class.java),
            context.getString(R.string.manual_hbm_label),
            Icon.createWithResource(context, R.drawable.ic_manual_hbm),
            context.mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加磁贴"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已在控制中心"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "未添加磁贴"
                else -> "添加请求未完成，可在控制中心编辑磁贴"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    })
}
