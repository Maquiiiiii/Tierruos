package com.tierruos.stickers.security

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal object ImageFixtures {
    const val PAYLOAD = "tierruos-untrusted-exif-xmp-test-9e8c61e7"

    @Suppress("DEPRECATION")
    fun solidImage(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(format, 90, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun noiseImage(randomAlpha: Boolean): ByteArray {
        val edge = ImageSanitizerAndCompressor.STICKER_EDGE - 2 * ImageSanitizerAndCompressor.STICKER_PADDING_PX
        val random = Random(20261109L)
        val pixels = IntArray(edge * edge) {
            val color = random.nextInt()
            if (randomAlpha) color else color or Color.BLACK
        }
        val bitmap = Bitmap.createBitmap(pixels, edge, edge, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Streams up to 64M grayscale pixels into deflate without allocating a large Bitmap. */
    fun grayscalePng(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(width)
                out.writeInt(height)
                out.write(byteArrayOf(8, 0, 0, 0, 0))
            }
        }.toByteArray()
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { deflate ->
                val row = ByteArray(width + 1)
                repeat(height) { deflate.write(row) }
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", compressed))
            write(chunk("IEND", byteArrayOf()))
        }.toByteArray()
    }

    fun pngWithMetadata(): ByteArray {
        val png = solidImage()
        val text = chunk("tEXt", "Comment\u0000$PAYLOAD".toByteArray(Charsets.US_ASCII))
        return png.copyOfRange(0, png.size - 12) + text + png.copyOfRange(png.size - 12, png.size)
    }

    fun jpegWithExif(): ByteArray {
        val jpeg = solidImage(Bitmap.CompressFormat.JPEG)
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0) +
            PAYLOAD.toByteArray(Charsets.US_ASCII)
        val app1 = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeShort(0xFFE1)
                out.writeShort(exif.size + 2)
                out.write(exif)
            }
        }.toByteArray()
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(data.size)
                out.write(typeBytes)
                out.write(data)
                out.writeInt(crc.value.toInt())
            }
        }.toByteArray()
    }
}
