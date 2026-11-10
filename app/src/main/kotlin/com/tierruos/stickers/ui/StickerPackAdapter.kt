package com.tierruos.stickers.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridLayout
import androidx.core.view.ViewCompat
import com.tierruos.stickers.R
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.StickerFileRepository
import com.tierruos.stickers.databinding.ItemStickerPackBinding
import com.tierruos.stickers.databinding.ItemStickerThumbnailBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Native ListView recycling, bounded thumbnail cache and at most two simultaneous decoders. */
class StickerPackAdapter(
    private val repository: StickerFileRepository,
    private val scope: CoroutineScope,
    private val onSelect: (StickerPackDraft) -> Unit,
    private val onAdd: (StickerPackDraft) -> Unit,
    private val onEdit: (StickerPackDraft, String) -> Unit,
    private val onDeleteSticker: (StickerPackDraft, String) -> Unit,
    private val onDeletePack: (StickerPackDraft) -> Unit,
) : BaseAdapter() {
    private data class Holder(val binding: ItemStickerPackBinding, var job: Job? = null)
    private var packs = emptyList<StickerPackDraft>()
    private var selected: String? = null
    private var interactive = true
    private val jobs = mutableSetOf<Job>()
    private val decoders = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun submit(items: List<StickerPackDraft>, selectedIdentifier: String?) {
        jobs.forEach { it.cancel() }; jobs.clear()
        packs = items.toList(); selected = selectedIdentifier
        notifyDataSetChanged()
    }
    fun setInteractive(value: Boolean) { interactive = value }
    fun dispose() { jobs.forEach { it.cancel() }; jobs.clear(); cache.evictAll() }
    override fun getCount(): Int = packs.size
    override fun getItem(position: Int): StickerPackDraft = packs[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = convertView?.tag as? Holder ?: Holder(ItemStickerPackBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            .also { it.binding.root.tag = it }
        holder.job?.let { it.cancel(); jobs.remove(it) }
        val job = SupervisorJob(scope.coroutineContext[Job]).also { holder.job = it; jobs.add(it) }
        val rowScope = CoroutineScope(scope.coroutineContext + job)
        val binding = holder.binding
        val pack = getItem(position)
        val context = parent.context
        val active = pack.identifier == selected
        binding.packName.text = pack.name
        binding.publisher.text = pack.publisher
        binding.packProgress.text = progressText(pack, context.resources)
        binding.packActions.visibility = if (active) View.VISIBLE else View.GONE
        binding.newSticker.isEnabled = pack.stickers.size < 30
        ViewCompat.setStateDescription(binding.packCard, if (active) context.getString(R.string.pack_selected) else null)
        binding.packCard.setOnClickListener { if (interactive) onSelect(pack) }
        binding.newSticker.setOnClickListener { if (interactive) onAdd(pack) }
        binding.deletePack.setOnClickListener { if (interactive) onDeletePack(pack) }
        binding.stickerGrid.removeAllViews()
        val resources = context.resources
        val gap = resources.getDimensionPixelSize(R.dimen.space_8)
        val windowWidth = parent.width.takeIf { it > 0 }
            ?: (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        val availableWidth = (windowWidth - 2 * resources.getDimensionPixelSize(R.dimen.space_16) -
            2 * resources.getDimensionPixelSize(R.dimen.card_padding)).coerceAtLeast(1)
        val columns = ((availableWidth + gap) / (resources.getDimensionPixelSize(R.dimen.thumbnail_size) + gap)).coerceIn(1, 8)
        val cellWidth = ((availableWidth - gap * (columns - 1)) / columns).coerceAtLeast(1)
        binding.stickerGrid.columnCount = columns
        val previews = if (active) pack.stickers else pack.stickers.take(columns)
        binding.stickerGrid.visibility = if (previews.isEmpty()) View.GONE else View.VISIBLE
        previews.forEachIndexed { index, sticker ->
            val thumb = ItemStickerThumbnailBinding.inflate(LayoutInflater.from(context), binding.stickerGrid, false)
            thumb.root.layoutParams = GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
                width = cellWidth; height = resources.getDimensionPixelSize(R.dimen.thumbnail_size)
                setMargins(0, 0, if (index % columns < columns - 1) gap else 0, gap)
            }
            thumb.root.contentDescription = context.getString(R.string.thumbnail_description, index + 1, pack.name)
            thumb.root.setOnClickListener { if (interactive) onEdit(pack, sticker.imageFileName) }
            thumb.root.setOnLongClickListener { if (interactive) onDeleteSticker(pack, sticker.imageFileName); true }
            binding.stickerGrid.addView(thumb.root)
            rowScope.launch {
                try {
                    val key = "${pack.identifier}/${sticker.imageFileName}"
                    val bitmap = cache.get(key) ?: withContext(Dispatchers.IO) {
                        decoders.withPermit {
                            cache.get(key) ?: run {
                                val bytes = repository.readSticker(pack.identifier, sticker.imageFileName)
                                val options = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
                                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)).also { cache.put(key, it) }
                            }
                        }
                    }
                    thumb.image.setImageBitmap(bitmap)
                    thumb.progress.visibility = View.GONE
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) {
                    thumb.progress.visibility = View.GONE
                    thumb.error.visibility = View.VISIBLE
                }
            }
        }
        return binding.root
    }

    companion object {
        internal fun progressText(pack: StickerPackDraft, resources: android.content.res.Resources): String {
            val count = pack.stickers.size
            return if (count >= 3) resources.getString(R.string.ready_pack, count) else {
                val missing = 3 - count
                resources.getQuantityString(R.plurals.missing_stickers, missing, missing, count)
            }
        }
    }
}
