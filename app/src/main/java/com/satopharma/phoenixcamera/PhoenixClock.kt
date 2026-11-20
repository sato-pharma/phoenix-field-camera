package com.satopharma.phoenixcamera

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Phoenix's own clock, read over HTTPS whenever the phone has a signal and carried forward by the phone's uptime
 * counter, which changing the time in Settings does not move. It is forgotten when the phone restarts. In a clinic
 * with no signal the photo still carries the last reading, and Phoenix compares it with the satellites' time.
 * Nothing is sent: the request carries no name, no location and no photo.
 */
object PhoenixClock {
    private const val PREFS = "phoenix_clock"
    private const val ADDRESS = "https://phoenix-hr.satopharma.com/api/healthz"

    fun refresh(context: Context): Boolean {
        return try {
            val started = SystemClock.elapsedRealtime()
            val connection = (URL(ADDRESS).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                useCaches = false
            }
            connection.connect()
            val serverMs = connection.getHeaderFieldDate("Date", 0L)
            val ended = SystemClock.elapsedRealtime()
            connection.disconnect()
            if (serverMs <= 0L) return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("serverMs", serverMs)
                .putLong("elapsed", (started + ended) / 2)
                .putInt("boot", bootCount(context))
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Phoenix's time now, or null when the phone has not read it since it was last switched on. */
    fun nowMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val serverMs = prefs.getLong("serverMs", 0L)
        if (serverMs <= 0L || prefs.getInt("boot", -2) != bootCount(context)) return null
        val elapsed = prefs.getLong("elapsed", 0L)
        val now = SystemClock.elapsedRealtime()
        if (now < elapsed) return null
        return serverMs + (now - elapsed)
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
