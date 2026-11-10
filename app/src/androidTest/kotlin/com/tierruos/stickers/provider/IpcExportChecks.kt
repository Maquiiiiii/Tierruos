package com.tierruos.stickers.provider

import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.text.Layout
import com.tierruos.stickers.R
import com.tierruos.stickers.data.PackNotReadyException
import com.tierruos.stickers.data.PackVersionConflictException
import com.tierruos.stickers.data.StickerStorageNames
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.export.ExportInProgressException
import com.tierruos.stickers.export.WhatsAppExporter
import com.tierruos.stickers.export.WhatsAppNotInstalledException
import com.tierruos.stickers.export.WhatsAppUnavailableException
import com.tierruos.stickers.security.ImageFixtures
import com.tierruos.stickers.security.ImageSanitizerAndCompressor
import com.tierruos.stickers.security.InvalidImageFormatException
import com.tierruos.stickers.ui.DesignPreviewActivity
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class IpcExportChecks(private val instrumentation: Instrumentation) {
    data class Check(val name: String, val body: suspend () -> Unit)
    private val app get() = instrumentation.targetContext
    private val storage get() = TierruosStorage.get(app)
    private val consumer = ComponentName("com.whatsapp", "com.whatsapp.TestReceiver")
    private val business = ComponentName("com.whatsapp.w4b", "com.whatsapp.w4b.TestReceiver")
    private var stickerBytes: ByteArray? = null
    private var trayBytes: ByteArray? = null

    fun cases(): List<Check> = listOf(
        Check("ipcAllowlistRejectsUntrustedPackagesAndAllowsNullCaller") {
            listOf(null, "com.whatsapp", "com.whatsapp.w4b", "com.tierruos.stickers").forEach {
                StickerProviderContract.requireAllowedCaller(it)
            }
            listOf("", "com.whatsapp.fake", "com.tierruos.stickers.test", "android").forEach {
                expect<SecurityException> { StickerProviderContract.requireAllowedCaller(it) }
            }
            val provider = StickerContentProvider().apply {
                attachInfo(app, ProviderInfo().apply { authority = StickerProviderContract.authority(app) })
            }
            expect<IllegalArgumentException> { provider.query(Uri.parse("content://bad/metadata"), null, null, null, null) }
        },
        Check("ipcMetadataUsesExactWhatsAppColumnsAndCacheFlag") { withPack { id ->
            val pack = requireNotNull(storage.database.getPack(id))
            store().metadata(id, null).use { cursor ->
                check(cursor.columnNames.contentEquals(StickerProviderContract.metadataColumns))
                check(cursor.moveToFirst() && cursor.count == 1)
                check(cursor.getString(0) == id && cursor.getString(3) == pack.trayImageFile)
                check(cursor.getString(10) == pack.imageDataVersion.toString())
                check(cursor.getInt(11) == 0 && cursor.getInt(12) == 0)
                check(cursor.getString(4).isEmpty())
            }
            storage.repository.updatePackMetadata(id, "Pack", "Tierruos", true)
            store().metadata(id, arrayOf("whatsapp_will_not_cache_stickers")).use {
                check(it.moveToFirst() && it.getInt(0) == 1)
            }
        } },
        Check("ipcStickerCursorPreservesOrderAndEmojiCsv") { withPack { id ->
            store().stickers(id, null).use {
                check(it.columnNames.contentEquals(StickerProviderContract.stickerColumns))
                check(it.count == 3 && it.moveToFirst())
                check(it.getString(0) == "s0.webp" && it.getString(1) == "😀,🥑" && it.getString(2).isEmpty())
                check(it.moveToNext() && it.getString(0) == "s1.webp")
            }
        } },
        Check("ipcProjectionRejectsSqlAndUnknownColumns") { withPack { id ->
            store().metadata(id, arrayOf("image_data_version", "sticker_pack_identifier")).use {
                check(it.moveToFirst() && it.getString(1) == id)
            }
            for (columns in listOf(arrayOf("avoid_cache"), arrayOf("*"), emptyArray(),
                arrayOf("sticker_pack_name", "sticker_pack_name"), arrayOf("(SELECT name FROM packs)"))) {
                expect<IllegalArgumentException> { store().metadata(id, columns) }
            }
        } },
        Check("ipcDraftsAndUnknownPacksStayHidden") { withPack(2) { id ->
            store().metadata(id, null).use { check(it.count == 0) }
            store().stickers(id, null).use { check(it.count == 0) }
            store().metadata("MissingPack", null).use { check(it.count == 0) }
            expect<FileNotFoundException> { store().openAsset(id, "s0.webp") }
        } },
        Check("ipcRegisteredProviderServesOwnResolver") { withPack { id ->
            app.contentResolver.query(StickerProviderContract.metadataUri(app, id), null, null, null, null)!!.use {
                check(it.count == 1 && it.moveToFirst() && it.getString(0) == id)
            }
            app.contentResolver.query(StickerProviderContract.stickersUri(app, id), null, null, null, null)!!.use {
                check(it.count == 3)
            }
            // Android clears same-UID MIME identity on this API; no anonymous fallback is allowed.
            check(app.contentResolver.getType(StickerProviderContract.assetUri(app, id, "s0.webp")) == null)
        } },
        Check("ipcProviderRejectsWritesAndCallerSql") { withPack { id ->
            val uri = StickerProviderContract.metadataUri(app, id)
            expect<UnsupportedOperationException> { app.contentResolver.insert(uri, ContentValues()) }
            expect<UnsupportedOperationException> { app.contentResolver.update(uri, ContentValues(), null, null) }
            expect<UnsupportedOperationException> { app.contentResolver.delete(uri, null, null) }
            expect<UnsupportedOperationException> { app.contentResolver.call(uri, "secret", null, null) }
            expect<IllegalArgumentException> { app.contentResolver.query(uri, null, "1=1", null, null) }
            expect<IllegalArgumentException> { app.contentResolver.query(uri.buildUpon().appendQueryParameter("x", "1").build(), null, null, null, null) }
        } },
        Check("ipcAssetsUseReadOnlyDescriptorsAndExactBytes") { withPack { id ->
            val pack = requireNotNull(storage.database.getPack(id))
            for ((name, expected) in listOf(pack.trayImageFile to requireNotNull(trayBytes), "s0.webp" to requireNotNull(stickerBytes))) {
                val uri = StickerProviderContract.assetUri(app, id, name)
                app.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { descriptor ->
                    check(descriptor.startOffset == 0L && descriptor.length == expected.size.toLong())
                    expect<ErrnoException> { Os.write(descriptor.fileDescriptor, byteArrayOf(1), 0, 1) }
                    descriptor.createInputStream().use { check(it.readBytes().contentEquals(expected)) }
                }
            }
            val uri = StickerProviderContract.assetUri(app, id, "s0.webp")
            for (mode in listOf("w", "rw", "rwt", "wa")) {
                expect<IllegalArgumentException> { app.contentResolver.openAssetFileDescriptor(uri, mode) }
            }
        } },
        Check("ipcDoesNotExposeUnregisteredPrivateWebp") { withPack { id ->
            val orphan = File(app.filesDir, "stickers/$id/secret.webp").apply { writeBytes(requireNotNull(stickerBytes)) }
            try { expect<FileNotFoundException> { store().openAsset(id, orphan.name) } }
            finally { check(orphan.delete()) }
        } },
        Check("ipcTraversalAndEncodedSeparatorsAreRejected") { withPack { id ->
            for (name in listOf("../s0.webp", "a/s0.webp", "..", "s0.webp%00", "/s0.webp")) {
                expect<SecurityException> { store().openAsset(id, name) }
            }
            expect<SecurityException> { store().openAsset("../$id", "s0.webp") }
            val attack = Uri.parse("content://${StickerProviderContract.authority(app)}/stickers_asset/$id/..%2Fs0.webp")
            expect<SecurityException> { app.contentResolver.openAssetFileDescriptor(attack, "r") }
        } },
        Check("ipcRejectsSymlinkWithoutReadingItsTarget") { withPack { id ->
            val original = File(app.filesDir, "stickers/$id/s0.webp")
            val outside = File.createTempFile("ipc-outside-", ".webp", app.cacheDir).apply { writeBytes(requireNotNull(stickerBytes)) }
            check(original.delete())
            Os.symlink(outside.path, original.path)
            try { expect<SecurityException> { store().openAsset(id, original.name) } }
            finally { check(original.delete()); check(outside.readBytes().contentEquals(stickerBytes)); check(outside.delete()) }
        } },
        Check("exportRevisionAndExplicitIntentMatchContract") { withPack { id ->
            val before = requireNotNull(storage.database.getPack(id))
            val intent = exporter(listOf(consumer)).prepareIntent(id)
            check(intent.component == consumer && intent.action == WhatsAppExporter.ACTION_ENABLE_STICKER_PACK)
            check(intent.getStringExtra(WhatsAppExporter.EXTRA_PACK_ID) == id)
            check(intent.getStringExtra(WhatsAppExporter.EXTRA_PACK_NAME) == before.name)
            check(intent.getStringExtra(WhatsAppExporter.EXTRA_PACK_AUTHORITY) == StickerProviderContract.authority(app))
            check(requireNotNull(storage.database.getPack(id)).imageDataVersion == before.imageDataVersion + 1)
            check(intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
        } },
        Check("exportChooserContainsOnlyExplicitWhatsAppTargets") { withPack { id ->
            val intent = exporter(listOf(consumer, business, ComponentName("evil.app", "Receiver"))).prepareIntent(id)
            check(intent.action == Intent.ACTION_CHOOSER)
            @Suppress("DEPRECATION")
            val primary = requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            @Suppress("DEPRECATION")
            val others = requireNotNull(intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS))
            check(primary.component == consumer && others.size == 1 && (others[0] as Intent).component == business)
            check(exporter(listOf(consumer, business)).prepareIntent(id, WhatsAppExporter.Target.BUSINESS).component == business)
        } },
        Check("exportMissingDestinationDoesNotChangeVersion") { withPack { id ->
            val before = storage.database.getPack(id)!!.imageDataVersion
            expect<WhatsAppUnavailableException> { exporter(emptyList()).prepareIntent(id) }
            expect<WhatsAppUnavailableException> { exporter(listOf(consumer)).prepareIntent(id, WhatsAppExporter.Target.BUSINESS) }
            check(storage.database.getPack(id)!!.imageDataVersion == before)
        } },
        Check("exportRejectsIncompletePackWithoutRevision") { withPack(2) { id ->
            val before = storage.database.getDraft(id)!!.imageDataVersion
            expect<PackNotReadyException> { exporter(listOf(consumer)).prepareIntent(id) }
            check(storage.database.getDraft(id)!!.imageDataVersion == before)
        } },
        Check("exportChecksTrayDimensionsBeforeRevision") { withPack { id ->
            val before = storage.database.getPack(id)!!
            File(app.filesDir, "stickers/$id/${before.trayImageFile}").writeBytes(requireNotNull(stickerBytes))
            expect<InvalidImageFormatException> { exporter(listOf(consumer)).prepareIntent(id) }
            check(storage.database.getPack(id)!!.imageDataVersion == before.imageDataVersion)
        } },
        Check("exportMissingStickerCannotLaunchOrAdvanceVersion") { withPack { id ->
            val before = storage.database.getPack(id)!!.imageDataVersion
            check(File(app.filesDir, "stickers/$id/s0.webp").delete())
            expect<java.io.IOException> { exporter(listOf(consumer)).prepareIntent(id) }
            check(storage.database.getPack(id)!!.imageDataVersion == before)
        } },
        Check("exportRevisionGuardsStaleValuesAndOverflow") { withPack { id ->
            val before = storage.database.getPack(id)!!.imageDataVersion
            expect<PackVersionConflictException> { storage.database.advanceImageDataVersion(id, before - 1) }
            storage.database.writableDatabase.update("packs", ContentValues().apply { put("image_data_version", Long.MAX_VALUE) },
                "identifier=?", arrayOf(id))
            expect<IllegalStateException> { exporter(listOf(consumer)).prepareIntent(id) }
            check(storage.database.getPack(id)!!.imageDataVersion == Long.MAX_VALUE)
        } },
        Check("exportBusyStateBlocksDuplicateLaunchesAndResets") { withPack { id ->
            withHost { host -> coroutineScope {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val first = WhatsAppExporter(app, storage.repository) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    listOf(consumer)
                }
                val pending = async { first.export(host, id) }
                try {
                    check(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    check(first.isExporting.value)
                    expect<ExportInProgressException> { exporter(listOf(consumer)).export(host, id) }
                } finally { release.countDown() }
                pending.await()
                check(!first.isExporting.value && host.recordedIntent?.component == consumer)
                expect<WhatsAppUnavailableException> { exporter(emptyList()).export(host, id) }
                check(!first.isExporting.value)
            } }
        } },
        Check("exportRealPackageLookupHandlesWhatsAppAbsence") { withPack { id ->
            // The local AOSP test emulator has neither WhatsApp package installed.
            withHost { host -> expect<WhatsAppNotInstalledException> { WhatsAppExporter(app).export(host, id) } }
        } },
        Check("designXmlInflatesWithoutItalicAndRendersAllIcons") {
            withHost { host ->
                withContext(Dispatchers.Main) {
                    val binding = host.binding
                    check(!binding.previewTitle.typeface.isItalic && !binding.previewCounter.typeface.isItalic)
                    check(binding.previewTitle.breakStrategy == Layout.BREAK_STRATEGY_BALANCED)
                    check(binding.previewTitle.hyphenationFrequency == Layout.HYPHENATION_FREQUENCY_NONE)
                    check(binding.previewCounter.fontFeatureSettings == "tnum" && binding.previewMetric.fontFeatureSettings == "tnum")
                    check(kotlin.math.abs(binding.previewMetric.paint.measureText("111111") -
                        binding.previewMetric.paint.measureText("WWWWWW")) < 0.1f) { "KB metric is not monospace" }
                    check(binding.previewCounter.paint.measureText("111111") < binding.previewCounter.paint.measureText("WWWWWW"))
                    val density = app.resources.displayMetrics.density
                    for (id in listOf(R.drawable.ic_create, R.drawable.ic_paste, R.drawable.ic_lasso, R.drawable.ic_eraser,
                        R.drawable.ic_transform, R.drawable.ic_text, R.drawable.ic_outline, R.drawable.ic_undo, R.drawable.ic_export)) {
                        val drawable = requireNotNull(host.getDrawable(id))
                        check(drawable.intrinsicWidth == (24 * density + 0.5f).toInt() && drawable.intrinsicHeight == drawable.intrinsicWidth)
                    }
                }
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val output = File(app.filesDir, "qa/design-system-module5.png").apply { parentFile!!.mkdirs() }
                    output.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { screenshot.recycle() }
            }
        },
    )

    private fun store() = StickerProviderStore(app, storage.database)
    private fun exporter(components: List<ComponentName>) = WhatsAppExporter(app, storage.repository) { components }

    private suspend fun withPack(count: Int = 3, block: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        if (stickerBytes == null) {
            val source = File.createTempFile("ipc-source-", ".png", app.cacheDir)
            try {
                source.writeBytes(ImageFixtures.solidImage())
                val engine = ImageSanitizerAndCompressor(app)
                stickerBytes = engine.createSticker(Uri.fromFile(source)).bytes
                trayBytes = engine.createTrayIcon(Uri.fromFile(source)).bytes
            } finally { check(source.delete()) }
        }
        val id = "IpcTest${StickerStorageNames.newIdentifier()}"
        try {
            storage.repository.createDraft("Pack", "Tierruos", requireNotNull(trayBytes), id)
            repeat(count) { storage.repository.addSticker(id, requireNotNull(stickerBytes), listOf("😀", "🥑"), "s$it.webp") }
            block(id)
        } finally { storage.repository.deletePack(id) }
    }

    private suspend fun withHost(block: suspend (DesignPreviewActivity) -> Unit) {
        val host = instrumentation.startActivitySync(Intent(app, DesignPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DesignPreviewActivity
        try {
            withContext(Dispatchers.Main) { withTimeout(5_000) { while (host.binding.root.width == 0) delay(16) } }
            block(host)
        } finally { withContext(Dispatchers.Main) { host.finish() } }
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) { if (failure is T) return else throw failure }
        error("Expected ${T::class.java.simpleName}")
    }
}
