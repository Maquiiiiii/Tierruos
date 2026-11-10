package com.tierruos.stickers.security

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class InvalidImageFormatException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class ImageTooLargeException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class ImageCompressionException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Imports still images without carrying their original container or metadata forward.
 *
 * The URI is opened once. All parsing and decoding uses a bounded private snapshot,
 * so a provider cannot swap the file between the bounds pass and the pixel pass.
 * Native decoding still happens before sanitization: this is not a decoder sandbox
 * or a guarantee against native codec vulnerabilities or pixel steganography.
 */
class ImageSanitizerAndCompressor(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val stagingDirectory = File(appContext.filesDir, "image-imports")

    class CompressedImage internal constructor(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int,
    ) {
        val sizeBytes: Int get() = bytes.size

        /** Decimal KB; the same unit used by this class's strict byte budgets. */
        val sizeInKb: Double get() = bytes.size / 1_000.0
    }

    /**
     * Returns a static 512 x 512 WebP strictly smaller than [STICKER_MAX_BYTES].
     * @throws SecurityException If URI access is denied or its scheme is unsupported.
     * @throws InvalidImageFormatException If its signature, bounds or pixels are invalid.
     * @throws ImageTooLargeException If input, dimensions, memory or output exceed limits.
     * @throws ImageCompressionException If the platform encoder fails validation.
     * @throws IOException If input or private storage cannot be read/written.
     */
    suspend fun createSticker(uri: Uri): CompressedImage = process(uri, 512, STICKER_MAX_BYTES)

    /** Same security pipeline, with a 96 x 96 canvas and [TRAY_ICON_MAX_BYTES] budget. */
    suspend fun createTrayIcon(uri: Uri): CompressedImage =
        process(uri, 96, TRAY_ICON_MAX_BYTES)

    /** Only for software pixels already owned by StickerCanvasView, never external URI input. */
    suspend fun compressStickerBitmap(bitmap: Bitmap): CompressedImage = encodeEditorBitmap(bitmap, 512, STICKER_MAX_BYTES)

    suspend fun compressTrayBitmap(bitmap: Bitmap): CompressedImage = encodeEditorBitmap(bitmap, 96, TRAY_ICON_MAX_BYTES)

    private suspend fun encodeEditorBitmap(source: Bitmap, edge: Int, limit: Int): CompressedImage = withContext(Dispatchers.IO) {
        PROCESS_MUTEX.withLock {
            require(!source.isRecycled && source.width in 1..1024 && source.height in 1..1024)
            if (Build.VERSION.SDK_INT >= 26) require(source.config != Bitmap.Config.HARDWARE)
            val job = currentCoroutineContext()
            job.ensureActive()
            try {
                val clean = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                try {
                    clean.eraseColor(Color.TRANSPARENT)
                    val scale = min(edge.toFloat() / source.width, edge.toFloat() / source.height)
                    val left = (edge - source.width * scale) / 2f
                    val top = (edge - source.height * scale) / 2f
                    Canvas(clean).drawBitmap(source, null, RectF(left, top, edge - left, edge - top),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    compress(clean, limit, job)
                } finally { clean.recycle() }
            } catch (error: OutOfMemoryError) {
                throw ImageTooLargeException("Memoria insuficiente para comprimir el sticker.", error)
            }
        }
    }

    private suspend fun process(uri: Uri, edge: Int, byteLimit: Int): CompressedImage =
        withContext(Dispatchers.IO) {
            // Shared by all instances: callers cannot multiply bitmap allocations.
            PROCESS_MUTEX.withLock {
                val jobContext = currentCoroutineContext()
                jobContext.ensureActive()
                try {
                    requireSupportedUri(uri)
                    checkReportedSize(uri)
                    newSnapshot().use { snapshot ->
                        copyBounded(uri, snapshot.file, jobContext)
                        val format = readSignature(snapshot.file)
                        val bounds = readBounds(snapshot.file, format)
                        jobContext.ensureActive()
                        val bitmap = decodeOntoCleanCanvas(snapshot.file, bounds, edge, jobContext)
                        try {
                            compress(bitmap, byteLimit, jobContext)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } catch (error: OutOfMemoryError) {
                    // Best effort for heap pressure outside the predictable bitmap budget.
                    // Bitmap and stream finally blocks run before converting this error.
                    throw ImageTooLargeException("Memoria insuficiente para procesar la imagen.", error)
                }
            }
        }

    private fun requireSupportedUri(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_FILE) {
            throw SecurityException("Solo se admiten URI locales content:// o file://.")
        }
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            // Local files must belong to this app; arbitrary readable paths are not an input API.
            val path = File(uri.path ?: throw SecurityException("La URI local no tiene una ruta válida."))
                .canonicalPath
            val allowed = listOf(appContext.filesDir, appContext.cacheDir).any { directory ->
                path.startsWith(directory.canonicalPath + File.separator)
            }
            if (!allowed) throw SecurityException("La URI local debe pertenecer al almacenamiento privado de la app.")
        }
    }

    private fun checkReportedSize(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column) &&
                    cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER
                ) {
                    requireInputSize(cursor.getLong(column))
                }
            }
        } catch (_: UnsupportedOperationException) {
            // A provider need not implement size queries. Counting bytes remains mandatory.
        } catch (_: IllegalArgumentException) {
            // Unsupported metadata columns are not grounds to trust or reject the bytes.
        }
    }

    private fun newSnapshot(): PrivateSnapshot {
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IOException("No se pudo preparar el almacenamiento privado de importación.")
        }
        // Recover temporary files left by process death, while holding the process-wide lock.
        val staleFiles = stagingDirectory.listFiles { _, name ->
            name.startsWith("input-") && name.endsWith(".tmp")
        } ?: throw IOException("No se pudo consultar el almacenamiento privado de importación.")
        staleFiles.forEach { stale ->
            if (!stale.delete()) throw IOException("No se pudo eliminar una importación temporal.")
        }
        return PrivateSnapshot(File.createTempFile("input-", ".tmp", stagingDirectory))
    }

    private fun copyBounded(uri: Uri, destination: File, jobContext: CoroutineContext) {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("No se pudo abrir la imagen seleccionada.")
        descriptor.use { asset ->
            requireInputSize(asset.length)
            asset.createInputStream().use { input ->
                destination.outputStream().buffered(STREAM_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        jobContext.ensureActive()
                        // Inspect at most one byte past the budget, including unknown-size pipes.
                        val requested = min(buffer.size.toLong(), MAX_INPUT_BYTES - total + 1).toInt()
                        val count = input.read(buffer, 0, requested)
                        if (count == -1) break
                        if (count == 0) {
                            val next = input.read()
                            if (next == -1) break
                            requireInputSize(++total)
                            output.write(next)
                        } else {
                            total += count
                            requireInputSize(total)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        jobContext.ensureActive()
    }

    private fun requireInputSize(size: Long) {
        if (size > MAX_INPUT_BYTES) {
            throw ImageTooLargeException("La imagen de entrada supera los 15 MB.")
        }
    }

    private enum class ImageFormat(val mime: String) {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"),
    }

    private fun readSignature(file: File): ImageFormat {
        if (file.length() < 12) throw InvalidImageFormatException("La cabecera está incompleta.")
        val header = ByteArray(12)
        DataInputStream(file.inputStream()).use { it.readFully(header) }
        return when {
            header.matches(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
            header.matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
            header.matches(0, 0x52, 0x49, 0x46, 0x46) &&
                header.matches(8, 0x57, 0x45, 0x42, 0x50) -> {
                if (header.unsignedIntAt(4) + 8L != file.length()) {
                    throw InvalidImageFormatException("El tamaño del contenedor WebP es inválido.")
                }
                ImageFormat.WEBP
            }
            else -> throw InvalidImageFormatException("Solo se admiten imágenes JPEG, PNG o WebP válidas.")
        }
    }

    private data class ImageBounds(val width: Int, val height: Int, val sampleSize: Int)

    private fun readBounds(file: File, format: ImageFormat): ImageBounds {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: IllegalArgumentException) {
            throw InvalidImageFormatException("No se pudieron leer las dimensiones de la imagen.", error)
        }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || options.outMimeType != format.mime) {
            throw InvalidImageFormatException("La cabecera y las dimensiones no describen una imagen válida.")
        }
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) {
            throw ImageTooLargeException("Ninguna dimensión de la imagen puede superar los 8000 píxeles.")
        }
        var sample = 1
        val largest = max(width, height)
        while ((largest.toLong() + sample - 1) / sample > MAX_DECODE_DIMENSION) sample *= 2
        return ImageBounds(width, height, sample)
    }

    private fun decodeOntoCleanCanvas(
        file: File,
        bounds: ImageBounds,
        edge: Int,
        jobContext: CoroutineContext,
    ): Bitmap {
        val sampledWidth = (bounds.width.toLong() + bounds.sampleSize - 1) / bounds.sampleSize
        val sampledHeight = (bounds.height.toLong() + bounds.sampleSize - 1) / bounds.sampleSize
        if (sampledWidth * sampledHeight * 4L > MAX_DECODE_BYTES) {
            throw ImageTooLargeException("La imagen muestreada excede el presupuesto de memoria.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = bounds.sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
            inDensity = 0
            inTargetDensity = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw InvalidImageFormatException("La imagen no se pudo decodificar.")
        } catch (error: IllegalArgumentException) {
            throw InvalidImageFormatException("Los datos de píxeles de la imagen son inválidos.", error)
        }
        var clean: Bitmap? = null
        var returned = false
        try {
            jobContext.ensureActive()
            if (decoded.width > MAX_DECODE_DIMENSION || decoded.height > MAX_DECODE_DIMENSION ||
                decoded.allocationByteCount.toLong() > MAX_DECODE_BYTES
            ) {
                throw ImageTooLargeException("El decodificador excedió el presupuesto de píxeles permitido.")
            }
            if (decoded.config != Bitmap.Config.ARGB_8888) {
                throw InvalidImageFormatException("El decodificador no produjo píxeles ARGB_8888.")
            }
            // One fresh canvas both destroys the source container and normalizes the pixels.
            // No EXIF parser, source chunks, original ByteArray or Bitmap metadata is reused.
            val target = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            clean = target
            target.density = Bitmap.DENSITY_NONE
            target.setHasAlpha(true)
            target.eraseColor(Color.TRANSPARENT)
            val scale = min(edge.toFloat() / decoded.width, edge.toFloat() / decoded.height)
            val width = decoded.width * scale
            val height = decoded.height * scale
            val left = (edge - width) / 2f
            val top = (edge - height) / 2f
            val canvas = Canvas(target)
            try {
                canvas.drawBitmap(
                    decoded,
                    null,
                    RectF(left, top, left + width, top + height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            } finally {
                canvas.setBitmap(null)
            }
            jobContext.ensureActive()
            returned = true
            return target
        } finally {
            decoded.recycle()
            if (!returned) clean?.recycle()
        }
    }

    @Suppress("DEPRECATION") // WEBP is required on supported Android 24–29 devices.
    private fun compress(bitmap: Bitmap, byteLimit: Int, jobContext: CoroutineContext): CompressedImage {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val output = BoundedOutput(byteLimit)
        for (quality in 90 downTo 0 step 10) {
            jobContext.ensureActive()
            output.reset()
            val success = bitmap.compress(format, quality, output)
            jobContext.ensureActive()
            if (output.exceeded) continue
            if (!success || output.size == 0) {
                throw ImageCompressionException("El codificador WebP no pudo generar la imagen.")
            }
            val bytes = stripEncoderMetadata(output.toByteArray())
            verifyEncodedWebp(bytes, bitmap.width, bitmap.height)
            return CompressedImage(bytes, bitmap.width, bitmap.height, quality)
        }
        // Lossy WebP keeps alpha lossless: lowering color quality cannot always fit it.
        throw ImageTooLargeException("La imagen no cabe en el límite de salida ni con calidad 0.")
    }

    /** Some platform encoders add their own ICC profile even to a fresh sRGB bitmap. */
    private fun stripEncoderMetadata(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || !bytes.matches(0, 0x52, 0x49, 0x46, 0x46) ||
            !bytes.matches(8, 0x57, 0x45, 0x42, 0x50) ||
            bytes.unsignedIntAt(4) + 8L != bytes.size.toLong()
        ) throw ImageCompressionException("El codificador produjo un contenedor WebP inválido.")
        var read = 12
        var write = 12
        while (read < bytes.size) {
            if (bytes.size - read < 8) throw ImageCompressionException("Chunk WebP incompleto.")
            val length = bytes.unsignedIntAt(read + 4)
            val next = read.toLong() + 8L + length + (length and 1L)
            if (next > bytes.size) throw ImageCompressionException("Tamaño de chunk WebP inválido.")
            val chunk = String(bytes, read, 4, Charsets.US_ASCII)
            if (chunk != "ICCP" && chunk != "EXIF" && chunk != "XMP ") {
                val size = next.toInt() - read
                bytes.copyInto(bytes, write, read, next.toInt())
                if (chunk == "VP8X") {
                    if (length != 10L || (bytes[write + 8].toInt() and 0x02) != 0) {
                        throw ImageCompressionException("La salida WebP no es una imagen estática válida.")
                    }
                    // Retain alpha only; the associated metadata chunks are removed below/above.
                    bytes[write + 8] = (bytes[write + 8].toInt() and 0x10).toByte()
                }
                write += size
            }
            read = next.toInt()
        }
        val riffLength = write - 8
        repeat(4) { bytes[4 + it] = (riffLength ushr (it * 8)).toByte() }
        return if (write == bytes.size) bytes else bytes.copyOf(write)
    }

    private fun verifyEncodedWebp(bytes: ByteArray, width: Int, height: Int) {
        if (bytes.size < 12 || !bytes.matches(0, 0x52, 0x49, 0x46, 0x46) ||
            !bytes.matches(8, 0x57, 0x45, 0x42, 0x50) ||
            bytes.unsignedIntAt(4) + 8L != bytes.size.toLong()
        ) {
            throw ImageCompressionException("El codificador produjo un contenedor WebP inválido.")
        }
        var offset = 12
        var imageChunks = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < 8) throw ImageCompressionException("Chunk WebP incompleto.")
            val size = bytes.unsignedIntAt(offset + 4)
            val next = offset.toLong() + 8L + size + (size and 1L)
            if (next > bytes.size) throw ImageCompressionException("Tamaño de chunk WebP inválido.")
            when (String(bytes, offset, 4, Charsets.US_ASCII)) {
                "VP8 ", "VP8L" -> imageChunks++
                "VP8X", "ALPH" -> Unit
                else -> throw ImageCompressionException("La salida WebP contiene un chunk ajeno a los píxeles.")
            }
            offset = next.toInt()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (imageChunks != 1 || options.outWidth != width || options.outHeight != height) {
            throw ImageCompressionException("Las dimensiones de la salida WebP son inválidas.")
        }
    }

    private class PrivateSnapshot(val file: File) : Closeable {
        override fun close() {
            if (file.exists() && !file.delete()) {
                throw IOException("No se pudo eliminar el archivo privado temporal.")
            }
        }
    }

    /** Fixed-capacity sink: even an oversized encoding cannot grow a heap buffer. */
    private class BoundedOutput(exclusiveLimit: Int) : OutputStream() {
        private val buffer = ByteArray(exclusiveLimit - 1)
        var size: Int = 0
            private set
        var exceeded: Boolean = false
            private set

        fun reset() {
            size = 0
            exceeded = false
        }

        override fun write(value: Int) {
            if (exceeded) return
            if (size == buffer.size) {
                exceeded = true
            } else {
                buffer[size++] = value.toByte()
            }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (exceeded) return
            if (length > buffer.size - size) {
                // Do not throw through a native encoder, which may swallow stream errors.
                exceeded = true
            } else {
                bytes.copyInto(buffer, size, offset, offset + length)
                size += length
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    companion object {
        const val MAX_INPUT_BYTES: Long = 15_000_000L
        const val STICKER_MAX_BYTES: Int = 98_000
        const val TRAY_ICON_MAX_BYTES: Int = 48_000
        private const val STREAM_BUFFER_BYTES = 16 * 1024
        private const val MAX_SOURCE_DIMENSION = 8000
        private const val MAX_DECODE_DIMENSION = 1024
        private const val MAX_DECODE_BYTES = 1024L * 1024 * 4
        private val PROCESS_MUTEX = Mutex()

        private fun ByteArray.matches(offset: Int, vararg signature: Int): Boolean =
            signature.indices.all { (this[offset + it].toInt() and 0xFF) == signature[it] }

        private fun ByteArray.unsignedIntAt(offset: Int): Long =
            (this[offset].toLong() and 0xFF) or
                ((this[offset + 1].toLong() and 0xFF) shl 8) or
                ((this[offset + 2].toLong() and 0xFF) shl 16) or
                ((this[offset + 3].toLong() and 0xFF) shl 24)
    }
}
