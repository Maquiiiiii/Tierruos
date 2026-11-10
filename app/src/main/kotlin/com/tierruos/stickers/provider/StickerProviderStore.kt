package com.tierruos.stickers.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.tierruos.stickers.data.StickerStorageCoordinator
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.db.TierruosDatabaseHelper
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.StickerFileRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.withLock

/** Synchronous bounded work for Binder threads; never waits on a coroutine or holds a bitmap. */
internal class StickerProviderStore(context: Context, private val database: TierruosDatabaseHelper) {
    private val app = context.applicationContext

    fun metadata(identifier: String?, projection: Array<out String>?): MatrixCursor = StickerStorageCoordinator.lock.withLock {
        if (identifier != null) StickerStorageNames.requireIdentifier(identifier)
        val requested = columns(projection, StickerProviderContract.metadataColumns)
        val cursor = StickerContentProvider.newMetadataCursor(requested)
        val cursorColumns = cursor.columnNames
        fun add(pack: StickerPackDraft) {
            if (!pack.isExportable) return
            val exportedName = pack.name.replace("\u2800", "").trim().ifEmpty { "Tierruos" }
            val exportedPublisher = pack.publisher.replace("\u2800", "").trim().ifEmpty { "Tierruos" }
            val values = arrayOf<Any>(
                pack.identifier,
                exportedName,
                exportedPublisher,
                pack.trayImageFile,
                "",
                "",
                "",
                "",
                "",
                "",
                pack.imageDataVersion.toString(),
                if (pack.avoidCache) 1 else 0,
                StickerContentProvider.STATIC_STICKER_PACK_VALUE,
            )
            cursor.addRow(
                cursorColumns.map { col ->
                    if (col == StickerProviderContract.IOS_APP_STORE_LINK) ""
                    else values[StickerProviderContract.metadataColumns.indexOf(col)]
                }
            )
        }
        if (identifier != null) database.getDraft(identifier)?.let(::add) else {
            var offset = 0
            do {
                val page = database.listPacks(200, offset)
                page.forEach(::add)
                offset += page.size
            } while (page.size == 200)
        }
        cursor
    }

    fun stickers(identifier: String, projection: Array<out String>?): MatrixCursor = StickerStorageCoordinator.lock.withLock {
        StickerStorageNames.requireIdentifier(identifier)
        val columns = columns(projection, StickerProviderContract.stickerColumns)
        MatrixCursor(columns).apply {
            val pack = database.getDraft(identifier)
            if (pack?.isExportable == true) pack.stickers.forEach { sticker ->
                val emojis = StickerContentProvider.formatStickerEmojis(sticker.emojis)
                val values = arrayOf(sticker.imageFileName, emojis, "")
                addRow(columns.map { values[StickerProviderContract.stickerColumns.indexOf(it)] })
            }
        }
    }

    fun openAsset(identifier: String, fileName: String): AssetFileDescriptor = StickerStorageCoordinator.lock.withLock {
        try {
            openAssetLocked(identifier, fileName)
        } catch (error: ErrnoException) {
            throw FileNotFoundException("No se pudo abrir la imagen privada.").apply { initCause(error) }
        } catch (error: IOException) {
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("La imagen privada no es válida para exportar.").apply { initCause(error) }
        }
    }

    private fun openAssetLocked(identifier: String, fileName: String): AssetFileDescriptor {
        StickerStorageNames.requireIdentifier(identifier)
        StickerStorageNames.requireImageFileName(fileName)
        val pack = database.getDraft(identifier)?.takeIf { it.isExportable }
            ?: throw FileNotFoundException("No existe un pack exportable.")
        val tray = fileName == pack.trayImageFile || fileName == StickerFileRepository.TRAY_FILE_NAME
        val sticker = pack.stickers.firstOrNull { it.imageFileName == fileName }
        if (!tray && sticker == null) throw FileNotFoundException("La imagen no está registrada en el pack.")

        val privateRoot = app.filesDir.canonicalFile
        val root = safeChild(privateRoot, "stickers")
        val directory = safeChild(root, identifier)
        val file = if (tray) {
            val requested = safeChild(directory, fileName)
            val configured = safeChild(directory, pack.trayImageFile)
            val defaultTray = safeChild(directory, StickerFileRepository.TRAY_FILE_NAME)
            when {
                requested.isFile -> requested
                configured.isFile -> configured
                defaultTray.isFile -> defaultTray
                else -> requested
            }
        } else {
            safeChild(directory, sticker!!.imageFileName)
        }
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw SecurityException("La imagen debe pertenecer al directorio privado de stickers.")
        }
        val before = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(before.st_mode)) throw SecurityException("La imagen debe ser un archivo regular.")
        if (!file.isFile || file.length() <= 0L) {
            throw FileNotFoundException("El archivo de imagen no existe o está vacío.")
        }
        return StickerContentProvider.openReadOnlyAssetDescriptor(file)
    }

    private fun safeChild(parent: File, name: String): File {
        val file = File(parent, name).absoluteFile
        if (parent.canonicalFile != parent.absoluteFile || file.canonicalFile != file || file.parentFile != parent) {
            throw SecurityException("Se rechazó una ruta no canónica.")
        }
        if (OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)) throw SecurityException("No se admiten enlaces simbólicos.")
        return file
    }

    private fun columns(projection: Array<out String>?, allowed: Array<String>): Array<String> {
        if (projection == null) return allowed.copyOf()
        require(projection.isNotEmpty() && projection.size <= allowed.size && projection.distinct().size == projection.size &&
            projection.all { it in allowed }) { "Proyección de columnas no admitida."
        }
        return projection.map { it }.toTypedArray()
    }
}
