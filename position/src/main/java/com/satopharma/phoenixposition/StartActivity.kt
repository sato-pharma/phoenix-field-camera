package com.satopharma.phoenixposition

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * The one-shot start, and the only thing in this app that was ever visible.
 *
 * An app that nothing has started sits in Android's "stopped" state, where no broadcast reaches it — not even
 * BOOT_COMPLETED — so something has to start it once. Headwind, which is the device owner on these tablets, starts an
 * app it has just installed through its launcher intent, and that is the whole reason this activity exists.
 *
 * It draws nothing: no layout, a translucent theme, and it finishes inside `onCreate`, so the tablet's screen does not
 * even flicker. Then it DISABLES ITSELF — from that moment there is no icon in any launcher and no rep has anything to
 * open, which is the rule these tablets live by. The rounds and the restart receiver do not need it again.
 */
class StartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReportWork.schedule(applicationContext)
        hideForGood()
        finish()
    }

    /**
     * Takes the icon away for good. `DONT_KILL_APP` keeps this round and the scheduled work alive while it happens.
     *
     * By hand afterwards, with the tablet on a cable, the rounds are started again without bringing the icon back:
     *     adb shell am broadcast -n com.satopharma.phoenixposition/.StartReceiver \
     *         -a com.satopharma.phoenixposition.START --include-stopped-packages
     */
    private fun hideForGood() {
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, StartActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
