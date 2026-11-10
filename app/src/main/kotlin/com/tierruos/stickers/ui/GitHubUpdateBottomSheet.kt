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
 * when the user taps "TIERRUOS" in MainActivity.
 * - Replaces release notes/README with a concise version summary and a prominent "Descargar actualización" button.
 * - Downloads Tierruos-universal.apk in-app with live progress only when the user taps "Descargar actualización".
 * - Prevents duplicate concurrent downloads, verifies PK\x03\x04, and launches the system package installer.
 */
class GitHubUpdateBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogGithubUpdaterBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private var isDownloading = false
    private var pendingApkFile: File? = null
    private var latestRelease: GitHubUpdater.ReleaseInfo? = null

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val context = context ?: return@registerForActivityResult
        val updater = GitHubUpdater(context.applicationContext)
        val apk = pendingApkFile
        if (apk != null && apk.isFile && updater.canRequestPackageInstalls()) {
            launchInstaller(updater, apk)
        } else if (_binding != null && apk != null && apk.isFile) {
            showInstallReadyState(updater, apk)
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
            isDownloading = false
            activeJob?.cancel()
            dismissAllowingStateLoss()
        }

        checkUpdates(updater)
    }

    internal fun checkUpdates(updater: GitHubUpdater) {
        if (isDownloading) return
        activeJob?.cancel()
        pendingApkFile = null
        binding.stateChecking.visibility = View.VISIBLE
        binding.stateResult.visibility = View.GONE
        binding.stateDownloading.visibility = View.GONE
        binding.updateNowButton.visibility = View.GONE
        binding.updateNowButton.isEnabled = false

        activeJob = scope.launch {
            try {
                val release = updater.checkLatestRelease()
                latestRelease = release
                if (_binding == null) return@launch
                renderReleaseInfo(updater, release)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (missingAsset: GitHubUpdater.MissingApkAssetException) {
                if (_binding == null) return@launch
                renderErrorState(
                    updater = updater,
                    message = getString(R.string.updater_missing_asset, missingAsset.tagName),
                )
            } catch (_: Exception) {
                if (_binding == null) return@launch
                renderErrorState(
                    updater = updater,
                    message = getString(R.string.updater_error),
                )
            }
        }
    }

    internal fun renderReleaseInfo(updater: GitHubUpdater, release: GitHubUpdater.ReleaseInfo) {
        val b = _binding ?: return
        b.stateChecking.visibility = View.GONE
        b.stateDownloading.visibility = View.GONE
        b.stateResult.visibility = View.VISIBLE

        if (release.updateAvailable) {
            if (release.downloadUrl.isNullOrBlank()) {
                renderErrorState(
                    updater = updater,
                    message = getString(R.string.updater_missing_asset, release.tagName),
                )
                return
            }
            b.updaterStatusHeadline.text = getString(
                R.string.updater_available_title,
                release.tagName,
            )
            b.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.accent_primary))
            b.updaterVersionDetail.text = if (release.assetSizeBytes > 0L) {
                getString(
                    R.string.updater_available_summary,
                    "v${release.latestVersion}",
                    formatMegabytes(release.assetSizeBytes),
                )
            } else {
                getString(R.string.updater_available_summary_no_size, "v${release.latestVersion}")
            }
            b.updaterVersionDetail.visibility = View.VISIBLE

            b.updateNowButton.setText(R.string.updater_download_cta)
            b.updateNowButton.visibility = View.VISIBLE
            b.updateNowButton.isEnabled = true
            b.updateNowButton.setOnClickListener { btn ->
                if (!isDownloading) {
                    btn.actionFeedback()
                    startDownload(updater, release)
                }
            }
        } else {
            b.updaterStatusHeadline.text = getString(
                R.string.updater_up_to_date,
                release.installedVersion,
            )
            b.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.text_primary))
            b.updaterVersionDetail.visibility = View.GONE
            b.updateNowButton.visibility = View.GONE
            b.updateNowButton.isEnabled = false
        }
    }

    internal fun renderErrorState(
        updater: GitHubUpdater,
        message: String,
        retryAction: (() -> Unit)? = null,
    ) {
        val b = _binding ?: return
        isDownloading = false
        isCancelable = true
        b.stateChecking.visibility = View.GONE
        b.stateDownloading.visibility = View.GONE
        b.stateResult.visibility = View.VISIBLE
        b.updaterStatusHeadline.text = message
        b.updaterStatusHeadline.setTextColor(requireContext().getColor(R.color.error))
        b.updaterVersionDetail.visibility = View.GONE

        b.updateNowButton.setText(R.string.retry_action)
        b.updateNowButton.visibility = View.VISIBLE
        b.updateNowButton.isEnabled = true
        b.updateNowButton.setOnClickListener { btn ->
            btn.actionFeedback()
            if (retryAction != null) {
                retryAction()
            } else {
                checkUpdates(updater)
            }
        }
    }

    private fun startDownload(updater: GitHubUpdater, release: GitHubUpdater.ReleaseInfo) {
        if (isDownloading) return
        isDownloading = true
        activeJob?.cancel()
        isCancelable = false

        binding.updateNowButton.isEnabled = false
        binding.updateNowButton.visibility = View.GONE
        binding.stateChecking.visibility = View.GONE
        binding.stateResult.visibility = View.VISIBLE
        binding.stateDownloading.visibility = View.VISIBLE
        binding.downloadingLabel.setText(R.string.updater_downloading)
        binding.downloadProgressBar.progress = 0
        binding.downloadProgressMetric.text = if (release.assetSizeBytes > 0L) {
            getString(
                R.string.updater_progress_metric,
                0,
                formatMegabytes(0L),
                formatMegabytes(release.assetSizeBytes),
            )
        } else {
            getString(R.string.updater_progress_indeterminate, formatMegabytes(0L))
        }

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
                isDownloading = false
                isCancelable = true
                if (_binding == null) return@launch
                pendingApkFile = apkFile
                binding.downloadProgressBar.progress = 100
                showInstallReadyState(updater, apkFile)
                if (!updater.canRequestPackageInstalls()) {
                    binding.downloadingLabel.setText(R.string.updater_permission_needed)
                    unknownSourcesLauncher.launch(updater.createManageUnknownAppSourcesIntent())
                } else {
                    launchInstaller(updater, apkFile)
                }
            } catch (canceled: CancellationException) {
                isDownloading = false
                isCancelable = true
                throw canceled
            } catch (missing: GitHubUpdater.MissingApkAssetException) {
                renderErrorState(
                    updater = updater,
                    message = getString(R.string.updater_missing_asset, missing.tagName),
                )
            } catch (_: Exception) {
                renderErrorState(
                    updater = updater,
                    message = getString(R.string.updater_download_error),
                    retryAction = { startDownload(updater, release) },
                )
            }
        }
    }

    private fun showInstallReadyState(updater: GitHubUpdater, apkFile: File) {
        val b = _binding ?: return
        b.stateChecking.visibility = View.GONE
        b.stateDownloading.visibility = View.VISIBLE
        b.downloadingLabel.setText(R.string.updater_completed)
        b.downloadProgressBar.progress = 100
        b.updateNowButton.setText(R.string.updater_install_ready_cta)
        b.updateNowButton.visibility = View.VISIBLE
        b.updateNowButton.isEnabled = true
        b.updateNowButton.setOnClickListener { btn ->
            btn.actionFeedback()
            if (!updater.canRequestPackageInstalls()) {
                b.downloadingLabel.setText(R.string.updater_permission_needed)
                unknownSourcesLauncher.launch(updater.createManageUnknownAppSourcesIntent())
            } else {
                launchInstaller(updater, apkFile)
            }
        }
    }

    private fun launchInstaller(updater: GitHubUpdater, apkFile: File) {
        try {
            val installIntent = updater.createInstallIntent(apkFile)
            startActivity(installIntent)
        } catch (_: Exception) {
            renderErrorState(
                updater = updater,
                message = getString(R.string.updater_download_error),
            )
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
        isDownloading = false
        activeJob?.cancel()
        scope.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "GitHubUpdateBottomSheet"
    }
}
