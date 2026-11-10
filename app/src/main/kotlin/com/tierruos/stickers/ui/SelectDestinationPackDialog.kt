package com.tierruos.stickers.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tierruos.stickers.R
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.StickerFileRepository
import com.tierruos.stickers.databinding.DialogSelectPackBinding
import com.tierruos.stickers.databinding.ItemSelectDestinationPackBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Floating modal dialog (#16161D, 24dp radius, 1dp #2E2E3A border, 92% screen width) shown when
 * saving a sticker from the main AnimatedBottomNav flow (`EXTRA_PACK_ID == null`).
 * - Scenario 1 (`packs.isNotEmpty()`): "Guardar en carpeta" list (max 260dp height) + "+ Crear nueva carpeta" button.
 * - Scenario 2 (`packs.isEmpty()`): Empty folder illustration + "No existe ninguna carpeta" + "Crear carpeta ahora" CTA.
 */
object SelectDestinationPackDialog {

    fun show(
        activity: AppCompatActivity,
        repository: StickerFileRepository,
        scope: CoroutineScope,
        packs: List<StickerPackDraft>,
        onPackSelected: (StickerPackDraft) -> Unit,
        onCreateNewPackRequested: () -> Unit,
    ): Dialog {
        val binding = DialogSelectPackBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = activity.resources.displayMetrics.density
        val targetWidth = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        val jobs = mutableSetOf<Job>()
        dialog.setOnDismissListener {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }

        if (packs.isEmpty()) {
            binding.scenarioEmptyPacks.visibility = View.VISIBLE
            binding.scenarioExistingPacks.visibility = View.GONE

            binding.createFolderNowButton.setOnClickListener { btn ->
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
                onCreateNewPackRequested()
            }
            binding.cancelSelectPackButton.setOnClickListener {
                dialog.dismiss()
            }
        } else {
            binding.scenarioEmptyPacks.visibility = View.GONE
            binding.scenarioExistingPacks.visibility = View.VISIBLE

            val maxHeightPx = (260f * density).toInt()
            binding.destinationPacksRecycler.layoutManager = object : LinearLayoutManager(activity) {
                override fun onMeasure(
                    recycler: RecyclerView.Recycler,
                    state: RecyclerView.State,
                    widthSpec: Int,
                    heightSpec: Int,
                ) {
                    val cappedHeightSpec = View.MeasureSpec.makeMeasureSpec(
                        maxHeightPx,
                        View.MeasureSpec.AT_MOST,
                    )
                    super.onMeasure(recycler, state, widthSpec, cappedHeightSpec)
                }
            }
            binding.destinationPacksRecycler.adapter = DestinationAdapter(
                packs = packs,
                repository = repository,
                scope = scope,
                jobs = jobs,
                onSelect = { selectedPack ->
                    dialog.dismiss()
                    onPackSelected(selectedPack)
                },
            )

            binding.createNewFolderButton.setOnClickListener { btn ->
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
                onCreateNewPackRequested()
            }
        }

        dialog.show()
        dialog.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private class DestinationAdapter(
        private val packs: List<StickerPackDraft>,
        private val repository: StickerFileRepository,
        private val scope: CoroutineScope,
        private val jobs: MutableSet<Job>,
        private val onSelect: (StickerPackDraft) -> Unit,
    ) : RecyclerView.Adapter<DestinationAdapter.ViewHolder>() {

        class ViewHolder(
            val binding: ItemSelectDestinationPackBinding,
            var job: Job? = null,
        ) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSelectDestinationPackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = packs.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.job?.let {
                it.cancel()
                jobs.remove(it)
            }
            val pack = packs[position]
            val binding = holder.binding
            val context = binding.root.context
            val isAnonymous = pack.name.contains("\u2800") || pack.name.isBlank()
            val count = pack.stickers.size
            val isFull = count >= 30

            if (isAnonymous) {
                binding.folderName.text = context.getString(R.string.unnamed_folder)
                binding.folderName.setTextColor(context.getColor(R.color.text_secondary))
                binding.folderName.setTypeface(null, Typeface.ITALIC)
            } else {
                binding.folderName.text = pack.name
                binding.folderName.setTextColor(context.getColor(R.color.text_primary))
                binding.folderName.setTypeface(null, Typeface.NORMAL)
            }

            if (isFull) {
                binding.folderCount.text = context.getString(R.string.folder_full)
                binding.folderItemRoot.alpha = 0.4f
                binding.folderItemRoot.isEnabled = false
                binding.folderItemRoot.isClickable = false
                binding.folderItemRoot.setOnClickListener(null)
            } else {
                binding.folderCount.text = context.getString(R.string.folder_count, count)
                binding.folderItemRoot.alpha = 1.0f
                binding.folderItemRoot.isEnabled = true
                binding.folderItemRoot.isClickable = true
                binding.folderItemRoot.setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSelect(pack)
                }
            }

            val firstSticker = pack.stickers.firstOrNull()
            if (firstSticker == null) {
                binding.folderThumb.setImageResource(R.drawable.ic_folder_pack)
            } else {
                binding.folderThumb.setImageResource(R.drawable.ic_folder_pack)
                val job = SupervisorJob(scope.coroutineContext[Job]).also {
                    holder.job = it
                    jobs.add(it)
                }
                val rowScope = CoroutineScope(scope.coroutineContext + job)
                rowScope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            val bytes = repository.readSticker(pack.identifier, firstSticker.imageFileName)
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = 2
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inScaled = false
                            }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                        if (bitmap != null) {
                            binding.folderThumb.setImageBitmap(bitmap)
                        }
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        binding.folderThumb.setImageResource(R.drawable.ic_folder_pack)
                    }
                }
            }
        }
    }
}
