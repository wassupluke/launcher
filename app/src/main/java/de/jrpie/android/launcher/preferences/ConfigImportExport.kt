package de.jrpie.android.launcher.preferences

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.apps.AbstractAppInfo.Companion.INVALID_USER
import de.jrpie.android.launcher.apps.getPrivateSpaceUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

private const val TAG = "Launcher - ConfigIO"

private const val FORMAT_VERSION = 1

private const val KEY_FORMAT_VERSION = "formatVersion"
private const val KEY_PREFERENCE_VERSION = "preferenceVersion"
private const val KEY_EXPORTED_AT = "exportedAt"
private const val KEY_PREFERENCES = "preferences"
private const val KEY_USER_PROFILES = "userProfiles"

private val exportJson = Json { prettyPrint = true }

// --- User profile helpers ---

private const val PROFILE_TYPE_MAIN = "main"
private const val PROFILE_TYPE_WORK = "work"
private const val PROFILE_TYPE_PRIVATE = "private"

/**
 * Build a mapping from userId (UserHandle.hashCode()) to a portable profile type string.
 */
private fun buildUserProfileMap(context: Context): Map<Int, String> {
    val userManager = context.getSystemService(Service.USER_SERVICE) as UserManager
    val profiles = userManager.userProfiles
    val map = mutableMapOf<Int, String>()

    if (profiles.isNotEmpty()) {
        map[profiles[0].hashCode()] = PROFILE_TYPE_MAIN
    }

    val privateSpaceUser = getPrivateSpaceUser(context)
    if (privateSpaceUser != null) {
        map[privateSpaceUser.hashCode()] = PROFILE_TYPE_PRIVATE
    }

    for (profile in profiles.drop(1)) {
        if (!map.containsKey(profile.hashCode())) {
            map[profile.hashCode()] = PROFILE_TYPE_WORK
        }
    }

    return map
}

/**
 * Build a reverse mapping from profile type string to local userId.
 */
private fun buildProfileTypeToUserIdMap(context: Context): Map<String, Int> {
    return buildUserProfileMap(context).entries.associate { (k, v) -> v to k }
}

/**
 * Collect all exportable preference keys and their SharedPreferences types.
 *
 * This combines:
 * - The auto-generated export registry from [LauncherPreferences.getExportEntries],
 *   which is derived from `@Preference(export = true)` annotations in
 *   `LauncherPreferences$Config.java`.
 * - Gesture action bindings from [Gesture.entries], which are stored as JSON strings
 *   keyed by [Gesture.id].
 */
private fun getExportableEntries(): Map<String, String> {
    val entries = LinkedHashMap(LauncherPreferences.getExportEntries())
    for (gesture in Gesture.entries) {
        entries[gesture.id] = "String"
    }
    return entries
}

// --- Export ---

fun exportConfig(context: Context, outputStream: OutputStream) {
    val prefs = LauncherPreferences.getSharedPreferences()
    val exportable = getExportableEntries()
    val userProfileMap = buildUserProfileMap(context)

    val prefsMap = mutableMapOf<String, JsonElement>()
    for ((key, type) in exportable) {
        if (!prefs.contains(key)) continue
        prefsMap[key] = readPref(prefs, key, type)
    }

    val envelope = JsonObject(
        mapOf(
            KEY_FORMAT_VERSION to JsonPrimitive(FORMAT_VERSION),
            KEY_PREFERENCE_VERSION to JsonPrimitive(
                LauncherPreferences.internal().versionCode()
            ),
            KEY_EXPORTED_AT to JsonPrimitive(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Instant.now().toString()
                else
                    System.currentTimeMillis().toString()
            ),
            KEY_USER_PROFILES to JsonObject(
                userProfileMap.map { (id, type) ->
                    id.toString() to JsonPrimitive(type)
                }.toMap()
            ),
            KEY_PREFERENCES to JsonObject(prefsMap)
        )
    )

    outputStream.bufferedWriter().use { writer ->
        writer.write(exportJson.encodeToString(JsonObject.serializer(), envelope))
    }
}

// --- Import ---

sealed class ImportResult {
    data object Success : ImportResult()
    data class Error(val message: String) : ImportResult()
}

fun importConfig(context: Context, inputStream: InputStream): ImportResult {
    val text = try {
        inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read config file", e)
        return ImportResult.Error("Failed to read file: ${e.message}")
    }

    val envelope: JsonObject = try {
        Json.decodeFromString(JsonObject.serializer(), text)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse config JSON", e)
        return ImportResult.Error("Invalid JSON: ${e.message}")
    }

    val formatVersion = (envelope[KEY_FORMAT_VERSION] as? JsonPrimitive)?.intOrNull
    if (formatVersion == null || formatVersion != FORMAT_VERSION) {
        return ImportResult.Error(
            "Unsupported config format version: $formatVersion (expected $FORMAT_VERSION)"
        )
    }

    val prefVersion = (envelope[KEY_PREFERENCE_VERSION] as? JsonPrimitive)?.intOrNull
    if (prefVersion != null && prefVersion != PREFERENCE_VERSION) {
        Log.w(TAG, "Importing config from preference version $prefVersion (current: $PREFERENCE_VERSION)")
    }

    val preferences = envelope[KEY_PREFERENCES] as? JsonObject
        ?: return ImportResult.Error("Missing 'preferences' in config file")

    val userIdRemap = buildUserIdRemap(context, envelope)
    val exportable = getExportableEntries()

    val editor = LauncherPreferences.getSharedPreferences().edit()
    editor.clear()

    for ((key, value) in preferences) {
        val type = exportable[key]
        if (type == null) {
            Log.i(TAG, "Skipping unknown preference key: $key")
            continue
        }
        try {
            writePref(editor, key, type, remapUserIds(value, userIdRemap))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to import preference '$key': ${e.message}")
        }
    }

    if (prefVersion != null) {
        editor.putInt(
            LauncherPreferences.internal().keys().versionCode(),
            prefVersion
        )
    }

    editor.apply()

    migratePreferencesToNewVersion(context)

    return ImportResult.Success
}

/**
 * Build a mapping from exported user IDs to local user IDs based on profile types.
 */
private fun buildUserIdRemap(context: Context, envelope: JsonObject): Map<Int, Int> {
    val userProfiles = envelope[KEY_USER_PROFILES] as? JsonObject ?: return emptyMap()
    val localMap = buildProfileTypeToUserIdMap(context)
    val remap = mutableMapOf<Int, Int>()

    for ((exportedIdStr, typeElement) in userProfiles) {
        val exportedId = exportedIdStr.toIntOrNull() ?: continue
        val profileType = (typeElement as? JsonPrimitive)?.content ?: continue
        val localId = localMap[profileType]
        if (localId != null) {
            remap[exportedId] = localId
        } else {
            Log.w(TAG, "No local profile match for type '$profileType' (exported user $exportedId), using INVALID_USER")
            remap[exportedId] = INVALID_USER
        }
    }

    return remap
}

/**
 * Recursively remap user IDs in JSON values.
 */
private fun remapUserIds(element: JsonElement, remap: Map<Int, Int>): JsonElement {
    if (remap.isEmpty()) return element

    return when (element) {
        is JsonPrimitive -> {
            if (element.isString) {
                JsonPrimitive(remapUserIdsInString(element.content, remap))
            } else {
                element
            }
        }
        is JsonArray -> JsonArray(element.map { remapUserIds(it, remap) })
        is JsonObject -> JsonObject(element.map { (k, v) -> k to remapUserIds(v, remap) }.toMap())
        is JsonNull -> element
    }
}

private fun remapUserIdsInString(value: String, remap: Map<Int, Int>): String {
    if (!value.contains("\"user\"")) return value

    return try {
        val parsed = Json.decodeFromString(JsonElement.serializer(), value)
        val remapped = remapUserIdsInJsonElement(parsed, remap)
        Json.encodeToString(JsonElement.serializer(), remapped)
    } catch (_: Exception) {
        value
    }
}

private fun remapUserIdsInJsonElement(element: JsonElement, remap: Map<Int, Int>): JsonElement {
    return when (element) {
        is JsonObject -> {
            val entries = element.entries.map { (k, v) ->
                if (k == "user" && v is JsonPrimitive && v.intOrNull != null) {
                    val oldId = v.int
                    k to JsonPrimitive(remap[oldId] ?: oldId)
                } else {
                    k to remapUserIdsInJsonElement(v, remap)
                }
            }
            JsonObject(entries.toMap())
        }
        is JsonArray -> JsonArray(element.map { remapUserIdsInJsonElement(it, remap) })
        else -> element
    }
}

// --- SharedPreferences <-> JSON helpers ---

/**
 * Read a preference value from SharedPreferences and convert it to a JsonElement.
 * The [type] is one of the SharedPreferences type names from [LauncherPreferences.getExportEntries].
 */
private fun readPref(prefs: SharedPreferences, key: String, type: String): JsonElement {
    return when (type) {
        "boolean" -> JsonPrimitive(prefs.getBoolean(key, false))
        "int" -> JsonPrimitive(prefs.getInt(key, 0))
        "long" -> JsonPrimitive(prefs.getLong(key, 0))
        "float" -> JsonPrimitive(prefs.getFloat(key, 0f))
        "String" -> JsonPrimitive(prefs.getString(key, null) ?: "")
        "Set<String>" -> JsonArray(
            (prefs.getStringSet(key, null) ?: emptySet()).map { JsonPrimitive(it) }
        )
        else -> {
            Log.w(TAG, "Unknown preference type '$type' for key '$key'")
            JsonPrimitive(prefs.getString(key, null) ?: "")
        }
    }
}

/**
 * Write a JSON value to a SharedPreferences editor.
 * The [type] is one of the SharedPreferences type names from [LauncherPreferences.getExportEntries].
 */
private fun writePref(editor: SharedPreferences.Editor, key: String, type: String, value: JsonElement) {
    val prim = value as? JsonPrimitive
    when (type) {
        "boolean" -> editor.putBoolean(key, prim?.boolean ?: return)
        "int" -> editor.putInt(key, prim?.int ?: return)
        "long" -> editor.putLong(key, prim?.long ?: return)
        "float" -> editor.putFloat(key, prim?.float ?: return)
        "String" -> editor.putString(key, prim?.content ?: return)
        "Set<String>" -> editor.putStringSet(
            key,
            (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: return
        )
        else -> Log.w(TAG, "Cannot import preference '$key': unknown type '$type'")
    }
}
