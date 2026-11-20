package com.satopharma.phoenixcamera

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.provider.Settings

/**
 * The satellites' clock, kept after the satellites are out of sight. Every satellite reading carries the satellites'
 * own UTC time; it is pinned to the phone's uptime counter (which changing the time in Settings does not move), so a
 * photo taken inside a building an hour later still carries the satellites' time. Forgotten when the phone restarts.
 */
object SatelliteClock {
    private const val PREFS = "satellite_clock"

    fun remember(context: Context, fix: Location) {
        if (fix.time <= 0L || fix.elapsedRealtimeNanos <= 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("utcMs", fix.time)
            .putLong("elapsedMs", fix.elapsedRealtimeNanos / 1_000_000)
            .putInt("boot", bootCount(context))
            .apply()
    }

    /** The satellites' UTC time at the uptime instant [elapsedNanos], or null with no satellite reading since boot. */
    fun utcAt(context: Context, elapsedNanos: Long): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val utcMs = prefs.getLong("utcMs", 0L)
        if (utcMs <= 0L || prefs.getInt("boot", -2) != bootCount(context)) return null
        val elapsedMs = prefs.getLong("elapsedMs", 0L)
        val at = elapsedNanos / 1_000_000
        if (at < elapsedMs - 60_000L || at > SystemClock.elapsedRealtime() + 1_000L) return null
        return utcMs + (at - elapsedMs)
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
