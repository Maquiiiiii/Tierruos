package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.tierruos.stickers.R
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : TierruosActivity() {
    internal lateinit var binding: ActivityMainBinding
        private set
    private val repository by lazy { TierruosStorage.get(this).repository }
    private val clipboard by lazy { ClipboardImages(this) }
    private lateinit var adapter: StickerPackAdapter
    private var packs = emptyList<StickerPackDraft>()
    private var selected: String? = null
    private var working = false
    private var picking = false
    private var loadJob: Job? = null
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

        adapter = StickerPackAdapter(
            repository = repository,
            scope = uiScope,
            onOpenPack = { pack, sharedCardView ->
                selected = pack.identifier
                adapter.submit(packs, selected)
                openPackDetail(pack, sharedCardView)
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

        binding.createPack.setOnClickListener {
            if (!busy()) openEditor()
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
        binding.pasteImage.setOnClickListener {
            if (!busy()) {
                if (clipboard.candidate() == null) {
                    refreshClipboard()
                    notice(R.string.clipboard_empty)
                } else {
                    openEditor(selected, paste = true)
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
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.bottomNav.setSelectedTab(AnimatedBottomNav.TAB_PACKS, animate = false)
            reload()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) refreshClipboard()
    }

    private fun refreshClipboard() {
        if (!hasWindowFocus()) return
        binding.pasteImage.visibility = if (clipboard.candidate() != null) View.VISIBLE else View.GONE
        renderControls()
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
                binding.emptyState.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
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
        binding.createPack.isEnabled = !blocked
        binding.pasteImage.isEnabled = !blocked
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.dispose()
        super.onDestroy()
    }
}
