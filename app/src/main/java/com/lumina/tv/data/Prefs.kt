package com.lumina.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.lumina.tv.diag.PageLog
import java.util.UUID
import com.lumina.tv.player.TrackMemoryType
import com.lumina.tv.player.TrackMemoryIdentity
import com.lumina.tv.player.TrackIdentityV2
import com.lumina.tv.player.TrackVersionEvidence
import com.lumina.tv.player.TrackInvalidReason
import com.lumina.tv.player.trackIdentityMismatch
import com.lumina.tv.player.trackMemoryIdentity

data class SavedServer(
    val name: String,
    val url: String,
    val userName: String,
    val userId: String,
    val token: String,
    val lastUsed: Long,
)

internal enum class TrackMemoryMode { INHERIT, TRACK, OFF, AUTO }
internal data class TrackMemoryRecord(val type: TrackMemoryType, val mode: TrackMemoryMode,
    val index: Int? = null, val identity: TrackIdentityV2? = null, val invalidReason: TrackInvalidReason? = null,
    val legacyReason: TrackInvalidReason? = null)
internal data class TrackMemoryRead(val record: TrackMemoryRecord, val persisted: Boolean)

/** 极轻量的本地配置存储：服务器、凭证、播放内核偏好。 */
class Prefs internal constructor(
    private val sp: SharedPreferences,
    private val encrypt: (String) -> String? = SecureStore::encrypt,
    private val decrypt: (String) -> String? = SecureStore::decrypt,
    // Production deliberately admits no opaque MediaSource validator; tests supply attested evidence.
    private val trackIdentity: (MediaSource?, TrackMemoryType) -> TrackMemoryIdentity = { source, type ->
        trackMemoryIdentity(source, type.name)
    },
) {
    constructor(context: Context) : this(context.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE))

    /** UI subscriptions are limited to a named preference; no credential values are exposed. */
    internal fun observePreference(key: String, changed: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, updated ->
            if (updated == key) changed()
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private val sessionStore = SessionStore(sp, encrypt, decrypt, legacy = { key ->
        getSecret(key, if (key == "server_name") "Emby" else "")
    })
    private var failedServerName: String? = null
    private var secretStorageError: String? = null

    val credentialStorageError: String?
        get() = sessionStore.lastError ?: secretStorageError

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
        sp.getString(SecureStore.PREFIX + key, null)?.let { runCatching { decrypt(it) }.getOrNull() }
            ?: sp.getString(key, default) ?: default

    private fun writeEncryptedSecret(key: String, value: String): Boolean =
        encrypt(value)?.takeIf(String::isNotBlank)?.let { ciphertext ->
            sp.edit().putString(SecureStore.PREFIX + key, ciphertext).commit()
        } ?: false

    private fun putSecret(key: String, value: String): Boolean = runCatching {
        check(writeEncryptedSecret(key, value)) { "Secret encryption or commit failed" }
        // A failed encryption/commit must leave the only legacy copy available for retry.
        if (sp.contains(key)) check(sp.edit().remove(key).commit())
        check(sp.edit().putBoolean("secret_migrated_v1_$key", true).commit())
        secretStorageError = null
        true
    }.onFailure {
        secretStorageError = "无法安全保存服务器凭据，请检查设备安全存储后重试。"
        PageLog.add("prefs secret save failed=${it.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun migrateLegacySecrets() {
        // SessionStore consumes the five legacy session fields as a single, read-only input.
        if (!migrateLegacySecrets(sp, { key ->
                sp.getString(SecureStore.PREFIX + key, null)?.let(decrypt)
            }, ::writeEncryptedSecret, keys = listOf("saved_servers"))) {
            secretStorageError = "旧服务器凭据尚未完成安全迁移，原有副本已保留，请重试。"
        }
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
        get() = failedServerName ?: sessionStore.read(migrate = false)?.serverName ?: if (sessionStore.hasRecord())
            sp.getString(SessionStore.DRAFT_NAME, "Emby") ?: "Emby" else getSecret("server_name", "Emby")
        set(v) {
            val session = sessionStore.read(migrate = false)
            if (session != null) {
                if (saveSession(session.copy(serverName = v))) failedServerName = null
                else {
                    failedServerName = failedServerName ?: session.serverName
                    PageLog.add("prefs serverName save failed")
                }
            }
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
        set(v) { check(putSecret("saved_servers", v)) { credentialStorageError.orEmpty() } }

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

    fun upsertServer(s: SavedServer): Boolean {
        val url = s.url.trim().trimEnd('/')
        if (url.isBlank()) return false
        val servers = savedServerList().filterNot { sameServer(it.url, url) }
        val updated = s.copy(url = url, lastUsed = System.currentTimeMillis())
        return putSecret("saved_servers", org.json.JSONArray().apply {
            (servers + updated).forEach { server -> put(server.toJson()) }
        }.toString())
    }

    fun currentSavedServer(): SavedServer? = savedServerList().firstOrNull {
        sameServer(it.url, baseUrl)
    }

    fun saveCurrentServer(): Boolean {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) return false
        return upsertServer(SavedServer(serverName.ifBlank { url }, url, userName, userId, token, System.currentTimeMillis()))
    }

    fun removeSavedServer(url: String): Boolean {
        val key = canonicalServerKey(url) ?: return false
        return putSecret("saved_servers", org.json.JSONArray().apply {
            savedServerList().filterNot { canonicalServerKey(it.url) == key }
                .forEach { put(it.toJson()) }
        }.toString())
    }

    fun renameSavedServer(
        url: String,
        name: String,
        expectedSession: Session? = sessionStore.read(migrate = false),
    ): Boolean = synchronized(sessionStore) {
        // The same monitor is used by SessionStore.save/clear, including callers outside AppRuntime.
        if (sessionStore.read(migrate = false) != expectedSession) return false
        val key = canonicalServerKey(url) ?: return false
        if (!putSecret("saved_servers", org.json.JSONArray().apply {
            savedServerList().forEach { server ->
                put(server.copy(name = if (canonicalServerKey(server.url) == key) name.trim().ifBlank { server.url } else server.name).toJson())
            }
        }.toString())) return false
        if (canonicalServerKey(baseUrl) == key) {
            val renamed = name.trim().ifBlank { "Emby" }
            val session = sessionStore.read(migrate = false)
            if (session != expectedSession) return false
            if (session != null) return saveSession(session.copy(serverName = renamed))
            return sp.edit().putString(SessionStore.DRAFT_NAME, renamed).commit()
        }
        true
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

    private data class TrackKeys(val payload: String, val seen: String, val suffix: String, val legacySuffix: String)
    private data class LegacyTrackValue(val value: Any?, val prefix: String, val copyTo: String?, val marker: String?)

    // All Prefs instances serialize read/migrate/invalidate/write through this lock. Account scope
    // is frozen before any editor is created. Failed writes remain fail-closed in this process.
    companion object {
        private val trackMemoryLock = Any()
        private val failedTrackWrites = java.util.IdentityHashMap<SharedPreferences, MutableMap<String, TrackMemoryRecord>>()
    }

    var trackMemoryStorageError: String? = null
        private set

    private fun trackKeys(itemId: String, type: TrackMemoryType): TrackKeys {
        val suffix = "${ns()}_$itemId"
        return TrackKeys("track_memory_v2_${suffix}_${type.name.lowercase(java.util.Locale.ROOT)}",
            "track_memory_v2_seen_${suffix}_${type.name.lowercase(java.util.Locale.ROOT)}", suffix, "${legacyNs()}_$itemId")
    }

    private fun legacyTrackValue(keys: TrackKeys, type: TrackMemoryType): LegacyTrackValue {
        val prefix = if (type == TrackMemoryType.Audio) "audio_track" else "subtitle_track"
        val key = "${prefix}_${keys.suffix}"
        val old = "${prefix}_${keys.legacySuffix}"
        val marker = "item_ns_migrated_v2_$key"
        val migrate = key != old && runCatching { sp.getBoolean(marker, false) }.getOrDefault(false).not()
        val selected = if (!sp.contains(key) && migrate && sp.contains(old)) old else key
        val raw = runCatching {
            if (!sp.contains(selected)) null
            else if (type == TrackMemoryType.Audio) sp.getInt(selected, -1)
            else sp.getString(selected, null)
        }.getOrElse { InvalidLegacyTrackValue }
        return LegacyTrackValue(raw, prefix, key.takeIf { migrate && selected == old && raw !== InvalidLegacyTrackValue },
            marker.takeIf { migrate })
    }

    private object InvalidLegacyTrackValue

    private fun migratedTrackRecord(type: TrackMemoryType, value: Any?): TrackMemoryRecord = when {
        type == TrackMemoryType.Subtitle && (value == "off" || value == "-1") -> TrackMemoryRecord(type, TrackMemoryMode.OFF)
        type == TrackMemoryType.Subtitle && value == "auto" -> TrackMemoryRecord(type, TrackMemoryMode.AUTO)
        value == null -> TrackMemoryRecord(type, TrackMemoryMode.INHERIT)
        type == TrackMemoryType.Audio && value is Int || type == TrackMemoryType.Subtitle && value is String &&
            value.isNotBlank() && (value.startsWith("track:") || value.toIntOrNull() != null || Regex(".+:[0-9]+").matches(value)) ->
            TrackMemoryRecord(type, TrackMemoryMode.INHERIT, invalidReason = TrackInvalidReason.SCHEMA_LEGACY,
                legacyReason = TrackInvalidReason.LEGACY_UNVERIFIABLE)
        else -> TrackMemoryRecord(type, TrackMemoryMode.INHERIT, invalidReason = TrackInvalidReason.MALFORMED_RECORD)
    }

    private fun encodeTrackRecord(record: TrackMemoryRecord): String {
        val identity = record.identity?.let { id -> org.json.JSONObject()
            .put("schemaVersion", id.schemaVersion).put("type", id.type.name).put("sourceId", id.sourceId)
            .put("sourceVersion", id.sourceVersion).put("inventoryDigest", id.inventoryDigest)
            .put("validator", org.json.JSONObject().put("kind", id.validator.kind).put("value", id.validator.value)
                .put("coverage", id.validator.coverage)) }
        return org.json.JSONObject().put("schemaVersion", 2).put("type", record.type.name).put("mode", record.mode.name)
            .put("index", record.index ?: org.json.JSONObject.NULL).put("identity", identity ?: org.json.JSONObject.NULL)
            .put("invalidReason", record.invalidReason?.name ?: org.json.JSONObject.NULL)
            .put("legacyReason", record.legacyReason?.name ?: org.json.JSONObject.NULL).toString()
    }

    private fun decodeTrackRecord(json: org.json.JSONObject, type: TrackMemoryType): TrackMemoryRecord {
        require(listOf("schemaVersion", "type", "mode", "index", "identity", "invalidReason").all(json::has))
        fun text(o: org.json.JSONObject, key: String) = o.opt(key) as? String ?: error("Invalid track string")
        fun int(o: org.json.JSONObject, key: String) = o.opt(key) as? Int ?: error("Invalid track integer")
        require(int(json, "schemaVersion") == 2)
        require(text(json, "type") == type.name)
        val mode = TrackMemoryMode.valueOf(text(json, "mode"))
        require(type == TrackMemoryType.Subtitle || mode in setOf(TrackMemoryMode.TRACK, TrackMemoryMode.INHERIT))
        val index = if (json.isNull("index")) null else int(json, "index")
        val identity = if (json.isNull("identity")) null else {
            val id = json.getJSONObject("identity")
            val validator = id.getJSONObject("validator")
            TrackIdentityV2(int(id, "schemaVersion"), TrackMemoryType.valueOf(text(id, "type")), text(id, "sourceId"),
                TrackVersionEvidence(text(validator, "kind"), text(validator, "value"), text(validator, "coverage")),
                text(id, "sourceVersion"), text(id, "inventoryDigest")).also {
                require(it.schemaVersion == 2 && it.type == type && it.sourceId.isNotBlank() && it.validator.isValid())
                require(Regex("[0-9a-f]{64}").matches(it.inventoryDigest))
            }
        }
        val reason = if (json.isNull("invalidReason")) null else TrackInvalidReason.valueOf(text(json, "invalidReason"))
        val legacy = if (json.isNull("legacyReason")) null else TrackInvalidReason.valueOf(text(json, "legacyReason"))
        if (mode == TrackMemoryMode.TRACK) require(index != null && index >= 0 && identity != null && reason == null && legacy == null)
        else require(index == null && identity == null)
        return TrackMemoryRecord(type, mode, index, identity, reason, legacy)
    }

    private fun persistTrackRecord(keys: TrackKeys, record: TrackMemoryRecord, legacy: LegacyTrackValue? = null): TrackMemoryRead {
        val success = runCatching {
            val edit = sp.edit().putString(keys.payload, encodeTrackRecord(record)).putBoolean(keys.seen, true)
            legacy?.copyTo?.let { key ->
                when (val value = legacy.value) {
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                    else -> Unit
                }
            }
            legacy?.marker?.let { edit.putBoolean(it, true) }
            edit.commit()
        }.getOrDefault(false)
        if (success) {
            failedTrackWrites[sp]?.remove(keys.payload)
            trackMemoryStorageError = null
        } else {
            failedTrackWrites.getOrPut(sp) { mutableMapOf() }[keys.payload] = record.copy(
                mode = if (record.mode == TrackMemoryMode.TRACK) TrackMemoryMode.INHERIT else record.mode,
                index = null, identity = null)
            trackMemoryStorageError = "Track memory commit failed"
        }
        return TrackMemoryRead(record, success)
    }

    internal fun trackMemoryFor(itemId: String, type: TrackMemoryType, source: MediaSource? = null): TrackMemoryRead =
        synchronized(trackMemoryLock) {
            val keys = trackKeys(itemId, type)
            failedTrackWrites[sp]?.get(keys.payload)?.let { return@synchronized persistTrackRecord(keys, it) }
            if (!sp.contains(keys.payload) && !sp.contains(keys.seen)) {
                val legacy = legacyTrackValue(keys, type)
                return@synchronized persistTrackRecord(keys, migratedTrackRecord(type, legacy.value), legacy)
            }
            // Presence, not the value of seen, establishes v2 authority. Never call itemKey here.
            val json = runCatching { sp.getString(keys.payload, null)?.let(::orgJsonObject) }.getOrNull()
            val schema = json?.opt("schemaVersion") as? Int
            if (schema != null && schema > 2) return@synchronized TrackMemoryRead(
                TrackMemoryRecord(type, TrackMemoryMode.INHERIT, invalidReason = TrackInvalidReason.UNKNOWN_SCHEMA), true)
            val failure = when {
                schema != null && schema < 2 -> TrackInvalidReason.SCHEMA_LEGACY
                json != null && (json.opt("type") is String && json.opt("type") != type.name ||
                    json.optJSONObject("identity")?.opt("type")?.let { it != type.name } == true) -> TrackInvalidReason.TYPE_CHANGED
                else -> TrackInvalidReason.MALFORMED_RECORD
            }
            val record = json?.let { runCatching { decodeTrackRecord(it, type) }.getOrNull() }
                ?: return@synchronized persistTrackRecord(keys, TrackMemoryRecord(type, TrackMemoryMode.INHERIT,
                    invalidReason = failure, legacyReason = TrackInvalidReason.LEGACY_UNVERIFIABLE.takeIf { failure == TrackInvalidReason.SCHEMA_LEGACY }))
            if (record.mode != TrackMemoryMode.TRACK) return@synchronized TrackMemoryRead(record, true)
            // No context is not an observation of a different version; do not retire the stored record.
            if (source == null) return@synchronized TrackMemoryRead(TrackMemoryRecord(type, TrackMemoryMode.INHERIT,
                invalidReason = TrackInvalidReason.NO_CONTEXT), true)
            val current = trackIdentity(source, type)
            val saved = requireNotNull(record.identity)
            val reason = when {
                saved.sourceId != source.id -> TrackInvalidReason.SOURCE_CHANGED
                current is TrackMemoryIdentity.Unavailable -> current.reason
                source.streams.count { it.type == type.name && it.index == record.index } != 1 -> TrackInvalidReason.INDEX_CHANGED
                source.streams.single { it.type == type.name && it.index == record.index }.isExternal -> TrackInvalidReason.EXTERNAL_UNATTESTED
                else -> trackIdentityMismatch(saved, current)
            }
            if (reason == null) TrackMemoryRead(record, true)
            else persistTrackRecord(keys, TrackMemoryRecord(type, TrackMemoryMode.INHERIT, invalidReason = reason))
        }

    private fun orgJsonObject(value: String) = org.json.JSONObject(value)

    private fun rememberTrack(itemId: String, type: TrackMemoryType, mode: TrackMemoryMode, index: Int?, source: MediaSource?): Boolean =
        synchronized(trackMemoryLock) {
            val keys = trackKeys(itemId, type)
            var identity: TrackIdentityV2? = null
            var reason: TrackInvalidReason? = null
            if (mode == TrackMemoryMode.TRACK) {
                val current = trackIdentity(source, type)
                reason = when {
                    current is TrackMemoryIdentity.Unavailable -> current.reason
                    index == null || index < 0 || source?.streams?.count { it.type == type.name && it.index == index } != 1 -> TrackInvalidReason.INDEX_CHANGED
                    source.streams.single { it.type == type.name && it.index == index }.isExternal -> TrackInvalidReason.EXTERNAL_UNATTESTED
                    else -> null
                }
                if (reason == null) identity = (current as TrackMemoryIdentity.Available).identity
            }
            persistTrackRecord(keys, TrackMemoryRecord(type, if (mode == TrackMemoryMode.TRACK && identity == null) TrackMemoryMode.INHERIT else mode,
                index.takeIf { identity != null }, identity, reason)).persisted
        }

    fun subtitleModeFor(itemId: String, source: MediaSource? = null): SubtitleMode = when (subtitlePreferenceFor(itemId, source)) {
        "off" -> SubtitleMode.OFF
        null, "auto" -> SubtitleMode.AUTO
        else -> SubtitleMode.TRACK
    }

    fun subtitlePreferenceFor(itemId: String, source: MediaSource? = null): String? {
        val read = trackMemoryFor(itemId, TrackMemoryType.Subtitle, source)
        if (!read.persisted) return null
        return when (read.record.mode) {
            TrackMemoryMode.OFF -> "off"
            TrackMemoryMode.AUTO -> "auto"
            TrackMemoryMode.TRACK -> "track:emby:${read.record.index}"
            TrackMemoryMode.INHERIT -> null
        }
    }

    fun subtitleTrackFor(itemId: String, source: MediaSource? = null): String? = subtitlePreferenceFor(itemId, source)
        ?.takeIf { it.startsWith("track:") }?.removePrefix("track:")

    fun rememberSubtitleTrack(itemId: String, trackKey: String?, source: MediaSource? = null): Boolean {
        val mode = when (trackKey) {
            "off", "-1" -> TrackMemoryMode.OFF
            "auto" -> TrackMemoryMode.AUTO
            null, "" -> TrackMemoryMode.INHERIT
            else -> TrackMemoryMode.TRACK
        }
        val index = trackKey?.removePrefix("track:")?.takeIf { it.startsWith("emby:") }?.removePrefix("emby:")?.toIntOrNull()
        return rememberTrack(itemId, TrackMemoryType.Subtitle, mode, index, source)
    }

    fun audioTrackFor(itemId: String, source: MediaSource? = null): Int? {
        val read = trackMemoryFor(itemId, TrackMemoryType.Audio, source)
        return read.record.index.takeIf { read.persisted && read.record.mode == TrackMemoryMode.TRACK }
    }

    fun rememberAudioTrack(itemId: String, streamIndex: Int, source: MediaSource? = null): Boolean =
        rememberTrack(itemId, TrackMemoryType.Audio, TrackMemoryMode.TRACK, streamIndex, source)


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

    fun saveSession(s: Session): Boolean = sessionStore.save(s).also { saved ->
        if (saved) failedServerName = null
    }

    fun readSession(): Session? = sessionStore.read()

    fun prepareServerLogin(url: String, user: String): Boolean = sessionStore.clear(url, user).also { cleared ->
        if (!cleared) PageLog.add("prefs prepareServerLogin failed")
    }

    fun clearSession(): Boolean = sessionStore.clear().also { cleared ->
        if (!cleared) PageLog.add("prefs clearSession failed")
    }

    fun hasSession(): Boolean = sessionStore.read(migrate = false) != null
}

/** Callbacks let JVM checks exercise failure/retry without requiring a device Keystore. */
internal fun migrateLegacySecrets(
    sp: SharedPreferences,
    readEncrypted: (String) -> String?,
    writeEncrypted: (String, String) -> Boolean,
    keys: List<String> = listOf("base_url", "token", "user_id", "user_name", "server_name", "saved_servers"),
): Boolean = runCatching {
    if (sp.getBoolean("secrets_migrated_v1", false) && keys.none { sp.getString(it, null) != null }) {
        return@runCatching true
    }
    for (key in keys) {
        val marker = "secret_migrated_v1_$key"
        val legacy = sp.getString(key, null)
        if (legacy == null) {
            // Cleanup may have committed before a previous marker write failed.
            if (sp.getString(SecureStore.PREFIX + key, null) != null && !sp.getBoolean(marker, false)) {
                check(readEncrypted(key) != null)
                check(sp.edit().putBoolean(marker, true).commit())
            }
            continue
        }
        if (sp.getString(SecureStore.PREFIX + key, null) == null) {
            check(writeEncrypted(key, legacy))
        } else {
            // A previous attempt may have persisted only some encrypted values.
            // Never delete a legacy value unless the existing ciphertext decrypts to it.
            check(readEncrypted(key) == legacy)
        }
        // Commit each record's ciphertext first, then its cleanup, then its marker.
        check(sp.edit().remove(key).commit())
        check(sp.edit().putBoolean(marker, true).commit())
    }
    check(sp.edit().putBoolean("secrets_migrated_v1", true).commit())
    true
}.onFailure {
    runCatching { PageLog.add("secureStore op=migrate failed=${it.javaClass.simpleName}") }
}.getOrDefault(false)

val STREAMING_BITRATE_OPTIONS = setOf(20_000_000L, 40_000_000L, 80_000_000L, 120_000_000L)
val PLAYBACK_CACHE_OPTIONS = setOf(0L, 256L, 512L, 1024L, 2048L).map { it * 1024L * 1024L }.toSet()
