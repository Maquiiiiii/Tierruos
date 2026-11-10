package com.tierruos.stickers.data

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** Shared by SQLite CRUD and filesystem operations, including different repository instances. */
internal object StickerStorageCoordinator {
    val lock = ReentrantLock()
}

object StickerStorageNames {
    private val identifierPattern = Regex("^[a-zA-Z0-9]+$")
    private val filePattern = Regex("^[a-zA-Z0-9_-]+\\.webp$")

    fun requireIdentifier(identifier: String) {
        if (identifier.length !in 1..128 || !identifierPattern.matches(identifier)) {
            throw SecurityException("El identificador debe contener únicamente letras y números ASCII.")
        }
    }

    fun requireImageFileName(fileName: String) {
        if (fileName.length !in 6..128 || !filePattern.matches(fileName)) {
            throw SecurityException("El nombre de imagen no cumple el formato WebP permitido.")
        }
    }

    fun newIdentifier(): String = UUID.randomUUID().toString().replace("-", "")
}

class PackNotReadyException(val stickerCount: Int) :
    IllegalStateException("Un pack exportable necesita entre 3 y 30 stickers; contiene $stickerCount.")

class PackVersionConflictException(val expected: Long, val actual: Long) :
    IllegalStateException("La versión del pack cambió durante la edición.")
