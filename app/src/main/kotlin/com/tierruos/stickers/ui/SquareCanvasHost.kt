package com.tierruos.stickers.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/** Fits one complete square into the workspace, including short landscape windows. */
class SquareCanvasHost @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val side = min((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0),
            (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0))
        if (childCount > 0) getChildAt(0).measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val child = getChildAt(0)
        val x = paddingLeft + (width - paddingLeft - paddingRight - child.measuredWidth) / 2
        val y = paddingTop + (height - paddingTop - paddingBottom - child.measuredHeight) / 2
        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
    }
}
