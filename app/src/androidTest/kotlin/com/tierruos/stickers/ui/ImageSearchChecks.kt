package com.tierruos.stickers.ui

import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ClipboardManager
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Native instrumentation, with no new test or production dependencies. */
internal class ImageSearchChecks(private val instrumentation: Instrumentation) {
    data class Check(val name: String, val body: suspend () -> Unit)
    private val app get() = instrumentation.targetContext

    fun cases(): List<Check> = listOf(
        Check("sharedImageRejectsMalformedExtrasAndNonLocalInput") {
            val send = Intent(Intent.ACTION_SEND).setType("image/png")
            val uri = Uri.parse("content://example/image")
            check(MainActivity.sharedImageUri(Intent(send).putExtra(Intent.EXTRA_STREAM, uri)) == uri)
            check(MainActivity.sharedImageUri(send) == null)
            check(MainActivity.sharedImageUri(Intent(send).putExtra(Intent.EXTRA_STREAM, "wrong type")) == null)
            for (invalid in listOf("https://example/image", "file:///etc/passwd", "content:///missing-authority")) {
                check(MainActivity.sharedImageUri(Intent(send).putExtra(Intent.EXTRA_STREAM, Uri.parse(invalid))) == null)
            }
            check(MainActivity.sharedImageUri(Intent(send).setAction(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, uri)) == null)
            check(MainActivity.sharedImageUri(Intent(send).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)) == null)
        },
        Check("sharedImageRoutingKeepsOnlyReadGrantAndNoPackExtras") {
            val uri = Uri.parse("content://example/image")
            val routed = MainActivity.sharedEditorIntent(app, uri)
            check(routed.component?.className == StickerEditorActivity::class.java.name)
            check(routed.getStringExtra(StickerEditorActivity.EXTRA_IMAGE_URI) == uri.toString())
            check(routed.clipData?.getItemAt(0)?.uri == uri)
            check(routed.flags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(!routed.hasExtra(StickerEditorActivity.EXTRA_PACK_ID))
            @Suppress("DEPRECATION")
            val info = app.packageManager.getActivityInfo(ComponentName(app, StickerEditorActivity::class.java), 0)
            check(!info.exported)
        },
        Check("sharedImageLaunchesPrivateEditorThroughSanitizer") {
            val uri = Uri.parse("content://com.tierruos.stickers.test.sanitizer-inputs/spoofed")
            val monitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
            var editor: StickerEditorActivity? = null
            val main = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(StickerEditorActivity.EXTRA_PACK_ID, "untrusted-pack")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                editor = instrumentation.waitForMonitorWithTimeout(monitor, 8_000) as? StickerEditorActivity
                val activity = requireNotNull(editor)
                withContext(Dispatchers.Main) {
                    withTimeout(8_000) {
                        while (!activity.binding.stickerCanvas.hasImage || activity.binding.loading.root.visibility == View.VISIBLE) delay(16)
                    }
                    check(activity.binding.saveButton.isEnabled)
                    check(!activity.intent.hasExtra(StickerEditorActivity.EXTRA_PACK_ID))
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                withContext(Dispatchers.Main) { editor?.finish(); main.finish() }
            }
        },
        Check("searchPngIsWhiteAndOnlyCacheSubdirectoryIsExposed") {
            val search = ImageSearchShare(app)
            val pixels = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            var name: String? = null
            try {
                pixels.eraseColor(Color.TRANSPARENT)
                pixels.setPixel(256, 256, Color.RED)
                name = search.create(pixels)
                val uri = search.uri(name)
                check(uri.scheme == "content" && uri.authority == "${app.packageName}.fileprovider")
                app.contentResolver.openInputStream(uri).use { stream ->
                    val decoded = requireNotNull(BitmapFactory.decodeStream(stream))
                    try {
                        check(decoded.width == 512 && decoded.height == 512)
                        check(decoded.getPixel(0, 0) == Color.WHITE)
                        check(decoded.getPixel(256, 256) == Color.RED)
                    } finally { decoded.recycle() }
                }
                val privateFile = File(app.filesDir, "not-shared.png")
                try {
                    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", privateFile)
                    error("Private storage must not be exposed")
                } catch (_: IllegalArgumentException) { }
                search.release(name)
                check(!File(app.cacheDir, "image-search/$name").exists())
            } finally { name?.let(search::release); pixels.recycle() }
        },
        Check("searchDispatchTriesLensThenGoogleThenExcludesSelf") {
            val own = ComponentName(app, MainActivity::class.java)
            val other = ComponentName("example.search", "example.search.Receiver")
            val send = Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/image"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val attempts = mutableListOf<Intent>()
            check(ImageSearchShare.dispatch(send, listOf(own, other), app.packageName, "Search") {
                attempts.add(it)
                if (it.action != Intent.ACTION_CHOOSER) throw ActivityNotFoundException()
            })
            check(attempts.map { it.`package` } == ImageSearchShare.GOOGLE_PACKAGES + listOf(null))
            val chooser = attempts.last()
            check(IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)?.toList() == listOf(own))
            val target = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
            check(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            check(target.type == "image/png")
        },
        Check("searchDispatchStopsAtFirstAvailableGoogleApp") {
            val attempts = mutableListOf<String?>()
            check(ImageSearchShare.dispatch(Intent(Intent.ACTION_SEND), emptyList(), app.packageName, "Search") {
                attempts.add(it.`package`)
                if (it.`package` == ImageSearchShare.GOOGLE_PACKAGES.first()) throw ActivityNotFoundException()
            })
            check(attempts == ImageSearchShare.GOOGLE_PACKAGES)
        },
        Check("searchDispatchHandlesNoReceiversAndSecurityFailure") {
            check(!ImageSearchShare.dispatch(Intent(Intent.ACTION_SEND), listOf(ComponentName(app, MainActivity::class.java)),
                app.packageName, "Search") { throw ActivityNotFoundException() })
            check(!ImageSearchShare.dispatch(Intent(Intent.ACTION_SEND), listOf(ComponentName("other", "other.Receiver")),
                app.packageName, "Search") { throw SecurityException() })
        },
        Check("searchClipboardIgnoresOldImageAndIdentifiesFirstWebLink") {
            val uri = Uri.parse("content://example/new")
            val clip = ClipData.newRawUri("image", uri)
            val result = ClipboardImages.searchResult(clip)
            check(!ClipboardImages.isNewImage(result, uri.toString()))
            check(ClipboardImages.isNewImage(result, "content://example/old"))
            check(ClipboardImages.isNewImage(result, null))
            check(!ClipboardImages.isNewImage(ClipboardImages.searchResult(null), null))
            for (url in listOf("http://example/image", "https://example/image")) {
                val web = ClipData.newPlainText("link", url).apply { addItem(ClipData.Item(uri)) }
                check(ClipboardImages.searchResult(web).webLink)
                check(ClipboardImages.searchResult(web).uri == null)
            }
        },
        Check("searchReturnConserveKeepsEditsAndDoesNotPromptAgain") {
            withEditedCanvas { activity ->
                val version = withContext(Dispatchers.Main) { activity.binding.stickerCanvas.documentVersion }
                simulateReturn(activity, validImage())
                val modal = awaitSearchDialog(activity)
                withContext(Dispatchers.Main) { modal.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                withContext(Dispatchers.Main) {
                    withTimeout(3_000) { while (searchDialog(activity) != null) delay(16) }
                    activity.onWindowFocusChanged(true)
                    check(activity.binding.stickerCanvas.documentVersion == version)
                    check(activity.binding.stickerCanvas.textLayer == "Conservar")
                    check(activity.binding.stickerCanvas.canUndo)
                    check(searchDialog(activity) == null)
                }
            }
        },
        Check("searchReturnReplaceResetsEditsOnlyAfterAcceptance") {
            withEditedCanvas { activity ->
                simulateReturn(activity, validImage())
                val modal = awaitSearchDialog(activity)
                withContext(Dispatchers.Main) {
                    check(activity.binding.stickerCanvas.textLayer == "Conservar")
                    modal.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                awaitReady(activity)
                withContext(Dispatchers.Main) {
                    check(activity.binding.stickerCanvas.textLayer.isEmpty())
                    check(!activity.binding.stickerCanvas.canUndo)
                    check(activity.binding.saveButton.isEnabled)
                }
            }
        },
        Check("searchReturnRejectsDeniedImageAndOrdinaryFocusNeverPrompts") {
            withEditedCanvas { activity ->
                withContext(Dispatchers.Main) {
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newRawUri("image", validImage()))
                    activity.onWindowFocusChanged(true)
                    check(searchDialog(activity) == null)
                }
                simulateReturn(activity, Uri.parse("content://com.tierruos.stickers.test.sanitizer-inputs/denied"))
                awaitReady(activity)
                withContext(Dispatchers.Main) {
                    check(searchDialog(activity) == null)
                    check(activity.binding.stickerCanvas.textLayer == "Conservar")
                    check(activity.binding.stickerCanvas.canUndo)
                }
            }
        },
    )

    private fun validImage() = Uri.parse("content://com.tierruos.stickers.test.sanitizer-inputs/spoofed")

    private fun searchDialog(activity: StickerEditorActivity): AlertDialog? =
        StickerEditorActivity::class.java.getDeclaredField("searchDialog").apply { isAccessible = true }.get(activity) as? AlertDialog

    // Simulate only external navigation state; real clipboard access, validation and dialog run unchanged.
    private suspend fun simulateReturn(activity: StickerEditorActivity, uri: Uri) = withContext(Dispatchers.Main) {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        // Text-form local URIs also use the existing pipeline and survive host/emulator clipboard sync.
        clipboard.setPrimaryClip(ClipData.newPlainText("image", uri.toString()))
        for (field in listOf("searchPending", "searchDeparted")) {
            StickerEditorActivity::class.java.getDeclaredField(field).apply { isAccessible = true }.setBoolean(activity, true)
        }
        activity.onWindowFocusChanged(true)
    }

    private suspend fun awaitSearchDialog(activity: StickerEditorActivity): AlertDialog = withContext(Dispatchers.Main) {
        try { withTimeout(8_000) { while (searchDialog(activity) == null) delay(16) } }
        catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val clip = ClipboardImages(activity).searchResult()
            error("No dialog: focus=${activity.hasWindowFocus()}, clipboard=$clip, busy=${activity.binding.loading.root.visibility}, text=${activity.binding.root.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)?.text}")
        }
        requireNotNull(searchDialog(activity))
    }

    private suspend fun awaitReady(activity: StickerEditorActivity) = withContext(Dispatchers.Main) {
        withTimeout(8_000) {
            while (activity.binding.loading.root.visibility == View.VISIBLE || !activity.binding.stickerCanvas.hasImage) delay(16)
        }
    }

    private suspend fun withEditedCanvas(body: suspend (StickerEditorActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(app, StickerEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StickerEditorActivity.EXTRA_IMAGE_URI, validImage().toString())) as StickerEditorActivity
        try {
            awaitReady(activity)
            withContext(Dispatchers.Main) {
                withTimeout(8_000) { while (!activity.hasWindowFocus()) delay(16) }
                activity.binding.stickerCanvas.setTextLayer("Conservar")
            }
            // Let the existing debounce and compression finish before emulating the return.
            delay(300)
            awaitReady(activity)
            body(activity)
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }
}
