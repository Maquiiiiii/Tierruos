package com.tierruos.stickers.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Enforces a 1:1 square aspect ratio for grid cells in StickerPackDetailActivity. */
class SquareSlotFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
