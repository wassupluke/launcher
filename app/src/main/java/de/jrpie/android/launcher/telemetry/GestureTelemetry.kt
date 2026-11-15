package de.jrpie.android.launcher.telemetry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.preferences.LauncherPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random

object GestureTelemetry {
    data class Sample(val x: Float, val y: Float, val time: Long)

    data class ScreenContext(
        val width: Int,
        val height: Int,
        val density: Float,
        val insetTop: Int,
        val insetBottom: Int,
        val insetLeft: Int,
        val insetRight: Int,
        val touchSlop: Int,
        val edgeWidth: Float
    )

    interface PointerSamples {
        val number: Int
        val samples: List<Sample>
    }

    const val EXTRA_RECORD_ID = "telemetry.record_id"
    const val EXTRA_GESTURE = "telemetry.gesture"
    const val EXTRA_CORRECT = "telemetry.correct"
    const val NOTIFICATION_ID = 7531

    private const val TAG = "GestureTelemetry"
    private const val PREFS = "gesture_telemetry"
    private const val KEY_RULE = "active_rule"
    private const val KEY_NEXT_SWITCH = "next_switch_at"
    private const val KEY_FILE_URI = "file_uri"
    private const val FILE_NAME = "ulauncher-gestures.txt"
    private const val CHANNEL = "launcher:gesture_check"
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    private const val MAX_INTERVAL_MS = 60 * 60 * 1000L

    private val writer = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = System.currentTimeMillis()

    private fun timestamp(millis: Long) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))

    fun activeRule(context: Context): GestureRule {
        val prefs = prefs(context)
        val current = GestureRule.byId(prefs.getString(KEY_RULE, null))
        val nextSwitch = prefs.getLong(KEY_NEXT_SWITCH, 0L)
        if (current != null && now() < nextSwitch) {
            return current
        }
        val rule = GestureRule.entries.random()
        val next = now() + Random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS + 1)
        prefs.edit().putString(KEY_RULE, rule.id).putLong(KEY_NEXT_SWITCH, next).apply()
        append(context, JSONObject().apply {
            put("type", "rule_switch")
            put("time", timestamp(now()))
            put("from", current?.id ?: JSONObject.NULL)
            put("to", rule.id)
            put("until", timestamp(next))
        })
        return rule
    }

    fun onGesture(
        context: Context,
        kind: String,
        triggered: Gesture?,
        results: Map<GestureRule, Gesture?>,
        activeRule: GestureRule,
        paths: Collection<PointerSamples>,
        downTime: Long,
        upTime: Long,
        screen: ScreenContext
    ) {
        val id = UUID.randomUUID().toString()
        val action = triggered?.let { Action.forGesture(it) }
        val record = JSONObject().apply {
            put("type", kind)
            put("id", id)
            put("time", timestamp(now()))
            put("active_rule", activeRule.id)
            put("results", JSONObject().apply {
                results.forEach { (rule, gesture) -> put(rule.id, gesture?.name ?: JSONObject.NULL) }
            })
            put("triggered", triggered?.name ?: JSONObject.NULL)
            put("action", action?.label(context) ?: JSONObject.NULL)
            put("prompted", triggered != null)
            put("duration_ms", upTime - downTime)
            put("screen", JSONObject().apply {
                put("width", screen.width)
                put("height", screen.height)
                put("density", screen.density.toDouble())
                put("inset_top", screen.insetTop)
                put("inset_bottom", screen.insetBottom)
                put("inset_left", screen.insetLeft)
                put("inset_right", screen.insetRight)
                put("touch_slop", screen.touchSlop)
            })
            put("settings", JSONObject().apply {
                put("diagonal_swipe", LauncherPreferences.enabled_gestures().diagonalSwipe())
                put("double_swipe", LauncherPreferences.enabled_gestures().doubleSwipe())
                put("edge_swipe", LauncherPreferences.enabled_gestures().edgeSwipe())
                put("edge_width", screen.edgeWidth.toDouble())
            })
            put("pointers", JSONArray().apply {
                paths.sortedBy { it.number }.forEach { path ->
                    put(JSONObject().apply {
                        put("number", path.number)
                        put("points", JSONArray().apply {
                            path.samples.forEach { s ->
                                put(JSONArray().apply {
                                    put(Math.round(s.x * 10) / 10.0)
                                    put(Math.round(s.y * 10) / 10.0)
                                    put(s.time - downTime)
                                })
                            }
                        })
                    })
                }
            })
        }
        append(context, record)

        triggered?.invoke(context)

        if (triggered != null) {
            prompt(context, id, triggered)
        }
    }

    fun recordAnswer(context: Context, recordId: String, correct: Boolean, intended: String?) {
        append(context, JSONObject().apply {
            put("type", "answer")
            put("ref", recordId)
            put("time", timestamp(now()))
            put("correct", correct)
            put("intended", intended ?: JSONObject.NULL)
        })
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun promptText(context: Context, gesture: Gesture) =
        "Was that a ${gesture.getLabel(context)} (${gesture.getDescription(context)})?"

    private fun prompt(context: Context, recordId: String, gesture: Gesture) {
        val appContext = context.applicationContext
        createChannel(appContext)

        val yes = PendingIntent.getBroadcast(
            appContext, 1,
            Intent(appContext, GesturePromptReceiver::class.java).apply {
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_CORRECT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val no = PendingIntent.getActivity(
            appContext, 2,
            Intent(appContext, GesturePickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_GESTURE, gesture.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.baseline_bug_report_24)
            .setContentTitle(promptText(appContext, gesture))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .addAction(0, "Yes", yes)
            .addAction(0, "No", no)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(CHANNEL, "Gesture check", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun append(context: Context, json: JSONObject) {
        val appContext = context.applicationContext
        val line = json.toString() + "\n"
        writer.execute {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@execute
            }
            try {
                val uri = existingFile(appContext) ?: createFile(appContext) ?: return@execute
                appContext.contentResolver.openOutputStream(uri, "wa")?.use {
                    it.write(line.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not write gesture log", e)
                prefs(appContext).edit().remove(KEY_FILE_URI).apply()
            }
        }
    }

    private fun existingFile(context: Context): Uri? {
        val uri = prefs(context).getString(KEY_FILE_URI, null)?.let { Uri.parse(it) } ?: return null
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { if (it.moveToFirst()) uri else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun createFile(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        prefs(context).edit().putString(KEY_FILE_URI, uri?.toString()).apply()
        return uri
    }
}
