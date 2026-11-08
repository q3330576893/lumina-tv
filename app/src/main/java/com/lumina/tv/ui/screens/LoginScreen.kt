package com.lumina.tv.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.pair.PairConfig
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.QrCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val Ink = Color(0xFFF7F5FF)
private val InkSub = Color(0xFFB7B0C9)
private val Violet = Color(0xFFB69CFF)
private val VioletBright = Color(0xFFD6C4FF)
private val Glass = Color(0x8A10141D)
private val GlassLine = Color(0x47FFFFFF)

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onCancel: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = AppRuntime.prefs
    var scheme by remember { mutableStateOf("http") }
    var host by remember {
        mutableStateOf(prefs?.baseUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it.removePrefix("https://").removePrefix("http://").substringBefore(':') }.orEmpty())
    }
    var path by remember { mutableStateOf(prefs?.baseUrl?.let { runCatching { Uri.parse(it).path.orEmpty().trim('/') }.getOrDefault("") }.orEmpty()) }
    var port by remember {
        mutableStateOf(prefs?.baseUrl?.let { runCatching { Uri.parse(it).port }.getOrDefault(-1) }
            ?.takeIf { it > 0 }?.toString() ?: "8096")
    }
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
    var pendingPairConfig by remember { mutableStateOf<PairConfig?>(null) }
    var pairNotice by remember { mutableStateOf<String?>(null) }
    val loginFocusRequester = remember { FocusRequester() }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val parsed = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val raw = parsed?.contents
        if (raw.isNullOrBlank()) {
            unsupportedScan = true
            return@rememberLauncherForActivityResult
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val server = uri?.getQueryParameter("server") ?: uri?.getQueryParameter("url") ?: json?.optString("server")?.takeIf { it.isNotBlank() }
        val scanUser = uri?.getQueryParameter("user") ?: uri?.getQueryParameter("username") ?: json?.optString("username")?.ifBlank { json.optString("user") }
        val scanPass = uri?.getQueryParameter("pass") ?: uri?.getQueryParameter("password") ?: json?.optString("password")?.ifBlank { json.optString("pass") }
        val direct = runCatching { Uri.parse(raw) }.getOrNull()
        when {
            !server.isNullOrBlank() -> {
                val parsedServer = runCatching { Uri.parse(if (server.startsWith("http")) server else "http://$server") }.getOrNull()
                scheme = parsedServer?.scheme?.takeIf { it == "https" } ?: "http"
                host = parsedServer?.host.orEmpty()
                port = parsedServer?.port?.takeIf { it > 0 }?.toString() ?: if (scheme == "https") "8920" else "8096"
                user = scanUser.orEmpty()
                pass = scanPass.orEmpty()
                scanError = null
            }
            direct?.host != null -> {
                scheme = direct.scheme ?: "http"
                host = direct.host.orEmpty()
                port = direct.port.takeIf { it > 0 }?.toString() ?: if (scheme == "https") "8920" else "8096"
                user = direct.getQueryParameter("user") ?: user
                pass = direct.getQueryParameter("pass") ?: pass
                scanError = null
            }
            json != null -> scanError = "二维码中缺少服务器地址或账号字段。"
            else -> scanError = "二维码格式无法识别。请扫描包含服务器地址、用户名和密码的 Lumina 配置码。"
        }
        val resolvedUser = scanUser ?: direct?.getQueryParameter("user") ?: direct?.getQueryParameter("username")
        val resolvedPass = scanPass ?: direct?.getQueryParameter("pass") ?: direct?.getQueryParameter("password")
        val resolvedServer = server?.let { if (it.startsWith("http")) it else "http://$it" } ?: direct?.takeIf { it.host != null }?.let {
            "${it.scheme ?: "http"}://${it.host}:${it.port.takeIf { p -> p > 0 } ?: if (it.scheme == "https") 8920 else 8096}"
        }
        if (scanError == null && !resolvedServer.isNullOrBlank() && !resolvedUser.isNullOrBlank() && !resolvedPass.isNullOrBlank()) {
            user = resolvedUser
            pass = resolvedPass
            val base = Uri.parse(if (resolvedServer.startsWith("http")) resolvedServer else "http://$resolvedServer")
            scheme = base.scheme?.takeIf { it == "https" } ?: "http"
            host = base.host.orEmpty()
            port = base.port.takeIf { it > 0 }?.toString() ?: if (scheme == "https") "8920" else "8096"
            pendingLoginBase = "${scheme}://${host}:${port}"
            scanLoginRequested = true
        } else if (scanError == null) {
            scanError = "二维码缺少完整的服务器地址、用户名或密码。"
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

    fun loginAt(base: String, name: String, password: String) {
        scope.launch {
            loading = true
            error = null
            try {
                val client = EmbyClient(baseUrl = base, deviceId = prefs?.deviceId ?: "lumina-tv")
                val newSession = client.login(base, name, password)
                if (AppRuntime.session?.baseUrl?.trimEnd('/') != newSession.baseUrl.trimEnd('/')) {
                    com.lumina.tv.data.LibraryCache.clear()
                }
                val sessionToSave = newSession.copy(
                    serverName = if (paired) pairingAlias?.ifBlank { newSession.serverName } ?: newSession.serverName else newSession.serverName,
                )
                AppRuntime.applySession(sessionToSave)
                AppRuntime.prefs?.saveCurrentServer()
                if (paired) prefs?.serverNote = pairingNote?.ifBlank { null }
                AppRuntime.updatePairingStatus("登录成功，电视已连接")
                if (paired) AppRuntime.resolvePairRequest("confirmed")
                if (paired) {
                    successDialogServer = sessionToSave.serverName
                    kotlinx.coroutines.delay(1200)
                }
                onLoggedIn()
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                loginErrorDialog = when {
                    message.contains("401") || message.contains("账号或密码") || message.contains("Unauthorized", ignoreCase = true) -> "账号或密码错误，请核对后重试。"
                    message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) -> "连接超时，请检查服务器地址、端口和网络。"
                    message.contains("Unable to resolve", ignoreCase = true) || message.contains("Failed to connect", ignoreCase = true) || message.contains("Connection refused", ignoreCase = true) || message.contains("CLEARTEXT", ignoreCase = true) -> "服务器连接失败，请检查服务器地址、端口及 HTTP/HTTPS 设置。"
                    message.startsWith("HTTP ") -> "服务器接口报错：$message"
                    else -> "登录失败：${message.ifBlank { "服务器返回异常，请检查网络和账号信息。" }}"
                }
                error = loginErrorDialog
                AppRuntime.updatePairingStatus(loginErrorDialog.orEmpty())
                if (AppRuntime.session != null) {
                    android.widget.Toast.makeText(context, loginErrorDialog, android.widget.Toast.LENGTH_LONG).show()
                    kotlinx.coroutines.delay(1200)
                    onCancel()
                }
            } finally { loading = false; probing = false }
        }
    }

    fun submit(name: String = user, password: String = pass) {
        if (loading || probing) return
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleanHost.isBlank() || name.isBlank()) { error = "请填写服务器地址和用户名"; return }
        val selectedPort = port.trim().ifBlank { if (scheme == "https") "8920" else "8096" }
        val cleanPath = path.trim().trim('/')
        val primary = pendingLoginBase ?: "$scheme://$cleanHost:$selectedPort${if (cleanPath.isNotBlank()) "/$cleanPath" else ""}"
        val onlyPrimary = pendingLoginBase != null
        pendingLoginBase = null
        scope.launch {
            probing = true; error = null
            val candidates = buildList {
                add(primary)
                if (!onlyPrimary && scheme == "http") { add("http://$cleanHost:8096"); add("https://$cleanHost:8920") }
                else if (!onlyPrimary) { add("https://$cleanHost:8920"); add("http://$cleanHost:8096") }
                if (!onlyPrimary) add("http://$cleanHost:80")

            }.distinct()
            val deadline = System.currentTimeMillis() + 26_000L
            var found: String? = null
            for (candidate in candidates) {
                if (System.currentTimeMillis() >= deadline) break
                try {
                    EmbyClient(candidate, deviceId = prefs?.deviceId ?: "lumina-tv").testConnection(candidate)
                    found = candidate
                    break
                } catch (_: Exception) { }
            }
            if (found == null) {
                probing = false
                val message = if (System.currentTimeMillis() >= deadline) "连接超时：请检查服务器响应和网络。" else "服务器连接失败：未找到可用的 Emby 服务，请检查地址、端口和网络。"
                error = message
                loginErrorDialog = message
            } else {
                if (found != primary) error = "已自动切换至 $found"
                loginAt(found, name, password)
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
            if (loading || probing) return@config false
            val u = runCatching { Uri.parse(if (server.startsWith("http")) server else "http://$server") }.getOrNull()
            scheme = if (u?.scheme == "https") "https" else "http"
            host = u?.host ?: server.substringBefore(':')
            if (u?.host == null && server.contains(':')) {
                server.substringAfterLast(':').substringBefore('/').toIntOrNull()?.let { port = it.toString() }
            }
            port = u?.port?.takeIf { it > 0 }?.toString() ?: if (scheme == "https") "8920" else "8096"
            user = pairedUser; pass = pairedPass; paired = true
            pendingLoginBase = "${scheme}://${host}:${port}"
            AppRuntime.updatePairingStatus("电视正在连接服务器…")
            submit(pairedUser, pairedPass)

            true
        }
        AppRuntime.onPairRequest = { config -> pendingPairConfig = config }
        onDispose {
            AppRuntime.onPairConfig = null
            AppRuntime.onPairRequest = null
        }
    }

    LaunchedEffect(pendingPairConfig) {
        val config = pendingPairConfig ?: return@LaunchedEffect
        scheme = config.scheme
        host = config.host
        port = config.port.toString()
        path = config.path.trimStart('/')
        user = config.user
        pass = config.pass
        paired = true
        pairingAlias = config.alias.ifBlank { config.host }
        pairingNote = config.note
        pendingLoginBase = null
        pairNotice = "已接收手机推送的配置（${config.alias.ifBlank { config.host }}），确认后点『连接并登录』"
        AppRuntime.resolvePairRequest("received")
        pendingPairConfig = null
        loginFocusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "登录"
        if (AppRuntime.pairing == null) AppRuntime.startPairing(context)
    }

    if (!loginErrorDialog.isNullOrBlank()) AlertDialog(
        onDismissRequest = { loginErrorDialog = null },
        title = { Text("登录失败") },
        text = { Text(loginErrorDialog.orEmpty()) },
        confirmButton = { TextButton(onClick = { loginErrorDialog = null }) { Text("确定") } },
    )
    if (helpOpen) AlertDialog(
        onDismissRequest = { helpOpen = false },
        title = { Text("连接与同步") },
        text = { Text("手机相机或浏览器扫描电视二维码后，在网页表单中填写服务器和账号并同步即可。也可以展开手动输入，或扫描另一台 Lumina 生成的配置码。") },
        confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("知道了") } },
    )
    if (!scanError.isNullOrBlank()) AlertDialog(
        onDismissRequest = { scanError = null },
        title = { Text("二维码扫描") },
        text = { Text(scanError.orEmpty()) },
        confirmButton = { TextButton(onClick = { scanError = null }) { Text("关闭") } },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090810))) {
        val wide = maxWidth >= 720.dp
        Box(Modifier.fillMaxSize().background(Color(0xFF090810)))
        Box(Modifier.align(Alignment.TopStart).offset(x = (-110).dp, y = (-170).dp).size(520.dp)
            .background(Brush.radialGradient(listOf(Color(0x143DB8B0), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 140.dp, y = 190.dp).size(520.dp)
            .background(Brush.radialGradient(listOf(Color(0x103B79D8), Color.Transparent))))
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                LoginBrand(Modifier.weight(0.58f).fillMaxHeight())
                Spacer(Modifier.width(28.dp))
                LoginCard(
                    Modifier.weight(1.42f).widthIn(min = 620.dp, max = 900.dp)
                        .fillMaxHeight().verticalScroll(rememberScrollState()),
                    host, { host = it }, port, { port = it.filter(Char::isDigit).take(5) }, path, { path = it }, user, { user = it }, pass, { pass = it },
                    scheme, { scheme = it }, loading || probing, probing, error, AppRuntime.pairingUrl,
                    { submit() }, { launchScan() }, { helpOpen = true }, pairNotice, { pairNotice = null }, loginFocusRequester,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
                LoginCard(
                    Modifier.fillMaxWidth(),
                    host, { host = it }, port, { port = it.filter(Char::isDigit).take(5) }, path, { path = it }, user, { user = it }, pass, { pass = it },
                    scheme, { scheme = it }, loading || probing, probing, error, AppRuntime.pairingUrl,
                    { submit() }, { launchScan() }, { helpOpen = true }, pairNotice, { pairNotice = null }, loginFocusRequester,
                )
                Spacer(Modifier.height(24.dp))
                LoginBrand(Modifier.fillMaxWidth())
            }
        }
    }
    if (unsupportedScan) AlertDialog(
        onDismissRequest = { unsupportedScan = false },
        title = { Text("无法读取此二维码") },
        text = { Text("请使用 Lumina 登录配置码。其他设备同步请点击卡片下方的“从其他设备同步”。") },
        confirmButton = { TextButton(onClick = { unsupportedScan = false }) { Text("关闭") } },
    )
    if (successDialogServer != null) AlertDialog(
        onDismissRequest = {},
        title = { Text("已连接 ${successDialogServer}") },
        text = { Text("正在进入…") },
        confirmButton = {},
    )
}

@Composable
private fun LoginCard(
    modifier: Modifier, host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit,
    path: String, onPath: (String) -> Unit,
    user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit,
    scheme: String, onScheme: (String) -> Unit, busy: Boolean, probing: Boolean, error: String?,
    pairingUrl: String, onLogin: () -> Unit, onScan: () -> Unit, onHelp: () -> Unit,
    pairNotice: String?, onNoticeDismiss: () -> Unit, loginFocusRequester: FocusRequester,
) {
    var qrOpen by remember { mutableStateOf(false) }
    var cardFocused by remember { mutableStateOf(false) }
    val hostFocusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier.clip(shape).background(Glass)
            .border(1.dp, Brush.verticalGradient(listOf(if (cardFocused) Color(0x9994FFF0) else Color(0x55FFFFFF), Color(0x11FFFFFF))), shape)
            .onFocusChanged { cardFocused = it.hasFocus }
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { hostFocusRequester.requestFocus() }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("连接媒体服务器", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            FocusSurface(Modifier.height(32.dp), corner = 16.dp, onClick = { qrOpen = !qrOpen }) { focused ->
                Row(Modifier.height(32.dp).background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = if (focused) VioletBright else InkSub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("扫码配对", color = Ink, fontSize = 11.sp)
                }
            }
        }
        pairNotice?.let { notice ->
            Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x222BD9C0)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = onNoticeDismiss) { Text("知道了", color = VioletBright, fontSize = 10.sp) }
            }
        }
        if (qrOpen) {
            if (pairingUrl.isNotBlank()) {
                Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                    QrCode(pairingUrl, size = 200.dp)
                }
                Text("手机扫码 → 填写 → 点『同步到电视』", color = InkSub, fontSize = 10.sp)
                Text(pairingUrl, color = VioletBright, fontSize = 10.sp, maxLines = 2, modifier = Modifier.fillMaxWidth().clickable {
                    clipboard.setText(AnnotatedString(pairingUrl))
                    android.widget.Toast.makeText(context, "配对地址已复制", android.widget.Toast.LENGTH_SHORT).show()
                })
            } else Text(AppRuntime.pairingError ?: "未连接局域网，配对服务暂不可用", color = InkSub, fontSize = 11.sp)
            TextButton(onClick = { qrOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("收起", color = VioletBright, fontSize = 11.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("协议", color = InkSub, fontSize = 11.sp)
                listOf("http", "https").forEach { candidate ->
                    FocusSurface(Modifier.width(66.dp).height(36.dp), corner = 13.dp, onClick = { onScheme(candidate) }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (scheme == candidate) Color(0x552B9D9A) else if (focused) Color(0x33FFFFFF) else Color(0x1DFFFFFF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Text(candidate, color = if (scheme == candidate) VioletBright else InkSub, fontSize = 12.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassField(host, onHost, "IP 或域名", Modifier.weight(1f), ImeAction.Next, focusRequester = hostFocusRequester)
                GlassField(port, onPort, "端口", Modifier.width(86.dp), ImeAction.Next, keyboardType = KeyboardType.Number)
            }
            GlassField(path, onPath, "路径（可选）", Modifier.fillMaxWidth(), ImeAction.Next)
            GlassField(user, onUser, "Emby 用户名", Modifier.fillMaxWidth(), ImeAction.Next)
            GlassField(pass, onPass, "密码", Modifier.fillMaxWidth(), ImeAction.Done, password = true, onDone = onLogin)
            FocusSurface(Modifier.fillMaxWidth().height(48.dp).focusRequester(loginFocusRequester), corner = 16.dp, scaleTo = 1.02f, onClick = onLogin) { focused ->
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(if (focused) listOf(Color(0xFF58D9C7), Color(0xFF2694A0)) else listOf(Color(0xFF34B8AE), Color(0xFF207C8F))), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(if (probing) "正在探测服务器…" else "正在登录…", color = Color.White, fontSize = 13.sp) }
                    else Text("连接并登录", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusSurface(Modifier.weight(1f).height(40.dp), corner = 14.dp, onClick = onScan) { focused ->
                Row(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(14.dp)), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = if (focused) VioletBright else InkSub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("扫描配置二维码", color = Ink, fontSize = 11.sp)
                }
            }
            FocusSurface(Modifier.width(104.dp).height(40.dp), corner = 14.dp, onClick = onHelp) { focused ->
                Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("帮助", color = if (focused) VioletBright else InkSub, fontSize = 11.sp) }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF929D), fontSize = 11.sp) }
    }
}

@Composable
private fun LoginBrand(modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF49C8B7), Color(0xFF657DE8)))), contentAlignment = Alignment.Center) {
            Text("L", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        }
        Text("LUMINA · EMBY", color = VioletBright, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("Lumina", color = Ink, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("把 Emby 内容带到客厅屏幕", color = InkSub, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("本地解码 · 支持 TrueHD / DTS", color = InkSub, fontSize = 12.sp, modifier = Modifier.height(22.dp))
            Text("手机扫码配对，无需遥控输入", color = InkSub, fontSize = 12.sp, modifier = Modifier.height(22.dp))
            Text("多服务器一键切换", color = InkSub, fontSize = 12.sp, modifier = Modifier.height(22.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FeaturePill("4K HDR 直连")
            FeaturePill("多内核播放")
            FeaturePill("局域网同步")
        }
        Spacer(Modifier.height(28.dp))
        Text("版本 ${com.lumina.tv.BuildConfig.VERSION_NAME}", color = InkSub, fontSize = 10.sp)
    }
}

@Composable
private fun GlassField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier, action: ImeAction, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false, onDone: (() -> Unit)? = null, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true,
        placeholder = { Text(label, color = InkSub.copy(alpha = 0.78f), fontSize = 13.sp) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = action),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedBorderColor = VioletBright, unfocusedBorderColor = Color(0x30FFFFFF),
            cursorColor = VioletBright, focusedContainerColor = Color(0x2CFFFFFF), unfocusedContainerColor = Color(0x1CFFFFFF),
        ),
        modifier = modifier.height(56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, if (focused) VioletBright else Color.Transparent, RoundedCornerShape(15.dp)),
    )
}

@Composable
private fun FeaturePill(text: String) {
    Row(Modifier.clip(CircleShape).background(Color(0x1DFFFFFF)).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(Violet, CircleShape)); Spacer(Modifier.width(5.dp)); Text(text, color = InkSub, fontSize = 9.sp)
    }
}

@Composable
private fun SideNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tv: Boolean, enabled: Boolean, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.width(if (tv) 58.dp else 46.dp).height(if (tv) 62.dp else 48.dp)
            .clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0x3D9B7DE1) else if (focused) Color(0x33FFFFFF) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) VioletBright else if (selected) Color(0x669F80E8) else Color.Transparent, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) VioletBright else InkSub, modifier = Modifier.size(if (tv) 22.dp else 19.dp))
        if (tv) Text(label, color = if (selected) Ink else InkSub, fontSize = 8.sp)
    }
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
