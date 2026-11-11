package dev.local.sunwindowdemo

import android.app.NotificationManager
import android.content.*
import android.database.ContentObserver
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.widget.Toast
import java.util.concurrent.Executors

/** Plain application-style shortcuts in the existing SystemUI small-window menu. */
@android.annotation.SuppressLint("MissingPermission")
class WindowQuickHost(private val context: Context, private val dismiss: Runnable): AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val media = context.getSystemService(MediaSessionManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val resources by lazy { context.createPackageContext(Wire.PKG, 0) }
    private var page: View? = null
    private var controller: MediaController? = null
    private var attached = false
    private var sessionWatching = false
    private var lastVolume = 1
    private val observer = object: ContentObserver(main) { override fun onChange(selfChange: Boolean) { refresh() } }
    private val receiver = object: BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { refresh() } }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { chooseMedia(it.orEmpty()) }
    private val callback = object: MediaController.Callback() {
        override fun onPlaybackStateChanged(value: PlaybackState?) { refresh() }
        override fun onSessionDestroyed() { chooseMedia(runCatching { media.getActiveSessions(null) }.getOrDefault(emptyList())) }
    }

    fun view(): View {
        detach()
        val view = AppList.quickPage(context, emptyList(), ::action)
        page = view; attached = true
        try {
            context.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction("android.media.VOLUME_CHANGED_ACTION")
            }, null, main, Context.RECEIVER_NOT_EXPORTED)
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
            runCatching {
                media.addOnActiveSessionsChangedListener(sessions, null, main); sessionWatching = true
                chooseMedia(media.getActiveSessions(null))
            }
            refresh()
        } catch (error: RuntimeException) { detach(); throw error }
        return view
    }

    private fun chooseMedia(values: List<MediaController>) {
        if (!attached) return
        val next = values.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: values.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback); controller = next; next?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val view = page ?: return
        if (!attached) return
        runCatching {
            fun entry(id: String, label: String, icon: Int, checked: Boolean? = null) =
                AppList.QuickEntry(id, label, resources.getDrawable(icon), checked)
            val enabled = wifi.isWifiEnabled
            val muted = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            AppList.updateQuick(view, buildList {
                add(entry("dnd", "免打扰", R.drawable.ic_game_do_not_disturb_on, notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL))
                add(entry("wifi", "Wi-Fi", if (enabled) R.drawable.ic_game_wifi else R.drawable.ic_game_wifi_off, enabled))
                add(entry("rotation", "旋转锁定", R.drawable.ic_game_screen_rotation, Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0))
                add(entry("mute", "静音", if (muted) R.drawable.ic_game_volume_off else R.drawable.ic_game_volume_up, muted))
                add(entry("screenshot", "截屏", R.drawable.ic_game_screenshot))
                add(entry("record", "录屏", R.drawable.ic_game_screen_record))
                if (controller != null) {
                    add(entry("previous", "上一首", R.drawable.ic_game_skip_previous))
                    add(entry("play", if (playing) "暂停" else "播放", if (playing) R.drawable.ic_game_pause else R.drawable.ic_game_play_arrow))
                    add(entry("next", "下一首", R.drawable.ic_game_skip_next))
                }
            })
        }.onFailure { android.util.Log.w("NativeComplements", "QUICK_STATE_UNAVAILABLE", it) }
    }

    private fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    private fun action(value: String) {
        if (!attached) return
        try {
            when (value) {
                "wifi" -> { @Suppress("DEPRECATION") val ok = wifi.setWifiEnabled(!wifi.isWifiEnabled); if (!ok) message("Wi-Fi 切换未完成") }
                "dnd" -> notifications.setInterruptionFilter(if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                "rotation" -> {
                    val lock = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) != 0
                    if (lock) Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, context.display?.rotation ?: Surface.ROTATION_0)
                    Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (lock) 0 else 1)
                }
                "mute" -> {
                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (current > 0) lastVolume = current
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, if (current > 0) 0 else lastVolume.coerceIn(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)), 0)
                }
                "play" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause() else controller?.transportControls?.play()
                "previous" -> controller?.transportControls?.skipToPrevious()
                "next" -> controller?.transportControls?.skipToNext()
                "screenshot" -> { dismiss.run(); main.postDelayed({ worker.execute { if (!WindowTools.screenshot(context)) main.post { message("截屏请求未完成") } } }, 240) }
                "record" -> { dismiss.run(); main.postDelayed({ if (!WindowTools.record()) message("录屏入口暂未就绪，请先打开一次系统快捷设置") }, 200) }
            }
            refresh()
        } catch (error: RuntimeException) { android.util.Log.w("NativeComplements", "QUICK_ACTION_FAILED $value", error); message("操作未完成，请稍后重试") }
    }

    fun detach() {
        attached = false; page = null
        runCatching { context.unregisterReceiver(receiver) }
        context.contentResolver.unregisterContentObserver(observer)
        if (sessionWatching) { runCatching { media.removeOnActiveSessionsChangedListener(sessions) }; sessionWatching = false }
        controller?.unregisterCallback(callback); controller = null
    }
    override fun close() { detach(); main.removeCallbacksAndMessages(null); worker.shutdownNow() }
}
