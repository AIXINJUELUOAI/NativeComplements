package dev.local.sunwindowdemo.core.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComplementsTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var paletteRevision by remember(context) { mutableIntStateOf(0) }
    if (dynamic) DisposableEffect(context, lifecycle) {
        val palette = SystemPalette(context) { paletteRevision++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) palette.recheck()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            palette.close()
        }
    }
    val colors = remember(context, configuration, paletteRevision, dynamic, dark) {
        when {
            dynamic && dark -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            dark -> darkColorScheme(primary = Color(0xFFB5C9ED), background = Color(0xFF141318), surface = Color(0xFF141318), surfaceContainerLow = Color(0xFF1D1C22), surfaceContainerHighest = Color(0xFF35343B))
            else -> lightColorScheme(primary = Color(0xFF405F91), background = Color(0xFFEFEEF6), surface = Color(0xFFEFEEF6), surfaceContainerLow = Color(0xFFFAF8FE), surfaceContainerHighest = Color(0xFFE2E2EC))
        }
    }
    val view = LocalView.current
    SideEffect {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        val window = (current as? Activity)?.window
        if (window != null) {
            // Insets and transition gaps must use the same dynamic surface as the pages.
            window.setBackgroundDrawable(ColorDrawable(colors.surface.toArgb()))
            val darkIcons = colors.surface.luminance() > .5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
            // Draw the page under both gesture and three-button navigation without a second scrim.
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
