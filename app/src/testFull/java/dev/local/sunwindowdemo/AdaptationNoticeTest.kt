package dev.local.sunwindowdemo

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.adaptation.CommunityAdaptation
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptationNoticeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val scenarios = mutableListOf<ActivityScenario<MainActivity>>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun close() { scenarios.asReversed().forEach { it.close() } }

    private fun home(): Pair<ActivityScenario<MainActivity>, MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            .also { scenarios += it }
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        compose.waitForIdle()
        return scenario to activity
    }

    @Test fun oneNoticePerRecordSurvivesRecreationAndBuildUpdatesAndAboutRoutesSafely() {
        ShadowSystemProperties.override("ro.lineage.build.version", "24.0")
        ShadowSystemProperties.override("ro.lineage.version", "24.0-20261116-NIGHTLY-device")
        val record = requireNotNull(CommunityAdaptation.current())
        val prefs = context.getSharedPreferences("community_adaptation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val (scenario, _) = home()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("社区适配").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("社区适配").assertIsDisplayed()
        compose.waitUntil(5_000) { prefs.getBoolean(record.seenKey(), false) }
        scenario.recreate()
        compose.onNodeWithText("社区适配").assertIsDisplayed()
        compose.onNodeWithText("查看适配者").performClick()
        val recreated = activityAfterRecreate(scenario)
        lateinit var about: Intent
        compose.runOnIdle { about = requireNotNull(shadowOf(recreated).nextStartedActivity) }
        scenario.moveToState(Lifecycle.State.CREATED)
        val aboutScenario = ActivityScenario.launch<MainActivity>(about).also { scenarios += it }
        lateinit var aboutActivity: MainActivity
        aboutScenario.onActivity { aboutActivity = it }
        compose.onNodeWithText("适配者").assertIsDisplayed()
        compose.onNodeWithText("LFRon · LineageOS 24.0").performClick()
        compose.runOnIdle {
            assertEquals(record.authorUrl(), shadowOf(aboutActivity).nextStartedActivity.dataString)
        }
        compose.onNodeWithText("反馈问题").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(CommunityAdaptation.PROJECT_ISSUES, shadowOf(aboutActivity).nextStartedActivity.dataString)
        }
        aboutScenario.close()
        scenario.close()
        ShadowSystemProperties.override("ro.lineage.version", "24.0-20270101-UNOFFICIAL-device")
        home()
        compose.onNodeWithText("社区适配").assertDoesNotExist()
        compose.onNodeWithText("适配者").assertDoesNotExist()
    }

    @Test fun unknownHasNoAttributionNoticeAndUsesProjectFeedback() {
        ShadowSystemProperties.override("ro.lineage.build.version", "")
        ShadowSystemProperties.override("ro.lineage.version", "")
        val (scenario, activity) = home()
        compose.onNodeWithText("社区适配").assertDoesNotExist()
        compose.onNodeWithText("关于").performScrollTo().performClick()
        lateinit var about: Intent
        compose.runOnIdle { about = requireNotNull(shadowOf(activity).nextStartedActivity) }
        scenario.moveToState(Lifecycle.State.CREATED)
        val aboutScenario = ActivityScenario.launch<MainActivity>(about).also { scenarios += it }
        lateinit var aboutActivity: MainActivity
        aboutScenario.onActivity { aboutActivity = it }
        compose.onNodeWithText("适配者").assertDoesNotExist()
        compose.onNodeWithText("反馈问题").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(CommunityAdaptation.PROJECT_ISSUES, shadowOf(aboutActivity).nextStartedActivity.dataString)
        }
    }

    private fun activityAfterRecreate(scenario: ActivityScenario<MainActivity>): MainActivity {
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        return activity
    }
}
