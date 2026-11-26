package com.satopharma.phoenixposition

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fixes waiting on the tablet, oldest first, in this app's private storage.
 *
 * A tablet in a village with no signal keeps reporting to itself and sends the lot when it next sees the network, so a
 * day with no coverage is a late trail, never a missing one. Phoenix refuses a fix older than 30 days, so the file
 * keeps [KEEP] of them — a month of quarter-hourly rounds — and drops the oldest first when it is full.
 *
 * A batch is dropped from here only after Phoenix has said it stored it. If the reply is lost on a dropping
 * connection, the batch stays and is sent again: the unique key on Phoenix's side makes the second copy a no-op
 * rather than a doubled trail.
 */
object Trail {
    private const val FILE = "trail.json"

    /** A month of quarter-hourly rounds, with room to spare. */
    const val KEEP = 3_000

    private val lock = Any()

    fun add(context: Context, fix: JSONObject) {
        synchronized(lock) {
            val held = read(context)
            held.put(fix)
            write(context, trimmed(held))
        }
    }

    /** The oldest [max] fixes, oldest first. */
    fun oldest(context: Context, max: Int): List<JSONObject> = synchronized(lock) {
        val held = read(context)
        val take = minOf(max, held.length())
        (0 until take).mapNotNull { held.optJSONObject(it) }
    }

    /** Forgets the [count] oldest fixes: Phoenix has them. */
    fun forgetOldest(context: Context, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            val held = read(context)
            val kept = JSONArray()
            for (index in count until held.length()) held.optJSONObject(index)?.let { kept.put(it) }
            write(context, kept)
        }
    }

    fun waiting(context: Context): Int = synchronized(lock) { read(context).length() }

    private fun trimmed(held: JSONArray): JSONArray {
        if (held.length() <= KEEP) return held
        val kept = JSONArray()
        for (index in (held.length() - KEEP) until held.length()) held.optJSONObject(index)?.let { kept.put(it) }
        return kept
    }

    private fun read(context: Context): JSONArray {
        val file = context.getFileStreamPath(FILE)
        if (file == null || !file.exists()) return JSONArray()
        // A file cut short by a battery-flat tablet reads as no trail rather than taking the rounds down with it.
        return runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    }

    private fun write(context: Context, fixes: JSONArray) {
        runCatching {
            context.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(fixes.toString().toByteArray()) }
        }
    }
}
