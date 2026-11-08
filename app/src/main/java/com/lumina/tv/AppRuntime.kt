package com.lumina.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.Prefs
import com.lumina.tv.data.Session
import com.lumina.tv.pair.PairConfig
import com.lumina.tv.pair.PairingServer
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** 全局运行时：给 UI 与内置配对服务共享的薄状态层。 */
object AppRuntime {
    var prefs: Prefs? = null
    var client: EmbyClient? = null
    var session: Session? = null

    var pairing: PairingServer? = null
    var pairingUrl: String = ""
    var pairingError: String? = null
    var pairingStatus by mutableStateOf("")

    var screenLabel: String = "登录"
    var nowPlaying: String = ""

    var onRemoteKey: ((String) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onPairConfig: ((String, String, String) -> Boolean)? = null
    var onPairRequest: ((PairConfig) -> Unit)? = null

    fun resolvePairRequest(state: String): Boolean = pairing?.resolvePending(state) == true
    fun pairRequestIsPending(): Boolean = pairing?.hasPendingRequest() == true

    @Volatile private var pairingToken: String? = null
    @Volatile private var pairingAccessToken: String? = null
    @Volatile private var pairingTokenExpiresAt: Long = 0L

    fun issuePairingToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also {
            pairingToken = it
            pairingAccessToken = it
            pairingTokenExpiresAt = System.currentTimeMillis() + 5 * 60 * 1000L
        }
    }

    fun updatePairingStatus(message: String) {
        pairingStatus = message.take(240)
    }

    @Synchronized
    fun consumePairingToken(token: String): Boolean {
        val expected = pairingToken ?: return false
        val notExpired = System.currentTimeMillis() <= pairingTokenExpiresAt
        return notExpired && token.isNotBlank() && java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8)
        )
    }

    fun validatePairingAccess(token: String): Boolean {
        val expected = pairingAccessToken ?: return false
        return System.currentTimeMillis() <= pairingTokenExpiresAt && token.isNotBlank() &&
            java.security.MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }

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
        pairingStatus = ""
        val server = PairingServer.start(
            stateProvider = { stateJson() },
            onConfig = { token, server, user, pass ->
                if (consumePairingToken(token)) onPairConfig?.invoke(server, user, pass) == true else false
            },
            onPairRequest = { config -> onPairRequest?.invoke(config) },
            authorize = { token -> validatePairingAccess(token) },
            onRemote = { action -> onRemoteKey?.invoke(action) },
            onSearch = { keyword -> onSearch?.invoke(keyword) },
        )
        pairing = server
        val ip = PairingServer.localIp()
        pairingUrl = if (server != null && ip != null) "http://$ip:${server.listeningPort}/?token=${issuePairingToken()}" else ""
        pairingError = if (server == null) "无法启动配对服务（端口被占用）" else if (ip == null) "未连接到局域网" else null
    }

    fun stopPairing() {
        runCatching { pairing?.stop() }
        pairing = null
        pairingUrl = ""
        pairingToken = null
        pairingAccessToken = null
        pairingTokenExpiresAt = 0L
    }

    fun stateJson(): String = JSONObject()
        .put("screen", screenLabel)
        .put("playing", nowPlaying)
        .put("server", session?.serverName ?: "")
        .put("user", session?.userName ?: "")
        .put("pairingStatus", pairingStatus)
        .toString()
}
