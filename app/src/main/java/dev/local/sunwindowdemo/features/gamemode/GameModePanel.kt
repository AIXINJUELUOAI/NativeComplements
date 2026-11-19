package dev.local.sunwindowdemo

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import dev.local.sunwindowdemo.core.ui.MenuBackground
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

internal fun gamePanelContainerColor(colors: ColorScheme) =
    if (colors.surface.luminance() > .5f) colors.surfaceContainerHighest else colors.surfaceContainerLow

/** Two separate native menu surfaces: pinned apps on the left, switchable content on the right. */
internal fun gameModePanel(
    context: Context,
    apps: List<AppCatalog.App>,
    catalog: List<AppCatalog.App>,
    actions: List<QuickActionCatalog.Action>,
    width: Int,
    maxHeight: Int,
    blur: Boolean,
    launch: (String) -> Unit,
    quick: (String) -> Unit,
): View {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    var colors = if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val height = maxHeight
    val railWidth = minOf(dp(64), (width - dp(10)).coerceAtLeast(1) / 3)
    val gap = dp(10)
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutParams = android.view.ViewGroup.LayoutParams(width, height)
    }
    fun material(view: View) {
        view.clipToOutline = true
        MenuBackground.bind(view, blur, gamePanelContainerColor(colors).toArgb(), dp(28).toFloat())
    }
    val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        material(this)
    }
    root.addView(rail, LinearLayout.LayoutParams(railWidth, -1))
    val appColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val slot = maxOf(dp(48), measuredHeight / appColumn.childCount.coerceAtLeast(1))
            var changed = false
            for (index in 0 until appColumn.childCount) {
                val child = appColumn.getChildAt(index)
                if (child.layoutParams.height != slot) {
                    child.layoutParams = LinearLayout.LayoutParams(-1, slot)
                    changed = true
                }
            }
            if (changed) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(appColumn) }
    rail.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    apps.forEach { app ->
        val button = FrameLayout(context).apply {
            contentDescription = "以小窗打开${app.label}"
            isFocusable = true
            setOnClickListener { launch(app.pkg) }
        }
        val icon = ImageView(context).apply {
            val drawable = app.icon.constantState?.newDrawable(context.resources)?.mutate() ?: app.icon
            setImageDrawable(androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(context.resources,
                drawable.toBitmap(dp(36), dp(36))).apply { isCircular = true })
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        button.addView(icon, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        appColumn.addView(button, LinearLayout.LayoutParams(-1, dp(48)))
    }
    val arrow = ImageButton(context).apply {
        setImageDrawable(gameSliderIcon(context, "gm_chevron"))
        imageTintList = android.content.res.ColorStateList.valueOf(colors.onPrimary.toArgb())
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(7), dp(7), dp(7), dp(7))
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x26000000),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors.primary.toArgb()) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        contentDescription = "显示更多小窗应用"
    }
    val arrowSlot = FrameLayout(context).apply {
        addView(arrow, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
    }
    arrowSlot.setOnClickListener { arrow.performClick() }
    arrowSlot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    appColumn.addView(arrowSlot, LinearLayout.LayoutParams(-1, dp(48)))
    // All entries, including the arrow, share the same slot; overflow keeps 48dp targets and scrolls.
    val content = FrameLayout(context).apply { material(this) }
    root.addView(content, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap })
    var library: View? = null
    val quickPage = GameQuickView(context, actions, quick) { next ->
        colors = next
        material(rail)
        material(content)
        library?.let(::material)
        arrow.imageTintList = android.content.res.ColorStateList.valueOf(colors.onPrimary.toArgb())
        val disc = (arrow.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as GradientDrawable
        disc.setColor(colors.primary.toArgb())
    }.apply { tag = "game-quick-page" }
    content.addView(quickPage, FrameLayout.LayoutParams(-1, -1))
    var showingApps = false
    arrow.setOnClickListener {
        showingApps = !showingApps
        if (showingApps && library == null) {
            library = AppList.gameLibrary(context, catalog) { launch(it) }.apply { material(this) }
            content.addView(library, FrameLayout.LayoutParams(-1, -1))
        }
        // Preserve both lists' scroll positions without rebuilding the overlay window.
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.visibility = if ((child === library) == showingApps) View.VISIBLE else View.GONE
        }
        arrow.rotation = if (showingApps) 180f else 0f
        arrow.contentDescription = if (showingApps) "返回快捷功能" else "显示更多小窗应用"
    }
    return root
}
