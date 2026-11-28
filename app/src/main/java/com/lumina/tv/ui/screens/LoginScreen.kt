package com.lumina.tv.ui.screens

import com.lumina.tv.ui.theme.*
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.google.zxing.integration.android.IntentIntegrator
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.SavedServer
import com.lumina.tv.data.ServerAddress
import com.lumina.tv.data.parseServerAddress
import com.lumina.tv.data.remainingBudgetMillis
import com.lumina.tv.pair.PairConfig
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.QrCode
import com.lumina.tv.ui.theme.TypeScale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job

internal fun loginProbeCandidates(address: ServerAddress, onlyPrimary: Boolean): List<String> = buildList {
    add(address.baseUrl)
    if (!onlyPrimary) {
        // Probe ports within the explicitly chosen protocol; crossing protocols needs confirmation.
        add(address.withEndpoint(address.scheme, if (address.scheme == "https") 8920 else 8096).baseUrl)
        add(address.withEndpoint(address.scheme, if (address.scheme == "https") 443 else 80).baseUrl)
    }
}.distinct()

/** Reserve synchronously, before launching work, and invalidate every completion on screen exit. */
internal class LoginAttemptGuard {
    private var generation = 0L
    private var busy = false
    private var active = true

    @Synchronized fun acceptPair(): Long? {
        if (!active || busy) return null
        return ++generation
    }

    @Synchronized fun begin(): Long? {
        if (!active || busy) return null
        busy = true
        return generation
    }

    @Synchronized fun isCurrent(expected: Long): Boolean = active && generation == expected

    @Synchronized fun finish(expected: Long) {
        if (active && generation == expected) busy = false
    }

    @Synchronized fun close() {
        active = false
        busy = false
        generation++
    }
}

private data class PendingLoginPair(val config: PairConfig, val generation: Long)
private data class LoginPairSnapshot(val generation: Long, val paired: Boolean, val alias: String?, val note: String?)

enum class LoginRequiredField { Host, User }

fun loginRequiredField(host: String, user: String): LoginRequiredField? = when {
    host.isBlank() -> LoginRequiredField.Host
    user.isBlank() -> LoginRequiredField.User
    else -> null
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onCancel: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = AppRuntime.prefs
    val attempts = remember { LoginAttemptGuard() }
    val connection = remember { LoginConnectionFlow(attempts) }
    var fieldErrors by remember { mutableStateOf<Map<LoginField, String>>(emptyMap()) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val initialAddress = remember { prefs?.baseUrl?.let { parseServerAddress(it) } }
    var scheme by remember { mutableStateOf(initialAddress?.scheme ?: "http") }
    var host by remember { mutableStateOf(initialAddress?.hostWithPath.orEmpty()) }
    var port by remember { mutableStateOf(initialAddress?.port?.toString() ?: "8096") }
    var user by remember { mutableStateOf(prefs?.userName.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginErrorDialog by remember { mutableStateOf<String?>(null) }
    var pendingLoginBase by remember { mutableStateOf<String?>(null) }
    var scanLoginRequested by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(false) }
    var pairingAlias by remember { mutableStateOf<String?>(null) }
    var pairingNote by remember { mutableStateOf<String?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var unsupportedScan by remember { mutableStateOf(false) }
    var successDialogServer by remember { mutableStateOf<String?>(null) }
    var pendingPairConfig by remember { mutableStateOf<PendingLoginPair?>(null) }
    var pairArrivalTick by remember { mutableStateOf(0) }
    var pairNotice by remember { mutableStateOf<String?>(null) }
    val loginFocusRequester = remember { FocusRequester() }
    val passFocusRequester = remember { FocusRequester() }

    fun leaveLogin() {
        connection.cancel()
        loginJob?.cancel()
        if (AppRuntime.session != null) onCancel() else activity?.finish()
    }

    fun fillAddress(address: ServerAddress) {
        scheme = address.scheme
        host = address.hostWithPath
        port = address.port.toString()
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null) return@rememberLauncherForActivityResult
        val parsed = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val raw = parsed?.contents
        if (raw.isNullOrBlank()) {
            unsupportedScan = true
            return@rememberLauncherForActivityResult
        }
        val uri = runCatching { Uri.parse(raw).takeIf { it.isHierarchical } }.getOrNull()
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val server = uri?.getQueryParameter("server") ?: uri?.getQueryParameter("url") ?: json?.optString("server")?.ifBlank { json.optString("url") }?.takeIf { it.isNotBlank() }
        val scanUser = uri?.getQueryParameter("user") ?: uri?.getQueryParameter("username") ?: json?.optString("username")?.ifBlank { json.optString("user") }
        val scanPass = uri?.getQueryParameter("pass") ?: uri?.getQueryParameter("password") ?: json?.optString("password")?.ifBlank { json.optString("pass") }
        val address = (server ?: raw.takeIf { json == null })?.let { parseServerAddress(it) }
        if (address == null) {
            scanError = "二维码中缺少有效的服务器地址。"
        } else if (!scanUser.isNullOrBlank()) {
            fillAddress(address)
            user = scanUser
            pass = scanPass.orEmpty()
            scanError = null
            pendingLoginBase = address.baseUrl
            scanLoginRequested = true
        } else {
            fillAddress(address)
            scanError = "二维码缺少完整的服务器地址或用户名。"
        }

    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && activity != null) scanLauncher.launch(IntentIntegrator(activity)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("扫描 Lumina 登录配置二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .createScanIntent())
        else scanError = if (granted) "无法启动相机扫描，请重试。" else "需要相机权限才能扫描二维码。"
    }

    fun launchScan() {
        if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null) return
        if (activity == null) {
            scanError = "当前设备无法启动扫码界面。"
        } else if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            scanLauncher.launch(IntentIntegrator(activity)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("扫描 Lumina 登录配置二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .createScanIntent())
        }
    }

    suspend fun loginAt(base: String, name: String, password: String, pair: LoginPairSnapshot) {
        val client = EmbyClient(baseUrl = base, deviceId = prefs?.deviceId ?: "lumina-tv")
        val newSession = try { client.login(base, name, password) } finally { client.close() }
        currentCoroutineContext().ensureActive()
        if (!attempts.isCurrent(pair.generation)) return
        val sessionToSave = newSession.copy(
            serverName = if (pair.paired) pair.alias?.ifBlank { newSession.serverName } ?: newSession.serverName else newSession.serverName,
        )
        // applySession commits encrypted credentials before publishing the runtime session.
        check(connection.persistAuthenticated(pair.generation) {
            checkNotNull(AppRuntime.prefs) { "设备存储尚未就绪" }
            AppRuntime.applySession(sessionToSave)
            AppRuntime.prefs?.saveCurrentServer() == true
        }) { "无法保存服务器凭据，请检查设备存储后重试" }
        if (pair.paired && attempts.isCurrent(pair.generation)) {
            prefs?.serverNote = pair.note?.ifBlank { null }
            AppRuntime.updatePairingStatus(connection.state.message)
            AppRuntime.resolvePairRequest("confirmed")
            successDialogServer = sessionToSave.serverName
            delay(1200)
        }
        currentCoroutineContext().ensureActive()
        if (attempts.isCurrent(pair.generation)) onLoggedIn()
    }

    fun submit(name: String = user, password: String = pass) {
        if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null) return
        if (host.isBlank() || name.isBlank()) {
            fieldErrors = buildMap {
                if (host.isBlank()) put(LoginField.Host, "请填写服务器地址")
                if (name.isBlank()) put(LoginField.User, "请填写用户名")
            }
            error = "请填写服务器地址和用户名"
            return
        }
        val selectedPort = port.trim().ifBlank { if (scheme == "https") "8920" else "8096" }
        val address = pendingLoginBase?.let { parseServerAddress(it) }
            ?: parseServerAddress(host, scheme, selectedPort)
            ?: run {
                val field = if ((selectedPort.toIntOrNull() ?: 0) !in 1..65535) LoginField.Port else LoginField.Host
                fieldErrors = mapOf(field to "服务器地址或端口无效")
                error = "服务器地址或端口无效"; return
            }
        val primary = address.baseUrl
        val onlyPrimary = pendingLoginBase != null || host.contains("://") || paired
        val generation = connection.begin() ?: return
        val pair = LoginPairSnapshot(generation, paired, pairingAlias, pairingNote)
        pendingLoginBase = null
        probing = true; error = null; fieldErrors = emptyMap()
        if (paired) AppRuntime.updatePairingStatus(connection.state.message)
        loginJob = scope.launch {
            try {
                val candidates = loginProbeCandidates(address, onlyPrimary)
                val deadlineNanos = System.nanoTime() + 26_000_000_000L
                var found: String? = null
                for (candidate in candidates) {
                    val remainingMs = remainingBudgetMillis(deadlineNanos)
                    if (remainingMs <= 0L) break
                    try {
                        val probe = EmbyClient(candidate, deviceId = prefs?.deviceId ?: "lumina-tv")
                        try { probe.testConnection(candidate, timeoutMs = remainingMs) } finally { probe.close() }
                        if (remainingBudgetMillis(deadlineNanos) <= 0L) break
                        found = candidate
                        break
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) { }
                }
                currentCoroutineContext().ensureActive()
                if (!attempts.isCurrent(generation)) return@launch
                if (found == null) {
                    probing = false
                    val message = if (remainingBudgetMillis(deadlineNanos) == 0L) "连接超时：请检查服务器响应和网络。" else "服务器连接失败：未找到可用的 Emby 服务，请检查地址、端口和网络。"
                    error = message
                    fieldErrors = mapOf(LoginField.Host to message)
                    connection.error(generation)
                } else {
                    if (found != primary) error = "已自动切换至 $found"
                    if (!connection.authenticating(generation)) return@launch
                    if (paired) AppRuntime.updatePairingStatus(connection.state.message)
                    probing = false; loading = true
                    loginAt(found, name, password, pair)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!attempts.isCurrent(generation)) return@launch
                val message = e.message.orEmpty()
                loginErrorDialog = when {
                    message.contains("401") || message.contains("账号或密码") || message.contains("Unauthorized", ignoreCase = true) -> "账号或密码错误，请核对后重试。"
                    message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) -> "连接超时，请检查服务器地址、端口和网络。"
                    message.contains("Unable to resolve", ignoreCase = true) || message.contains("Failed to connect", ignoreCase = true) || message.contains("Connection refused", ignoreCase = true) || message.contains("CLEARTEXT", ignoreCase = true) -> "服务器连接失败，请检查服务器地址、端口及 HTTP/HTTPS 设置。"
                    message.contains("存储") || message.contains("保存") -> "无法保存登录信息，请检查设备存储后重试。"
                    else -> "登录未完成，请检查服务器、网络和账号信息后重试。"
                }
                error = loginErrorDialog
                val field = if (message.contains("401") || message.contains("账号或密码") || message.contains("Unauthorized", ignoreCase = true)) LoginField.User else LoginField.Host
                fieldErrors = mapOf(field to loginErrorDialog.orEmpty())
                connection.error(generation)
                loginErrorDialog = null
                if (pair.paired) AppRuntime.updatePairingStatus(connection.state.message)

            } finally {
                if (attempts.isCurrent(generation)) {
                    loading = false; probing = false
                    attempts.finish(generation)
                }
            }
        }
    }

    LaunchedEffect(scanLoginRequested) {
        if (scanLoginRequested) {
            scanLoginRequested = false
            submit()
        }
    }

    DisposableEffect(Unit) {
        AppRuntime.onPairConfig = config@{ server, pairedUser, pairedPass ->
            if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null || scanLoginRequested || pairedUser.isBlank()) return@config false
            val address = parseServerAddress(server) ?: return@config false
            val pairGeneration = attempts.acceptPair() ?: return@config false
            connection.pairPending(pairGeneration)
            fillAddress(address)
            user = pairedUser; pass = pairedPass; paired = true
            pairingAlias = null; pairingNote = null
            pairArrivalTick++
            pendingLoginBase = address.baseUrl
            AppRuntime.updatePairingStatus(connection.state.message)
            submit(pairedUser, pairedPass)

            true
        }
        AppRuntime.onPairRequest = request@{ config ->
            if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null || scanLoginRequested) return@request false
            val generation = attempts.acceptPair() ?: return@request false
            if (!connection.pairPending(generation)) return@request false
            AppRuntime.updatePairingStatus(connection.state.message)
            pendingPairConfig = PendingLoginPair(config, generation)
            true
        }
        onDispose {
            connection.cancel()
            loginJob?.cancel()
            if (paired || pendingPairConfig != null) AppRuntime.resolvePairRequest("cancelled")
            AppRuntime.onPairConfig = null
            AppRuntime.onPairRequest = null
        }
    }

    LaunchedEffect(pendingPairConfig) {
        val pending = pendingPairConfig ?: return@LaunchedEffect
        if (!attempts.isCurrent(pending.generation)) return@LaunchedEffect
        val config = pending.config
        val address = parseServerAddress(config.host, config.scheme, config.port, config.path) ?: run {
            error = "配对地址或端口无效"
            fieldErrors = mapOf(LoginField.Host to error.orEmpty())
            connection.error(pending.generation)
            AppRuntime.resolvePairRequest("cancelled")
            pendingPairConfig = null
            return@LaunchedEffect
        }
        fillAddress(address)
        user = config.user
        pass = config.pass
        paired = true
        pairingAlias = config.alias.ifBlank { config.host }
        pairingNote = config.note
        pendingLoginBase = null
        pairNotice = LoginConnectionState.PairPending.message
        pairArrivalTick++
        // Keep pending until login succeeds (confirmed), or this screen closes (cancelled).
        pendingPairConfig = null
        // LoginCard restores the form focus after the QR window has closed.
    }

    // 从主页切服务器进来时，遥控器返回应该回到主页；首次登录（无 session）才允许系统退出
    BackHandler(enabled = AppRuntime.session != null || loading || probing || paired) { leaveLogin() }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "登录"
        prefs?.credentialStorageError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (!loginErrorDialog.isNullOrBlank()) AlertDialog(
        onDismissRequest = { loginErrorDialog = null },
        title = { Text("登录失败") },
        text = { Text(loginErrorDialog.orEmpty()) },
        confirmButton = { LoginTextButton(onClick = { loginErrorDialog = null }) { Text("确定") } },
    )
    if (helpOpen) AlertDialog(
        onDismissRequest = { helpOpen = false },
        title = { Text("连接与同步") },
        text = { Text("手机相机或浏览器扫描电视二维码后，在网页表单中填写服务器和账号并同步即可。也可以展开手动输入，或扫描另一台 Lumina 生成的配置码。") },
        confirmButton = { LoginTextButton(onClick = { helpOpen = false }) { Text("知道了") } },
    )
    if (!scanError.isNullOrBlank()) AlertDialog(
        onDismissRequest = { scanError = null },
        title = { Text("二维码扫描") },
        text = { Text(scanError.orEmpty()) },
        confirmButton = { LoginTextButton(onClick = { scanError = null }) { Text("关闭") } },
    )

    BoxWithConstraints(Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Canvas)) {
        val wide = TvTokenValues.loginColumnsFit(maxWidth.value, maxHeight.value, androidx.compose.ui.platform.LocalDensity.current.fontScale)
        val formWidth = loginFormWidth(maxWidth.value).dp
        PosterBackdrop()

        val savedServers = remember { AppRuntime.prefs?.savedServerList().orEmpty() }
        fun fillFromSaved(server: SavedServer) {
            if (!connection.acceptsEntry || loading || probing || pendingPairConfig != null) return
            val address = parseServerAddress(server.url) ?: return
            fillAddress(address)
            user = server.userName
            pass = ""
            if (paired) AppRuntime.resolvePairRequest("cancelled")
            paired = false
            pairingAlias = null; pairingNote = null; pendingLoginBase = null
            fieldErrors = emptyMap()
            connection.editEntry()
            pairNotice = "已填入 ${server.name}，可直接登录或补充密码"
            error = null
            runCatching { passFocusRequester.requestFocus() }
        }
        val maxCardHeight = maxHeight - 40.dp
        val goHome: (() -> Unit)? = if (AppRuntime.session != null) ({ leaveLogin() }) else null

        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = TvTokens.safeInset(maxWidth), vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoginRail(onHome = goHome, onHelp = { helpOpen = true })
                Spacer(Modifier.width(30.dp))
                BrandHero(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    savedServers = savedServers,
                    onPickServer = { fillFromSaved(it) },
                )
                Spacer(Modifier.width(30.dp))
                Box(Modifier.width(formWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    LoginCard(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                            .heightIn(max = maxCardHeight).verticalScroll(rememberScrollState()),
                        host = host, onHost = { host = it; fieldErrors = fieldErrors - LoginField.Host },
                        port = port, onPort = { port = it.filter(Char::isDigit).take(5); fieldErrors = fieldErrors - LoginField.Port },
                        user = user, onUser = { user = it; fieldErrors = fieldErrors - LoginField.User },
                        pass = pass, onPass = { pass = it; fieldErrors = fieldErrors - LoginField.User - LoginField.Password },
                        scheme = scheme, onScheme = { scheme = it },
                        busy = loading || probing, probing = probing, error = error,
                        fieldErrors = fieldErrors, connectionState = connection.state,
                        pairingUrl = AppRuntime.pairingUrl,
                        pairArrivalTick = pairArrivalTick,
                        onLogin = { submit() }, onInvalid = { connection.invalidInput() }, onScan = { launchScan() }, onHelp = { helpOpen = true },
                        pairNotice = pairNotice, onNoticeDismiss = { pairNotice = null },
                        loginFocusRequester = loginFocusRequester,
                        onRefreshQr = { scope.launch { AppRuntime.refreshPairingUrl(context, force = true) } },
                        passFocus = passFocusRequester,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TvTokens.safeInset(maxWidth), vertical = 18.dp)) {
                BrandHero(Modifier.fillMaxWidth(), savedServers, { fillFromSaved(it) })
                Spacer(Modifier.height(16.dp))
                LoginCard(
                    modifier = Modifier.fillMaxWidth(),
                    host = host, onHost = { host = it; fieldErrors = fieldErrors - LoginField.Host },
                    port = port, onPort = { port = it.filter(Char::isDigit).take(5); fieldErrors = fieldErrors - LoginField.Port },
                    user = user, onUser = { user = it; fieldErrors = fieldErrors - LoginField.User },
                    pass = pass, onPass = { pass = it; fieldErrors = fieldErrors - LoginField.User - LoginField.Password },
                    scheme = scheme, onScheme = { scheme = it },
                    busy = loading || probing, probing = probing, error = error,
                        fieldErrors = fieldErrors, connectionState = connection.state,
                    pairingUrl = AppRuntime.pairingUrl,
                    pairArrivalTick = pairArrivalTick,
                    onLogin = { submit() }, onInvalid = { connection.invalidInput() }, onScan = { launchScan() }, onHelp = { helpOpen = true },
                    pairNotice = pairNotice, onNoticeDismiss = { pairNotice = null },
                    loginFocusRequester = loginFocusRequester,
                    onRefreshQr = { scope.launch { AppRuntime.refreshPairingUrl(context, force = true) } },
                    passFocus = passFocusRequester,
                )

            }
        }
    }
    if (unsupportedScan) AlertDialog(
        onDismissRequest = { unsupportedScan = false },
        title = { Text("无法读取此二维码") },
        text = { Text("请使用 Lumina 登录配置码。其他设备同步请点击卡片下方的“扫描配置二维码”。") },
        confirmButton = { LoginTextButton(onClick = { unsupportedScan = false }) { Text("关闭") } },
    )
    if (successDialogServer != null) AlertDialog(
        onDismissRequest = {},
        title = { Text("已连接 ${successDialogServer}") },
        text = { Text("正在进入…") },
        confirmButton = {},
    )
}

/** 背景：有历史服务器时用它的海报拼一面墙，首登时退回深色渐层。 */
@Composable
private fun PosterBackdrop() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val urls = remember {
        val home = LibraryCache.home
        (home?.let { it.resume + it.movies + it.series + it.latest } ?: emptyList())
            .mapNotNull { AppRuntime.client?.imageUrl(it, 360) }
            .distinct()
            .take(6)
    }
    if (urls.isEmpty() || !foreground) {
        Box(Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Canvas).clearAndSetSemantics { }) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawRect(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                    surfaceColor(SurfaceLevel.Canvas), Accent.copy(alpha = 0.12f), surfaceColor(SurfaceLevel.Canvas))))
            }
        }
        return
    }
    Row(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        urls.forEach { url ->
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.32f),
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().luminaScrim(BackdropShade.Login))
}

/** 左侧极简导航条：Logo + 主页 / 帮助 + 版本号，仿 Flux 的悬浮胶囊。 */
@Composable
private fun LoginRail(onHome: (() -> Unit)?, onHelp: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.width(78.dp).fillMaxHeight()

            .clip(shape)
            .luminaSurface(SurfaceLevel.Floating)
            .luminaBoundary(shape)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                .luminaStatus(Accent),
            contentAlignment = Alignment.Center,
        ) { Text("L", color = Color.White, fontSize = TypeScale.sp23, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
        if (onHome != null) RailItem(Icons.Filled.Home, "主页", onHome)
        Spacer(Modifier.height(6.dp))
        RailItem(Icons.Filled.Info, "帮助", onHelp)
        Spacer(Modifier.weight(1f))
        Text("v${com.lumina.tv.BuildConfig.VERSION_NAME}", color = TextSub.copy(alpha = 0.45f), fontSize = TypeScale.sp9)
    }
}

@Composable
private fun RailItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.size(58.dp),
        corner = 18.dp,
        scaleTo = TvTokenValues.FOCUS_SCALE,
        onClick = onClick,
    ) { focused ->
        Column(
            Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(18.dp), tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (focused) Accent else TextSub, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, color = if (focused) Accent else TextSub, fontSize = TypeScale.sp9)
        }
    }
}

/** 品牌区：LUMINA·EMBY / Lumina / 三个特性 / 最近连接。 */
@Composable
private fun BrandHero(
    modifier: Modifier,
    savedServers: List<SavedServer>,
    onPickServer: (SavedServer) -> Unit,
    expand: Boolean = false,
) {
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.Center) {
        Text("LUMINA · EMBY", color = Accent, fontSize = TypeScale.sp11, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Lumina", color = TextMain, fontSize = TypeScale.sp44, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("连接你的 Emby 服务器", color = TextSub, fontSize = TypeScale.sp15)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeaturePill("扫码 30 秒配好")
            FeaturePill("自动探测端口")
        }
        if (savedServers.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text("最近连接", color = TextSub.copy(alpha = 0.9f), fontSize = TypeScale.sp11)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                savedServers.sortedByDescending { it.lastUsed }.take(2).forEach { server ->
                    FocusSurface(
                        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().height(52.dp),
                        corner = 15.dp,
                        scaleTo = TvTokenValues.FOCUS_SCALE,
                        onClick = { onPickServer(server) },
                    ) { focused ->
                        Row(
                            Modifier.fillMaxSize()
                                .luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(15.dp), tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .luminaSurface(SurfaceLevel.Content, tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral),
                                contentAlignment = Alignment.Center,
                            ) { Text("L", color = if (focused) Accent else TextSub, fontSize = TypeScale.sp13, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(server.name, color = TextMain, fontSize = TypeScale.sp13, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    server.url.substringAfter("://").trimEnd('/'),
                                    color = TextSub, fontSize = TypeScale.sp10, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text("填入", color = if (focused) Accent else TextSub, fontSize = TypeScale.sp11)
                        }
                    }
                }
            }
        }
        if (expand) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LoginCard(
    modifier: Modifier,
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    scheme: String, onScheme: (String) -> Unit,
    busy: Boolean, probing: Boolean, error: String?,
    fieldErrors: Map<LoginField, String>, connectionState: LoginConnectionState,
    pairingUrl: String,
    pairArrivalTick: Int,
    onLogin: () -> Unit, onInvalid: () -> Unit, onScan: () -> Unit, onHelp: () -> Unit,
    pairNotice: String?, onNoticeDismiss: () -> Unit,
    loginFocusRequester: FocusRequester,
    onRefreshQr: () -> Unit,
    passFocus: FocusRequester,
) {
    var qrOpen by remember { mutableStateOf(false) }
    var focusManualHost by remember { mutableStateOf(false) }
    var hostBad by remember { mutableStateOf(false) }
    var userBad by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val userFocusRequester = remember { FocusRequester() }
    val hostFocusRequester = remember { FocusRequester() }
    val portFocusRequester = remember { FocusRequester() }
    LaunchedEffect(error) {
        if (fieldErrors.isNotEmpty()) {
            withFrameNanos { }
            when (fieldErrors.keys.minBy { it.ordinal }) {
                LoginField.Host -> hostFocusRequester
                LoginField.Port -> portFocusRequester
                LoginField.User -> userFocusRequester
                LoginField.Password -> passFocus
            }.requestFocus()
        }
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shape = RoundedCornerShape(TvTokens.GroupCorner)
    LaunchedEffect(pairArrivalTick) {
        if (pairArrivalTick > 0) qrOpen = false
        else { androidx.compose.runtime.withFrameNanos { }; runCatching { hostFocusRequester.requestFocus() } }
    }
    // Request focus after the Dialog has left composition, including its window focus restoration.
    LaunchedEffect(pairArrivalTick, qrOpen) {
        if (pairArrivalTick > 0 && !qrOpen) { withFrameNanos { }; loginFocusRequester.requestFocus() }
    }
    LaunchedEffect(focusManualHost, qrOpen) {
        if (focusManualHost && !qrOpen) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { hostFocusRequester.requestFocus() }
            focusManualHost = false
        }
    }
    fun validateAndSubmit() {
        if (busy) return
        val missing = loginRequiredField(host, user)
        hostBad = host.isBlank()
        userBad = user.isBlank()
        localError = when (missing) {
            LoginRequiredField.Host -> "请填写服务器地址"
            LoginRequiredField.User -> "请填写用户名"
            else -> null
        }
        if (missing != null) onInvalid()
        when (missing) {
            LoginRequiredField.Host -> hostFocusRequester.requestFocus()
            LoginRequiredField.User -> userFocusRequester.requestFocus()
            else -> onLogin()
        }
    }
    Column(
        modifier.clip(shape)
            .luminaSurface(SurfaceLevel.Content)
            .luminaBoundary(shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 标题 + 扫码
        LuminaSurface(SurfaceLevel.Content) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("连接服务器", color = TextMain, fontSize = TypeScale.sp22, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("输入账号，或扫码从手机同步配置", color = TextSub, fontSize = TypeScale.sp11)
            }
            FocusSurface(Modifier.height(38.dp), corner = 19.dp, scaleTo = TvTokenValues.FOCUS_SCALE, onClick = { qrOpen = true }) { focused ->
                Row(
                    Modifier.height(38.dp)
                        .luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(19.dp), tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = if (focused) Accent else TextSub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫码登录", color = TextMain, fontSize = TypeScale.sp11)
                }
            }
        }

        }
        pairNotice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).luminaSurface(tone = SurfaceTone.Success)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, color = TextMain, fontSize = TypeScale.sp11, modifier = Modifier.weight(1f), maxLines = 2)
                LoginTextButton(onClick = onNoticeDismiss) { Text("知道了", color = Accent, fontSize = TypeScale.sp10) }
            }
        }

        if (qrOpen) Dialog(onDismissRequest = { qrOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        AppRuntime.refreshPairingUrl(context)
                        delay(1_000L)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().luminaScrim(BackdropShade.Modal), contentAlignment = Alignment.Center) {
                val panelShape = RoundedCornerShape(20.dp)
                val qrSize = minOf(220.dp, (maxWidth - 88.dp).coerceAtLeast(1.dp))
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(20.dp)
                        .heightIn(max = (maxHeight - 40.dp).coerceAtLeast(1.dp))
                        .clip(panelShape)
                        .luminaSurface(SurfaceLevel.Overlay).luminaBoundary(panelShape)
                        .verticalScroll(rememberScrollState()).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (pairingUrl.isNotBlank()) {
                        QrCode(pairingUrl, size = qrSize)
                        Text("手机与电视连接同一局域网，扫码后填写并同步。", color = TextMain, fontSize = TypeScale.sp12)
                        Text(pairingUrl, color = Accent, fontSize = TypeScale.sp12)
                        Text("浏览器拦截 HTTP 时，可关闭此窗口，在电视上手动输入服务器地址。", color = TextSub, fontSize = TypeScale.sp11)
                        AppRuntime.pairingRefreshNotice?.let { Text(it, color = Accent, fontSize = TypeScale.sp12) }
                        LoginTextButton(onClick = {
                            clipboard.setText(AnnotatedString(pairingUrl))
                            android.widget.Toast.makeText(context, "配对地址已复制", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("复制地址", color = Accent) }
                    } else {
                        Text(AppRuntime.pairingError ?: "正在获取局域网配对地址…", color = TextSub, fontSize = TypeScale.sp12)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoginTextButton(onClick = onRefreshQr) {
                            Text(if (pairingUrl.isNotBlank()) "刷新二维码" else "重试", color = Accent)
                        }
                        LoginTextButton(onClick = {
                            qrOpen = false
                            focusManualHost = true
                        }) { Text("手动输入", color = TextMain) }
                    }
                }
            }
        }

        // 协议
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("协议", color = TextSub, fontSize = TypeScale.sp11)
            listOf("http", "https").forEach { candidate ->
                FocusSurface(Modifier.width(74.dp).height(34.dp), corner = 17.dp, scaleTo = TvTokenValues.FOCUS_SCALE, onClick = { onScheme(candidate) }) { focused ->
                    Box(
                        Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Content, RoundedCornerShape(17.dp), selected = scheme == candidate),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(candidate, color = if (scheme == candidate) Color.White else TextSub, fontSize = TypeScale.sp12, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        GlassField(host, { localError = null; hostBad = false; onHost(it) }, "服务器地址", Modifier.fillMaxWidth(), ImeAction.Next,
            focusRequester = hostFocusRequester, required = true, busy = busy,
            supportingError = if (hostBad) "请填写服务器地址" else fieldErrors[LoginField.Host], onNext = { portFocusRequester.requestFocus() })
        GlassField(port, onPort, "端口 / 路径在服务器地址中填写", Modifier.fillMaxWidth(), ImeAction.Next,
            keyboardType = KeyboardType.Number, focusRequester = portFocusRequester, busy = busy,
            supportingError = fieldErrors[LoginField.Port], onNext = { userFocusRequester.requestFocus() })
        GlassField(user, { localError = null; userBad = false; onUser(it) }, "用户名", Modifier.fillMaxWidth(), ImeAction.Next,
            focusRequester = userFocusRequester, required = true, busy = busy,
            supportingError = if (userBad) "请填写用户名" else fieldErrors[LoginField.User], onNext = { passFocus.requestFocus() })
        GlassField(pass, { localError = null; onPass(it) }, "密码（可留空）", Modifier.fillMaxWidth(), ImeAction.Done,
            password = true, busy = busy, onDone = { validateAndSubmit() }, focusRequester = passFocus,
            supportingError = fieldErrors[LoginField.Password])

        Spacer(Modifier.height(2.dp))
        FocusSurface(
            Modifier.fillMaxWidth().height(50.dp).focusRequester(loginFocusRequester).semantics {
                contentDescription = "连接并登录"
                stateDescription = connectionState.message
            },
            corner = 25.dp,
            scaleTo = TvTokenValues.FOCUS_SCALE,
            onClick = { validateAndSubmit() },
        ) { focused ->
            Box(
                Modifier.fillMaxSize().luminaStatus(Accent, RoundedCornerShape(25.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF06101F), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(connectionState.message, color = Color(0xFF06101F), fontSize = TypeScale.sp13)
                    }
                } else {
                    Text("连接并登录", color = Color(0xFF06101F), fontSize = TypeScale.sp14, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.CenterStart) {
            val shown = localError ?: error
            if (shown != null) {
                Text(shown, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = TypeScale.sp11)
            } else {
                Text(connectionState.message, color = TextSub, fontSize = TypeScale.sp11)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusSurface(Modifier.weight(1f).height(34.dp), corner = 17.dp, onClick = onScan) { focused ->
                Row(
                    Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(17.dp), tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = if (focused) Accent else TextSub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫描配置二维码", color = TextMain, fontSize = TypeScale.sp11)
                }
            }
            FocusSurface(Modifier.width(96.dp).height(34.dp), corner = 17.dp, onClick = onHelp) { focused ->
                Box(
                    Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(17.dp), tone = if (focused) SurfaceTone.Raised else SurfaceTone.Neutral),
                    contentAlignment = Alignment.Center,
                ) { Text("帮助", color = if (focused) Accent else TextSub, fontSize = TypeScale.sp11) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("v${com.lumina.tv.BuildConfig.VERSION_NAME} · Lumina for Emby", color = TextSub.copy(alpha = 0.55f), fontSize = TypeScale.sp9)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(5.dp).luminaStatus(if (pairingUrl.isNotBlank()) Mint else TextSub, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                if (pairingUrl.isNotBlank()) "局域网配对就绪" else "局域网未就绪",
                color = TextSub.copy(alpha = 0.55f), fontSize = TypeScale.sp9,
            )
        }
    }
}

@Composable
private fun GlassField(
    value: String, onValue: (String) -> Unit, label: String, modifier: Modifier, action: ImeAction,
    keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false,
    onDone: (() -> Unit)? = null, focusRequester: FocusRequester? = null,
    supportingError: String? = null, required: Boolean = false, busy: Boolean = false, onNext: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true,
        label = { Text(label, color = TextSub, fontSize = TypeScale.sp13) },
        isError = supportingError != null,
        supportingText = supportingError?.let { message -> { Text(message) } },
        readOnly = busy,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = action),
        keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }, onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = TvTokens.Boundary,
            cursorColor = Accent,
            focusedContainerColor = surfaceColor(SurfaceLevel.Content, focused = true), unfocusedContainerColor = surfaceColor(SurfaceLevel.Content),
        ),
        modifier = modifier.heightIn(min = 64.dp)
            .semantics {
                contentDescription = label
                stateDescription = loginFieldState(required, value.isNotEmpty(), busy, supportingError)
            }
            .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
            .tvFocusBoundary(RoundedCornerShape(TvTokens.ContentCorner))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun FeaturePill(text: String) {
    Row(
        Modifier.clip(CircleShape).luminaSurface(SurfaceLevel.Content).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).luminaStatus(Accent, shape = CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = TextSub, fontSize = TypeScale.sp9)
    }
}

@Composable
private fun LoginTextButton(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    FocusSurface(Modifier.heightIn(min = 44.dp), corner = TvTokens.ContentCorner,
        scaleTo = TvTokenValues.FOCUS_SCALE, onClick = onClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
