package dev.local.sunwindowdemo

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlinx.coroutines.*

/** A resource-correct Compose lifecycle inside the existing SystemUI window. */
internal class OverlayComposition(private val context: Context, private val failed: (Throwable) -> Unit): LifecycleOwner,SavedStateRegistryOwner,AutoCloseable {
    private val registry=LifecycleRegistry(this)
    private val saved=SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get()=registry
    override val savedStateRegistry: SavedStateRegistry get()=saved.savedStateRegistry
    private var scope: CoroutineScope?=null
    private var recomposer: Recomposer?=null
    private var compose: ComposeView?=null
    fun view(content: @Composable () -> Unit): ComposeView {
        saved.performAttach();saved.performRestore(null);registry.currentState=Lifecycle.State.CREATED
        val resources=context.createPackageContext(Wire.PKG,0).createConfigurationContext(context.resources.configuration)
        val themed=object: android.view.ContextThemeWrapper(resources,R.style.Theme_NativeComplements) {
            override fun getApplicationContext(): Context=this@OverlayComposition.context.applicationContext
        }
        val view=ComposeView(themed);compose=view
        view.setViewTreeLifecycleOwner(this);view.setViewTreeSavedStateRegistryOwner(this)
        val coroutine=CoroutineScope(SupervisorJob()+AndroidUiDispatcher.Main+CoroutineExceptionHandler { _,error -> failed(error) })
        scope=coroutine
        val composer=Recomposer(coroutine.coroutineContext);recomposer=composer
        view.setParentCompositionContext(composer);view.setContent(content)
        coroutine.launch { composer.runRecomposeAndApplyChanges() }
        registry.currentState=Lifecycle.State.RESUMED
        return view
    }
    override fun close() {
        compose?.disposeComposition();compose=null
        if(registry.currentState!=Lifecycle.State.INITIALIZED)registry.currentState=Lifecycle.State.DESTROYED
        recomposer?.cancel();scope?.cancel()
    }
}
