package com.satopharma.phoenixcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Phoenix Camera: a live camera only (no gallery), the rep's position and time, and a seal Phoenix can check.
 * The rep sends the file to the System Administrator as a WhatsApp DOCUMENT, so it arrives whole; Phoenix accepts it
 * for 72 hours after it was taken.
 *
 * The rep never waits for the location (owner, 20 Nov 2026: "I don't want him to wait for the satellites ... he can
 * take the photo now, and make sure the location is accurate, at least 98%"). The location starts when the app opens
 * ([PositionWatch]: the phone's combined Wi-Fi, mobile-network and satellite position beside the satellites alone),
 * the shutter is always live, and the photo is sealed with the most accurate reading around its shutter as soon as
 * the phone is 98% sure of the place to within [EXACT_M] x 1.85 m, at worst [MAX_M] x 1.85 m. A fake-location mark
 * on any reading, a fake-location app switched on, a rooted phone or an emulator still stop the photo.
 *
 * Dressed in the Phoenix identity: the signature card's navy, orange and cream, square corners, flat colour.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** A reading this accurate (Android's 68% radius; 98% within about 37 m) seals the photo at once. */
        const val EXACT_M = 20f
        /** The least accurate reading a photo may carry (98% within about 74 m). Phoenix refuses anything wider. */
        const val MAX_M = 40f
        /** A reading this fresh at the shutter seals the photo at once. */
        const val INSTANT_AGE_MS = 15_000L
        /** After the shutter the location has this long to lock before the photo is given up. */
        const val LOCK_WAIT_MS = 20_000L
        /** The oldest reading a photo may carry. Phoenix allows two minutes. */
        const val BEFORE_MS = 120_000L
    }

    /** What the phone knew at the instant of the shutter. */
    private class Shot(val shutterNanos: Long, val phoneMs: Long, val satellitesUsed: Int, val satellitesSeen: Int)

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var watch: PositionWatch? = null
    private var imageCapture: ImageCapture? = null
    private var statusView: TextView? = null
    private var stateView: TextView? = null
    private var stateDot: View? = null
    private var satValue: TextView? = null
    private var accValue: TextView? = null
    private var timeLabel: TextView? = null
    private var timeValue: TextView? = null
    private var shutter: View? = null
    private var busy = false
    private val ticker = Handler(Looper.getMainLooper())
    // The camera screen counts every second even when the satellites send nothing, so "nothing in view" shows as such.
    private val tick = object : Runnable {
        override fun run() {
            if (statusView == null) return
            refreshStatus()
            ticker.postDelayed(this, 1_000L)
        }
    }

    private val cairo = ZoneId.of("Africa/Cairo")
    private val stampTime = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.ENGLISH)
    private val listDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val listTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ENGLISH)

    // ── The Phoenix identity ─────────────────────────────────────────────────────────────────────────────────
    private val navy = Color.rgb(11, 31, 58)
    private val navyRaised = Color.rgb(22, 44, 76)
    private val navyRule = Color.rgb(36, 60, 96)
    private val orange = Color.rgb(244, 123, 32)
    private val orangePressed = Color.rgb(214, 101, 16)
    private val cream = Color.rgb(253, 248, 243)
    private val paper = Color.WHITE
    private val ink = Color.rgb(42, 58, 92)
    private val inkSoft = Color.rgb(98, 112, 138)
    private val rule = Color.rgb(224, 208, 188)
    private val onNavySoft = Color.rgb(170, 184, 206)
    private val disabledFill = Color.rgb(46, 66, 98)
    private val disabledText = Color.rgb(120, 138, 166)

    private val bold: Typeface by lazy { ResourcesCompat.getFont(this, R.font.montserrat_bold) ?: Typeface.DEFAULT_BOLD }
    private val regular: Typeface by lazy { ResourcesCompat.getFont(this, R.font.montserrat_regular) ?: Typeface.DEFAULT }
    private val script: Typeface by lazy { ResourcesCompat.getFont(this, R.font.pinyon_script) ?: Typeface.SERIF }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        dropExpiredPhotos()
        worker.execute { PhoenixClock.refresh(this) }
        showSplash()
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onResume() {
        super.onResume()
        worker.execute { PhoenixClock.refresh(this) }
        startWatch()
    }

    override fun onPause() {
        watch?.stop()
        super.onPause()
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
        if (granted) startWatch()
        when {
            !granted -> showMessage(
                "Camera and precise location",
                "The Phoenix Camera needs the camera and your precise location to seal a photo.",
                "Allow both",
            ) { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)) }
            PhoneChecks.rooted(this) || PhoneChecks.emulator() -> showMessage(
                "This phone cannot seal photos",
                "Its system has been modified. Use a phone with its original system.",
                null,
                null,
            )
            repHrId().isEmpty() -> showSetup()
            else -> showCamera()
        }
    }

    /** The brand moment while the permission sheet is up. */
    private fun showSplash() {
        setScreen(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(navy)
            addView(emblem(132))
            addView(wordmark(30f, Gravity.CENTER).apply { setPadding(0, dp(20), 0, 0) })
        })
    }

    private fun showSetup() {
        stopCamera()
        val preview = TextView(this).apply {
            typeface = script
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTextColor(ink)
            setPadding(0, dp(4), 0, dp(2))
        }
        fun showName(value: String) {
            preview.text = value.ifBlank { "Your name" }
            preview.setTextColor(if (value.isBlank()) rule else ink)
        }
        val hrId = field("For example B-182", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, repHrId())
        val name = field("As it is on your Phoenix account", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, repName())
        showName(repName())
        name.doAfterTextChanged { showName(it?.toString()?.trim() ?: "") }

        val card = card(
            micro("Rep identity"),
            preview,
            divider("Phoenix Camera"),
            gap(12),
            micro("HR id", soft = true),
            hrId,
            gap(14),
            micro("Full name", soft = true),
            name,
            gap(20),
            wave = true,
        )
        val save = primaryButton("Save and open the camera") {
            val id = hrId.text.toString().trim().uppercase(Locale.ENGLISH)
            val full = name.text.toString().trim()
            if (!Regex("^[A-Z0-9-]{2,20}$").matches(id) || full.length < 3) {
                toast("Enter your own HR id and your full name.")
            } else {
                prefs().edit().putString("hrId", id).putString("name", full.take(120)).apply()
                showCamera()
            }
        }
        setScreen(page(
            heading("Rep identity", "Who is taking the photos"),
            body("Every photo you take carries your name and HR id. Enter your own, exactly as on your Phoenix account."),
            gap(16),
            card,
            gap(20),
            save,
        ))
    }

    private fun showCamera() {
        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val viewfinder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
            addView(preview)
            addView(FrameMarks(this@MainActivity, cream, dp(2).toFloat(), dp(26).toFloat(), dp(18).toFloat()).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }

        // The status band: one line of state, three readings, then what to do.
        val dot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) } }
        val state = micro("", color = cream)
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(state)
        }
        val sat = reading()
        val acc = reading()
        val timeL = micro("Source", color = orange)
        val timeV = reading()
        val readings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(cell(micro("Satellites", color = orange), sat))
            addView(vRule())
            addView(cell(micro("98% within", color = orange), acc))
            addView(vRule())
            addView(cell(timeL, timeV))
        }
        val status = TextView(this).apply {
            typeface = regular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(onNavySoft)
            setLineSpacing(0f, 1.2f)
            minLines = 2
        }
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(20), dp(14), dp(20), dp(4))
            addView(stateRow)
            addView(readings)
            addView(hRule(navyRule))
            addView(status.apply { setPadding(0, dp(10), 0, 0) })
        }

        val take = Shutter().apply { setOnClickListener { capture() }; isEnabled = false }
        val count = sealedFiles().size
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(20), dp(10), dp(20), dp(18))
            addView(sideButton(if (count > 0) "Photos · $count" else "Photos", Gravity.START) { showPhotos() })
            addView(take)
            addView(sideButton("My id", Gravity.END) { showSetup() })
        }

        statusView = status
        stateView = state
        stateDot = dot
        satValue = sat
        accValue = acc
        timeLabel = timeL
        timeValue = timeV
        shutter = take
        setScreen(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            addView(header(showRep = true))
            addView(viewfinder)
            addView(hRule(orange, 2))
            addView(band)
            addView(controls)
        })

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val captureUse = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, captureUse)
            imageCapture = captureUse
        }, ContextCompat.getMainExecutor(this))

        startWatch()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        refreshStatus()
    }

    private fun showPhotos() {
        stopCamera()
        val rows = mutableListOf<View>(
            heading("Sealed photos", "Photos to send"),
            body("Send each photo to the System Administrator on WhatsApp as a Document, not as a photo, so the seal arrives whole."),
            gap(16),
        )
        val files = sealedFiles()
        if (files.isEmpty()) {
            rows += card(
                micro("Nothing to send"),
                gap(6),
                body("No photos yet. A photo can be sent for 72 hours after it is taken."),
                gap(8),
                wave = true,
            )
        }
        for (file in files) {
            rows += photoCard(file)
            rows += gap(14)
        }
        rows += gap(6)
        rows += primaryButton("Back to the camera") { showCamera() }
        rows += gap(10)
        rows += outlineButton("Change my HR id or name") { showSetup() }
        setScreen(page(*rows.toTypedArray()))
    }

    private fun showSealed(file: File) {
        stopCamera()
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(decodeSampled(file, 1600))
            setBackgroundColor(navy)
        }
        setScreen(page(
            heading("Sealed", "The photo is sealed"),
            card(image, wave = false, padded = false),
            gap(16),
            body("Send it to the System Administrator on WhatsApp as a Document, not as a photo, so the seal arrives whole. " +
                "With no signal here, send it from \"Photos to send\" once you have one, within 72 hours."),
            gap(18),
            primaryButton("Send as a Document") { share(file) },
            gap(10),
            outlineButton("Take another photo") { showCamera() },
        ))
    }

    private fun showMessage(title: String, message: String, action: String?, onAction: (() -> Unit)?) {
        stopCamera()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(navy)
            setPadding(dp(28), dp(56), dp(28), dp(32))
            addView(emblem(112))
            addView(wordmark(26f, Gravity.CENTER).apply { setPadding(0, dp(16), 0, dp(28)) })
            addView(hRule(orange, 2).apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(2)) })
            addView(TextView(this@MainActivity).apply {
                text = title
                typeface = bold
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setPadding(0, dp(24), 0, dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text = message
                typeface = regular
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onNavySoft)
                setLineSpacing(0f, 1.25f)
            })
            if (action != null && onAction != null) {
                addView(gap(28))
                addView(primaryButton(action) { onAction() })
            }
        }
        setScreen(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(navy); addView(column) })
    }

    // ── Readiness and capture ────────────────────────────────────────────────────────────────────────────────

    private fun permitted(): Boolean = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The location starts as soon as the app may read it, before the camera screen, so it is ready by the shutter. */
    private fun startWatch() {
        if (!permitted()) return
        val current = watch ?: PositionWatch(this) { runOnUiThread { refreshStatus() } }.also { watch = it }
        current.start()
    }

    /** Null when a photo can be taken now; otherwise what stops it. The location never holds the shutter back. */
    private fun blocker(): String? {
        val current = watch ?: return "Starting the location…"
        if (!current.locationOn()) return "Switch Location on in the phone's settings."
        if (current.mockSeen()) return "A fake-location app is giving the position. Switch it off to take the photo."
        if (imageCapture == null) return "Starting the camera…"
        return null
    }

    /** The reading a photo taken now would be sealed with at once, or null while the location is still settling. */
    private fun lockedNow(current: PositionWatch): PositionWatch.Fix? =
        current.best(SystemClock.elapsedRealtimeNanos(), INSTANT_AGE_MS, 0L)?.takeIf { it.location.accuracy <= EXACT_M }

    private fun refreshStatus() {
        val current = watch ?: return
        if (statusView == null) return
        val problem = blocker()
        val locked = lockedNow(current)
        val shown = locked ?: current.best(SystemClock.elapsedRealtimeNanos(), INSTANT_AGE_MS, 0L) ?: current.latest()

        stateDot?.background = ColorDrawable(if (locked != null) orange else disabledText)
        stateView?.text = when {
            busy -> "SEALING THE PHOTO"
            locked != null -> "LOCATION LOCKED"
            shown != null -> "LOCATION SETTLING"
            else -> "FINDING THE LOCATION"
        }
        satValue?.text = "${current.satellites.used} / ${current.satellites.seen}"
        accValue?.text = shown?.let { "${PositionWatch.radius98(it.location.accuracy).roundToInt()} m" } ?: "—"
        timeValue?.text = when (shown?.source) {
            null -> "—"
            "gps" -> "Satellites"
            else -> "Wi-Fi + GPS"
        }
        if (!busy) {
            statusView?.text = problem ?: if (locked != null) {
                "Take the photo."
            } else {
                "Take the photo now. The location locks by itself in a few seconds."
            }
        }
        shutter?.isEnabled = problem == null && !busy
    }

    private fun capture() {
        val current = watch ?: return
        val use = imageCapture ?: return
        if (blocker() != null || busy) return
        if (PhoneChecks.mockAppActive(this)) {
            toast("A fake-location app is switched on in this phone. Switch it off to take the photo.")
            return
        }
        val shot = Shot(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), current.satellites.used, current.satellites.seen)
        busy = true
        refreshStatus()
        statusView?.text = "Photo taken. Locking the location…"
        use.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val photo = runCatching { Sealer.upright(image.toBitmap(), image.imageInfo.rotationDegrees) }
                image.close()
                runOnUiThread {
                    photo.onSuccess { lockThenSeal(it, shot) }.onFailure {
                        busy = false
                        toast("The camera did not take the photo. Try again.")
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

    /**
     * The photo is in hand. It is sealed with the most accurate reading around its shutter the moment one is exact
     * ([EXACT_M]); after [LOCK_WAIT_MS] the best reading within [MAX_M] is taken; with none the rep is told where to
     * stand and the photo is not kept.
     */
    private fun lockThenSeal(photo: Bitmap, shot: Shot) {
        val poll = object : Runnable {
            override fun run() {
                val current = watch
                if (current == null) {
                    busy = false
                    refreshStatus()
                    return
                }
                if (current.mockSeen()) {
                    busy = false
                    toast("A fake-location app is giving the position. Switch it off to take the photo.")
                    refreshStatus()
                    return
                }
                val sinceMs = (SystemClock.elapsedRealtimeNanos() - shot.shutterNanos) / 1_000_000
                val exact = current.best(shot.shutterNanos, INSTANT_AGE_MS, LOCK_WAIT_MS)?.takeIf { it.location.accuracy <= EXACT_M }
                val best = current.best(shot.shutterNanos, BEFORE_MS, LOCK_WAIT_MS)
                val chosen = exact ?: if (sinceMs >= LOCK_WAIT_MS) best?.takeIf { it.location.accuracy <= MAX_M } else null
                when {
                    chosen != null -> sealInBackground(photo, chosen, shot)
                    sinceMs >= LOCK_WAIT_MS -> {
                        busy = false
                        val sure = best?.let { "The phone is only sure of the place to ${PositionWatch.radius98(it.location.accuracy).roundToInt()} m. " } ?: ""
                        showMessage(
                            "The location is not exact enough here",
                            sure + "Step near a window or the door for a moment, then take the photo again.",
                            "Back to the camera",
                        ) { showCamera() }
                    }
                    else -> {
                        statusView?.text = "Photo taken. Locking the location" +
                            (best?.let { " · ${PositionWatch.radius98(it.location.accuracy).roundToInt()} m so far" } ?: "…")
                        ticker.postDelayed(this, 250L)
                    }
                }
            }
        }
        ticker.post(poll)
    }

    private fun sealInBackground(photo: Bitmap, fix: PositionWatch.Fix, shot: Shot) {
        statusView?.text = "Sealing the photo…"
        worker.execute {
            val result = runCatching { seal(photo, fix, shot) }
            runOnUiThread {
                busy = false
                result.onSuccess { showSealed(it) }.onFailure {
                    toast("The photo could not be sealed: ${it.message ?: "try again"}")
                    refreshStatus()
                }
            }
        }
    }

    private fun seal(photo: Bitmap, fix: PositionWatch.Fix, shot: Shot): File {
        SealKey.ensure()
        val sinceShutterMs = (SystemClock.elapsedRealtimeNanos() - shot.shutterNanos) / 1_000_000
        // Positive: the reading was taken before the shutter; negative: just after it, while the location locked.
        val fixAgeMs = -fix.offsetMs(shot.shutterNanos)
        val satelliteMs = SatelliteClock.utcAt(this, shot.shutterNanos)
        val networkMs = PhoenixClock.nowMs(this)?.minus(sinceShutterMs)
        val takenMs: Long
        val timeSource: String
        val timeWords: String
        when {
            satelliteMs != null -> { takenMs = satelliteMs; timeSource = "satellites"; timeWords = "satellite time" }
            networkMs != null -> { takenMs = networkMs; timeSource = "phoenix"; timeWords = "Phoenix time" }
            else -> { takenMs = shot.phoneMs; timeSource = "phone"; timeWords = "phone's clock" }
        }
        val takenAt = Instant.ofEpochMilli(takenMs)
        val used = if (fix.source == "gps") fix.satellites else shot.satellitesUsed
        val radius = PositionWatch.radius98(fix.location.accuracy).roundToInt()

        val stamped = Sealer.stamp(photo, listOfNotNull(
            "${takenAt.atZone(cairo).format(stampTime)} Cairo · $timeWords",
            "%.6f, %.6f · 98%% within %d m".format(Locale.ENGLISH, fix.location.latitude, fix.location.longitude, radius) +
                (if (used > 0) " · $used satellites" else ""),
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
            .put("lat", fix.location.latitude)
            .put("lon", fix.location.longitude)
            .put("accuracyMeters", fix.location.accuracy.toDouble())
            .put("satellitesUsed", used)
            .put("satellitesSeen", shot.satellitesSeen)
            .put("provider", fix.source)
            .put("fixAgeMs", fixAgeMs)
            .put("takenAtUtc", takenAt.toString())
            .put("timeSource", timeSource)
            .put("phoneClockUtc", Instant.ofEpochMilli(shot.phoneMs).toString())
            .put("networkTimeUtc", networkMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("mock", fix.mock)
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
        imageCapture = null
        statusView = null
        stateView = null
        stateDot = null
        satValue = null
        accValue = null
        timeLabel = null
        timeValue = null
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

    private fun clock(seconds: Long): String = "%d:%02d".format(Locale.ENGLISH, seconds / 60, seconds % 60)

    /** A picture small enough for the screen; a sealed file is a JPEG with the seal after its end, which decoders skip. */
    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ── The Phoenix look: square corners, flat colour, Montserrat, the signature card's wave and diamond ────────

    /** Every screen sits inside the system bars, on navy, so the bars read as part of the app. */
    private fun setScreen(content: View) {
        val root = FrameLayout(this).apply {
            setBackgroundColor(navy)
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    /** Navy header, cream page that scrolls. */
    private fun page(vararg views: View): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            views.forEach { addView(it) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            addView(header(showRep = false))
            addView(hRule(orange, 2))
            addView(ScrollView(this@MainActivity).apply {
                setBackgroundColor(cream)
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(column)
            })
        }
    }

    private fun header(showRep: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(navy)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        addView(emblem(34).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10) })
        addView(wordmark(17f, Gravity.START).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (showRep && repHrId().isNotEmpty()) {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(TextView(this@MainActivity).apply {
                    text = repName().split(" ").filter { it.isNotBlank() }.take(2).joinToString(" ")
                    maxWidth = dp(180)
                    typeface = script
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    setTextColor(cream)
                    setSingleLine()
                    gravity = Gravity.END
                })
                addView(micro(repHrId(), color = orange).apply { gravity = Gravity.END })
            })
        }
    }

    /** PHOENIX in white over CAMERA in orange, the sidebar lockup's two-line wordmark. */
    private fun wordmark(sizeSp: Float, align: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = align
        addView(TextView(this@MainActivity).apply {
            text = "PHOENIX"
            typeface = bold
            letterSpacing = 0.12f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            gravity = align
        })
        addView(TextView(this@MainActivity).apply {
            text = "CAMERA"
            typeface = bold
            letterSpacing = 0.42f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * 0.42f)
            setTextColor(orange)
            includeFontPadding = false
            gravity = align
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun emblem(sizeDp: Int) = ImageView(this).apply {
        setImageResource(R.drawable.phoenix_emblem)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Phoenix"
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** The bold, widely spaced orange label of the signature card. */
    private fun micro(value: String, color: Int = orange, soft: Boolean = false) = TextView(this).apply {
        text = value.uppercase(Locale.ENGLISH)
        typeface = bold
        letterSpacing = 0.22f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(if (soft) inkSoft else color)
        includeFontPadding = false
    }

    private fun heading(label: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(micro(label))
        addView(TextView(this@MainActivity).apply {
            text = title
            typeface = bold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(navy)
            setPadding(0, dp(8), 0, dp(6))
        })
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        typeface = regular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ink)
        setLineSpacing(0f, 1.25f)
    }

    private fun gap(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun hRule(color: Int, thicknessDp: Int = 1) = View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(thicknessDp))
    }

    private fun vRule() = View(this).apply {
        setBackgroundColor(navyRule)
        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** The orange hairline and small square diamond of the signature card, with its label between. */
    private fun divider(label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        fun line() = View(this@MainActivity).apply {
            setBackgroundColor(orange)
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        }
        fun diamond() = View(this@MainActivity).apply {
            setBackgroundColor(orange)
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { leftMargin = dp(4); rightMargin = dp(4) }
        }
        addView(line())
        addView(diamond())
        addView(micro(label).apply { setPadding(dp(6), 0, dp(6), 0) })
        addView(diamond())
        addView(line())
    }

    /** A white card ruled in the card's sand colour, with the navy wave and orange stroke along its foot. */
    private fun card(vararg views: View, wave: Boolean, padded: Boolean = true): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (padded) setPadding(dp(20), dp(20), dp(20), 0)
            views.forEach { addView(it) }
        }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(paper)
                setStroke(dp(1), rule)
                cornerRadius = 0f
            }
            setPadding(dp(1), dp(1), dp(1), dp(1))
            val column = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(inner)
                if (wave) addView(Wave(this@MainActivity, navy, orange, dp(2).toFloat()).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
                })
            }
            addView(column)
            if (wave) addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.phoenix_emblem)
                alpha = 0.10f
                layoutParams = FrameLayout.LayoutParams(dp(76), dp(76), Gravity.END or Gravity.BOTTOM).apply {
                    rightMargin = dp(10); bottomMargin = dp(10)
                }
            })
        }
    }

    private fun photoCard(file: File): View {
        val taken = Instant.ofEpochMilli(file.lastModified()).atZone(cairo)
        val until = taken.plusHours(72)
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(navy)
            setImageBitmap(decodeSampled(file, 320))
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(14) }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(micro("Sealed · ${taken.format(listTime)} Cairo"))
            addView(TextView(this@MainActivity).apply {
                text = taken.format(listDay)
                typeface = bold
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(navy)
                setPadding(0, dp(6), 0, dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Send by ${until.format(listDay)}, ${until.format(listTime)}"
                typeface = regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(inkSoft)
            })
            addView(TextView(this@MainActivity).apply {
                text = "SEND AS A DOCUMENT"
                typeface = bold
                letterSpacing = 0.12f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(orange)
                setPadding(0, dp(12), dp(12), dp(6))
                setOnClickListener { share(file) }
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
            addView(thumb)
            addView(text)
        }
        return card(row, wave = true)
    }

    private fun field(hint: String, type: Int, value: String) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        typeface = bold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(navy)
        setHintTextColor(rule)
        setSingleLine()
        val underline = this
        fun dress(focused: Boolean) {
            underline.background = LayerDrawable(arrayOf<Drawable>(ColorDrawable(if (focused) orange else rule), ColorDrawable(paper))).apply {
                setLayerInset(1, 0, 0, 0, dp(if (focused) 2 else 1))
            }
            underline.setPadding(0, dp(8), 0, dp(10))
        }
        dress(false)
        setOnFocusChangeListener { _, focused -> dress(focused) }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label.uppercase(Locale.ENGLISH)
        typeface = bold
        letterSpacing = 0.14f
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabledText, Color.WHITE)))
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(disabledFill))
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(orangePressed))
            addState(intArrayOf(), ColorDrawable(orange))
        }
        isClickable = true
        isFocusable = true
        minHeight = dp(54)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label.uppercase(Locale.ENGLISH)
        typeface = bold
        letterSpacing = 0.14f
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(navy)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(rule); setStroke(dp(1), navy) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), navy) })
        }
        isClickable = true
        isFocusable = true
        minHeight = dp(50)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sideButton(label: String, align: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label.uppercase(Locale.ENGLISH)
        typeface = bold
        letterSpacing = 0.16f
        gravity = align or Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(cream)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun reading() = TextView(this).apply {
        typeface = bold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Color.WHITE)
        setSingleLine()
        setPadding(0, dp(6), 0, 0)
    }

    private fun cell(label: TextView, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        label.gravity = Gravity.CENTER
        value.gravity = Gravity.CENTER
        addView(label)
        addView(value)
    }

    /** The shutter: a square orange key inside a cream square frame; slate when the position is not ready. */
    private inner class Shutter : View(this@MainActivity) {
        init {
            contentDescription = "Take the photo"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
            fun key(frame: Int, fill: Int) = LayerDrawable(arrayOf<Drawable>(
                GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(2), frame) },
                ColorDrawable(fill),
            )).apply { setLayerInset(1, dp(7), dp(7), dp(7), dp(7)) }
            background = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), key(navyRule, disabledFill))
                addState(intArrayOf(android.R.attr.state_pressed), key(cream, orangePressed))
                addState(intArrayOf(), key(cream, orange))
            }
        }
    }
}

/** The signature card's foot: a navy wave with an orange stroke along its crest (M0 18 C100 28 260 28 390 8). */
private class Wave(context: Context, waveColor: Int, crestColor: Int, crestWidth: Float) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = waveColor; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = crestColor; style = Paint.Style.STROKE; strokeWidth = crestWidth }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fun x(v: Float) = v / 390f * w
        fun y(v: Float) = v / 38f * h
        val crest = Path().apply {
            moveTo(0f, y(18f))
            cubicTo(x(100f), y(28f), x(260f), y(28f), w, y(8f))
        }
        val body = Path(crest).apply {
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(body, fill)
        canvas.drawPath(crest, stroke)
    }
}

/** Four square corner marks over the live picture, like a viewfinder. */
private class FrameMarks(context: Context, markColor: Int, private val thickness: Float, private val length: Float, private val inset: Float) : View(context) {
    private val paint = Paint().apply { color = markColor; style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val l = inset
        val t = inset
        val r = width - inset
        val b = height - inset
        canvas.drawRect(l, t, l + length, t + thickness, paint)
        canvas.drawRect(l, t, l + thickness, t + length, paint)
        canvas.drawRect(r - length, t, r, t + thickness, paint)
        canvas.drawRect(r - thickness, t, r, t + length, paint)
        canvas.drawRect(l, b - thickness, l + length, b, paint)
        canvas.drawRect(l, b - length, l + thickness, b, paint)
        canvas.drawRect(r - length, b - thickness, r, b, paint)
        canvas.drawRect(r - thickness, b - length, r, b, paint)
    }
}
