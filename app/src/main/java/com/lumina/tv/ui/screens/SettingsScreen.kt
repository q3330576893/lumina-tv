package com.lumina.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
import com.lumina.tv.BuildConfig
import com.lumina.tv.CrashHandler
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.QrCode
import com.lumina.tv.ui.components.SectionTitle
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.THEME_PRESETS
import com.lumina.tv.ui.theme.GLASS_PRESETS
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val prefs = AppRuntime.prefs
    var forceTranscode by remember { mutableStateOf(prefs?.forceTranscode ?: false) }
    var autoNext by remember { mutableStateOf(prefs?.autoNextEpisode ?: false) }
    var bootLaunch by remember { mutableStateOf(prefs?.bootLaunch ?: false) }
    var subtitleBg by remember { mutableStateOf(prefs?.subtitleBg ?: false) }
    var defaultSpeed by remember { mutableStateOf(prefs?.defaultSpeed ?: 1.0f) }
    var subtitleScale by remember { mutableStateOf(prefs?.subtitleScale ?: 1.0f) }
    var introSeconds by remember { mutableStateOf(prefs?.globalIntroSeconds ?: 0) }
    var themeKey by remember { mutableStateOf(prefs?.themeColor ?: "blue") }
    var glassKey by remember { mutableStateOf(prefs?.glassStyle ?: "frosted") }
    var qrUrl by remember { mutableStateOf(AppRuntime.pairingUrl) }
    var crashInfo by remember { mutableStateOf(CrashHandler.lastCrashSummary(context)) }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "设置"
        if (AppRuntime.pairing == null) AppRuntime.startPairing(context)
        qrUrl = AppRuntime.pairingUrl
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 960.dp

        Row(Modifier.fillMaxSize().padding(40.dp)) {

            // 左栏：外观 / 播放 / 字幕 / 账号 / 诊断
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionTitle("设置", trailing = "琉璃TV ${BuildConfig.VERSION_NAME}")
                Spacer(Modifier.height(18.dp))

                // ---------- 外观 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("外观", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        Text("主题色", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            THEME_PRESETS.forEach { p ->
                                val active = themeKey == p.key
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FocusSurface(
                                        modifier = Modifier.size(44.dp),
                                        corner = 22.dp,
                                        onClick = {
                                            themeKey = p.key
                                            ThemeRuntime.applyTheme(p.key)
                                            prefs?.themeColor = p.key
                                        },
                                    ) { focused ->
                                        Box(
                                            Modifier.fillMaxSize().background(p.accent, CircleShape)
                                                .border(
                                                    width = if (active || focused) 2.5.dp else 1.dp,
                                                    color = if (active) Color(0xFFEAF1FF) else GlassLine,
                                                    shape = CircleShape,
                                                )
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(p.label, color = if (active) TextMain else TextSub, fontSize = 10.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("玻璃质感", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GLASS_PRESETS.forEach { g ->
                                val active = glassKey == g.key
                                FocusSurface(
                                    modifier = Modifier.width(96.dp).height(38.dp),
                                    corner = 12.dp, scaleTo = 1f,
                                    onClick = {
                                        glassKey = g.key
                                        ThemeRuntime.applyGlass(g.key)
                                        prefs?.glassStyle = g.key
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            when {
                                                active -> Accent.copy(alpha = 0.9f)
                                                focused -> Color(0x2EFFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }, RoundedCornerShape(12.dp)
                                        ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            g.label,
                                            color = if (active) Color(0xFF06101F) else TextMain,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            if (glassKey == "off") "已关闭玻璃效果：低配设备更流畅省电" else "磨砂更沉浸，清透更轻盈，关闭更省电",
                            color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------- 播放 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("播放", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))

                        Text("默认倍速", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { s ->
                                val active = kotlin.math.abs(defaultSpeed - s) < 0.01f
                                FocusSurface(
                                    modifier = Modifier.width(72.dp).height(36.dp),
                                    corner = 12.dp, scaleTo = 1f,
                                    onClick = {
                                        defaultSpeed = s
                                        prefs?.defaultSpeed = s
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            when {
                                                active -> Accent.copy(alpha = 0.9f)
                                                focused -> Color(0x2EFFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }, RoundedCornerShape(12.dp)
                                        ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (s == 1.0f) "1.0x" else "${s}x",
                                            color = if (active) Color(0xFF06101F) else TextMain,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        SwitchRow(
                            title = "自动连播",
                            desc = "剧集播完自动播放下一集",
                            checked = autoNext,
                            onChange = {
                                autoNext = it
                                prefs?.autoNextEpisode = it
                            },
                        )
                        SwitchRow(
                            title = "强制转码",
                            desc = "电视盒子解码异常或画面卡顿时打开",
                            checked = forceTranscode,
                            onChange = {
                                forceTranscode = it
                                prefs?.forceTranscode = it
                            },
                        )
                        SwitchRow(
                            title = "开机自启",
                            desc = "电视盒子开机后自动打开琉璃",
                            checked = bootLaunch,
                            onChange = {
                                bootLaunch = it
                                prefs?.bootLaunch = it
                            },
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("跳过片头（全局默认）", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "关", 60 to "60秒", 90 to "90秒", 120 to "120秒").forEach { (sec, label) ->
                                val active = introSeconds == sec
                                FocusSurface(
                                    modifier = Modifier.width(84.dp).height(36.dp),
                                    corner = 12.dp, scaleTo = 1f,
                                    onClick = {
                                        introSeconds = sec
                                        prefs?.globalIntroSeconds = sec
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            when {
                                                active -> Accent.copy(alpha = 0.9f)
                                                focused -> Color(0x2EFFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }, RoundedCornerShape(12.dp)
                                        ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            label,
                                            color = if (active) Color(0xFF06101F) else TextMain,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "播放器内也可对单部剧「记住片头到此为止」，单剧设置优先于全局",
                            color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("播放内核：ExoPlayer（硬解 · 支持 H.264/H.265/AV1 · 帧率自动匹配）", color = TextSub, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------- 字幕 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("字幕", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        Text("字号", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.85f to "小", 1.0f to "标准", 1.25f to "大", 1.5f to "特大").forEach { (scale, label) ->
                                val active = kotlin.math.abs(subtitleScale - scale) < 0.01f
                                FocusSurface(
                                    modifier = Modifier.width(84.dp).height(36.dp),
                                    corner = 12.dp, scaleTo = 1f,
                                    onClick = {
                                        subtitleScale = scale
                                        prefs?.subtitleScale = scale
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            when {
                                                active -> Accent.copy(alpha = 0.9f)
                                                focused -> Color(0x2EFFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }, RoundedCornerShape(12.dp)
                                        ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            label,
                                            color = if (active) Color(0xFF06101F) else TextMain,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SwitchRow(
                            title = "字幕背景底色",
                            desc = "亮背景下更易读，默认关闭",
                            checked = subtitleBg,
                            onChange = {
                                subtitleBg = it
                                prefs?.subtitleBg = it
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------- 账号 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("账号", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        Text("当前服务器", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(AppRuntime.session?.serverName ?: "-", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(AppRuntime.session?.baseUrl ?: "-", color = Accent, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("账号：${AppRuntime.session?.userName ?: "-"}", color = TextSub, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        FocusSurface(
                            modifier = Modifier.height(42.dp).width(140.dp),
                            corner = 21.dp,
                            onClick = {
                                AppRuntime.logout()
                                onLogout()
                            },
                        ) { focused ->
                            Box(
                                Modifier.fillMaxSize().background(
                                    if (focused) Color(0x33FF6B81) else Color(0x1AFF6B81)
                                ),
                                contentAlignment = Alignment.Center,
                            ) { Text("退出登录", color = Color(0xFFFF8A9A), fontSize = 13.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------- 诊断与反馈 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("诊断与反馈", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if (crashInfo != null) {
                            Text("上次崩溃：$crashInfo", color = Color(0xFFFF8A9A), fontSize = 11.sp)
                        } else {
                            Text("暂无崩溃记录", color = TextSub, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FocusSurface(
                                modifier = Modifier.height(40.dp).width(150.dp),
                                corner = 18.dp,
                                onClick = { CrashHandler.shareReport(context) },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("导出诊断包", color = TextMain, fontSize = 12.sp) }
                            }
                            if (crashInfo != null) {
                                FocusSurface(
                                    modifier = Modifier.height(40.dp).width(130.dp),
                                    corner = 18.dp,
                                    onClick = {
                                        CrashHandler.clearCrash(context)
                                        crashInfo = null
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(if (focused) Color(0x33FF6B81) else Color(0x1AFF6B81)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("清除记录", color = Color(0xFFFF8A9A), fontSize = 12.sp) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("闪退后重新打开本页，点「导出诊断包」把内容发给开发者即可定位问题", color = TextSub, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                FocusSurface(
                    modifier = Modifier.height(44.dp).width(130.dp),
                    corner = 22.dp,
                    onClick = onBack,
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) { Text("返回", color = TextMain, fontSize = 13.sp) }
                }

                Spacer(Modifier.height(20.dp))
            }

            // 右栏：扫码配对（窄屏自动隐藏）
            if (wide) {
                Spacer(Modifier.width(36.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassPanel(Modifier.width(320.dp), corner = 26.dp) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("手机遥控 / 换服务器", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("扫码后可用手机打字搜索、当遥控器", color = TextSub, fontSize = 11.sp)
                            Spacer(Modifier.height(14.dp))
                            if (qrUrl.isNotEmpty()) {
                                QrCode(qrUrl, size = 200.dp)
                                Spacer(Modifier.height(10.dp))
                                Text(qrUrl.removePrefix("http://").removeSuffix("/"), color = Accent, fontSize = 12.sp)
                            } else {
                                Box(
                                    Modifier.height(200.dp).fillMaxWidth()
                                        .background(Color(0x14FFFFFF), RoundedCornerShape(18.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        AppRuntime.pairingError ?: "未连接局域网",
                                        color = TextSub, fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 7.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 13.sp)
            Text(desc, color = TextSub, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06101F),
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color(0xFF8B99B0),
                uncheckedTrackColor = Color(0x33FFFFFF),
            ),
        )
    }
}
