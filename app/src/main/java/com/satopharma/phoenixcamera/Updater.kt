package com.satopharma.phoenixcamera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * The "Update the app" button (camera 2.2, owner 20 Nov 2026: "when the rep presses it, it updates itself").
 *
 * Only when the rep presses it, never in the background: the APK Phoenix's pack names is downloaded into the app's
 * cache (the link is already signed, so no login travels with it), checked against the size and SHA-256 the pack
 * gives, and handed to Android's own installer. Android refuses an APK signed with another key, and an update with the
 * same package and key keeps the app's data, so the visits waiting in the app's private files folder survive it.
 */
object Updater {
    private const val AUTHORITY = "com.satopharma.phoenixcamera.files"

    /** The update failed in a way the rep can fix by trying again. */
    class Failed(message: String) : IOException(message)

    fun installedVersionCode(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(Long.MAX_VALUE)

    /** True when Phoenix has published a newer Phoenix Camera than the one installed. */
    fun available(context: Context, latest: LatestApp?): Boolean =
        latest != null && latest.versionCode > installedVersionCode(context)

    /** Android 8+: the rep has let Phoenix Camera install apps ("Install unknown apps"). */
    fun mayInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installSetting(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads and checks the APK. Throws [Failed] with plain words; a wrong file is deleted. */
    fun download(context: Context, latest: LatestApp): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, "Phoenix-Camera-update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            val connection = (URL(latest.url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) throw Failed(INCOMPLETE)
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > latest.sizeBytes) throw Failed(INCOMPLETE)
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (failed: Failed) {
            file.delete()
            throw failed
        } catch (_: Exception) {
            file.delete()
            throw Failed(NO_NETWORK)
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (written != latest.sizeBytes || sha != latest.sha256) {
            file.delete()
            throw Failed(INCOMPLETE)
        }
        return file
    }

    /** Hands the checked APK to Android's installer; the rep confirms there. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    const val INCOMPLETE = "The update did not download whole. Try again."
    const val NO_NETWORK = "The update needs network. Try again where there is network."
}
