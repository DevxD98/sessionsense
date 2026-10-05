package com.sessionsense.session_sense

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Nothing phones with Glyph lights. A third-party app can't change the phone's Glyph settings or any other app's
 * Glyph behaviour; it can only change what its own notifications do, and open Nothing's per-app Glyph notifications
 * list, the one place the user can switch SessionSense's entry off (Glyph Progress settings need a signature permission).
 */
object GlyphSupport {
    const val SETTINGS_ACTION = "android.settings.GLYPHS_ALL_APPS_NOTIFICATION_SETTINGS"

    /** A Nothing phone whose Glyph notification list can be opened. CMF and other phones have no such screen. */
    fun available(manufacturer: String, resolves: (String) -> Boolean) = manufacturer.equals("Nothing", ignoreCase = true) && resolves(SETTINGS_ACTION)

    // resolveActivity only sees the action because the manifest declares it under <queries> (package visibility, Android 11+).
    fun available(context: Context) = available(Build.MANUFACTURER) { context.packageManager.resolveActivity(Intent(it), 0) != null }

    fun openSettings(context: Context) = runCatching { context.startActivity(Intent(SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
