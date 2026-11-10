package com.tierruos.stickers.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.provider.Settings
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Custom AppCompatTextView that renders "TIERRUOS" in uppercase as a single accessible touch target
 * and plays a one-time staggered left-to-right 3D paper-unfold animation (620ms per letter, 60ms stagger,
 * ~1040ms total) using Camera + Matrix perspective transforms and artisanal cubic-bezier deceleration.
 */
class PaperUnfoldTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val titleString = TITLE_TEXT
    private val letterProgress = FloatArray(titleString.length) { 1f }
    private val camera = Camera()
    private val letterMatrix = Matrix()
    private val density = resources.displayMetrics.density

    private var isAnimatingUnfold = false
    private var hasPlayedOnce = false
    private var unfoldAnimatorSet: AnimatorSet? = null

    init {
        text = titleString
        contentDescription = titleString
        // Higher camera Z distance prevents harsh perspective distortion while preserving 3D depth.
        camera.setLocation(0f, 0f, -10f * density)
    }

    /**
     * Plays the progressive paper-unfold animation once. If system animations are disabled or
     * [alreadyPlayed] is true, immediately sets the title to its final flat state.
     */
    fun playUnfoldOnceIfAllowed(alreadyPlayed: Boolean) {
        if (alreadyPlayed || hasPlayedOnce) {
            setToFinalFlatState()
            return
        }
        hasPlayedOnce = true

        if (!areSystemAnimationsEnabled()) {
            setToFinalFlatState()
            return
        }

        cancelAndResetAnimators(restoreFlatState = false)
        isAnimatingUnfold = true
        for (i in letterProgress.indices) {
            letterProgress[i] = 0f
        }
        invalidate()

        val interpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
        val letterAnimators = titleString.indices.map { index ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LETTER_DURATION_MS
                startDelay = index * LETTER_STAGGER_MS
                this.interpolator = interpolator
                addUpdateListener { anim ->
                    letterProgress[index] = anim.animatedValue as Float
                    invalidate()
                }
            }
        }

        unfoldAnimatorSet = AnimatorSet().apply {
            playTogether(letterAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setToFinalFlatState()
                }

                override fun onAnimationCancel(animation: Animator) {
                    setToFinalFlatState()
                }
            })
            start()
        }
    }

    fun cancelAndRelease() {
        cancelAndResetAnimators(restoreFlatState = true)
    }

    private fun setToFinalFlatState() {
        isAnimatingUnfold = false
        for (i in letterProgress.indices) {
            letterProgress[i] = 1f
        }
        letterMatrix.reset()
        unfoldAnimatorSet = null
        invalidate()
    }

    private fun cancelAndResetAnimators(restoreFlatState: Boolean) {
        val active = unfoldAnimatorSet
        unfoldAnimatorSet = null
        active?.removeAllListeners()
        active?.cancel()
        if (restoreFlatState) {
            setToFinalFlatState()
        }
    }

    private fun areSystemAnimationsEnabled(): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (_: Exception) {
            1f
        }
        return scale > 0f
    }

    override fun onDraw(canvas: Canvas) {
        if (!isAnimatingUnfold) {
            super.onDraw(canvas)
            return
        }

        val textLayout = layout
        if (textLayout == null || titleString.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        val textPaint = paint
        textPaint.color = currentTextColor
        textPaint.drawableState = drawableState

        val baseColor = currentTextColor
        val baseR = Color.red(baseColor)
        val baseG = Color.green(baseColor)
        val baseB = Color.blue(baseColor)

        // Shaded crease tone when folded
        val foldR = 135
        val foldG = 135
        val foldB = 148

        val baselineY = baseline.toFloat()
        val fontMetrics = textPaint.fontMetrics
        val glyphTopY = baselineY + fontMetrics.ascent
        val glyphCenterY = baselineY + (fontMetrics.ascent + fontMetrics.descent) * 0.5f
        val startPadding = compoundPaddingLeft.toFloat()

        for (i in titleString.indices) {
            val p = letterProgress[i].coerceIn(0f, 1f)
            val fold = 1f - p

            val charLeft = startPadding + textLayout.getPrimaryHorizontal(i)
            val charStr = titleString.substring(i, i + 1)
            val charWidth = textPaint.measureText(charStr)
            val hingeX = charLeft + charWidth * 0.35f
            val hingeY = glyphTopY + (glyphCenterY - glyphTopY) * 0.45f

            // 3D paper-unfold transformation: starts folded along the top-left crease with reduced
            // opening-axis scale and perspective rotation, settling smoothly to identity at p == 1f.
            val rotX = fold * -66f
            val rotY = fold * -20f
            val scaleX = 0.84f + 0.16f * p
            val scaleY = 0.26f + 0.74f * p
            val liftY = -fold * 4.5f * density

            letterMatrix.reset()
            camera.save()
            camera.rotateX(rotX)
            camera.rotateY(rotY)
            camera.getMatrix(letterMatrix)
            camera.restore()

            letterMatrix.preScale(scaleX, scaleY)
            letterMatrix.preTranslate(-hingeX, -hingeY)
            letterMatrix.postTranslate(hingeX, hingeY + liftY)

            val alpha = ((0.14f + 0.86f * p) * 255f).toInt().coerceIn(0, 255)
            val r = (foldR + ((baseR - foldR) * p)).toInt().coerceIn(0, 255)
            val g = (foldG + ((baseG - foldG) * p)).toInt().coerceIn(0, 255)
            val b = (foldB + ((baseB - foldB) * p)).toInt().coerceIn(0, 255)
            textPaint.color = Color.argb(alpha, r, g, b)

            val saveCount = canvas.save()
            canvas.concat(letterMatrix)
            canvas.drawText(titleString, i, i + 1, charLeft, baselineY, textPaint)
            canvas.restoreToCount(saveCount)
        }

        textPaint.color = baseColor
    }

    override fun onDetachedFromWindow() {
        cancelAndRelease()
        super.onDetachedFromWindow()
    }

    companion object {
        const val TITLE_TEXT = "TIERRUOS"
        private const val LETTER_DURATION_MS = 620L
        private const val LETTER_STAGGER_MS = 60L
    }
}
