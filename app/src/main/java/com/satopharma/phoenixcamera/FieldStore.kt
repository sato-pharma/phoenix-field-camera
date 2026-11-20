package com.satopharma.phoenixcamera

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One customer on the rep's list, as Phoenix's pack names him. */
class PackCustomer(
    val customerId: String,
    val name: String,
    val listType: String,
    val speciality: String?,
    val plannedToday: Boolean,
    val visitedToday: Boolean,
    val lastProducts: List<String>,
    /** Phoenix already holds his place; when false the photo may also be the doctor's name (camera 2.1). */
    val validated: Boolean,
)

/** Somebody the rep may name on "Who was on the visit". */
class PackPerson(val hrId: String, val name: String, val side: String)

/** What the phone keeps for a day with no network: his territory, his list and plan, the line's products. */
class Pack(
    val seatId: String,
    val hrId: String,
    val repName: String,
    val visitDay: String,
    val products: List<String>,
    val people: List<PackPerson>,
    val customers: List<PackCustomer>,
) {
    companion object {
        fun parse(json: JSONObject): Pack {
            fun strings(array: JSONArray?): List<String> =
                (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf { s -> s.isNotBlank() } }
            val customers = json.optJSONArray("customers") ?: JSONArray()
            val people = json.optJSONArray("people") ?: JSONArray()
            return Pack(
                seatId = json.getString("seatId"),
                hrId = json.optString("hrId"),
                repName = json.optString("repName"),
                visitDay = json.optString("visitDay"),
                products = strings(json.optJSONArray("products")),
                people = (0 until people.length()).map { index ->
                    val p = people.getJSONObject(index)
                    PackPerson(p.getString("hrId"), p.optString("name", p.getString("hrId")), p.optString("side", "manager"))
                },
                customers = (0 until customers.length()).map { index ->
                    val c = customers.getJSONObject(index)
                    PackCustomer(
                        customerId = c.getString("customerId"),
                        name = c.optString("name"),
                        listType = c.optString("listType"),
                        speciality = c.optString("speciality").takeIf { it.isNotBlank() && it != "null" },
                        plannedToday = c.optBoolean("plannedToday"),
                        visitedToday = c.optBoolean("visitedToday"),
                        lastProducts = strings(c.optJSONArray("lastProducts")),
                        validated = c.optBoolean("validated", true),
                    )
                },
            )
        }
    }
}

/**
 * The phone's own store: the latest pack, and the visits waiting to reach Phoenix. Everything is in the app's private
 * folder; nothing is shared with another app. A visit leaves the queue only when Phoenix has recorded it.
 */
object FieldStore {
    private fun packFile(context: Context) = File(context.filesDir, "pack.json")
    private fun queueFolder(context: Context) = File(context.filesDir, "queue").apply { mkdirs() }
    private fun sealedFolder(context: Context) = File(context.filesDir, "sealed").apply { mkdirs() }

    fun savePack(context: Context, json: JSONObject) {
        val file = packFile(context)
        val temp = File(file.parentFile, "pack.json.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        temp.renameTo(file)
    }

    fun pack(context: Context): Pack? = runCatching { Pack.parse(JSONObject(packFile(context).readText(Charsets.UTF_8))) }.getOrNull()

    fun clear(context: Context) {
        packFile(context).delete()
    }

    fun sealedFile(context: Context, name: String) = File(sealedFolder(context), name)

    /** Queues one visit: the sealed photo is already on disk; this is what the sync sends beside it. */
    fun enqueue(context: Context, visitId: String, sealedName: String, draft: JSONObject, customerName: String) {
        val item = JSONObject()
            .put("visitId", visitId)
            .put("file", sealedName)
            .put("visit", draft)
            .put("customerName", customerName)
            .put("queuedAtMs", System.currentTimeMillis())
        val file = File(queueFolder(context), "$visitId.json")
        val temp = File(file.parentFile, "$visitId.json.tmp")
        temp.writeText(item.toString(), Charsets.UTF_8)
        temp.renameTo(file)
    }

    fun queued(context: Context): List<JSONObject> =
        (queueFolder(context).listFiles { f -> f.name.endsWith(".json") }?.toList() ?: emptyList())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull() }

    fun waiting(context: Context): Boolean = queued(context).any { !it.has("refused") }

    private fun finish(context: Context, item: JSONObject) {
        File(queueFolder(context), "${item.getString("visitId")}.json").delete()
        sealedFile(context, item.getString("file")).delete()
    }

    private fun markRefused(context: Context, item: JSONObject, words: String) {
        item.put("refused", words)
        File(queueFolder(context), "${item.getString("visitId")}.json").writeText(item.toString(), Charsets.UTF_8)
    }

    fun forget(context: Context, visitId: String) {
        queued(context).firstOrNull { it.optString("visitId") == visitId }?.let { finish(context, it) }
    }

    /** The only words the rep reads about a visit Phoenix did not take in the ordinary way. */
    const val SENT_FOR_REVIEW = "This visit went to the System Administrator."

    /** What happened to one send. */
    enum class Outcome { RECORDED, REFUSED, LATER, SIGN_IN }

    /**
     * Sends every waiting visit, oldest first. Phoenix answers every visit it received with success (2.1: it refuses
     * nothing from the camera and the System Administrator decides), and the visit leaves the queue. Any other
     * refusal: it stays, marked, with plain words and never Phoenix's own. No answer: it waits for the next time. A
     * lapsed login stops the run until the rep signs in again. One run at a time: the app and the background send
     * never send the same visit together.
     */
    @Synchronized
    fun drain(context: Context): Outcome? {
        var last: Outcome? = null
        for (item in queued(context)) {
            if (item.has("refused")) continue
            val outcome = sendOne(context, item)
            last = outcome
            if (outcome == Outcome.SIGN_IN || outcome == Outcome.LATER) return outcome
        }
        return last
    }

    private fun sendOne(context: Context, item: JSONObject): Outcome {
        val file = sealedFile(context, item.getString("file"))
        if (!file.exists()) {
            markRefused(context, item, "The photo of this visit is no longer on the phone. Record the visit in the main account.")
            return Outcome.REFUSED
        }
        val pack = pack(context) ?: return Outcome.LATER
        val body = JSONObject()
            .put("seatId", pack.seatId)
            .put("file", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("visit", item.getJSONObject("visit"))
        val reply = try {
            PhoenixApi.sync(context, body)
        } catch (refused: PhoenixApi.Refused) {
            return if (refused.status == 401) Outcome.SIGN_IN else Outcome.LATER
        } catch (_: Exception) {
            return Outcome.LATER
        }
        return when {
            reply.status in 200..299 -> { finish(context, item); Outcome.RECORDED }
            reply.status == 401 -> Outcome.SIGN_IN
            reply.status == 429 || reply.status >= 500 -> Outcome.LATER
            else -> {
                markRefused(context, item, SENT_FOR_REVIEW)
                Outcome.REFUSED
            }
        }
    }
}
