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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.QrCode
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.launch

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
            var lastName = ""
            for (base in candidates) {
                try {
                    val client = com.lumina.tv.data.EmbyClient(baseUrl = base, deviceId = prefs?.deviceId ?: "lumina-tv")
                    lastName = client.testConnection(base)
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1020.dp

        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {

            // 左：品牌 + 登录表单
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("琉璃", color = TextMain, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(" TV", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.height(4.dp))
                Text("连接你的 Emby 服务器", color = TextSub, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))

                GlassPanel(Modifier.width(520.dp), corner = 26.dp) {
                    Column(Modifier.padding(26.dp)) {

                        // ---- 服务器地址 ----
                        Text("服务器地址", color = TextSub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("http", "https").forEach { s ->
                                    val selected = scheme == s
                                    FocusSurface(
                                        modifier = Modifier.width(82.dp).height(50.dp),
                                        corner = 12.dp,
                                        scaleTo = 1f,
                                        onClick = { scheme = s },
                                    ) { focused ->
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                when {
                                                    selected -> Accent
                                                    focused -> Color(0x2EFFFFFF)
                                                    else -> Color(0x14FFFFFF)
                                                }
                                            ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                s,
                                                fontSize = 12.sp,
                                                color = if (selected) Color(0xFF06101F) else TextSub,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            LumiField(
                                value = host,
                                onChange = { host = it },
                                placeholder = "IP 或域名",
                                modifier = Modifier.weight(1f),
                                imeAction = ImeAction.Next,
                            )
                            Spacer(Modifier.width(8.dp))
                            LumiField(
                                value = port,
                                onChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = "端口",
                                modifier = Modifier.width(88.dp),
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // ---- 用户名 ----
                        Text("用户名", color = TextSub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LumiField(
                            value = user,
                            onChange = { user = it },
                            placeholder = "Emby 用户名",
                            modifier = Modifier.fillMaxWidth(),
                            imeAction = ImeAction.Next,
                        )

                        Spacer(Modifier.height(14.dp))

                        // ---- 密码 ----
                        Text("密码", color = TextSub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LumiField(
                            value = pass,
                            onChange = { pass = it },
                            placeholder = "密码",
                            modifier = Modifier.fillMaxWidth(),
                            password = true,
                            imeAction = ImeAction.Done,
                            onDone = { submit(user, pass) },
                        )

                        Spacer(Modifier.height(20.dp))

                        // ---- 登录按钮 ----
                        FocusSurface(
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            corner = 16.dp,
                            scaleTo = 1.02f,
                            onClick = { submit(user, pass) },
                        ) { _ ->
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(listOf(Accent, Accent2))
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
                                        "登 录",
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 6.sp,
                                    )
                                }
                            }
                        }

                        error?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = Color(0xFFFF8A9A), fontSize = 12.sp)
                        }
                    }
                }
            }

            // 右：扫码配对（仅宽屏 / TV 显示）
            if (wide) {
                Spacer(Modifier.width(44.dp))

                Column(
                    Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlassPanel(Modifier.width(330.dp), corner = 28.dp) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("扫码 30 秒配好", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "手机扫一扫，直接填服务器、账号、密码",
                                color = TextSub, fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(16.dp))
                            if (qrUrl.isNotEmpty()) {
                                QrCode(qrUrl, size = 196.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    qrUrl.removePrefix("http://").removeSuffix("/"),
                                    color = Accent, fontSize = 12.sp,
                                )
                            } else {
                                Box(
                                    Modifier.size(196.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        qrError ?: "正在生成二维码…",
                                        color = TextSub, fontSize = 11.sp,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (paired) "已收到手机配置，正在登录…" else "手机与电视需在同一个 WiFi",
                                color = if (paired) Accent else TextSub, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 输入框：外部标签 + 56dp 高度，placeholder 常显，支持 IME 动作。 */
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
        placeholder = { Text(placeholder, fontSize = 13.sp, color = TextSub.copy(alpha = 0.55f)) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() },
            onNext = { /* 默认跳到下一焦点 */ },
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedBorderColor = Accent,
            unfocusedBorderColor = GlassLine,
            cursorColor = Accent,
            focusedContainerColor = Color(0x0DFFFFFF),
            unfocusedContainerColor = Color(0x0AFFFFFF),
        ),
        modifier = modifier.height(56.dp),
    )
}
