package com.lumina.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.QrCode
import kotlinx.coroutines.launch

// 淡薰衣草玻璃风配色（参考图：轻盈、留白、圆角）
private val LBgTop = Color(0xFFEDEBFF)
private val LBgMid = Color(0xFFF3F2FB)
private val LBgEnd = Color(0xFFFBFBFE)
private val LInk = Color(0xFF1A1826)
private val LInkSub = Color(0xFF6E6B80)
private val LPurple = Color(0xFF6C5CE7)
private val LPurple2 = Color(0xFF9D8CFF)
private val LCard = Color(0xF5FFFFFF)
private val LLine = Color(0x1F1A1826)

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = AppRuntime.prefs

    var scheme by remember { mutableStateOf("http") }
    var host by remember {
        mutableStateOf(
            prefs?.baseUrl
                ?.removePrefix("https://")?.removePrefix("http://")
                ?.substringBefore(":") ?: ""
        )
    }
    var port by remember {
        mutableStateOf(
            prefs?.baseUrl?.substringAfterLast(":", "")?.takeIf { it.toIntOrNull() != null } ?: "8096"
        )
    }
    var user by remember { mutableStateOf(prefs?.userName ?: "") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrUrl by remember { mutableStateOf(AppRuntime.pairingUrl) }
    var qrError by remember { mutableStateOf(AppRuntime.pairingError) }
    var paired by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    fun doLogin(baseUrl: String, u: String, p: String) {
        scope.launch {
            loading = true
            error = null
            try {
                val client = com.lumina.tv.data.EmbyClient(
                    baseUrl = baseUrl,
                    deviceId = prefs?.deviceId ?: "lumina-tv",
                )
                val session = client.login(baseUrl, u, p)
                AppRuntime.applySession(session)
                onLoggedIn()
            } catch (e: Exception) {
                error = when {
                    e.message?.contains("401") == true -> "用户名或密码不正确"
                    else -> e.message ?: "登录失败"
                }
            } finally {
                loading = false
                probing = false
            }
        }
    }

    fun submit(u: String, p: String) {
        if (loading || probing) return
        if (host.isBlank() || u.isBlank()) {
            error = "请填写服务器地址和用户名"
            return
        }
        val h = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val pt = port.trim().ifBlank { "8096" }
        scope.launch {
            probing = true
            error = null
            val candidates = buildList {
                add("$scheme://$h:$pt")
                if (scheme == "http") {
                    if (pt != "8096") add("http://$h:8096")
                    add("https://$h:8920")
                } else {
                    if (pt != "8920") add("https://$h:8920")
                    add("http://$h:8096")
                }
                add("http://$h:80")
            }.distinct()
            var found: String? = null
            for (base in candidates) {
                try {
                    val client = com.lumina.tv.data.EmbyClient(baseUrl = base, deviceId = prefs?.deviceId ?: "lumina-tv")
                    client.testConnection(base)
                    found = base
                    break
                } catch (_: Exception) { /* 尝试下一个 */ }
            }
            probing = false
            if (found == null) {
                error = "找不到服务器，请检查地址、端口和是否在同一网络"
            } else {
                if (found != "$scheme://$h:$pt") {
                    error = "已自动切换到 $found"
                }
                doLogin(found, u, p)
            }
        }
    }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "登录"
        AppRuntime.onPairConfig = { s, _, _ ->
            val norm = s.removePrefix("http://").removePrefix("https://")
            scheme = if (s.startsWith("https")) "https" else "http"
            host = norm.substringBefore(":")
            port = norm.substringAfter(":", "8096").ifBlank { "8096" }
            paired = true
            submit(user, pass)
        }
        if (AppRuntime.pairing == null) AppRuntime.startPairing(context)
        qrUrl = AppRuntime.pairingUrl
        qrError = AppRuntime.pairingError
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(LBgTop, LBgMid, LBgEnd))
        )
    ) {
        // 背景光晕
        Box(
            Modifier.size(620.dp).align(Alignment.TopEnd).offset(x = 180.dp, y = (-220).dp)
                .background(Brush.radialGradient(listOf(Color(0x73B9A8FF), Color(0x00B9A8FF))), CircleShape)
        )
        Box(
            Modifier.size(520.dp).align(Alignment.BottomStart).offset(x = (-180).dp, y = 200.dp)
                .background(Brush.radialGradient(listOf(Color(0x4D8FD8FF), Color(0x008FD8FF))), CircleShape)
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1020.dp

            Row(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 30.dp)) {

                // 左：品牌 + 表单
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 品牌（统一用「琉璃」）
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("琉璃", color = LInk, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(" TV", color = LPurple, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("连接你的 Emby 服务器", color = LInkSub, fontSize = 12.sp)

                    Spacer(Modifier.height(26.dp))
                    Text(
                        "添加媒体服务器\n从未如此简单",
                        color = LInk, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeaturePill("扫码 30 秒配好")
                        FeaturePill("自动探测端口")
                        FeaturePill("账号不落明文")
                    }

                    Spacer(Modifier.height(24.dp))

                    Box(
                        Modifier.width(560.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(LCard)
                            .padding(26.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("服务器", color = LInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                listOf("http", "https").forEach { s ->
                                    val selected = scheme == s
                                    FocusSurface(
                                        modifier = Modifier.width(64.dp).height(32.dp),
                                        corner = 16.dp, scaleTo = 1f,
                                        onClick = { scheme = s },
                                    ) { focused ->
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                when {
                                                    selected -> LPurple
                                                    focused -> Color(0x141A1826)
                                                    else -> Color(0x0A1A1826)
                                                }, RoundedCornerShape(16.dp)
                                            ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                s, fontSize = 11.sp,
                                                color = if (selected) Color.White else LInkSub,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LumiField(
                                    value = host,
                                    onChange = { host = it },
                                    placeholder = "IP 或域名",
                                    modifier = Modifier.weight(1f),
                                    imeAction = ImeAction.Next,
                                )
                                Spacer(Modifier.width(10.dp))
                                LumiField(
                                    value = port,
                                    onChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                                    placeholder = "端口",
                                    modifier = Modifier.width(96.dp),
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next,
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                            LumiField(
                                value = user,
                                onChange = { user = it },
                                placeholder = "Emby 用户名",
                                modifier = Modifier.fillMaxWidth(),
                                imeAction = ImeAction.Next,
                            )
                            Spacer(Modifier.height(10.dp))
                            LumiField(
                                value = pass,
                                onChange = { pass = it },
                                placeholder = "密码",
                                modifier = Modifier.fillMaxWidth(),
                                password = true,
                                imeAction = ImeAction.Done,
                                onDone = { submit(user, pass) },
                            )

                            Spacer(Modifier.height(18.dp))
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                corner = 18.dp,
                                scaleTo = 1.02f,
                                onClick = { submit(user, pass) },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                if (focused) LPurple else LPurple.copy(alpha = 0.92f),
                                                if (focused) LPurple2 else LPurple2.copy(alpha = 0.92f),
                                            )
                                        )
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (loading || probing) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                color = Color.White, strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                if (probing) "正在探测服务器…" else "正在登录…",
                                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    } else {
                                        Text(
                                            "登 录", color = Color.White, fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                                        )
                                    }
                                }
                            }

                            error?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = Color(0xFFD6304B), fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LightPillButton("从其他设备同步", LPurple) { showHelp = !showHelp }
                        LightPillButton("帮助与指引", LInkSub) { showHelp = !showHelp }
                    }
                    if (showHelp) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.width(560.dp).clip(RoundedCornerShape(16.dp))
                                .background(Color(0x0F1A1826)).padding(14.dp)
                        ) {
                            Column {
                                Text("如何连接", color = LInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "1. 手机与电视连同一个 WiFi\n2. 扫码后自动填入服务器、账号、密码\n3. 也可手动输入 IP（默认端口 8096）",
                                    color = LInkSub, fontSize = 11.sp, lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // 右：扫码配对
                if (wide) {
                    Spacer(Modifier.width(46.dp))
                    Column(
                        Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.width(330.dp).clip(RoundedCornerShape(28.dp))
                                .background(LCard).padding(24.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("扫码 30 秒配好", color = LInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("手机扫一扫，直接填服务器、账号、密码", color = LInkSub, fontSize = 11.sp)
                                Spacer(Modifier.height(16.dp))
                                if (qrUrl.isNotEmpty()) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(18.dp))
                                            .background(Color.White).padding(10.dp)
                                    ) { QrCode(qrUrl, size = 196.dp) }
                                    Spacer(Modifier.height(12.dp))
                                    Text(qrUrl.removePrefix("http://").removeSuffix("/"), color = LPurple, fontSize = 12.sp)
                                } else {
                                    Box(
                                        Modifier.size(196.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x0F1A1826)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            qrError ?: "正在生成二维码…", color = LInkSub, fontSize = 11.sp,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (paired) "已收到手机配置，正在登录…" else "手机与电视需在同一个 WiFi",
                                    color = if (paired) LPurple else LInkSub, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturePill(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x121A1826)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(LPurple, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = LInkSub, fontSize = 10.sp)
    }
}

@Composable
private fun LightPillButton(text: String, color: Color, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.height(36.dp),
        corner = 18.dp, scaleTo = 1.03f,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                if (focused) Color(0x141A1826) else Color(0x0A1A1826),
                RoundedCornerShape(18.dp),
            ).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = color, fontSize = 12.sp) }
    }
}

/** 浅色输入框：白底、淡紫聚焦，56dp。 */
@Composable
private fun LumiField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, fontSize = 13.sp, color = LInkSub.copy(alpha = 0.55f)) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() },
            onNext = { /* 默认跳到下一焦点 */ },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LInk,
            unfocusedTextColor = LInk,
            focusedBorderColor = LPurple,
            unfocusedBorderColor = LLine,
            cursorColor = LPurple,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color(0xFAFFFFFF),
        ),
        modifier = modifier.height(56.dp),
    )
}
