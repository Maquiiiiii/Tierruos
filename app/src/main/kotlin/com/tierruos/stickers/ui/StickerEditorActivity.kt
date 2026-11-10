package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tierruos.stickers.R
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.repository.WebpAssetValidation
import com.tierruos.stickers.databinding.ActivityStickerEditorBinding
import com.tierruos.stickers.databinding.DialogPackDetailsBinding
import com.tierruos.stickers.databinding.DialogStickerTextBinding
import com.tierruos.stickers.security.ImageSanitizerAndCompressor
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StickerEditorActivity : TierruosActivity() {
    internal lateinit var binding: ActivityStickerEditorBinding
        private set
    private val repository by lazy { TierruosStorage.get(this).repository }
    private val engine by lazy { ImageSanitizerAndCompressor(this) }
    private val clipboard by lazy { ClipboardImages(this) }
    private var packId: String? = null
    private var oldFileName: String? = null
    private var expectedVersion: Long? = null
    private var newPackId = StickerStorageNames.newIdentifier()
    private var pendingFileName = "sticker_${StickerStorageNames.newIdentifier()}.webp"
    private var task = false
    private var saving = false
    private var picking = false
    private var pastePending = false
    private var dirty = false
    private var suppressChanges = false
    private var hasClipboardImage = false
    private var revision = 0L
    private var seenDocumentVersion = 0L
    private var lastBytes: ByteArray? = null
    private var lastSession: StickerCanvasView.Session? = null
    private var metricJob: Job? = null
    private var dialog: AlertDialog? = null
    private var dialogConfirmButton: View? = null
    private var selectPackDialog: android.app.Dialog? = null
    private var loadingMessage = R.string.sanitizing_image
    private var pendingImport: String? = null

    private data class Retained(
        val session: StickerCanvasView.Session?, val bytes: ByteArray?, val dirty: Boolean,
        val expectedVersion: Long?, val newPackId: String, val fileName: String, val mode: Int, val eraserWidth: Float,
    )

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false; renderControls()
        if (uri != null) requestImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerEditorBinding.inflate(layoutInflater)
        setContentView(binding.root); applyInsets(binding.root)
        packId = intent.getStringExtra(EXTRA_PACK_ID)
        oldFileName = intent.getStringExtra(EXTRA_EDIT_STICKER_ID) ?: intent.getStringExtra(EXTRA_FILE_NAME)
        newPackId = savedInstanceState?.getString("newPackId") ?: newPackId
        pendingFileName = savedInstanceState?.getString("pendingFileName") ?: pendingFileName
        expectedVersion = savedInstanceState?.getLong("version")?.takeIf { it > 0 }
        picking = savedInstanceState?.getBoolean("picking") ?: false
        pendingImport = savedInstanceState?.getString("pendingImport")
        pastePending = if (savedInstanceState == null) intent.getBooleanExtra(EXTRA_PASTE, false) else false
        configureTools()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leaveEditor() }
        })
        @Suppress("DEPRECATION")
        val retained = lastCustomNonConfigurationInstance as? Retained
        if (retained != null) {
            newPackId = retained.newPackId; pendingFileName = retained.fileName
            expectedVersion = retained.expectedVersion; dirty = retained.dirty; lastBytes = retained.bytes
            retained.session?.let {
                suppressChanges = true
                binding.stickerCanvas.restoreSession(it)
                lastSession = it
                binding.stickerCanvas.mode = retained.mode
                binding.stickerCanvas.eraserWidthPx = retained.eraserWidth
                binding.eraserSize.progress = (retained.eraserWidth - 4).toInt()
                seenDocumentVersion = binding.stickerCanvas.documentVersion
                suppressChanges = false
            }
            showWeight(lastBytes?.size)
        }
        val checkpoint = savedInstanceState?.getByteArray("checkpoint")
        if (!binding.stickerCanvas.hasImage) uiScope.launch {
            task = true; renderControls(R.string.sanitizing_image)
            try {
                if (packId != null) {
                    val pack = repository.getDraft(requireNotNull(packId)) ?: throw NoSuchElementException()
                    if (expectedVersion == null) expectedVersion = pack.imageDataVersion
                    if (oldFileName == null && pack.stickers.size >= 30) { notice(R.string.pack_full); finish(); return@launch }
                }
                if (checkpoint != null) {
                    restoreCheckpoint(checkpoint)
                    notice(R.string.restored_checkpoint)
                } else {
                    val uri = (pendingImport ?: intent.getStringExtra(EXTRA_IMAGE_URI))?.let(Uri::parse)
                        ?: oldFileName?.let { Uri.fromFile(repository.getStickerFile(requireNotNull(packId), it)) }
                    if (uri != null) { installSanitized(engine.createSticker(uri), oldFileName == null || pendingImport != null); pendingImport = null }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { notice(errorMessage(error)); binding.saveButton.actionFeedback(false) }
            finally { task = false; renderControls(); if (hasWindowFocus()) refreshClipboard() }
        }
        else if (pendingImport != null) importUri(Uri.parse(pendingImport))
        renderControls()
    }

    private fun configureTools() {
        val canvas = binding.stickerCanvas
        binding.backButton.setOnClickListener { leaveEditor() }
        binding.pasteImage.setOnClickListener { pasteImage() }
        binding.chooseTool.setOnClickListener {
            if (!blocked()) {
                picking = true; renderControls()
                try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (error: Exception) { picking = false; renderControls(); notice(errorMessage(error)) }
            }
        }
        canvas.setOnClickListener { if (!canvas.hasImage && !blocked()) binding.chooseTool.performClick() }
        binding.transformTool.setOnClickListener { selectMode(StickerCanvasView.MODE_TRANSFORM, it) }
        binding.lassoTool.setOnClickListener { selectMode(StickerCanvasView.MODE_LASSO_CROP, it) }
        binding.eraserTool.setOnClickListener { selectMode(StickerCanvasView.MODE_ERASER, it) }
        binding.outlineTool.setOnClickListener { if (!blocked() && canvas.hasImage) canvas.applyOutline(if (canvas.hasOutline) 0f else resources.getDimension(R.dimen.canvas_outline_default) / resources.displayMetrics.density) }
        binding.textTool.setOnClickListener { if (!blocked() && canvas.hasImage) editText() }
        binding.undoTool.setOnClickListener { if (!blocked() && canvas.undo()) it.actionFeedback() }
        binding.saveButton.setOnClickListener {
            if (!blocked() && canvas.hasImage) {
                if (packId == null) openSelectDestinationDialog() else saveSticker()
            }
        }
        binding.eraserSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !blocked()) canvas.eraserWidthPx = (progress + 4).toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        canvas.onEditChanged = { if (!suppressChanges) documentChanged() }
        canvas.onBusyChanged = { renderControls() }
        canvas.onError = { notice(R.string.canvas_edit_failed); binding.saveButton.actionFeedback(false) }
    }

    private fun blocked(): Boolean = task || picking || binding.stickerCanvas.isBusy

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) refreshClipboard()
    }
    private fun refreshClipboard() {
        if (!hasWindowFocus()) return
        hasClipboardImage = clipboard.candidate() != null
        binding.pasteImage.visibility = if (hasClipboardImage) View.VISIBLE else View.GONE
        renderControls()
        if (pastePending && !blocked()) { pastePending = false; pasteImage() }
    }
    internal fun pasteImage() {
        if (blocked() || !hasWindowFocus()) return
        val uri = clipboard.candidate()
        if (uri == null) { notice(R.string.clipboard_empty); refreshClipboard(); return }
        requestImport(uri)
    }
    private fun requestImport(uri: Uri) {
        if (blocked()) return
        if (dirty && binding.stickerCanvas.hasImage) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.replace_image_title).setMessage(R.string.replace_image_body)
                .setNegativeButton(R.string.cancel_action, null).setPositiveButton(R.string.replace_action) { _, _ -> importUri(uri) }.show()
        } else importUri(uri)
    }

    /** All external URI inputs, regardless of MIME or origin, enter the Module 2 URI pipeline. */
    internal fun importUri(uri: Uri) {
        if (blocked()) return
        metricJob?.cancel()
        pendingImport = uri.toString()
        task = true; renderControls(R.string.sanitizing_image)
        uiScope.launch {
            try { installSanitized(engine.createSticker(uri), true); binding.stickerCanvas.actionFeedback() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { notice(errorMessage(error)); binding.saveButton.actionFeedback(false) }
            finally { pendingImport = null; task = false; renderControls() }
        }
    }

    private suspend fun installSanitized(image: ImageSanitizerAndCompressor.CompressedImage, changed: Boolean) {
        installBytes(image.bytes)
        dirty = changed
    }
    private suspend fun restoreCheckpoint(bytes: ByteArray) {
        withContext(Dispatchers.IO) { WebpAssetValidation.snapshot(bytes, 512, 98_000) }
        installBytes(bytes)
        dirty = true
    }
    private suspend fun installBytes(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }))
        try {
            withContext(Dispatchers.Main.immediate) {
                suppressChanges = true
                try { binding.stickerCanvas.setImageBitmap(bitmap); binding.stickerCanvas.mode = StickerCanvasView.MODE_TRANSFORM }
                finally { suppressChanges = false }
                revision++
                seenDocumentVersion = binding.stickerCanvas.documentVersion
                lastSession = binding.stickerCanvas.captureSession()
                lastBytes = bytes
                showWeight(bytes.size)
            }
        } finally { bitmap.recycle() }
    }

    private fun selectMode(mode: Int, button: View) {
        if (blocked() || !binding.stickerCanvas.hasImage) return
        binding.stickerCanvas.mode = mode
        renderControls()
        button.actionFeedback()
    }
    private fun documentChanged() {
        if (suppressChanges || task || !binding.stickerCanvas.hasImage || binding.stickerCanvas.isBusy) { renderControls(); return }
        if (seenDocumentVersion == binding.stickerCanvas.documentVersion) { renderControls(); return }
        try { lastSession = binding.stickerCanvas.captureSession() } catch (_: IllegalStateException) { renderControls(); return }
        seenDocumentVersion = binding.stickerCanvas.documentVersion
        dirty = true; revision++
        binding.fileWeight.setTextAppearance(R.style.TextAppearance_Tierruos_Caption)
        binding.fileWeight.setText(R.string.metric_pending)
        metricJob?.cancel()
        val requested = revision
        metricJob = uiScope.launch {
            delay(180)
            var snapshot: android.graphics.Bitmap? = null
            while (snapshot == null) {
                if (requested != revision || !binding.stickerCanvas.hasImage) return@launch
                if (!blocked()) {
                    snapshot = try { binding.stickerCanvas.exportFinalBitmap() } catch (_: IllegalStateException) { null }
                }
                if (snapshot == null) delay(32)
            }
            val bitmap = requireNotNull(snapshot)
            task = true; renderControls(R.string.compressing_image)
            try {
                val image = engine.compressStickerBitmap(bitmap)
                if (requested == revision) { lastBytes = image.bytes; showWeight(image.sizeBytes) }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { showWeight(null); notice(errorMessage(error)) }
            finally { bitmap.recycle(); task = false; renderControls() }
        }
        renderControls()
    }

    private fun editText() {
        val content = DialogStickerTextBinding.inflate(layoutInflater)
        content.dialogTitle.setText(R.string.text_layer_title)
        content.caption.setText(binding.stickerCanvas.textLayer)
        content.confirmButton.setText(R.string.apply_action)

        val modal = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Tierruos_CreatePack)
            .setView(content.root)
            .create()
        modal.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog = modal
        dialogConfirmButton = content.confirmButton
        modal.setOnDismissListener {
            dialog = null
            dialogConfirmButton = null
        }
        content.cancelButton.setOnClickListener {
            modal.dismiss()
        }
        content.confirmButton.setOnClickListener {
            if (!blocked()) try {
                binding.stickerCanvas.setTextLayer(content.caption.text.toString())
                binding.textTool.actionFeedback()
                modal.dismiss()
            } catch (_: IllegalArgumentException) {
                content.caption.error = getString(R.string.text_does_not_fit)
            }
        }
        modal.show()
        applyElevatedDialogSurface(modal)
    }

    private fun openSelectDestinationDialog() {
        if (blocked() || !binding.stickerCanvas.hasImage) return
        uiScope.launch {
            val allPacks = try {
                val result = mutableListOf<com.tierruos.stickers.data.model.StickerPackDraft>()
                do {
                    val page = repository.listPacks(200, result.size)
                    result.addAll(page)
                } while (page.size == 200)
                result
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                notice(errorMessage(error))
                return@launch
            }
            if (isFinishing || isDestroyed || blocked()) return@launch
            selectPackDialog?.dismiss()
            selectPackDialog = SelectDestinationPackDialog.show(
                activity = this@StickerEditorActivity,
                repository = repository,
                scope = uiScope,
                packs = allPacks,
                onPackSelected = { chosenPack ->
                    packId = chosenPack.identifier
                    expectedVersion = chosenPack.imageDataVersion
                    oldFileName = null
                    saveSticker()
                },
                onCreateNewPackRequested = {
                    enterPackDetails()
                },
            ).also { modal ->
                modal.setOnDismissListener { selectPackDialog = null }
            }
        }
    }

    private fun enterPackDetails() {
        val content = DialogPackDetailsBinding.inflate(layoutInflater)
        content.dialogTitle.setText(R.string.new_pack)
        content.confirmButton.setText(R.string.save_sticker)

        val modal = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Tierruos_CreatePack)
            .setView(content.root)
            .create()
        modal.window?.setWindowAnimations(0)
        modal.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog = modal
        dialogConfirmButton = content.confirmButton
        modal.setOnDismissListener {
            dialog = null
            dialogConfirmButton = null
        }
        content.cancelButton.setOnClickListener {
            modal.dismiss()
        }
        content.confirmButton.setOnClickListener {
            val name = content.packName.text.toString().trim().ifBlank { INVISIBLE_NAME }
            val publisher = content.publisher.text.toString().trim().ifBlank { INVISIBLE_NAME }
            if (!blocked()) {
                modal.dismiss()
                saveSticker(name, publisher)
            }
        }
        modal.show()
        applyElevatedDialogSurface(modal)
    }

    private fun applyElevatedDialogSurface(modal: AlertDialog) {
        val surface = com.google.android.material.shape.MaterialShapeDrawable().apply {
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCornerSizes(resources.getDimension(R.dimen.dialog_corner_radius)).build()
            fillColor = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_dialog))
            setStroke(
                resources.getDimension(R.dimen.stroke_precision),
                getColor(R.color.border_dialog),
            )
        }
        modal.window?.setBackgroundDrawable(
            android.graphics.drawable.InsetDrawable(surface, resources.getDimensionPixelSize(R.dimen.space_16)),
        )
    }

    internal fun saveSticker(name: String = "", publisher: String = "") {
        if (blocked() || !binding.stickerCanvas.hasImage) return
        metricJob?.cancel()
        task = true; saving = true; renderControls(R.string.saving_image)
        uiScope.launch {
            val bitmap = try { binding.stickerCanvas.exportFinalBitmap() } catch (_: IllegalStateException) {
                task = false; saving = false; renderControls(); return@launch
            }
            try {
                val image = engine.compressStickerBitmap(bitmap)
                val id = packId
                if (id == null) {
                    val tray = engine.createTrayFromSticker(image.bytes)
                    val previous = repository.getDraft(newPackId)
                    if (previous == null) {
                        repository.createPackWithSticker(
                            newPackId,
                            name.trim().ifBlank { INVISIBLE_NAME },
                            publisher.trim().ifBlank { INVISIBLE_NAME },
                            image.bytes,
                            tray.bytes,
                            pendingFileName,
                            com.tierruos.stickers.data.model.Sticker.DEFAULT_EMOJIS,
                        )
                    } else check(previous.stickers.any { it.imageFileName == pendingFileName })
                } else {
                    val current = repository.getDraft(id) ?: throw NoSuchElementException()
                    val old = oldFileName
                    if (old == null) {
                        repository.addSticker(
                            id,
                            image.bytes,
                            emojis = com.tierruos.stickers.data.model.Sticker.DEFAULT_EMOJIS,
                            imageFileName = pendingFileName,
                            expectedVersion = expectedVersion,
                        )
                    } else {
                        val sticker = current.stickers.firstOrNull { it.imageFileName == old } ?: throw NoSuchElementException()
                        repository.replaceSticker(
                            id,
                            old,
                            image.bytes,
                            sticker.emojis.ifEmpty { com.tierruos.stickers.data.model.Sticker.DEFAULT_EMOJIS },
                            pendingFileName,
                            expectedVersion,
                        )
                    }
                }
                showWeight(image.sizeBytes)
                dirty = false
                binding.saveButton.actionFeedback()
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PACK_ID, id ?: newPackId))
                finish()
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { notice(errorMessage(error)); binding.saveButton.actionFeedback(false) }
            finally { bitmap.recycle(); task = false; saving = false; renderControls() }
        }
    }

    private fun showWeight(bytes: Int?) {
        binding.fileWeight.setTextAppearance(if (bytes == null) R.style.TextAppearance_Tierruos_Caption
            else R.style.TextAppearance_Tierruos_TechnicalMetric)
        binding.fileWeight.text = if (bytes == null) getString(R.string.metric_empty) else {
            val format = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
            getString(R.string.metric_weight, format.format(bytes / 1_000.0))
        }
    }
    private fun renderControls(message: Int? = null) {
        if (!::binding.isInitialized) return
        if (message != null) loadingMessage = message
        val blocked = blocked()
        val image = binding.stickerCanvas.hasImage
        binding.loading.root.visibility = if (task) View.VISIBLE else View.GONE
        binding.loading.message.setText(loadingMessage)
        binding.saveButton.isEnabled = image && !blocked
        binding.backButton.isEnabled = !saving
        binding.pasteImage.isEnabled = !blocked && hasClipboardImage
        binding.chooseTool.isEnabled = !blocked
        listOf(binding.transformTool, binding.lassoTool, binding.eraserTool, binding.outlineTool, binding.textTool).forEach { it.isEnabled = image && !blocked }
        binding.undoTool.isEnabled = !blocked && binding.stickerCanvas.canUndo
        binding.transformTool.isActivated = binding.stickerCanvas.mode == StickerCanvasView.MODE_TRANSFORM
        binding.lassoTool.isActivated = binding.stickerCanvas.mode == StickerCanvasView.MODE_LASSO_CROP
        binding.eraserTool.isActivated = binding.stickerCanvas.mode == StickerCanvasView.MODE_ERASER
        binding.outlineTool.isActivated = binding.stickerCanvas.hasOutline
        binding.eraserSize.visibility = if (binding.stickerCanvas.mode == StickerCanvasView.MODE_ERASER) View.VISIBLE else View.GONE
        binding.eraserSize.isEnabled = !blocked
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !blocked
        dialogConfirmButton?.isEnabled = !blocked
        binding.stickerCanvas.isEnabled = !task && !picking
    }
    private fun leaveEditor() {
        if (saving) return
        if (task || !dirty) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.discard_title).setMessage(R.string.discard_body)
            .setNegativeButton(R.string.cancel_action, null).setPositiveButton(R.string.discard_action) { _, _ -> finish() }.show()
    }
    private fun notice(message: Int) { Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.saveButton).show() }

    @Deprecated("Retained across configuration changes via platform non-configuration instance")
    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any = Retained(lastSession, lastBytes, dirty, expectedVersion,
        newPackId, pendingFileName, binding.stickerCanvas.mode, binding.stickerCanvas.eraserWidthPx)
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("newPackId", newPackId); outState.putString("pendingFileName", pendingFileName)
        expectedVersion?.let { outState.putLong("version", it) }
        outState.putBoolean("picking", picking)
        pendingImport?.let { outState.putString("pendingImport", it) }
        lastBytes?.let { outState.putByteArray("checkpoint", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        selectPackDialog?.dismiss()
        selectPackDialog = null
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        const val INVISIBLE_NAME = "\u2800"
        const val INVISIBLE_CHAR = INVISIBLE_NAME
        const val EXTRA_PACK_ID = "pack_id"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_FILE_NAME = "image_file_name"
        const val EXTRA_EDIT_STICKER_ID = "edit_sticker_id"
        const val EXTRA_PASTE = "paste_image"
    }
}
