package com.lumina.tv

import android.content.Context
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.Prefs
import com.lumina.tv.data.Session
import com.lumina.tv.pair.PairingServer
import org.json.JSONObject

/** 全局运行时：给 UI 与内置配对服务共享的薄状态层。 */
object AppRuntime {
    var prefs: Prefs? = null
    var client: EmbyClient? = null
    var session: Session? = null

    var pairing: PairingServer? = null
    var pairingUrl: String = ""
    var pairingError: String? = null

    var screenLabel: String = "登录"
    var nowPlaying: String = ""

    var onRemoteKey: ((String) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onPairConfig: ((String, String, String) -> Unit)? = null

    fun init(context: Context) {
        if (prefs == null) prefs = Prefs(context.applicationContext)
    }

    fun restore(): Session? {
        val p = prefs ?: return null
        if (!p.hasSession()) return null
        val s = Session(
            baseUrl = p.baseUrl,
            token = p.token,
            userId = p.userId,
            userName = p.userName,
            serverName = p.serverName,
        )
        applySession(s)
        return s
    }

    fun applySession(s: Session) {
        session = s
        client = EmbyClient(
            baseUrl = s.baseUrl,
            token = s.token,
            userId = s.userId,
            deviceId = prefs?.deviceId ?: "lumina-tv",
            userName = s.userName,
            serverName = s.serverName,
        )
        prefs?.saveSession(s)
    }

    fun logout() {
        session = null
        client = null
        prefs?.clearSession()
    }

    fun startPairing(context: Context) {
        if (pairing != null) return
        val server = PairingServer.start(
            stateProvider = { stateJson() },
            onConfig = { server, user, pass -> onPairConfig?.invoke(server, user, pass) },
            onRemote = { action -> onRemoteKey?.invoke(action) },
            onSearch = { keyword -> onSearch?.invoke(keyword) },
        )
        pairing = server
        val ip = PairingServer.localIp()
        pairingUrl = if (server != null && ip != null) "http://$ip:${server.listeningPort}/" else ""
        pairingError = if (server == null) "无法启动配对服务（端口被占用）" else if (ip == null) "未连接到局域网" else null
    }

    fun stopPairing() {
        runCatching { pairing?.stop() }
        pairing = null
        pairingUrl = ""
    }

    fun stateJson(): String = JSONObject()
        .put("screen", screenLabel)
        .put("playing", nowPlaying)
        .put("server", session?.serverName ?: "")
        .put("user", session?.userName ?: "")
        .toString()
}
