package dev.local.sunwindowdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.navigation.ModuleApp

open class MainActivity : ComponentActivity() {
    private lateinit var controller: WindowController
    protected open val startInWindowSettings = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = WindowController(this)
        setContent { ComplementsTheme { ModuleApp(controller, startInWindowSettings, onExit = { finish() }) } }
        if (intent.action == "recover") controller.restore()
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == "recover") controller.restore()
    }
    override fun onDestroy() { if (::controller.isInitialized) controller.close(); super.onDestroy() }
}

// Preserve the component referenced by Android accessibility settings and the existing fan panel.
class SettingsActivity : MainActivity() { override val startInWindowSettings = true }
