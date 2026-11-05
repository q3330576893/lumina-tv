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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.QrCode
import kotlinx.coroutines.launch

private val Ink = Color(0xFFF7F5FF)
private val InkSub = Color(0xFFB7B0C9)
private val Violet = Color(0xFFB69CFF)
private val VioletBright = Color(0xFFD6C4FF)
private val Glass = Color(0xB8161420)
private val GlassLine = Color(0x47FFFFFF)

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = AppRuntime.prefs
    var scheme by remember { mutableStateOf("http") }
    var host by remember {
        mutableStateOf(prefs?.baseUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it.removePrefix("https://").removePrefix("http://").substringBefore(':') }.orEmpty())
    }
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
    var helpOpen by remember { mutableStateOf(false) }
    var syncOpen by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var unsupportedScan by remember { mutableStateOf(false) }

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
                AppRuntime.applySession(client.login(base, name, password))
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
            } finally { loading = false; probing = false }
        }
    }

    fun submit(name: String = user, password: String = pass) {
        if (loading || probing) return
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleanHost.isBlank() || name.isBlank()) { error = "请填写服务器地址和用户名"; return }
        val selectedPort = port.trim().ifBlank { if (scheme == "https") "8920" else "8096" }
        val primary = pendingLoginBase ?: "$scheme://$cleanHost:$selectedPort"
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

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "登录"
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
            pendingLoginBase = null
            submit(pairedUser, pairedPass)

            true
        }
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
        text = { Text("手动连接：填写 Emby 服务器 IP 或域名、端口与账号后登录（常用端口 HTTP 8096、HTTPS 8920）。\n\n从其他设备同步：在已登录的 Lumina 设备上打开“从其他设备同步”，扫描本页电视二维码，配置会自动发送到电视。\n\n手机扫码登录：点击登录卡片顶部的扫码按钮，扫描包含服务器、用户名与密码字段的 Lumina 配置二维码。") },
        confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("知道了") } },
    )
    if (syncOpen) AlertDialog(
        onDismissRequest = { syncOpen = false },
        title = { Text("从其他设备同步") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("使用已登录的 Lumina 设备扫描下方二维码，将服务器配置发送到本机。手机与本机需在同一 Wi-Fi。")
                Spacer(Modifier.height(14.dp))
                if (AppRuntime.pairingUrl.isNotBlank()) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                        QrCode(AppRuntime.pairingUrl, size = 190.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("配对码有效期 5 分钟", fontSize = 10.sp)
                } else Text(AppRuntime.pairingError ?: "配对服务暂不可用")
            }
        },
        confirmButton = { TextButton(onClick = { syncOpen = false }) { Text("关闭") } },
    )
    if (!scanError.isNullOrBlank()) AlertDialog(
        onDismissRequest = { scanError = null },
        title = { Text("二维码扫描") },
        text = { Text(scanError.orEmpty()) },
        confirmButton = { TextButton(onClick = { scanError = null }) { Text("关闭") } },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090810))) {
        val isTv = true
        val landscape = true
        val navWidth = 88.dp
        // Atmospheric poster-like artwork built from blurred cinematic color fields; no external images or network fetches.
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF151226), Color(0xFF090810), Color(0xFF1A1024)))))
        Row(Modifier.fillMaxSize().blur(34.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(Color(0x554A386E), Color(0x335D334C), Color(0x44323E70), Color(0x445B452E)).forEachIndexed { index, tone ->
                Box(Modifier.padding(top = if (index % 2 == 0) 0.dp else 54.dp).size(width = 118.dp, height = 248.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(tone, Color(0x180F1422), Color(0x66554275))))
                    .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(18.dp)))
            }
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(navWidth).fillMaxHeight().padding(vertical = 30.dp)
                    .clip(RoundedCornerShape(0.dp, 24.dp, 24.dp, 0.dp))
            .background(Color(0x331B1727)).border(1.dp, Color(0x19FFFFFF), RoundedCornerShape(topStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 0.dp))
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("L", color = VioletBright, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                listOf(Icons.Filled.Home to "主页", Icons.Filled.Settings to "设置").forEach { (icon, label) ->
                    SideNavButton(icon, label, isTv, enabled = true, selected = false) { onNavigate(if (label == "主页") "home" else "settings") }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(0.9f).padding(start = 10.dp)) {
                        Text("LUMINA · EMBY", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("Lumina", color = Ink, fontSize = 50.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
                        Text("连接你的 Emby 服务器", color = InkSub, fontSize = 15.sp)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeaturePill("扫码 30 秒配好"); FeaturePill("自动探测端口"); FeaturePill("账号不落明文")
                        }
                    }
                    Spacer(Modifier.width(26.dp))
                    LoginCard(
                        Modifier.weight(1.1f).widthIn(max = 610.dp).verticalScroll(rememberScrollState()),
                        host, { host = it }, port, { port = it.filter(Char::isDigit).take(5) }, user, { user = it }, pass, { pass = it },
                        scheme, { scheme = it }, loading || probing, probing, error, { submit() }, { launchScan() }, { syncOpen = true }, { helpOpen = true },
                    )
                }
            }
        }
        if (landscape && AppRuntime.pairingUrl.isNotBlank()) {
            FocusSurface(Modifier.align(Alignment.BottomStart).padding(start = navWidth + 38.dp, bottom = 18.dp).width(210.dp).height(52.dp), corner = 14.dp, scaleTo = 1.02f, onClick = { syncOpen = true }) { focused ->
                Row(Modifier.fillMaxSize().background(if (focused) Color(0x443D3450) else Color(0x1EFFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(3.dp)) { QrCode(AppRuntime.pairingUrl, size = 44.dp) }
                    Text(if (paired) "已收到配置，正在登录" else "扫描以从其他设备同步", color = InkSub, fontSize = 9.sp, maxLines = 2)
                }
            }
        }
    }
    if (unsupportedScan) AlertDialog(
        onDismissRequest = { unsupportedScan = false },
        title = { Text("无法读取此二维码") },
        text = { Text("请使用 Lumina 登录配置码。其他设备同步请点击卡片下方的“从其他设备同步”。") },
        confirmButton = { TextButton(onClick = { unsupportedScan = false }) { Text("关闭") } },
    )
}

@Composable
private fun LoginCard(
    modifier: Modifier, host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit,
    user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit,
    scheme: String, onScheme: (String) -> Unit, busy: Boolean, probing: Boolean, error: String?,
    onLogin: () -> Unit, onScan: () -> Unit, onSync: () -> Unit, onHelp: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(30.dp)).background(Glass).border(1.dp, GlassLine, RoundedCornerShape(30.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("连接服务器", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("输入账号或扫描配置二维码", color = InkSub, fontSize = 11.sp)
            }
            FocusSurface(Modifier.width(116.dp).height(42.dp), corner = 15.dp, scaleTo = 1.04f, onClick = onScan) { focused ->
                Row(Modifier.fillMaxSize().background(if (focused) Color(0x66A78BFA) else Color(0x332A2438), RoundedCornerShape(15.dp)), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = VioletBright, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("扫码登录", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("协议", color = InkSub, fontSize = 11.sp)
            listOf("http", "https").forEach { candidate ->
                FocusSurface(Modifier.width(66.dp).height(38.dp), corner = 13.dp, scaleTo = 1.03f, onClick = { onScheme(candidate) }) { focused ->
                    Box(Modifier.fillMaxSize().background(if (scheme == candidate) Color(0x776B54A8) else if (focused) Color(0x33FFFFFF) else Color(0x1DFFFFFF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text(candidate, color = if (scheme == candidate) VioletBright else InkSub, fontSize = 12.sp, fontWeight = if (scheme == candidate) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassField(host, onHost, "IP 或域名", Modifier.weight(1f), ImeAction.Next)
            GlassField(port, onPort, "端口", Modifier.width(112.dp), ImeAction.Next, keyboardType = KeyboardType.Number)
        }
        GlassField(user, onUser, "Emby 用户名", Modifier.fillMaxWidth(), ImeAction.Next)
        GlassField(pass, onPass, "密码", Modifier.fillMaxWidth(), ImeAction.Done, password = true, onDone = onLogin)
        FocusSurface(Modifier.fillMaxWidth().height(54.dp), corner = 18.dp, scaleTo = 1.025f, onClick = onLogin) { focused ->
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(if (focused) listOf(Color(0xFF9E7BEE), Color(0xFFC3A8FF)) else listOf(Color(0xFF7757B5), Color(0xFF9A7BDA))), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(if (probing) "正在探测服务器…" else "正在登录…", color = Color.White, fontSize = 13.sp) }
                else Text("登 录", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FocusSurface(Modifier.height(38.dp), corner = 13.dp, scaleTo = 1.03f, onClick = onSync) { focused ->
                Box(Modifier.fillMaxSize().background(if (focused) Color(0x3DFFFFFF) else Color(0x1FFFFFFF), RoundedCornerShape(13.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("从其他设备同步", color = Ink, fontSize = 11.sp)
                }
            }
            FocusSurface(Modifier.height(38.dp), corner = 13.dp, scaleTo = 1.03f, onClick = onHelp) { focused ->
                Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text("帮助与指引", color = if (focused) VioletBright else Violet, fontSize = 11.sp) }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF929D), fontSize = 11.sp) }
    }
}

@Composable
private fun GlassField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier, action: ImeAction, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false, onDone: (() -> Unit)? = null) {
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
        modifier = modifier.height(56.dp).onFocusChanged { focused = it.isFocused }
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
