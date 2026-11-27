package com.lumina.tv

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.Prefs
import com.lumina.tv.data.ProgressQueue
import com.lumina.tv.data.SecureStore
import com.lumina.tv.data.Session
import com.lumina.tv.data.canonicalServerKey
import com.lumina.tv.pair.PairConfig
import com.lumina.tv.pair.PairingAdvertisement
import com.lumina.tv.pair.PairingEndpoint
import com.lumina.tv.pair.PairingServer
import com.lumina.tv.pair.deliverPairRequest
import com.lumina.tv.pair.refreshPairingAdvertisement
import com.lumina.tv.diag.PageLog
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** 全局运行时：给 UI 与内置配对服务共享的薄状态层。 */
object AppRuntime {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var applicationContext: Context? = null
    @Volatile var prefs: Prefs? = null
    var client: EmbyClient? = null
    var session: Session? = null

    // Display-only changes must not replace the authentication snapshot used by in-flight work.
    private data class SessionDisplayMetadata(val owner: Session, val serverName: String)
    private var sessionDisplayMetadata by mutableStateOf<SessionDisplayMetadata?>(null)
    val serverDisplayName: String?
        get() = sessionDisplayMetadata?.takeIf { it.owner === session }?.serverName ?: session?.serverName

    var pairing: PairingServer? = null

    /** 配对地址必须走 Compose 状态：配对服务是异步启动的，普通 var 不会触发重组，二维码会一直是空白。 */
    var pairingUrl by mutableStateOf("")
    var pairingError by mutableStateOf<String?>(null)
    var pairingRefreshNotice by mutableStateOf<String?>(null)
        private set
    var pairingStatus by mutableStateOf("")

    var screenLabel: String = "登录"
        set(value) {
            if (field != value) PageLog.add(value)
            field = value
        }
    var nowPlaying: String = ""
    @Volatile var currentVideoRange: String = "SDR"
    @Volatile var playingItemId: String = ""
    @Volatile var activePlayerEngine: String = "unknown"

    var onRemoteKey: ((String) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onPairConfig: ((String, String, String) -> Boolean)? = null
    var onPairRequest: ((PairConfig) -> Boolean)? = null

    fun resolvePairRequest(state: String): Boolean = pairing?.resolvePending(state) == true
    fun pairRequestIsPending(): Boolean = pairing?.hasPendingRequest() == true

    @Volatile private var pairingAdvertisement: PairingAdvertisement? = null
    private val pairingMutex = Mutex()
    private var pairingListenerGeneration = 0L
    private val pairingRandom by lazy { SecureRandom() }

    private fun issuePairingToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(pairingRandom::nextBytes))

    fun updatePairingStatus(message: String) {
        pairingStatus = message.take(240)
    }

    @Synchronized
    fun consumePairingToken(token: String): Boolean {
        if (!validatePairingAccess(token)) return false
        pairingAdvertisement = null
        return true
    }

    fun validatePairingAccess(token: String): Boolean =
        pairingAdvertisement?.accepts(token, SystemClock.elapsedRealtime()) == true

    @Synchronized
    fun init(context: Context) {
        applicationContext = context.applicationContext
        if (prefs == null) {
            SecureStore.init(context.applicationContext)
            prefs = Prefs(context.applicationContext)
        }
    }

    @Synchronized
    fun restore(): Session? {
        val p = prefs ?: return null
        val s = p.readSession() ?: return null
        activateSession(s)
        return s
    }

    @Synchronized
    fun applySession(s: Session) {
        require(com.lumina.tv.data.SessionStore.complete(s)) { "Incomplete session" }
        check(prefs?.saveSession(s) != false) { "无法保存登录会话，请检查设备存储后重试" }
        activateSession(s)
    }

    private fun activateSession(s: Session) {
        val previous = session
        val identityChanged = previous != null &&
            com.lumina.tv.data.identityScope(previous.baseUrl, previous.userId) != com.lumina.tv.data.identityScope(s.baseUrl, s.userId)
        if (identityChanged) {
            applicationContext?.let { com.lumina.tv.data.LibraryCache.removeHomeDisk(it, previous.baseUrl, previous.userId) }
            com.lumina.tv.data.LibraryCache.clear()
        }
        // Switching identities seals the old outbox; only explicit logout discards its pending reports.
        client?.close()
        sessionDisplayMetadata = null
        session = s
        client = EmbyClient(
            baseUrl = s.baseUrl,
            token = s.token,
            userId = s.userId,
            deviceId = prefs?.deviceId ?: "lumina-tv",
            userName = s.userName,
            serverName = s.serverName,
            progressQueue = applicationContext?.let { ProgressQueue(it, s.baseUrl, s.userId) },
        )
    }

    /** Persist first, then publish only metadata belonging to the exact session that opened the editor. */
    @Synchronized
    fun renameSavedServer(url: String, name: String, expectedSession: Session? = session): Boolean {
        if (session !== expectedSession) return false
        val p = prefs ?: return false
        val expectedClient = client
        val persistedBefore = p.readSession()
        if (expectedSession != null && (persistedBefore == null ||
                persistedBefore.copy(serverName = expectedSession.serverName) != expectedSession)) return false
        if (!p.renameSavedServer(url, name, expectedSession = persistedBefore)) return false
        val persistedAfter = p.readSession()
        if (session !== expectedSession || client !== expectedClient) return false
        if (expectedSession != null && persistedBefore != null && persistedAfter != null &&
            canonicalServerKey(url) == canonicalServerKey(expectedSession.baseUrl) &&
            persistedAfter.copy(serverName = persistedBefore.serverName) == persistedBefore) {
            sessionDisplayMetadata = SessionDisplayMetadata(expectedSession, persistedAfter.serverName)
        }
        return true
    }

    @Synchronized
    fun switchToServer(url: String): Boolean {
        val p = prefs ?: return false
        val serverKey = canonicalServerKey(url) ?: return false
        val saved = p.savedServerList().firstOrNull {
            canonicalServerKey(it.url) == serverKey
        } ?: return false
        if (saved.token.isBlank()) return false
        val switched = Session(
            baseUrl = saved.url,
            token = saved.token,
            userId = saved.userId,
            userName = saved.userName,
            serverName = saved.name,
        )
        if (!com.lumina.tv.data.SessionStore.complete(switched)) return false
        if (!p.saveSession(switched)) return false
        activateSession(switched)
        p.upsertServer(saved)
        return true
    }

    @Synchronized
    fun logout(): Boolean {
        if (prefs?.clearSession() == false) return false
        client?.clearPendingProgress()
        client?.close()
        session?.let { current ->
            applicationContext?.let { com.lumina.tv.data.LibraryCache.removeHomeDisk(it, current.baseUrl, current.userId) }
        }
        com.lumina.tv.data.LibraryCache.clear()
        sessionDisplayMetadata = null
        session = null
        client = null
        return true
    }

    suspend fun startPairing(context: Context) = refreshPairingUrl(context)

    private fun bindPairingServer() = PairingServer.start(
            stateProvider = { stateJson() },
            onConfig = { token, server, user, pass ->
                if (consumePairingToken(token)) onPairConfig?.invoke(server, user, pass) == true else false
            },
            onPairRequest = { config ->
                deliverPairRequest(onPairRequest, config) == com.lumina.tv.pair.PairRequestDeliveryResult.DELIVERED
            },
            authorize = { token -> validatePairingAccess(token) },
            onRemote = { action -> onRemoteKey?.invoke(action) },
            onSearch = { keyword -> onSearch?.invoke(keyword) },
    )

    suspend fun refreshPairingUrl(context: Context, force: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        pairingMutex.withLock {
            val ip = withContext(Dispatchers.IO) { PairingServer.localIp(context.applicationContext) }
            val existing = pairing
            if (existing?.isAlive != true && (ip != null || force)) {
                // If the dialog closes during bind, retain ownership of the listener we opened.
                withContext(NonCancellable) {
                    pairing = withContext(Dispatchers.IO) {
                        runCatching { existing?.stop() }
                        bindPairingServer()
                    }
                    pairingListenerGeneration++
                }
            }
            val server = pairing?.takeIf { it.isAlive }
            val endpoint = if (ip != null && server != null) PairingEndpoint(ip, server.listeningPort, pairingListenerGeneration) else null
            val previous = pairingAdvertisement
            val now = SystemClock.elapsedRealtime()
            val next = refreshPairingAdvertisement(previous, endpoint, now, force, ::issuePairingToken)
            if (previous !== next) {
                pairingAdvertisement = next
                pairingUrl = next?.url.orEmpty()
                pairingRefreshNotice = when {
                    next == null -> null
                    force -> "配对码已刷新"
                    previous != null && previous.endpoint != next.endpoint -> "局域网地址已变化，配对码已换新"
                    previous != null && now >= previous.expiresAt -> "配对码已到期，已自动换新"
                    else -> null
                }
            }
            pairingError = when {
                ip == null -> "未连接到局域网"
                server == null -> "无法启动配对服务（端口被占用）"
                else -> null
            }
        }
    }

    suspend fun stopPairing() = withContext(Dispatchers.Main.immediate) {
        pairingMutex.withLock {
            val server = pairing
            pairing = null
            pairingAdvertisement = null
            pairingUrl = ""
            pairingRefreshNotice = null
            pairingError = "配对服务未启动"
            withContext(NonCancellable + Dispatchers.IO) { runCatching { server?.stop() } }
        }
        Unit
    }

    fun stateJson(): String = JSONObject()
        .put("screen", screenLabel)
        .put("playing", nowPlaying)
        .put("server", serverDisplayName ?: "")
        .put("user", session?.userName ?: "")
        .put("pairingStatus", pairingStatus)
        .toString()
}
