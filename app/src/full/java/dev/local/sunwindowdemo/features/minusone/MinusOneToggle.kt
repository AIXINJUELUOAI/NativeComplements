package dev.local.sunwindowdemo.features.minusone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.ui.ToggleRow

@Composable
fun MinusOneToggle() {
    val context = LocalContext.current
    val supported = remember(context) { LauncherMinusOne.supported(context) }
    var enabled by remember(context) { mutableStateOf(MinusOneSettings.enabled(context)) }
    ToggleRow(
        "桌面负一屏",
        if (supported) "首屏右滑打开微件画布。切换后重启桌面生效"
        else "需要 Android 17 和 Pixel 启动器 907",
        value = supported && enabled,
        enabled = supported,
    ) {
        enabled = it
        MinusOneSettings.setEnabled(context, it)
    }
}
