package com.satopharma.phoenixcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The sealed file, byte for byte what Phoenix reads (phoenix-workforce, src/lib/phoenix-crm/field-camera):
 *
 *   JPEG | seal JSON | uint32 big-endian length of the seal JSON | "PHXSEAL1"
 *
 * The seal JSON is {v: 1, payload: "<JSON text>", sig: base64 DER ECDSA-SHA256 over the payload's UTF-8 bytes,
 * chain: [base64 DER, leaf first]}. The payload names the SHA-256 of the JPEG, so one changed pixel or one changed
 * stamp breaks the seal. A JPEG viewer stops at the image's end, so the file still opens as an ordinary photo.
 */
object Sealer {
    const val MAX_SIDE = 1600
    const val MAX_BYTES = 1_600_000
    private const val MAGIC = "PHXSEAL1"

    fun upright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** At most MAX_SIDE on its longest side, with the stamp burned into a plain band along the bottom. */
    fun stamp(source: Bitmap, lines: List<String>): Bitmap {
        val scale = MAX_SIDE.toFloat() / max(source.width, source.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val out = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val textSize = out.width / 32f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val lineHeight = textSize * 1.35f
        val padding = textSize * 0.6f
        val bandTop = out.height - (lines.size * lineHeight + padding * 2)
        canvas.drawRect(0f, bandTop, out.width.toFloat(), out.height.toFloat(), Paint().apply { color = Color.argb(200, 0, 0, 0) })
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, bandTop + padding + textSize + index * lineHeight, paint)
        }
        return out
    }

    /** JPEG under MAX_BYTES, leaving room for the seal. */
    fun encode(bitmap: Bitmap): ByteArray {
        var quality = 90
        while (true) {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            val bytes = buffer.toByteArray()
            if (bytes.size <= MAX_BYTES - 64 * 1024 || quality <= 40) return bytes
            quality -= 8
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun assemble(jpeg: ByteArray, payload: String, signature: ByteArray, chain: List<ByteArray>): ByteArray {
        val seal = JSONObject()
            .put("v", 1)
            .put("payload", payload)
            .put("sig", Base64.encodeToString(signature, Base64.NO_WRAP))
            .put("chain", JSONArray(chain.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
            .toString()
            .toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(jpeg)
            write(seal)
            write(ByteBuffer.allocate(4).putInt(seal.size).array())
            write(MAGIC.toByteArray(Charsets.US_ASCII))
        }.toByteArray()
    }
}
