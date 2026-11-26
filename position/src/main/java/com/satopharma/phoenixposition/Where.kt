package com.satopharma.phoenixposition

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One reading of where the tablet is, taken on the round's own thread.
 *
 * The tablet's combined position (Google's fused location: Wi-Fi, the mobile network and the satellites together) is
 * asked first, because it is the only one that answers inside a building and it answers in a second or two. Without
 * Google Play services, or when it says nothing in time, the tablet's own last known reading is taken instead — a
 * quarter-hourly trail is honest about being a few minutes old, which is what `ts` carries.
 *
 * A reading Android marks as mocked is still sent, marked `mock: true`: Phoenix decides what a mocked position means,
 * and an agent that quietly dropped them would look like a tablet standing still.
 */
object Where {
    /** Long enough for the satellites to join indoors, short enough to leave the round well inside its window. */
    private const val WAIT_SECONDS = 25L

    @SuppressLint("MissingPermission")
    fun fix(context: Context): JSONObject? {
        if (!Install.canRead(context)) return null
        val location = fused(context) ?: lastKnown(context) ?: return null
        if (location.latitude == 0.0 && location.longitude == 0.0) return null
        return JSONObject()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            // The tablet's own clock, in epoch milliseconds, which is what Phoenix stores as the fix's time.
            .put("ts", if (location.time > 0L) location.time else System.currentTimeMillis())
            .put("accuracyM", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
            .put("provider", (location.provider ?: "unknown").take(16))
            .put("mock", mocked(location))
            .put("batteryLevel", battery(context) ?: JSONObject.NULL)
    }

    @SuppressLint("MissingPermission")
    private fun fused(context: Context): Location? {
        val available =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        if (!available) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            // A reading up to five minutes old is taken as it is, rather than waking the satellites for nothing.
            .setMaxUpdateAgeMillis(TimeUnit.MINUTES.toMillis(5))
            .build()
        return runCatching {
            Tasks.await(client.getCurrentLocation(request, null), WAIT_SECONDS + 5, TimeUnit.SECONDS)
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers
            .filter { manager.allProviders.contains(it) }
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun mocked(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }

    /** How much charge is left, so a tablet that stopped reporting can be told from one that went flat. */
    private fun battery(context: Context): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        return if (level != null && level in 0..100) level else null
    }
}
