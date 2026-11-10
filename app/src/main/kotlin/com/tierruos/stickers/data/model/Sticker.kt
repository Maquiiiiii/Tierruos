package com.tierruos.stickers.data.model

import com.tierruos.stickers.data.StickerStorageNames
import kotlin.math.abs
import kotlin.math.roundToInt

data class Sticker(
    val imageFileName: String,
    val sizeInKb: Double,
    val emojis: List<String> = emptyList(),
) {
    init {
        StickerStorageNames.requireImageFileName(imageFileName)
        require(sizeInKb.isFinite() && sizeInKb > 0.0 && sizeInKb < 98.0)
        require(abs(sizeInKb * 1_000.0 - (sizeInKb * 1_000.0).roundToInt()) < 0.000001) {
            "El tamaño debe representar un número entero de bytes."
        }
        require(emojis.size <= 3 && emojis.all { it.isNotBlank() && it.length <= 64 && '\u0000' !in it })
    }

    /** Decimal KB, consistently with the image engine. SQLite persists this exact byte count. */
    val sizeInBytes: Int get() = (sizeInKb * 1_000.0).roundToInt()

    internal fun snapshot(): Sticker = copy(emojis = java.util.Collections.unmodifiableList(emojis.toList()))
}
