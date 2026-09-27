package com.sessionsense.session_sense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SessionSenseTheme { SessionSenseRoot(viewModel, intent.action) } }
    }

    override fun onStart() {
        super.onStart()
        // A background start may have been refused by the OS; the app is in the foreground now, so this one is allowed.
        if ((application as SessionSenseApp).credentials.hasConnected()) UsagePollingService.start(this)
    }
}
