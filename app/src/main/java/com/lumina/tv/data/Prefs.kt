package com.lumina.tv.data

import android.content.Context
import java.util.UUID

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

    /** 播放内核：exo（硬解直连） / auto */
    var playerEngine: String
        get() = sp.getString("engine", "exo") ?: "exo"
        set(v) = sp.edit().putString("engine", v).apply()

    /** 强制转码（部分异形编码在电视盒上直连会卡顿时使用） */
    var forceTranscode: Boolean
        get() = sp.getBoolean("force_transcode", false)
        set(v) = sp.edit().putBoolean("force_transcode", v).apply()

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

    /** 自动连播下一集 */
    var autoNextEpisode: Boolean
        get() = sp.getBoolean("auto_next", false)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

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
