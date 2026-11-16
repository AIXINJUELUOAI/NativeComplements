package dev.local.sunwindowdemo.features.alive

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.aliveclean.AliveRenderer

/** A real successful editor frame covers renderer recreation, never a fabricated wallpaper preview. */
@Composable
internal fun AlivePreview(
    snapshot: AliveSettings.Snapshot,
    scene: Int,
    resourcesRevision: Long,
    modifier: Modifier = Modifier,
    renderEnabled: Boolean = true,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val error by rememberUpdatedState(onError)
    val view = remember(context) { DraftPreviewView(context) }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            view.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        view.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); view.dispose() }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        it.onError = { value -> error(value) }
        it.show(snapshot, scene, resourcesRevision, renderEnabled)
    })
}

private class DraftPreviewView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val textureView = TextureView(context)
    private val cover = ImageView(context)
    private var renderer: AliveRenderer? = null
    private var surface: Surface? = null
    private var currentTexture: SurfaceTexture? = null
    private var renderTexture: SurfaceTexture? = null
    private var closingTexture: SurfaceTexture? = null
    private var destroyAfterClose: SurfaceTexture? = null
    private var closing = false
    private var snapshot: AliveSettings.Snapshot? = null
    private var scene = 0
    private var revision = -1L
    private var active = false
    private var enabled = false
    private var generation = 0L
    private var submitted = false
    private var updated = false
    private var accepted = false
    private var bufferAfter = 0L
    private var renderingKey: AlivePreviewCache.Key? = null
    private var frameCheck: Runnable? = null
    private var frameWatchdog: Runnable? = null
    private var checksStarted = false
    private var confirmationError = ""
    var onError: (String) -> Unit = {}
    private val checkError = Runnable { renderer?.let { onError(it.error().ifBlank { confirmationError }) } }
    private fun diagnose(event: String, details: String = "") {
        android.util.Log.i("NativeComplements", "ALIVE_PREVIEW phase=$scene generation=$generation event=$event $details")
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        textureView.surfaceTextureListener = this
        textureView.isOpaque = true
        cover.scaleType = ImageView.ScaleType.FIT_XY
        cover.setBackgroundColor(android.graphics.Color.BLACK)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun key(): AlivePreviewCache.Key? = snapshot?.takeIf { width > 0 && height > 0 }?.let {
        AlivePreviewCache.Key(it, scene, revision, width, height)
    }
    private fun showCover() {
        cover.setImageBitmap(key()?.let(AlivePreviewCache::get))
        cover.visibility = View.VISIBLE
    }
    fun show(next: AliveSettings.Snapshot, target: Int, resourcesRevision: Long, renderEnabled: Boolean) {
        val changed = snapshot != next || scene != target || revision != resourcesRevision || enabled != renderEnabled
        snapshot = next
        scene = target
        revision = resourcesRevision
        enabled = renderEnabled
        if (changed) { showCover(); stop() }
        start()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) { showCover(); start() } else { showCover(); stop() }
    }
    private fun start() {
        if (!active || !enabled || closing || renderer != null || width < 1 || height < 1) return
        val texture = currentTexture ?: return
        val input = snapshot ?: return
        val ticket = ++generation
        submitted = false; updated = false; accepted = false; checksStarted = false; confirmationError = ""
        renderingKey = key()
        // Retain the old-buffer cutoff; OEM timestamp origins still require real-device verification.
        bufferAfter = System.nanoTime()
        try {
            val target = Surface(texture)
            surface = target
            renderTexture = texture
            val next = AliveRenderer(context, input)
            renderer = next
            val cacheKey = renderingKey
            val watchdog = Runnable {
                if (generation == ticket && renderer === next && renderTexture === texture &&
                    renderingKey == cacheKey && key() == cacheKey && active && enabled && !accepted && confirmationError.isBlank()) {
                    confirmationError = "真实预览首帧未能确认，请离开页面后重试"
                    diagnose("timeout", "cutoff=$bufferAfter timestamp=${texture.timestamp} submitted=$submitted updated=$updated")
                    onError(next.error().ifBlank { confirmationError })
                }
                frameWatchdog = null
            }
            frameWatchdog = watchdog
            main.postDelayed(watchdog, 3000)
            next.onUnavailable {
                main.post { if (generation == ticket && renderer === next) onError(next.error()) }
            }
            next.mode(scene, false)
            next.attach(target, width, height)
            next.visible(true)
            next.presented {
                main.post {
                    if (generation == ticket && renderer === next && renderTexture === texture) {
                        submitted = true
                        diagnose("submitted", "cutoff=$bufferAfter updated=$updated")
                        queueFrameCheck(texture)
                    }
                }
            }
            checkSoon()
        } catch (failure: Exception) {
            stop(restartAfterClose = false)
            onError(failure.message ?: "无法创建真实预览")
        } catch (failure: LinkageError) {
            stop(restartAfterClose = false)
            onError(failure.message ?: "原生渲染库不可用")
        }
    }
    private fun queueFrameCheck(texture: SurfaceTexture) {
        val next = renderer ?: return
        val cacheKey = renderingKey ?: return
        if (checksStarted || accepted || !submitted || !updated) return
        checksStarted = true
        val ticket = generation
        var attempt = 0
        val delays = longArrayOf(16, 50, 150)
        val check = object : Runnable {
            override fun run() {
                if (generation != ticket || renderer !== next || renderTexture !== texture ||
                    renderingKey != cacheKey || key() != cacheKey || !active || !enabled || accepted) return
                if (next.error().isNotBlank()) { onError(next.error()); return }
                val scale = minOf(1f, AlivePreviewCache.MAX_SIDE.toFloat() / maxOf(width, height))
                val before = texture.timestamp
                // TextureView's draw listener precedes RenderThread latch. Readback synchronizes that
                // pending layer update; never call it from onSurfaceTextureUpdated/the drawing stack.
                val bitmap = try {
                    textureView.getBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                } catch (_: RuntimeException) { null }
                catch (_: OutOfMemoryError) { null }
                val after = texture.timestamp
                if (attempt == 0) diagnose("readback", "cutoff=$bufferAfter before=$before after=$after bitmap=${bitmap != null}")
                if (after >= bufferAfter && next.error().isBlank()) {
                    accepted = true
                    frameCheck = null
                    frameWatchdog?.let(main::removeCallbacks); frameWatchdog = null
                    confirmationError = ""
                    bitmap?.let { AlivePreviewCache.put(cacheKey, it) }
                    cover.setImageDrawable(null)
                    cover.visibility = View.GONE
                    diagnose("accepted", "timestamp=$after attempts=${attempt + 1}")
                    onError("")
                    return
                }
                bitmap?.recycle() // Rejected copies have never been published to a cover or the cache.
                attempt++
                if (attempt < delays.size) main.postDelayed(this, delays[attempt]) else {
                    frameCheck = null
                    frameWatchdog?.let(main::removeCallbacks); frameWatchdog = null
                    confirmationError = "真实预览首帧未能确认，请离开页面后重试"
                    diagnose("timeout", "cutoff=$bufferAfter timestamp=$after attempts=$attempt")
                    onError(next.error().ifBlank { confirmationError })
                    // Keep a matching cached cover (or black placeholder), never expose an unconfirmed old buffer.
                }
            }
        }
        frameCheck = check
        main.postDelayed(check, delays[0])
    }
    private fun checkSoon() {
        main.removeCallbacks(checkError)
        for (delay in longArrayOf(250, 500, 1000)) main.postDelayed(checkError, delay)
    }
    private fun stop(restartAfterClose: Boolean = true) {
        ++generation
        submitted = false; updated = false; renderingKey = null
        frameCheck?.let(main::removeCallbacks); frameCheck = null
        frameWatchdog?.let(main::removeCallbacks); frameWatchdog = null
        main.removeCallbacks(checkError)
        val oldRenderer = renderer
        val oldSurface = surface
        val oldTexture = renderTexture
        renderer = null; surface = null; renderTexture = null
        if (oldRenderer == null) { oldSurface?.release(); return }
        closing = true
        closingTexture = oldTexture
        oldRenderer.close {
            main.post {
                oldSurface?.release()
                if (oldTexture != null && destroyAfterClose === oldTexture) {
                    destroyAfterClose = null
                    oldTexture.release()
                }
                closingTexture = null
                closing = false
                if (restartAfterClose) start()
            }
        }
    }
    fun dispose() { active = false; stop(); cover.setImageDrawable(null); onError = {} }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) { showCover(); stop(); start() }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        currentTexture = texture
        showCover()
        start()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        showCover(); stop(); start()
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        showCover()
        if (currentTexture === texture) currentTexture = null
        when {
            renderTexture === texture -> { destroyAfterClose = texture; stop() }
            closingTexture === texture -> destroyAfterClose = texture
            else -> texture.release()
        }
        return false // EGL must disconnect this exact native buffer queue before it is released.
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (renderTexture === texture) {
            if (!updated) diagnose("updated", "timestamp=${texture.timestamp} cutoff=$bufferAfter submitted=$submitted")
            updated = true
            queueFrameCheck(texture)
        }
    }
}
