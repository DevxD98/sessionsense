package com.sessionsense.session_sense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only. Points the updater at another update.json on GitHub (e.g. a test pre-release), forgets the last
 * check, skip and notification, and runs the automatic check (so the notification fires as it would in the wild):
 *   adb shell am broadcast -n com.sessionsense.session_sense/.UpdateDebugReceiver \
 *     -a com.sessionsense.DEBUG_UPDATE_MANIFEST --es url https://github.com/DevxD98/sessionsense/releases/download/<tag>/update.json
 * Omit --es url to return to the latest release. The URL must still pass UpdateHosts; hash and signature checks still apply.
 */
class UpdateDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = SelfUpdater.get(context.applicationContext as SessionSenseApp)
        val url = intent.getStringExtra("url")?.takeIf(UpdateHosts::isAllowed)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { updater.setManifestUrlOverride(url); updater.backgroundCheck() } finally { pending.finish() }
        }
    }
}
