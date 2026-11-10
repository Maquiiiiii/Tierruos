package com.tierruos.stickers.data.model

import com.tierruos.stickers.data.PackNotReadyException
import com.tierruos.stickers.data.StickerStorageNames

/** A complete WhatsApp pack. Incomplete work is represented explicitly by StickerPackDraft. */
data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val stickers: List<Sticker>,
    val imageDataVersion: Long = 1L,
    val avoidCache: Boolean = false,
) {
    init {
        validatePack(identifier, name, publisher, trayImageFile, imageDataVersion, stickers)
        require(stickers.size in 3..30) { "Un pack exportable necesita entre 3 y 30 stickers." }
    }

    internal fun snapshot(): StickerPack = copy(stickers = immutableStickers(stickers))
}

data class StickerPackDraft(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val stickers: List<Sticker> = emptyList(),
    val imageDataVersion: Long = 1L,
    val avoidCache: Boolean = false,
) {
    init { validatePack(identifier, name, publisher, trayImageFile, imageDataVersion, stickers) }

    val isExportable: Boolean get() = stickers.size in 3..30

    fun toStickerPack(): StickerPack {
        if (!isExportable) throw PackNotReadyException(stickers.size)
        return StickerPack(identifier, name, publisher, trayImageFile, immutableStickers(stickers),
            imageDataVersion, avoidCache)
    }

    internal fun snapshot(): StickerPackDraft = copy(stickers = immutableStickers(stickers))
}

private fun immutableStickers(stickers: List<Sticker>): List<Sticker> =
    java.util.Collections.unmodifiableList(stickers.map { it.snapshot() })

private fun validatePack(
    identifier: String,
    name: String,
    publisher: String,
    trayImageFile: String,
    version: Long,
    stickers: List<Sticker>,
) {
    StickerStorageNames.requireIdentifier(identifier)
    StickerStorageNames.requireImageFileName(trayImageFile)
    require(name.isNotBlank() && name.length <= 128 && '\u0000' !in name)
    require(publisher.isNotBlank() && publisher.length <= 128 && '\u0000' !in publisher)
    require(version > 0L)
    require(stickers.size <= 30)
    require(stickers.map { it.imageFileName }.distinct().size == stickers.size)
    require(stickers.none { it.imageFileName == trayImageFile })
}
