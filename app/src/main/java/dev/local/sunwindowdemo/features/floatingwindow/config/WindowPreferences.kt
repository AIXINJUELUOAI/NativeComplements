package dev.local.sunwindowdemo

import android.content.Context

/** UI adapter over the original preferences. The SystemUI host receives a read-only snapshot. */
data class WindowPreferences(
    val enabled: Boolean = true,
    val longBackWindow: Boolean = false,
    val apps: List<String> = emptyList(), val targetLabels: Map<String, String> = emptyMap(), val blocked: Set<String> = emptySet(),
    val heat: Int = 36, val distance: Int = 42, val selectionDistance: Int = 90,
    val radius: Int = 180, val icon: Int = 44, val fanCount: Int = 4,
    val hideTopHint: Boolean = false, val showHeat: Boolean = false, val menuBlur: Boolean = false,
    val windowDim: Int = Config.DEFAULT_WINDOW_DIM,
    val landscapeOff: Boolean = true, val keyboardOff: Boolean = true,
    val autoMinimizeOnOpen: Boolean = true,
    val endOnClose: Boolean = false, val keepEdgeAwake: Boolean = false,
    val hideRecents: Boolean = false, val doubleClose: Boolean = false, val notificationWindow: Boolean = true, val landscapeShareWindow: Boolean = true,
)

class WindowPreferenceStore(private val context: Context) {
    companion object { const val MAX_TARGETS = Config.MAX_TARGETS }
    private val config = Config(context)
    val preferences = config.prefs!!
    fun read() = WindowPreferences(
        enabled = config.enabled(), longBackWindow = config.longBackWindow(), apps = config.targets().toList(), targetLabels = config.targetLabels().toMap(), blocked = config.blocked().toSet(),
        heat = config.heat(), distance = config.distance(), selectionDistance = config.selectionDistance(),
        radius = config.radius(), icon = config.icon(), fanCount = config.fanCount(), hideTopHint = config.hideTopHint(),
        showHeat = config.showHeat(), menuBlur = config.menuBlur(), windowDim = config.windowDim(), landscapeOff = config.landscapeOff(), keyboardOff = config.keyboardOff(),
        autoMinimizeOnOpen = config.autoMinimizeOnOpen(), endOnClose = config.endOnClose(), keepEdgeAwake = config.keepEdgeAwake(),
        hideRecents = config.hideRecents(), doubleClose = config.doubleClose(), notificationWindow = config.notificationWindow(), landscapeShareWindow = config.landscapeShareWindow(),
    )
    fun boolean(key: String, value: Boolean) {
        require(key in setOf("enabled", "longBackWindow", "hideTopHint", "showHeat", "menuBlur", "landscape", "keyboard", "hideRecents", "doubleClose", "notificationWindow", "landscapeShareWindow", "autoMinimizeOnOpen", "endOnClose", "keepEdgeAwake"))
        preferences.edit().putBoolean(key, value).apply()
        ConfigProvider.changed(context)
    }
    fun number(key: String, value: Int) {
        val range = when (key) {
            "heat" -> 36..72; "distance" -> 24..100; "selectionDistance" -> 50..100
            "radius" -> 120..260; "icon" -> 32..56; "fanCount" -> 1..5; "windowDim" -> 0..100; else -> error("Unknown preference")
        }
        preferences.edit().putInt(key, value.coerceIn(range)).apply()
        ConfigProvider.changed(context)
    }
    fun apps(packages: List<String>) { config.targets(packages.filter(ShortcutTarget::validKey).distinct()); ConfigProvider.changed(context) }
    fun blocked(packages: Set<String>) {
        preferences.edit().putStringSet("blocked", packages.filter(Rules::validPackage).toSet()).apply()
        ConfigProvider.changed(context)
    }
}
