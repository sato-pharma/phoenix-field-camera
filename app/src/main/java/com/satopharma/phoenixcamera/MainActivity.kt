package com.satopharma.phoenixcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import android.text.method.PasswordTransformationMethod
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
import androidx.activity.OnBackPressedCallback
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Phoenix Camera 2.2: the rep's own visit app for a clinic with no network (owner, 20 Nov 2026).
 *
 * He signs in with his HR ID and Phoenix password once, with network; the phone then keeps his list, today's plan and
 * the line's products. With no network he chooses Planned or Unplanned, picks the customer, fills the same visit the
 * main account asks for, and takes a live photo. The photo is sealed with the place and time and the visit it was
 * taken for, and waits on the phone. The moment Phoenix answers again the visit sends itself: the validation and the
 * visit register in Phoenix, and nobody sends anything by hand. While Phoenix answers, the camera refuses a visit:
 * "you have network, go validate in the main account".
 *
 * The rep never waits for the location, and no reading is shown to him: no satellites, no accuracy, no distance. The
 * seal carries all of it to Phoenix, where it is read above him only.
 *
 * Dressed in the Phoenix identity: the signature card's navy, orange and cream, square corners, flat colour.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** A reading this fresh and within [Pending.EXACT_M] shows "location locked" on the camera screen. */
        const val INSTANT_AGE_MS = 15_000L
        /** How often a visit waiting for its place looks at the newest readings. */
        const val PENDING_EVERY_MS = 2_000L
        /** How often the phone asks whether Phoenix answers. */
        const val NETWORK_EVERY_MS = 15_000L
        /** With network, the list and the time anchor are read again at most this often. */
        const val REFRESH_EVERY_MS = 10L * 60_000L
    }

    /** What the phone knew at the instant of the shutter. */
    private class Shot(
        val shutterNanos: Long,
        val phoneMs: Long,
        val satellitesUsed: Int,
        val satellitesSeen: Int,
        val satelliteMs: Long?,
        val networkMs: Long?,
        val offline: JSONObject?,
        val bootId: String,
    )

    /**
     * What a photo is being taken for (camera 2.2): a visit ([body] is its form), a validation of a customer with no
     * place yet, or a new customer ([newCustomer] is everything the rep typed). The last two carry no visit form; all
     * they say travels inside the seal.
     */
    private class VisitDraft(
        val intent: String,
        val customerId: String,
        val customerName: String,
        /** Phoenix already holds his place: no photo-kind question. */
        val validated: Boolean,
        val body: JSONObject?,
        val newCustomer: JSONObject?,
        /** Where Back goes from the photo-kind screen (or from the camera when there is none). */
        val back: () -> Unit,
    ) {
        /** The item's own id: the visit's id for a visit, a fresh one for a validation or a new customer. */
        val visitId: String = UUID.randomUUID().toString()
        /** What the photo shows: the place, or, for a customer with no place yet, one of the four doctor's-name photos. */
        var photoKind: String = "place"
    }

    /** The new-customer form's answers, kept while the rep goes to the photo and back. */
    private class NewCustomerState {
        var listType: String? = null
        var first = ""
        var middle = ""
        var last = ""
        var name = ""
        var customerType: String? = null
        var speciality: String? = null
        var grade: String? = null
        var phone = ""
        val customerId: String = UUID.randomUUID().toString()
    }

    private enum class Screen { START, LOGIN, HOME, LIST, FORM, NEW_CUSTOMER, KIND, CAMERA, OTHER }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val net: ExecutorService = Executors.newSingleThreadExecutor()
    private var watch: PositionWatch? = null
    private var imageCapture: ImageCapture? = null
    private var statusView: TextView? = null
    private var stateView: TextView? = null
    private var stateDot: View? = null
    private var shutter: View? = null
    private var busy = false
    private var screen = Screen.START
    private var draft: VisitDraft? = null
    /** A checked update waiting for the rep to allow "Install unknown apps" for Phoenix Camera. */
    private var waitingInstall: File? = null

    /** Null until the first answer; true while Phoenix answers. */
    private var online: Boolean? = null
    private var needsSignIn = false
    private var lastRefreshMs = 0L
    private var sending = false
    /** Where Back goes from the screen on show, or null where there is nowhere to go back to (the home screen). */
    private var backAction: (() -> Unit)? = null
    /** Draws the screen on show again after Refresh; null on a screen with typing in it, which is never redrawn. */
    private var redraw: (() -> Unit)? = null
    private var refreshing = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (statusView == null) return
            refreshStatus()
            ticker.postDelayed(this, 1_000L)
        }
    }
    private val networkLoop = object : Runnable {
        override fun run() {
            checkNetwork()
            ticker.postDelayed(this, NETWORK_EVERY_MS)
        }
    }
    private var pendingBusy = false
    /** While the app is open, a visit waiting for its place takes the first good reading as it arrives. */
    private val pendingLoop = object : Runnable {
        override fun run() {
            advancePending()
            ticker.postDelayed(this, PENDING_EVERY_MS)
        }
    }

    private val cairo = ZoneId.of("Africa/Cairo")
    private val stampTime = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.ENGLISH)

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

    /** The camera and the location; on Android 13 and later also the notifications that say visits are waiting. */
    private fun asked(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        worker.execute {
            dropOrphanPhotos()
            PhoenixClock.refresh(this)
            Notifier.refresh(this)
        }
        SendWork.schedule(this)
        // The phone's own Back key goes where the Back button at the top goes; on the home screen it leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val back = backAction
                if (back != null) {
                    back()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        showSplash()
        permissions.launch(asked())
    }

    override fun onResume() {
        super.onResume()
        startWatch()
        ticker.removeCallbacks(networkLoop)
        ticker.post(networkLoop)
        ticker.removeCallbacks(pendingLoop)
        ticker.post(pendingLoop)
        waitingInstall?.let { file -> if (file.exists() && Updater.mayInstall(this)) installUpdate(file) }
    }

    override fun onPause() {
        watch?.stop()
        ticker.removeCallbacks(networkLoop)
        ticker.removeCallbacks(pendingLoop)
        super.onPause()
    }

    /** Seals every pending visit whose place has arrived (or whose window has passed) and sends it on its way. */
    private fun advancePending() {
        if (pendingBusy) return
        pendingBusy = true
        val current = watch
        worker.execute {
            try {
                if (Pending.any(this) && Pending.advance(this, current) > 0) {
                    SendWork.kick(this)
                    Notifier.refresh(this)
                }
            } finally {
                runOnUiThread { pendingBusy = false }
            }
        }
    }

    override fun onDestroy() {
        watch?.stop()
        worker.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    // ── The network: refuse while Phoenix answers, and send everything waiting the moment it does ────────────

    private fun checkNetwork() {
        net.execute {
            val answers = PhoenixApi.reachable()
            var signIn = false
            if (answers) {
                PhoenixClock.refresh(this)
                if (PhoenixApi.signedInOnce(this) && PhoenixApi.token(this) == null) {
                    signIn = true
                } else if (PhoenixApi.token(this) != null) {
                    if (System.currentTimeMillis() - lastRefreshMs > REFRESH_EVERY_MS || FieldStore.pack(this) == null) {
                        runCatching {
                            val pack = PhoenixApi.pack(this)
                            FieldStore.savePack(this, pack)
                            PhoenixApi.refreshAnchor(this, pack.getString("seatId"))
                            lastRefreshMs = System.currentTimeMillis()
                        }.onFailure { if ((it as? PhoenixApi.Refused)?.status == 401) signIn = true }
                    }
                    // Every time Phoenix answers, the anchor is renewed once it is five minutes old.
                    if (!signIn) FieldStore.pack(this)?.let { pack -> runCatching { PhoenixApi.refreshAnchorIfOld(this, pack.seatId) } }
                    if (!signIn && FieldStore.waiting(this)) {
                        runOnUiThread { sending = true; if (screen == Screen.HOME) showHome() }
                        if (FieldStore.drain(this) == FieldStore.Outcome.SIGN_IN) signIn = true
                    }
                }
            }
            Notifier.refresh(this)
            runOnUiThread {
                val changed = online != answers || needsSignIn != signIn || sending
                online = answers
                needsSignIn = signIn
                sending = false
                if (screen == Screen.HOME && changed) showHome()
                if (screen == Screen.CAMERA) refreshStatus()
            }
        }
    }

    // ── Screens ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun showStart() {
        screen = Screen.START
        backAction = null
        redraw = null
        val granted = permitted()
        if (granted) startWatch()
        when {
            !granted -> showMessage(
                "Camera and precise location",
                "The Phoenix Camera needs the camera and your precise location to seal a photo.",
                "Allow both",
            ) { permissions.launch(asked()) }
            PhoneChecks.rooted(this) || PhoneChecks.emulator() -> showMessage(
                "This phone cannot seal photos",
                "Its system has been modified. Use a phone with its original system.",
                null,
                null,
            )
            !PhoenixApi.signedInOnce(this) || FieldStore.pack(this) == null -> showLogin(null)
            else -> showHome()
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

    private fun showLogin(note: String?) {
        stopCamera()
        screen = Screen.LOGIN
        backAction = null
        redraw = null
        val hrId = field("For example B-182", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, PhoenixApi.hrId(this))
        val password = field("Your Phoenix password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "").apply {
            // After the single-line dressing, which would otherwise show the password.
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val problem = body("").apply { setTextColor(orangePressed); visibility = View.GONE }
        lateinit var signIn: TextView
        signIn = primaryButton("Sign in") {
            val id = hrId.text.toString().trim().uppercase(Locale.ENGLISH)
            val secret = password.text.toString()
            val previous = PhoenixApi.hrId(this)
            when {
                !Regex("^[A-Z0-9-]{2,35}$").matches(id) || secret.isEmpty() -> {
                    problem.text = "Enter your HR ID and your Phoenix password."
                    problem.visibility = View.VISIBLE
                }
                previous.isNotEmpty() && previous != id && Notifier.anythingWaiting(this) -> {
                    problem.text = "Visits taken under $previous are waiting to send. Sign in as $previous first."
                    problem.visibility = View.VISIBLE
                }
                else -> {
                    signIn.isEnabled = false
                    problem.visibility = View.GONE
                    net.execute {
                        val result = runCatching {
                            if (previous.isNotEmpty() && previous != id) FieldStore.clear(this)
                            PhoenixApi.signIn(this, id, secret)
                            val pack = PhoenixApi.pack(this)
                            FieldStore.savePack(this, pack)
                            PhoenixApi.refreshAnchor(this, pack.getString("seatId"))
                            lastRefreshMs = System.currentTimeMillis()
                        }
                        runOnUiThread {
                            signIn.isEnabled = true
                            result.onSuccess {
                                online = true
                                needsSignIn = false
                                showHome()
                            }.onFailure { error ->
                                problem.text = when {
                                    error is PhoenixApi.Refused && error.status == 404 ->
                                        "This camera is for a medical rep's own territory. Your login has no territory of its own."
                                    error is PhoenixApi.Refused -> error.message ?: "Phoenix did not sign you in."
                                    else -> "Signing in needs network. Try again where there is network."
                                }
                                problem.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        val card = card(
            micro("Sign in"),
            gap(12),
            micro("HR ID", soft = true),
            hrId,
            gap(14),
            micro("Password", soft = true),
            password,
            gap(20),
            wave = true,
        )
        setScreen(page(
            heading("Phoenix account", "Sign in with your HR ID"),
            body(note ?: "Use the same HR ID and password as your Phoenix account. Sign in where there is network; your list stays on the phone for the places with none."),
            gap(16),
            card,
            gap(12),
            problem,
            gap(12),
            signIn,
        ))
    }

    private fun showHome() {
        stopCamera()
        screen = Screen.HOME
        backAction = null
        redraw = { showHome() }
        draft = null
        val pack = FieldStore.pack(this) ?: return showLogin(null)
        val rows = mutableListOf<View>()
        val waiting = Notifier.anythingWaiting(this)
        val refused = FieldStore.queued(this).filter { it.has("refused") }

        if (Updater.available(this, pack.latestApp)) {
            val latest = pack.latestApp
            rows += card(
                micro("Update the app"),
                gap(6),
                body("A new version of Phoenix Camera is ready${latest?.versionName?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}."),
                gap(14),
                primaryButton("Update the app") { startUpdate() },
                gap(16),
                wave = false,
            )
            rows += gap(18)
        }
        if (online == true) {
            rows += heading("You have network", "Use the main account")
            rows += body("You have network, so record and validate this visit in the main Phoenix account. The camera is for the places with no network.")
            rows += gap(16)
            rows += card(
                micro(if (needsSignIn) "Sign in again" else "Ready for no network"),
                gap(6),
                body(when {
                    needsSignIn -> "Your login has ended. Sign in again so your list stays current and your visits can send."
                    sending || waiting -> "Your visits are sending to Phoenix now."
                    else -> "Your list and today's plan are on the phone. Every visit taken here has reached Phoenix."
                }),
                gap(12),
                wave = true,
            )
            if (needsSignIn) {
                rows += gap(16)
                rows += primaryButton("Sign in again") { showLogin("Your login has ended. Sign in again so your visits can send.") }
            }
        } else {
            rows += heading(pack.repName.ifBlank { "Today's visits" }, "Record a visit")
            rows += body(if (online == null) "Checking for network…" else "No network here. Choose the visit, take the photo, and it sends itself when there is network.")
            rows += gap(18)
            rows += primaryButton("Planned visit") { showList(planned = true) }
            rows += gap(10)
            rows += outlineButton("Unplanned visit") { showList(planned = false) }
            rows += gap(10)
            rows += outlineButton("Add new customer") {
                if (pack.newCustomerOptions == null) {
                    showMessage(
                        "Add a new customer",
                        "A new customer is added from the main account, with network: there you take his place and send him for approval.",
                        "Back",
                    ) { showHome() }
                } else {
                    showNewCustomer(NewCustomerState())
                }
            }
            rows += gap(10)
            rows += outlineButton("Validate a customer") { showValidateList() }
            if (waiting) {
                rows += gap(16)
                rows += card(
                    micro("Waiting to be sent"),
                    gap(6),
                    body("They are saved on the phone and send themselves to Phoenix when there is network, even with the app closed."),
                    gap(12),
                    wave = true,
                )
            }
        }
        for (item in refused) {
            rows += gap(14)
            rows += card(
                micro(when (item.optString("intent", "visit")) {
                    "validate" -> "Validation"
                    "new_customer" -> "New customer"
                    else -> "Visit"
                }),
                gap(6),
                TextView(this).apply {
                    text = item.optString("customerName")
                    typeface = bold
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(navy)
                },
                gap(4),
                body(item.optString("refused")),
                gap(10),
                outlineButton("Remove from the phone") {
                    FieldStore.forget(this, item.optString("visitId"))
                    showHome()
                },
                gap(16),
                wave = false,
            )
        }
        if (online == true && !waiting && !needsSignIn) {
            rows += gap(20)
            rows += outlineButton("Sign out") {
                PhoenixApi.signOut(this)
                FieldStore.clear(this)
                showLogin(null)
            }
        }
        setScreen(page(*rows.toTypedArray()))
    }

    private fun showList(planned: Boolean) {
        stopCamera()
        screen = Screen.LIST
        backAction = { showHome() }
        redraw = { showList(planned) }
        val pack = FieldStore.pack(this) ?: return showLogin(null)
        val all = pack.customers.filter { it.plannedToday == planned }.sortedBy { it.name }
        val listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render(query: String) {
            listColumn.removeAllViews()
            val shown = all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || (it.speciality ?: "").contains(query, ignoreCase = true) }
            if (shown.isEmpty()) {
                listColumn.addView(body(if (all.isEmpty()) {
                    if (planned) "No customer is planned for today on the list this phone holds." else "Every customer on your list is planned for today."
                } else "No customer by that name."))
            }
            for (customer in shown) {
                listColumn.addView(customerRow(customer) { showForm(customer, planned) })
                listColumn.addView(gap(8))
            }
        }
        val search = field("Search by name", InputType.TYPE_CLASS_TEXT, "")
        search.doAfterTextChanged { render(it?.toString()?.trim() ?: "") }
        render("")
        val day = runCatching { java.time.LocalDate.parse(pack.visitDay).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) }.getOrNull()
        setScreen(page(
            heading(if (planned) "Planned visit" else "Unplanned visit", "Choose the customer"),
            body(if (planned) "Your plan for ${day ?: "today"}." else "Customers on your list who are not on today's plan."),
            gap(14),
            search,
            gap(14),
            listColumn,
            gap(14),
            outlineButton("Back") { showHome() },
        ))
    }

    private fun customerRow(customer: PackCustomer, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(rule); setStroke(dp(1), rule) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(paper); setStroke(dp(1), rule) })
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(TextView(this@MainActivity).apply {
            text = customer.name
            typeface = bold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(navy)
        })
        val detail = listOfNotNull(customer.speciality, listWords(customer.listType), if (customer.visitedToday) "Visited today" else null).joinToString(" · ")
        if (detail.isNotEmpty()) addView(micro(detail, soft = true).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun listWords(listType: String): String? = when (listType.lowercase(Locale.ENGLISH)) {
        "am" -> "AM"
        "pm" -> "PM"
        "pharmacy" -> "Pharmacy"
        else -> null
    }

    private fun showForm(customer: PackCustomer, planned: Boolean) {
        stopCamera()
        screen = Screen.FORM
        backAction = { showList(planned) }
        redraw = null
        val pack = FieldStore.pack(this) ?: return showLogin(null)
        val chosenPeople = linkedSetOf<String>()
        val chosenProducts = linkedSetOf<String>().apply { addAll(customer.lastProducts.filter { it in pack.products }) }

        val peopleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderPeople() {
            peopleColumn.removeAllViews()
            peopleColumn.addView(toggleRow("Alone", chosenPeople.isEmpty()) { chosenPeople.clear(); renderPeople() })
            for (person in pack.people) {
                peopleColumn.addView(toggleRow(person.name, person.hrId in chosenPeople) {
                    if (!chosenPeople.remove(person.hrId)) chosenPeople.add(person.hrId)
                    renderPeople()
                })
            }
        }
        renderPeople()

        val productColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderProducts() {
            productColumn.removeAllViews()
            for (product in pack.products) {
                productColumn.addView(toggleRow(product, product in chosenProducts) {
                    if (!chosenProducts.remove(product)) chosenProducts.add(product)
                    renderProducts()
                })
            }
        }
        renderProducts()

        val comment = area("What was discussed")
        val reason = if (planned) null else area("Why this visit was not on the plan (optional)")
        val problem = body("").apply { setTextColor(orangePressed); visibility = View.GONE }

        val take = primaryButton("Take the photo") {
            val words = comment.text.toString().trim()
            val people = pack.people.filter { it.hrId in chosenPeople }
            val managers = people.filter { it.side == "manager" }
            val message = when {
                chosenProducts.isEmpty() -> "Choose the products you discussed."
                words.isEmpty() -> "Write what was discussed."
                people.size >= 2 && managers.isEmpty() -> "A visit with two or more people needs the district manager among them."
                online == true -> "You have network. Record this visit in the main account."
                else -> null
            }
            if (message != null) {
                problem.text = message
                problem.visibility = View.VISIBLE
                return@primaryButton
            }
            val body = JSONObject()
                .put("customerId", customer.customerId)
                .put("products", JSONArray(chosenProducts.toList()))
                .put("comment", words)
                .put("visitType", if (planned) "planned" else "out_of_plan")
            reason?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("unplannedReason", it) }
            when {
                people.isEmpty() -> body.put("kind", "single")
                people.size == 1 && people[0].side == "manager" -> body.put("kind", "double").put("managerHrId", people[0].hrId)
                people.size == 1 -> body.put("kind", "product_manager").put("productManagerHrId", people[0].hrId)
                else -> {
                    body.put("kind", "chain").put("managerHrId", managers[0].hrId)
                    people.firstOrNull { it.side == "product" }?.let { body.put("productManagerHrId", it.hrId) }
                    managers.getOrNull(1)?.let { body.put("seniorHrId", it.hrId) }
                    body.put("companions", JSONArray(people.map { JSONObject().put("hrId", it.hrId).put("side", it.side) }))
                }
            }
            val visit = VisitDraft(
                intent = "visit",
                customerId = customer.customerId,
                customerName = customer.name,
                validated = customer.validated,
                body = body,
                newCustomer = null,
                back = { showForm(customer, planned) },
            )
            draft = visit
            if (customer.validated) showCamera() else showPhotoKind(visit)
        }

        val rows = mutableListOf<View>(
            heading(if (planned) "Planned visit" else "Unplanned visit", customer.name),
            gap(8),
            micro("Who was on the visit"),
            gap(8),
            peopleColumn,
            gap(18),
            micro("Products discussed"),
            gap(8),
            productColumn,
            gap(18),
            micro("Comment"),
            gap(6),
            comment,
        )
        if (reason != null) {
            rows += gap(16)
            rows += micro("Reason", soft = true)
            rows += gap(6)
            rows += reason
        }
        rows += gap(14)
        rows += problem
        rows += gap(12)
        rows += take
        rows += gap(10)
        rows += outlineButton("Back") { showList(planned) }
        setScreen(page(*rows.toTypedArray()))
    }

    /**
     * Validate a customer with no network (camera 2.2, owner 20 Nov 2026: "because there are bricks that have no
     * network"): his place only, no visit. The photo goes for approval in Phoenix exactly as a visit's does.
     */
    private fun showValidateList() {
        stopCamera()
        screen = Screen.LIST
        backAction = { showHome() }
        redraw = { showValidateList() }
        val pack = FieldStore.pack(this) ?: return showLogin(null)
        val all = pack.customers.filter { !it.validated }.sortedBy { it.name }
        val listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render(query: String) {
            listColumn.removeAllViews()
            val shown = all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || (it.speciality ?: "").contains(query, ignoreCase = true) }
            if (shown.isEmpty()) {
                listColumn.addView(body(if (all.isEmpty()) "Every customer on your list already has his place in Phoenix." else "No customer by that name."))
            }
            for (customer in shown) {
                listColumn.addView(customerRow(customer) {
                    val visit = VisitDraft(
                        intent = "validate",
                        customerId = customer.customerId,
                        customerName = customer.name,
                        validated = false,
                        body = null,
                        newCustomer = null,
                        back = { showValidateList() },
                    )
                    draft = visit
                    showPhotoKind(visit)
                })
                listColumn.addView(gap(8))
            }
        }
        val search = field("Search by name", InputType.TYPE_CLASS_TEXT, "")
        search.doAfterTextChanged { render(it?.toString()?.trim() ?: "") }
        render("")
        setScreen(page(
            heading("Validate a customer", "Choose the customer"),
            body("Customers on your list with no place in Phoenix yet. Take the photo at his clinic; it goes for approval."),
            gap(14),
            search,
            gap(14),
            listColumn,
            gap(14),
            outlineButton("Back") { showHome() },
        ))
    }

    /**
     * Add a new customer with no network (camera 2.2, owner 20 Nov 2026: "the exact same concept of the visit"): the
     * main account's form, then the photo. Everything typed is sealed with the photo and goes for approval in Phoenix.
     */
    private fun showNewCustomer(state: NewCustomerState) {
        stopCamera()
        screen = Screen.NEW_CUSTOMER
        backAction = { showHome() }
        redraw = null
        val pack = FieldStore.pack(this) ?: return showLogin(null)
        val options = pack.newCustomerOptions ?: return showHome()

        val first = field("First name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, state.first)
        val middle = field("Middle name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, state.middle)
        val last = field("Last name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, state.last)
        val name = field("Customer name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, state.name)
        val phone = field("Phone (optional)", InputType.TYPE_CLASS_PHONE, state.phone)
        val pmNames = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(micro("First name", soft = true)); addView(first); addView(gap(12))
            addView(micro("Middle name", soft = true)); addView(middle); addView(gap(12))
            addView(micro("Last name", soft = true)); addView(last)
        }
        val amName = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(micro("Name", soft = true)); addView(name)
        }
        val nameSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val typeColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val specialityColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val specialitySearch = field("Search the specialities", InputType.TYPE_CLASS_TEXT, "")
        val specialitySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(gap(18)); addView(micro("Speciality")); addView(gap(8))
            addView(specialitySearch); addView(gap(10)); addView(specialityColumn)
        }
        val gradeColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun needsSpeciality(): Boolean = state.customerType?.let { it in options.typesWithSpeciality } == true

        fun renderSpecialities() {
            specialityColumn.removeAllViews()
            val query = specialitySearch.text.toString().trim()
            val shown = options.specialities.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.value == state.speciality }
            for (option in shown) {
                specialityColumn.addView(toggleRow(option.label, state.speciality == option.value) {
                    state.speciality = option.value
                    renderSpecialities()
                })
            }
            if (shown.isEmpty()) specialityColumn.addView(body("No speciality by that name."))
        }
        specialitySearch.doAfterTextChanged { renderSpecialities() }

        fun renderTypes() {
            typeColumn.removeAllViews()
            for (option in options.customerTypes) {
                typeColumn.addView(toggleRow(option.label, state.customerType == option.value) {
                    state.customerType = option.value
                    renderTypes()
                })
            }
            specialitySection.visibility = if (needsSpeciality()) View.VISIBLE else View.GONE
            if (needsSpeciality()) renderSpecialities()
        }

        fun renderList() {
            listColumn.removeAllViews()
            for ((value, label) in listOf("am" to "AM", "pm" to "PM")) {
                listColumn.addView(toggleRow(label, state.listType == value) {
                    state.listType = value
                    // A PM customer is a clinic unless the rep says otherwise, as in the main account.
                    if (value == "pm" && state.customerType == null) {
                        state.customerType = options.customerTypes.firstOrNull {
                            it.value.equals("Clinic", ignoreCase = true) || it.label.equals("Clinic", ignoreCase = true)
                        }?.value
                    }
                    renderList()
                    renderTypes()
                })
            }
            nameSection.removeAllViews()
            when (state.listType) {
                "pm" -> nameSection.addView(pmNames)
                "am" -> nameSection.addView(amName)
            }
        }

        fun renderGrades() {
            gradeColumn.removeAllViews()
            for (grade in listOf("A", "B", "C")) {
                gradeColumn.addView(toggleRow(grade, state.grade == grade) { state.grade = grade; renderGrades() })
            }
        }

        renderList()
        renderTypes()
        renderGrades()

        val problem = body("").apply { setTextColor(orangePressed); visibility = View.GONE }
        fun keepTyped() {
            state.first = first.text.toString()
            state.middle = middle.text.toString()
            state.last = last.text.toString()
            state.name = name.text.toString()
            state.phone = phone.text.toString()
        }

        val take = primaryButton("Take the photo") {
            keepTyped()
            val parts = listOf(state.first, state.middle, state.last).map { it.trim().replace(Regex("\\s+"), " ") }
            val amWords = state.name.trim().replace(Regex("\\s+"), " ")
            val digits = state.phone.replace(Regex("[\\s-]"), "")
            val message = when {
                state.listType == null -> "Choose AM or PM."
                state.listType == "pm" && parts.any { it.isEmpty() } -> "Write the first, middle and last name."
                state.listType == "am" && amWords.length < 3 -> "Write the customer's name, at least 3 letters."
                state.customerType == null -> "Choose the customer type."
                needsSpeciality() && state.speciality == null -> "Choose the speciality."
                state.grade == null -> "Choose the proposed grade."
                digits.isNotEmpty() && !Regex("^[0-9]{7,15}$").matches(digits) -> "Write the phone in digits only, or leave it empty."
                online == true -> "You have network. Add this customer in the main account."
                else -> null
            }
            if (message != null) {
                problem.text = message
                problem.visibility = View.VISIBLE
                return@primaryButton
            }
            val pm = state.listType == "pm"
            val fullName = if (pm) parts.joinToString(" ") else amWords
            val newCustomer = JSONObject()
                .put("listType", state.listType)
                .put("name", fullName)
                .put("nameParts", if (pm) JSONObject().put("first", parts[0]).put("middle", parts[1]).put("last", parts[2]) else JSONObject.NULL)
                .put("customerType", state.customerType)
                .put("speciality", if (needsSpeciality()) state.speciality else JSONObject.NULL)
                .put("proposedGrade", state.grade)
                .put("phone", digits.ifEmpty { null } ?: JSONObject.NULL)
            val visit = VisitDraft(
                intent = "new_customer",
                customerId = state.customerId,
                customerName = fullName,
                validated = false,
                body = null,
                newCustomer = newCustomer,
                back = { showNewCustomer(state) },
            )
            draft = visit
            showPhotoKind(visit)
        }

        setScreen(page(
            heading("Add new customer", "The new customer"),
            body("Fill in the customer as in the main account, then take the photo at his clinic. He goes for approval in Phoenix."),
            gap(16),
            micro("AM or PM"),
            gap(8),
            listColumn,
            gap(10),
            nameSection,
            gap(18),
            micro("Customer type"),
            gap(8),
            typeColumn,
            specialitySection,
            gap(18),
            micro("Proposed grade"),
            gap(8),
            gradeColumn,
            gap(18),
            micro("Phone", soft = true),
            phone,
            gap(14),
            problem,
            gap(12),
            take,
            gap(10),
            outlineButton("Back") { showHome() },
        ))
    }

    /** The "Update the app" button: only when the rep presses it, never in the background (camera 2.2). */
    private fun startUpdate() {
        showMessage("Updating the app", "Downloading the new version of Phoenix Camera. Keep the app open.", null, null)
        net.execute {
            // A fresh pack first where Phoenix answers: the download link is short-lived.
            if (PhoenixApi.token(this) != null) {
                runCatching { FieldStore.savePack(this, PhoenixApi.pack(this)) }
            }
            val latest = FieldStore.pack(this)?.latestApp
            val result = runCatching {
                if (latest == null || !Updater.available(this, latest)) null else Updater.download(this, latest)
            }
            runOnUiThread {
                result.onSuccess { file ->
                    if (file == null) {
                        showMessage("Up to date", "This is the newest Phoenix Camera.", "Back") { showHome() }
                    } else {
                        installUpdate(file)
                    }
                }.onFailure { error ->
                    showMessage("The update did not finish", (error as? Updater.Failed)?.message ?: Updater.INCOMPLETE, "Back") { showHome() }
                }
            }
        }
    }

    /** Hands the checked APK to Android's installer, after the rep allows Phoenix Camera to install apps. */
    private fun installUpdate(file: File) {
        if (!Updater.mayInstall(this)) {
            waitingInstall = file
            showMessage(
                "Allow the update",
                "Switch on \"Allow from this source\" for Phoenix Camera, then come back here and the update continues.",
                "Open the setting",
            ) { runCatching { startActivity(Updater.installSetting(this)) } }
            return
        }
        waitingInstall = null
        showHome()
        runCatching { Updater.install(this, file) }.onFailure { toast(Updater.INCOMPLETE) }
    }

    /**
     * A customer with no place yet (camera 2.1, owner 20 Nov 2026): the rep says what the photo shows. A polyclinic
     * often has no timetable, so the doctor's name in one of four places stands in for it. Phoenix reads it and the
     * System Administrator decides.
     */
    private fun showPhotoKind(visit: VisitDraft) {
        stopCamera()
        screen = Screen.KIND
        backAction = { visit.back() }
        redraw = null
        val kinds = listOf(
            "place" to "Clinic sign or door",
            "door_sign" to "Doctor's name on his room door",
            "banner" to "Building sign or banner with the doctor's name",
            "prescription" to "Polyclinic printed prescription with the doctor's and polyclinic's names",
            "business_card" to "Doctor's business card, inside the polyclinic",
        )
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            column.removeAllViews()
            for ((kind, words) in kinds) {
                column.addView(toggleRow(words, visit.photoKind == kind) { visit.photoKind = kind; render() })
            }
        }
        render()
        setScreen(page(
            heading("The photo", "What will the photo show?"),
            body(if (visit.intent == "new_customer") {
                "${visit.customerName} is a new customer. Choose what you will photograph."
            } else {
                "${visit.customerName} has no place in Phoenix yet. Choose what you will photograph."
            }),
            gap(16),
            column,
            gap(16),
            primaryButton("Open the camera") { showCamera() },
            gap(10),
            outlineButton("Back") { visit.back() },
        ))
    }

    /** A full-width row with a square check, flat: orange when chosen. */
    private fun toggleRow(label: String, chosen: Boolean, onToggle: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(if (chosen) cream else paper); setStroke(dp(1), if (chosen) orange else rule) }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { onToggle() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { setColor(if (chosen) orange else paper); setStroke(dp(2), if (chosen) orange else inkSoft) }
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) }
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            typeface = if (chosen) bold else regular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(navy)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun showCamera() {
        val visit = draft ?: return showHome()
        screen = Screen.CAMERA
        backAction = { if (!busy) { if (visit.validated) visit.back() else showPhotoKind(visit) } }
        redraw = null
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

        // The status band: the customer, one line of state, then what to do. No reading is shown.
        val dot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) } }
        val state = micro("", color = cream)
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(state)
        }
        val who = TextView(this).apply {
            text = visit.customerName
            typeface = bold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(10))
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
            addView(who)
            addView(hRule(navyRule))
            addView(status.apply { setPadding(0, dp(10), 0, 0) })
        }

        val take = Shutter().apply { setOnClickListener { capture() }; isEnabled = false }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(20), dp(10), dp(20), dp(18))
            addView(sideButton("Back", Gravity.START) {
                if (!busy) {
                    if (visit.validated) visit.back() else showPhotoKind(visit)
                }
            })
            addView(take)
            addView(sideButton("", Gravity.END) {})
        }

        statusView = status
        stateView = state
        stateDot = dot
        shutter = take
        setScreen(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            addView(header(showRep = true))
            navBar()?.let { addView(it) }
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

    private fun showSaved(visit: VisitDraft, locationPending: Boolean) {
        stopCamera()
        screen = Screen.OTHER
        backAction = { showHome() }
        redraw = null
        draft = null
        val what = when (visit.intent) {
            "validate" -> "the photo for his place is"
            "new_customer" -> "the new customer and his photo are"
            else -> "the visit and its photo are"
        }
        setScreen(page(
            heading("Saved", if (locationPending) "Location pending" else "Saved"),
            body(if (locationPending) {
                "${visit.customerName}: $what on the phone. The location is added by itself; keeping the app open for a moment helps. It sends itself to Phoenix when there is network, for approval where one is needed."
            } else {
                "${visit.customerName}: $what on the phone. It sends itself to Phoenix when there is network, for approval where one is needed; you do not need to send anything."
            }),
            gap(20),
            primaryButton("Done") { showHome() },
        ))
        checkNetwork()
    }

    private fun showMessage(title: String, message: String, action: String?, onAction: (() -> Unit)?) {
        stopCamera()
        screen = Screen.OTHER
        backAction = null
        redraw = null
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
        if (online == true) return "You have network. Record this visit in the main account."
        val current = watch ?: return "Starting the location…"
        if (!current.locationOn()) return "Switch Location on in the phone's settings."
        if (current.mockSeen()) return "A fake-location app is giving the position. Switch it off to take the photo."
        if (imageCapture == null) return "Starting the camera…"
        return null
    }

    /** The reading a photo taken now would be sealed with at once, or null while the location is still settling. */
    private fun lockedNow(current: PositionWatch): PositionWatch.Fix? =
        current.best(SystemClock.elapsedRealtimeNanos(), INSTANT_AGE_MS, 0L)?.takeIf { it.location.accuracy <= Pending.EXACT_M }

    private fun refreshStatus() {
        val current = watch ?: return
        if (statusView == null) return
        val problem = blocker()
        val locked = lockedNow(current)
        val shown = locked ?: current.best(SystemClock.elapsedRealtimeNanos(), INSTANT_AGE_MS, 0L) ?: current.latest()

        stateDot?.background = ColorDrawable(if (locked != null) orange else disabledText)
        stateView?.text = when {
            busy -> "SAVING THE VISIT"
            locked != null -> "LOCATION LOCKED"
            shown != null -> "LOCATION SETTLING"
            else -> "FINDING THE LOCATION"
        }
        if (!busy) {
            statusView?.text = problem ?: if (locked != null) {
                "Take the photo."
            } else {
                "Take the photo now. The location is added by itself."
            }
        }
        shutter?.isEnabled = problem == null && !busy
    }

    private fun capture() {
        val current = watch ?: return
        val use = imageCapture ?: return
        val visit = draft ?: return
        if (blocker() != null || busy) return
        if (PhoneChecks.mockAppActive(this)) {
            toast("A fake-location app is switched on in this phone. Switch it off to take the photo.")
            return
        }
        val shutterNanos = SystemClock.elapsedRealtimeNanos()
        val shot = Shot(
            shutterNanos = shutterNanos,
            phoneMs = System.currentTimeMillis(),
            satellitesUsed = current.satellites.used,
            satellitesSeen = current.satellites.seen,
            satelliteMs = SatelliteClock.utcAt(this, shutterNanos),
            networkMs = PhoenixClock.nowMs(this),
            offline = PhoenixApi.offlineClock(this, shutterNanos / 1_000_000),
            bootId = PhoenixApi.bootId(this),
        )
        busy = true
        refreshStatus()
        statusView?.text = "Saving the visit…"
        use.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val saved = runCatching {
                    val photo = try {
                        Sealer.upright(image.toBitmap(), image.imageInfo.rotationDegrees)
                    } finally {
                        image.close()
                    }
                    keep(photo, shot, visit)
                }
                runOnUiThread {
                    busy = false
                    saved.onSuccess { pending -> showSaved(visit, pending) }.onFailure {
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
     * Runs on the worker thread the moment the photo is in hand. The stamp (date and time, whom and who) is burned in
     * and the photo is stored with the shutter's facts as a pending visit, so nothing is lost; it is then sealed at
     * once if a good reading from the ten minutes before the shutter is held. Returns true while the visit still
     * waits for its place.
     */
    private fun keep(photo: Bitmap, shot: Shot, visit: VisitDraft): Boolean {
        val pack = FieldStore.pack(this) ?: throw IllegalStateException("sign in again")
        val takenMs: Long
        val timeSource: String
        val timeWords: String
        when {
            shot.satelliteMs != null -> { takenMs = shot.satelliteMs; timeSource = "satellites"; timeWords = "satellite time" }
            shot.networkMs != null -> { takenMs = shot.networkMs; timeSource = "phoenix"; timeWords = "Phoenix time" }
            else -> { takenMs = shot.phoneMs; timeSource = "phone"; timeWords = "phone's clock" }
        }
        val takenAt = Instant.ofEpochMilli(takenMs)

        // The stamp names when, whom and who; the place and its accuracy travel in the seal, read above the rep.
        val stamped = Sealer.stamp(photo, listOf(
            "${takenAt.atZone(cairo).format(stampTime)} Cairo · $timeWords",
            visit.customerName,
            "${repName()} (${repHrId()})",
            "Phoenix Camera · sealed",
        ))
        val jpeg = Sealer.encode(stamped)

        val meta = JSONObject()
            .put("visitId", visit.visitId)
            .put("shutterNanos", shot.shutterNanos)
            .put("bootId", shot.bootId)
            .put("seatId", pack.seatId)
            .put("customerId", visit.customerId)
            .put("customerName", visit.customerName)
            .put("photoKind", visit.photoKind)
            .put("intent", visit.intent)
            .put("draft", visit.body ?: JSONObject.NULL)
            .put("newCustomer", visit.newCustomer ?: JSONObject.NULL)
            .put("hrId", repHrId())
            .put("repName", repName())
            .put("satellitesUsed", shot.satellitesUsed)
            .put("satellitesSeen", shot.satellitesSeen)
            .put("takenAtUtc", takenAt.toString())
            .put("timeSource", timeSource)
            .put("phoneClockUtc", Instant.ofEpochMilli(shot.phoneMs).toString())
            .put("networkTimeUtc", shot.networkMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("offline", shot.offline ?: JSONObject.NULL)
            .put(
                "checks",
                JSONObject()
                    .put("mockAppActive", PhoneChecks.mockAppActive(this))
                    .put("rooted", PhoneChecks.rooted(this))
                    .put("developerOptions", PhoneChecks.developerOptions(this))
                    .put("adb", PhoneChecks.adb(this)),
            )
        Pending.save(this, visit.visitId, jpeg, meta)
        Pending.advance(this, watch)
        SendWork.kick(this)
        Notifier.refresh(this)
        return Pending.isPending(this, visit.visitId)
    }

    // ── Small helpers ────────────────────────────────────────────────────────────────────────────────────────

    private fun stopCamera() {
        imageCapture = null
        statusView = null
        stateView = null
        stateDot = null
        shutter = null
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
    }

    /**
     * A sealed photo stays until Phoenix has it, however old (2.1: Phoenix receives every visit and the System
     * Administrator decides). Only a photo no waiting visit names any more, left by an interrupted save, is removed
     * after three days.
     */
    private fun dropOrphanPhotos() {
        val named = FieldStore.queued(this).map { it.optString("file") }.toSet()
        val cutoff = System.currentTimeMillis() - 72L * 3_600_000L
        (File(filesDir, "sealed").listFiles()?.toList() ?: emptyList())
            .filter { it.name !in named && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    /**
     * The HR ID a seal carries: the one Phoenix's own pack names, never the typed login id. A test account (TEST-…)
     * mirrors a real rep and Phoenix checks the seal against that rep. No seal is made before a pack has loaded.
     */
    private fun repHrId(): String = FieldStore.pack(this)?.hrId?.takeIf { it.isNotBlank() } ?: PhoenixApi.hrId(this)
    private fun repName(): String = FieldStore.pack(this)?.repName ?: ""

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** A multi-line box for the comment, dressed like the single-line fields. */
    private fun area(hint: String) = field(hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "").apply {
        setSingleLine(false)
        minLines = 3
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        typeface = regular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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

    /**
     * Back and Refresh at the top of every screen (owner, 21 Nov 2026: "make a refresh button and back button at the top
     * in the app"). Back goes where the screen's own Back goes; Refresh reads the list and the plan again from Phoenix and
     * sends whatever is waiting. Neither shows on the sign-in screen, which has nowhere to go back to and nothing to read.
     */
    private fun navBar(): View? {
        val back = backAction
        val canRefresh = screen != Screen.LOGIN && screen != Screen.START && FieldStore.pack(this) != null
        if (back == null && !canRefresh) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(navyRaised)
            setPadding(dp(18), 0, dp(18), 0)
            addView(if (back != null) sideButton("Back", Gravity.START) { back() } else spacer())
            addView(if (canRefresh) sideButton("Refresh", Gravity.END) { refreshNow() } else spacer())
        }
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) }

    /** Refresh: the list, the plan and the newest version from Phoenix now, then everything waiting is sent. */
    private fun refreshNow() {
        if (refreshing) return
        refreshing = true
        toast("Refreshing from Phoenix")
        net.execute {
            val words = when {
                !PhoenixApi.reachable() -> "No network here. Everything you take stays on the phone and sends itself when there is network."
                PhoenixApi.token(this) == null -> "Sign in again to refresh."
                else -> runCatching {
                    val pack = PhoenixApi.pack(this)
                    FieldStore.savePack(this, pack)
                    PhoenixApi.refreshAnchor(this, pack.getString("seatId"))
                    lastRefreshMs = System.currentTimeMillis()
                }.fold(
                    { "Your list and plan are up to date." },
                    { error -> (error as? PhoenixApi.Refused)?.message ?: "Phoenix did not answer. Try again in a moment." },
                )
            }
            runOnUiThread {
                refreshing = false
                toast(words)
                redraw?.invoke()
                checkNetwork()
            }
        }
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
            navBar()?.let { addView(it) }
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
