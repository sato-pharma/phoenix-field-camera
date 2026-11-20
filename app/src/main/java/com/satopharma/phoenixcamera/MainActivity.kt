package com.satopharma.phoenixcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Phoenix Camera: a live camera only (no gallery), the satellites' position and time, and a seal Phoenix can
 * check. Works with no network at all. The rep sends the file to the System Administrator as a WhatsApp DOCUMENT,
 * so it arrives whole; Phoenix accepts it for 72 hours after it was taken.
 */
class MainActivity : ComponentActivity() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var watch: SatelliteWatch? = null
    private var imageCapture: ImageCapture? = null
    private var statusView: TextView? = null
    private var shutter: Button? = null
    private var busy = false

    private val cairo = ZoneId.of("Africa/Cairo")
    private val stampTime = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.ENGLISH)
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ENGLISH)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dropExpiredPhotos()
        worker.execute { PhoenixClock.refresh(this) }
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onResume() {
        super.onResume()
        worker.execute { PhoenixClock.refresh(this) }
    }

    override fun onDestroy() {
        watch?.stop()
        worker.shutdown()
        super.onDestroy()
    }

    // ── Screens ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun showStart() {
        val granted = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        when {
            !granted -> showMessage(
                "The Phoenix Camera needs the camera and your precise location.",
                "Allow both",
            ) { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)) }
            PhoneChecks.rooted(this) || PhoneChecks.emulator() -> showMessage(
                "This phone cannot seal photos: its system has been modified. Use a phone with its original system.",
                null,
                null,
            )
            repHrId().isEmpty() -> showSetup()
            else -> showCamera()
        }
    }

    private fun showSetup() {
        stopCamera()
        val hrId = field("Your HR id, for example B-182", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, repHrId())
        val name = field("Your full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, repName())
        val save = button("Save") {
            val id = hrId.text.toString().trim().uppercase(Locale.ENGLISH)
            val full = name.text.toString().trim()
            if (!Regex("^[A-Z0-9-]{2,20}$").matches(id) || full.length < 3) {
                toast("Enter your own HR id and your full name.")
            } else {
                prefs().edit().putString("hrId", id).putString("name", full.take(120)).apply()
                showCamera()
            }
        }
        setContentView(column(
            title("Phoenix Camera"),
            text("Enter your own HR id and name. Every photo you take carries them."),
            hrId,
            name,
            save,
        ))
    }

    private fun showCamera() {
        val preview = PreviewView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val status = text("Looking for satellites…").apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        val take = button("Take the photo") { capture() }.apply { isEnabled = false }
        val list = button("Photos to send") { showPhotos() }
        statusView = status
        shutter = take
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(preview)
            addView(status)
            addView(take)
            addView(list)
        }
        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val captureUse = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, captureUse)
            imageCapture = captureUse
        }, ContextCompat.getMainExecutor(this))

        watch?.stop()
        watch = SatelliteWatch(this) { runOnUiThread { refreshStatus() } }.also { it.start() }
        refreshStatus()
    }

    private fun showPhotos() {
        stopCamera()
        val rows = mutableListOf<View>(title("Photos to send"))
        val files = sealedFiles()
        if (files.isEmpty()) rows += text("No photos yet. A photo can be sent for 72 hours after it is taken.")
        for (file in files) {
            val taken = Instant.ofEpochMilli(file.lastModified()).atZone(cairo).format(stampTime)
            rows += text(taken).apply { setTypeface(typeface, Typeface.BOLD) }
            rows += button("Send as a Document") { share(file) }
        }
        rows += button("Back to the camera") { showCamera() }
        rows += button("Change my HR id or name") { showSetup() }
        setContentView(ScrollView(this).apply { addView(column(*rows.toTypedArray())) })
    }

    private fun showSealed(file: File) {
        stopCamera()
        val image = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        }
        setContentView(ScrollView(this).apply {
            addView(column(
                title("The photo is sealed"),
                text("Send it to the System Administrator on WhatsApp as a Document, not as a photo, so the seal arrives whole. " +
                    "With no signal here, send it from \"Photos to send\" once you have one, within 72 hours."),
                image,
                button("Send as a Document") { share(file) },
                button("Take another photo") { showCamera() },
            ))
        })
    }

    private fun showMessage(message: String, action: String?, onAction: (() -> Unit)?) {
        stopCamera()
        val views = mutableListOf<View>(title("Phoenix Camera"), text(message))
        if (action != null && onAction != null) views += button(action) { onAction() }
        setContentView(column(*views.toTypedArray()))
    }

    // ── Readiness and capture ────────────────────────────────────────────────────────────────────────────────

    /** Null when a photo can be taken now; otherwise what the rep should do. */
    private fun blocker(): String? {
        val current = watch ?: return "Looking for satellites…"
        if (!current.gpsOn()) return "Switch Location on in the phone's settings."
        val fix = current.location ?: return "Looking for satellites. Stand outside or next to a window."
        if (PhoneChecks.isMock(fix)) return "A fake-location app is giving the position. Switch it off to take the photo."
        if (current.fixAgeMs(fix) > 15_000) return "Waiting for a fresh position from the satellites…"
        if (current.used < 4) return "Satellites: ${current.used} of the 4 needed. Stand outside or next to a window."
        if (!fix.hasAccuracy() || fix.accuracy > 150f) return "The position is still rough. Stand outside or next to a window."
        return null
    }

    private fun refreshStatus() {
        val current = watch ?: return
        val problem = blocker()
        val fix = current.location
        statusView?.text = problem
            ?: "Ready · ${current.used} satellites used of ${current.seen} · within ${fix?.accuracy?.toInt() ?: 0} m"
        shutter?.isEnabled = problem == null && !busy
    }

    private fun capture() {
        val current = watch ?: return
        val use = imageCapture ?: return
        if (blocker() != null || busy) return
        val latest = current.location ?: return
        if (PhoneChecks.mockAppActive(this)) {
            toast("A fake-location app is switched on in this phone. Switch it off to take the photo.")
            return
        }
        val fix = Location(latest)
        val shutterFixAge = current.fixAgeMs(fix)
        val used = current.used
        val seen = current.seen
        busy = true
        refreshStatus()
        statusView?.text = "Sealing the photo…"
        use.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching { seal(image, fix, shutterFixAge, used, seen) }
                image.close()
                runOnUiThread {
                    busy = false
                    result.onSuccess { showSealed(it) }.onFailure {
                        toast("The photo could not be sealed: ${it.message ?: "try again"}")
                        refreshStatus()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    busy = false
                    toast("The camera did not take the photo. Try again.")
                    refreshStatus()
                }
            }
        })
    }

    private fun seal(image: ImageProxy, fix: Location, fixAgeMs: Long, used: Int, seen: Int): File {
        SealKey.ensure()
        val satelliteMs = fix.time + fixAgeMs
        val phoneMs = System.currentTimeMillis()
        val networkMs = PhoenixClock.nowMs(this)
        val takenAt = Instant.ofEpochMilli(satelliteMs)

        val upright = Sealer.upright(image.toBitmap(), image.imageInfo.rotationDegrees)
        val stamped = Sealer.stamp(upright, listOf(
            "${takenAt.atZone(cairo).format(stampTime)} Cairo · satellite time",
            "%.6f, %.6f · within %d m · %d satellites".format(Locale.ENGLISH, fix.latitude, fix.longitude, fix.accuracy.toInt(), used),
            "${repName()} (${repHrId()})",
            "Phoenix Camera · sealed",
        ))
        val jpeg = Sealer.encode(stamped)

        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val payload = JSONObject()
            .put("v", 1)
            .put("app", packageName)
            .put("appVersion", appVersion())
            .put("imageSha256", Sealer.sha256Hex(jpeg))
            .put("hrId", repHrId())
            .put("repName", repName())
            .put("lat", fix.latitude)
            .put("lon", fix.longitude)
            .put("accuracyMeters", fix.accuracy.toDouble())
            .put("satellitesUsed", used)
            .put("satellitesSeen", seen)
            .put("provider", fix.provider ?: "gps")
            .put("fixAgeMs", fixAgeMs)
            .put("takenAtUtc", takenAt.toString())
            .put("timeSource", "satellites")
            .put("phoneClockUtc", Instant.ofEpochMilli(phoneMs).toString())
            .put("networkTimeUtc", networkMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("mock", PhoneChecks.isMock(fix))
            .put("mockAppActive", PhoneChecks.mockAppActive(this))
            .put("rooted", PhoneChecks.rooted(this))
            .put("emulator", PhoneChecks.emulator())
            .put("developerOptions", PhoneChecks.developerOptions(this))
            .put("adb", PhoneChecks.adb(this))
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("nonce", nonce)
            .toString()

        val signature = SealKey.sign(payload.toByteArray(Charsets.UTF_8))
        val bytes = Sealer.assemble(jpeg, payload, signature, SealKey.chain())
        val folder = File(filesDir, "sealed").apply { mkdirs() }
        val file = File(folder, "Phoenix-Camera-${takenAt.atZone(cairo).format(fileTime)}.jpg")
        file.writeBytes(bytes)
        return file
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "com.satopharma.phoenixcamera.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            // A generic type makes WhatsApp send it as a Document, so the photo is never re-compressed.
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send as a Document"))
    }

    // ── Small helpers ────────────────────────────────────────────────────────────────────────────────────────

    private fun stopCamera() {
        watch?.stop()
        watch = null
        imageCapture = null
        statusView = null
        shutter = null
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
    }

    private fun sealedFiles(): List<File> =
        (File(filesDir, "sealed").listFiles()?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }

    /** Phoenix refuses a seal older than 72 hours, so the phone does not keep one. */
    private fun dropExpiredPhotos() {
        val cutoff = System.currentTimeMillis() - 72L * 3_600_000L
        sealedFiles().filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private fun prefs() = getSharedPreferences("rep", Context.MODE_PRIVATE)
    private fun repHrId(): String = prefs().getString("hrId", "") ?: ""
    private fun repName(): String = prefs().getString("name", "") ?: ""

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "1.0.0"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun column(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(20), dp(24), dp(20), dp(24))
        views.forEach { addView(it) }
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 24f
        setTextColor(Color.rgb(15, 23, 42))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(51, 65, 85))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun field(hint: String, type: Int, value: String) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        textSize = 18f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }
}
