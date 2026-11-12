package com.tierruos.stickers.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/** Availability is only a hint. MIME and clipboard text never establish image safety. */
class ClipboardImages(context: Context) {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    fun candidate(): Uri? = try { extract(manager?.primaryClip) } catch (_: RuntimeException) { null }

    internal fun searchResult(): SearchResult = try { searchResult(manager?.primaryClip) }
        catch (_: RuntimeException) { SearchResult(null, false) }

    internal data class SearchResult(val uri: Uri?, val webLink: Boolean)

    companion object {
        internal fun searchResult(clip: ClipData?): SearchResult {
            val first = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val value = first?.uri?.toString() ?: first?.text?.takeIf { it.length <= 4096 }?.toString()
            val scheme = value?.let(Uri::parse)?.scheme
            val web = scheme == "http" || scheme == "https"
            return SearchResult(if (web) null else extract(clip), web)
        }

        internal fun isNewImage(result: SearchResult, before: String?): Boolean =
            result.uri != null && result.uri.toString() != before

        internal fun extract(clip: ClipData?): Uri? {
            if (clip == null) return null
            for (index in 0 until minOf(clip.itemCount, 8)) {
                val item = clip.getItemAt(index)
                val uri = item.uri ?: item.text?.takeIf { it.length <= 4096 }?.toString()?.let(Uri::parse)
                if (uri != null && uri.toString().length <= 4096 &&
                    ((uri.scheme == "content" && !uri.authority.isNullOrBlank()) || uri.scheme == "file")) return uri
            }
            return null
        }
    }
}
