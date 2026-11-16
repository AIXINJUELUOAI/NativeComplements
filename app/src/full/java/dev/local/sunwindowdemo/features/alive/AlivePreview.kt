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
    var onError: (String) -> Unit = {}
    private val checkError = Runnable { renderer?.let { onError(it.error()) } }

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
        submitted = false; updated = false; accepted = false
        renderingKey = key()
        // Old EGL has closed before this cutoff. EGL's automatic buffer timestamps use monotonic nanoseconds.
        // A queued old TextureView update alone must not acknowledge this new renderer's frame.
        bufferAfter = System.nanoTime()
        try {
            val target = Surface(texture)
            surface = target
            renderTexture = texture
            val next = AliveRenderer(context, input)
            renderer = next
            next.mode(scene, false)
            next.attach(target, width, height)
            next.visible(true)
            next.presented {
                main.post {
                    if (generation == ticket && renderer === next && renderTexture === texture) {
                        submitted = true
                        acceptFrame(texture)
                    }
                }
            }
            next.onUnavailable {
                main.post { if (generation == ticket && renderer === next) onError(next.error()) }
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
    private fun acceptFrame(texture: SurfaceTexture) {
        val next = renderer ?: return
        if (accepted || !submitted || !updated || !active || !enabled || renderTexture !== texture ||
            renderingKey != key() || next.error().isNotBlank() || texture.timestamp < bufferAfter) return
        accepted = true
        val cacheKey = renderingKey ?: return
        // Bound GPU readback as well as retained bytes. No per-frame capture or disk I/O.
        val scale = minOf(1f, AlivePreviewCache.MAX_SIDE.toFloat() / maxOf(width, height))
        try {
            val bitmap = textureView.getBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
            if (bitmap != null) AlivePreviewCache.put(cacheKey, bitmap)
        } catch (_: RuntimeException) { /* The confirmed real surface remains usable without a cached copy. */ }
        catch (_: OutOfMemoryError) { /* Thumbnail allocation is optional, never replace it with a black bitmap. */ }
        cover.setImageDrawable(null)
        cover.visibility = View.GONE
        onError("")
    }
    private fun checkSoon() {
        main.removeCallbacks(checkError)
        for (delay in longArrayOf(250, 500, 1000)) main.postDelayed(checkError, delay)
    }
    private fun stop(restartAfterClose: Boolean = true) {
        ++generation
        submitted = false; updated = false; renderingKey = null
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
        if (renderTexture === texture) { updated = true; acceptFrame(texture) }
    }
}
