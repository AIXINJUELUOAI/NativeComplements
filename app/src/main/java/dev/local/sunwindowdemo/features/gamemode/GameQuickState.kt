package dev.local.sunwindowdemo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Read-only snapshots. Unknown is deliberately distinct from a disabled switch. */
internal class GameQuickState(private val context: Context) : AutoCloseable {
    companion object {
        val TOGGLE_IDS = setOf("dnd", "wifi", "rotation", "mute", "flashlight", "airplane", "autobrightness", "batterysaver", "darkmode")
    }
    var states: Map<String, Boolean?> by mutableStateOf(TOGGLE_IDS.associateWith { null })
        private set
    private val main = Handler(Looper.getMainLooper())
    internal var observing = false
        private set
    private var torchCamera: String? = null
    private var torch: Boolean? = null
    private var cameras: CameraManager? = null
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { if (observing) refresh() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (observing) refresh() }
    }
    internal val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (observing && cameraId == torchCamera) { torch = enabled; refresh() }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (observing && cameraId == torchCamera) { torch = null; refresh() }
        }
    }

    fun refresh() {
        fun read(block: () -> Boolean): Boolean? = runCatching(block).getOrNull()
        states = mapOf(
            "dnd" to read {
                when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> false
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE,
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                    else -> error("Unknown interruption filter")
                }
            },
            "wifi" to read { context.getSystemService(WifiManager::class.java).isWifiEnabled },
            "rotation" to read { Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 0 },
            "mute" to read {
                val audio = context.getSystemService(AudioManager::class.java)
                audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            },
            "flashlight" to torch,
            "airplane" to read { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1 },
            "autobrightness" to read { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
            "batterysaver" to read { context.getSystemService(PowerManager::class.java).isPowerSaveMode },
            "darkmode" to read {
                when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> true
                    Configuration.UI_MODE_NIGHT_NO -> false
                    else -> error("Unknown night mode")
                }
            },
        )
    }

    fun start() {
        if (observing) return
        observing = true
        torch = null
        cameras = runCatching { context.getSystemService(CameraManager::class.java) }.getOrNull()
        torchCamera = runCatching {
            val manager = cameras ?: error("No cameras")
            val flash = manager.cameraIdList.filter { id ->
                runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
            }
            flash.firstOrNull { id ->
                runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false)
            } ?: flash.firstOrNull()
        }.getOrNull()
        runCatching {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, observer)
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }, null, main, Context.RECEIVER_EXPORTED)
        }
        if (torchCamera != null) runCatching { cameras?.registerTorchCallback(torchCallback, main) }
        refresh()
    }

    override fun close() {
        if (!observing) return
        observing = false
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { cameras?.unregisterTorchCallback(torchCallback) }
        main.removeCallbacksAndMessages(null)
        torch = null
        states = states + ("flashlight" to null)
    }
}
