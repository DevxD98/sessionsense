package com.sessionsense.session_sense

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    // A notification tap on a running app arrives through onNewIntent, not onCreate.
    private val launchAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) launchAction.value = intent.action
        setContent { SessionSenseTheme { SessionSenseRoot(viewModel, launchAction.value) { launchAction.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_UPDATE) launchAction.value = intent.action
    }

    override fun onStart() {
        super.onStart()
        // A background start may have been refused by the OS; the app is in the foreground now, so this one is allowed.
        if ((application as SessionSenseApp).credentials.hasConnected()) UsagePollingService.start(this)
    }
}
