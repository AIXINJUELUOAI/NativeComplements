package dev.local.sunwindowdemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator

/** One notification line drawn in Sona's danmaku-lyrics style: bold white, no card, right to left. */
internal class BarrageLyricsView(
    context: Context,
    private val text: String,
    private val icon: Drawable?,
    private val onFinished: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 18f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }
    private val speed = 100f * density / 1000f
    private val iconSize = 20f * density
    private val iconGap = 6f * density
    private var animator: ValueAnimator? = null
    private var started = false
    private var cancelled = false
    private var x = 0f
    private var drawAlpha = 0f
    private var duration = 0L

    private fun start(width: Float) {
        if (started || cancelled) return
        started = true
        val textWidth = paint.measureText(text)
        val contentWidth = textWidth + if (icon != null) iconSize + iconGap else 0f
        duration = (((width + contentWidth) / speed).toLong()).coerceAtLeast(1)
        animator = ValueAnimator.ofFloat(width, -contentWidth).apply {
            duration = this@BarrageLyricsView.duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                x = animation.animatedValue as Float
                val elapsed = animation.currentPlayTime.toFloat()
                val fadeIn = (elapsed / 120f).coerceIn(0f, 1f)
                val fadeOut = ((this@BarrageLyricsView.duration - elapsed) / 220f).coerceIn(0f, 1f)
                drawAlpha = minOf(fadeIn, fadeOut) * .9f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onFinished()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) start(w.toFloat())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (!started && width > 0) start(width.toFloat()) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawAlpha <= 0f) return
        val alphaInt = (drawAlpha * 255).toInt()
        val textX = if (icon != null) {
            val top = (height - iconSize) / 2f
            val oldAlpha = icon.alpha
            icon.setBounds(Math.round(x), Math.round(top), Math.round(x + iconSize), Math.round(top + iconSize))
            icon.alpha = alphaInt
            icon.draw(canvas)
            icon.alpha = oldAlpha
            x + iconSize + iconGap
        } else x
        paint.alpha = alphaInt
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, textX, baseline, paint)
    }

    fun pause() {
        if (cancelled) return
        visibility = INVISIBLE
        animator?.pause()
    }

    fun resume() {
        if (cancelled) return
        visibility = VISIBLE
        val running = animator
        when {
            running == null && !started && width > 0 -> start(width.toFloat())
            running != null && running.isPaused -> running.resume()
        }
    }

    fun cancel() {
        cancelled = true
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }
}
