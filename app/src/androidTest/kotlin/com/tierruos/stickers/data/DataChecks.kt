package com.tierruos.stickers.data

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.system.Os
import com.tierruos.stickers.data.db.TierruosDatabaseHelper
import com.tierruos.stickers.data.model.Sticker
import com.tierruos.stickers.data.model.StickerPack
import com.tierruos.stickers.data.model.StickerPackDraft
import com.tierruos.stickers.data.repository.StickerFileRepository
import com.tierruos.stickers.security.ImageFixtures
import com.tierruos.stickers.security.ImageSanitizerAndCompressor
import com.tierruos.stickers.security.ImageTooLargeException
import com.tierruos.stickers.security.InvalidImageFormatException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal class DataChecks(private val instrumentation: Instrumentation) {
    data class Check(val name: String, val body: suspend () -> Unit)
    private var stickerBytes: ByteArray? = null
    private var trayBytes: ByteArray? = null

    fun cases(): List<Check> = listOf(
        Check("dataModelsEnforceExportCountsAndEmojiLimit") {
            val sticker = Sticker("one.webp", 0.1, listOf("😀"))
            val draft = StickerPackDraft("A1", "Pack", "Tierruos", "tray.webp")
            expect<PackNotReadyException> { draft.toStickerPack() }
            expect<IllegalArgumentException> { StickerPack("A1", "Pack", "Tierruos", "tray.webp", listOf(sticker)) }
            expect<IllegalArgumentException> { Sticker("one.webp", 0.1, List(4) { "😀" }) }
            expect<IllegalArgumentException> { Sticker("one.webp", Double.NaN) }
            expect<IllegalArgumentException> { Sticker("one.webp", 98.0) }
            expect<IllegalArgumentException> { Sticker("one.webp", 0.1005) }
            val valid = completePack()
            check(valid.stickers.size == 3 && valid.imageDataVersion == 1L)
        },
        Check("dataPackCrudUsesBoundValues") { withStore { s ->
            val attack = "O'Brien'); DROP TABLE packs; --"
            val draft = s.db.createDraft(StickerPackDraft("A1", attack, attack, "tray.webp"))
            check(s.db.getDraft("A1")?.name == attack)
            val updated = s.db.updatePackMetadata("A1", "Changed", attack, draft.trayImageFile, true, 1)
            check(updated.imageDataVersion == 2L && updated.publisher == attack && updated.avoidCache)
            check(s.db.listPacks().size == 1)
            check(s.db.deletePack("A1") && !s.db.deletePack("A1"))
        } },
        Check("dataFullPackAndStickerCrudPreservesOrder") { withStore { s ->
            val created = s.db.createPack(completePack())
            check(s.db.getPack(created.identifier)?.stickers?.map { it.imageFileName } == listOf("a.webp", "b.webp", "c.webp"))
            val changed = s.db.updatePack(created.copy(name = "Edited", stickers = created.stickers.reversed()))
            check(changed.imageDataVersion == 2L && changed.stickers.first().imageFileName == "c.webp")
            s.db.updateSticker("A1", "a.webp", Sticker("a.webp", 0.2, listOf("🥑")), 2)
            check(s.db.getSticker("A1", "a.webp")?.emojis == listOf("🥑"))
            check(s.db.deleteSticker("A1", "b.webp", 3))
            check(s.db.getDraft("A1")?.imageDataVersion == 4L)
            expect<PackNotReadyException> { s.db.getPack("A1") }
        } },
        Check("dataForeignKeysCascadeAndQueueDeletes") { withStore { s ->
            s.db.createPack(completePack())
            s.db.writableDatabase.rawQuery("PRAGMA foreign_keys", null).use {
                check(it.moveToFirst() && it.getInt(0) == 1)
            }
            s.db.writableDatabase.delete("packs", "identifier=?", arrayOf("A1"))
            check(s.db.listStickers("A1").isEmpty())
            check(s.db.pendingDeletions().size == 5)
            val orphan = ContentValues().apply {
                put("pack_identifier", "Missing"); put("image_file_name", "x.webp"); put("size_bytes", 100)
            }
            expect<SQLiteConstraintException> { s.db.writableDatabase.insertOrThrow("stickers", null, orphan) }
        } },
        Check("dataDatabaseRejects31stSticker") { withStore { s ->
            s.db.createDraft(StickerPackDraft("A1", "Pack", "Tierruos", "tray.webp"))
            repeat(30) { s.db.createSticker("A1", Sticker("s$it.webp", 0.1)) }
            expect<SQLiteConstraintException> { s.db.createSticker("A1", Sticker("excess.webp", 0.1)) }
            check(s.db.getPack("A1")?.stickers?.size == 30)
            check(s.db.getDraft("A1")?.imageDataVersion == 31L)
        } },
        Check("dataVersionConflictRollsBackNewFile") { withStore { s ->
            s.create()
            s.repo.updatePackMetadata("A1", "Changed", "Tierruos", false, 1)
            expect<PackVersionConflictException> { s.repo.addSticker("A1", s.sticker, imageFileName = "conflict.webp", expectedVersion = 1) }
            check(!s.file("conflict.webp").exists())
            check(s.db.getDraft("A1")?.stickers?.isEmpty() == true)
            check(s.db.getDraft("A1")?.imageDataVersion == 2L)
        } },
        Check("dataSavesPrivateWebpAndExactByteMetric") { withStore { s ->
            s.create()
            val sticker = s.repo.addSticker("A1", s.sticker, listOf("😀", "👩🏽‍💻"), "hello_01-A.webp")
            val file = s.repo.getStickerFile("A1", sticker.imageFileName)
            check(file.canonicalFile == s.file(sticker.imageFileName).canonicalFile)
            check(sticker.sizeInBytes == file.length().toInt())
            check(sticker.sizeInKb == file.length() / 1_000.0)
            check(s.repo.readSticker("A1", sticker.imageFileName).contentEquals(s.sticker))
            check(s.repo.readTrayImage("A1").contentEquals(s.tray))
        } },
        Check("dataRejectsTraversalAndNonAlphanumericIds") { withStore { s ->
            for (id in listOf("../escape", "a/b", "a-b", "%2e%2e", "", "á")) {
                expect<SecurityException> { s.repo.createDraft("Pack", "Tierruos", s.tray, id) }
            }
            s.create()
            for (name in listOf("../outside.webp", "/outside.webp", "a/b.webp", "a\\b.webp", "a.webp.webp", "a.WEBP", "a..webp")) {
                expect<SecurityException> { s.repo.addSticker("A1", s.sticker, imageFileName = name) }
            }
            check(s.db.getDraft("A1")?.stickers?.isEmpty() == true)
        } },
        Check("dataRejectsSymlinkWithoutTouchingTarget") { withStore { s ->
            s.create()
            val outside = File(s.directory, "outside.bin").apply { writeText("keep") }
            val link = s.file("link.webp")
            Os.symlink(outside.path, link.path)
            try {
                expect<SecurityException> { s.repo.addSticker("A1", s.sticker, imageFileName = "link.webp") }
                expect<SecurityException> { s.repo.removeOrphanedFiles() }
                check(outside.readText() == "keep")
            } finally { check(link.delete()) }
        } },
        Check("dataStickerDeletionRemovesFileAndBumpsVersion") { withStore { s ->
            s.create()
            s.repo.addSticker("A1", s.sticker, imageFileName = "delete.webp")
            check(s.repo.deleteSticker("A1", "delete.webp", 2))
            check(!s.file("delete.webp").exists())
            check(s.db.getDraft("A1")?.imageDataVersion == 3L)
            check(!s.repo.deleteSticker("A1", "delete.webp"))
        } },
        Check("dataPackDeletionCascadesAndCleansOrphans") { withStore { s ->
            s.create(3)
            s.file("orphan.webp").writeBytes(s.sticker)
            check(s.repo.deletePack("A1"))
            check(!s.packDirectory.exists())
            check(s.db.listStickers("A1").isEmpty() && s.db.pendingDeletions().isEmpty())
            check(!s.repo.deletePack("A1"))
        } },
        Check("dataReplacingTrayAndStickerRetiresOldNames") { withStore { s ->
            s.create()
            val trayBefore = requireNotNull(s.db.getDraft("A1")).trayImageFile
            s.repo.addSticker("A1", s.sticker, imageFileName = "old.webp")
            val changed = s.repo.replaceSticker("A1", "old.webp", s.sticker, listOf("🌱"), expectedVersion = 2)
            check(!s.file("old.webp").exists() && s.file(changed.imageFileName).isFile)
            val pack = s.repo.replaceTrayImage("A1", s.tray, 3)
            check(!s.file(trayBefore).exists() && s.file(pack.trayImageFile).isFile)
            s.repo.updateStickerEmojis("A1", changed.imageFileName, listOf("😀", "🌱", "🥑"), 4)
            check(s.db.getDraft("A1")?.imageDataVersion == 5L)
        } },
        Check("dataNeverOverwritesAnActiveFile") { withStore { s ->
            s.create()
            s.repo.addSticker("A1", s.sticker, imageFileName = "active.webp")
            expect<IOException> { s.repo.addSticker("A1", s.sticker, imageFileName = "active.webp") }
            check(s.repo.readSticker("A1", "active.webp").contentEquals(s.sticker))
            check(s.db.getDraft("A1")?.imageDataVersion == 2L)
        } },
        Check("dataSqlOnlyDeleteIsRecoveredByNextRepository") { withStore { s ->
            s.create()
            s.repo.addSticker("A1", s.sticker, imageFileName = "queued.webp")
            s.db.deleteSticker("A1", "queued.webp")
            check(s.file("queued.webp").exists() && s.db.pendingDeletions().isNotEmpty())
            StickerFileRepository(s.context, s.db).getDraft("A1")
            check(!s.file("queued.webp").exists() && s.db.pendingDeletions().isEmpty())
        } },
        Check("dataStartupSweepsInterruptedWritesAndUnknownPacks") { withStore { s ->
            s.create()
            s.repo.addSticker("A1", s.sticker, imageFileName = "keep.webp")
            s.file("orphan.webp").writeBytes(s.sticker)
            s.file(".write-interrupted.tmp").writeBytes(byteArrayOf(1, 2))
            val ghost = File(s.context.filesDir, "stickers/Ghost1").apply { check(mkdir()) }
            File(ghost, "ghost.webp").writeBytes(s.sticker)
            StickerFileRepository(s.context, s.db).removeOrphanedFiles()
            check(!s.file("orphan.webp").exists() && !s.file(".write-interrupted.tmp").exists() && !ghost.exists())
            check(s.file("keep.webp").isFile)
        } },
        Check("dataQueuedDeleteDoesNotRemoveReinsertedReferences") { withStore { s ->
            s.create(3)
            val pack = requireNotNull(s.db.getPack("A1"))
            s.db.updatePack(pack.copy(stickers = pack.stickers.reversed(), avoidCache = true))
            s.repo.removeOrphanedFiles()
            val result = s.repo.getExportablePack("A1")
            check(result.stickers.map { it.imageFileName } == listOf("s2.webp", "s1.webp", "s0.webp"))
            check(result.avoidCache && result.imageDataVersion == 5L)
        } },
        Check("dataMissingFileBlocksExportWithoutDeletingMetadata") { withStore { s ->
            s.create(3)
            check(s.repo.getExportablePack("A1").stickers.size == 3)
            check(s.file("s0.webp").delete())
            expect<IOException> { s.repo.getExportablePack("A1") }
            check(s.db.listStickers("A1").size == 3)
        } },
        Check("dataRejectsWrongDimensionsAndOversizedBytes") { withStore { s ->
            s.create()
            expect<InvalidImageFormatException> { s.repo.addSticker("A1", s.tray) }
            expect<InvalidImageFormatException> { s.repo.addSticker("A1", ImageFixtures.solidImage()) }
            expect<ImageTooLargeException> { s.repo.addSticker("A1", ByteArray(98_000)) }
            check(s.db.listStickers("A1").isEmpty() && s.db.getDraft("A1")?.imageDataVersion == 1L)
        } },
        Check("dataConcurrentInstancesDetectStaleVersionAndLeaveNoOrphan") { withStore { s ->
            s.create()
            val secondHelper = TierruosDatabaseHelper(s.context, s.databaseName)
            try {
                val second = StickerFileRepository(s.context, secondHelper)
                val successes = coroutineScope {
                    listOf(s.repo, second).mapIndexed { index, repository -> async {
                        try {
                            repository.addSticker("A1", s.sticker, imageFileName = "concurrent$index.webp", expectedVersion = 1)
                            true
                        } catch (_: PackVersionConflictException) { false }
                    } }.awaitAll()
                }
                check(successes.count { it } == 1)
                check(s.db.listStickers("A1").size == 1 && s.packDirectory.listFiles()?.size == 2)
            } finally { secondHelper.close() }
        } },
        Check("dataSurvivesDatabaseCloseAndReopen") { withStore { s ->
            s.create(3)
            s.db.close()
            val reopened = TierruosDatabaseHelper(s.context, s.databaseName)
            try {
                val result = StickerFileRepository(s.context, reopened).getExportablePack("A1")
                check(result.identifier == "A1" && result.stickers.size == 3 && result.imageDataVersion == 4L)
            } finally { reopened.close() }
        } },
        Check("dataMissingDatabasePreservesExistingImages") { withStore { s ->
            s.create(3)
            s.db.close()
            check(instrumentation.targetContext.deleteDatabase(s.databaseName))
            val reopened = TierruosDatabaseHelper(s.context, s.databaseName)
            try {
                expect<IOException> { s.repo.getDraft("A1") }
                expect<IOException> { StickerFileRepository(s.context, reopened).getDraft("A1") }
                check(s.file("s0.webp").isFile && s.packDirectory.listFiles()?.size == 4)
                check(!File(reopened.storageIdentity).exists())
            } finally { reopened.close() }
        } },
        Check("dataCorruptDatabaseDoesNotTriggerDestructiveReset") { withStore { s ->
            s.create(3)
            s.db.close()
            val databaseFile = File(s.db.storageIdentity)
            RandomAccessFile(databaseFile, "rw").use { it.write(ByteArray(4096) { 0x55.toByte() }); it.fd.sync() }
            val reopened = TierruosDatabaseHelper(s.context, s.databaseName)
            try {
                expect<SQLiteException> { StickerFileRepository(s.context, reopened).getDraft("A1") }
                check(s.file("s0.webp").isFile && s.packDirectory.listFiles()?.size == 4)
                check(databaseFile.isFile && databaseFile.inputStream().use { it.read() } == 0x55)
            } finally { reopened.close() }
        } },
    )

    private class Store(
        val context: Context,
        val directory: File,
        val databaseName: String,
        val db: TierruosDatabaseHelper,
        val repo: StickerFileRepository,
        val sticker: ByteArray,
        val tray: ByteArray,
    ) {
        val packDirectory: File get() = File(context.filesDir, "stickers/A1")
        fun file(name: String): File = File(packDirectory, name)
        suspend fun create(count: Int = 0) {
            repo.createDraft("Pack", "Tierruos", tray, "A1")
            repeat(count) { repo.addSticker("A1", sticker, imageFileName = "s$it.webp") }
        }
    }

    private suspend fun withStore(block: suspend (Store) -> Unit) = withContext(Dispatchers.IO) {
        val base = instrumentation.targetContext
        if (stickerBytes == null) {
            val source = File.createTempFile("data-source-", ".png", base.cacheDir)
            try {
                source.writeBytes(ImageFixtures.solidImage())
                val engine = ImageSanitizerAndCompressor(base)
                stickerBytes = engine.createSticker(Uri.fromFile(source)).bytes
                trayBytes = engine.createTrayIcon(Uri.fromFile(source)).bytes
            } finally { check(source.delete()) }
        }
        val token = StickerStorageNames.newIdentifier()
        val directory = File(base.cacheDir, "data-tests-$token").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { check(isDirectory || mkdirs()) }
        }
        val name = "data_$token.db"
        val helper = TierruosDatabaseHelper(context, name)
        try {
            block(Store(context, directory, name, helper, StickerFileRepository(context, helper),
                requireNotNull(stickerBytes), requireNotNull(trayBytes)))
        } finally {
            helper.close()
            base.deleteDatabase(name)
            check(directory.canonicalPath.startsWith(base.cacheDir.canonicalPath + "/data-tests-"))
            check(directory.deleteRecursively())
        }
    }

    private fun completePack(): StickerPack = StickerPack("A1", "Pack", "Tierruos", "tray.webp",
        listOf("a.webp", "b.webp", "c.webp").map { Sticker(it, 0.1) })

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        error("Expected ${T::class.java.simpleName}")
    }
}
