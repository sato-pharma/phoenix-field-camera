package com.satopharma.phoenixcamera

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * Phoenix, over HTTPS, as the rep's own login (camera 2.0, owner 20 Nov 2026: "each one will log in with his HR ID").
 *
 * The login is Phoenix's own (`POST /api/auth/login`), declaring itself a native client with `x-phoenix-client`, so
 * Phoenix hands back the session token instead of a cookie; every later call carries it as `Authorization: Bearer`
 * and no cookie, which is how Phoenix recognises the app (and refuses the same doors to a browser).
 *
 * The token lives in this app's private storage only and is the same 12-hour session the web gives.
 */
object PhoenixApi {
    const val BASE = "https://phoenix-hr.satopharma.com"
    private const val CLIENT = "phoenix-camera"
    private const val PREFS = "session"

    /** Phoenix answered, with this status and body. */
    class Reply(val status: Int, val body: JSONObject)

    /** Phoenix could not be reached, or refused in words worth showing. */
    class Refused(val status: Int, message: String) : IOException(message)

    // ── The session ────────────────────────────────────────────────────────────────────────────────────────

    fun token(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("token", null) ?: return null
        val expires = prefs.getLong("expiresMs", 0L)
        return if (expires > System.currentTimeMillis()) token else null
    }

    /** The login id the rep typed (an HR ID, or a TEST- id for a test account). */
    fun hrId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("hrId", "") ?: ""

    fun signedInOnce(context: Context): Boolean = hrId(context).isNotEmpty()

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Signs in with the HR ID and Phoenix password. Throws [Refused] with Phoenix's own words when it says no. */
    fun signIn(context: Context, hrId: String, password: String) {
        val body = JSONObject().put("loginId", hrId).put("password", password).put("responseMode", "json")
        val reply = call("POST", "/api/auth/login", body, null)
        if (reply.status !in 200..299) throw Refused(reply.status, wordsOf(reply, "The HR ID or the password is not right."))
        val token = reply.body.optString("sessionToken", "")
        if (token.isEmpty()) {
            throw Refused(reply.status, "This login needs a second step. Sign in to the main account first, then try again.")
        }
        val expires = runCatching { Instant.parse(reply.body.optString("expiresAt")).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis() + 11L * 3_600_000L)
        // The typed login id, kept for the sign-in screen. A test account (TEST-...) has no HR ID of its own; the seal
        // carries the HR ID Phoenix's pack names (MainActivity.repHrId), never this one.
        val id = hrId.trim().uppercase()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token)
            .putLong("expiresMs", expires)
            .putString("hrId", id)
            .apply()
    }

    // ── Is Phoenix there? ──────────────────────────────────────────────────────────────────────────────────

    /** True when Phoenix answers right now. The camera refuses a visit while it does: the main account is for that. */
    fun reachable(): Boolean = try {
        val connection = open("GET", "/api/healthz", null, 5_000)
        val status = connection.responseCode
        connection.disconnect()
        status in 200..499
    } catch (_: Exception) {
        false
    }

    // ── The day's pack, the time anchor and the sync ──────────────────────────────────────────────────────

    fun pack(context: Context): JSONObject {
        val token = token(context) ?: throw Refused(401, "Sign in again.")
        val reply = call("GET", "/api/phoenix-crm/field-camera/pack", null, token)
        if (reply.status !in 200..299) throw Refused(reply.status, wordsOf(reply, "Your list could not be read."))
        return reply.body.getJSONObject("pack")
    }

    /**
     * A starting point only Phoenix can set, kept with the uptime counter at the moment it arrived. A photo taken
     * later with no signal carries how long after it was taken (uptime, which changing the phone's time does not
     * move), and Phoenix dates the visit from its own clock.
     */
    fun refreshAnchor(context: Context, seatId: String) {
        val token = token(context) ?: return
        val started = SystemClock.elapsedRealtime()
        val reply = call("POST", "/api/phoenix-crm/time-anchor", JSONObject().put("seatId", seatId).put("bootId", bootId(context)), token)
        val ended = SystemClock.elapsedRealtime()
        if (reply.status !in 200..299) return
        val anchor = reply.body.optJSONObject("anchor") ?: return
        val id = anchor.optString("anchorId", "")
        if (id.isEmpty()) return
        context.getSharedPreferences("anchor", Context.MODE_PRIVATE).edit()
            .putString("anchorId", id)
            // The request's start: the anchor was issued after it, so the elapsed time measured from here is never short.
            .putLong("elapsed", started)
            .putLong("ended", ended)
            .putString("bootId", bootId(context))
            .apply()
    }

    /**
     * Refreshes the anchor whenever Phoenix answers and the one held is more than [ANCHOR_FRESH_MS] old, or from
     * before the phone last restarted (camera 2.1: a visit is dated from the newest anchor possible).
     */
    fun refreshAnchorIfOld(context: Context, seatId: String) {
        val prefs = context.getSharedPreferences("anchor", Context.MODE_PRIVATE)
        val sameBoot = prefs.getString("bootId", null) == bootId(context)
        val ended = prefs.getLong("ended", -1L)
        val now = SystemClock.elapsedRealtime()
        if (sameBoot && ended in 0..now && now - ended < ANCHOR_FRESH_MS) return
        refreshAnchor(context, seatId)
    }

    private const val ANCHOR_FRESH_MS = 5L * 60_000L

    /** The offline clock for a shutter at this uptime, or null when there is no anchor from since the phone started. */
    fun offlineClock(context: Context, shutterElapsedMs: Long): JSONObject? {
        val prefs = context.getSharedPreferences("anchor", Context.MODE_PRIVATE)
        val id = prefs.getString("anchorId", null) ?: return null
        val boot = prefs.getString("bootId", null) ?: return null
        if (boot != bootId(context)) return null
        val elapsed = prefs.getLong("elapsed", -1L)
        if (elapsed < 0 || shutterElapsedMs < elapsed) return null
        return JSONObject().put("anchorId", id).put("elapsedMs", shutterElapsedMs - elapsed).put("bootId", boot)
    }

    fun sync(context: Context, body: JSONObject): Reply {
        val token = token(context) ?: throw Refused(401, "Sign in again.")
        return call("POST", "/api/phoenix-crm/field-camera/sync", body, token, readTimeoutMs = 60_000)
    }

    fun bootId(context: Context): String =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()

    // ── The wire ───────────────────────────────────────────────────────────────────────────────────────────

    private fun open(method: String, path: String, token: String?, timeoutMs: Int): HttpsURLConnection =
        (URL(BASE + path).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("x-phoenix-client", CLIENT)
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun call(method: String, path: String, body: JSONObject?, token: String?, readTimeoutMs: Int = 20_000): Reply {
        val connection = open(method, path, token, 15_000).apply { readTimeout = readTimeoutMs }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            return Reply(status, json)
        } finally {
            connection.disconnect()
        }
    }

    /** Phoenix's own refusal, in its words, or the fallback. */
    fun wordsOf(reply: Reply, fallback: String): String {
        val message = reply.body.optString("message", "").ifBlank { reply.body.optString("error", "") }
        return if (message.isBlank() || message.contains('_') && !message.contains(' ')) fallback else message
    }
}
