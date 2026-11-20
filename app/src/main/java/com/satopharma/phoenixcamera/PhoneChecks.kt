package com.satopharma.phoenixcamera

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * What the phone itself says about the photo's trust. Every answer goes into the seal; Phoenix decides. The app
 * refuses to take a photo only where Phoenix would refuse it anyway (a fake location, a rooted phone, an emulator),
 * so the rep learns it at the clinic and not on the call.
 */
object PhoneChecks {

    fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider

    /** True only when a fake-location app is CONFIRMED switched on; an unreadable answer is not a yes. */
    fun mockAppActive(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        for (info in installedPackages(context)) {
            if (info.packageName == context.packageName) continue
            val requested = info.requestedPermissions ?: continue
            if (!requested.contains("android.permission.ACCESS_MOCK_LOCATION")) continue
            val uid = info.applicationInfo?.uid ?: continue
            val mode = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, info.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, info.packageName)
                }
            } catch (_: Exception) {
                AppOpsManager.MODE_ERRORED
            }
            if (mode == AppOpsManager.MODE_ALLOWED) return true
        }
        return false
    }

    fun rooted(context: Context): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/vendor/bin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk",
        )
        if (paths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        val rootApps = setOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "me.weishu.kernelsu", "com.kingroot.kinguser",
        )
        return installedPackages(context).any { it.packageName in rootApps }
    }

    fun emulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator") || fingerprint.contains("sdk_gphone") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.startsWith("sdk") || Build.MANUFACTURER.contains("Genymotion")
    }

    fun developerOptions(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun adb(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun installedPackages(context: Context): List<PackageInfo> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
