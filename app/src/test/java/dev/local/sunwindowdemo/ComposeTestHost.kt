package dev.local.sunwindowdemo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** Local JVM host: no ActivityScenario/package-manager manifest resolution. */
class ComposeTestHost : ExternalResource() {
    private lateinit var controller: ActivityController<ComponentActivity>
    val activity: ComponentActivity get() = controller.get()

    override fun before() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    override fun after() {
        controller.pause().stop().destroy()
    }
}

fun createRobolectricComposeRule() = AndroidComposeTestRule(
    activityRule = ComposeTestHost(),
    activityProvider = { it.activity },
)
