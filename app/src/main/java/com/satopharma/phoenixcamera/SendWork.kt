package com.satopharma.phoenixcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Sending in the background (camera 2.1, owner 20 Nov 2026): the visits waiting on the phone go to Phoenix the moment
 * there is network, with the app closed or after the phone restarted, through Android's own scheduler.
 *
 *   - "send-now": once, as soon as the phone is connected; queued whenever a visit is saved and when the app opens.
 *   - "send-every-15": every 15 minutes, network or not. With network it sends what is waiting; without, it seals a
 *     pending visit whose place window has passed, keeps the "waiting" notification right, and at 22:00 Cairo
 *     reminds the rep once a day that visits are still on the phone. (A network condition on this one would stop the
 *     reminder from ever reaching a rep who has no network, which is exactly when it matters.)
 */
class SendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        Pending.advance(context, null)
        var outcome: FieldStore.Outcome? = null
        if (SendWork.connected(context) && FieldStore.waiting(context)) {
            if (PhoenixApi.signedInOnce(context) && PhoenixApi.token(context) == null) {
                outcome = FieldStore.Outcome.SIGN_IN
            } else {
                FieldStore.pack(context)?.let { pack -> runCatching { PhoenixApi.refreshAnchorIfOld(context, pack.seatId) } }
                outcome = FieldStore.drain(context)
            }
        }
        if (outcome == FieldStore.Outcome.SIGN_IN) Notifier.signIn(context)
        Notifier.refresh(context)
        Notifier.remindIfLate(context)
        val oneTime = tags.contains(SendWork.NOW)
        return if (oneTime && outcome == FieldStore.Outcome.LATER) Result.retry() else Result.success()
    }
}

object SendWork {
    const val NOW = "send-now"
    private const val EVERY = "send-every-15"

    /** Sends as soon as the phone is connected. Already queued: that one stays, and sends everything waiting. */
    fun kick(context: Context) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(NOW)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request) }
    }

    /** The 15-minute round; Android keeps it across restarts. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SendWorker>(15, TimeUnit.MINUTES).addTag(EVERY).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(EVERY, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        kick(context)
    }

    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** The rep's notifications. No count and no limit is ever shown: "waiting", never "late". */
object Notifier {
    private const val SENDING = "sending"
    private const val REMINDERS = "reminders"
    private const val WAITING_ID = 1
    private const val SIGN_IN_ID = 2
    private const val REMINDER_ID = 3
    private const val REMINDER_HOUR = 22
    private val cairo = ZoneId.of("Africa/Cairo")

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SENDING, "Visits waiting to be sent", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    private fun show(context: Context, id: Int, channel: String, title: String, text: String, quiet: Boolean) {
        if (!allowed(context)) return
        channels(context)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_phoenix)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(quiet)
            .setAutoCancel(!quiet)
            .setOngoing(false)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** True while a visit is on the phone and has not reached Phoenix (sealed or still waiting for its place). */
    fun anythingWaiting(context: Context): Boolean = FieldStore.waiting(context) || Pending.any(context)

    /** The one "waiting" notification, shown while anything is waiting and cleared when the phone is empty. */
    fun refresh(context: Context) {
        if (anythingWaiting(context)) {
            show(context, WAITING_ID, SENDING, "Visits waiting to be sent", "They send themselves to Phoenix when there is network.", quiet = true)
        } else {
            cancel(context, WAITING_ID)
            cancel(context, REMINDER_ID)
        }
        if (PhoenixApi.token(context) != null) cancel(context, SIGN_IN_ID)
    }

    fun signIn(context: Context) {
        show(context, SIGN_IN_ID, REMINDERS, "Sign in to send your visits", "Open the Phoenix Camera and sign in again where there is network.", quiet = false)
    }

    /** At 22:00 Cairo, once a day, when visits are still on the phone. */
    fun remindIfLate(context: Context) {
        val now = ZonedDateTime.now(cairo)
        if (now.hour < REMINDER_HOUR || !anythingWaiting(context)) return
        val prefs = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)
        val today = now.toLocalDate().toString()
        if (prefs.getString("day", null) == today) return
        show(context, REMINDER_ID, REMINDERS, "Visits waiting to be sent", "Connect to the network to send today's visits.", quiet = false)
        prefs.edit().putString("day", today).apply()
    }
}
