package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.tierruos.stickers.R
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.databinding.ActivityMainBinding
import com.tierruos.stickers.databinding.DialogPackDetailsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : TierruosActivity() {
    internal lateinit var binding: ActivityMainBinding
        private set
    private val repository by lazy { TierruosStorage.get(this).repository }
    private lateinit var adapter: StickerPackAdapter
    private var packs = emptyList<StickerPackDraft>()
    private var selected: String? = null
    private var working = false
    private var picking = false
    private var titleUnfolded = false
    private var loadJob: Job? = null
    private var createDialog: AlertDialog? = null
    private var loadingMessage = R.string.loading_packs

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking = false
        renderControls()
        if (uri != null) openEditor(uri = uri)
    }

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selected = result.data?.getStringExtra(StickerEditorActivity.EXTRA_PACK_ID) ?: selected
            binding.bottomNav.actionFeedback()
            notice(R.string.sticker_saved)
        }
        reload()
    }

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK &&
            result.data?.getBooleanExtra(StickerPackDetailActivity.EXTRA_PACK_DELETED, false) == true
        ) {
            notice(R.string.pack_deleted)
        }
        reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        selected = savedInstanceState?.getString("selected")
        picking = savedInstanceState?.getBoolean("picking") ?: false
        titleUnfolded = savedInstanceState?.getBoolean("title_unfolded") ?: false

        // Trigger the progressive paper-unfold animation once per initial MainActivity opening.
        binding.appTitle.post {
            if (!isFinishing && !isDestroyed) {
                binding.appTitle.playUnfoldOnceIfAllowed(alreadyPlayed = titleUnfolded)
                titleUnfolded = true
            }
        }

        adapter = StickerPackAdapter(
            repository = repository,
            scope = uiScope,
            onOpenPack = { pack, sharedCardView ->
                selected = pack.identifier
                adapter.submit(packs, selected)
                openPackDetail(pack, sharedCardView)
            },
            onCreateNewPack = {
                if (!busy()) showCreatePackDialog()
            },
        )
        binding.packList.adapter = adapter
        binding.packList.itemsCanFocus = true

        binding.appTitle.setOnClickListener { titleView ->
            if (!busy()) {
                titleView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                titleView.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(80L)
                    .withEndAction {
                        titleView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(120L)
                            .start()
                    }
                    .start()
                openGitHubUpdater()
            }
        }

        binding.emptyChoose.setOnClickListener {
            if (!busy()) {
                picking = true
                renderControls()
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (error: Exception) {
                    picking = false
                    renderControls()
                    notice(errorMessage(error))
                }
            }
        }
        binding.retryButton.setOnClickListener { reload() }

        binding.bottomNav.onTabSelected = { tab ->
            if (tab == AnimatedBottomNav.TAB_CREATE) {
                if (!busy()) {
                    openEditor()
                } else {
                    binding.bottomNav.setSelectedTab(AnimatedBottomNav.TAB_PACKS, animate = false)
                }
            }
        }
        if (savedInstanceState == null) receiveSharedImage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedImage(intent)
    }

    private fun receiveSharedImage(incoming: Intent) {
        if (incoming.action != Intent.ACTION_SEND) return
        val uri = sharedImageUri(incoming)
        if (uri == null) { notice(R.string.image_invalid); return }
        // Never forward external extras, component names or arbitrary grants to the private editor.
        editor.launch(sharedEditorIntent(this, uri))
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNav.setSelectedTab(AnimatedBottomNav.TAB_PACKS, animate = false)
            reload()
        }
    }

    private fun busy(): Boolean = working || picking

    internal fun reload() {
        if (working) return
        loadJob?.cancel()
        loadJob = uiScope.launch {
            working = true
            renderControls(R.string.loading_packs)
            try {
                val result = mutableListOf<StickerPackDraft>()
                do {
                    val page = repository.listPacks(200, result.size)
                    result.addAll(page)
                } while (page.size == 200)
                packs = result
                if (packs.none { it.identifier == selected }) selected = packs.firstOrNull()?.identifier
                adapter.submit(packs, selected)
                // Footer button ("Crear nuevo paquete") is always rendered at the bottom of packList
                binding.emptyState.visibility = View.GONE
                binding.loadError.visibility = View.GONE
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                binding.emptyState.visibility = View.GONE
                binding.loadError.visibility = View.VISIBLE
                notice(errorMessage(error))
            } finally {
                working = false
                renderControls()
            }
        }
    }

    private fun showCreatePackDialog() {
        if (busy()) return
        val content = DialogPackDetailsBinding.inflate(layoutInflater)
        content.dialogTitle.setText(R.string.new_pack)
        content.confirmButton.setText(R.string.create_pack_action)

        val modal = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Tierruos_CreatePack)
            .setView(content.root)
            .create()
        modal.window?.setWindowAnimations(0)
        modal.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        createDialog = modal
        modal.setOnDismissListener { createDialog = null }
        content.cancelButton.setOnClickListener {
            modal.dismiss()
        }
        content.confirmButton.setOnClickListener {
            val name = content.packName.text.toString().trim().ifBlank { StickerEditorActivity.INVISIBLE_NAME }
            val publisher = content.publisher.text.toString().trim().ifBlank { StickerEditorActivity.INVISIBLE_NAME }
            if (!busy()) {
                modal.dismiss()
                createEmptyPackAndReload(name, publisher)
            }
        }
        modal.show()
        applyElevatedDialogSurface(modal)
    }

    private fun applyElevatedDialogSurface(modal: AlertDialog) {
        val surface = MaterialShapeDrawable().apply {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(resources.getDimension(R.dimen.dialog_corner_radius))
                .build()
            fillColor = ColorStateList.valueOf(getColor(R.color.surface_dialog))
            setStroke(
                resources.getDimension(R.dimen.stroke_precision),
                getColor(R.color.border_dialog),
            )
        }
        modal.window?.setBackgroundDrawable(
            InsetDrawable(surface, resources.getDimensionPixelSize(R.dimen.space_16)),
        )
    }

    private fun createEmptyPackAndReload(name: String, publisher: String) {
        if (busy()) return
        uiScope.launch {
            working = true
            renderControls(R.string.saving_image)
            try {
                val created = repository.createEmptyPack(name, publisher)
                selected = created.identifier
                binding.packList.actionFeedback()
                notice(R.string.pack_created)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                notice(errorMessage(error))
            } finally {
                working = false
                renderControls()
                reload()
            }
        }
    }

    private fun openPackDetail(pack: StickerPackDraft, sharedCardView: View) {
        if (busy()) return
        val transitionName = ViewCompat.getTransitionName(sharedCardView)
            ?: StickerPackDetailActivity.transitionNameForPack(pack.identifier)
        val intent = Intent(this, StickerPackDetailActivity::class.java)
            .putExtra(StickerPackDetailActivity.EXTRA_PACK_ID, pack.identifier)
            .putExtra(StickerPackDetailActivity.EXTRA_TRANSITION_NAME, transitionName)
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this,
            sharedCardView,
            transitionName,
        )
        detailLauncher.launch(intent, options)
    }

    private fun openGitHubUpdater() {
        val existing = supportFragmentManager.findFragmentByTag(GitHubUpdateBottomSheet.TAG)
        if (existing != null) return
        GitHubUpdateBottomSheet().show(supportFragmentManager, GitHubUpdateBottomSheet.TAG)
    }

    private fun openEditor(
        packId: String? = null,
        uri: Uri? = null,
        fileName: String? = null,
        paste: Boolean = false,
    ) {
        if (working) return
        val pack = packs.firstOrNull { it.identifier == packId }
        if (fileName == null && pack?.stickers?.size == 30) {
            notice(R.string.pack_full)
            return
        }
        val intent = Intent(this, StickerEditorActivity::class.java)
            .putExtra(StickerEditorActivity.EXTRA_PACK_ID, packId)
            .putExtra(StickerEditorActivity.EXTRA_IMAGE_URI, uri?.toString())
            .putExtra(StickerEditorActivity.EXTRA_FILE_NAME, fileName)
            .putExtra(StickerEditorActivity.EXTRA_EDIT_STICKER_ID, fileName)
            .putExtra(StickerEditorActivity.EXTRA_PASTE, paste)
        editor.launch(intent)
    }

    private fun renderControls(message: Int? = null) {
        if (!::binding.isInitialized) return
        if (message != null) loadingMessage = message
        val blocked = busy()
        binding.loading.root.visibility = if (working) View.VISIBLE else View.GONE
        binding.loading.message.setText(loadingMessage)
        binding.appTitle.isEnabled = !blocked
        binding.emptyChoose.isEnabled = !blocked
        binding.retryButton.isEnabled = !blocked
        binding.bottomNav.isEnabled = !blocked
        binding.packList.importantForAccessibility =
            if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (::adapter.isInitialized) adapter.setInteractive(!blocked)
    }

    private fun notice(message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomNav)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        outState.putBoolean("picking", picking)
        outState.putBoolean("title_unfolded", titleUnfolded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        createDialog?.dismiss()
        createDialog = null
        if (::binding.isInitialized) {
            binding.appTitle.cancelAndRelease()
        }
        if (::adapter.isInitialized) adapter.dispose()
        super.onDestroy()
    }

    companion object {
        internal fun sharedImageUri(incoming: Intent): Uri? = try {
            if (incoming.action != Intent.ACTION_SEND || incoming.type?.startsWith("image/") != true) null
            else IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)?.takeIf {
                it.scheme == "content" && !it.authority.isNullOrBlank() && it.toString().length <= 4096
            }
        } catch (_: RuntimeException) { null }

        internal fun sharedEditorIntent(context: android.content.Context, uri: Uri): Intent =
            Intent(context, StickerEditorActivity::class.java)
                .putExtra(StickerEditorActivity.EXTRA_IMAGE_URI, uri.toString())
                .apply { clipData = ClipData.newRawUri("image", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
