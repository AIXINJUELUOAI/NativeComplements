package dev.local.sunwindowdemo.features.gamemode

internal object GameModePolicy {
    fun gaming(enabled: Boolean, selected: Boolean, fullscreen: Boolean, interactive: Boolean, locked: Boolean) =
        enabled && selected && fullscreen && interactive && !locked

    /** Keep the handle on the physical edge; move along it rather than insetting away from a cutout. */
    fun handleTop(width: Int, height: Int, right: Boolean, top: Int, handleWidth: Int, handleHeight: Int, cutouts: List<android.graphics.Rect>): Int {
        var y = top.coerceIn(0, (height - handleHeight).coerceAtLeast(0))
        repeat(cutouts.size + 1) {
            val x = if (right) width - handleWidth else 0
            val hit = cutouts.firstOrNull { android.graphics.Rect.intersects(it, android.graphics.Rect(x, y, x + handleWidth, y + handleHeight)) }
                ?: return y
            y = if (hit.bottom + handleHeight <= height) hit.bottom else (hit.top - handleHeight).coerceAtLeast(0)
        }
        return y
    }
}
