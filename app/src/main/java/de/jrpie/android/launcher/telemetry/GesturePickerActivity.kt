package de.jrpie.android.launcher.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import de.jrpie.android.launcher.actions.Gesture

class GesturePickerActivity : Activity() {
    private val excluded = setOf(
        Gesture.VOLUME_UP,
        Gesture.VOLUME_DOWN,
        Gesture.TIME,
        Gesture.DATE,
        Gesture.BACK,
        Gesture.LONG_CLICK
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getStringExtra(GestureTelemetry.EXTRA_RECORD_ID)
        val detected = intent.getStringExtra(GestureTelemetry.EXTRA_GESTURE)
            ?.let { name -> Gesture.entries.firstOrNull { it.name == name } }
        if (recordId == null) {
            finish()
            return
        }

        val options = Gesture.entries.filter { it !in excluded && it != detected }
        val labels = listOf("Nothing / accidental touch") +
                options.map { "${it.getLabel(this)} (${it.getDescription(this)})" }

        var answered = false
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("What did you mean?")
            .setItems(labels.toTypedArray()) { _, which ->
                val intended = if (which == 0) "NONE" else options[which - 1].name
                answered = true
                GestureTelemetry.recordAnswer(this, recordId, false, intended)
            }
            .setOnDismissListener {
                if (!answered) {
                    GestureTelemetry.recordAnswer(this, recordId, false, null)
                }
                finish()
            }
            .show()
    }
}
