package dev.local.sunwindowdemo.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.R
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun AboutScreen(onLicenses: () -> Unit, onTools: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var versionTaps by remember { mutableIntStateOf(0) }
    var toolsVisible by rememberSaveable { mutableStateOf(false) }
    val icon = remember {
        val drawable = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
        Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, 192, 192)
            drawable.draw(Canvas(it))
        }.asImageBitmap()
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
        Text("类原生补全计划", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text("版本 ${BuildConfig.VERSION_NAME}",
            Modifier.clickable { versionTaps++; if (versionTaps >= 7) toolsVisible = true }.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsGroup {
        SettingRow("开发者", "AIXINJUELUOAI", { uri.openUri("https://github.com/AIXINJUELUOAI") })
        SettingRow("项目主页", onClick = { uri.openUri("https://github.com/AIXINJUELUOAI/NativeComplements") })
        SettingRow("反馈问题", onClick = { uri.openUri("https://github.com/AIXINJUELUOAI/NativeComplements/issues") })
        SettingRow("开源许可", onClick = onLicenses)
    }
    if (toolsVisible) {
        Spacer(Modifier.height(24.dp))
        SettingsGroup { SettingRow("开发者工具", onClick = onTools) }
    }
}
