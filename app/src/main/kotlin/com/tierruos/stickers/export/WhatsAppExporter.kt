package com.tierruos.stickers.export

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.tierruos.stickers.R
import com.tierruos.stickers.data.TierruosStorage
import com.tierruos.stickers.data.repository.StickerFileRepository
import com.tierruos.stickers.provider.StickerProviderContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class WhatsAppNotInstalledException : IllegalStateException("Instala WhatsApp o WhatsApp Business para añadir el pack.")
class WhatsAppUnavailableException : IllegalStateException("WhatsApp no puede recibir stickers. Actualiza o habilita la aplicación.")
class ExportInProgressException : IllegalStateException("Ya hay una exportación en curso.")

/** Opens WhatsApp's confirmation UI; a successful launch does not mean that the user added the pack. */
class WhatsAppExporter internal constructor(
    context: Context,
    private val repository: StickerFileRepository,
    private val destinations: () -> List<ComponentName>,
) {
    constructor(context: Context) : this(context, TierruosStorage.get(context).repository,
        { resolveDestinations(context.applicationContext) })

    enum class Target(val packageName: String) {
        WHATSAPP(StickerProviderContract.WHATSAPP), BUSINESS(StickerProviderContract.WHATSAPP_BUSINESS)
    }

    private val app = context.applicationContext
    val isExporting: StateFlow<Boolean> get() = exportingState

    /** Collect isExporting on the UI to display progress and disable the primary CTA. */
    suspend fun export(activity: Activity, identifier: String, target: Target? = null,
        launcher: ((Intent) -> Unit)? = null) {
        if (!exportMutex.tryLock()) throw ExportInProgressException()
        mutableExporting.value = true
        try {
            withContext(Dispatchers.Main.immediate) { checkActivity(activity) }
            val intent = prepareIntent(identifier, target)
            withContext(Dispatchers.Main.immediate) {
                checkActivity(activity)
                if (launcher == null) activity.startActivity(intent) else launcher(intent)
            }
        } finally {
            mutableExporting.value = false
            exportMutex.unlock()
        }
    }

    internal suspend fun prepareIntent(identifier: String, target: Target? = null): Intent = withContext(Dispatchers.IO) {
        // Check installed, enabled handlers before committing an export revision.
        val available = destinations().filter {
            it.packageName == StickerProviderContract.WHATSAPP || it.packageName == StickerProviderContract.WHATSAPP_BUSINESS
        }.filter { target == null || it.packageName == target.packageName }.distinctBy { it.packageName }
        if (available.isEmpty()) throw WhatsAppUnavailableException()
        val pack = repository.preparePackForExport(identifier)
        val intents = available.map { component ->
            Intent(ACTION_ENABLE_STICKER_PACK).setComponent(component)
                .putExtra(EXTRA_PACK_ID, pack.identifier)
                .putExtra(EXTRA_PACK_AUTHORITY, StickerProviderContract.authority(app))
                .putExtra(EXTRA_PACK_NAME, pack.name)
        }
        app.contentResolver.notifyChange(StickerProviderContract.metadataUri(app), null)
        app.contentResolver.notifyChange(StickerProviderContract.metadataUri(app, pack.identifier), null)
        app.contentResolver.notifyChange(StickerProviderContract.stickersUri(app, pack.identifier), null)
        if (intents.size == 1) intents.single() else Intent.createChooser(intents.first(), app.getString(R.string.export_choose_whatsapp))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
    }

    private fun checkActivity(activity: Activity) {
        check(!activity.isFinishing && !activity.isDestroyed) { "La pantalla de exportación ya no está disponible." }
        require(activity.applicationContext.packageName == app.packageName)
    }

    companion object {
        const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        const val EXTRA_PACK_ID = "sticker_pack_id"
        const val EXTRA_PACK_AUTHORITY = "sticker_pack_authority"
        const val EXTRA_PACK_NAME = "sticker_pack_name"
        private val exportMutex = Mutex()
        private val mutableExporting = MutableStateFlow(false)
        private val exportingState = mutableExporting.asStateFlow()

        @Suppress("DEPRECATION")
        private fun resolveDestinations(context: Context): List<ComponentName> {
            val manager = context.packageManager
            val packages = listOf(StickerProviderContract.WHATSAPP, StickerProviderContract.WHATSAPP_BUSINESS)
            var installed = false
            val result = packages.mapNotNull { name ->
                val application = try { manager.getApplicationInfo(name, 0) }
                catch (_: PackageManager.NameNotFoundException) { return@mapNotNull null }
                installed = true
                if (!application.enabled) return@mapNotNull null
                val intent = Intent(ACTION_ENABLE_STICKER_PACK).setPackage(name)
                manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull {
                    val info = it.activityInfo
                    info != null && info.packageName == name && info.exported && info.enabled &&
                        (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
                }?.activityInfo?.let { ComponentName(it.packageName, it.name) }
            }
            if (!installed) throw WhatsAppNotInstalledException()
            return result
        }
    }
}
