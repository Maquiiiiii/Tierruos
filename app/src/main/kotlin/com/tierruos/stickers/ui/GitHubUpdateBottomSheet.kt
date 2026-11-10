package com.tierruos.stickers.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tierruos.stickers.R
import com.tierruos.stickers.databinding.DialogGithubUpdaterBinding
import com.tierruos.stickers.update.GitHubUpdater
import java.io.File
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * High-contrast BottomSheet (#16161D, 24dp radius, 1dp #2E2E3A border) that checks GitHub Releases
 * when the user taps "Tierruos" in MainActivity, asks for manual confirmation before downloading,
 * streams progress in monospace + tnum, verifies PK\x03\x04, and launches the package installer.
 */
class GitHubUpdateBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogGithubUpdaterBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private var pendingApkFile: File? = null
    private var latestRelease: GitHubUpdater.ReleaseInfo? = null

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val context = context ?: return@registerForActivityResult
        val updater = GitHubUpdater(context)
        val apk = pendingApkFile
        if (apk != null && apk.isFile && updater.canRequestPackageInstalls()) {
            launchInstaller(updater, apk)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogGithubUpdaterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val updater = GitHubUpdater(requireContext().applicationContext)
        val installed = updater.installedVersionName()
        binding.updaterCurrentVersion.text = getString(R.string.updater_current_version, installed)

        binding.closeUpdaterButton.setOnClickListener {
            activeJob?.cancel()
            dismissAllowingStateLoss()
        }

        binding.updateNowButton.setOnClickListener { viewButton ->
            val release = latestRelease ?: return@setOnClickListener
            viewButton.actionFeedback()
            startDownload(updater, release)
        }

        checkUpdates(updater)
    }

    private fun checkUpdates(updater: GitHubUpdater) {
        activeJob?.cancel()
        binding.stateChecking.visibility = View.VISIBLE
        binding.stateResult.visibility = View.GONE
        binding.stateDownloading.visibility = View.GONE
        binding.updateNowButton.visibility = View.GONE

        activeJob = scope.launch {
            try {
                val release = updater.checkLatestRelease()
                latestRelease = release
                if (_binding == null) return@launch
                binding.stateChecking.visibility = View.GONE
                binding.stateResult.visibility = View.VISIBLE

                if (release.updateAvailable) {
                    binding.updaterStatusHeadline.text = getString(
                        R.string.updater_available_title,
                        release.tagName,
                    )
                    binding.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.accent_primary))
                    if (release.releaseNotes.isNotBlank()) {
                        binding.releaseNotesScroll.visibility = View.VISIBLE
                        binding.updaterReleaseNotes.text = release.releaseNotes
                    } else {
                        binding.releaseNotesScroll.visibility = View.GONE
                    }
                    binding.updateNowButton.text = if (release.assetSizeBytes > 0L) {
                        getString(R.string.updater_update_now, formatMegabytes(release.assetSizeBytes))
                    } else {
                        getString(R.string.updater_update_now_no_size)
                    }
                    binding.updateNowButton.visibility = View.VISIBLE
                } else {
                    binding.updaterStatusHeadline.text = getString(
                        R.string.updater_up_to_date,
                        release.installedVersion,
                    )
                    binding.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.text_primary))
                    binding.releaseNotesScroll.visibility = View.GONE
                    binding.updateNowButton.visibility = View.GONE
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                if (_binding == null) return@launch
                binding.stateChecking.visibility = View.GONE
                binding.stateResult.visibility = View.VISIBLE
                binding.updaterStatusHeadline.text = getString(R.string.updater_error)
                binding.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.error))
                binding.releaseNotesScroll.visibility = View.GONE
                binding.updateNowButton.text = getString(R.string.retry_action)
                binding.updateNowButton.visibility = View.VISIBLE
                binding.updateNowButton.setOnClickListener {
                    it.actionFeedback()
                    checkUpdates(updater)
                }
            }
        }
    }

    private fun startDownload(updater: GitHubUpdater, release: GitHubUpdater.ReleaseInfo) {
        activeJob?.cancel()
        isCancelable = false
        binding.updateNowButton.visibility = View.GONE
        binding.stateDownloading.visibility = View.VISIBLE
        binding.downloadingLabel.setText(R.string.updater_downloading)
        binding.downloadProgressBar.progress = 0
        binding.downloadProgressMetric.text = getString(
            R.string.updater_progress_metric,
            0,
            formatMegabytes(0L),
            formatMegabytes(release.assetSizeBytes),
        )

        activeJob = scope.launch {
            try {
                val apkFile = updater.downloadApk(release) { progress ->
                    scope.launch {
                        val b = _binding ?: return@launch
                        b.downloadProgressBar.progress = progress.percent
                        b.downloadProgressMetric.text = if (progress.totalBytes > 0L) {
                            getString(
                                R.string.updater_progress_metric,
                                progress.percent,
                                formatMegabytes(progress.downloadedBytes),
                                formatMegabytes(progress.totalBytes),
                            )
                        } else {
                            getString(
                                R.string.updater_progress_indeterminate,
                                formatMegabytes(progress.downloadedBytes),
                            )
                        }
                    }
                }
                if (_binding == null) return@launch
                binding.downloadingLabel.setText(R.string.updater_verifying)
                binding.downloadProgressBar.progress = 100
                pendingApkFile = apkFile
                isCancelable = true
                if (!updater.canRequestPackageInstalls()) {
                    binding.downloadingLabel.setText(R.string.updater_permission_needed)
                    unknownSourcesLauncher.launch(updater.createManageUnknownAppSourcesIntent())
                } else {
                    launchInstaller(updater, apkFile)
                }
            } catch (canceled: CancellationException) {
                isCancelable = true
                throw canceled
            } catch (_: Exception) {
                isCancelable = true
                if (_binding == null) return@launch
                binding.stateDownloading.visibility = View.GONE
                binding.stateResult.visibility = View.VISIBLE
                binding.updaterStatusHeadline.text = getString(R.string.updater_error)
                binding.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.error))
                binding.updateNowButton.visibility = View.VISIBLE
            }
        }
    }

    private fun launchInstaller(updater: GitHubUpdater, apkFile: File) {
        try {
            val installIntent = updater.createInstallIntent(apkFile)
            startActivity(installIntent)
            dismissAllowingStateLoss()
        } catch (_: Exception) {
            if (_binding != null) {
                binding.updaterStatusHeadline.text = getString(R.string.updater_error)
                binding.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.error))
            }
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val format = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return format.format(bytes / (1024.0 * 1024.0))
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        scope.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "GitHubUpdateBottomSheet"
    }
}
