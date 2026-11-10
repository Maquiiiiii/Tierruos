package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.Window
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.tierruos.stickers.R
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.databinding.ActivityStickerPackDetailBinding
import com.tierruos.stickers.export.WhatsAppExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Detail window for a single sticker pack with MaterialContainerTransform shared-element transition,
 * 3-column RecyclerView grid (existing stickers + trailing "Agregar Sticker" slot), floating water-effect
 * mini-navbar per sticker, primary WhatsApp export CTA, and LiquidHoldDeleteButton for pack deletion.
 */
class StickerPackDetailActivity : TierruosActivity() {
    internal lateinit var binding: ActivityStickerPackDetailBinding
        private set

    private val repository by lazy { TierruosStorage.get(this).repository }
    private val exporter by lazy { WhatsAppExporter(this) }
    private lateinit var adapter: StickerPackDetailAdapter
    private var packId: String = ""
    private var currentPack: StickerPackDraft? = null
    private var working = false
    private var awaitingExport = false
    private var loadingMessage = R.string.loading_packs
    private var loadJob: Job? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            notice(R.string.sticker_saved)
        }
        reloadPack()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        awaitingExport = false
        renderControls()
        val accepted = result.resultCode == Activity.RESULT_OK
        binding.exportPackButton.actionFeedback(accepted)
        notice(if (accepted) R.string.whatsapp_added else R.string.whatsapp_canceled)
        reloadPack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        packId = intent.getStringExtra(EXTRA_PACK_ID).orEmpty()
        val transitionName = intent.getStringExtra(EXTRA_TRANSITION_NAME)
            ?: transitionNameForPack(packId)

        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = buildContainerTransform()
        window.sharedElementReturnTransition = buildContainerTransform()

        super.onCreate(savedInstanceState)
        if (packId.isBlank()) {
            finish()
            return
        }

        binding = ActivityStickerPackDetailBinding.inflate(layoutInflater)
        ViewCompat.setTransitionName(binding.detailRoot, transitionName)
        setContentView(binding.root)
        applyInsets(binding.detailRoot)

        awaitingExport = savedInstanceState?.getBoolean("awaitingExport") ?: false

        adapter = StickerPackDetailAdapter(
            repository = repository,
            scope = uiScope,
            onStickerTap = { sticker, anchorView ->
                if (!busy()) {
                    showStickerFloatingMenu(sticker, anchorView)
                }
            },
            onAddStickerTap = {
                if (!busy()) {
                    openEditorForPack(editFileName = null)
                }
            },
        )

        binding.stickerRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.stickerRecyclerView.adapter = adapter
        binding.stickerRecyclerView.itemAnimator = null

        binding.backButton.setOnClickListener {
            if (!busy()) finishAfterTransition()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!busy()) finishAfterTransition()
                }
            },
        )

        binding.exportPackButton.setOnClickListener {
            exportCurrentPack()
        }

        binding.liquidDeletePackButton.onHoldComplete = {
            deleteCurrentPack()
        }

        uiScope.launch {
            exporter.isExporting.collect { renderControls() }
        }
    }

    private fun buildContainerTransform(): MaterialContainerTransform =
        MaterialContainerTransform().apply {
            addTarget(R.id.detailRoot)
            duration = 380L
            interpolator = FastOutSlowInInterpolator()
            val surfaceColor = Color.parseColor("#131317")
            containerColor = surfaceColor
            startContainerColor = surfaceColor
            endContainerColor = surfaceColor
            scrimColor = Color.parseColor("#B3000000")
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && !awaitingExport) {
            reloadPack()
        }
    }

    private fun busy(): Boolean = working || awaitingExport || exporter.isExporting.value

    internal fun reloadPack() {
        if (working || awaitingExport) return
        loadJob?.cancel()
        loadJob = uiScope.launch {
            working = true
            renderControls(R.string.loading_packs)
            try {
                val pack = repository.getDraft(packId)
                if (pack == null) {
                    setResult(Activity.RESULT_OK)
                    finish()
                    return@launch
                }
                currentPack = pack
                bindPackHeaderAndActions(pack)
                adapter.submit(pack)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                notice(errorMessage(error))
            } finally {
                working = false
                renderControls()
            }
        }
    }

    private fun bindPackHeaderAndActions(pack: StickerPackDraft) {
        val isAnonymous = pack.name.contains("\u2800") || pack.name.isBlank()
        val isPublisherAnonymous = pack.publisher.contains("\u2800") || pack.publisher.isBlank()

        binding.detailPackTitle.text = pack.name
        binding.detailPackTitle.visibility = if (isAnonymous) View.GONE else View.VISIBLE

        binding.detailPackPublisher.text = pack.publisher
        binding.detailPackPublisher.visibility =
            if (isAnonymous || isPublisherAnonymous) View.GONE else View.VISIBLE

        val count = pack.stickers.size
        binding.detailStickerCounter.text = getString(R.string.detail_pack_counter, count)
        binding.detailProgressBar.max = 30
        binding.detailProgressBar.progress = count.coerceIn(0, 30)

        updateExportButtonState(pack)
    }

    private fun updateExportButtonState(pack: StickerPackDraft?) {
        if (pack == null) {
            binding.exportPackButton.isEnabled = false
            binding.exportPackButton.alpha = 0.45f
            return
        }
        val count = pack.stickers.size
        if (count >= 3) {
            binding.exportPackButton.text = getString(R.string.add_whatsapp)
            binding.exportPackButton.isEnabled = !busy()
            binding.exportPackButton.alpha = if (busy()) 0.6f else 1.0f
        } else {
            val missing = (3 - count).coerceAtLeast(1)
            binding.exportPackButton.text =
                resources.getQuantityString(R.plurals.detail_export_missing, missing, missing)
            binding.exportPackButton.isEnabled = false
            binding.exportPackButton.alpha = 0.45f
        }
    }

    private fun showStickerFloatingMenu(sticker: Sticker, anchorView: View) {
        StickerFloatingActionMenu.show(
            activity = this,
            anchorView = anchorView,
            onEdit = {
                openEditorForPack(editFileName = sticker.imageFileName)
            },
            onDelete = {
                deleteSingleSticker(sticker.imageFileName)
            },
        )
    }

    private fun openEditorForPack(editFileName: String?) {
        if (busy()) return
        val pack = currentPack ?: return
        if (editFileName == null && pack.stickers.size >= 30) {
            notice(R.string.pack_full)
            return
        }
        val intent = Intent(this, StickerEditorActivity::class.java)
            .putExtra(StickerEditorActivity.EXTRA_PACK_ID, pack.identifier)
            .putExtra(StickerEditorActivity.EXTRA_FILE_NAME, editFileName)
            .putExtra(StickerEditorActivity.EXTRA_EDIT_STICKER_ID, editFileName)
        editorLauncher.launch(intent)
    }

    private fun deleteSingleSticker(fileName: String) {
        val pack = currentPack ?: return
        if (busy()) return
        uiScope.launch {
            working = true
            renderControls(R.string.deleting_image)
            try {
                repository.deleteSticker(pack.identifier, fileName, pack.imageDataVersion)
                binding.stickerRecyclerView.actionFeedback()
                notice(R.string.sticker_deleted)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                notice(errorMessage(error))
                binding.stickerRecyclerView.actionFeedback(false)
            } finally {
                working = false
                renderControls()
                reloadPack()
            }
        }
    }

    private fun deleteCurrentPack() {
        val pack = currentPack ?: return
        if (busy()) {
            binding.liquidDeletePackButton.resetState()
            return
        }
        uiScope.launch {
            working = true
            renderControls(R.string.deleting_image)
            try {
                repository.deletePack(pack.identifier, pack.imageDataVersion)
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PACK_DELETED, true))
                finishAfterTransition()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                working = false
                binding.liquidDeletePackButton.resetState()
                renderControls()
                notice(errorMessage(error))
            }
        }
    }

    private fun exportCurrentPack() {
        val pack = currentPack ?: return
        if (busy() || !pack.isExportable) return
        working = true
        renderControls(R.string.exporting_pack)
        uiScope.launch {
            try {
                exporter.export(
                    activity = this@StickerPackDetailActivity,
                    identifier = pack.identifier,
                    launcher = { intent ->
                        awaitingExport = true
                        exportLauncher.launch(intent)
                    },
                )
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                awaitingExport = false
                binding.exportPackButton.actionFeedback(false)
                notice(errorMessage(error))
            } finally {
                working = false
                renderControls()
            }
        }
    }

    private fun renderControls(message: Int? = null) {
        if (!::binding.isInitialized) return
        if (message != null) loadingMessage = message
        val blocked = busy()
        binding.loading.root.visibility =
            if (working || awaitingExport || exporter.isExporting.value) View.VISIBLE else View.GONE
        binding.loading.message.setText(if (awaitingExport) R.string.waiting_whatsapp else loadingMessage)
        binding.backButton.isEnabled = !blocked
        binding.liquidDeletePackButton.isEnabled = !blocked
        if (::adapter.isInitialized) adapter.setInteractive(!blocked)
        updateExportButtonState(currentPack)
    }

    private fun notice(message: Int) {
        Snackbar.make(binding.detailRoot, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.exportPackButton)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingExport", awaitingExport)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.dispose()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACK_ID = "extra_pack_id"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
        const val EXTRA_PACK_DELETED = "extra_pack_deleted"

        fun transitionNameForPack(identifier: String): String =
            "pack_card_transition_$identifier"
    }
}
