package com.tierruos.stickers.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.tierruos.stickers.data.PackVersionConflictException
import com.tierruos.stickers.data.StickerStorageCoordinator
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPack
import com.tierruos.stickers.data.model.StickerPackDraft
import kotlin.concurrent.withLock

/** Synchronous SQL API: use on IO/Binder threads. Application writes go through the file repository. */
class TierruosDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION, PRESERVE_CORRUPTION) {
    init {
        require(Regex("^[a-zA-Z0-9_-]+\\.db$").matches(databaseName))
        setWriteAheadLoggingEnabled(true)
    }
    internal val filesDirectory = context.applicationContext.filesDir
    internal val storageIdentity = context.applicationContext.getDatabasePath(databaseName).absolutePath

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE packs (
                identifier TEXT PRIMARY KEY NOT NULL
                    CHECK(length(identifier) BETWEEN 1 AND 128 AND identifier NOT GLOB '*[^a-zA-Z0-9]*'),
                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 128),
                publisher TEXT NOT NULL CHECK(length(publisher) BETWEEN 1 AND 128),
                tray_image_file TEXT NOT NULL CHECK(
                    length(tray_image_file) BETWEEN 6 AND 128 AND substr(tray_image_file,-5)='.webp'
                    AND substr(tray_image_file,1,length(tray_image_file)-5) NOT GLOB '*[^a-zA-Z0-9_-]*'),
                image_data_version INTEGER NOT NULL CHECK(image_data_version > 0),
                avoid_cache INTEGER NOT NULL CHECK(avoid_cache IN (0,1))
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE stickers (
                _id INTEGER PRIMARY KEY,
                pack_identifier TEXT NOT NULL,
                image_file_name TEXT NOT NULL CHECK(
                    length(image_file_name) BETWEEN 6 AND 128 AND substr(image_file_name,-5)='.webp'
                    AND substr(image_file_name,1,length(image_file_name)-5) NOT GLOB '*[^a-zA-Z0-9_-]*'),
                size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 97999),
                emoji_1 TEXT CHECK(emoji_1 IS NULL OR length(emoji_1) BETWEEN 1 AND 64),
                emoji_2 TEXT CHECK(emoji_2 IS NULL OR length(emoji_2) BETWEEN 1 AND 64),
                emoji_3 TEXT CHECK(emoji_3 IS NULL OR length(emoji_3) BETWEEN 1 AND 64),
                CHECK(emoji_1 IS NOT NULL OR emoji_2 IS NULL),
                CHECK(emoji_2 IS NOT NULL OR emoji_3 IS NULL),
                UNIQUE(pack_identifier,image_file_name),
                FOREIGN KEY(pack_identifier) REFERENCES packs(identifier) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX stickers_pack_order ON stickers(pack_identifier,_id)")
        // No foreign key: deletion work must outlive the pack that scheduled it.
        db.execSQL("""
            CREATE TABLE pending_file_deletions (
                pack_identifier TEXT NOT NULL,
                image_file_name TEXT NOT NULL,
                PRIMARY KEY(pack_identifier,image_file_name)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER stickers_limit BEFORE INSERT ON stickers
            WHEN (SELECT COUNT(*) FROM stickers WHERE pack_identifier=NEW.pack_identifier) >= 30
            BEGIN SELECT RAISE(ABORT,'A pack cannot contain more than 30 stickers'); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER sticker_tray_collision BEFORE INSERT ON stickers
            WHEN NEW.image_file_name=(SELECT tray_image_file FROM packs WHERE identifier=NEW.pack_identifier)
            BEGIN SELECT RAISE(ABORT,'Tray and sticker filenames must differ'); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER sticker_update_tray_collision BEFORE UPDATE OF image_file_name ON stickers
            WHEN NEW.image_file_name=(SELECT tray_image_file FROM packs WHERE identifier=NEW.pack_identifier)
            BEGIN SELECT RAISE(ABORT,'Tray and sticker filenames must differ'); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER pack_update_tray_collision BEFORE UPDATE OF tray_image_file ON packs
            WHEN EXISTS(SELECT 1 FROM stickers WHERE pack_identifier=NEW.identifier AND image_file_name=NEW.tray_image_file)
            BEGIN SELECT RAISE(ABORT,'Tray and sticker filenames must differ'); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER queue_sticker_delete AFTER DELETE ON stickers
            BEGIN INSERT OR IGNORE INTO pending_file_deletions VALUES(OLD.pack_identifier,OLD.image_file_name); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER queue_sticker_replace AFTER UPDATE OF image_file_name ON stickers
            WHEN OLD.image_file_name!=NEW.image_file_name
            BEGIN INSERT OR IGNORE INTO pending_file_deletions VALUES(OLD.pack_identifier,OLD.image_file_name); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER queue_tray_replace AFTER UPDATE OF tray_image_file ON packs
            WHEN OLD.tray_image_file!=NEW.tray_image_file
            BEGIN INSERT OR IGNORE INTO pending_file_deletions VALUES(OLD.identifier,OLD.tray_image_file); END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER queue_pack_delete AFTER DELETE ON packs
            BEGIN
                INSERT OR IGNORE INTO pending_file_deletions VALUES(OLD.identifier,OLD.tray_image_file);
                INSERT OR IGNORE INTO pending_file_deletions VALUES(OLD.identifier,'');
            END
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("No existe una migración de esquema $oldVersion a $newVersion; se conservan los datos.")
    }

    fun createPack(pack: StickerPack): StickerPack = transaction { db ->
        val value = pack.snapshot()
        insertPackRow(db, StickerPackDraft(value.identifier, value.name, value.publisher,
            value.trayImageFile, value.stickers, value.imageDataVersion, value.avoidCache))
        value.stickers.forEach { insertStickerRow(db, value.identifier, it) }
        requireNotNull(readDraft(db, value.identifier)).toStickerPack()
    }

    fun createDraft(pack: StickerPackDraft): StickerPackDraft = transaction { db ->
        val value = pack.snapshot()
        insertPackRow(db, value)
        value.stickers.forEach { insertStickerRow(db, value.identifier, it) }
        requireNotNull(readDraft(db, value.identifier))
    }

    fun getPack(identifier: String): StickerPack? = getDraft(identifier)?.toStickerPack()

    fun getDraft(identifier: String): StickerPackDraft? = read { db ->
        StickerStorageNames.requireIdentifier(identifier)
        readDraft(db, identifier)
    }

    fun listPacks(limit: Int = 100, offset: Int = 0): List<StickerPackDraft> = read { db ->
        require(limit in 1..200 && offset >= 0)
        db.rawQuery("SELECT identifier FROM packs ORDER BY rowid DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(requireNotNull(readDraft(db, cursor.getString(0)))) }
        }
    }

    /** Replaces the complete ordered sticker list; a stale version cannot overwrite newer work. */
    fun updatePack(pack: StickerPack): StickerPack = transaction { db ->
        val value = pack.snapshot()
        val current = requireDraft(db, value.identifier)
        checkVersion(current, value.imageDataVersion)
        db.delete("stickers", "pack_identifier=?", arrayOf(value.identifier))
        val values = packValues(value.name, value.publisher, value.trayImageFile, value.avoidCache)
        db.update("packs", values, "identifier=?", arrayOf(value.identifier))
        value.stickers.forEach { insertStickerRow(db, value.identifier, it) }
        bumpVersion(db, current)
        requireNotNull(readDraft(db, value.identifier)).toStickerPack()
    }

    fun updatePackMetadata(
        identifier: String,
        name: String,
        publisher: String,
        trayImageFile: String,
        avoidCache: Boolean,
        expectedVersion: Long? = null,
    ): StickerPackDraft = transaction { db ->
        val current = requireDraft(db, identifier)
        checkVersion(current, expectedVersion)
        StickerPackDraft(identifier, name, publisher, trayImageFile, current.stickers,
            current.imageDataVersion, avoidCache).snapshot()
        db.update("packs", packValues(name, publisher, trayImageFile, avoidCache),
            "identifier=?", arrayOf(identifier))
        bumpVersion(db, current)
        requireNotNull(readDraft(db, identifier))
    }

    /** Export revision: validates the complete pack and increments exactly once, atomically. */
    fun advanceImageDataVersion(identifier: String, expectedVersion: Long): StickerPack = transaction { db ->
        val current = requireDraft(db, identifier)
        checkVersion(current, expectedVersion)
        current.toStickerPack()
        bumpVersion(db, current)
        requireNotNull(readDraft(db, identifier)).toStickerPack()
    }

    fun deletePack(identifier: String, expectedVersion: Long? = null): Boolean = transaction { db ->
        StickerStorageNames.requireIdentifier(identifier)
        val current = readDraft(db, identifier) ?: return@transaction false
        checkVersion(current, expectedVersion)
        db.delete("packs", "identifier=?", arrayOf(identifier)) == 1
    }

    fun createSticker(identifier: String, sticker: Sticker, expectedVersion: Long? = null): Sticker =
        transaction { db ->
            val current = requireDraft(db, identifier)
            checkVersion(current, expectedVersion)
            val value = sticker.snapshot()
            insertStickerRow(db, identifier, value)
            bumpVersion(db, current)
            value
        }

    fun getSticker(identifier: String, fileName: String): Sticker? = read { db ->
        StickerStorageNames.requireIdentifier(identifier)
        StickerStorageNames.requireImageFileName(fileName)
        readStickers(db, identifier).firstOrNull { it.imageFileName == fileName }
    }

    fun listStickers(identifier: String): List<Sticker> = read { db ->
        StickerStorageNames.requireIdentifier(identifier)
        readStickers(db, identifier)
    }

    fun updateSticker(
        identifier: String,
        previousFileName: String,
        sticker: Sticker,
        expectedVersion: Long? = null,
    ): Sticker = transaction { db ->
        StickerStorageNames.requireImageFileName(previousFileName)
        val current = requireDraft(db, identifier)
        checkVersion(current, expectedVersion)
        val value = sticker.snapshot()
        val count = db.update("stickers", stickerValues(value),
            "pack_identifier=? AND image_file_name=?", arrayOf(identifier, previousFileName))
        if (count != 1) throw NoSuchElementException("El sticker ya no existe.")
        bumpVersion(db, current)
        value
    }

    fun deleteSticker(identifier: String, fileName: String, expectedVersion: Long? = null): Boolean =
        transaction { db ->
            StickerStorageNames.requireImageFileName(fileName)
            val current = requireDraft(db, identifier)
            checkVersion(current, expectedVersion)
            val count = db.delete("stickers", "pack_identifier=? AND image_file_name=?",
                arrayOf(identifier, fileName))
            if (count > 0) bumpVersion(db, current)
            count == 1
        }

    internal data class PendingDeletion(val identifier: String, val fileName: String)

    internal fun pendingDeletions(): List<PendingDeletion> = read { db ->
        db.rawQuery("SELECT pack_identifier,image_file_name FROM pending_file_deletions LIMIT ?",
            arrayOf("128")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PendingDeletion(cursor.getString(0), cursor.getString(1))) }
        }
    }

    internal fun acknowledgeDeletion(value: PendingDeletion) = read { db ->
        db.delete("pending_file_deletions", "pack_identifier=? AND image_file_name=?",
            arrayOf(value.identifier, value.fileName))
        Unit
    }

    internal fun referencedFiles(identifier: String): Set<String> = read { db ->
        readDraft(db, identifier)?.let { pack ->
            pack.stickers.mapTo(mutableSetOf(pack.trayImageFile)) { it.imageFileName }
        } ?: emptySet()
    }

    override fun close() = StickerStorageCoordinator.lock.withLock { super.close() }

    private fun insertPackRow(db: SQLiteDatabase, value: StickerPackDraft) {
        val values = packValues(value.name, value.publisher, value.trayImageFile, value.avoidCache).apply {
            put("identifier", value.identifier)
            put("image_data_version", value.imageDataVersion)
        }
        db.insertOrThrow("packs", null, values)
    }

    private fun insertStickerRow(db: SQLiteDatabase, identifier: String, value: Sticker) {
        val values = stickerValues(value).apply { put("pack_identifier", identifier) }
        db.insertOrThrow("stickers", null, values)
    }

    private fun packValues(name: String, publisher: String, tray: String, avoidCache: Boolean) = ContentValues().apply {
        put("name", name); put("publisher", publisher); put("tray_image_file", tray)
        put("avoid_cache", if (avoidCache) 1 else 0)
    }

    private fun stickerValues(value: Sticker) = ContentValues().apply {
        put("image_file_name", value.imageFileName)
        put("size_bytes", value.sizeInBytes)
        for (i in 0..2) {
            val emoji = value.emojis.getOrNull(i)
            if (emoji == null) putNull("emoji_${i + 1}") else put("emoji_${i + 1}", emoji)
        }
    }

    private fun requireDraft(db: SQLiteDatabase, identifier: String): StickerPackDraft {
        StickerStorageNames.requireIdentifier(identifier)
        return readDraft(db, identifier) ?: throw NoSuchElementException("El pack ya no existe.")
    }

    private fun checkVersion(current: StickerPackDraft, expected: Long?) {
        if (expected != null && expected != current.imageDataVersion) {
            throw PackVersionConflictException(expected, current.imageDataVersion)
        }
    }

    private fun bumpVersion(db: SQLiteDatabase, current: StickerPackDraft) {
        check(current.imageDataVersion < Long.MAX_VALUE) { "La versión del pack alcanzó su límite." }
        val values = ContentValues().apply { put("image_data_version", current.imageDataVersion + 1) }
        check(db.update("packs", values, "identifier=? AND image_data_version=?",
            arrayOf(current.identifier, current.imageDataVersion.toString())) == 1)
    }

    private fun readDraft(db: SQLiteDatabase, identifier: String): StickerPackDraft? =
        db.query("packs", arrayOf("identifier", "name", "publisher", "tray_image_file", "image_data_version", "avoid_cache"),
            "identifier=?", arrayOf(identifier), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else StickerPackDraft(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                readStickers(db, identifier), cursor.getLong(4), cursor.getInt(5) == 1).snapshot()
        }

    private fun readStickers(db: SQLiteDatabase, identifier: String): List<Sticker> =
        db.query("stickers", arrayOf("image_file_name", "size_bytes", "emoji_1", "emoji_2", "emoji_3"),
            "pack_identifier=?", arrayOf(identifier), null, null, "_id ASC").use { cursor ->
            java.util.Collections.unmodifiableList(buildList {
                while (cursor.moveToNext()) add(cursor.sticker())
            })
        }

    private fun Cursor.sticker(): Sticker = Sticker(getString(0), getLong(1) / 1_000.0,
        buildList { for (column in 2..4) if (!isNull(column)) add(getString(column)) }).snapshot()

    private inline fun <T> read(block: (SQLiteDatabase) -> T): T =
        StickerStorageCoordinator.lock.withLock { block(readableDatabase) }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T =
        StickerStorageCoordinator.lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val result = block(db)
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        }

    companion object {
        const val DATABASE_NAME = "tierruos.db"
        private const val DATABASE_VERSION = 1
        private val PRESERVE_CORRUPTION = DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("La base de datos está dañada; se conservan la base y sus imágenes.")
        }
    }
}
