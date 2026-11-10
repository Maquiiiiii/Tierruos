package com.tierruos.stickers.security

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.tierruos.stickers.ui.CanvasChecks
import com.tierruos.stickers.data.DataChecks
import com.tierruos.stickers.provider.IpcExportChecks
import com.tierruos.stickers.ui.PresentationChecks
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Android's native instrumentation protocol; no JUnit/AndroidX Test dependency is needed. */
class SanitizerInstrumentation : Instrumentation() {
    private lateinit var engine: ImageSanitizerAndCompressor
    private lateinit var fixtures: File

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private class Case(val name: String, val body: suspend () -> Unit)

    @Suppress("DEPRECATION")
    override fun onStart() = runBlocking {
        engine = ImageSanitizerAndCompressor(targetContext)
        fixtures = File(targetContext.cacheDir, "sanitizer-tests-${System.nanoTime()}")
        check(fixtures.mkdirs())
        val cases = listOf(
            Case("pngJpegWebpNormalizeAndCenter") {
                for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
                    val result = engine.createSticker(fixture(ImageFixtures.solidImage(format)))
                    verify(result, 512, 98_000)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.sizeBytes))
                    try {
                        check(Color.alpha(bitmap.getPixel(256, 0)) == 0)
                        check(Color.alpha(bitmap.getPixel(256, 511)) == 0)
                        check(Color.alpha(bitmap.getPixel(8, 256)) == 0)
                        check(Color.alpha(bitmap.getPixel(503, 256)) == 0)
                        check(Color.red(bitmap.getPixel(256, 256)) > 180)
                    } finally { bitmap.recycle() }
                }
            },
            Case("trayIs96AndBelow48KB") {
                verify(engine.createTrayIcon(fixture(ImageFixtures.solidImage())), 96, 48_000)
            },
            Case("ignoresProviderMimeAndExtension") {
                verify(engine.createSticker(providerUri("spoofed")), 512, 98_000)
            },
            Case("preservesProviderSecurityException") {
                expect<SecurityException> { engine.createSticker(providerUri("denied")) }
            },
            Case("rejectsUnknownAndTruncatedSignatures") {
                for (bytes in listOf(byteArrayOf(), ByteArray(11), "GIF89a-not-a-PNG-file".toByteArray(),
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(30))) {
                    expect<InvalidImageFormatException> { engine.createSticker(fixture(bytes)) }
                }
            },
            Case("rejectsDimensionBombsBeforePixelDecode") {
                for ((width, height) in listOf(8001 to 1, 1 to 8001, 0 to 1)) {
                    val uri = fixture(ImageFixtures.grayscalePng(width, height))
                    if (width == 0) expect<InvalidImageFormatException> { engine.createSticker(uri) }
                    else expect<ImageTooLargeException> { engine.createSticker(uri) }
                }
            },
            Case("samples8000SquareWithoutFullBitmap") {
                verify(engine.createSticker(fixture(ImageFixtures.grayscalePng(8000, 8000))), 512, 98_000)
            },
            Case("rejectsKnownOversizeBeforeOpening") {
                resetProvider()
                expect<ImageTooLargeException> { engine.createSticker(providerUri("known-large")) }
                check(providerOpens() == 0)
            },
            Case("rejectsUnknownSizePipeAbove15MB") {
                expect<ImageTooLargeException> { engine.createSticker(providerUri("unknown-large")) }
            },
            Case("rejectsOversizeLocalDescriptor") {
                val file = File(fixtures, "large.bin")
                RandomAccessFile(file, "rw").use { it.setLength(15_000_001L) }
                expect<ImageTooLargeException> { engine.createSticker(Uri.fromFile(file)) }
            },
            Case("acceptsExactly15MBAndDiscardsTrailingData") {
                val file = File(fixtures, "exact-limit.png")
                file.writeBytes(ImageFixtures.solidImage())
                RandomAccessFile(file, "rw").use { it.setLength(15_000_000L) }
                verify(engine.createSticker(Uri.fromFile(file)), 512, 98_000)
            },
            Case("snapshotsProviderOnlyOnce") {
                resetProvider()
                verify(engine.createSticker(providerUri("swap")), 512, 98_000)
                check(providerOpens() == 1)
            },
            Case("destroysExifTextAndTrailingPayload") {
                for (source in listOf(ImageFixtures.jpegWithExif(), ImageFixtures.pngWithMetadata(),
                    ImageFixtures.solidImage() + ImageFixtures.PAYLOAD.toByteArray())) {
                    val result = engine.createSticker(fixture(source))
                    verify(result, 512, 98_000)
                    check(!String(result.bytes, Charsets.ISO_8859_1).contains(ImageFixtures.PAYLOAD))
                }
            },
            Case("rejectsWebpTrailingPolyglot") {
                val bytes = ImageFixtures.solidImage(Bitmap.CompressFormat.WEBP) + byteArrayOf(1, 2)
                expect<InvalidImageFormatException> { engine.createSticker(fixture(bytes)) }
            },
            Case("reducesQualityForColorNoise") {
                val result = engine.createSticker(fixture(ImageFixtures.noiseImage(false)))
                verify(result, 512, 98_000)
                check(result.quality < 90)
            },
            Case("rejectsIncompressibleAlphaInsteadOfReturningOversize") {
                expect<ImageTooLargeException> {
                    engine.createSticker(fixture(ImageFixtures.noiseImage(true)))
                }
            },
            Case("cancelsImportAndCleansPrivateSnapshot") {
                resetProvider()
                coroutineScope {
                    val import = launch { engine.createSticker(providerUri("slow")) }
                    withTimeout(3_000) { while (providerOpens() == 0) delay(10) }
                    delay(50)
                    withTimeout(3_000) { import.cancelAndJoin() }
                    check(import.isCancelled)
                }
            },
            Case("serializesDifferentInstances") {
                val uri = fixture(ImageFixtures.solidImage())
                coroutineScope {
                    List(4) {
                        async { ImageSanitizerAndCompressor(targetContext).createSticker(uri) }
                    }.awaitAll().forEach { verify(it, 512, 98_000) }
                }
            },
            Case("rejectsNetworkUri") {
                expect<SecurityException> { engine.createSticker(Uri.parse("https://example.com/image.png")) }
            },
        ) + CanvasChecks(this@SanitizerInstrumentation).cases().map { Case(it.name, it.body) } +
            DataChecks(this@SanitizerInstrumentation).cases().map { Case(it.name, it.body) } +
            IpcExportChecks(this@SanitizerInstrumentation).cases().map { Case(it.name, it.body) } +
            PresentationChecks(this@SanitizerInstrumentation).cases().map { Case(it.name, it.body) }
        var failures = 0
        try {
            cases.forEachIndexed { index, case ->
                val status = Bundle().apply {
                    putString("class", this@SanitizerInstrumentation.javaClass.name)
                    putString("test", case.name)
                    putInt("numtests", cases.size)
                    putInt("current", index + 1)
                }
                sendStatus(1, status)
                try {
                    withTimeout(30_000) { case.body() }
                    check(File(targetContext.filesDir, "image-imports").listFiles().orEmpty().isEmpty())
                    sendStatus(0, status)
                } catch (failure: Throwable) {
                    failures++
                    status.putString("stack", Log.getStackTraceString(failure))
                    sendStatus(-2, status)
                }
            }
        } finally {
            fixtures.deleteRecursively()
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n${cases.size} tests; $failures failures.\n")
        })
    }

    private fun providerUri(name: String): Uri =
        Uri.parse("content://com.tierruos.stickers.test.sanitizer-inputs/$name")

    private fun resetProvider() {
        targetContext.contentResolver.call(providerUri("swap"), "reset", null, null)
    }

    private fun providerOpens(): Int = requireNotNull(
        targetContext.contentResolver.call(providerUri("swap"), "count", null, null),
    ).getInt("opens")

    private fun fixture(bytes: ByteArray): Uri {
        val file = File.createTempFile("fixture-", ".jpg", fixtures)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun verify(result: ImageSanitizerAndCompressor.CompressedImage, edge: Int, limit: Int) {
        check(result.bytes.size in 1 until limit)
        check(result.sizeInKb == result.bytes.size / 1_000.0)
        check(result.quality in 0..90 && result.quality % 10 == 0)
        check(result.width == edge && result.height == edge)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size, bounds)
        check(bounds.outMimeType == "image/webp" && bounds.outWidth == edge && bounds.outHeight == edge)
        var offset = 12
        val permitted = setOf("VP8 ", "VP8L", "VP8X", "ALPH")
        while (offset < result.bytes.size) {
            check(String(result.bytes, offset, 4, Charsets.US_ASCII) in permitted)
            var length = 0L
            repeat(4) { length = length or ((result.bytes[offset + 4 + it].toLong() and 255) shl (it * 8)) }
            offset += (8L + length + (length and 1L)).toInt()
        }
        check(offset == result.bytes.size)
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        error("Expected ${T::class.java.simpleName}")
    }
}
