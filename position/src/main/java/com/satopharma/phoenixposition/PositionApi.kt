package com.satopharma.phoenixposition

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Phoenix's two doors for a tablet's own positions, over HTTPS, with nothing else in between — no tablet server, no
 * premium plugin, no launcher version to depend on.
 *
 *   - `POST /api/devices/positions/hello` — the install introduces itself and Phoenix answers with a token ONCE.
 *   - `POST /api/devices/positions`      — the fixes, as `Authorization: Bearer <that token>`.
 *
 * An unknown install and a wrong token are refused with the SAME 401 by design, so this app cannot tell which half
 * was wrong either; both are answered the one way that is always right — mint a new install id and say hello again.
 * A revoked install is refused 403, and that is the end of it: the rounds stop.
 */
object PositionApi {
    const val BASE = "https://phoenix-hr.satopharma.com"

    /** Phoenix answered. */
    class Answer(val status: Int, val body: JSONObject) {
        val ok: Boolean get() = status in 200..299
    }

    /** Says hello. Returns null when Phoenix could not be reached at all. */
    fun hello(context: Context): Answer? = call("/api/devices/positions/hello", introduction(context), null)

    /** Sends the fixes, oldest first. Returns null when Phoenix could not be reached at all. */
    fun report(context: Context, fixes: List<JSONObject>, token: String): Answer? {
        val body = introduction(context)
        val array = JSONArray()
        for (fix in fixes) array.put(fix)
        body.put("fixes", array)
        return call("/api/devices/positions", body, token)
    }

    /**
     * What this install says it is, in every call: the id it minted, this app's version, the tablet's own Android id,
     * what tablet it is, and what Android really granted it. Phoenix keeps the lot on the install's row, so a tablet
     * page can say "this install, this model, this permission" without asking the tablet anything.
     */
    private fun introduction(context: Context): JSONObject = JSONObject()
        .put("installId", Install.id(context))
        .put("agentVersion", Install.version(context))
        .put("androidId", Install.androidId(context))
        .put("androidVersion", Build.VERSION.RELEASE.orEmpty().take(24))
        .put("manufacturer", Build.MANUFACTURER.orEmpty().take(48))
        .put("model", Build.MODEL.orEmpty().take(64))
        .put("locationPermission", Install.permission(context))

    private fun call(path: String, body: JSONObject, token: String?): Answer? {
        var connection: HttpsURLConnection? = null
        return try {
            connection = (URL(BASE + path).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            Answer(status, runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
        } catch (_: Exception) {
            // No network, no DNS, a dropped connection: the fixes stay on the tablet and go in the next round.
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
