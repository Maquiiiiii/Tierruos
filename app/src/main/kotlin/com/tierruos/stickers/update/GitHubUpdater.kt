package com.tierruos.stickers.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Zero-dependency GitHub Releases client using HttpsURLConnection and org.json.JSONObject.
 * Checks https://api.github.com/repos/Maquiiiiii/Tierruos/releases/latest, compares semantic
 * version strings, streams Tierruos-universal.apk to cacheDir/updates/Tierruos-universal.apk,
 * verifies the ZIP/APK magic header (PK\x03\x04), and launches the system package installer.
 */
class GitHubUpdater(private val context: Context) {

    data class ReleaseInfo(
        val installedVersion: String,
        val latestVersion: String,
        val tagName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetSizeBytes: Long,
        val updateAvailable: Boolean,
    )

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int,
    )

    fun installedVersionName(): String = try {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        pkgInfo.versionName ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }

    suspend fun checkLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val installed = installedVersionName()
        val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Tierruos-Android/$installed")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("GitHub API devolvió HTTP $status")
            }
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(payload)
            val tagName = json.optString("tag_name", "").trim()
            val cleanLatest = normalizeVersion(tagName)
            val body = json.optString("body", "").trim()

            var downloadUrl = FALLBACK_DOWNLOAD_URL
            var sizeBytes = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name == APK_ASSET_NAME || name.endsWith(".apk", ignoreCase = true)) {
                        val browserUrl = asset.optString("browser_download_url", "")
                        if (browserUrl.isNotBlank()) {
                            downloadUrl = browserUrl
                            sizeBytes = asset.optLong("size", 0L)
                            if (name == APK_ASSET_NAME) break
                        }
                    }
                }
            }

            ReleaseInfo(
                installedVersion = normalizeVersion(installed),
                latestVersion = cleanLatest.ifEmpty { normalizeVersion(installed) },
                tagName = tagName.ifEmpty { "v$installed" },
                releaseNotes = body,
                downloadUrl = downloadUrl,
                assetSizeBytes = sizeBytes,
                updateAvailable = isRemoteNewer(installed, cleanLatest),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(
        release: ReleaseInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            throw IOException("No se pudo preparar la caché de actualización.")
        }
        val targetFile = File(updatesDir, APK_ASSET_NAME)
        val tempFile = File(updatesDir, "$APK_ASSET_NAME.part")
        if (tempFile.exists()) tempFile.delete()

        var currentUrl = release.downloadUrl
        var redirects = 0
        var connection: HttpsURLConnection? = null
        try {
            while (redirects < 6) {
                job.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "Tierruos-Android/${release.installedVersion}")
                }
                connection = conn
                val code = conn.responseCode
                if (code in listOf(
                        HttpsURLConnection.HTTP_MOVED_PERM,
                        HttpsURLConnection.HTTP_MOVED_TEMP,
                        HttpsURLConnection.HTTP_SEE_OTHER,
                        307,
                        308,
                    )
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirección sin cabecera Location.")
                    conn.disconnect()
                    currentUrl = location
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("Error descargando APK (HTTP $code)")
                }
                break
            }

            val activeConn = connection ?: throw IOException("Demasiadas redirecciones al descargar el APK.")
            val contentLength = activeConn.contentLengthLong.takeIf { it > 0L } ?: release.assetSizeBytes

            BufferedInputStream(activeConn.inputStream, 16 * 1024).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastReportedPercent = -1
                    var lastReportTime = 0L
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (contentLength > 0L) {
                            ((downloaded * 100L) / contentLength).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val now = System.currentTimeMillis()
                        if (percent != lastReportedPercent || now - lastReportTime >= 80L) {
                            lastReportedPercent = percent
                            lastReportTime = now
                            onProgress(DownloadProgress(downloaded, contentLength, percent))
                        }
                    }
                    output.fd.sync()
                    onProgress(DownloadProgress(downloaded, contentLength.coerceAtLeast(downloaded), 100))
                }
            }

            verifyZipMagic(tempFile)
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("No se pudo reemplazar el archivo APK anterior.")
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("No se pudo finalizar el archivo APK descargado.")
            }
            targetFile
        } catch (error: Throwable) {
            if (tempFile.exists()) tempFile.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun createManageUnknownAppSourcesIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    fun createInstallIntent(apkFile: File): Intent {
        verifyZipMagic(apkFile)
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/Maquiiiiii/Tierruos/releases/latest"
        const val FALLBACK_DOWNLOAD_URL = "https://github.com/Maquiiiiii/Tierruos/releases/latest/download/Tierruos-universal.apk"
        const val APK_ASSET_NAME = "Tierruos-universal.apk"

        internal fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").trim()

        internal fun isRemoteNewer(installedRaw: String, remoteRaw: String): Boolean {
            val installed = parseParts(installedRaw)
            val remote = parseParts(remoteRaw)
            if (remote.isEmpty()) return false
            val max = maxOf(installed.size, remote.size)
            for (i in 0 until max) {
                val localPart = installed.getOrElse(i) { 0 }
                val remotePart = remote.getOrElse(i) { 0 }
                if (remotePart > localPart) return true
                if (remotePart < localPart) return false
            }
            return false
        }

        private fun parseParts(version: String): List<Int> =
            normalizeVersion(version)
                .substringBefore('-')
                .split('.')
                .mapNotNull { it.trim().toIntOrNull() }

        internal fun verifyZipMagic(file: File) {
            if (!file.isFile || file.length() < 4L) {
                throw IOException("El archivo APK descargado está incompleto o vacío.")
            }
            val header = ByteArray(4)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            val isPkZip = header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
            if (!isPkZip) {
                throw IOException("El archivo descargado no tiene una firma APK/ZIP válida (PK\\x03\\x04).")
            }
        }
    }
}
