package dev.local.sunwindowdemo.features.navbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavbarSearchTest {
    private val pkg = "github.tornaco.android.thanos"
    private val activity = "now.fortuitous.thanos.main.NavActivity"

    @Test fun fullComponentWithDifferentActivityNamespaceMatchesTheActivity() {
        assertTrue(navbarSearchMatches("$pkg/$activity", "灭霸", pkg, activity))
        assertFalse(navbarSearchMatches("$pkg/$activity", "灭霸", pkg))
        assertFalse(navbarSearchMatches("$pkg/$activity", "灭霸", pkg, "now.fortuitous.thanos.SettingsActivity"))
    }

    @Test fun labelPackageActivityAndCaseInsensitiveQueriesMatch() {
        assertTrue(navbarSearchMatches("灭霸", "灭霸", pkg, activity))
        assertTrue(navbarSearchMatches(pkg, "灭霸", pkg, activity))
        assertTrue(navbarSearchMatches("  NAVACTIVITY  ", "灭霸", pkg, activity))
        assertTrue(navbarSearchMatches(activity, "灭霸", pkg, activity))
        assertTrue(navbarSearchMatches("", "灭霸", pkg, activity))
        assertFalse(navbarSearchMatches("NoSuchActivity", "灭霸", pkg, activity))
    }

    @Test fun relativeAndFullComponentNamesMatch() {
        assertTrue(navbarSearchMatches("example.app/.MainActivity", "示例", "example.app", "example.app.MainActivity"))
        assertTrue(navbarSearchMatches("example.app/example.app.MainActivity", "示例", "example.app", ".MainActivity"))
        assertFalse(navbarSearchMatches("other.app/.MainActivity", "示例", "example.app", "example.app.MainActivity"))
    }
}
