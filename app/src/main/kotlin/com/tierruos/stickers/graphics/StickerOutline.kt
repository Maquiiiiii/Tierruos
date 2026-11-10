package com.tierruos.stickers.graphics

import android.graphics.Bitmap
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

/** Circular alpha dilation by an exact squared Euclidean distance transform, O(width * height). */
internal object StickerOutline {
    fun create(source: Bitmap, radius: Float, color: Int, context: CoroutineContext): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val distances = IntArray(pixels.size)
        val length = maxOf(width, height)
        val input = IntArray(length)
        val output = IntArray(length)
        val sites = IntArray(length)
        val boundaries = DoubleArray(length + 1)
        val infinity = 1_000_000
        for (x in 0 until width) {
            context.ensureActive()
            for (y in 0 until height) {
                input[y] = if ((pixels[y * width + x] ushr 24) >= 128) 0 else infinity
            }
            distanceTransform(input, output, height, sites, boundaries)
            for (y in 0 until height) distances[y * width + x] = output[y]
        }
        for (y in 0 until height) {
            context.ensureActive()
            for (x in 0 until width) input[x] = distances[y * width + x]
            distanceTransform(input, output, width, sites, boundaries)
            for (x in 0 until width) {
                val coverage = (radius + 0.5f - sqrt(output[x].toFloat())).coerceIn(0f, 1f)
                val alpha = (coverage * 255f).roundToInt()
                pixels[y * width + x] = (color and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        context.ensureActive()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
    }

    private fun distanceTransform(
        input: IntArray,
        output: IntArray,
        length: Int,
        sites: IntArray,
        boundaries: DoubleArray,
    ) {
        var top = 0
        sites[0] = 0
        boundaries[0] = Double.NEGATIVE_INFINITY
        boundaries[1] = Double.POSITIVE_INFINITY
        for (q in 1 until length) {
            var previous = sites[top]
            var intersection = ((input[q] + q * q).toDouble() - input[previous] - previous * previous) /
                (2.0 * (q - previous))
            while (intersection <= boundaries[top]) {
                top--
                previous = sites[top]
                intersection = ((input[q] + q * q).toDouble() - input[previous] - previous * previous) /
                    (2.0 * (q - previous))
            }
            sites[++top] = q
            boundaries[top] = intersection
            boundaries[top + 1] = Double.POSITIVE_INFINITY
        }
        top = 0
        for (q in 0 until length) {
            while (boundaries[top + 1] < q) top++
            val distance = q - sites[top]
            output[q] = distance * distance + input[sites[top]]
        }
    }
}
