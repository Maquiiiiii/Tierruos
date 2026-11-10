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
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.WebpAssetValidation
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.withLock

/** Synchronous bounded work for Binder threads; never waits on a coroutine or holds a bitmap. */
internal class StickerProviderStore(context: Context, private val database: TierruosDatabaseHelper) {
    private val app = context.applicationContext

    fun metadata(identifier: String?, projection: Array<out String>?): MatrixCursor = StickerStorageCoordinator.lock.withLock {
        if (identifier != null) StickerStorageNames.requireIdentifier(identifier)
        val columns = columns(projection, StickerProviderContract.metadataColumns)
        val cursor = MatrixCursor(columns)
        fun add(pack: StickerPackDraft) {
            if (!pack.isExportable) return
            val values = arrayOf<Any>(pack.identifier, pack.name, pack.publisher, pack.trayImageFile,
                "", "", "", "", "", "", pack.imageDataVersion.toString(), if (pack.avoidCache) 1 else 0, 0)
            cursor.addRow(columns.map { values[StickerProviderContract.metadataColumns.indexOf(it)] })
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
                val values = arrayOf(sticker.imageFileName, sticker.emojis.joinToString(","), "")
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
        val tray = fileName == pack.trayImageFile
        val sticker = pack.stickers.firstOrNull { it.imageFileName == fileName }
        if (!tray && sticker == null) throw FileNotFoundException("La imagen no está registrada en el pack.")

        val privateRoot = app.filesDir.canonicalFile
        val root = safeChild(privateRoot, "stickers")
        val directory = safeChild(root, identifier)
        val file = safeChild(directory, fileName)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw SecurityException("La imagen debe pertenecer al directorio privado de stickers.")
        }
        val before = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(before.st_mode)) throw SecurityException("La imagen debe ser un archivo regular.")
        val limit = if (tray) 48_000 else 98_000
        if (before.st_size !in 1 until limit.toLong() || (!tray && before.st_size != sticker!!.sizeInBytes.toLong())) {
            throw FileNotFoundException("El tamaño de la imagen registrada es inválido.")
        }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            val opened = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(opened.st_mode) || opened.st_ino != before.st_ino ||
                opened.st_dev != before.st_dev || opened.st_size != before.st_size) {
                throw SecurityException("La imagen cambió durante la apertura.")
            }
            val bytes = ByteArray(opened.st_size.toInt())
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))).use {
                it.readFully(bytes)
                if (it.read() != -1) throw SecurityException("La imagen cambió de tamaño.")
            }
            WebpAssetValidation.snapshot(bytes, if (tray) 96 else 512, limit)
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            AssetFileDescriptor(descriptor, 0, opened.st_size)
        } catch (failure: Throwable) {
            try { descriptor.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
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
