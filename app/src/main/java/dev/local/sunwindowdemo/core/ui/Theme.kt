package dev.local.sunwindowdemo.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComplementsTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(LocalContext.current)
        dynamic -> dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(primary = Color(0xFFB5C9ED), background = Color(0xFF141318), surface = Color(0xFF141318), surfaceContainerLow = Color(0xFF1D1C22), surfaceContainerHighest = Color(0xFF35343B))
        else -> lightColorScheme(primary = Color(0xFF405F91), background = Color(0xFFEFEEF6), surface = Color(0xFFEFEEF6), surfaceContainerLow = Color(0xFFFAF8FE), surfaceContainerHighest = Color(0xFFE2E2EC))
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
