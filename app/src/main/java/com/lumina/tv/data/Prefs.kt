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
class Prefs internal constructor(private val sp: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE))
    private val sessionStore = SessionStore(sp)

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
        // SessionStore consumes the five legacy session fields as a single, read-only input.
        migrateLegacySecrets(sp, SecureStore::get, SecureStore::put, keys = listOf("saved_servers"))
    }

    var declareDtsDirectPlay: Boolean
        get() = sp.getBoolean("declare_dts_direct_play", false)
        set(v) = sp.edit().putBoolean("declare_dts_direct_play", v).apply()

    val baseUrl: String
        get() = sessionStore.read(migrate = false)?.baseUrl ?: if (sessionStore.hasRecord())
            sp.getString(SessionStore.DRAFT_URL, "").orEmpty() else getSecret("base_url")

    val token: String
        get() = sessionStore.read(migrate = false)?.token ?: if (sessionStore.hasRecord()) "" else getSecret("token")

    val userId: String
        get() = sessionStore.read(migrate = false)?.userId ?: if (sessionStore.hasRecord()) "" else getSecret("user_id")

    val userName: String
        get() = sessionStore.read(migrate = false)?.userName ?: if (sessionStore.hasRecord())
            sp.getString(SessionStore.DRAFT_USER, "").orEmpty() else getSecret("user_name")

    var serverName: String
        get() = sessionStore.read(migrate = false)?.serverName ?: if (sessionStore.hasRecord())
            sp.getString(SessionStore.DRAFT_NAME, "Emby") ?: "Emby" else getSecret("server_name", "Emby")
        set(v) {
            val session = sessionStore.read(migrate = false)
            if (session != null) check(saveSession(session.copy(serverName = v)))
            else sp.edit().putString(SessionStore.DRAFT_NAME, v).apply()
        }

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

    var anime4kMode: String
        get() = sp.getString("anime4k_mode", "off")
            ?.takeIf { it in setOf("off", "efficiency", "quality") } ?: "off"
        set(v) = sp.edit().putString("anime4k_mode", v.takeIf { it in setOf("off", "efficiency", "quality") } ?: "off").apply()

    var seekStepSeconds: Int
        get() = sp.getInt("seek_step_seconds", 10).takeIf { it in setOf(5, 10, 15, 30) } ?: 10
        set(v) = sp.edit().putInt("seek_step_seconds", v.takeIf { it in setOf(5, 10, 15, 30) } ?: 10).apply()

    var playerVolume: Int
        get() = sp.getInt("player_volume", 100).coerceIn(0, 100)
        set(v) = sp.edit().putInt("player_volume", v.coerceIn(0, 100)).apply()

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
        val servers = savedServerList().filterNot { sameServer(it.url, url) }
        val updated = s.copy(url = url, lastUsed = System.currentTimeMillis())
        savedServersJson = org.json.JSONArray().apply {
            (servers + updated).forEach { server -> put(server.toJson()) }
        }.toString()
    }

    fun currentSavedServer(): SavedServer? = savedServerList().firstOrNull {
        sameServer(it.url, baseUrl)
    }

    fun saveCurrentServer() {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) return
        upsertServer(SavedServer(serverName.ifBlank { url }, url, userName, userId, token, System.currentTimeMillis()))
    }

    fun removeSavedServer(url: String) {
        val key = canonicalServerKey(url) ?: return
        savedServersJson = org.json.JSONArray().apply {
            savedServerList().filterNot { canonicalServerKey(it.url) == key }
                .forEach { put(it.toJson()) }
        }.toString()
    }

    fun renameSavedServer(url: String, name: String) {
        val key = canonicalServerKey(url) ?: return
        savedServersJson = org.json.JSONArray().apply {
            savedServerList().forEach { server ->
                put(server.copy(name = if (canonicalServerKey(server.url) == key) name.trim().ifBlank { server.url } else server.name).toJson())
            }
        }.toString()
        if (canonicalServerKey(baseUrl) == key) serverName = name.trim().ifBlank { "Emby" }
    }

    private fun sameServer(left: String, right: String): Boolean =
        canonicalServerKey(left)?.let { it == canonicalServerKey(right) } ?: false

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
        val server = canonicalServerKey(baseUrl) ?: baseUrl.trim().ifBlank { "none" }
        return namespace(server)
    }

    // Preserve the exact old rule, including its raw URL/default-port/trailing-slash differences.
    private fun legacyNs(): String = namespace(baseUrl.trim().lowercase().ifBlank { "none" })

    private fun namespace(server: String): String {
        val user = userId.ifBlank { "none" }
        return Integer.toHexString(server.hashCode()) + "_" + user.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun itemKey(prefix: String, itemId: String): String {
        val key = "${prefix}_${ns()}_$itemId"
        val legacyKey = "${prefix}_${legacyNs()}_$itemId"
        val marker = "item_ns_migrated_v2_$key"
        if (key != legacyKey && !sp.getBoolean(marker, false)) {
            val edit = sp.edit()
            if (!sp.contains(key) && sp.contains(legacyKey)) {
                if (prefix == "audio_track") edit.putInt(key, sp.getInt(legacyKey, -1))
                else edit.putString(key, sp.getString(legacyKey, null))
            }
            // Copy and marker share one edit. Retain old keys; never resurrect a later cleared value.
            edit.putBoolean(marker, true).apply()
        }
        return key
    }

    private fun normalizeSubtitlePreference(stored: String): String = when {
        stored == "off" || stored == "auto" || stored.startsWith("track:") -> stored
        stored == "-1" -> "off"
        else -> "track:$stored"
    }

    fun subtitleModeFor(itemId: String, source: MediaSource? = null): SubtitleMode = when {
        subtitlePreferenceFor(itemId, source) == "off" -> SubtitleMode.OFF
        subtitlePreferenceFor(itemId, source)?.startsWith("track:") == true -> SubtitleMode.TRACK
        else -> SubtitleMode.AUTO
    }

    fun subtitlePreferenceFor(itemId: String, source: MediaSource? = null): String? {
        val key = itemKey("subtitle_track", itemId)
        // 只读该片的手动记忆；旧的跨片字幕轨/关闭状态不能遮住全局语言偏好。
        val stored = sp.getString(key, null)
            ?: return null
        val value = normalizeSubtitlePreference(stored)
        if (value != stored) sp.edit().putString(key, value).apply()
        if (value.startsWith("track:")) {
            if (source == null || sp.getString(itemKey("subtitle_identity", itemId), null) !=
                com.lumina.tv.player.trackMemoryIdentity(source, "Subtitle")) return null
            val index = value.removePrefix("track:emby:").toIntOrNull() ?: return null
            if (source.subtitleStreams.count { it.index == index } != 1) return null
        }
        return value
    }

    fun subtitleTrackFor(itemId: String, source: MediaSource? = null): String? = subtitlePreferenceFor(itemId, source)
        ?.takeIf { it.startsWith("track:") }
        ?.removePrefix("track:")

    fun rememberSubtitleTrack(itemId: String, trackKey: String?, source: MediaSource? = null) {
        val value = when {
            trackKey == null || trackKey == "off" -> "off"
            trackKey == "auto" -> "auto"
            trackKey == "-1" -> "off"
            trackKey.startsWith("track:") -> trackKey
            else -> "track:$trackKey"
        }
        val key = itemKey("subtitle_track", itemId)
        if (value.startsWith("track:")) {
            val index = value.removePrefix("track:emby:").toIntOrNull() ?: return
            if (source == null || source.subtitleStreams.count { it.index == index } != 1) return
        }
        sp.edit()
            .putString(key, value)
            .putString(itemKey("subtitle_identity", itemId), source?.let { com.lumina.tv.player.trackMemoryIdentity(it, "Subtitle") })
            .apply()
    }

    fun audioTrackFor(itemId: String, source: MediaSource? = null): Int? {
        if (source == null || sp.getString(itemKey("audio_identity", itemId), null) !=
            com.lumina.tv.player.trackMemoryIdentity(source, "Audio")) return null
        val key = itemKey("audio_track", itemId)
        return sp.getInt(key, -1).takeIf { index -> source.audioStreams.count { it.index == index } == 1 }
    }

    fun rememberAudioTrack(itemId: String, streamIndex: Int, source: MediaSource? = null) {
        if (source == null || source.audioStreams.count { it.index == streamIndex } != 1) return
        sp.edit().putInt(itemKey("audio_track", itemId), streamIndex)
            .putString(itemKey("audio_identity", itemId), com.lumina.tv.player.trackMemoryIdentity(source, "Audio")).apply()
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

    private val contentState: AccountContentState?
        get() = identityScope(baseUrl, userId)?.let { AccountContentState(sp, it) }

    /** 每部剧集的片头时长（itemId -> 秒），按服务器与账号存储 */
    var introMapJson: String
        get() = contentState?.introMapJson ?: "{}"
        set(v) { contentState?.introMapJson = v }

    fun introSecondsFor(itemId: String): Int {
        if (itemId.isBlank()) return 0
        val perItem = runCatching {
            org.json.JSONObject(introMapJson).optInt(itemId, -1)
        }.getOrDefault(-1)
        return if (perItem >= 0) perItem else globalIntroSeconds
    }

    fun setIntroSeconds(itemId: String, seconds: Int) {
        if (itemId.isBlank()) return
        contentState?.setIntroSeconds(itemId, seconds)
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
        get() = contentState?.hiddenViewIds.orEmpty()
        set(v) { contentState?.hiddenViewIds = v }

    fun isViewHidden(viewId: String): Boolean = viewId in hiddenViewIds

    fun toggleViewHidden(viewId: String) {
        contentState?.toggleViewHidden(viewId)
    }

    val hiddenResumeIds: Map<String, Long> get() = contentState?.hiddenResumeIds.orEmpty()

    fun hideResumeItem(item: MediaItem, hiddenAt: Long = System.currentTimeMillis()) {
        contentState?.hideResumeItem(item, hiddenAt)
    }

    fun visibleResumeItems(items: List<MediaItem>): List<MediaItem> =
        contentState?.visibleResumeItems(items) ?: items

    fun saveSession(s: Session): Boolean = sessionStore.save(s)

    fun readSession(): Session? = sessionStore.read()

    fun prepareServerLogin(url: String, user: String) { check(sessionStore.clear(url, user)) }

    fun clearSession() {
        check(sessionStore.clear())
    }

    fun hasSession(): Boolean = sessionStore.read(migrate = false) != null
}

/** Callbacks let JVM checks exercise failure/retry without requiring a device Keystore. */
internal fun migrateLegacySecrets(
    sp: SharedPreferences,
    readEncrypted: (String) -> String?,
    writeEncrypted: (String, String) -> Boolean,
    keys: List<String> = listOf("base_url", "token", "user_id", "user_name", "server_name", "saved_servers"),
) {
    runCatching {
        if (sp.getBoolean("secrets_migrated_v1", false)) return@runCatching
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
