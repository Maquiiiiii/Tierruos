package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tierruos.stickers.R
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.databinding.ActivityMainBinding
import com.tierruos.stickers.export.WhatsAppExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : TierruosActivity() {
    internal lateinit var binding: ActivityMainBinding
        private set
    private val repository by lazy { TierruosStorage.get(this).repository }
    private val exporter by lazy { WhatsAppExporter(this) }
    private val clipboard by lazy { ClipboardImages(this) }
    private lateinit var adapter: StickerPackAdapter
    private var packs = emptyList<StickerPackDraft>()
    private var selected: String? = null
    private var working = false
    private var awaitingExport = false
    private var picking = false
    private var loadJob: Job? = null
    private var loadingMessage = R.string.loading_packs
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false; renderControls()
        if (uri != null) openEditor(uri = uri)
    }
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selected = result.data?.getStringExtra(StickerEditorActivity.EXTRA_PACK_ID) ?: selected
            binding.exportButton.actionFeedback()
            notice(R.string.sticker_saved)
        }
        reload()
    }
    private val exportResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingExport = false; renderControls()
        val accepted = result.resultCode == Activity.RESULT_OK
        binding.exportButton.actionFeedback(accepted)
        notice(if (accepted) R.string.whatsapp_added else R.string.whatsapp_canceled)
        reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root); applyInsets(binding.root)
        selected = savedInstanceState?.getString("selected")
        awaitingExport = savedInstanceState?.getBoolean("exporting") ?: false
        picking = savedInstanceState?.getBoolean("picking") ?: false
        adapter = StickerPackAdapter(repository, uiScope,
            onSelect = { selected = it.identifier; adapter.submit(packs, selected); renderControls() },
            onAdd = { openEditor(it.identifier) },
            onEdit = { pack, name -> openEditor(pack.identifier, fileName = name) },
            onDeleteSticker = { pack, name -> confirmDelete(pack, name) },
            onDeletePack = { confirmDelete(it, null) })
        binding.packList.adapter = adapter
        binding.packList.itemsCanFocus = true
        binding.createPack.setOnClickListener { if (!busy()) openEditor() }
        binding.emptyChoose.setOnClickListener { if (!busy()) {
            picking = true; renderControls()
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (error: Exception) { picking = false; renderControls(); notice(errorMessage(error)) }
        } }
        binding.pasteImage.setOnClickListener {
            if (!busy()) {
                if (clipboard.candidate() == null) { refreshClipboard(); notice(R.string.clipboard_empty) }
                else openEditor(selected, paste = true)
            }
        }
        binding.retryButton.setOnClickListener { reload() }
        binding.exportButton.setOnClickListener { exportSelected() }
        uiScope.launch { exporter.isExporting.collect { renderControls() } }
    }

    override fun onResume() { super.onResume(); if (::binding.isInitialized && !awaitingExport) reload() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) refreshClipboard()
    }

    private fun refreshClipboard() {
        if (!hasWindowFocus()) return
        binding.pasteImage.visibility = if (clipboard.candidate() != null) View.VISIBLE else View.GONE
        renderControls()
    }
    private fun busy(): Boolean = working || awaitingExport || picking || exporter.isExporting.value

    internal fun reload() {
        if (working || awaitingExport) return
        loadJob?.cancel()
        loadJob = uiScope.launch {
            working = true; renderControls(R.string.loading_packs)
            try {
                val result = mutableListOf<StickerPackDraft>()
                do {
                    val page = repository.listPacks(200, result.size)
                    result.addAll(page)
                } while (page.size == 200)
                packs = result
                if (packs.none { it.identifier == selected }) selected = packs.firstOrNull()?.identifier
                adapter.submit(packs, selected)
                binding.emptyState.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
                binding.loadError.visibility = View.GONE
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { binding.emptyState.visibility = View.GONE; binding.loadError.visibility = View.VISIBLE; notice(errorMessage(error)) }
            finally { working = false; renderControls() }
        }
    }

    private fun openEditor(packId: String? = null, uri: Uri? = null, fileName: String? = null, paste: Boolean = false) {
        if (working || awaitingExport) return
        val pack = packs.firstOrNull { it.identifier == packId }
        if (fileName == null && pack?.stickers?.size == 30) { notice(R.string.pack_full); return }
        val intent = Intent(this, StickerEditorActivity::class.java)
            .putExtra(StickerEditorActivity.EXTRA_PACK_ID, packId)
            .putExtra(StickerEditorActivity.EXTRA_IMAGE_URI, uri?.toString())
            .putExtra(StickerEditorActivity.EXTRA_FILE_NAME, fileName)
            .putExtra(StickerEditorActivity.EXTRA_PASTE, paste)
        editor.launch(intent)
    }

    private fun exportSelected() {
        val pack = packs.firstOrNull { it.identifier == selected } ?: return
        if (busy() || !pack.isExportable) return
        working = true; renderControls(R.string.exporting_pack)
        uiScope.launch {
            try {
                exporter.export(this@MainActivity, pack.identifier, launcher = { intent ->
                    awaitingExport = true
                    exportResult.launch(intent)
                })
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { awaitingExport = false; binding.exportButton.actionFeedback(false); notice(errorMessage(error)) }
            finally { working = false; renderControls() }
        }
    }

    private fun confirmDelete(pack: StickerPackDraft, fileName: String?) {
        if (busy()) return
        MaterialAlertDialogBuilder(this).setTitle(if (fileName == null) R.string.delete_pack else R.string.delete_sticker)
            .setMessage(if (fileName == null) R.string.delete_pack_confirm else R.string.delete_sticker_confirm)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.delete_action) { _, _ ->
                if (!busy()) uiScope.launch {
                    working = true; renderControls(R.string.deleting_image)
                    try {
                        if (fileName == null) repository.deletePack(pack.identifier, pack.imageDataVersion)
                        else repository.deleteSticker(pack.identifier, fileName, pack.imageDataVersion)
                        binding.packList.actionFeedback()
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { notice(errorMessage(error)); binding.packList.actionFeedback(false) }
                    finally { working = false; renderControls(); reload() }
                }
            }.show()
    }

    private fun renderControls(message: Int? = null) {
        if (!::binding.isInitialized) return
        if (message != null) loadingMessage = message
        val blocked = busy()
        binding.loading.root.visibility = if (working || awaitingExport || exporter.isExporting.value) View.VISIBLE else View.GONE
        binding.loading.message.setText(if (awaitingExport) R.string.waiting_whatsapp else loadingMessage)
        binding.createPack.isEnabled = !blocked
        binding.pasteImage.isEnabled = !blocked
        binding.emptyChoose.isEnabled = !blocked
        binding.retryButton.isEnabled = !blocked
        binding.exportButton.isEnabled = !blocked && packs.any { it.identifier == selected && it.isExportable }
        binding.packList.importantForAccessibility = if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (::adapter.isInitialized) adapter.setInteractive(!blocked)
    }
    private fun notice(message: Int) { Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.exportButton).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected); outState.putBoolean("exporting", awaitingExport); outState.putBoolean("picking", picking)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (::adapter.isInitialized) adapter.dispose(); super.onDestroy() }
}
