package dev.local.sunwindowdemo

import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import dev.local.sunwindowdemo.features.gamemode.GameModePolicy
import dev.local.sunwindowdemo.features.gamemode.GameModeStore
import dev.local.sunwindowdemo.features.gamemode.GameCornerGeometry
import dev.local.sunwindowdemo.features.gamemode.GameHandleGeometry
import java.util.concurrent.Executors

/** Only this small edge target excludes Back; the rest of the display remains native. */
internal fun gameModeHandle(context: Context, right: Boolean, open: () -> Unit): View {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context)
    val bar = object : View(context) {
        private val shape = GradientDrawable().apply { cornerRadius = dp(3).toFloat() }
        init {
            background = shape
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            updateColor(resources.configuration)
        }
        private fun updateColor(config: android.content.res.Configuration) {
            val dark = config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            shape.setColor((WindowPresentation.handleColor(dark) and 0x00ffffff) or 0xb8000000.toInt())
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            updateColor(newConfig)
        }
    }
    frame.addView(bar, FrameLayout.LayoutParams(dp(GameHandleGeometry.BAR_WIDTH_DP), dp(GameHandleGeometry.BAR_HEIGHT_DP), Gravity.CENTER_VERTICAL or if (right) Gravity.RIGHT else Gravity.LEFT))
    frame.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
    }
    frame.contentDescription = "游戏助手"
    frame.setOnClickListener { open() }
    val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val threshold = maxOf(slop, dp(18).toFloat())
    var x = 0f
    var y = 0f
    var active = false
    var moved = false
    frame.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.rawX; y = event.rawY; active = event.pointerCount == 1; moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> active = false
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = event.rawX - x
                val dy = kotlin.math.abs(event.rawY - y)
                val inward = if (right) -dx else dx
                if (event.pointerCount != 1 || dy > slop && dy >= kotlin.math.abs(dx) || inward < -slop) active = false
                else {
                    if (kotlin.math.abs(dx) > slop || dy > slop) moved = true
                    if (inward >= threshold) { active = false; open() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (active && !moved && kotlin.math.abs(event.rawX - x) <= slop && kotlin.math.abs(event.rawY - y) <= slop) view.performClick()
                active = false
            }
        }
        true
    }
    return frame
}

/** Native property animation; cancellation must never run a stale window-removal callback. */
internal fun gamePanelSlide(view: View, right: Boolean, entering: Boolean, distance: Int,
    initial: Float = view.translationX, position: (Float) -> Unit = { view.translationX = it },
    finished: () -> Unit): android.animation.ValueAnimator {
    val offscreen = distance.toFloat() * if (right) 1 else -1
    val start = if (entering) offscreen else initial
    val end = if (entering) 0f else offscreen
    if (entering) position(start)
    return android.animation.ValueAnimator.ofFloat(start, end).apply {
        duration = if (entering) 240L else 180L
        interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
        addUpdateListener { position(it.animatedValue as Float) }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finished() }
        })
    }
}

/** Game eligibility and shortcut content are independent. All windows belong to SystemUI main. */
class GameModeHost(private val context: Context, private val launch: (String) -> Unit, private val quick: (String) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("game_mode_host", Context.MODE_PRIVATE)
    private val cornerOverlay = GameCornerOverlay(context)
    private var config = Bundle()
    private var foreground = ""
    private var actualForeground = ""
    private var cornerConfigLive = false
    private var loadRevision = 0
    private var cornerProtected = intArrayOf()
    private var cornerExclusions = intArrayOf()
    private var cornerWidth = 0
    private var cornerHeight = 0
    private val cornerHeartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            publishCorners()
            if (config.getBoolean("enabled") && config.getBoolean("corner_rejection")) main.postDelayed(this, 1000)
        }
    }
    fun protectedRects(rectangles: IntArray) { cornerProtected = rectangles.clone(); publishCorners() }
    fun blocksCorner(x: Float, y: Float): Boolean = GameCornerGeometry.inside(x, y, cornerWidth, cornerHeight,
        config.getFloat("corner_radius_percent", GameCornerGeometry.DEFAULT_PERCENT.toFloat())) && !protectedCorner(x, y)
    private fun protectedCorner(x: Float, y: Float): Boolean = cornerExclusions.indices.step(4).any {
        x >= cornerExclusions[it] && y >= cornerExclusions[it + 1] && x < cornerExclusions[it + 2] && y < cornerExclusions[it + 3]
    }
    private fun publishCorners() {
        val bounds = wm.currentWindowMetrics.bounds
        val enabled = !closed && cornerConfigLive && config.getBoolean("corner_rejection") && gaming() &&
            foreground == actualForeground && !expanded && !collapsing
        cornerWidth = if (enabled) bounds.width() else 0
        cornerHeight = if (enabled) bounds.height() else 0
        val protected = if (enabled && config.getBoolean("panel", true)) {
            // The game's own non-focusable assistant handle must remain usable at the edge.
            val p = params()
            val left = if (right()) bounds.width() - p.x - p.width else p.x
            cornerProtected + intArrayOf(left, p.y, left + p.width, p.y + p.height)
        } else cornerProtected
        cornerExclusions = protected
        cornerOverlay.update(cornerWidth, cornerHeight,
            config.getFloat("corner_radius_percent", GameCornerGeometry.DEFAULT_PERCENT.toFloat()), protected,
            enabled && config.getBoolean("corner_show_regions"))
        context.sendBroadcast(Intent(GameModeStore.CORNER_REGIONS).setPackage("android")
            .putExtra("width", cornerWidth).putExtra("height", cornerHeight)
            .putExtra("percent", config.getFloat("corner_radius_percent", GameCornerGeometry.DEFAULT_PERCENT.toFloat()))
            .putExtra("protected", protected).putExtra("package", if (enabled) foreground else ""), null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }
    private var fullscreen = false
    private var blocked = true
    private var closed = false
    private var expanded = false
    private var apps = emptyList<AppCatalog.App>()
    private var catalog = emptyList<AppCatalog.App>()
    private var actions = emptyList<QuickActionCatalog.Action>()
    private var overlay: View? = null
    private var panelAnimator: android.animation.ValueAnimator? = null
    private var collapsing = false
    private var overlayLayout: WindowManager.LayoutParams? = null
    private var restingX = 0
    private var panelOffset = 0f
    private var geometry = ""
    private val iconsReloaded = Runnable { main.post { if (!closed) load() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) { blocked = true; hide(); publishCorners(); return }
            if (sentFromUid == runCatching { context.packageManager.getApplicationInfo(Wire.PKG, 0).uid }.getOrDefault(-1)) load()
        }
    }
    init {
        context.registerReceiver(receiver, IntentFilter(GameModeStore.CHANGED).apply { addAction(Intent.ACTION_SCREEN_OFF) }, null, main, Context.RECEIVER_EXPORTED)
        dev.local.sunwindowdemo.features.iconlayers.IconAssets.onReload(iconsReloaded)
        restoreLegacyBrightness()
        load()
    }
    private fun load() {
        if (closed) return
        val revision = ++loadRevision
        cornerConfigLive = false; publishCorners()
        worker.execute {
            val snapshot = dev.local.sunwindowdemo.core.HostSnapshot.open(context, "game_mode")
            val read = snapshot.read { context.contentResolver.call(Uri.parse("content://${GameModeStore.AUTHORITY}"), "read", null, null) }
            if (read.report()) android.util.Log.w("NativeComplements", "GAMEMODE_SETTINGS ${read.summary()}")
            val next = read.config() ?: Bundle()
            val chosen = next.getStringArrayList("quick_apps").orEmpty()
            val catalog = if (!next.getBoolean("enabled") || !next.getBoolean("panel", true)) emptyList() else runCatching { AppCatalog.load(context) }.getOrDefault(emptyList())
            val selected = chosen.mapNotNull { pkg -> catalog.firstOrNull { it.pkg == pkg } }
            val shortcuts = next.getStringArrayList("quick").orEmpty().mapNotNull { QuickActionCatalog.find(it) }
            main.post {
                if (closed || revision != loadRevision) return@post
                config = next; cornerConfigLive = read.live(); apps = selected; this.catalog = catalog; actions = shortcuts
                hide(); apply()
                main.removeCallbacks(cornerHeartbeat); cornerHeartbeat.run()
                if (!read.live()) main.postDelayed({ if (revision == loadRevision) load() }, snapshot.retryDelay(3000))
            }
        }
    }
    fun environment(pkg: String?, fullscreen: Boolean, blocked: Boolean, actualForeground: String? = null) {
        foreground = pkg.orEmpty(); this.fullscreen = fullscreen; this.blocked = blocked
        this.actualForeground = actualForeground.orEmpty()
        apply()
    }
    private fun gaming() = GameModePolicy.gaming(config.getBoolean("enabled"),
        config.getStringArrayList("apps")?.contains(foreground) == true, fullscreen,
        !blocked && context.getSystemService(android.os.PowerManager::class.java).isInteractive,
        context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)

    private fun apply() {
        if (closed) return
        restoreLegacyBrightness()
        publishCorners()
        if (!gaming() || !config.getBoolean("panel", true)) { hide(); return }
        val model = GameHandleGeometry.read(context)
        val key = model.toString()
        if (geometry != key) { hide(); geometry = key }
        if (!expanded && model.bounds(right(), GameModeStore.handlePosition(config, model.landscape)).isEmpty) { hide(); return }
        if (overlay == null) show()
    }
    /** Only release a previous test version's journal; never acquire a new brightness hold. */
    private fun restoreLegacyBrightness() {
        if (!prefs.getBoolean("brightness_hold", false)) return
        if (runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) }.getOrDefault(false))
            prefs.edit().putBoolean("brightness_hold", false).commit()
    }
    private fun right(): Boolean {
        val bounds = wm.currentWindowMetrics.bounds
        return GameModeStore.handleSide(config, bounds.width() > bounds.height()) == GameModeStore.SIDE_RIGHT
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun toggle() {
        if (closed || collapsing || !gaming()) return
        val current = overlay
        if (expanded && current != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            collapsing = true
            current.findViewWithTag<GameQuickView>("game-quick-page")?.suspendControls()
            panelAnimator?.cancel()
            val bounds = expandedBounds()
            val edgeGap = if (right()) wm.currentWindowMetrics.bounds.width() - bounds.right else bounds.left
            panelAnimator = gamePanelSlide(current, right(), false, bounds.width() + edgeGap,
                initial = panelOffset, position = { offset -> movePanel(current, offset) }) {
                if (overlay === current && !closed) { hide(); apply() }
            }.also { it.start() }
        } else {
            expanded = !expanded
            publishCorners()
            removeOverlay()
            show()
        }
    }
    private fun show() {
        val root: View
        if (expanded) {
            val card = expandedBounds()
            root = gameModePanel(context, apps, catalog, actions, card.width(), card.height(), Config(context).menuBlur(),
                { pkg -> if (!collapsing && gaming() && catalog.any { it.pkg == pkg } && Wire.allowedPackage(pkg)) { hide(); launch(pkg) } },
                { id -> if (!closed && !collapsing && gaming() && actions.any { it.id() == id }) {
                    if (id !in GameQuickState.TOGGLE_IDS) hide()
                    quick(id)
                } }, right = right())
            root.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { toggle(); true } else false }
        } else {
            root = gameModeHandle(context, right()) { toggle() }
        }
        val layout = params()
        restingX = layout.x
        val animate = expanded && android.animation.ValueAnimator.areAnimatorsEnabled()
        val distance = layout.width + restingX
        panelOffset = if (animate) distance.toFloat() * if (right()) 1 else -1 else 0f
        if (animate) layout.x = -layout.width
        // Move the window itself: root translation would clip the card inside its final bounds.
        if (expanded) layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            wm.addView(root, layout); overlay = root; overlayLayout = layout
            if (animate) {
                panelAnimator = gamePanelSlide(root, right(), true, distance,
                    position = { offset -> movePanel(root, offset) }) {
                    if (overlay === root) panelAnimator = null
                }.also { it.start() }
            }
        }
        catch (failed: RuntimeException) { android.util.Log.w("NativeComplements", "GAMEMODE_OVERLAY_UNAVAILABLE", failed) }
    }
    /** Landscape keeps the menu geometry; portrait uses the same short-side height, centered safely. */
    internal fun expandedBounds(): Rect {
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val placement = AppList.placement(context, metrics)
        val landscape = bounds.width() > bounds.height()
        val types = WindowInsets.Type.displayCutout() or if (landscape) 0 else WindowInsets.Type.systemBars()
        val safe = metrics.windowInsets.getInsetsIgnoringVisibility(types)
        val margin = dp(if (landscape) 4 else 12)
        val available = (bounds.width() - safe.left - safe.right - 2 * margin).coerceAtLeast(1)
        val width = minOf(available, Math.round(placement.width() * placement.scale() * 1.2f) + dp(64) + dp(10))
        val height = if (landscape) Math.round(placement.height() * placement.scale()) else
            minOf((minOf(bounds.width(), bounds.height()) - dp(8)).coerceAtLeast(1),
                (bounds.height() - safe.top - safe.bottom - 2 * margin).coerceAtLeast(1))
        val left = if (right()) bounds.width() - safe.right - margin - width else safe.left + margin
        val top = if (landscape) Math.round(placement.y()) - bounds.top else
            safe.top + (bounds.height() - safe.top - safe.bottom - height) / 2
        return Rect(left, top, left + width, top + height)
    }
    internal fun params(): WindowManager.LayoutParams {
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val model = GameHandleGeometry.read(context)
        val handle = model.bounds(right(), GameModeStore.handlePosition(config, model.landscape))
        @Suppress("WrongConstant")
        val card = expandedBounds()
        val p = WindowManager.LayoutParams(if (expanded) card.width() else handle.width(),
            if (expanded) card.height() else handle.height(), SystemUiHost.OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or if (right()) Gravity.RIGHT else Gravity.LEFT
        val target = if (expanded) card else handle
        p.x = if (right()) bounds.width() - target.right else target.left
        p.y = target.top
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.setFitInsetsTypes(0); p.title = "NativeComplements-game-mode"; p.windowAnimations = 0
        SystemUiHost.trust(p)
        return p
    }
    private fun movePanel(root: View, offset: Float) {
        if (overlay !== root) return
        val layout = overlayLayout ?: return
        panelOffset = offset
        layout.x = restingX + kotlin.math.round(offset * if (right()) -1 else 1).toInt()
        runCatching { wm.updateViewLayout(root, layout) }.onFailure { hide() }
    }
    private fun removeOverlay() {
        panelAnimator?.cancel(); panelAnimator = null; collapsing = false
        overlay?.let {
            it.findViewWithTag<GameQuickView>("game-quick-page")?.suspendControls()
            runCatching { wm.removeViewImmediate(it) }
        }
        overlay = null; overlayLayout = null; panelOffset = 0f
    }
    private fun hide() { removeOverlay(); expanded = false }
    override fun close() {
        if (closed) return
        closed = true; hide(); publishCorners(); restoreLegacyBrightness()
        dev.local.sunwindowdemo.features.iconlayers.IconAssets.removeOnReload(iconsReloaded)
        context.unregisterReceiver(receiver); main.removeCallbacksAndMessages(null); worker.shutdownNow()
    }
}
