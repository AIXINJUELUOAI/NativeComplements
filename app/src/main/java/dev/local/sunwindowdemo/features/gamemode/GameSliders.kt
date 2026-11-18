package dev.local.sunwindowdemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** AOSP SettingsLib BrightnessUtils (Apache-2.0), gamma/linear HLG transfer curve.
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/src/com/android/settingslib/display/BrightnessUtils.java
 */
internal fun gameBrightnessLinear(progress: Int, max: Int = 1000): Float {
    val gamma = progress.coerceIn(0, max).toFloat() / max
    val value = if (gamma <= .5f) (gamma / .5f) * (gamma / .5f)
        else kotlin.math.exp((gamma - .55991073f) / .17883277f) + .28466892f
    return (value / 12f).coerceIn(0f, 1f)
}
internal fun gameBrightnessProgress(linear: Float): Int {
    val value = linear.coerceIn(0f, 1f) * 12f
    val gamma = if (value <= 1f) kotlin.math.sqrt(value) * .5f
        else .17883277f * kotlin.math.ln(value - .28466892f) + .55991073f
    return kotlin.math.round(gamma * 1000).toInt().coerceIn(0, 1000)
}

internal fun gameSliderIcon(context: Context, name: String): android.graphics.drawable.Drawable? = runCatching {
    val module = context.createPackageContext(Wire.PKG, 0)
    val id = module.resources.getIdentifier(name, "drawable", Wire.PKG)
    if (id == 0) null else module.getDrawable(id)
}.getOrNull()

private fun systemBrightnessWriter(context: Context): (Float) -> Unit = { value ->
    require(value.isFinite() && value in 0f..1f)
    val display = context.display ?: error("No display")
    val manager = context.getSystemService(DisplayManager::class.java)
    manager.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        .invoke(manager, display.displayId, value)
}

/** Writes the shared system brightness value without changing automatic/manual mode. */
internal class GameSliderState(
    private val context: Context,
    private val readBrightness: () -> Float? = {
        runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
            val display = context.display ?: error("No display")
            manager.javaClass.getMethod("getBrightness", Int::class.javaPrimitiveType)
                .invoke(manager, display.displayId) as Float
        }.getOrNull()
    },
    private val writeBrightness: (Float) -> Unit = systemBrightnessWriter(context),
    private val brightnessRange: () -> ClosedFloatingPointRange<Float> = {
        runCatching {
            val display = context.display ?: error("No display")
            val info = display.javaClass.getMethod("getBrightnessInfo").invoke(display) ?: error("No brightness info")
            val min = info.javaClass.getField("brightnessMinimum").getFloat(info)
            val max = info.javaClass.getField("brightnessMaximum").getFloat(info)
            require(min.isFinite() && max.isFinite() && min >= 0f && max <= 1f && max > min)
            min..max
        }.getOrDefault(0f..1f)
    },
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    var brightness by mutableStateOf(0f)
        private set
    var brightnessEnabled by mutableStateOf(false)
        private set
    var volume by mutableStateOf(0f)
        private set
    var volumeMin by mutableStateOf(0f)
        private set
    var volumeMax by mutableStateOf(0f)
        private set
    var volumeEnabled by mutableStateOf(false)
        private set
    internal var observing = false
        private set
    private var brightnessDragging = false
    private var volumeDragging = false
    private var range = 0f..1f
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (observing) refresh()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (observing) refresh() }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (observing) refresh() }
        override fun onDisplayRemoved(displayId: Int) { if (observing) refresh() }
        override fun onDisplayChanged(displayId: Int) { if (observing) refresh() }
    }

    init { refresh() }

    internal fun refresh() {
        if (!brightnessDragging) {
            range = runCatching {
                brightnessRange().also {
                    require(it.start.isFinite() && it.endInclusive.isFinite() &&
                        it.start >= 0f && it.endInclusive <= 1f && it.endInclusive > it.start)
                }
            }.getOrDefault(0f..1f)
            val value = runCatching { readBrightness() }.getOrNull()
            brightnessEnabled = value != null && value.isFinite() && value in 0f..1f
            if (brightnessEnabled) brightness = gameBrightnessProgress(
                (value!!.coerceIn(range.start, range.endInclusive) - range.start) / (range.endInclusive - range.start)).toFloat()
        }
        if (volumeDragging) return
        runCatching {
            val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            volumeMin = min.toFloat()
            volumeMax = max.coerceAtLeast(min).toFloat()
            volumeEnabled = !audio.isVolumeFixed && max > min
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, volumeMax.toInt()).toFloat()
        }.onFailure { volumeEnabled = false }
    }

    fun beginBrightness() { brightnessDragging = true }
    fun setBrightnessProgress(progress: Float) {
        if (!observing || !brightnessEnabled || !progress.isFinite()) return
        runCatching {
            val next = progress.coerceIn(0f, 1000f)
            val value = range.start + gameBrightnessLinear(kotlin.math.round(next).toInt()) * (range.endInclusive - range.start)
            writeBrightness(value)
            brightness = next
        }.onFailure { brightnessEnabled = false }
    }
    fun endBrightness() { brightnessDragging = false; if (observing) refresh() }
    fun beginVolume() { volumeDragging = true }
    fun setVolumeProgress(progress: Float) {
        if (!observing || !volumeEnabled || !progress.isFinite()) return
        runCatching {
            if (audio.isVolumeFixed) { volumeEnabled = false; return }
            val next = kotlin.math.round(progress).toInt().coerceIn(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            volume = next.toFloat()
        }.onFailure { volumeEnabled = false }
    }
    fun endVolume() { volumeDragging = false; if (observing) refresh() }

    fun start() {
        if (observing) return
        observing = true
        runCatching {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
            context.contentResolver.registerContentObserver(Settings.System.getUriFor("screen_brightness_float"), false, observer)
        }
        runCatching {
            // The hidden overload includes brightness-only display changes; older platforms use the standard listener.
            displays.javaClass.getMethod("registerDisplayListener", DisplayManager.DisplayListener::class.java,
                Handler::class.java, Long::class.javaPrimitiveType).invoke(displays, displayListener, main, 8L)
        }.onFailure { runCatching { displays.registerDisplayListener(displayListener, main) } }
        runCatching { context.registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), null, main, Context.RECEIVER_EXPORTED) }
        refresh()
    }
    override fun close() {
        brightnessDragging = false
        volumeDragging = false
        if (!observing) return
        observing = false
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        runCatching { displays.unregisterDisplayListener(displayListener) }
        runCatching { context.unregisterReceiver(receiver) }
        main.removeCallbacksAndMessages(null)
    }
}
