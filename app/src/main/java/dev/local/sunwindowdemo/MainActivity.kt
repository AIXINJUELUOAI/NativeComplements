package dev.local.sunwindowdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.navigation.ModuleApp
import dev.local.sunwindowdemo.navigation.Page

open class MainActivity : ComponentActivity() {
    private lateinit var controller: WindowController
    protected open val initialPage: Page get() = Page.Home
    protected open val initialAppPackage: String? get() = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dev.local.sunwindowdemo.features.diagnostics.Diagnostics.initialize(this, dev.local.sunwindowdemo.features.diagnostics.Diagnostics.APP)
        controller = WindowController(this)
        setContent { ComplementsTheme { ModuleApp(controller, initialPage, ::openPage, ::finish, initialAppPackage, ::openIconApp) } }
        if (intent.action == "recover") controller.restore()
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == "recover") controller.restore()
    }
    override fun onDestroy() { if (::controller.isInitialized) controller.close(); super.onDestroy() }
    private fun openPage(page: Page) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE_EXTRA, page.name))
    }
    private fun openIconApp(pkg: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE_EXTRA, Page.IconApp.name).putExtra(APP_EXTRA, pkg))
    }

    companion object { const val PAGE_EXTRA = "settingsPage"; const val APP_EXTRA = "iconAppPackage" }
}

// Preserve the component referenced by Android accessibility settings and the existing fan panel.
class SettingsActivity : MainActivity() {
    override val initialPage: Page
        get() = Page.entries.firstOrNull { it.name == intent.getStringExtra(PAGE_EXTRA) } ?: Page.Window
    override val initialAppPackage: String? get() = intent.getStringExtra(APP_EXTRA)
}
