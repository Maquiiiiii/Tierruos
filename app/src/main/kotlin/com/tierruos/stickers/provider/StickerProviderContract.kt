package com.tierruos.stickers.provider

import android.content.Context
import android.net.Uri

/** Wire names are defined by WhatsApp, independently of the local SQLite column names. */
object StickerProviderContract {
    const val AUTHORITY_SUFFIX = ".stickercontentprovider"
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    internal val metadataColumns = arrayOf(
        "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
        "android_play_store_link", "ios_app_store_link", "sticker_pack_publisher_email",
        "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
        "sticker_pack_license_agreement_website", "image_data_version",
        "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
    )
    internal val stickerColumns = arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")

    fun authority(context: Context): String = context.applicationContext.packageName + AUTHORITY_SUFFIX
    fun metadataUri(context: Context): Uri = Uri.Builder().scheme("content").authority(authority(context))
        .appendPath("metadata").build()
    fun metadataUri(context: Context, identifier: String): Uri = metadataUri(context).buildUpon().appendPath(identifier).build()
    fun stickersUri(context: Context, identifier: String): Uri = metadataUri(context).buildUpon()
        .path(null).appendPath("stickers").appendPath(identifier).build()
    fun assetUri(context: Context, identifier: String, fileName: String): Uri = metadataUri(context).buildUpon()
        .path(null).appendPath("stickers_asset").appendPath(identifier).appendPath(fileName).build()

    internal fun requireAllowedCaller(
        packageName: String?,
        ownPackage: String = "com.tierruos.stickers",
    ) {
        if (packageName != null &&
            packageName != WHATSAPP &&
            packageName != WHATSAPP_BUSINESS &&
            packageName != ownPackage
        ) {
            throw SecurityException("Este proveedor solo admite Tierruos y WhatsApp.")
        }
    }
}
