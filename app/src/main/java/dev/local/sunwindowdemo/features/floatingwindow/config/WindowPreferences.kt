package dev.local.sunwindowdemo

import android.content.Context

/** UI adapter over the original preferences. Runtime Config.java stays byte-for-byte unchanged. */
data class WindowPreferences(
    val apps: List<String> = emptyList(), val blocked: Set<String> = emptySet(),
    val heat: Int = 36, val distance: Int = 42, val selectionDistance: Int = 90,
    val radius: Int = 180, val icon: Int = 44,
    val hideTopHint: Boolean = false, val showHeat: Boolean = false,
    val landscapeOff: Boolean = true, val keyboardOff: Boolean = true,
    val hideRecents: Boolean = false, val doubleClose: Boolean = true,
)

class WindowPreferenceStore(context: Context) {
    private val config = Config(context)
    val preferences = config.prefs!!
    fun read() = WindowPreferences(
        apps = config.apps().toList(), blocked = config.blocked().toSet(),
        heat = config.heat(), distance = config.distance(), selectionDistance = config.selectionDistance(),
        radius = config.radius(), icon = config.icon(), hideTopHint = config.hideTopHint(),
        showHeat = config.showHeat(), landscapeOff = config.landscapeOff(), keyboardOff = config.keyboardOff(),
        hideRecents = config.hideRecents(), doubleClose = config.doubleClose(),
    )
    fun boolean(key: String, value: Boolean) {
        require(key in setOf("hideTopHint", "showHeat", "landscape", "keyboard", "hideRecents", "doubleClose"))
        preferences.edit().putBoolean(key, value).apply()
    }
    fun number(key: String, value: Int) {
        val range = when (key) {
            "heat" -> 36..72; "distance" -> 24..100; "selectionDistance" -> 50..100
            "radius" -> 120..260; "icon" -> 32..56; else -> error("Unknown preference")
        }
        preferences.edit().putInt(key, value.coerceIn(range)).apply()
    }
    fun apps(packages: List<String>) = config.apps(packages.filter(Rules::validPackage).distinct().take(5))
    fun blocked(packages: Set<String>) {
        preferences.edit().putStringSet("blocked", packages.filter(Rules::validPackage).toSet()).apply()
    }
}
