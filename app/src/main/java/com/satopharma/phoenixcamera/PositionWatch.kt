package com.satopharma.phoenixcamera

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs

/**
 * Where the rep is, from the moment the app opens (owner, 20 Nov 2026: "I don't want him to wait for the satellites
 * ... when he opens the app he can capture the location and take the photo now").
 *
 * Two sources run side by side and every reading is kept for eleven minutes:
 *   - the phone's combined position (Google's fused location: Wi-Fi, the mobile network and the satellites together),
 *     the only one that answers inside a polyclinic, usually within a second or two;
 *   - the satellites alone (GPS), which also give the satellites' own clock and count.
 * A photo carries the most accurate reading taken around its shutter ([best]). Every reading is checked for the
 * fake-location mark Android puts on a mocked position, whichever source it came from.
 */
class PositionWatch(private val context: Context, private val onChange: () -> Unit) {

    /** One reading: where, how sure, which source, and the satellites in the fix (0 when not the satellites). */
    class Fix(val location: Location, val source: String, val satellites: Int) {
        val mock: Boolean = PhoneChecks.isMock(location)
        /** Milliseconds from [shutterNanos] to this reading; negative = taken before the shutter. */
        fun offsetMs(shutterNanos: Long): Long = (location.elapsedRealtimeNanos - shutterNanos) / 1_000_000
    }

    private val manager = context.getSystemService(LocationManager::class.java)
    private val fixes = ArrayDeque<Fix>()
    private val lock = Any()
    private var running = false

    val satellites = SatelliteWatch(context, onChange) { record(it, "gps", it.extras?.getInt("satellites") ?: 0) }

    private val fused: FusedLocationProviderClient? =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            LocationServices.getFusedLocationProviderClient(context)
        } else {
            null
        }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) record(location, "fused", 0)
        }
    }

    /** Without Google Play services: the network position from the phone's own location service. */
    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = record(location, "network", 0)
        override fun onProviderEnabled(provider: String) = onChange()
        override fun onProviderDisabled(provider: String) = onChange()

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        runCatching { satellites.start() }
        val client = fused
        if (client != null) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()
            runCatching { client.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper()) }
            runCatching { client.lastLocation.addOnSuccessListener { it?.let { last -> record(last, "fused", 0) } } }
        } else if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1_000L, 0f, networkListener, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { satellites.stop() }
        runCatching { fused?.removeLocationUpdates(fusedCallback) }
        runCatching { manager.removeUpdates(networkListener) }
    }

    private fun record(location: Location, source: String, satellitesInFix: Int) {
        if (!location.hasAccuracy() || location.elapsedRealtimeNanos <= 0L) return
        val count = if (source == "gps") maxOf(satellitesInFix, satellites.used) else 0
        val fix = Fix(Location(location), source, count)
        val cutoff = SystemClock.elapsedRealtimeNanos() - KEEP_MS * 1_000_000
        synchronized(lock) {
            if (fixes.any { it.source == source && it.location.elapsedRealtimeNanos == location.elapsedRealtimeNanos }) return
            fixes.addLast(fix)
            while (fixes.isNotEmpty() && fixes.first().location.elapsedRealtimeNanos < cutoff) fixes.removeFirst()
        }
        if (source == "gps") SatelliteClock.remember(context, location)
        onChange()
    }

    private fun snapshot(): List<Fix> = synchronized(lock) { fixes.toList() }

    /** Location switched on in the phone's settings. */
    fun locationOn(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else satellites.gpsOn()

    /** The newest reading of all, or null. */
    fun latest(): Fix? = snapshot().maxByOrNull { it.location.elapsedRealtimeNanos }

    /** A fake-location mark on any reading in the last [withinMs]. */
    fun mockSeen(withinMs: Long = 30_000L): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        return snapshot().any { it.mock && (now - it.location.elapsedRealtimeNanos) / 1_000_000 <= withinMs }
    }

    /**
     * The most accurate genuine reading taken from [beforeMs] before the shutter to [afterMs] after it; between two
     * equally accurate readings the one nearer the shutter.
     */
    fun best(shutterNanos: Long, beforeMs: Long, afterMs: Long): Fix? = snapshot()
        .filter { !it.mock }
        .filter { val offset = it.offsetMs(shutterNanos); offset >= -beforeMs && offset <= afterMs }
        .minWithOrNull(compareBy<Fix>({ it.location.accuracy }, { abs(it.offsetMs(shutterNanos)) }))

    /** Every genuine reading still kept (no fake-location mark), oldest first. */
    fun genuine(): List<Fix> = snapshot().filter { !it.mock }

    /** Milliseconds since the newest reading, or null with none yet. */
    fun latestAgeMs(): Long? = latest()?.let { (SystemClock.elapsedRealtimeNanos() - it.location.elapsedRealtimeNanos) / 1_000_000 }

    companion object {
        /** Readings are kept this long: a photo may carry one from up to ten minutes before its shutter (2.1). */
        const val KEEP_MS = 11 * 60_000L

        /**
         * Android's accuracy is the radius the phone is 68% sure of. For the owner's "at least 98% accurate" the
         * camera shows and judges the 98% radius: 2.797 / 1.5096 = 1.853 times as wide (a circular normal spread).
         */
        fun radius98(accuracy68: Float): Float = accuracy68 * 1.853f
    }
}
