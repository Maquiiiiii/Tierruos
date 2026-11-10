package com.tierruos.stickers.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import com.tierruos.stickers.R
import com.tierruos.stickers.databinding.ViewStickerCanvasBinding

/** Debug-only host for native gesture tests and screenshots; it never receives external images. */
class CanvasPreviewActivity : Activity() {
    lateinit var stickerCanvas: StickerCanvasView
        private set

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.background))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val binding = ViewStickerCanvasBinding.inflate(layoutInflater, root, false)
        (binding.root.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        root.addView(binding.root)
        setContentView(root)
        stickerCanvas = binding.stickerCanvas
        showSample()
    }

    fun showSample() {
        val sample = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sample)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.accent) }
        canvas.drawRoundRect(RectF(112f, 112f, 400f, 400f), 72f, 72f, paint)
        paint.color = getColor(R.color.background)
        canvas.drawCircle(208f, 232f, 16f, paint)
        canvas.drawCircle(304f, 232f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 12f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(208f, 256f, 304f, 320f), 10f, 160f, false, paint)
        stickerCanvas.setImageBitmap(sample)
        sample.recycle()
        stickerCanvas.setTextLayer(getString(R.string.app_name))
        stickerCanvas.applyOutline()
    }
}
