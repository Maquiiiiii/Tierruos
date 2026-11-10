package com.tierruos.stickers.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.tierruos.stickers.data.StickerStorageCoordinator
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.db.TierruosDatabaseHelper
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPack
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.security.ImageSanitizerAndCompressor
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Coordinated application API for private assets and metadata. Never overwrites an active
 * filename: replacements publish a new file before committing a new database reference.
 * The application owns the injected helper and closes it after its repositories stop.
 */
class StickerFileRepository(context: Context, private val database: TierruosDatabaseHelper) {
    private val appContext = context.applicationContext
    private val root by lazy(LazyThreadSafetyMode.NONE) { File(appContext.filesDir.canonicalFile, "stickers") }
    private var initialized = false

    init { require(database.filesDirectory.absolutePath == appContext.filesDir.absolutePath) }

    suspend fun createDraft(
        name: String,
        publisher: String,
        trayBytes: ByteArray,
        identifier: String = StickerStorageNames.newIdentifier(),
        avoidCache: Boolean = false,
    ): StickerPackDraft = access { job ->
        StickerStorageNames.requireIdentifier(identifier)
        if (database.getDraft(identifier) != null) throw SQLiteConstraintException("El identificador del pack ya existe.")
        val fileName = TRAY_FILE_NAME
        val draft = StickerPackDraft(identifier, name, publisher, fileName, avoidCache = avoidCache)
        val bytes = WebpAssetValidation.snapshot(trayBytes, 96, TRAY_LIMIT)
        val file = writeFresh(identifier, fileName, bytes)
        publish(file, job) { database.createDraft(draft) }
    }

    /** Publishes a new draft with its first real sticker and matching tray in one SQL commit. */
    suspend fun createPackWithSticker(
        identifier: String,
        name: String,
        publisher: String,
        stickerBytes: ByteArray,
        trayBytes: ByteArray? = null,
        imageFileName: String,
        emojis: List<String> = Sticker.DEFAULT_EMOJIS,
    ): StickerPackDraft {
        val resolvedTrayBytes = trayBytes
            ?: ImageSanitizerAndCompressor(appContext).createTrayFromSticker(stickerBytes).bytes
        return access { job ->
            StickerStorageNames.requireIdentifier(identifier)
            if (database.getDraft(identifier) != null) throw SQLiteConstraintException("El pack ya existe.")
            val image = WebpAssetValidation.snapshot(stickerBytes, 512, STICKER_LIMIT)
            val tray = WebpAssetValidation.snapshot(resolvedTrayBytes, 96, TRAY_LIMIT)
            val trayName = TRAY_FILE_NAME
            val sticker = Sticker(
                imageFileName,
                image.size / 1_000.0,
                emojis.ifEmpty { Sticker.DEFAULT_EMOJIS },
            ).snapshot()
            val draft = StickerPackDraft(identifier, name, publisher, trayName, listOf(sticker))
            val trayFile = writeFresh(identifier, trayName, tray)
            publish(trayFile, job) {
                val imageFile = writeFresh(identifier, imageFileName, image)
                publish(imageFile, job) { database.createDraft(draft) }
            }
        }
    }

    suspend fun getDraft(identifier: String): StickerPackDraft? = access { database.getDraft(identifier) }

    suspend fun listPacks(limit: Int = 100, offset: Int = 0): List<StickerPackDraft> =
        access { database.listPacks(limit, offset) }

    /** Checks both the 3–30 model contract and every referenced asset before export. */
    suspend fun getExportablePack(identifier: String): StickerPack = access { job ->
        checkedExportablePack(identifier, job)
    }

    /** File validation and the export revision share the repository's process lock. */
    internal suspend fun preparePackForExport(identifier: String): StickerPack = access { job ->
        val pack = checkedExportablePack(identifier, job)
        job.ensureActive()
        database.advanceImageDataVersion(identifier, pack.imageDataVersion)
    }

    internal fun syncStickerFileSize(identifier: String, imageFileName: String, sizeBytes: Int) {
        database.syncStickerFileSize(identifier, imageFileName, sizeBytes)
    }

    internal fun syncTrayImageFile(identifier: String, trayFileName: String = TRAY_FILE_NAME) {
        database.syncTrayImageFile(identifier, trayFileName)
    }

    private fun checkedExportablePack(identifier: String, job: CoroutineContext): StickerPack {
        val pack = database.getPack(identifier) ?: throw NoSuchElementException("El pack no existe.")
        readAsset(identifier, pack.trayImageFile, 96, TRAY_LIMIT)
        pack.stickers.forEach {
            job.ensureActive()
            val bytes = readAsset(identifier, it.imageFileName, 512, STICKER_LIMIT)
            if (bytes.size != it.sizeInBytes) throw IOException("El tamaño del sticker no coincide con sus metadatos.")
        }
        return pack
    }

    suspend fun updatePackMetadata(
        identifier: String,
        name: String,
        publisher: String,
        avoidCache: Boolean,
        expectedVersion: Long? = null,
    ): StickerPackDraft = access {
        val current = requireDraft(identifier)
        database.updatePackMetadata(identifier, name, publisher, current.trayImageFile, avoidCache, expectedVersion)
    }

    suspend fun replaceTrayImage(
        identifier: String,
        bytes: ByteArray,
        expectedVersion: Long? = null,
    ): StickerPackDraft = access { job ->
        val current = requireDraft(identifier)
        val data = WebpAssetValidation.snapshot(bytes, 96, TRAY_LIMIT)
        val name = newName("tray")
        val file = writeFresh(identifier, name, data)
        val result = publish(file, job) {
            database.updatePackMetadata(identifier, current.name, current.publisher, name,
                current.avoidCache, expectedVersion)
        }
        drainDeletions()
        result
    }

    suspend fun addSticker(
        identifier: String,
        bytes: ByteArray,
        emojis: List<String> = Sticker.DEFAULT_EMOJIS,
        imageFileName: String = newName("sticker"),
        expectedVersion: Long? = null,
    ): Sticker = access { job ->
        val current = requireDraft(identifier)
        check(current.stickers.size < 30) { "El pack ya contiene 30 stickers." }
        StickerStorageNames.requireImageFileName(imageFileName)
        require(imageFileName != current.trayImageFile)
        val data = WebpAssetValidation.snapshot(bytes, 512, STICKER_LIMIT)
        val sticker = Sticker(
            imageFileName,
            data.size / 1_000.0,
            emojis.ifEmpty { Sticker.DEFAULT_EMOJIS }.toList(),
        ).snapshot()
        val file = writeFresh(identifier, imageFileName, data)
        publish(file, job) { database.createSticker(identifier, sticker, expectedVersion) }
    }

    suspend fun replaceSticker(
        identifier: String,
        previousFileName: String,
        bytes: ByteArray,
        emojis: List<String> = Sticker.DEFAULT_EMOJIS,
        imageFileName: String = newName("sticker"),
        expectedVersion: Long? = null,
    ): Sticker = access { job ->
        requireDraft(identifier)
        requireNotNull(database.getSticker(identifier, previousFileName)) { "El sticker no existe." }
        StickerStorageNames.requireImageFileName(imageFileName)
        require(imageFileName != previousFileName) { "La edición debe usar un nombre de archivo nuevo." }
        val data = WebpAssetValidation.snapshot(bytes, 512, STICKER_LIMIT)
        val sticker = Sticker(
            imageFileName,
            data.size / 1_000.0,
            emojis.ifEmpty { Sticker.DEFAULT_EMOJIS }.toList(),
        ).snapshot()
        val file = writeFresh(identifier, imageFileName, data)
        val result = publish(file, job) {
            database.updateSticker(identifier, previousFileName, sticker, expectedVersion)
        }
        drainDeletions()
        result
    }

    suspend fun updateStickerEmojis(
        identifier: String,
        imageFileName: String,
        emojis: List<String>,
        expectedVersion: Long? = null,
    ): Sticker = access {
        val current = database.getSticker(identifier, imageFileName)
            ?: throw NoSuchElementException("El sticker no existe.")
        database.updateSticker(
            identifier,
            imageFileName,
            current.copy(emojis = emojis.ifEmpty { Sticker.DEFAULT_EMOJIS }.toList()),
            expectedVersion,
        )
    }

    suspend fun readSticker(identifier: String, imageFileName: String): ByteArray = access {
        val sticker = database.getSticker(identifier, imageFileName)
            ?: throw NoSuchElementException("El sticker no existe.")
        val bytes = readAsset(identifier, imageFileName, 512, STICKER_LIMIT)
        if (bytes.size != sticker.sizeInBytes) throw IOException("El tamaño del archivo no coincide con SQLite.")
        bytes
    }

    suspend fun readTrayImage(identifier: String): ByteArray = access {
        readAsset(identifier, requireDraft(identifier).trayImageFile, 96, TRAY_LIMIT)
    }

    /** For a future ContentProvider. Do not expose this private path to another application. */
    suspend fun getStickerFile(identifier: String, imageFileName: String): File = access {
        val sticker = database.getSticker(identifier, imageFileName)
            ?: throw NoSuchElementException("El sticker no existe.")
        val file = assetFile(identifier, imageFileName)
        if (!file.isFile || file.length() != sticker.sizeInBytes.toLong()) throw FileNotFoundException("Falta el archivo del sticker.")
        file
    }

    suspend fun deleteSticker(identifier: String, imageFileName: String, expectedVersion: Long? = null): Boolean =
        access {
            StickerStorageNames.requireIdentifier(identifier)
            StickerStorageNames.requireImageFileName(imageFileName)
            val deleted = if (database.getDraft(identifier) == null) false
                else database.deleteSticker(identifier, imageFileName, expectedVersion)
            drainDeletions()
            deleted
        }

    suspend fun deletePack(identifier: String, expectedVersion: Long? = null): Boolean = access {
        val deleted = database.deletePack(identifier, expectedVersion)
        drainDeletions()
        // Also recover a directory left by a previously interrupted deletion.
        if (database.getDraft(identifier) == null) deletePackDirectory(identifier)
        deleted
    }

    /** Full recovery, also run automatically on the first repository operation. */
    suspend fun removeOrphanedFiles(): Unit = access { job -> drainDeletions(); sweep(job); Unit }

    private suspend fun <T> access(block: (CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        StickerStorageCoordinator.lock.withLock {
            job.ensureActive()
            if (!initialized) {
                ensureDirectory(appContext.filesDir.canonicalFile, root)
                val existingOwner = ROOT_DATABASES[root.canonicalPath]
                check(existingOwner == null || existingOwner == database.storageIdentity) {
                    "A private sticker root cannot be shared by different databases."
                }
                ROOT_DATABASES[root.canonicalPath] = database.storageIdentity
                if (root.listFiles()?.isNotEmpty() == true && !File(database.storageIdentity).isFile) {
                    throw IOException("Falta la base de datos de los stickers existentes; se conservan sus archivos.")
                }
                drainDeletions()
                sweep(job)
                initialized = true
            } else {
                if (!File(database.storageIdentity).isFile && root.listFiles()?.isNotEmpty() == true) {
                    throw IOException("Falta la base de datos; se conservan los archivos existentes.")
                }
                drainDeletions()
            }
            job.ensureActive()
            block(job)
        }
    }

    private fun requireDraft(identifier: String): StickerPackDraft = database.getDraft(identifier)
        ?: throw NoSuchElementException("El pack no existe.")

    private fun packDirectory(identifier: String): File {
        StickerStorageNames.requireIdentifier(identifier)
        return safeChild(root, identifier)
    }

    private fun assetFile(identifier: String, fileName: String): File {
        StickerStorageNames.requireImageFileName(fileName)
        return safeChild(packDirectory(identifier), fileName)
    }

    private fun safeChild(parent: File, name: String): File {
        val file = File(parent, name).absoluteFile
        rejectSymlink(parent)
        rejectSymlink(file)
        if (parent.canonicalFile != parent.absoluteFile || file.canonicalFile != file || file.parentFile != parent.absoluteFile) {
            throw SecurityException("Se rechazó una ruta no canónica o un enlace simbólico.")
        }
        return file
    }

    private fun ensureDirectory(parent: File, directory: File) {
        rejectSymlink(directory)
        if (directory.canonicalFile != directory.absoluteFile || directory.parentFile?.canonicalFile != parent.canonicalFile) {
            throw SecurityException("El directorio debe pertenecer al almacenamiento privado esperado.")
        }
        if (!directory.exists()) {
            if (!directory.mkdir()) throw IOException("No se pudo crear el directorio privado.")
            syncDirectory(parent)
        }
        if (!directory.isDirectory) throw IOException("La ruta privada no es un directorio.")
    }

    private fun writeFresh(identifier: String, fileName: String, bytes: ByteArray): File {
        val directory = packDirectory(identifier)
        ensureDirectory(root, directory)
        val destination = assetFile(identifier, fileName)
        if (fileName in database.referencedFiles(identifier)) throw IOException("El nombre ya está referenciado por el pack.")
        if (destination.exists()) throw IOException("El archivo ya existe; no se sobrescriben imágenes activas.")
        val temporary = File.createTempFile(".write-", ".tmp", directory)
        var published = false
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            if (!temporary.renameTo(destination)) throw IOException("No se pudo publicar el archivo privado.")
            published = true
            syncDirectory(directory)
            return destination
        } catch (failure: Throwable) {
            try {
                if (temporary.exists() && !temporary.delete()) throw IOException("No se pudo limpiar el temporal.")
                if (published && !destination.delete()) throw IOException("No se pudo revertir el archivo nuevo.")
            } catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private inline fun <T> publish(file: File, job: CoroutineContext, commit: () -> T): T {
        try {
            job.ensureActive()
            return commit()
        } catch (failure: Throwable) {
            // Confirm rollback before unlinking: a commit IO error can have an uncertain outcome.
            // An unreadable database preserves the new bytes for subsequent recovery.
            var unreferenced = false
            try {
                unreferenced = file.name !in database.referencedFiles(requireNotNull(file.parentFile).name)
            } catch (verification: Throwable) {
                if (verification !== failure) failure.addSuppressed(verification)
            }
            if (unreferenced && !file.delete()) {
                failure.addSuppressed(IOException("Quedó un archivo huérfano pendiente de recuperación."))
            }
            throw failure
        }
    }

    private fun readAsset(identifier: String, name: String, edge: Int, limit: Int): ByteArray {
        val file = assetFile(identifier, name)
        val length = file.length()
        if (!file.isFile || length !in 1 until limit.toLong()) throw IOException("La imagen privada falta o tiene un tamaño inválido.")
        val bytes = ByteArray(length.toInt())
        DataInputStream(file.inputStream()).use {
            it.readFully(bytes)
            if (it.read() != -1) throw IOException("La imagen cambió de tamaño durante la lectura.")
        }
        return WebpAssetValidation.snapshot(bytes, edge, limit)
    }

    private fun drainDeletions(): Int {
        var removed = 0
        while (true) {
            val batch = database.pendingDeletions()
            if (batch.isEmpty()) return removed
            for (entry in batch) {
                StickerStorageNames.requireIdentifier(entry.identifier)
                val referenced = database.referencedFiles(entry.identifier)
                if (entry.fileName.isEmpty()) {
                    if (database.getDraft(entry.identifier) == null) removed += deletePackDirectory(entry.identifier)
                } else {
                    StickerStorageNames.requireImageFileName(entry.fileName)
                    if (entry.fileName !in referenced) {
                        val file = assetFile(entry.identifier, entry.fileName)
                        if (file.exists()) {
                            if (!file.isFile || !file.delete()) throw IOException("No se pudo eliminar una imagen pendiente.")
                            syncDirectory(requireNotNull(file.parentFile))
                            removed++
                        }
                    }
                }
                // A failed unlink never acknowledges the durable deletion task.
                database.acknowledgeDeletion(entry)
            }
        }
    }

    private fun sweep(job: CoroutineContext): Int {
        var removed = 0
        val entries = root.listFiles() ?: throw IOException("No se pudo leer el directorio de stickers.")
        for (entry in entries) {
            job.ensureActive()
            val safe = safeChild(root, entry.name)
            if (safe.isFile) {
                if (!safe.delete()) throw IOException("No se pudo limpiar un archivo huérfano.")
                removed++
                continue
            }
            StickerStorageNames.requireIdentifier(entry.name)
            if (!safe.isDirectory) throw SecurityException("Entrada inesperada en el almacenamiento privado.")
            val pack = database.getDraft(entry.name)
            if (pack == null) removed += deletePackDirectory(entry.name) else {
                val references = database.referencedFiles(entry.name)
                val files = safe.listFiles() ?: throw IOException("No se pudo leer el pack privado.")
                for (file in files) {
                    val child = safeChild(safe, file.name)
                    if (!child.isFile) throw SecurityException("No se permiten subdirectorios ni enlaces en un pack.")
                    if (file.name !in references) {
                        if (!child.delete()) throw IOException("No se pudo eliminar un archivo huérfano.")
                        removed++
                    }
                }
            }
        }
        return removed
    }

    private fun deletePackDirectory(identifier: String): Int {
        val directory = packDirectory(identifier)
        if (!directory.exists()) return 0
        if (!directory.isDirectory) throw SecurityException("La ruta del pack no es un directorio.")
        val files = directory.listFiles() ?: throw IOException("No se pudo leer el pack eliminado.")
        val checked = files.map { safeChild(directory, it.name) }
        if (checked.any { !it.isFile }) throw SecurityException("No se permiten subdirectorios dentro de un pack.")
        checked.forEach { if (!it.delete()) throw IOException("No se pudo retirar un archivo del pack.") }
        if (!directory.delete()) throw IOException("No se pudo retirar el directorio del pack.")
        syncDirectory(root)
        return checked.size
    }

    private fun syncDirectory(directory: File) {
        try {
            if (!directory.isDirectory) throw IOException("La ruta a sincronizar no es un directorio.")
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        } catch (error: ErrnoException) { throw IOException("No se pudo sincronizar el directorio privado.", error) }
    }

    private fun rejectSymlink(file: File) {
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)) {
                throw SecurityException("No se permiten enlaces simbólicos en el almacenamiento de stickers.")
            }
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw IOException("No se pudo verificar la ruta privada.", error)
        }
    }

    companion object {
        const val TRAY_FILE_NAME: String = "tray.webp"
        private const val STICKER_LIMIT = ImageSanitizerAndCompressor.STICKER_MAX_BYTES
        private const val TRAY_LIMIT = ImageSanitizerAndCompressor.TRAY_ICON_MAX_BYTES
        private val ROOT_DATABASES = mutableMapOf<String, String>()
        private fun newName(prefix: String): String = "${prefix}_${StickerStorageNames.newIdentifier()}.webp"
    }
}
