package com.tierruos.stickers.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.tierruos.stickers.R
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.databinding.ItemStickerPackBinding
import com.tierruos.stickers.security.ImageSanitizerAndCompressor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class PresentationChecks(private val instrumentation: Instrumentation) {
    data class Check(val name: String, val body: suspend () -> Unit)
    private val app get() = instrumentation.targetContext
    private val repository get() = TierruosStorage.get(app).repository
    private val engine get() = ImageSanitizerAndCompressor(app)
    private var assets: List<ByteArray>? = null
    private var tray: ByteArray? = null

    fun cases(): List<Check> = listOf(
        Check("presentationClipboardAcceptsLocalUriWithoutTrustingMime") {
            val uri = Uri.parse("content://untrusted.example/pixels")
            check(ClipboardImages.extract(ClipData("image", arrayOf("image/png"), ClipData.Item(uri))) == uri)
            check(ClipboardImages.extract(ClipData("unknown", arrayOf("application/octet-stream"), ClipData.Item(uri))) == uri)
            check(ClipboardImages.extract(ClipData.newPlainText("uri", uri.toString())) == uri)
            check(ClipboardImages.extract(ClipData.newPlainText("url", "https://example.com/image.png")) == null)
            check(ClipboardImages.extract(ClipData.newPlainText("text", "Hola")) == null)
            check(ClipboardImages.extract(ClipData("image", arrayOf("image/png"), ClipData.Item("no URI"))) == null)
        },
        Check("presentationEditorBitmapEncoderPreservesTransparentCanvasAndTray") {
            val bitmap = artwork(0)
            try {
                val sticker = engine.compressStickerBitmap(bitmap)
                val trayIcon = engine.compressTrayBitmap(bitmap)
                verify(sticker.bytes, 512, 98_000); verify(trayIcon.bytes, 96, 48_000)
                val pixels = BitmapFactory.decodeByteArray(sticker.bytes, 0, sticker.sizeBytes)
                try { check(Color.alpha(pixels.getPixel(0, 0)) == 0) } finally { pixels.recycle() }
            } finally { bitmap.recycle() }
        },
        Check("presentationFirstStickerAndRealTrayPublishTogether") { withPack(1) { id ->
            val draft = requireNotNull(repository.getDraft(id))
            check(draft.stickers.size == 1 && draft.imageDataVersion == 1L)
            verify(repository.readTrayImage(id), 96, 48_000)
            verify(repository.readSticker(id, "s0.webp"), 512, 98_000)
            val files = File(app.filesDir, "stickers/$id").listFiles()!!
            check(files.size == 2 && files.all { it.extension == "webp" })
        } },
        Check("presentationMainShowsRealThumbnailsAndOneExportCta") { withPack(3) { id ->
            withMain { activity ->
                waitMain(activity)
                withContext(Dispatchers.Main) {
                    val row = ItemStickerPackBinding.bind(activity.binding.packList.getChildAt(0))
                    check(row.packName.text == "Entre panas" && row.publisher.text == "Tierruos")
                    check(row.packProgress.text.toString().contains("3/30") && activity.binding.exportButton.isEnabled)
                    check(row.stickerGrid.childCount == 3)
                    val available = activity.binding.root.height - activity.binding.root.paddingTop - activity.binding.root.paddingBottom
                    check(activity.binding.packList.height == available)
                    check((activity.binding.packList.height - activity.binding.packList.paddingTop - activity.binding.packList.paddingBottom).toFloat() / available > 0.75f)
                    check((activity.binding.packList.adapter.getItem(0) as com.tierruos.stickers.data.model.StickerPackDraft).identifier == id)
                }
                delay(350)
                capture("main-module6.png")
            }
        } },
        Check("presentationMainProgressChangesFromMissingToReady") { withPack(1) { id ->
            withMain { activity ->
                waitMain(activity)
                withContext(Dispatchers.Main) {
                    val row = ItemStickerPackBinding.bind(activity.binding.packList.getChildAt(0))
                    check(row.packProgress.text.toString().contains("Faltan 2") && row.packProgress.text.toString().contains("1/30"))
                    check(!activity.binding.exportButton.isEnabled)
                }
                repository.addSticker(id, images()[1], imageFileName = "s1.webp")
                repository.addSticker(id, images()[2], imageFileName = "s2.webp")
                withContext(Dispatchers.Main) { activity.reload() }
                waitMain(activity)
                withContext(Dispatchers.Main) { check(activity.binding.exportButton.isEnabled) }
            }
        } },
        Check("presentationMainClipboardButtonFollowsFocusedClip") { withMain { activity ->
            waitMain(activity)
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            try {
                withContext(Dispatchers.Main) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("text", "Hola"))
                    activity.onWindowFocusChanged(true)
                    check(activity.binding.pasteImage.visibility == View.GONE)
                    clipboard.setPrimaryClip(imageClip())
                    activity.onWindowFocusChanged(true)
                    check(activity.binding.pasteImage.visibility == View.VISIBLE)
                }
                delay(250)
                withContext(Dispatchers.Main) { check(activity.binding.appTitle.layout.lineCount == 1) }
                capture("main-clipboard-module6.png")
            } finally { withContext(Dispatchers.Main) { clearClipboard(clipboard) } }
        } },
        Check("presentationEditorSanitizesUriBeforeShowingBitmap") { withEditor { activity ->
            withContext(Dispatchers.Main) {
                activity.importUri(providerUri("spoofed"))
                check(activity.binding.loading.root.visibility == View.VISIBLE)
                check(!activity.binding.saveButton.isEnabled && !activity.binding.chooseTool.isEnabled)
                check(activity.binding.loading.message.text.isNotBlank())
            }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) {
                val pixels = activity.binding.stickerCanvas.exportFinalBitmap()
                try { check(pixels.width == 512 && pixels.height == 512 && Color.alpha(pixels.getPixel(256, 0)) == 0) }
                finally { pixels.recycle() }
                check(activity.binding.fileWeight.text.toString().endsWith("KB / 98 KB"))
            }
        } },
        Check("presentationEditorHostileImportKeepsPreviousDocument") { withEditor { activity ->
            withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
            waitEditor(activity, image = true)
            val before = withContext(Dispatchers.Main) { activity.binding.stickerCanvas.exportFinalBitmap() }
            try {
                withContext(Dispatchers.Main) { activity.importUri(Uri.parse("https://example.com/image.png")) }
                waitEditor(activity, image = true)
                withContext(Dispatchers.Main) {
                    val after = activity.binding.stickerCanvas.exportFinalBitmap()
                    try { check(before.sameAs(after) && activity.binding.saveButton.isEnabled) } finally { after.recycle() }
                }
            } finally { before.recycle() }
        } },
        Check("presentationEditorPastesClipboardThroughSecurityPipeline") { withEditor { activity ->
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            try {
                withContext(Dispatchers.Main) {
                    clipboard.setPrimaryClip(imageClip())
                    activity.onWindowFocusChanged(true)
                    check(activity.binding.pasteImage.visibility == View.VISIBLE)
                    activity.binding.pasteImage.performClick()
                }
                waitEditor(activity, image = true)
                withContext(Dispatchers.Main) { check(activity.binding.saveButton.isEnabled) }
            } finally { withContext(Dispatchers.Main) { clearClipboard(clipboard) } }
        } },
        Check("presentationDoubleImportOpensSourceOnlyOnce") { withEditor { activity ->
            app.contentResolver.call(providerUri("swap"), "reset", null, null)
            withContext(Dispatchers.Main) { activity.importUri(providerUri("swap")); activity.importUri(providerUri("swap")) }
            waitEditor(activity, image = true)
            check(app.contentResolver.call(providerUri("swap"), "count", null, null)!!.getInt("opens") == 1)
        } },
        Check("presentationPhotoPickerImageOnlyResultIsSanitized") { withEditor { activity ->
            var seen: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    seen = Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(providerUri("spoofed")))
                }
            }
            instrumentation.addMonitor(monitor)
            try { withContext(Dispatchers.Main) { activity.binding.chooseTool.performClick() } }
            finally { instrumentation.removeMonitor(monitor) }
            waitEditor(activity, image = true)
            check(seen?.type == "image/*")
        } },
        Check("presentationToolSelectionOutlineUndoAndSquareWorkspace") { withEditor { activity ->
            withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) {
                activity.binding.lassoTool.performClick()
                check(activity.binding.stickerCanvas.mode == StickerCanvasView.MODE_LASSO_CROP && activity.binding.lassoTool.isActivated)
                activity.binding.eraserTool.performClick()
                check(activity.binding.eraserSize.visibility == View.VISIBLE)
                activity.binding.transformTool.performClick()
                activity.binding.outlineTool.performClick()
                check(!activity.binding.saveButton.isEnabled)
            }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) {
                check(activity.binding.stickerCanvas.hasOutline)
                check(activity.binding.stickerCanvas.width == activity.binding.stickerCanvas.height)
                activity.binding.undoTool.performClick()
            }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) { check(!activity.binding.stickerCanvas.hasOutline) }
            delay(200)
            capture("editor-module6.png")
        } },
        Check("presentationMissingPickerRestoresEditorControls") { withEditor { activity ->
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    throw android.content.ActivityNotFoundException("No image picker in this fixture")
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                withContext(Dispatchers.Main) {
                    activity.binding.chooseTool.performClick()
                    check(activity.binding.chooseTool.isEnabled && activity.binding.loading.root.visibility == View.GONE)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        } },
        Check("presentationCanvasSessionRestoresLayersAndUndo") { withEditor { activity ->
            withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) {
                val canvas = activity.binding.stickerCanvas
                canvas.setTextLayer("Ya po")
                val session = canvas.captureSession()
                canvas.clearEditor()
                canvas.restoreSession(session)
                check(canvas.textLayer == "Ya po" && canvas.canUndo)
                check(canvas.undo() && canvas.textLayer.isEmpty())
            }
            waitEditor(activity, image = true)
        } },
        Check("presentationWeightRecoversAfterGestureOverlapsDebounce") { withEditor { activity ->
            withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
            waitEditor(activity, image = true)
            val start = android.os.SystemClock.uptimeMillis()
            withContext(Dispatchers.Main) {
                val canvas = activity.binding.stickerCanvas
                canvas.setTextLayer("Dale")
                val event = android.view.MotionEvent.obtain(start, start, android.view.MotionEvent.ACTION_DOWN,
                    canvas.width / 2f, canvas.height / 2f, 0)
                try { check(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            delay(260)
            withContext(Dispatchers.Main) {
                val canvas = activity.binding.stickerCanvas
                val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP,
                    canvas.width / 2f, canvas.height / 2f, 0)
                try { check(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            waitEditor(activity, image = true)
            withContext(Dispatchers.Main) { check(activity.binding.fileWeight.text.toString().endsWith("KB / 98 KB")) }
        } },
        Check("presentationSaveAppendsOneStickerAndUpdatesVersion") { withPack(1) { id ->
            withEditor(id) { activity ->
                withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
                waitEditor(activity, image = true)
                withContext(Dispatchers.Main) { activity.binding.saveButton.performClick(); check(!activity.binding.saveButton.isEnabled) }
                awaitFinished(activity)
                val pack = requireNotNull(repository.getDraft(id))
                check(pack.stickers.size == 2 && pack.imageDataVersion == 2L)
                verify(repository.readSticker(id, pack.stickers.last().imageFileName), 512, 98_000)
            }
        } },
        Check("presentationRecreationKeepsTextUndoAndToolState") {
            val first = instrumentation.startActivitySync(Intent(app, StickerEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StickerEditorActivity
            var recreated: StickerEditorActivity? = null
            val monitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
            try {
                waitEditor(first)
                withContext(Dispatchers.Main) { first.importUri(providerUri("spoofed")) }
                waitEditor(first, image = true)
                withContext(Dispatchers.Main) { first.binding.stickerCanvas.setTextLayer("Cachai") }
                waitEditor(first, image = true)
                withContext(Dispatchers.Main) {
                    val version = first.binding.stickerCanvas.documentVersion
                    first.binding.eraserTool.performClick()
                    check(first.binding.stickerCanvas.documentVersion == version)
                    check(first.binding.fileWeight.text.toString() != app.getString(R.string.metric_pending))
                    first.recreate()
                }
                recreated = instrumentation.waitForMonitorWithTimeout(monitor, 7_000) as? StickerEditorActivity
                val next = requireNotNull(recreated)
                waitEditor(next, image = true)
                withContext(Dispatchers.Main) {
                    check(next.binding.stickerCanvas.textLayer == "Cachai" && next.binding.stickerCanvas.canUndo)
                    check(next.binding.stickerCanvas.mode == StickerCanvasView.MODE_ERASER)
                    check(next.binding.stickerCanvas.undo() && next.binding.stickerCanvas.textLayer.isEmpty())
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                withContext(Dispatchers.Main) { recreated?.finish(); if (!first.isDestroyed) first.finish() }
            }
        },
        Check("presentationSaveReplacementPreservesEmojiAndRemovesOldFile") { withPack(3) { id ->
            repository.updateStickerEmojis(id, "s0.webp", listOf("😀"))
            withEditor(id, "s0.webp") { activity ->
                waitEditor(activity, image = true)
                withContext(Dispatchers.Main) { activity.binding.stickerCanvas.setTextLayer("Dale") }
                waitEditor(activity, image = true)
                withContext(Dispatchers.Main) { activity.binding.saveButton.performClick() }
                awaitFinished(activity)
                val pack = repository.getDraft(id)!!
                check(pack.stickers.size == 3 && pack.stickers.first().emojis == listOf("😀"))
                check(pack.stickers.first().imageFileName != "s0.webp" && !File(app.filesDir, "stickers/$id/s0.webp").exists())
            }
        } },
        Check("presentationNewPackSavePublishesFirstStickerAndTray") {
            val name = "UiNew${StickerStorageNames.newIdentifier()}"
            try {
                withEditor { activity ->
                    withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
                    waitEditor(activity, image = true)
                    withContext(Dispatchers.Main) { activity.saveSticker(name, "Tierruos") }
                    awaitFinished(activity)
                    val pack = repository.listPacks(200).single { it.name == name }
                    check(pack.stickers.size == 1 && pack.imageDataVersion == 1L)
                    verify(repository.readTrayImage(pack.identifier), 96, 48_000)
                }
            } finally { repository.listPacks(200).filter { it.name == name }.forEach { repository.deletePack(it.identifier) } }
        },
        Check("presentationStaleSaveDoesNotAppendOrLeaveOrphans") { withPack(1) { id ->
            withEditor(id) { activity ->
                withContext(Dispatchers.Main) { activity.importUri(providerUri("spoofed")) }
                waitEditor(activity, image = true)
                repository.updatePackMetadata(id, "Changed", "Tierruos", false)
                withContext(Dispatchers.Main) { activity.binding.saveButton.performClick() }
                waitEditor(activity, image = true)
                check(repository.getDraft(id)!!.stickers.size == 1)
                check(File(app.filesDir, "stickers/$id").listFiles()!!.size == 2)
                check(!activity.isFinishing)
            }
        } },
        Check("presentationExportFailureRestoresMainCtaWithoutFalseSuccess") { withPack(3) { _ ->
            withMain { activity ->
                waitMain(activity)
                withContext(Dispatchers.Main) { activity.binding.exportButton.performClick() }
                waitMain(activity)
                withContext(Dispatchers.Main) { check(activity.binding.exportButton.isEnabled && !activity.isFinishing) }
            }
        } },
    )

    private suspend fun images(): List<ByteArray> {
        if (assets == null) {
            val result = mutableListOf<ByteArray>()
            repeat(3) { index ->
                val source = File.createTempFile("presentation-", ".png", app.cacheDir)
                val bitmap = artwork(index)
                try {
                    withContext(Dispatchers.IO) { source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    result += engine.createSticker(Uri.fromFile(source)).bytes
                    if (index == 0) tray = engine.createTrayIcon(Uri.fromFile(source)).bytes
                } finally { bitmap.recycle(); check(source.delete()) }
            }
            assets = result
        }
        return requireNotNull(assets)
    }
    private suspend fun withPack(count: Int, block: suspend (String) -> Unit) {
        val data = images()
        val id = "UiTest${StickerStorageNames.newIdentifier()}"
        try {
            repository.createPackWithSticker(id, "Entre panas", "Tierruos", data[0], requireNotNull(tray), "s0.webp")
            repeat(count - 1) { repository.addSticker(id, data[(it + 1) % data.size], imageFileName = "s${it + 1}.webp") }
            block(id)
        } finally { repository.deletePack(id) }
    }
    private suspend fun withMain(block: suspend (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { waitMain(activity); block(activity) } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }
    private suspend fun withEditor(packId: String? = null, fileName: String? = null, block: suspend (StickerEditorActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(app, StickerEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(StickerEditorActivity.EXTRA_PACK_ID, packId).putExtra(StickerEditorActivity.EXTRA_FILE_NAME, fileName)) as StickerEditorActivity
        try { waitEditor(activity); block(activity) } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }
    private suspend fun waitMain(activity: MainActivity) = withContext(Dispatchers.Main) {
        withTimeout(8_000) { while (!activity.hasWindowFocus() || activity.binding.root.width == 0 || activity.binding.loading.root.visibility == View.VISIBLE) delay(16) }
        delay(100)
    }
    private suspend fun waitEditor(activity: StickerEditorActivity, image: Boolean = false) = withContext(Dispatchers.Main) {
        delay(240)
        withTimeout(10_000) {
            while (!activity.hasWindowFocus() || activity.binding.root.width == 0 || activity.binding.loading.root.visibility == View.VISIBLE ||
                activity.binding.stickerCanvas.isBusy || (image && !activity.binding.stickerCanvas.hasImage) ||
                activity.binding.fileWeight.text.toString() == app.getString(R.string.metric_pending)) delay(16)
        }
    }
    private suspend fun awaitFinished(activity: Activity) = withContext(Dispatchers.Main) {
        withTimeout(10_000) { while (!activity.isFinishing) delay(16) }
    }
    private fun providerUri(name: String): Uri = Uri.parse("content://com.tierruos.stickers.test.sanitizer-inputs/$name")
    private fun imageClip(): ClipData = ClipData("Image", arrayOf("image/png"), ClipData.Item(providerUri("spoofed")))
    private fun clearClipboard(manager: ClipboardManager) {
        if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
    private fun verify(bytes: ByteArray, edge: Int, limit: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(bytes.size in 1 until limit && options.outWidth == edge && options.outHeight == edge && options.outMimeType == "image/webp")
    }
    private fun artwork(index: Int): Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(60f, 60f, 452f, 452f), 108f, 108f, paint)
        paint.color = intArrayOf(0xFF25D366.toInt(), 0xFFFFC857.toInt(), 0xFF80BFFF.toInt())[index]
        canvas.drawRoundRect(RectF(72f, 72f, 440f, 440f), 96f, 96f, paint)
        paint.color = 0xFF0E0E11.toInt()
        canvas.drawCircle(192f, 210f, 16f, paint); canvas.drawCircle(320f, 210f, 16f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(200f, 212f, 312f, 300f), 10f, 160f, false, paint)
        paint.style = Paint.Style.FILL; paint.textSize = 56f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(arrayOf("Ya po", "Dale", "Tranqui")[index], 256f, 376f, paint)
    }
    private suspend fun capture(name: String) {
        delay(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(app.filesDir, "qa").apply { check(isDirectory || mkdirs()) }
            withContext(Dispatchers.IO) { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        } finally { bitmap.recycle() }
    }
}
