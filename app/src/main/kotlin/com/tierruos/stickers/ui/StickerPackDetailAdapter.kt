package com.tierruos.stickers.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tierruos.stickers.R
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.StickerFileRepository
import com.tierruos.stickers.databinding.ItemPackDetailAddBinding
import com.tierruos.stickers.databinding.ItemPackDetailStickerBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 3-column RecyclerView adapter for StickerPackDetailActivity.
 * Renders all existing stickers in order inside square cells (#1E1E26, radius 16dp, border 1dp #2E2E38),
 * followed by the special "Agregar Sticker" cell (#0B0B0E, border 1.5dp #2E2E38, '+' + embossed icon)
 * as the last item whenever pack.stickers.size < 30.
 */
class StickerPackDetailAdapter(
    private val repository: StickerFileRepository,
    private val scope: CoroutineScope,
    private val onStickerTap: (Sticker, View) -> Unit,
    private val onAddStickerTap: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var packId: String = ""
    private var packName: String = ""
    private var packVersion: Long = 0L
    private var stickers: List<Sticker> = emptyList()
    private var interactive: Boolean = true

    private val decoders = Semaphore(2)
    private val jobs = mutableSetOf<Job>()
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun submit(pack: StickerPackDraft) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        packId = pack.identifier
        packName = pack.name
        packVersion = pack.imageDataVersion
        stickers = pack.stickers.toList()
        notifyDataSetChanged()
    }

    fun setInteractive(enabled: Boolean) {
        interactive = enabled
    }

    fun dispose() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        cache.evictAll()
    }

    private fun hasAddSlot(): Boolean = stickers.size < 30

    override fun getItemCount(): Int = stickers.size + if (hasAddSlot()) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position < stickers.size) VIEW_TYPE_STICKER else VIEW_TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_STICKER) {
            StickerViewHolder(ItemPackDetailStickerBinding.inflate(inflater, parent, false))
        } else {
            AddViewHolder(ItemPackDetailAddBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is StickerViewHolder -> bindSticker(holder, stickers[position], position)
            is AddViewHolder -> bindAdd(holder)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is StickerViewHolder) {
            holder.job?.let {
                it.cancel()
                jobs.remove(it)
            }
            holder.job = null
        }
        super.onViewRecycled(holder)
    }

    private fun bindSticker(holder: StickerViewHolder, sticker: Sticker, position: Int) {
        holder.job?.let {
            it.cancel()
            jobs.remove(it)
        }
        val binding = holder.binding
        val context = binding.root.context
        binding.stickerImage.setImageDrawable(null)
        binding.stickerLoading.visibility = View.VISIBLE
        binding.stickerCellRoot.contentDescription =
            context.getString(R.string.thumbnail_description, position + 1, packName)

        binding.stickerCellRoot.setOnClickListener { view ->
            if (interactive) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onStickerTap(sticker, view)
            }
        }

        val currentPackId = packId
        val cacheKey = "$currentPackId/$packVersion/${sticker.imageFileName}"
        val cached = cache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            binding.stickerImage.setImageBitmap(cached)
            binding.stickerLoading.visibility = View.GONE
            return
        }

        val job = SupervisorJob(scope.coroutineContext[Job]).also {
            holder.job = it
            jobs.add(it)
        }
        val cellScope = CoroutineScope(scope.coroutineContext + job)
        cellScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decoders.withPermit {
                        cache.get(cacheKey) ?: run {
                            val bytes = repository.readSticker(currentPackId, sticker.imageFileName)
                            val options = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inScaled = false
                            }
                            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)).also {
                                cache.put(cacheKey, it)
                            }
                        }
                    }
                }
                binding.stickerImage.setImageBitmap(bitmap)
                binding.stickerLoading.visibility = View.GONE
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                binding.stickerLoading.visibility = View.GONE
            }
        }
    }

    private fun bindAdd(holder: AddViewHolder) {
        holder.binding.addCellRoot.setOnClickListener { view ->
            if (interactive) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onAddStickerTap()
            }
        }
    }

    class StickerViewHolder(
        val binding: ItemPackDetailStickerBinding,
        var job: Job? = null,
    ) : RecyclerView.ViewHolder(binding.root)

    class AddViewHolder(
        val binding: ItemPackDetailAddBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_STICKER = 0
        private const val VIEW_TYPE_ADD = 1
    }
}
