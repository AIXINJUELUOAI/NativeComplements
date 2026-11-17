package dev.local.sunwindowdemo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The visible composition owns start/close; this backend never owns a window or global media keys. */
internal class GameMediaState(context: Context) : AutoCloseable {
    var cover: Bitmap? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var canPrevious by mutableStateOf(false)
        private set
    var canToggle by mutableStateOf(false)
        private set
    var canNext by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var listening = false
    private var selected: MediaController? = null
    private val controllers = linkedMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }

    fun start() {
        if (listening) return
        try {
            listening = true
            manager.addOnActiveSessionsChangedListener(sessions, null, main)
            // This API is scoped to the calling user; never query all users or dispatch global keys.
            update(manager.getActiveSessions(null))
        } catch (_: RuntimeException) { close() }
    }

    private fun update(active: List<MediaController>) {
        if (!listening) return
        try {
            val tokens = active.map { it.sessionToken }.toSet()
            controllers.keys.filter { it !in tokens }.toList().forEach { token ->
                controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
            }
            active.forEach { controller ->
                val token = controller.sessionToken
                if (token !in controllers) {
                    val callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) { render() }
                        override fun onMetadataChanged(metadata: MediaMetadata?) { render() }
                        override fun onSessionDestroyed() {
                            if (!listening || controllers[token]?.second !== this) return
                            controllers.remove(token)?.let { (old, listener) -> old.unregisterCallback(listener) }
                            render()
                        }
                    }
                    controllers[token] = controller to callback
                    controller.registerCallback(callback, main)
                }
            }
            render()
        } catch (_: RuntimeException) { close() }
    }

    private fun render() {
        if (!listening) return
        try {
            val available = controllers.values.map { it.first }
            selected = available.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: available.firstOrNull { it.sessionToken == selected?.sessionToken } ?: available.firstOrNull()
            val controller = selected
            val state = controller?.playbackState
            val actions = state?.actions ?: 0L
            playing = state?.state == PlaybackState.STATE_PLAYING
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
            val action = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
            canToggle = actions and (action or PlaybackState.ACTION_PLAY_PAUSE) != 0L
            val metadata = controller?.metadata
            cover = (metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))?.takeUnless { it.isRecycled }
        } catch (_: RuntimeException) { close() }
    }

    fun previous() = control(PlaybackState.ACTION_SKIP_TO_PREVIOUS) { skipToPrevious() }
    fun next() = control(PlaybackState.ACTION_SKIP_TO_NEXT) { skipToNext() }
    fun toggle() {
        try {
            val active = selected?.playbackState?.state == PlaybackState.STATE_PLAYING
            val action = if (active) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
            control(action, PlaybackState.ACTION_PLAY_PAUSE) { if (active) pause() else play() }
        } catch (_: RuntimeException) { close() }
    }

    private fun control(action: Long, alternative: Long = 0, command: MediaController.TransportControls.() -> Unit) {
        if (!listening) return
        try {
            val controller = selected ?: return
            if ((controller.playbackState?.actions ?: 0L) and (action or alternative) == 0L) return
            controller.transportControls.command()
        } catch (_: RuntimeException) { close() }
    }

    override fun close() {
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        listening = false
        controllers.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
        selected = null
        cover = null
        playing = false
        canPrevious = false
        canToggle = false
        canNext = false
    }
}
