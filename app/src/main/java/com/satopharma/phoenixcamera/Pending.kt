package com.satopharma.phoenixcamera

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The photo is never thrown away (camera 2.1, owner 20 Nov 2026).
 *
 * The shutter stores the stamped photo and everything known at that instant in the app's private folder at once, as a
 * pending visit. It is sealed with the place the moment one is known:
 *   - the most accurate reading within [MAX_M] from the [WINDOW_MS] before the shutter (one within [EXACT_M] and
 *     nearest in time first): "locked" when it is at most [LOCKED_BEFORE_MS] old, "cached" when older;
 *   - with none, the visit waits as "location pending" and takes the first reading within [MAX_M] in the [WINDOW_MS]
 *     after the shutter: "locked" within [LOCKED_AFTER_MS], "late" after;
 *   - with none by then, or after the phone restarted, it is sealed with no place at all: "none".
 * Phoenix receives every one of them and shows the System Administrator what the seal says; the rep sees only that
 * the visit is saved.
 */
object Pending {
    const val WINDOW_MS = 10L * 60_000L
    const val LOCKED_BEFORE_MS = 2L * 60_000L
    const val LOCKED_AFTER_MS = 30_000L
    const val EXACT_M = 20f
    const val MAX_M = 40f

    private val lock = Any()
    private val cairo = ZoneId.of("Africa/Cairo")
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ENGLISH)

    private fun folder(context: Context) = File(context.filesDir, "pending").apply { mkdirs() }

    /** Writes the stamped photo and its shutter facts; nothing is lost from here on, whatever happens to the app. */
    fun save(context: Context, visitId: String, jpeg: ByteArray, meta: JSONObject) {
        synchronized(lock) {
            val dir = folder(context)
            val photo = File(dir, "$visitId.jpg")
            val photoTemp = File(dir, "$visitId.jpg.tmp")
            photoTemp.writeBytes(jpeg)
            photoTemp.renameTo(photo)
            val file = File(dir, "$visitId.json")
            val temp = File(dir, "$visitId.json.tmp")
            temp.writeText(meta.toString(), Charsets.UTF_8)
            temp.renameTo(file)
        }
    }

    private fun items(context: Context): List<JSONObject> =
        (folder(context).listFiles { f -> f.name.endsWith(".json") }?.toList() ?: emptyList())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull() }

    fun any(context: Context): Boolean = items(context).isNotEmpty()

    fun isPending(context: Context, visitId: String): Boolean = File(folder(context), "$visitId.json").exists()

    /**
     * Seals every pending visit whose place is now known, or whose window has passed. [watch] is the live position
     * history, or null in the background, where only a visit past its window (or from before a restart) is sealed,
     * with no place. Returns how many were sealed into the send queue.
     */
    fun advance(context: Context, watch: PositionWatch?): Int = synchronized(lock) {
        var sealed = 0
        for (meta in items(context)) {
            val visitId = meta.optString("visitId")
            if (visitId.isEmpty()) continue
            val photo = File(folder(context), "$visitId.jpg")
            if (!photo.exists()) {
                File(folder(context), "$visitId.json").delete()
                continue
            }
            val shutterNanos = meta.getLong("shutterNanos")
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val restarted = meta.optString("bootId") != PhoenixApi.bootId(context) || nowNanos < shutterNanos
            val sinceMs = if (restarted) Long.MAX_VALUE else (nowNanos - shutterNanos) / 1_000_000
            val fix = if (restarted || watch == null) null else choose(watch, shutterNanos)
            val status = when {
                fix != null -> statusOf(-fix.offsetMs(shutterNanos))
                restarted || sinceMs > WINDOW_MS -> "none"
                else -> null
            } ?: continue
            runCatching {
                val sealedFile = seal(context, meta, photo.readBytes(), fix, status)
                FieldStore.enqueue(
                    context,
                    visitId,
                    sealedFile.name,
                    meta.optJSONObject("draft"),
                    meta.optString("customerName"),
                    intentOf(meta),
                )
                photo.delete()
                File(folder(context), "$visitId.json").delete()
                sealed++
            }
        }
        sealed
    }

    /** The reading a pending visit is sealed with now, or null while none qualifies yet. */
    private fun choose(watch: PositionWatch, shutterNanos: Long): PositionWatch.Fix? {
        val all = watch.genuine().filter { it.location.accuracy <= MAX_M }
        val before = all.filter { val o = it.offsetMs(shutterNanos); o <= 0 && o >= -WINDOW_MS }
        val exactBefore = before.filter { it.location.accuracy <= EXACT_M }
        if (exactBefore.isNotEmpty()) return exactBefore.minByOrNull { abs(it.offsetMs(shutterNanos)) }
        if (before.isNotEmpty()) {
            return before.minWithOrNull(compareBy<PositionWatch.Fix>({ it.location.accuracy }, { abs(it.offsetMs(shutterNanos)) }))
        }
        return all
            .filter { val o = it.offsetMs(shutterNanos); o > 0 && o <= WINDOW_MS }
            .minByOrNull { it.offsetMs(shutterNanos) }
    }

    /** "visit", "validate" or "new_customer"; an item from before 2.2 is a visit. */
    fun intentOf(meta: JSONObject): String = meta.optString("intent", "visit").ifBlank { "visit" }

    /** [fixAgeMs] is the shutter minus the reading's time: positive before the shutter. */
    private fun statusOf(fixAgeMs: Long): String = when {
        fixAgeMs > LOCKED_BEFORE_MS -> "cached"
        fixAgeMs < -LOCKED_AFTER_MS -> "late"
        else -> "locked"
    }

    private fun seal(context: Context, meta: JSONObject, jpeg: ByteArray, fix: PositionWatch.Fix?, status: String): File {
        SealKey.ensure()
        val visitId = meta.getString("visitId")
        val shutterNanos = meta.getLong("shutterNanos")
        val checks = meta.optJSONObject("checks") ?: JSONObject()

        val visitBlock = JSONObject()
            .put("seatId", meta.getString("seatId"))
            .put("customerId", meta.getString("customerId"))
            .put("visitId", visitId)
            .put("offline", meta.optJSONObject("offline") ?: JSONObject.NULL)
            .put("photoKind", meta.optString("photoKind", "place").ifBlank { "place" })
            .put("intent", intentOf(meta))
        // A new customer: everything the rep typed travels inside the signed block, never beside it (camera 2.2).
        meta.optJSONObject("newCustomer")?.let { visitBlock.put("newCustomer", it) }

        fun orNull(value: Any?): Any = value ?: JSONObject.NULL
        val usedAtShutter = if (meta.has("satellitesUsed")) meta.optInt("satellitesUsed") else null
        val seenAtShutter = if (meta.has("satellitesSeen")) meta.optInt("satellitesSeen") else null

        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val payload = JSONObject()
            .put("v", 1)
            .put("app", context.packageName)
            .put("appVersion", appVersion(context))
            .put("imageSha256", Sealer.sha256Hex(jpeg))
            .put("hrId", meta.optString("hrId"))
            .put("repName", meta.optString("repName"))
            .put("lat", orNull(fix?.location?.latitude))
            .put("lon", orNull(fix?.location?.longitude))
            .put("accuracyMeters", orNull(fix?.location?.accuracy?.toDouble()))
            .put("satellitesUsed", orNull(fix?.let { if (it.source == "gps") it.satellites else usedAtShutter }))
            .put("satellitesSeen", orNull(fix?.let { seenAtShutter }))
            .put("provider", orNull(fix?.source))
            .put("fixAgeMs", orNull(fix?.let { -it.offsetMs(shutterNanos) }))
            .put("locationStatus", status)
            .put("takenAtUtc", meta.getString("takenAtUtc"))
            .put("timeSource", meta.getString("timeSource"))
            .put("phoneClockUtc", meta.getString("phoneClockUtc"))
            .put("networkTimeUtc", meta.opt("networkTimeUtc") ?: JSONObject.NULL)
            .put("mock", fix?.mock ?: false)
            .put("mockAppActive", checks.optBoolean("mockAppActive") || PhoneChecks.mockAppActive(context))
            .put("rooted", checks.optBoolean("rooted") || PhoneChecks.rooted(context))
            .put("emulator", PhoneChecks.emulator())
            .put("developerOptions", checks.optBoolean("developerOptions") || PhoneChecks.developerOptions(context))
            .put("adb", checks.optBoolean("adb") || PhoneChecks.adb(context))
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("nonce", nonce)
            .put("visit", visitBlock)
            .toString()

        val signature = SealKey.sign(payload.toByteArray(Charsets.UTF_8))
        val bytes = Sealer.assemble(jpeg, payload, signature, SealKey.chain())
        val takenAt = Instant.parse(meta.getString("takenAtUtc"))
        val file = FieldStore.sealedFile(context, "Phoenix-Camera-${takenAt.atZone(cairo).format(fileTime)}-${visitId.take(8)}.jpg")
        file.writeBytes(bytes)
        return file
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "2.2.0"
}
