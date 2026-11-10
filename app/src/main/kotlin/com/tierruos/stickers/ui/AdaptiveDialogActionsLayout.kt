package com.tierruos.stickers.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Adaptive action container for dialogs.
 * Measures the two action buttons ("Cancelar" and positive action) at their natural single-line width:
 * - If both fit side-by-side within the available width with a 12dp gap, lays them out horizontally
 *   aligned to the end (or balanced) with full labels visible.
 * - If the screen is narrow or system font scale is enlarged so they would wrap or clip in one row,
 *   stacks them vertically at full width with a 10dp gap so both labels remain 100% complete.
 */
class AdaptiveDialogActionsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val horizontalGapPx = (12f * density).toInt()
    private val verticalGapPx = (10f * density).toInt()
    private val minTouchTargetPx = (48f * density).toInt()

    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visibleChildren = (0 until childCount)
            .map { getChildAt(it) }
            .filter { it.visibility != View.GONE }

        if (visibleChildren.isEmpty()) {
            setMeasuredDimension(
                resolveSize(0, widthMeasureSpec),
                resolveSize(0, heightMeasureSpec),
            )
            return
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            (resources.displayMetrics.widthPixels * 0.8f).toInt()
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        val unconstrainedSpec = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST)
        val unspecifiedHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        var totalNaturalWidth = 0
        var requiresStack = false
        for (child in visibleChildren) {
            if (child is TextView) {
                child.isSingleLine = false
                child.maxLines = 2
            }
            child.measure(unconstrainedSpec, unspecifiedHeight)
            totalNaturalWidth += child.measuredWidth
            if (child is TextView && (child.layout?.lineCount ?: 1) > 1) {
                requiresStack = true
            }
        }
        if (visibleChildren.size > 1) {
            totalNaturalWidth += horizontalGapPx * (visibleChildren.size - 1)
        }

        stacked = requiresStack || totalNaturalWidth > availableWidth

        if (!stacked) {
            var maxHeight = minTouchTargetPx
            for (child in visibleChildren) {
                maxHeight = maxOf(maxHeight, child.measuredHeight)
            }
            val exactHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY)
            for (child in visibleChildren) {
                val exactWidthSpec = MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY)
                child.measure(exactWidthSpec, exactHeightSpec)
            }
            val finalWidth = if (widthMode == MeasureSpec.EXACTLY) {
                widthSize
            } else {
                minOf(widthSize, totalNaturalWidth + paddingLeft + paddingRight)
            }
            val finalHeight = maxHeight + paddingTop + paddingBottom
            setMeasuredDimension(
                resolveSize(finalWidth, widthMeasureSpec),
                resolveSize(finalHeight, heightMeasureSpec),
            )
        } else {
            val fullWidthSpec = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY)
            var totalHeight = paddingTop + paddingBottom
            // In stacked mode, measure each button across the full available width.
            for ((index, child) in visibleChildren.withIndex()) {
                child.measure(fullWidthSpec, unspecifiedHeight)
                val h = maxOf(minTouchTargetPx, child.measuredHeight)
                if (h != child.measuredHeight) {
                    child.measure(fullWidthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                }
                totalHeight += child.measuredHeight
                if (index < visibleChildren.size - 1) {
                    totalHeight += verticalGapPx
                }
            }
            val finalWidth = availableWidth + paddingLeft + paddingRight
            setMeasuredDimension(
                resolveSize(finalWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val visibleChildren = (0 until childCount)
            .map { getChildAt(it) }
            .filter { it.visibility != View.GONE }
        if (visibleChildren.isEmpty()) return

        val contentLeft = paddingLeft
        val contentRight = (r - l) - paddingRight
        val contentTop = paddingTop

        if (!stacked) {
            val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            var totalW = visibleChildren.sumOf { it.measuredWidth }
            if (visibleChildren.size > 1) {
                totalW += horizontalGapPx * (visibleChildren.size - 1)
            }
            var currentX = if (isRtl) {
                contentLeft
            } else {
                (contentRight - totalW).coerceAtLeast(contentLeft)
            }
            val ordered = if (isRtl) visibleChildren.reversed() else visibleChildren
            for (child in ordered) {
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                child.layout(currentX, contentTop, currentX + cw, contentTop + ch)
                currentX += cw + horizontalGapPx
            }
        } else {
            // In stacked mode, put the primary (positive, last child) on top and secondary ("Cancelar") below
            // so the primary CTA is immediately reachable, with full labels visible.
            val stackedOrder = visibleChildren.reversed()
            var currentY = contentTop
            for (child in stackedOrder) {
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                child.layout(contentLeft, currentY, contentLeft + cw, currentY + ch)
                currentY += ch + verticalGapPx
            }
        }
    }
}
