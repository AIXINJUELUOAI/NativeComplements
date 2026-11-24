package dev.local.sunwindowdemo.features.gamemode

import android.content.Context
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/** Display-pixel model shared by the real touch target and its scaled, draggable preview. */
internal data class GameHandleGeometry(
    val width: Int,
    val height: Int,
    val density: Float,
    val safe: Rect,
    val cutouts: List<Rect>
) {
    val landscape get() = width > height
    private fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
    val handleWidth get() = minOf(dp(TARGET_WIDTH_DP), (safe.right - safe.left).coerceAtLeast(0))
    val handleHeight get() = minOf(dp(TARGET_HEIGHT_DP), (safe.bottom - safe.top).coerceAtLeast(0))
    private val travel get() = (safe.height() - handleHeight).coerceAtLeast(0)

    fun position(top: Float): Float = if (travel == 0) .5f else ((top - safe.top) / travel).coerceIn(0f, 1f)

    fun bounds(right: Boolean, position: Float = Float.NaN): Rect {
        if (handleWidth <= 0 || handleHeight <= 0) return Rect()
        val x = if (right) safe.right - handleWidth else safe.left
        val last = safe.bottom - handleHeight
        val preferred = if (position.isFinite()) safe.top + (travel * position.coerceIn(0f, 1f)).roundToInt()
            else if (landscape) safe.top + dp(28) else (height - handleHeight) / 2
        val top = preferred.coerceIn(safe.top, last)
        // Stay on the edge: go below a cutout first, then above. Validate against ALL cutouts,
        // rather than bouncing between two intersecting exclusions or returning outside safe bounds.
        val candidates = listOf(top) + cutouts.map { it.bottom }.filter { it >= top }.sorted() +
            cutouts.map { it.top - handleHeight }.filter { it <= top }.sortedDescending() + listOf(safe.top, last)
        return candidates.asSequence().filter { it in safe.top..last }
            .map { Rect(x, it, x + handleWidth, it + handleHeight) }
            .firstOrNull { target -> cutouts.none { Rect.intersects(target, it) } } ?: Rect()
    }

    /** Opposite-orientation preview only; runtime always reads that orientation's actual metrics. */
    fun rotated(): GameHandleGeometry {
        fun rotate(rect: Rect) = if (landscape) Rect(rect.top, width - rect.right, rect.bottom, width - rect.left)
            else Rect(height - rect.bottom, rect.left, height - rect.top, rect.right)
        return GameHandleGeometry(height, width, density, rotate(safe), cutouts.map { rotate(it) })
    }

    companion object {
        const val TARGET_WIDTH_DP = 28
        const val TARGET_HEIGHT_DP = 88
        const val BAR_WIDTH_DP = 5
        const val BAR_HEIGHT_DP = 44

        fun read(context: Context): GameHandleGeometry {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
            // Back's horizontal gesture inset is intentionally not consumed: only the small handle
            // excludes Back. Bottom/home gestures, bars and the cutout's vertical safe area stay clear.
            val safe = Rect(bars.left, maxOf(bars.top, cutout.top, gestures.top),
                bounds.width() - bars.right, bounds.height() - maxOf(bars.bottom, cutout.bottom, gestures.bottom))
            val cuts = insets.displayCutout?.boundingRects.orEmpty().map { Rect(it) }
            return GameHandleGeometry(bounds.width(), bounds.height(), context.resources.displayMetrics.density, safe, cuts)
        }
    }
}
