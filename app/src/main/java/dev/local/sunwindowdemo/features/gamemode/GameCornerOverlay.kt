package dev.local.sunwindowdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.local.sunwindowdemo.features.gamemode.GameCornerGeometry
import kotlin.math.ceil

/** Two small draw-only windows: never acquire input or change the rejection policy. */
internal class GameCornerOverlay(private val context: Context) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<View>()
    private var geometry = ""

    fun update(width: Int, height: Int, percent: Float, exclusions: IntArray, show: Boolean) {
        if (!show || width <= 0 || height <= 0) { close(); return }
        val key = "$width:$height:$percent:${exclusions.joinToString()}"
        if (geometry == key && views.size == 2) return
        close()
        val radius = GameCornerGeometry.radius(width, height, percent)
        val side = ceil(radius).toInt()
        try {
            repeat(2) { corner ->
                val left = if (corner == 0) 0 else width - side
                val top = height - side
                val view = object : View(context) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        val checkpoint = canvas.save()
                        try {
                            for (i in exclusions.indices step 4) canvas.clipOutRect(exclusions[i] - left, exclusions[i + 1] - top,
                                exclusions[i + 2] - left, exclusions[i + 3] - top)
                            val center = if (corner == 0) 0f else side.toFloat()
                            paint.style = Paint.Style.FILL; paint.color = 0x336750A4
                            canvas.drawCircle(center, side.toFloat(), radius, paint)
                            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
                            paint.color = 0xB39A82DB.toInt()
                            canvas.drawCircle(center, side.toFloat(), radius, paint)
                        } finally { canvas.restoreToCount(checkpoint) }
                    }
                }
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val params = parameters(side, left, top)
                SystemUiHost.trust(params)
                wm.addView(view, params); views += view
            }
            geometry = key
        } catch (unavailable: RuntimeException) {
            close()
            android.util.Log.w("NativeComplements", "GAME_CORNER_PREVIEW_UNAVAILABLE", unavailable)
        }
    }

    internal fun parameters(side: Int, left: Int, top: Int): WindowManager.LayoutParams {
        @Suppress("WrongConstant")
        val p = WindowManager.LayoutParams(side, side, SystemUiHost.OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.LEFT; p.x = left; p.y = top
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.setFitInsetsTypes(0); p.title = "NativeComplements-game-corner-range"; p.windowAnimations = 0
        return p
    }

    override fun close() {
        views.forEach { runCatching { wm.removeViewImmediate(it) } }
        views.clear(); geometry = ""
    }
}
