package com.tierruos.stickers.ui

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tierruos.stickers.R
import com.tierruos.stickers.data.PackVersionConflictException
import com.tierruos.stickers.export.WhatsAppNotInstalledException
import com.tierruos.stickers.export.WhatsAppUnavailableException
import com.tierruos.stickers.security.ImageTooLargeException
import com.tierruos.stickers.security.InvalidImageFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class TierruosActivity : AppCompatActivity() {
    protected val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}

internal fun View.actionFeedback(accepted: Boolean = true) {
    val haptic = if (Build.VERSION.SDK_INT >= 30) {
        if (accepted) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
    } else HapticFeedbackConstants.VIRTUAL_KEY
    performHapticFeedback(haptic)
    animate().cancel()
    scaleX = 0.97f; scaleY = 0.97f
    val duration = if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) 0L
        else resources.getInteger(R.integer.canvas_feedback_duration_ms).toLong()
    animate().scaleX(1f).scaleY(1f).setDuration(duration).start()
}

internal fun errorMessage(error: Exception): Int = when (error) {
    is SecurityException -> R.string.image_access_denied
    is ImageTooLargeException -> R.string.image_too_large
    is InvalidImageFormatException -> R.string.image_invalid
    is PackVersionConflictException -> R.string.pack_changed
    is NoSuchElementException -> R.string.pack_missing
    is WhatsAppNotInstalledException -> R.string.whatsapp_missing
    is WhatsAppUnavailableException -> R.string.whatsapp_unavailable
    else -> R.string.operation_failed
}
