package com.tierruos.stickers.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Private debug-only provider; deliberately lies about names, MIME types and sizes. */
class FixtureImageProvider : ContentProvider() {
    private val opens = AtomicInteger()
    private val validPng by lazy { ImageFixtures.solidImage() }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.SIZE)
        val size: Long? = when (uri.lastPathSegment) {
            "known-large" -> 15_000_001L
            "unknown-large", "slow" -> null
            else -> validPng.size.toLong()
        }
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) size else "document.jpg" })
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only test fixtures.")
        val count = opens.incrementAndGet()
        return when (uri.lastPathSegment) {
            "denied" -> throw SecurityException("Access denied by the test provider.")
            "known-large" -> throw FileNotFoundException("Oversized metadata must prevent opening.")
            "unknown-large", "slow" -> {
                val pipe = ParcelFileDescriptor.createPipe()
                Thread({
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var remaining = 15_000_001
                            while (remaining > 0) {
                                val length = minOf(buffer.size, remaining)
                                output.write(buffer, 0, length)
                                remaining -= length
                                if (uri.lastPathSegment == "slow") Thread.sleep(10)
                            }
                        }
                    } catch (_: IOException) {
                        // Expected when the importer rejects or cancels and closes its pipe.
                    }
                }, "sanitizer-test-input").start()
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            "spoofed", "swap" -> {
                val bytes = if (uri.lastPathSegment == "swap" && count > 1) {
                    ByteArray(12)
                } else {
                    validPng
                }
                val file = File.createTempFile("generated-", ".bin", requireNotNull(context).cacheDir)
                try {
                    file.writeBytes(bytes)
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    AssetFileDescriptor(descriptor, 0, bytes.size.toLong())
                } finally {
                    file.delete()
                }
            }
            else -> throw FileNotFoundException("Unknown test fixture.")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        if (method == "reset") opens.set(0)
        putInt("opens", opens.get())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        throw FileNotFoundException("Use openAssetFile for generated test fixtures.")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only.")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Read only.")
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Read only.")
}
