package com.tierruos.stickers.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import androidx.core.content.FileProvider
import com.tierruos.stickers.R
import java.io.File
import java.io.IOException

/** Owns only disposable search images; never exposes sticker files or the update cache. */
internal class ImageSearchShare(private val context: Context) {
    private val directory get() = File(context.cacheDir, "image-search")

    /** Consumes pixels of an independently owned export, never the live canvas. Call on IO. */
    fun create(bitmap: Bitmap): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo preparar la búsqueda.")
        val file = File.createTempFile("search-", ".png", directory)
        try {
            val canvas = Canvas(bitmap)
            try { canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER) }
            finally { canvas.setBitmap(null) }
            file.outputStream().buffered().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("No se pudo exportar la búsqueda.")
            }
            return file.name
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    fun uri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file(name))

    fun release(name: String) {
        context.revokeUriPermission(uri(name), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        file(name).delete()
    }

    /** Recover orphaned files after process death, without touching another active search. */
    fun prune() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles().orEmpty().filter { it.name.matches(NAME) && it.lastModified() < cutoff }
            .forEach { release(it.name) }
    }

    private fun file(name: String): File {
        require(name.matches(NAME))
        return File(directory, name)
    }

    fun launch(uri: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("image", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.queryIntentActivities(send, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        return dispatch(send, receivers, context.packageName, context.getString(R.string.search_image_chooser), context::startActivity)
    }

    companion object {
        private val NAME = Regex("search-[a-zA-Z0-9_-]+\\.png")
        internal val GOOGLE_PACKAGES = listOf("com.google.ar.lens", "com.google.android.googlequicksearchbox")

        /** Separate dispatch from Android resolution so absent/disabled apps can be tested. */
        internal fun dispatch(send: Intent, receivers: List<ComponentName>, ownPackage: String,
            title: String, start: (Intent) -> Unit): Boolean {
            fun attempt(intent: Intent): Boolean = try { start(intent); true }
                catch (_: ActivityNotFoundException) { false }
                catch (_: SecurityException) { false }
            for (google in GOOGLE_PACKAGES) {
                if (attempt(Intent(send).setPackage(google))) return true
            }
            if (receivers.none { it.packageName != ownPackage }) return false
            val chooser = Intent.createChooser(send, title).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                receivers.filter { it.packageName == ownPackage }.toTypedArray())
            return attempt(chooser)
        }
    }
}
