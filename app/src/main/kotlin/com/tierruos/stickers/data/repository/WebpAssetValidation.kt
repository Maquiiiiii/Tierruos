package com.tierruos.stickers.data.repository

import android.graphics.BitmapFactory
import com.tierruos.stickers.security.ImageTooLargeException
import com.tierruos.stickers.security.InvalidImageFormatException

/** Defense in depth for Module 2 output. This does not replace destructive input sanitization. */
internal object WebpAssetValidation {
    fun snapshot(bytes: ByteArray, edge: Int, exclusiveLimit: Int): ByteArray {
        if (bytes.size >= exclusiveLimit) throw ImageTooLargeException("La imagen supera el presupuesto de salida.")
        val value = bytes.copyOf()
        if (value.size < 12 || String(value, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(value, 8, 4, Charsets.US_ASCII) != "WEBP" || uint(value, 4) + 8 != value.size.toLong()
        ) throw InvalidImageFormatException("El archivo debe ser un WebP estático sanitizado.")
        var offset = 12
        var frames = 0
        while (offset < value.size) {
            if (value.size - offset < 8) throw InvalidImageFormatException("Chunk WebP incompleto.")
            val length = uint(value, offset + 4)
            val end = offset.toLong() + 8L + length + (length and 1L)
            if (end > value.size) throw InvalidImageFormatException("Longitud WebP inválida.")
            when (String(value, offset, 4, Charsets.US_ASCII)) {
                "VP8 ", "VP8L" -> frames++
                "ALPH" -> Unit
                "VP8X" -> if (length != 10L || (value[offset + 8].toInt() and 0xEF) != 0) {
                    throw InvalidImageFormatException("El WebP contiene metadatos o animación.")
                }
                else -> throw InvalidImageFormatException("El WebP contiene chunks ajenos a los píxeles.")
            }
            offset = end.toInt()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(value, 0, value.size, options)
        if (frames != 1 || options.outWidth != edge || options.outHeight != edge || options.outMimeType != "image/webp") {
            throw InvalidImageFormatException("El WebP debe medir exactamente $edge por $edge píxeles.")
        }
        return value
    }

    private fun uint(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 255) or ((bytes[offset + 1].toLong() and 255) shl 8) or
            ((bytes[offset + 2].toLong() and 255) shl 16) or ((bytes[offset + 3].toLong() and 255) shl 24)
}
