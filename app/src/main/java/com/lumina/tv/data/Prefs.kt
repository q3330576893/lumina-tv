package com.lumina.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.lumina.tv.diag.PageLog
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

    init {
        if (sp.contains("end_skip_seconds")) {
            val edit = sp.edit()
            if (!sp.contains("end_credits_skip_seconds")) {
                edit.putInt("end_credits_skip_seconds", sp.getInt("end_skip_seconds", 30).coerceIn(0, 60))
            }
            edit.remove("end_skip_seconds").apply()
        }
        migrateLegacySecrets()
    }

    private fun getSecret(key: String, default: String = ""): String =
        SecureStore.get(key) ?: sp.getString(key, default) ?: default

    private fun putSecret(key: String, value: String) {
        if (SecureStore.put(key, value)) {
            sp.edit().remove(key).apply()
        } else {
            // Keystore failure: keep a plaintext fallback for availability, discard stale ciphertext,
            // and retry encryption on the next startup (including the saved_servers token copies).
            sp.edit().putString(key, value).remove(SecureStore.PREFIX + key)
                .putBoolean("secrets_migrated_v1", false).apply()
        }
    }

    private fun migrateLegacySecrets() {
        migrateLegacySecrets(sp, SecureStore::get, SecureStore::put)
    }

    var declareDtsDirectPlay: Boolean
        get() = sp.getBoolean("declare_dts_direct_play", false)
        set(v) = sp.edit().putBoolean("declare_dts_direct_play", v).apply()

    var baseUrl: String
        get() = getSecret("base_url")
        set(v) = putSecret("base_url", v)

    var token: String
        get() = getSecret("token")
        set(v) = putSecret("token", v)

    var userId: String
        get() = getSecret("user_id")
        set(v) = putSecret("user_id", v)

    var userName: String
        get() = getSecret("user_name")
        set(v) = putSecret("user_name", v)

    var serverName: String
        get() = getSecret("server_name", "Emby")
        set(v) = putSecret("server_name", v)

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

    var seekStepSeconds: Int
        get() = sp.getInt("seek_step_seconds", 10).takeIf { it in setOf(5, 10, 15, 30) } ?: 10
        set(v) = sp.edit().putInt("seek_step_seconds", v.takeIf { it in setOf(5, 10, 15, 30) } ?: 10).apply()

    var decoderPreference: String
        get() = sp.getString("decoder_preference", "auto") ?: "auto"
        set(v) = sp.edit().putString("decoder_preference", v).apply()

    /** Media3 fast-start buffering; kept hidden so it can be disabled as a compatibility escape hatch. */
    var fastStart: Boolean
        get() = sp.getBoolean("fast_start", true)
        set(v) = sp.edit().putBoolean("fast_start", v).apply()

    var maxStreamingBitrate: Long
        get() = sp.getLong("max_streaming_bitrate", 120_000_000L).takeIf { it in STREAMING_BITRATE_OPTIONS }
            ?: 120_000_000L
        set(v) = sp.edit().putLong("max_streaming_bitrate", v.takeIf { it in STREAMING_BITRATE_OPTIONS } ?: 120_000_000L).apply()

    var playbackCacheBytes: Long
        get() = sp.getLong("playback_cache_bytes", 512L * 1024L * 1024L).takeIf { it in PLAYBACK_CACHE_OPTIONS }
            ?: 512L * 1024L * 1024L
        set(v) = sp.edit().putLong("playback_cache_bytes", v.takeIf { it in PLAYBACK_CACHE_OPTIONS } ?: 512L * 1024L * 1024L).apply()

    var playbackCacheLastTrimAt: Long
        get() = sp.getLong("playback_cache_last_trim_at", 0L)
        set(v) = sp.edit().putLong("playback_cache_last_trim_at", v).apply()

    var sourceDynamicRange: DynamicRangePref
        get() = runCatching { DynamicRangePref.valueOf(sp.getString("source_dynamic_range", "UNSET") ?: "UNSET") }
            .getOrDefault(DynamicRangePref.UNSET)
        set(v) = sp.edit().putString("source_dynamic_range", v.name).apply()

    var sourceBitratePreference: NumericPref
        get() = runCatching { NumericPref.valueOf(sp.getString("source_bitrate", "UNSET") ?: "UNSET") }
            .getOrDefault(NumericPref.UNSET)
        set(v) = sp.edit().putString("source_bitrate", v.name).apply()

    var sourceFrameRatePreference: NumericPref
        get() = runCatching { NumericPref.valueOf(sp.getString("source_frame_rate", "UNSET") ?: "UNSET") }
            .getOrDefault(NumericPref.UNSET)
        set(v) = sp.edit().putString("source_frame_rate", v.name).apply()

    var sourceReleaseGroups: String
        get() = sp.getString("source_release_groups", "").orEmpty()
        set(v) = sp.edit().putString("source_release_groups", v).apply()

    val sourceStrategy: SourceStrategy
        get() = SourceStrategy(
            dynamicRange = sourceDynamicRange,
            releaseGroups = sourceReleaseGroups.split(',').map(String::trim).filter(String::isNotEmpty),
            bitrate = sourceBitratePreference,
            frameRate = sourceFrameRatePreference,
        )

    var matchDisplayFrameRate: Boolean
        get() = sp.getBoolean("match_display_frame_rate", false)
        set(v) = sp.edit().putBoolean("match_display_frame_rate", v).apply()


    var savedServersJson: String
        get() = getSecret("saved_servers", "[]")
        set(v) = putSecret("saved_servers", v)

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

    enum class SubtitleMode { OFF, AUTO, TRACK }

    /** 空字符串跟随片源；这些语言偏好不是凭据，使用普通 SharedPreferences。 */
    var preferredSubtitleLanguage: String
        get() = sp.getString("preferred_subtitle_language", "") ?: ""
        set(v) = sp.edit().putString("preferred_subtitle_language", v.takeIf { it in setOf("", "zh", "en", "ja", "ko", "off") } ?: "").apply()

    var preferredAudioLanguage: String
        get() = sp.getString("preferred_audio_language", "") ?: ""
        set(v) = sp.edit().putString("preferred_audio_language", v.takeIf { it in setOf("", "zh", "en", "ja", "ko") } ?: "").apply()

    private fun ns(): String {
        val server = baseUrl.trim().lowercase().ifBlank { "none" }
        val user = userId.ifBlank { "none" }
        return Integer.toHexString(server.hashCode()) + "_" + user.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun itemKey(prefix: String, itemId: String) = "${prefix}_${ns()}_$itemId"

    private fun normalizeSubtitlePreference(stored: String): String = when {
        stored == "off" || stored == "auto" || stored.startsWith("track:") -> stored
        stored == "-1" -> "off"
        else -> "track:$stored"
    }

    private fun migratedSubtitleValue(key: String, legacyKey: String): String? {
        sp.getString(key, null)?.let { return it }
        val legacy = sp.getString(legacyKey, null) ?: return null
        val value = normalizeSubtitlePreference(legacy)
        sp.edit().putString(key, value).remove(legacyKey).apply()
        return value
    }

    fun subtitleModeFor(itemId: String): SubtitleMode = when {
        subtitlePreferenceFor(itemId) == "off" -> SubtitleMode.OFF
        subtitlePreferenceFor(itemId)?.startsWith("track:") == true -> SubtitleMode.TRACK
        else -> SubtitleMode.AUTO
    }

    fun subtitlePreferenceFor(itemId: String): String? {
        val key = itemKey("subtitle_track", itemId)
        // 只读该片的手动记忆；旧的跨片字幕轨/关闭状态不能遮住全局语言偏好。
        val stored = migratedSubtitleValue(key, "subtitle_track_$itemId")
            ?: return null
        val value = normalizeSubtitlePreference(stored)
        if (value != stored) sp.edit().putString(key, value).apply()
        return value
    }

    fun subtitleTrackFor(itemId: String): String? = subtitlePreferenceFor(itemId)
        ?.takeIf { it.startsWith("track:") }
        ?.removePrefix("track:")

    fun rememberSubtitleTrack(itemId: String, trackKey: String?) {
        val value = when {
            trackKey == null || trackKey == "off" -> "off"
            trackKey == "auto" -> "auto"
            trackKey == "-1" -> "off"
            trackKey.startsWith("track:") -> trackKey
            else -> "track:$trackKey"
        }
        val key = itemKey("subtitle_track", itemId)
        sp.edit()
            .putString(key, value)
            .apply()
    }

    fun audioTrackFor(itemId: String): Int? {
        val key = itemKey("audio_track", itemId)
        if (sp.contains(key)) return sp.getInt(key, -1).takeIf { it >= 0 }
        if (!sp.contains("audio_track_$itemId")) return null
        val legacy = sp.getInt("audio_track_$itemId", -1)
        sp.edit().putInt(key, legacy).remove("audio_track_$itemId").apply()
        return legacy.takeIf { it >= 0 }
    }

    fun rememberAudioTrack(itemId: String, streamIndex: Int) {
        sp.edit().putInt(itemKey("audio_track", itemId), streamIndex).apply()
    }

    fun lastSourceFor(itemId: String): String? = sp.getString(itemKey("last_source", itemId), null)

    fun rememberSource(itemId: String, sourceId: String?) {
        if (itemId.isBlank()) return
        sp.edit().putString(itemKey("last_source", itemId), sourceId?.takeIf { it.isNotBlank() }).apply()
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

    var baseTheme: String
        get() = sp.getString("base_theme", "ink")?.takeIf { it in setOf("ink", "oled", "graphite", "warm", "mint") } ?: "ink"
        set(v) = sp.edit().putString("base_theme", v.takeIf { it in setOf("ink", "oled", "graphite", "warm", "mint") } ?: "ink").apply()

    /** 玻璃质感：frosted 磨砂 / clear 清透 / off 关闭（省电） */
    var glassStyle: String
        get() = sp.getString("glass_style", "frosted") ?: "frosted"
        set(v) = sp.edit().putString("glass_style", v).apply()

    var detailBackgroundVideo: String
        get() = sp.getString("detail_background_video", "auto") ?: "auto"
        set(v) = sp.edit().putString("detail_background_video", v.takeIf { it in setOf("off", "720p", "auto") } ?: "auto").apply()

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

    var hiddenResumeIds: Map<String, Long>
        get() = runCatching {
            val json = org.json.JSONObject(sp.getString("hidden_resume_ids", "{}") ?: "{}")
            buildMap { json.keys().forEach { id -> put(id, json.optLong(id, 0L)) } }
        }.getOrDefault(emptyMap())
        set(value) {
            val json = org.json.JSONObject()
            value.forEach { (id, timestamp) -> json.put(id, timestamp) }
            sp.edit().putString("hidden_resume_ids", json.toString()).apply()
        }

    private var hiddenResumePositions: Map<String, Long>
        get() = runCatching {
            val json = org.json.JSONObject(sp.getString("hidden_resume_positions", "{}") ?: "{}")
            buildMap { json.keys().forEach { id -> put(id, json.optLong(id, 0L)) } }
        }.getOrDefault(emptyMap())
        set(value) {
            val json = org.json.JSONObject()
            value.forEach { (id, position) -> json.put(id, position) }
            sp.edit().putString("hidden_resume_positions", json.toString()).apply()
        }

    fun hideResumeItem(item: MediaItem, hiddenAt: Long = System.currentTimeMillis()) {
        hiddenResumeIds = hiddenResumeIds + (item.id to hiddenAt)
        hiddenResumePositions = hiddenResumePositions + (item.id to item.positionTicks)
    }

    fun visibleResumeItems(items: List<MediaItem>): List<MediaItem> {
        val hidden = hiddenResumeIds.toMutableMap()
        val positions = hiddenResumePositions.toMutableMap()
        val visible = items.filter { item ->
            val hiddenAt = hidden[item.id] ?: return@filter true
            val savedPosition = positions[item.id]
            val playedAgain = (item.lastPlayedDate?.let { it > hiddenAt } == true) ||
                (savedPosition != null && item.positionTicks != savedPosition)
            if (playedAgain) {
                hidden.remove(item.id)
                positions.remove(item.id)
                true
            } else false
        }
        if (hidden != hiddenResumeIds) hiddenResumeIds = hidden
        if (positions != hiddenResumePositions) hiddenResumePositions = positions
        return visible
    }

    fun saveSession(s: Session) {
        baseUrl = s.baseUrl
        token = s.token
        userId = s.userId
        userName = s.userName
        serverName = s.serverName
    }

    fun clearSession() {
        val edit = sp.edit()
        for (key in listOf("base_url", "token", "user_id", "user_name")) {
            edit.remove(key).remove(SecureStore.PREFIX + key)
        }
        edit.apply()
    }

    fun hasSession(): Boolean = baseUrl.isNotBlank() && token.isNotBlank() && userId.isNotBlank()
}

/** Callbacks let JVM checks exercise failure/retry without requiring a device Keystore. */
internal fun migrateLegacySecrets(
    sp: SharedPreferences,
    readEncrypted: (String) -> String?,
    writeEncrypted: (String, String) -> Boolean,
) {
    runCatching {
        if (sp.getBoolean("secrets_migrated_v1", false)) return@runCatching
        val keys = listOf("base_url", "token", "user_id", "user_name", "server_name", "saved_servers")
        val edit = sp.edit()
        for (key in keys) {
            val legacy = sp.getString(key, null) ?: continue
            if (sp.getString(SecureStore.PREFIX + key, null) == null) {
                check(writeEncrypted(key, legacy))
            } else {
                // A previous attempt may have persisted only some encrypted values.
                // Never delete a legacy value unless the existing ciphertext decrypts to it.
                check(readEncrypted(key) == legacy)
            }
            edit.remove(key)
        }
        // Delay all plaintext removal and the marker until every value is safely encrypted.
        edit.putBoolean("secrets_migrated_v1", true).apply()
    }.onFailure {
        runCatching { PageLog.add("secureStore op=migrate failed=${it.javaClass.simpleName}") }
    }
}

val STREAMING_BITRATE_OPTIONS = setOf(20_000_000L, 40_000_000L, 80_000_000L, 120_000_000L)
val PLAYBACK_CACHE_OPTIONS = setOf(0L, 256L, 512L, 1024L, 2048L).map { it * 1024L * 1024L }.toSet()
