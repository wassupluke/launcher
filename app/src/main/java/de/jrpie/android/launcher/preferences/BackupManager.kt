package de.jrpie.android.launcher.preferences

import android.content.Context
import android.net.Uri
import de.jrpie.android.launcher.BuildConfig
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.Gesture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupEnvelope(
    val backupVersion: Int = 1,
    val appVersion: Int,
    val timestamp: Long,
    val preferences: Map<String, Map<String, String>>, // group name → exportToMap()
    val gestures: Map<String, String>,                  // Gesture.id → JSON action string
    val skipped: SkippedItems
)

@Serializable
data class SkippedItems(
    val widgets: Int = 0,
    val pinnedShortcuts: Int = 0
)

data class ImportResult(
    val success: Boolean,
    val skipped: SkippedItems,
    val error: String? = null
)

object BackupManager {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    internal fun buildEnvelope(): BackupEnvelope {
        val prefGroups = mapOf(
            "apps"             to LauncherPreferences.apps().exportToMap(),
            "list"             to LauncherPreferences.list().exportToMap(),
            "theme"            to LauncherPreferences.theme().exportToMap(),
            "clock"            to LauncherPreferences.clock().exportToMap(),
            "display"          to LauncherPreferences.display().exportToMap(),
            "functionality"    to LauncherPreferences.functionality().exportToMap(),
            "enabled_gestures" to LauncherPreferences.enabled_gestures().exportToMap(),
            "actions"          to LauncherPreferences.actions().exportToMap(),
            "widgets"          to LauncherPreferences.widgets().exportToMap()
        )

        // Gesture bindings are stored as flat keys in the top-level SharedPreferences file.
        // LauncherPreferences.gestures() has no exportToMap entries — use getSharedPreferences() directly.
        val prefs = LauncherPreferences.getSharedPreferences()
        val gestureMap = buildMap {
            Gesture.entries.forEach { g ->
                val v = prefs.getString(g.id, null) ?: ""
                if (v.isNotEmpty()) put(g.id, v)
            }
        }

        return BackupEnvelope(
            appVersion = BuildConfig.VERSION_CODE,
            timestamp = System.currentTimeMillis(),
            preferences = prefGroups,
            gestures = gestureMap,
            skipped = SkippedItems(
                widgets = LauncherPreferences.widgets().widgets()?.size ?: 0,
                pinnedShortcuts = LauncherPreferences.apps().pinnedShortcuts()?.size ?: 0
            )
        )
    }

    fun export(context: Context, uri: Uri) {
        val envelope = buildEnvelope()
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray())
        }
    }

    fun import(context: Context, uri: Uri): ImportResult {
        return try {
            val raw = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: return ImportResult(false, SkippedItems(),
                    error = context.getString(R.string.backup_import_error_file))

            val envelope = json.decodeFromString(
                BackupEnvelope.serializer(), raw.decodeToString()
            )

            if (envelope.backupVersion > 1) {
                return ImportResult(false, SkippedItems(),
                    error = context.getString(R.string.backup_import_error_unsupported_version,
                        envelope.backupVersion))
            }

            envelope.preferences["apps"]?.let             { LauncherPreferences.apps().importFromMap(it) }
            envelope.preferences["list"]?.let             { LauncherPreferences.list().importFromMap(it) }
            envelope.preferences["theme"]?.let            { LauncherPreferences.theme().importFromMap(it) }
            envelope.preferences["clock"]?.let            { LauncherPreferences.clock().importFromMap(it) }
            envelope.preferences["display"]?.let          { LauncherPreferences.display().importFromMap(it) }
            envelope.preferences["functionality"]?.let    { LauncherPreferences.functionality().importFromMap(it) }
            envelope.preferences["enabled_gestures"]?.let { LauncherPreferences.enabled_gestures().importFromMap(it) }
            envelope.preferences["actions"]?.let          { LauncherPreferences.actions().importFromMap(it) }
            envelope.preferences["widgets"]?.let          { LauncherPreferences.widgets().importFromMap(it) }

            val editor = LauncherPreferences.getSharedPreferences().edit()
            envelope.gestures.forEach { (id, value) ->
                if (value.isNotEmpty()) editor.putString(id, value)
            }
            editor.apply()

            ImportResult(success = true, skipped = envelope.skipped)
        } catch (e: Exception) {
            ImportResult(false, SkippedItems(), error = e.message)
        }
    }
}
