package com.mvrk.vrka.mi5g.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mi5G Dl settings & history sync through a single JSON file the user picks with the Storage Access
 * Framework (e.g. on Google Drive) — no OAuth. Format:
 * { "format":"mi5gdl-sync", "version":1, "fileId":"uuid", "updatedAt":millis,
 *   "devices": { id: {name, lastSyncAt, appVersion} },
 *   "settings": { key: {"v": value, "t": updatedAt} },
 *   "history": { uuid: {...entry..., "updatedAt"} | {"deleted":true, "deletedAt", "updatedAt"} } }
 */
object SyncPolicy {
    /** Settings that follow the user across phones (DataStore key → type). */
    val SYNCED_SETTINGS: Map<String, Type> = linkedMapOf(
        "theme_mode" to Type.STRING,
        "amoled" to Type.BOOL,
        "font_preference" to Type.STRING,
        "default_mode" to Type.STRING,
        "default_quality" to Type.STRING,
        "default_audio" to Type.STRING,
        "default_bitrate" to Type.INT,
        "update_preference" to Type.STRING,
        "concurrent_downloads_enabled" to Type.BOOL,
        "max_concurrent_downloads" to Type.INT,
        "h264_compatibility_mode" to Type.BOOL,
        "custom_command_mode" to Type.BOOL,
        "custom_command_templates" to Type.STRING,
        "selected_custom_command_template_id" to Type.STRING,
        "share_skip_sheet" to Type.BOOL,
        "trim_precise_default" to Type.BOOL,
        "update_daily_check" to Type.BOOL,
    )

    /**
     * Never synced: secrets (GitHub token, browser cookies/sessions/headers) and device-specific
     * values (download folder tree URI, location mode, timestamps). Listed for documentation/tests;
     * anything not in [SYNCED_SETTINGS] is excluded anyway.
     */
    val NEVER_SYNCED: Set<String> = setOf(
        "github_token", "token", "cookies", "browser_session", "custom_headers", "authorization",
        "output_tree_uri", "is_download_location_configured", "save_location_mode",
        "last_app_update_check_timestamp", "sync_file_uri", "dark_theme", "concurrency",
    )

    private val SECRETISH = Regex("(token|secret|password|cookie|session|auth|credential|tree_uri)", RegexOption.IGNORE_CASE)

    fun isSyncable(key: String): Boolean =
        key in SYNCED_SETTINGS && key !in NEVER_SYNCED && !SECRETISH.containsMatchIn(key)

    /**
     * Custom yt-dlp command templates are synced, but never when they embed credentials
     * (cookies, auth headers, passwords, tokens). Such a template stays on the phone it was made on.
     */
    private val SECRET_VALUE = Regex(
        "(--cookies|cookies-from-browser|--username|--password|--video-password|--ap-password|--netrc|authorization\\s*:|cookie\\s*:|bearer\\s|token=|api[_-]?key|github_pat_|ghp_)",
        RegexOption.IGNORE_CASE,
    )

    fun valueLooksSecret(value: Any?): Boolean = value is String && SECRET_VALUE.containsMatchIn(value)

    fun isSyncableValue(key: String, value: Any?): Boolean = isSyncable(key) && !valueLooksSecret(value)

    enum class Type { STRING, BOOL, INT }
}

data class SettingValue(val value: Any, val updatedAt: Long)

/** One download-history entry as shared between devices (no file URIs, folders or headers). */
data class HistoryRecord(
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val url: String = "",
    val title: String = "",
    val state: String = "DONE",
    val mode: String = "VIDEO",
    val quality: String = "BEST",
    val audioFormat: String = "MP3",
    val bitrate: Int = 320,
    val trimStart: String = "",
    val trimEnd: String = "",
    val trimPrecise: Boolean = false,
    val detail: String = "",
    val error: String = "",
    val actualResolution: String? = null,
    val createdAt: Long = 0L,
    val device: String = "",
) {
    companion object {
        fun tombstone(id: String, deletedAt: Long) = HistoryRecord(id = id, updatedAt = deletedAt, deleted = true, deletedAt = deletedAt)
    }
}

data class DeviceInfo(val name: String, val lastSyncAt: Long, val appVersion: String)

data class SyncSnapshot(
    val settings: Map<String, SettingValue> = emptyMap(),
    val history: Map<String, HistoryRecord> = emptyMap(),
    val devices: Map<String, DeviceInfo> = emptyMap(),
    val fileId: String? = null,
    val updatedAt: Long = 0L,
)

class SyncFormatException(val code: String) : Exception(code)

object SyncCodec {
    const val FORMAT = "mi5gdl-sync"
    const val VERSION = 1

    fun encode(s: SyncSnapshot): String {
        val o = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .putOpt("fileId", s.fileId).put("updatedAt", s.updatedAt)
        o.put("devices", JSONObject().apply {
            s.devices.toSortedMap().forEach { (id, d) ->
                put(id, JSONObject().put("name", d.name).put("lastSyncAt", d.lastSyncAt).put("appVersion", d.appVersion))
            }
        })
        o.put("settings", JSONObject().apply {
            s.settings.toSortedMap().forEach { (k, v) ->
                if (SyncPolicy.isSyncableValue(k, v.value)) put(k, JSONObject().put("v", v.value).put("t", v.updatedAt))
            }
        })
        o.put("history", JSONObject().apply {
            s.history.values.sortedBy { it.id }.forEach { r -> put(r.id, encodeRecord(r)) }
        })
        return o.toString(1)
    }

    private fun encodeRecord(r: HistoryRecord): JSONObject {
        if (r.deleted) return JSONObject().put("deleted", true).put("deletedAt", r.deletedAt ?: r.updatedAt).put("updatedAt", r.updatedAt)
        return JSONObject()
            .put("updatedAt", r.updatedAt).put("createdAt", r.createdAt)
            .put("url", r.url).put("title", r.title).put("state", r.state)
            .put("mode", r.mode).put("quality", r.quality).put("audioFormat", r.audioFormat).put("bitrate", r.bitrate)
            .put("trimStart", r.trimStart).put("trimEnd", r.trimEnd).put("trimPrecise", r.trimPrecise)
            .put("detail", r.detail).put("error", r.error.take(500))
            .putOpt("actualResolution", r.actualResolution).put("device", r.device)
    }

    fun decode(text: String?): SyncSnapshot {
        if (text.isNullOrBlank()) return SyncSnapshot()
        val o = try { JSONObject(text) } catch (e: Exception) { throw SyncFormatException("not_json") }
        if (o.optString("format") != FORMAT) throw SyncFormatException("wrong_format")
        if (o.optInt("version", 0) > VERSION) throw SyncFormatException("newer_version")
        val devices = buildMap {
            o.optJSONObject("devices")?.let { d ->
                d.keys().forEach { id ->
                    val x = d.optJSONObject(id) ?: return@forEach
                    put(id, DeviceInfo(x.optString("name"), x.optLong("lastSyncAt"), x.optString("appVersion")))
                }
            }
        }
        val settings = buildMap {
            o.optJSONObject("settings")?.let { st ->
                st.keys().forEach { k ->
                    if (!SyncPolicy.isSyncable(k)) return@forEach
                    val x = st.optJSONObject(k) ?: return@forEach
                    val v = coerce(k, x.opt("v")) ?: return@forEach
                    if (SyncPolicy.valueLooksSecret(v)) return@forEach
                    put(k, SettingValue(v, x.optLong("t")))
                }
            }
        }
        val history = buildMap {
            o.optJSONObject("history")?.let { h ->
                h.keys().forEach { id ->
                    val x = h.optJSONObject(id) ?: return@forEach
                    put(id, decodeRecord(id, x))
                }
            }
        }
        return SyncSnapshot(settings, history, devices, if (o.isNull("fileId")) null else o.optString("fileId").ifBlank { null }, o.optLong("updatedAt"))
    }

    private fun decodeRecord(id: String, x: JSONObject): HistoryRecord {
        if (x.optBoolean("deleted")) {
            val at = x.optLong("deletedAt", x.optLong("updatedAt"))
            return HistoryRecord(id = id, updatedAt = x.optLong("updatedAt", at), deleted = true, deletedAt = at)
        }
        return HistoryRecord(
            id = id,
            updatedAt = x.optLong("updatedAt"),
            createdAt = x.optLong("createdAt"),
            url = x.optString("url"),
            title = x.optString("title"),
            state = x.optString("state", "DONE"),
            mode = x.optString("mode", "VIDEO"),
            quality = x.optString("quality", "BEST"),
            audioFormat = x.optString("audioFormat", "MP3"),
            bitrate = x.optInt("bitrate", 320),
            trimStart = x.optString("trimStart"),
            trimEnd = x.optString("trimEnd"),
            trimPrecise = x.optBoolean("trimPrecise"),
            detail = x.optString("detail"),
            error = x.optString("error"),
            actualResolution = x.optString("actualResolution").takeIf { it.isNotBlank() && it != "null" },
            device = x.optString("device"),
        )
    }

    /** Values are type-checked against the policy so a malformed file can't inject other types. */
    fun coerce(key: String, raw: Any?): Any? = when (SyncPolicy.SYNCED_SETTINGS[key]) {
        SyncPolicy.Type.BOOL -> raw as? Boolean
        SyncPolicy.Type.INT -> (raw as? Number)?.toInt()
        SyncPolicy.Type.STRING -> (raw as? String)?.takeIf { it.length <= 64_000 }
        null -> null
    }

    @Suppress("unused")
    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
}
