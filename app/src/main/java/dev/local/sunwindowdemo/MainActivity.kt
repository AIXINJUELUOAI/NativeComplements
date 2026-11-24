package dev.local.sunwindowdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.local.sunwindowdemo.core.RootCommand
import kotlinx.coroutines.launch
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
        setContent { ComplementsTheme { ModuleApp(controller, initialPage, ::openPage, ::finish, initialAppPackage,
            { openAppSettings(Page.IconApp, it) }, { openAppSettings(Page.BackgroundApp, it) }) } }
        if (intent.action == "recover") controller.restore()
        requestStartupRoot()
    }
    private fun requestStartupRoot() {
        if (javaClass == MainActivity::class.java) {
            lifecycleScope.launch { RootCommand.requestStartupRoot(applicationContext) }
        }
    }
    override fun onResume() { super.onResume(); if (::controller.isInitialized) controller.resume() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == "recover") controller.restore()
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) requestStartupRoot()
    }
    override fun onDestroy() { if (::controller.isInitialized) controller.close(); super.onDestroy() }
    private fun openPage(page: Page) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE_EXTRA, page.name))
    }
    private fun openAppSettings(page: Page, pkg: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE_EXTRA, page.name).putExtra(APP_EXTRA, pkg))
    }

    companion object { const val PAGE_EXTRA = "settingsPage"; const val APP_EXTRA = "iconAppPackage" }
}

// Preserve the component referenced by Android accessibility settings and the existing fan panel.
class SettingsActivity : MainActivity() {
    override val initialPage: Page
        get() {
            val destination = intent.getStringExtra(PAGE_EXTRA) ?: return Page.Window
            if (destination == "GameApps") return Page.GameMode
            if (destination == "Tombstone") return Page.Background
            return Page.entries.firstOrNull { it.name == destination } ?: Page.Home
        }
    override val initialAppPackage: String? get() = intent.getStringExtra(APP_EXTRA)
}
