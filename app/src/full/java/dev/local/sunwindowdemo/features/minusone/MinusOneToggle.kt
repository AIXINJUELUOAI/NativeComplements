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
        value = supported && enabled,
        enabled = supported,
    ) {
        enabled = it
        MinusOneSettings.setEnabled(context, it)
    }
}
