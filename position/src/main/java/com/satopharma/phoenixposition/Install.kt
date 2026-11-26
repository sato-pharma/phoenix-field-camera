package com.satopharma.phoenixposition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * This install: the id the tablet minted for itself, the token Phoenix handed back once, and what the tablet honestly
 * is.
 *
 * 🔴 NOTHING IS PROVISIONED INTO THIS APP. A tablet's Headwind configuration is world-readable — its sync endpoint is
 * public by design — so a credential put there would be public too. So the app mints an install id of its own on first
 * run, says hello, and keeps the token Phoenix answers with in this app's private storage, where no other app and no
 * configuration can read it. Phoenix keeps only the token's SHA-256.
 *
 * 🔴 AN INSTALL IS NOT A TABLET. Phoenix does not learn which tablet this is from anything the app says: someone with
 * the tablet in front of them binds the install to its number from the code. Until then the fixes are kept and belong
 * to nobody, and the bind stamps the ones already sent — so the days before the bind are not lost.
 */
object Install {
    private const val PREFS = "install"
    private const val ID = "installId"
    private const val TOKEN = "token"
    private const val REVOKED = "revoked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The install id, minted on first use and kept for the life of the installation. */
    fun id(context: Context): String {
        val held = prefs(context).getString(ID, null)
        if (held != null) return held
        val minted = UUID.randomUUID().toString()
        prefs(context).edit().putString(ID, minted).apply()
        return minted
    }

    fun token(context: Context): String? = prefs(context).getString(TOKEN, null)

    fun rememberToken(context: Context, token: String) {
        prefs(context).edit().putString(TOKEN, token).apply()
    }

    /**
     * A fresh start: a new install id and no token.
     *
     * Only for the two cases where this install can never speak again — Phoenix knows the id but the app holds no
     * token (the token was lost and is minted exactly once), or the token it holds is refused. Phoenix then sees a new,
     * unbound install waiting to be claimed, which is the honest state: nothing is silently taken over.
     */
    fun remint(context: Context) {
        prefs(context).edit().putString(ID, UUID.randomUUID().toString()).remove(TOKEN).remove(REVOKED).apply()
    }

    /** Phoenix said this install is no longer in service. The rounds stop, and the tablet goes quiet. */
    fun revoked(context: Context): Boolean = prefs(context).getBoolean(REVOKED, false)

    fun markRevoked(context: Context) {
        prefs(context).edit().putBoolean(REVOKED, true).apply()
    }

    /** Android's own id for this tablet. It tells Phoenix two installs are the same tablet; it is not a credential. */
    fun androidId(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()
            .orEmpty()
            .take(32)

    /** This app's own version, read from the package itself so it can never drift from the build. */
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .take(24)

    /**
     * What Android really granted, in Phoenix's words: `background` (the rounds can read the position with nothing
     * open), `fine`, `coarse`, or `none`.
     *
     * 🔴 THE WORD IS ALWAYS THE TRUTH. An agent that cannot read the position still reports, saying so, because a
     * tablet that was never granted the permission must never read to anyone as a tablet that did not move.
     */
    fun permission(context: Context): String {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return "none"
        val background = Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return when {
            background -> "background"
            fine -> "fine"
            else -> "coarse"
        }
    }

    /** True when the position can be read at all. */
    fun canRead(context: Context): Boolean = permission(context) != "none"

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
