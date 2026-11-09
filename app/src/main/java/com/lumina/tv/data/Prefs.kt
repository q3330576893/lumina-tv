package com.lumina.tv.data

import android.content.Context
import java.util.UUID

data class SavedServer(
    val name: String,
    val url: String,
    val userName: String,
    val userId: String,
    val token: String,
    val lastUsed: Long,
)

/** 极轻量的本地配置存储：服务器、凭证、播放内核偏好。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "") ?: ""
        set(v) = sp.edit().putString("base_url", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var userId: String
        get() = sp.getString("user_id", "") ?: ""
        set(v) = sp.edit().putString("user_id", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var serverName: String
        get() = sp.getString("server_name", "Emby") ?: "Emby"
        set(v) = sp.edit().putString("server_name", v).apply()

    var serverNote: String?
        get() = sp.getString("server_note", null)
        set(v) = sp.edit().putString("server_note", v).apply()

    /** 播放内核：exo（硬解直连） / auto */
    var playerEngine: String
        get() {
            val stored = sp.getString("engine", "auto") ?: "auto"
            if (stored == "ijk") {
                sp.edit().putString("engine", "auto").apply()
                return "auto"
            }
            return stored
        }
        set(v) = sp.edit().putString("engine", v).apply()

    var mpvBroken: Boolean
        get() = sp.getBoolean("mpv_broken", false)
        set(v) = sp.edit().putBoolean("mpv_broken", v).apply()

    var decoderPreference: String
        get() = sp.getString("decoder_preference", "auto") ?: "auto"
        set(v) = sp.edit().putString("decoder_preference", v).apply()


    var savedServersJson: String
        get() = sp.getString("saved_servers", "[]") ?: "[]"
        set(v) = sp.edit().putString("saved_servers", v).apply()

    fun savedServerList(): List<SavedServer> = runCatching {
        val array = org.json.JSONArray(savedServersJson)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                SavedServer(
                    name = item.optString("name", ""),
                    url = item.optString("url", ""),
                    userName = item.optString("userName", ""),
                    userId = item.optString("userId", ""),
                    token = item.optString("token", ""),
                    lastUsed = item.optLong("lastUsed", 0L),
                )
            }?.takeIf { it.url.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    fun savedServers(): List<Pair<String, String>> = savedServerList().map { it.name to it.url }

    fun upsertServer(s: SavedServer) {
        val url = s.url.trim().trimEnd('/')
        if (url.isBlank()) return
        val servers = savedServerList().filterNot { it.url.trimEnd('/').equals(url, ignoreCase = true) }
        val updated = s.copy(url = url, lastUsed = System.currentTimeMillis())
        savedServersJson = org.json.JSONArray().apply {
            (servers + updated).forEach { server -> put(server.toJson()) }
        }.toString()
    }

    fun currentSavedServer(): SavedServer? = savedServerList().firstOrNull {
        it.url.trimEnd('/').equals(baseUrl.trimEnd('/'), ignoreCase = true)
    }

    fun saveCurrentServer() {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) return
        upsertServer(SavedServer(serverName.ifBlank { url }, url, userName, userId, token, System.currentTimeMillis()))
    }

    fun removeSavedServer(url: String) {
        val normalized = url.trimEnd('/')
        savedServersJson = org.json.JSONArray().apply {
            savedServerList().filterNot { it.url.trimEnd('/').equals(normalized, ignoreCase = true) }
                .forEach { put(it.toJson()) }
        }.toString()
    }

    fun renameSavedServer(url: String, name: String) {
        val normalized = url.trimEnd('/')
        savedServersJson = org.json.JSONArray().apply {
            savedServerList().forEach { server ->
                put(server.copy(name = if (server.url.trimEnd('/').equals(normalized, true)) name.trim().ifBlank { server.url } else server.name).toJson())
            }
        }.toString()
        if (baseUrl.trimEnd('/').equals(normalized, true)) serverName = name.trim().ifBlank { "Emby" }
    }

    private fun SavedServer.toJson() = org.json.JSONObject()
        .put("name", name)
        .put("url", url)
        .put("userName", userName)
        .put("userId", userId)
        .put("token", token)
        .put("lastUsed", lastUsed)

    fun subtitleTrackFor(itemId: String): String? = sp.getString("subtitle_track_$itemId", null)

    fun rememberSubtitleTrack(itemId: String, trackKey: String?) {
        sp.edit().apply {
            if (trackKey.isNullOrBlank()) remove("subtitle_track_$itemId")
            else putString("subtitle_track_$itemId", trackKey)
        }.apply()
    }

    fun audioTrackFor(itemId: String): Int? = sp.getInt("audio_track_$itemId", -1).takeIf { it >= 0 }

    fun rememberAudioTrack(itemId: String, streamIndex: Int) {
        sp.edit().putInt("audio_track_$itemId", streamIndex).apply()
    }

    /** 强制转码（部分异形编码在电视盒上直连会卡顿时使用） */
    var forceTranscode: Boolean
        get() = sp.getBoolean("force_transcode", false)
        set(v) = sp.edit().putBoolean("force_transcode", v).apply()

    var audioDecodeMode: String
        get() {
            val stored = sp.getString("audio_decode_mode", null)
            if (stored in setOf("auto", "soft", "passthrough")) return stored!!
            val migrated = if (sp.getBoolean("force_audio_decode", false)) "soft" else "auto"
            sp.edit().putString("audio_decode_mode", migrated).remove("force_audio_decode").apply()
            return migrated
        }
        set(v) = sp.edit().putString("audio_decode_mode", v.takeIf { it in setOf("auto", "soft", "passthrough") } ?: "auto").apply()

    var endCreditsSkipSeconds: Int
        get() = sp.getInt("end_credits_skip_seconds", 30)
        set(v) = sp.edit().putInt("end_credits_skip_seconds", v).apply()

    var librarySort: String
        get() = sp.getString("library_sort", "date") ?: "date"
        set(v) = sp.edit().putString("library_sort", v).apply()

    var librarySortOrder: String
        get() = sp.getString("library_sort_order", "Descending") ?: "Descending"
        set(v) = sp.edit().putString("library_sort_order", v).apply()

    var lastPlaybackAudioFailure: String
        get() = sp.getString("last_playback_audio_failure", "") ?: ""
        set(v) = sp.edit().putString("last_playback_audio_failure", v).apply()

    // ---------------- v1.4.0 个性化 ----------------

    /** 主题色：blue / purple / teal / pink / gold */
    var themeColor: String
        get() = sp.getString("theme_color", "blue") ?: "blue"
        set(v) = sp.edit().putString("theme_color", v).apply()

    /** 玻璃质感：frosted 磨砂 / clear 清透 / off 关闭（省电） */
    var glassStyle: String
        get() = sp.getString("glass_style", "frosted") ?: "frosted"
        set(v) = sp.edit().putString("glass_style", v).apply()

    /** 默认倍速 */
    var defaultSpeed: Float
        get() = sp.getFloat("default_speed", 1.0f)
        set(v) = sp.edit().putFloat("default_speed", v).apply()

    /** DV Profile 7 直接用 mpv 软解 */
    var dvP7UseMpv: Boolean
        get() = sp.getBoolean("dv_p7_use_mpv", true)
        set(v) = sp.edit().putBoolean("dv_p7_use_mpv", v).apply()

    /** 自动连播下一集 */
    /** 海报上显示观看状态（已看 ✓） */
    var showPosterWatchState: Boolean
        get() = sp.getBoolean("show_poster_watch_state", true)
        set(v) = sp.edit().putBoolean("show_poster_watch_state", v).apply()

    /** 海报上显示评分 */
    var showPosterRating: Boolean
        get() = sp.getBoolean("show_poster_rating", true)
        set(v) = sp.edit().putBoolean("show_poster_rating", v).apply()

    var autoNextEpisode: Boolean
        get() = sp.getBoolean("auto_next", false)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    var endSkipSeconds: Int
        get() = sp.getInt("end_skip_seconds", 30).coerceIn(0, 60)
        set(v) = sp.edit().putInt("end_skip_seconds", v.coerceIn(0, 60)).apply()

    var searchHistoryJson: String
        get() = sp.getString("search_history", "[]") ?: "[]"
        private set(v) = sp.edit().putString("search_history", v).apply()

    fun searchHistory(): List<String> = runCatching {
        val array = org.json.JSONArray(searchHistoryJson)
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }.take(8)
    }.getOrDefault(emptyList())

    fun rememberSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistoryJson = org.json.JSONArray().apply {
            (listOf(normalized) + searchHistory().filterNot { it.equals(normalized, ignoreCase = true) })
                .take(8).forEach(::put)
        }.toString()
    }

    fun removeSearch(query: String) {
        searchHistoryJson = org.json.JSONArray().apply {
            searchHistory().filterNot { it == query }.forEach(::put)
        }.toString()
    }

    /** 开机自启（TV 盒子实用） */
    var bootLaunch: Boolean
        get() = sp.getBoolean("boot_launch", false)
        set(v) = sp.edit().putBoolean("boot_launch", v).apply()

    /** 字幕缩放：0.85 / 1.0 / 1.25 / 1.5 */
    var subtitleScale: Float
        get() = sp.getFloat("subtitle_scale", 1.0f)
        set(v) = sp.edit().putFloat("subtitle_scale", v).apply()

    /** 字幕背景底色 */
    var subtitleBg: Boolean
        get() = sp.getBoolean("subtitle_bg", false)
        set(v) = sp.edit().putBoolean("subtitle_bg", v).apply()

    var subtitleColor: Int
        get() = sp.getInt("subtitle_color", android.graphics.Color.WHITE)
        set(v) = sp.edit().putInt("subtitle_color", v).apply()

    var subtitleOutline: Boolean
        get() = sp.getBoolean("subtitle_outline", true)
        set(v) = sp.edit().putBoolean("subtitle_outline", v).apply()

    var subtitlePosition: Float
        get() = sp.getFloat("subtitle_position", 0.08f)
        set(v) = sp.edit().putFloat("subtitle_position", v.coerceIn(0f, 0.25f)).apply()

    var subtitleOffsetMs: Long
        get() = sp.getLong("subtitle_offset_ms", 0L)
        set(v) = sp.edit().putLong("subtitle_offset_ms", v.coerceIn(-10_000L, 10_000L)).apply()

    /** 全局片头时长（秒），0 = 关闭 */
    var globalIntroSeconds: Int
        get() = sp.getInt("global_intro_seconds", 0)
        set(v) = sp.edit().putInt("global_intro_seconds", v).apply()

    /** 每部剧集的片头时长（itemId -> 秒），JSON 存储 */
    var introMapJson: String
        get() = sp.getString("intro_map", "{}") ?: "{}"
        set(v) = sp.edit().putString("intro_map", v).apply()

    fun introSecondsFor(itemId: String): Int {
        if (itemId.isBlank()) return 0
        val perItem = runCatching {
            org.json.JSONObject(introMapJson).optInt(itemId, -1)
        }.getOrDefault(-1)
        return if (perItem >= 0) perItem else globalIntroSeconds
    }

    fun setIntroSeconds(itemId: String, seconds: Int) {
        if (itemId.isBlank()) return
        val obj = runCatching { org.json.JSONObject(introMapJson) }.getOrDefault(org.json.JSONObject())
        obj.put(itemId, seconds)
        introMapJson = obj.toString()
    }

    val deviceId: String
        get() {
            val saved = sp.getString("device_id", null)
            if (!saved.isNullOrEmpty()) return saved
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("device_id", id).apply()
            return id
        }

    /** 首页媒体库隐藏的服务器分类 ID（勾掉即不在首页显示） */
    var hiddenViewIds: Set<String>
        get() = sp.getStringSet("hidden_view_ids", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_view_ids", v).apply()

    fun isViewHidden(viewId: String): Boolean = viewId in hiddenViewIds

    fun toggleViewHidden(viewId: String) {
        val cur = hiddenViewIds.toMutableSet()
        if (!cur.add(viewId)) cur.remove(viewId)
        hiddenViewIds = cur
    }

    fun saveSession(s: Session) {
        baseUrl = s.baseUrl
        token = s.token
        userId = s.userId
        userName = s.userName
        serverName = s.serverName
    }

    fun clearSession() {
        sp.edit().remove("base_url").remove("token").remove("user_id").remove("user_name").apply()
    }

    fun hasSession(): Boolean = baseUrl.isNotBlank() && token.isNotBlank() && userId.isNotBlank()
}
