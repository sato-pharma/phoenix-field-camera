package com.satopharma.phoenixcamera

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** The satellites alone (GPS_PROVIDER) and the satellite count beside it; PositionWatch runs it beside the fused position. */
class SatelliteWatch(
    context: Context,
    private val onChange: () -> Unit,
    private val onFix: (Location) -> Unit = {},
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var location: Location? = null
        private set
    @Volatile var used: Int = 0
        private set
    @Volatile var seen: Int = 0
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(newLocation: Location) {
            location = newLocation
            onFix(newLocation)
            onChange()
        }

        override fun onProviderEnabled(provider: String) = onChange()

        override fun onProviderDisabled(provider: String) {
            location = null
            onChange()
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var inFix = 0
            for (index in 0 until status.satelliteCount) if (status.usedInFix(index)) inFix += 1
            used = inFix
            seen = status.satelliteCount
            onChange()
        }

        override fun onStopped() {
            used = 0
            seen = 0
            onChange()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        manager.registerGnssStatusCallback(gnss, handler)
    }

    fun stop() {
        manager.removeUpdates(listener)
        manager.unregisterGnssStatusCallback(gnss)
    }

    fun gpsOn(): Boolean = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    /** How old the fix is, by the uptime counter (never the phone's clock). */
    fun fixAgeMs(fix: Location): Long = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
}
