package com.satopharma.phoenixposition

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.TimeUnit

/**
 * The round: take a reading, keep it, and give Phoenix everything the tablet is still holding.
 *
 * It runs through Android's own scheduler every quarter of an hour, with nothing open, and comes back by itself after
 * the tablet restarts. There is no notification, no icon and no screen — the only thing this app does that anybody can
 * observe is one HTTPS call to Phoenix.
 *
 * The round never stops on a refusal it cannot help: with no network the reading is kept and sent later; with no
 * location permission it still reports, saying so, because a tablet nobody granted the permission must never read as a
 * tablet that did not move. Only one answer ends it — 403, the install revoked.
 */
class ReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (Install.revoked(context)) {
            ReportWork.stop(context)
            return Result.success()
        }

        Where.fix(context)?.let { Trail.add(context, it) }

        if (!ReportWork.connected(context)) return waitForTheNextRound()

        val token = Install.token(context) ?: enrol(context) ?: return waitForTheNextRound()

        // A round sends at most four batches, so a month of backlog drains over a few rounds instead of holding the
        // scheduler's slot open. Nothing is lost: what is left is the next round's first batch.
        for (call in 1..CALLS_PER_ROUND) {
            val batch = Trail.oldest(context, BATCH)
            val answer = PositionApi.report(context, batch, token) ?: return waitForTheNextRound()
            when {
                answer.ok -> {
                    Trail.forgetOldest(context, batch.size)
                    if (batch.size < BATCH) return Result.success()
                }
                // No longer in service: a lost or replaced tablet goes quiet, and stays quiet.
                answer.status == 403 -> {
                    Install.markRevoked(context)
                    ReportWork.stop(context)
                    return Result.success()
                }
                // The token is not recognised. The one honest answer is a new install, waiting to be claimed.
                answer.status == 401 -> {
                    Install.remint(context)
                    return Result.success()
                }
                else -> return waitForTheNextRound()
            }
        }
        return Result.success()
    }

    /**
     * Says hello and keeps the token. Phoenix mints it exactly once, so an install it knows while this app holds no
     * token can never speak again: it mints a new install id instead and says hello in the next round.
     */
    private fun enrol(context: Context): String? {
        val answer = PositionApi.hello(context) ?: return null
        val token = answer.body.optString("token", "")
        if (answer.ok && token.isNotEmpty()) {
            Install.rememberToken(context, token)
            return token
        }
        if (answer.body.optString("status") == "revoked") {
            Install.markRevoked(context)
            ReportWork.stop(context)
            return null
        }
        if (answer.ok || answer.status == 401) Install.remint(context)
        return null
    }

    /**
     * The reading is already kept, so the round has done its work either way. A one-off round asks to be tried again
     * (that is what it is for); the quarter-hourly one simply waits for the next quarter of an hour.
     */
    private fun waitForTheNextRound(): Result =
        if (tags.contains(ReportWork.NOW)) Result.retry() else Result.success()

    companion object {
        /** Phoenix takes at most 240 fixes in one call. */
        private const val BATCH = 240
        private const val CALLS_PER_ROUND = 4
    }
}

object ReportWork {
    const val NOW = "position-now"
    private const val ROUND = "position-round"

    /**
     * Starts the rounds, and keeps the one already running rather than restarting its clock. Called from the one-shot
     * start, after a restart, and after the app is updated — all three may happen in any order, and all three are safe
     * to repeat.
     */
    fun schedule(context: Context) {
        if (Install.revoked(context)) return
        val round = PeriodicWorkRequestBuilder<ReportWorker>(15, TimeUnit.MINUTES)
            .addTag(ROUND)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(ROUND, ExistingPeriodicWorkPolicy.KEEP, round)
        }
        kick(context)
    }

    /** One round as soon as the tablet is connected, so a fresh install does not wait a quarter of an hour. */
    fun kick(context: Context) {
        val now = OneTimeWorkRequestBuilder<ReportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag(NOW)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, now) }
    }

    /** The install was revoked: no more rounds. Re-installing the app is what starts a new install. */
    fun stop(context: Context) {
        runCatching {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(ROUND)
            manager.cancelUniqueWork(NOW)
        }
    }

    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
