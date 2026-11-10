package com.tierruos.stickers.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.tierruos.stickers.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Floating mini-navbar overlay shown when tapping a created sticker in StickerPackDetailActivity.
 * - Scrim: #80000000
 * - 1st Pill ("Editar", startDelay = 0ms): #1E1E28 with 1.5dp #38384A border, continuous water-floating
 *   animation (translationY ±3.5dp, rotation ±1.2°, and internal translucent sinusoidal wave).
 * - 2nd Pill ("Eliminar", startDelay = 95ms, OvershootInterpolator): #FF1744 with 1.5dp #FF5252 border.
 */
object StickerFloatingActionMenu {

    fun show(
        activity: Activity,
        anchorView: View,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        decorView.findViewWithTag<View>(OVERLAY_TAG)?.let { existing ->
            decorView.removeView(existing)
        }

        val context = activity
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity

        val overlay = object : FrameLayout(context) {
            var floatAnimator: ValueAnimator? = null
            var waveAnimator: ValueAnimator? = null

            override fun onDetachedFromWindow() {
                floatAnimator?.cancel()
                waveAnimator?.cancel()
                super.onDetachedFromWindow()
            }
        }.apply {
            tag = OVERLAY_TAG
            setBackgroundColor(Color.parseColor("#80000000"))
            alpha = 0f
            isClickable = true
            isFocusable = true
        }

        fun dismissOverlay(afterDismiss: (() -> Unit)? = null) {
            overlay.floatAnimator?.cancel()
            overlay.waveAnimator?.cancel()
            overlay.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    decorView.removeView(overlay)
                    afterDismiss?.invoke()
                }
                .start()
        }

        overlay.setOnClickListener { dismissOverlay() }

        val pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            val pad = (12f * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val editPill = WaterPillView(
            context = context,
            label = context.getString(R.string.floating_edit),
            iconRes = R.drawable.ic_edit,
            bgColor = Color.parseColor("#1E1E28"),
            borderColor = Color.parseColor("#38384A"),
            hasWaterWave = true,
            density = density,
            scaledDensity = scaledDensity,
        ).apply {
            alpha = 0f
            translationY = 18f * density
            scaleX = 0.86f
            scaleY = 0.86f
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dismissOverlay(onEdit)
            }
        }

        val deletePill = WaterPillView(
            context = context,
            label = context.getString(R.string.floating_delete),
            iconRes = R.drawable.ic_delete,
            bgColor = Color.parseColor("#FF1744"),
            borderColor = Color.parseColor("#FF5252"),
            hasWaterWave = false,
            density = density,
            scaledDensity = scaledDensity,
        ).apply {
            alpha = 0f
            translationY = 22f * density
            scaleX = 0.82f
            scaleY = 0.82f
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dismissOverlay(onDelete)
            }
        }

        val pillGap = (10f * density).toInt()
        pillContainer.addView(
            editPill,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44f * density).toInt(),
            ).apply {
                marginEnd = pillGap
            },
        )
        pillContainer.addView(
            deletePill,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44f * density).toInt(),
            ),
        )

        overlay.addView(
            pillContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        decorView.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        overlay.post {
            val anchorRect = Rect()
            anchorView.getGlobalVisibleRect(anchorRect)
            val decorWidth = decorView.width
            val decorHeight = decorView.height
            val menuWidth = pillContainer.width
            val menuHeight = pillContainer.height
            val margin = (12f * density).toInt()

            val targetX = (anchorRect.centerX() - menuWidth / 2)
                .coerceIn(margin, (decorWidth - menuWidth - margin).coerceAtLeast(margin))
            val aboveY = anchorRect.top - menuHeight - (4f * density).toInt()
            val belowY = anchorRect.bottom + (4f * density).toInt()
            val targetY = if (aboveY >= (56f * density).toInt()) {
                aboveY
            } else {
                belowY.coerceAtMost((decorHeight - menuHeight - margin).coerceAtLeast(margin))
            }

            pillContainer.x = targetX.toFloat()
            pillContainer.y = targetY.toFloat()

            // Scrim fade-in
            overlay.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()

            // 1st Button ("Editar", startDelay = 0ms) + continuous water bobbing
            editPill.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(0L)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    val bobAmplitudePx = 3.5f * density
                    val pitchDeg = 1.2f
                    overlay.floatAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
                        duration = 2100L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                        addUpdateListener { anim ->
                            val angle = anim.animatedValue as Float
                            editPill.translationY = sin(angle.toDouble()).toFloat() * bobAmplitudePx
                            editPill.rotation = sin((angle + PI / 3.0)).toFloat() * pitchDeg
                        }
                        start()
                    }
                    overlay.waveAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
                        duration = 1400L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                        addUpdateListener { anim ->
                            editPill.wavePhase = anim.animatedValue as Float
                            editPill.invalidate()
                        }
                        start()
                    }
                }
                .start()

            // 2nd Button ("Eliminar", startDelay = 95ms, OvershootInterpolator)
            deletePill.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(95L)
                .setDuration(290L)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()
        }
    }

    private class WaterPillView(
        context: Context,
        private val label: String,
        iconRes: Int,
        bgColor: Int,
        borderColor: Int,
        private val hasWaterWave: Boolean,
        private val density: Float,
        scaledDensity: Float,
    ) : View(context) {

        var wavePhase: Float = 0f

        private val strokeWidthPx = 1.5f * density
        private val horizontalPadPx = 16f * density
        private val iconSizePx = (18f * density).toInt()
        private val iconGapPx = 8f * density

        private val rect = RectF()
        private val borderRect = RectF()
        private val clipPath = Path()
        private val wavePath = Path()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bgColor
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = borderColor
        }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#2438BDF8")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * scaledDensity
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val iconDrawable = ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
            setTint(Color.WHITE)
        }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = label
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val textWidth = textPaint.measureText(label)
            val desiredW = (horizontalPadPx * 2f + iconSizePx + iconGapPx + textWidth).toInt()
            val desiredH = (44f * density).toInt()
            setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val radius = h / 2f
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            val inset = strokeWidthPx / 2f
            borderRect.set(inset, inset, w - inset, h - inset)
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = h / 2f

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            if (hasWaterWave) {
                wavePath.reset()
                val baseY = h * 0.58f
                val amp = 2.8f * density
                val freq = (2.0 * PI / (w * 0.75)).toFloat()
                wavePath.moveTo(0f, h)
                var x = 0f
                val step = 5f * density
                while (x <= w) {
                    val y = baseY + amp * sin((x * freq + wavePhase).toDouble()).toFloat()
                    wavePath.lineTo(x, y)
                    x += step
                }
                wavePath.lineTo(w, h)
                wavePath.close()
                canvas.drawPath(wavePath, wavePaint)
            }

            val centerY = h / 2f
            val iconLeft = horizontalPadPx.toInt()
            val iconTop = (centerY - iconSizePx / 2f).toInt()
            iconDrawable?.setBounds(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx)
            iconDrawable?.draw(canvas)

            val fm = textPaint.fontMetrics
            val textY = centerY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, horizontalPadPx + iconSizePx + iconGapPx, textY, textPaint)

            canvas.restoreToCount(save)
            canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
        }
    }

    private const val OVERLAY_TAG = "TierruosFloatingStickerMenu"
}
