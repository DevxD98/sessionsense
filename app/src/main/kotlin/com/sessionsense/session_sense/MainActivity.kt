package com.sessionsense.session_sense

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startWidgetPoller" -> {
                        val sk  = call.argument<String>("sessionKey") ?: return@setMethodCallHandler result.error("MISSING", "sessionKey required", null)
                        val org = call.argument<String>("orgId")      ?: return@setMethodCallHandler result.error("MISSING", "orgId required", null)
                        WidgetPollerService.start(applicationContext, sk, org)
                        result.success(null)
                    }
                    "stopWidgetPoller" -> {
                        WidgetPollerService.stop(applicationContext)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    companion object {
        const val CHANNEL = "com.sessionsense/widget_poller"
    }
}
