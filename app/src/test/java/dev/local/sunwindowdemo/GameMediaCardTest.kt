package dev.local.sunwindowdemo

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaSessionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class GameMediaCardTest {
    private val activity = Robolectric.buildActivity(Activity::class.java)
    private val sessions = mutableListOf<MediaSession>()
    private lateinit var media: GameMediaState
    private lateinit var manager: MediaSessionManager

    @Before fun setup() {
        activity.setup()
        manager = activity.get().getSystemService(MediaSessionManager::class.java)
        shadowOf(manager).clearControllers()
        media = GameMediaState(activity.get())
        media.start()
        idle()
    }

    @After fun close() {
        media.close()
        sessions.forEach { it.release() }
        shadowOf(manager).clearControllers()
        activity.pause().stop().destroy()
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun controller(state: Int, actions: Long): MediaController {
        // The two-argument constructor is a no-op in Robolectric's ShadowMediaSession.
        val session = MediaSession(activity.get(), "test-${sessions.size}", Bundle())
        sessions += session
        session.isActive = true
        return MediaController(activity.get(), session.sessionToken).also {
            shadowOf(it).setPlaybackState(PlaybackState.Builder().setState(state, 0, 1f).setActions(actions).build())
        }
    }
    private fun add(controller: MediaController) { shadowOf(manager).addController(controller); idle() }
    private fun state(controller: MediaController, state: Int, actions: Long) {
        shadowOf(controller).executeOnPlaybackStateChanged(
            PlaybackState.Builder().setState(state, 0, 1f).setActions(actions).build())
        idle()
    }
    private fun assertEmpty() {
        assertNull(media.cover)
        assertFalse(media.playing)
        assertFalse(media.canPrevious)
        assertFalse(media.canToggle)
        assertFalse(media.canNext)
    }

    @Test fun noSessionLeavesNeutralDisabledStateAndStartCloseAreRepeatable() {
        assertEmpty()
        media.previous(); media.toggle(); media.next()
        media.start(); media.start()
        val current = controller(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY)
        add(current)
        assertTrue(media.canToggle)
        assertEquals(1, shadowOf(current).callbacks.size)
        media.close(); media.close()
        assertEmpty()
        assertTrue(shadowOf(current).callbacks.isEmpty())
        media.start(); media.start()
        assertTrue(media.canToggle)
        assertEquals(1, shadowOf(current).callbacks.size)
    }

    @Test fun controlsOnlyPlayingSessionHonorsActionsAndDoesNotFakePlayback() {
        val paused = controller(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY)
        val playing = controller(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
        add(paused); add(playing)
        assertTrue(media.playing)
        assertFalse(media.canPrevious)
        assertTrue(media.canNext)
        media.toggle()
        assertTrue(media.playing)
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(playing.transportControls).lastPerformedAction)
        assertNotEquals(PlaybackState.ACTION_PAUSE, shadowOf(paused.transportControls).lastPerformedAction)
        media.previous()
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(playing.transportControls).lastPerformedAction)
        media.next()
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(playing.transportControls).lastPerformedAction)
        state(playing, PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertFalse(media.playing)
        assertFalse(media.canNext)
        media.toggle()
        assertEquals(PlaybackState.ACTION_PLAY, shadowOf(playing.transportControls).lastPerformedAction)
        media.previous()
        assertEquals(PlaybackState.ACTION_SKIP_TO_PREVIOUS, shadowOf(playing.transportControls).lastPerformedAction)
        // A changed native state is checked at click time, even before its callback reaches Compose.
        shadowOf(playing).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).setActions(0).build())
        media.toggle(); media.next()
        assertEquals(PlaybackState.ACTION_SKIP_TO_PREVIOUS, shadowOf(playing.transportControls).lastPerformedAction)
    }

    @Test fun bitmapUpdatesCloseAndSessionDestructionReleaseCallbacksAndCover() {
        val current = controller(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE)
        add(current)
        val art = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val metadata = MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build()
        shadowOf(current).executeOnMetadataChanged(metadata); idle()
        assertSame(art, media.cover)
        val oldCallback = shadowOf(current).callbacks.single()
        media.close()
        assertEmpty()
        assertTrue(shadowOf(current).callbacks.isEmpty())
        oldCallback.onMetadataChanged(metadata)
        assertEmpty()
        media.start()
        assertSame(art, media.cover)
        assertEquals(1, shadowOf(current).callbacks.size)
        oldCallback.onSessionDestroyed()
        assertEquals(1, shadowOf(current).callbacks.size)
        shadowOf(current).executeOnSessionDestroyed(); idle()
        assertEmpty()
        assertTrue(shadowOf(current).callbacks.isEmpty())
    }

    @Test fun activeSessionRemovalAndPlayingSwitchChooseRemainingSession() {
        val first = controller(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE)
        val second = controller(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY)
        add(first); add(second)
        state(first, PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY)
        state(second, PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE)
        media.toggle()
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(second.transportControls).lastPerformedAction)
        shadowOf(manager).clearControllers(); idle()
        assertTrue(shadowOf(first).callbacks.isEmpty())
        assertTrue(shadowOf(second).callbacks.isEmpty())
        assertEmpty()
        add(first)
        assertFalse(media.playing)
        assertTrue(media.canToggle)
    }

    @Implements(MediaSessionManager::class)
    class DeniedMediaSessions : ShadowMediaSessionManager() {
        @Implementation override fun getActiveSessions(component: ComponentName?): List<MediaController> =
            throw SecurityException("No media session permission")
    }

    @Test @Config(shadows = [DeniedMediaSessions::class])
    fun permissionFailureLeavesDisabledStateAndCloseIsSafe() {
        assertEmpty()
        media.start(); media.previous(); media.toggle(); media.next(); media.close()
        assertEmpty()
        val current = controller(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE)
        add(current)
        assertTrue(shadowOf(current).callbacks.isEmpty())
        assertEmpty()
    }
}
