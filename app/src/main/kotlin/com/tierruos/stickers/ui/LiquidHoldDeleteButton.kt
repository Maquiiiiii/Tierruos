package com.tierruos.stickers.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.tierruos.stickers.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Custom View (52dp height, 16dp radius, #1A1114 background, 1dp #4A1C24 border) that fills
 * from bottom to top with dual overlapping sinusoidal red waves (#CCFF1744 and #FFFF2D55) over
 * 1600ms while held down, drains back in 400ms if released early, and fires LONG_PRESS haptic +
 * onHoldComplete when reaching 100%.
 */
class LiquidHoldDeleteButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onHoldComplete: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val cornerRadiusPx = 16f * density
    private val strokeWidthPx = 1f * density

    private val boundsRect = RectF()
    private val strokeRect = RectF()
    private val clipPath = Path()
    private val backWavePath = Path()
    private val frontWavePath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A1114")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = Color.parseColor("#4A1C24")
    }
    private val backWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFF1744")
    }
    private val frontWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFF2D55")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B5C")
        textSize = 14.5f * scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }

    private val trashDrawable = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()
    private val labelText = context.getString(R.string.liquid_hold_delete)

    private var fillProgress = 0f
    private var wavePhase = 0f
    private var completed = false

    private var levelAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = labelText
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeight = (52f * density).toInt()
        val resolvedWidth = resolveSize((280f * density).toInt(), widthMeasureSpec)
        val resolvedHeight = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = strokeWidthPx / 2f
        strokeRect.set(inset, inset, w - inset, h - inset)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // Base surface
        canvas.drawRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, bgPaint)

        // Rising sinusoidal liquid waves
        if (fillProgress > 0.001f) {
            val baseLevelY = h * (1f - fillProgress)
            val amplitude = (4.5f * density) * (1f - (fillProgress - 0.92f).coerceAtLeast(0f) / 0.08f)
            val freq1 = (2.0 * PI / (w * 0.65)).toFloat()
            val freq2 = (2.0 * PI / (w * 0.52)).toFloat()

            buildWavePath(backWavePath, w, h, baseLevelY, amplitude, freq1, wavePhase)
            buildWavePath(frontWavePath, w, h, baseLevelY, amplitude * 1.15f, freq2, -wavePhase * 1.3f + 1.2f)

            canvas.drawPath(backWavePath, backWavePaint)
            canvas.drawPath(frontWavePath, frontWavePaint)
        }

        // Foreground icon + label (switches from #FF3B5C to white as liquid rises past center)
        val fgColor = if (fillProgress > 0.52f) Color.WHITE else Color.parseColor("#FF3B5C")
        textPaint.color = fgColor

        val iconSize = (18f * density).toInt()
        val gap = 8f * density
        val textWidth = textPaint.measureText(labelText)
        val totalWidth = iconSize + gap + textWidth
        val startX = (w - totalWidth) / 2f
        val centerY = h / 2f

        trashDrawable?.let { icon ->
            icon.setTint(fgColor)
            val iconTop = (centerY - iconSize / 2f).toInt()
            icon.setBounds(
                startX.toInt(),
                iconTop,
                startX.toInt() + iconSize,
                iconTop + iconSize,
            )
            icon.draw(canvas)
        }

        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(labelText, startX + iconSize + gap, textBaseline, textPaint)

        canvas.restoreToCount(saveCount)

        // Precision 1dp border
        canvas.drawRoundRect(strokeRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }

    private fun buildWavePath(
        path: Path,
        width: Float,
        height: Float,
        baseY: Float,
        amplitude: Float,
        frequency: Float,
        phase: Float,
    ) {
        path.reset()
        path.moveTo(0f, height)
        val step = (6f * density).coerceAtLeast(4f)
        var x = 0f
        while (x <= width) {
            val y = baseY + amplitude * sin((x * frequency + phase).toDouble()).toFloat()
            path.lineTo(x, y)
            x += step
        }
        val endY = baseY + amplitude * sin((width * frequency + phase).toDouble()).toFloat()
        path.lineTo(width, endY)
        path.lineTo(width, height)
        path.close()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (completed) return true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                startRisingAnimation()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!completed && fillProgress < 1f) {
                    startDrainingAnimation()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startRisingAnimation() {
        levelAnimator?.cancel()
        ensureWaveAnimatorRunning()

        val remainingFraction = (1f - fillProgress).coerceIn(0f, 1f)
        val durationMs = (1600L * remainingFraction).toLong().coerceAtLeast(80L)

        levelAnimator = ValueAnimator.ofFloat(fillProgress, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                fillProgress = anim.animatedValue as Float
                invalidate()
                if (fillProgress >= 1f && !completed) {
                    completed = true
                    stopWaveAnimator()
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onHoldComplete?.invoke()
                }
            }
            start()
        }
    }

    private fun startDrainingAnimation() {
        levelAnimator?.cancel()
        if (fillProgress <= 0f) {
            stopWaveAnimator()
            return
        }
        val startValue = fillProgress
        levelAnimator = ValueAnimator.ofFloat(startValue, 0f).apply {
            duration = 400L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                fillProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fillProgress <= 0.001f) {
                        fillProgress = 0f
                        stopWaveAnimator()
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun ensureWaveAnimatorRunning() {
        if (waveAnimator?.isRunning == true) return
        waveAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = 650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                wavePhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopWaveAnimator() {
        waveAnimator?.cancel()
        waveAnimator = null
    }

    fun resetState() {
        completed = false
        levelAnimator?.cancel()
        stopWaveAnimator()
        fillProgress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        levelAnimator?.cancel()
        stopWaveAnimator()
        super.onDetachedFromWindow()
    }
}
