package de.jrpie.android.launcher.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GesturePromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recordId = intent.getStringExtra(GestureTelemetry.EXTRA_RECORD_ID) ?: return
        val correct = intent.getBooleanExtra(GestureTelemetry.EXTRA_CORRECT, true)
        GestureTelemetry.recordAnswer(context, recordId, correct, null)
    }
}
