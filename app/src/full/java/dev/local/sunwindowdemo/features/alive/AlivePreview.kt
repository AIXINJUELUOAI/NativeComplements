package dev.local.sunwindowdemo.features.alive

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.aliveclean.AliveRenderer

/** The editor and wallpaper use the same renderer; only its immutable input differs. */
@Composable
internal fun AlivePreview(
    snapshot: AliveSettings.Snapshot,
    scene: Int,
    resourcesRevision: Int,
    modifier: Modifier = Modifier,
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
        it.show(snapshot, scene, resourcesRevision)
    })
}

private class DraftPreviewView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var renderer: AliveRenderer? = null
    private var surface: Surface? = null
    private var currentTexture: SurfaceTexture? = null
    private var renderTexture: SurfaceTexture? = null
    private var closingTexture: SurfaceTexture? = null
    private var destroyAfterClose: SurfaceTexture? = null
    private var closing = false
    private var snapshot: AliveSettings.Snapshot? = null
    private var scene = 0
    private var revision = -1
    private var active = false
    var onError: (String) -> Unit = {}
    private val checkError = Runnable { renderer?.let { onError(it.error()) } }

    init {
        surfaceTextureListener = this
        isOpaque = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(next: AliveSettings.Snapshot, target: Int, resourcesRevision: Int) {
        val old = snapshot
        val changed = old == null || old.aod != next.aod || old.lock != next.lock ||
            old.home != next.home || old.photo != next.photo || revision != resourcesRevision
        snapshot = next
        revision = resourcesRevision
        if (renderer == null) {
            scene = target
            start()
        } else {
            if (changed) renderer?.reload(next)
            val modeChanged = scene != target
            if (modeChanged) { scene = target; renderer?.mode(target, true) }
            if (changed || modeChanged) checkSoon()
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) start() else stop()
    }

    private fun start() {
        if (!active || closing || renderer != null || width < 1 || height < 1) return
        val texture = currentTexture ?: return
        val input = snapshot ?: return
        try {
            val target = Surface(texture)
            surface = target
            renderTexture = texture
            val next = AliveRenderer(context, input)
            renderer = next
            next.mode(scene, false)
            next.attach(target, width, height)
            next.visible(true)
            checkSoon()
        } catch (failure: Exception) {
            stop(restartAfterClose = false)
            onError(failure.message ?: "无法创建真实预览")
        } catch (failure: LinkageError) {
            stop(restartAfterClose = false)
            onError(failure.message ?: "原生渲染库不可用")
        }
    }

    private fun checkSoon() {
        main.removeCallbacks(checkError)
        // Bounded checks after user/resource events, never a permanent renderer poll.
        for (delay in longArrayOf(250, 500, 1000)) main.postDelayed(checkError, delay)
    }

    private fun stop(restartAfterClose: Boolean = true) {
        main.removeCallbacks(checkError)
        val oldRenderer = renderer
        val oldSurface = surface
        val oldTexture = renderTexture
        renderer = null
        surface = null
        renderTexture = null
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

    fun dispose() { active = false; stop(); onError = {} }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        currentTexture = texture
        start()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (renderTexture === texture) surface?.let { renderer?.attach(it, width, height) }
        checkSoon()
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (currentTexture === texture) currentTexture = null
        when {
            renderTexture === texture -> { destroyAfterClose = texture; stop() }
            closingTexture === texture -> destroyAfterClose = texture
            else -> texture.release()
        }
        // EGL must stop using this exact texture before its native buffer queue is destroyed.
        return false
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
