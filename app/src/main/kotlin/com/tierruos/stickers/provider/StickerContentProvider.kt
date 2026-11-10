package com.tierruos.stickers.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.model.Sticker
import java.io.File

/** Read-only IPC. Authorization runs before resolving a URI, reading SQLite or touching files. */
class StickerContentProvider : ContentProvider() {
    private lateinit var authority: String
    private lateinit var matcher: UriMatcher
    private val store: StickerProviderStore by lazy {
        val app = requireNotNull(context).applicationContext
        StickerProviderStore(app, TierruosStorage.get(app).database)
    }

    override fun onCreate(): Boolean {
        authority = StickerProviderContract.authority(requireNotNull(context))
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", METADATA)
            addURI(authority, "metadata/*", SINGLE_PACK)
            addURI(authority, "stickers/*", STICKERS)
            addURI(authority, "stickers_asset/*/*", ASSET)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        authorize()
        require(selection == null && selectionArgs.isNullOrEmpty() && sortOrder == null) {
            "Este contrato no admite filtros SQL ni ordenación del llamador."
        }
        return when (route(uri)) {
            METADATA -> store.metadata(null, projection)
            SINGLE_PACK -> store.metadata(uri.pathSegments[1], projection)
            STICKERS -> store.stickers(uri.pathSegments[1], projection)
            else -> throw IllegalArgumentException("La ruta no devuelve un cursor.")
        }.apply { setNotificationUri(requireNotNull(context).contentResolver, uri) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        authorize()
        cancellationSignal?.throwIfCanceled()
        val cursor = query(uri, projection, selection, selectionArgs, sortOrder)
        if (cancellationSignal?.isCanceled == true) {
            cursor.close()
            cancellationSignal.throwIfCanceled()
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        authorize()
        return when (route(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.$authority.metadata"
            SINGLE_PACK -> "vnd.android.cursor.item/vnd.$authority.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$authority.stickers"
            ASSET -> "image/webp"
            else -> error("Ruta no admitida.")
        }
    }

    // Android 34+ can request MIME anonymously. Do not disclose pack existence or bypass authorization.
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        authorize()
        require(mode == "r") { "Solo se admite lectura." }
        require(route(uri) == ASSET) { "La ruta no contiene una imagen." }
        return store.openAsset(uri.pathSegments[1], uri.pathSegments[2])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openAssetFile(uri, mode).parcelFileDescriptor

    override fun insert(uri: Uri, values: ContentValues?): Uri = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = unsupported()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = unsupported()

    private fun unsupported(): Nothing {
        authorize()
        throw UnsupportedOperationException("El proveedor de stickers es de solo lectura.")
    }

    private fun authorize() {
        val ownPackage = context?.applicationContext?.packageName ?: "com.tierruos.stickers"
        StickerProviderContract.requireAllowedCaller(callingPackage, ownPackage)
    }

    private fun route(uri: Uri): Int {
        require(uri.scheme == "content" && uri.authority == authority && uri.query == null && uri.fragment == null) {
            "URI de stickers inválido."
        }
        val code = matcher.match(uri)
        require(code != UriMatcher.NO_MATCH) { "Ruta de stickers desconocida." }
        return code
    }

    companion object {
        const val ANIMATED_STICKER_PACK = StickerProviderContract.ANIMATED_STICKER_PACK
        const val STATIC_STICKER_PACK_VALUE = 0
        private const val METADATA = 1
        private const val SINGLE_PACK = 2
        private const val STICKERS = 3
        private const val ASSET = 4

        internal fun formatStickerEmojis(emojis: List<String>): String =
            emojis
                .flatMap { raw -> raw.removeSurrounding("[", "]").split(",") }
                .map { it.trim().removeSurrounding("[", "]").trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { Sticker.DEFAULT_EMOJIS }
                .take(3)
                .joinToString(",")

        internal fun openReadOnlyAssetDescriptor(file: File): AssetFileDescriptor =
            AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                file.length(),
            )

        internal fun newMetadataCursor(columns: Array<String>): MatrixCursor {
            val resolved = if (ANIMATED_STICKER_PACK in columns) columns else columns + ANIMATED_STICKER_PACK
            return MatrixCursor(resolved)
        }
    }
}
