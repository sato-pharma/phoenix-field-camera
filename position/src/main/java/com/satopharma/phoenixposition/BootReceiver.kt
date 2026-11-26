package com.satopharma.phoenixposition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The rounds come back by themselves: after the tablet restarts, and after this app is updated.
 *
 * Android's scheduler already keeps a periodic round across a restart; this re-states it, which costs nothing and
 * covers the one case the scheduler does not — the round having been cancelled while the install was revoked and the
 * app then re-installed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReportWork.schedule(context.applicationContext)
            else -> Unit
        }
    }
}

/**
 * Starting the rounds from a cable, once [StartActivity] has disabled itself — the one way in that brings no icon
 * back. It schedules the rounds and answers nothing; there is no other action it can take, and nothing it can read.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReportWork.schedule(context.applicationContext)
    }
}
