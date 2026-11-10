package com.tierruos.stickers.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.tierruos.stickers.R
import com.tierruos.stickers.databinding.ViewAnimatedBottomNavBinding

/**
 * High-contrast nested-pill bottom navigation bar with vertical icon+label tabs,
 * smooth horizontal sliding indicator, ArgbEvaluator color crossfade, and elastic pulse.
 */
class AnimatedBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    internal val binding: ViewAnimatedBottomNavBinding =
        ViewAnimatedBottomNavBinding.inflate(LayoutInflater.from(context), this, true)

    private val activeColor = ContextCompat.getColor(context, R.color.text_on_accent)
    private val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val argbEvaluator = ArgbEvaluator()
    private val slideInterpolator = FastOutSlowInInterpolator()
    private val pulseInterpolator = OvershootInterpolator(2.0f)
    private var colorAnimator: ValueAnimator? = null

    var selectedTab: Int = TAB_PACKS
        private set

    var onTabSelected: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        binding.tabPacks.setOnClickListener { handleTabClick(TAB_PACKS, binding.tabPacksContent) }
        binding.tabCreate.setOnClickListener { handleTabClick(TAB_CREATE, binding.tabCreateContent) }
        applyImmediateState(TAB_PACKS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            val targetX = if (selectedTab == TAB_CREATE) binding.activeIndicator.width.toFloat() else 0f
            binding.activeIndicator.translationX = targetX
        }
    }

    private fun handleTabClick(tab: Int, contentView: View) {
        if (!isEnabled) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        pulseTabContent(contentView)
        setSelectedTab(tab, animate = true, notify = true)
    }

    fun setSelectedTab(tab: Int, animate: Boolean = true, notify: Boolean = false) {
        require(tab == TAB_PACKS || tab == TAB_CREATE)
        val changed = selectedTab != tab
        selectedTab = tab
        val animationsEnabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (!animate || !animationsEnabled || !isLaidOut || binding.activeIndicator.width == 0) {
            applyImmediateState(tab)
        } else if (changed) {
            animateToTab(tab)
        }
        if (notify) {
            onTabSelected?.invoke(tab)
        }
    }

    private fun pulseTabContent(target: View) {
        val animationsEnabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        target.animate().cancel()
        if (!animationsEnabled) {
            target.scaleX = 1f
            target.scaleY = 1f
            return
        }
        target.scaleX = 0.92f
        target.scaleY = 0.92f
        target.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(PULSE_DURATION_MS)
            .setInterpolator(pulseInterpolator)
            .start()
    }

    private fun animateToTab(tab: Int) {
        val indicatorWidth = binding.activeIndicator.width.toFloat()
        val targetX = if (tab == TAB_CREATE) indicatorWidth else 0f
        binding.activeIndicator.animate()
            .cancel()
        binding.activeIndicator.animate()
            .translationX(targetX)
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(slideInterpolator)
            .start()

        val packsStartColor = binding.labelPacks.currentTextColor
        val packsEndColor = if (tab == TAB_PACKS) activeColor else inactiveColor
        val createStartColor = binding.labelCreate.currentTextColor
        val createEndColor = if (tab == TAB_CREATE) activeColor else inactiveColor

        updateTypographyWeights(tab)

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_DURATION_MS
            interpolator = slideInterpolator
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val pColor = argbEvaluator.evaluate(fraction, packsStartColor, packsEndColor) as Int
                val cColor = argbEvaluator.evaluate(fraction, createStartColor, createEndColor) as Int
                applyTabColors(pColor, cColor)
            }
            start()
        }
    }

    private fun applyImmediateState(tab: Int) {
        colorAnimator?.cancel()
        binding.activeIndicator.animate().cancel()
        val packsColor = if (tab == TAB_PACKS) activeColor else inactiveColor
        val createColor = if (tab == TAB_CREATE) activeColor else inactiveColor
        applyTabColors(packsColor, createColor)
        updateTypographyWeights(tab)
        val updateTranslation = Runnable {
            val width = binding.activeIndicator.width.toFloat()
            binding.activeIndicator.translationX = if (tab == TAB_CREATE) width else 0f
        }
        if (binding.activeIndicator.width > 0) updateTranslation.run() else post(updateTranslation)
    }

    private fun applyTabColors(packsColor: Int, createColor: Int) {
        binding.labelPacks.setTextColor(packsColor)
        ImageViewCompat.setImageTintList(binding.iconPacks, ColorStateList.valueOf(packsColor))
        binding.labelCreate.setTextColor(createColor)
        ImageViewCompat.setImageTintList(binding.iconCreate, ColorStateList.valueOf(createColor))
    }

    private fun updateTypographyWeights(tab: Int) {
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
        binding.labelPacks.typeface = if (tab == TAB_PACKS) bold else medium
        binding.labelCreate.typeface = if (tab == TAB_CREATE) bold else medium
        binding.tabPacks.isSelected = tab == TAB_PACKS
        binding.tabCreate.isSelected = tab == TAB_CREATE
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        binding.tabPacks.isEnabled = enabled
        binding.tabCreate.isEnabled = enabled
        alpha = if (enabled) 1.0f else 0.6f
    }

    companion object {
        const val TAB_PACKS = 0
        const val TAB_CREATE = 1
        private const val SLIDE_DURATION_MS = 260L
        private const val COLOR_DURATION_MS = 220L
        private const val PULSE_DURATION_MS = 260L
    }
}
