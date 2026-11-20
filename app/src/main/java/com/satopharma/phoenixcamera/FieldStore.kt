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

/** One choice Phoenix offers on the new-customer form: what is sent, and what the rep reads. */
class PackOption(val value: String, val label: String)

/**
 * What the main account's new-customer form offers (camera 2.2): the customer types, the specialities, and the types
 * that need a speciality. When the saved list carries none, [DEFAULT] stands in (camera 2.2.1).
 */
class NewCustomerOptions(
    val customerTypes: List<PackOption>,
    val specialities: List<PackOption>,
    val typesWithSpeciality: Set<String>,
) {
    companion object {
        /**
         * The same choices Phoenix sends (speciality-list.ts in phoenix-workforce: every customer type but Pharmacy, the
         * doctor specialities, Clinic and Polyclinic carrying one). The form uses them when the saved list is older than
         * the form itself, so Add new customer works with no network from the first day (owner, 21 Nov 2026: "make him
         * able to add new customer even if offline"). Phoenix still checks every value it receives.
         */
        val DEFAULT = NewCustomerOptions(
            customerTypes = listOf(
                "Clinic", "Polyclinic", "Hospital", "Private Hospital", "University Hospital", "Educational Hospital",
                "Armed Forces Hospital", "Police Hospital", "Contract", "Health Insurance", "Educational Institute",
            ).map { PackOption(it, it) },
            specialities = listOf(
                "Internal Medicine", "Pediatrics", "ENT", "Neurology", "Chest", "General Practitioner", "Urology",
                "General Surgery", "Psychiatry", "Neuro Surgery", "Vascular Surgery", "Gastroenterologist", "Ophthalmologist",
                "Neuropedia", "Cardiology", "NICU", "Orthopedic Surgery", "Tropical Medicine", "Dermatologist", "Nephrology",
            ).map { PackOption(it, it) },
            typesWithSpeciality = setOf("Clinic", "Polyclinic"),
        )

        fun parse(json: JSONObject?): NewCustomerOptions? {
            if (json == null) return null
            fun options(array: JSONArray?): List<PackOption> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val item = array?.opt(index)
                when (item) {
                    is String -> item.takeIf { it.isNotBlank() }?.let { PackOption(it, it) }
                    is JSONObject -> {
                        val value = item.optString("value").ifBlank { item.optString("id") }.ifBlank { item.optString("name") }
                        val label = item.optString("label").ifBlank { item.optString("name") }.ifBlank { value }
                        value.takeIf { it.isNotBlank() }?.let { PackOption(it, label) }
                    }
                    else -> null
                }
            }
            val types = options(json.optJSONArray("customerTypes"))
            if (types.isEmpty()) return null
            return NewCustomerOptions(
                customerTypes = types,
                specialities = options(json.optJSONArray("specialities")),
                typesWithSpeciality = options(json.optJSONArray("typesWithSpeciality")).map { it.value }.toSet(),
            )
        }
    }
}

/** The newest published APK: its version, the SHA-256 and size it must have, and a short-lived signed link. */
class LatestApp(val versionCode: Long, val versionName: String, val sha256: String, val sizeBytes: Long, val url: String) {
    companion object {
        fun parse(json: JSONObject?): LatestApp? {
            if (json == null) return null
            val code = json.optLong("versionCode", 0L)
            val sha = json.optString("sha256").lowercase()
            val size = json.optLong("sizeBytes", 0L)
            val url = json.optString("url")
            if (code <= 0L || !Regex("^[0-9a-f]{64}$").matches(sha) || size <= 0L || !url.startsWith("https://")) return null
            return LatestApp(code, json.optString("versionName"), sha, size, url)
        }
    }
}

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
    /** Phoenix's choices, or the built-in ones when the saved list predates them: never null since camera 2.2.1. */
    val newCustomerOptions: NewCustomerOptions?,
    /** The newest Phoenix Camera Phoenix has published, or null (camera 2.2: the "Update the app" button). */
    val latestApp: LatestApp?,
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
                newCustomerOptions = NewCustomerOptions.parse(json.optJSONObject("newCustomerOptions")) ?: NewCustomerOptions.DEFAULT,
                latestApp = LatestApp.parse(json.optJSONObject("latestApp")),
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

    /**
     * Queues one sealed item: the photo is already on disk. A visit carries its form ([draft]); a validation or a new
     * customer carries none, since everything it says is inside the seal (camera 2.2).
     */
    fun enqueue(context: Context, visitId: String, sealedName: String, draft: JSONObject?, customerName: String, intent: String) {
        val item = JSONObject()
            .put("visitId", visitId)
            .put("file", sealedName)
            .put("customerName", customerName)
            .put("intent", intent)
            .put("queuedAtMs", System.currentTimeMillis())
        if (draft != null) item.put("visit", draft)
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
        item.optJSONObject("visit")?.let { body.put("visit", it) }
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
