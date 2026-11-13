package com.lumina.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.BuildConfig
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.PlaybackCache
import com.lumina.tv.data.PLAYBACK_CACHE_OPTIONS
import com.lumina.tv.data.DynamicRangePref
import com.lumina.tv.data.NumericPref
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.SectionTitle
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.THEME_PRESETS
import com.lumina.tv.ui.theme.GLASS_PRESETS
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = AppRuntime.prefs
    val scope = rememberCoroutineScope()
    var forceTranscode by remember { mutableStateOf(prefs?.forceTranscode ?: false) }
    var audioDecodeMode by remember { mutableStateOf(prefs?.audioDecodeMode ?: "auto") }
    var declareDtsDirectPlay by remember { mutableStateOf(prefs?.declareDtsDirectPlay ?: false) }
    var autoNext by remember { mutableStateOf(prefs?.autoNextEpisode ?: false) }
    var showPosterRating by remember { mutableStateOf(AppRuntime.prefs?.showPosterRating ?: true) }
    var showPosterWatchState by remember { mutableStateOf(AppRuntime.prefs?.showPosterWatchState ?: true) }
    var bootLaunch by remember { mutableStateOf(prefs?.bootLaunch ?: false) }
    var subtitleBg by remember { mutableStateOf(prefs?.subtitleBg ?: false) }
    var subtitleColor by remember { mutableStateOf(prefs?.subtitleColor ?: android.graphics.Color.WHITE) }
    var subtitleOutline by remember { mutableStateOf(prefs?.subtitleOutline ?: true) }
    var subtitlePosition by remember { mutableStateOf(prefs?.subtitlePosition ?: 0.08f) }
    var defaultSpeed by remember { mutableStateOf(prefs?.defaultSpeed ?: 1.0f) }
    var subtitleScale by remember { mutableStateOf(prefs?.subtitleScale ?: 1.0f) }
    var introSeconds by remember { mutableStateOf(prefs?.globalIntroSeconds ?: 0) }
    var endCreditsSkipSeconds by remember { mutableStateOf(AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) }
    var themeKey by remember { mutableStateOf(prefs?.themeColor ?: "blue") }
    var glassKey by remember { mutableStateOf(prefs?.glassStyle ?: "frosted") }
    var detailBackgroundVideo by remember { mutableStateOf(prefs?.detailBackgroundVideo?.takeIf { it in setOf("off", "720p", "auto") } ?: "auto") }
    var licensesOpen by remember { mutableStateOf(false) }
    var decoderPreference by remember { mutableStateOf(prefs?.decoderPreference ?: "auto") }
    var maxStreamingBitrate by remember { mutableStateOf(prefs?.maxStreamingBitrate ?: 120_000_000L) }
    var playbackCacheBytes by remember { mutableStateOf(prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L) }
    var playbackCacheUsed by remember { mutableStateOf(PlaybackCache.spaceBytes(context)) }
    var playerEngine by remember { mutableStateOf(prefs?.playerEngine ?: "auto") }
    var sourceDynamicRange by remember { mutableStateOf(prefs?.sourceDynamicRange ?: DynamicRangePref.UNSET) }
    var sourceBitrate by remember { mutableStateOf(prefs?.sourceBitratePreference ?: NumericPref.UNSET) }
    var sourceFrameRate by remember { mutableStateOf(prefs?.sourceFrameRatePreference ?: NumericPref.UNSET) }
    var sourceReleaseGroups by remember { mutableStateOf(prefs?.sourceReleaseGroups.orEmpty()) }
    var matchDisplayFrameRate by remember { mutableStateOf(prefs?.matchDisplayFrameRate ?: false) }
    var mpvBroken by remember { mutableStateOf(prefs?.mpvBroken ?: false) }
    var savedServers by remember { mutableStateOf(prefs?.savedServers().orEmpty()) }
    var hiddenIds by remember { mutableStateOf(prefs?.hiddenViewIds ?: emptySet()) }
    var views by remember { mutableStateOf(LibraryCache.home?.views ?: emptyList()) }
    var clearingHistory by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    var releaseUrl by remember { mutableStateOf<String?>(null) }
    var renameServerUrl by remember { mutableStateOf<String?>(null) }
    var renameServerText by remember { mutableStateOf("") }
    var showDiagExport by remember { mutableStateOf(false) }
    var sourcePreferencesExpanded by rememberSaveable { mutableStateOf(false) }
    var libraryViewsExpanded by rememberSaveable { mutableStateOf(false) }

    renameServerUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { renameServerUrl = null },
            title = { Text("编辑服务器名称") },
            text = {
                OutlinedTextField(
                    value = renameServerText,
                    onValueChange = { renameServerText = it.take(32) },
                    singleLine = true,
                    placeholder = { Text("例如：客厅 NAS") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs?.renameSavedServer(url, renameServerText)
                    savedServers = prefs?.savedServers().orEmpty()
                    renameServerUrl = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameServerUrl = null }) { Text("取消") } },
        )
    }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "设置"
        if (views.isEmpty()) {
            views = runCatching { AppRuntime.client?.getViews() }.getOrNull().orEmpty()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 960.dp

        Row(Modifier.fillMaxSize().padding(40.dp)) {

            // 左栏：外观 / 播放 / 字幕 / 账号 / 诊断
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("设置", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Lumina ${BuildConfig.VERSION_NAME}", color = TextSub, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    FocusSurface(Modifier.height(38.dp).width(88.dp), corner = 19.dp, onClick = onBack) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                            Text("返回", color = TextMain, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ---------- 账号与服务器 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("服务器与账号", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("当前服务器", color = TextSub, fontSize = 11.sp)
                        Text(AppRuntime.session?.serverName ?: "-", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(AppRuntime.session?.baseUrl ?: "-", color = Accent, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("账号：${AppRuntime.session?.userName ?: "-"}", color = TextSub, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FocusSurface(
                                modifier = Modifier.height(42.dp).width(150.dp),
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
                                ) { Text("退出 / 更换账号", color = Color(0xFFFF8A9A), fontSize = 13.sp) }
                            }
                            Text(
                                "修改服务器或账号后重新扫码或登录即可",
                                color = TextSub.copy(alpha = 0.7f), fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已保存服务器", color = TextSub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            FocusSurface(
                                    modifier = Modifier.height(34.dp).width(112.dp),
                                    corner = 17.dp, scaleTo = 1.04f,
                                onClick = { prefs?.saveCurrentServer(); savedServers = prefs?.savedServers().orEmpty() }) { focused ->
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                                    Text("保存当前服务器", color = TextMain, fontSize = 10.sp)
                                }
                            }
                        }
                        savedServers.forEach { (name, url) ->
                            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = TextMain, fontSize = 12.sp, maxLines = 1)
                                    Text(url, color = TextSub, fontSize = 10.sp, maxLines = 1)
                                }
                                FocusSurface(
                                    modifier = Modifier.height(32.dp).width(58.dp),
                                    corner = 16.dp, scaleTo = 1.04f, onClick = {
                                    renameServerUrl = url
                                    renameServerText = name
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                                        Text("编辑", color = TextMain, fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                FocusSurface(Modifier.size(32.dp), corner = 16.dp, scaleTo = 1.04f, onClick = {
                                    prefs?.removeSavedServer(url)
                                    savedServers = prefs?.savedServers().orEmpty()
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FF6B81) else Color(0x14FF6B81), CircleShape), contentAlignment = Alignment.Center) {
                                        Text("×", color = Color(0xFFFF8A9A), fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

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
                        Spacer(Modifier.height(12.dp))
                        Text("玻璃质感", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GLASS_PRESETS.forEach { g ->
                                val active = glassKey == g.key
                                FocusSurface(
                                    modifier = Modifier.width(96.dp).height(38.dp),
                                    corner = 12.dp, scaleTo = 1.04f,
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
                        Spacer(Modifier.height(8.dp))
                        Text("详情页背景视频", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("off" to "关", "720p" to "仅 720p 转码", "auto" to "自动").forEach { (key, label) ->
                                val active = detailBackgroundVideo == key
                                FocusSurface(
                                    modifier = Modifier.height(34.dp).widthIn(min = 72.dp),
                                    corner = 10.dp,
                                    onClick = {
                                        detailBackgroundVideo = key
                                        prefs?.detailBackgroundVideo = key
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            if (active) Accent.copy(alpha = 0.9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF),
                                            RoundedCornerShape(10.dp),
                                        ).padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            title = "海报评分",
                            desc = "在海报右上角显示 ★ 评分",
                            checked = showPosterRating,
                            onChange = {
                                showPosterRating = it
                                AppRuntime.prefs?.showPosterRating = it
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            title = "观看状态",
                            desc = "在海报左上角标记已看",
                            checked = showPosterWatchState,
                            onChange = {
                                showPosterWatchState = it
                                AppRuntime.prefs?.showPosterWatchState = it
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------- 首页媒体库分类 ----------
                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("首页媒体库", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "勾选即在首页媒体库中显示，取消勾选即隐藏。只影响首页，不影响搜索与详情",
                            color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (views.isEmpty()) {
                            Text("打开一次首页后会自动同步服务器分类", color = TextSub, fontSize = 12.sp)
                        } else {
                            (if (libraryViewsExpanded) views else views.take(3)).forEach { v ->
                                SwitchRow(
                                    title = v.name,
                                    desc = when (v.type) {
                                        "movies" -> "电影库"
                                        "tvshows" -> "剧集库"
                                        "music" -> "音乐库"
                                        "boxsets" -> "合集"
                                        else -> "媒体库"
                                    },
                                    checked = v.id !in hiddenIds,
                                    rowHeight = 38,
                                    onChange = {
                                        prefs?.toggleViewHidden(v.id)
                                        hiddenIds = prefs?.hiddenViewIds ?: emptySet()
                                    },
                                )
                            }
                            if (views.size > 3) {
                                FocusSurface(Modifier.fillMaxWidth().height(34.dp), corner = 10.dp, onClick = { libraryViewsExpanded = !libraryViewsExpanded }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                        Text(if (libraryViewsExpanded) "收起" else "查看更多（共 ${views.size} 个）", color = TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FocusSurface(
                                    modifier = Modifier.height(34.dp).width(110.dp),
                                    corner = 17.dp, scaleTo = 1.04f,
                                    onClick = {
                                        prefs?.hiddenViewIds = emptySet()
                                        hiddenIds = emptySet()
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("全部显示", color = TextMain, fontSize = 11.sp) }
                                }
                                FocusSurface(
                                    modifier = Modifier.height(34.dp).width(110.dp),
                                    corner = 17.dp, scaleTo = 1.04f,
                                    onClick = {
                                        val all = views.map { it.id }.toSet()
                                        prefs?.hiddenViewIds = all
                                        hiddenIds = all
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("全部隐藏", color = TextMain, fontSize = 11.sp) }
                                }
                            }
                        }
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
                                    corner = 12.dp, scaleTo = 1.04f,
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
                            title = "向服务器声明支持 DTS/TrueHD 直放（实验）",
                            desc = "开启后服务器可能直接给原轨；若本机解不了会退回 mpv，可能出现黑画面/无声，建议先导出诊断对比",
                            checked = declareDtsDirectPlay,
                            onChange = {
                                declareDtsDirectPlay = it
                                prefs?.declareDtsDirectPlay = it
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("文字颜色", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN, android.graphics.Color.GREEN).forEach { color ->
                                FocusSurface(Modifier.size(34.dp), corner = 17.dp, scaleTo = 1.04f, onClick = {
                                    subtitleColor = color
                                    prefs?.subtitleColor = color
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(Color(color), CircleShape).then(
                                        if (subtitleColor == color || focused) Modifier.border(2.dp, Accent, CircleShape) else Modifier))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(title = "字幕描边", desc = "高对比背景下保持文字清晰", checked = subtitleOutline, onChange = {
                            subtitleOutline = it
                            prefs?.subtitleOutline = it
                        })
                        Text("垂直位置", color = TextSub, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.04f to "低", 0.08f to "标准", 0.15f to "高").forEach { (value, label) ->
                                val active = kotlin.math.abs(subtitlePosition - value) < .01f
                                FocusSurface(Modifier.width(74.dp).height(34.dp), corner = 17.dp, scaleTo = 1.04f, onClick = {
                                    subtitlePosition = value
                                    prefs?.subtitlePosition = value
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
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
                                    corner = 12.dp, scaleTo = 1.04f,
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
                        Spacer(Modifier.height(8.dp))
                        Text("片尾跳过", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "关闭", 30 to "30 秒", 60 to "60 秒").forEach { (seconds, label) ->
                                val active = endCreditsSkipSeconds == seconds
                                FocusSurface(
                                    modifier = Modifier.width(84.dp).height(36.dp),
                                    corner = 12.dp, scaleTo = 1.04f,
                                    onClick = {
                                        endCreditsSkipSeconds = seconds
                                        AppRuntime.prefs?.endCreditsSkipSeconds = seconds
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
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("播放内核：ExoPlayer（硬解 · 支持 H.264/H.265/AV1 · 帧率自动匹配）", color = TextSub, fontSize = 11.sp)
                        Text("解码器优先级", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "自动", "hardware" to "硬解优先", "software" to "软解优先").forEach { (key, label) ->
                                val active = decoderPreference == key
                                FocusSurface(Modifier.width(100.dp).height(36.dp), corner = 18.dp, scaleTo = 1.04f, onClick = {
                                    decoderPreference = key
                                    prefs?.decoderPreference = key
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = 0.9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("播放码率上限", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(20_000_000L to "20 Mbps", 40_000_000L to "40 Mbps", 80_000_000L to "80 Mbps", 120_000_000L to "120 Mbps").forEach { (value, label) ->
                                val active = maxStreamingBitrate == value
                                FocusSurface(Modifier.width(98.dp).height(36.dp), corner = 12.dp, onClick = {
                                    maxStreamingBitrate = value
                                    prefs?.maxStreamingBitrate = value
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = 0.9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("播放缓存", color = TextSub, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PLAYBACK_CACHE_OPTIONS.forEach { value ->
                                val label = when (value) {
                                    0L -> "关闭"
                                    256L * 1024L * 1024L -> "256M"
                                    512L * 1024L * 1024L -> "512M"
                                    1024L * 1024L * 1024L -> "1G"
                                    else -> "2G"
                                }
                                val active = playbackCacheBytes == value
                                FocusSurface(Modifier.width(72.dp).height(36.dp), corner = 12.dp, onClick = {
                                    playbackCacheBytes = value
                                    prefs?.playbackCacheBytes = value
                                    PlaybackCache.setLimit(value)
                                    playbackCacheUsed = PlaybackCache.spaceBytes(context)
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = 0.9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已用 ${playbackCacheUsed / (1024L * 1024L)} MB", color = TextSub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            FocusSurface(Modifier.width(104.dp).height(34.dp), corner = 17.dp, onClick = {
                                PlaybackCache.clear(context)
                                playbackCacheUsed = PlaybackCache.spaceBytes(context)
                            }) { focused ->
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                                    Text("清空播放缓存", color = TextMain, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

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
                                    corner = 12.dp, scaleTo = 1.04f,
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

                Spacer(Modifier.height(14.dp))

                GlassPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Text("版本更新", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("当前版本 ${BuildConfig.VERSION_NAME}", color = TextSub, fontSize = 11.sp)
                        updateResult?.let { Text(it, color = TextSub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FocusSurface(Modifier.height(38.dp).width(138.dp), corner = 19.dp, scaleTo = 1.04f, onClick = {
                                if (!checkingUpdate) scope.launch {
                                    checkingUpdate = true
                                    updateResult = "正在检查…"
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            val request = okhttp3.Request.Builder()
                                                .url("https://api.github.com/repos/q3330576893/lumina-tv/releases/latest")
                                                .header("Accept", "application/vnd.github+json")
                                                .header("User-Agent", "LuminaTV/${BuildConfig.VERSION_NAME}")
                                                .build()
                                            okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                                                if (!response.isSuccessful) error("HTTP ${response.code}")
                                                val json = org.json.JSONObject(response.body?.string().orEmpty())
                                                val tag = json.optString("tag_name")
                                                val url = json.optString("html_url")
                                                val latest = tag.removePrefix("v").substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
                                                val current = BuildConfig.VERSION_NAME.split('.').mapNotNull(String::toIntOrNull)
                                                val newer = (0 until maxOf(latest.size, current.size)).firstNotNullOfOrNull { index ->
                                                    val delta = (latest.getOrElse(index) { 0 }).compareTo(current.getOrElse(index) { 0 })
                                                    delta.takeIf { it != 0 }
                                                }?.let { it > 0 } ?: false
                                                url to if (newer) "发现新版本 $tag" else "当前已是最新版本"
                                            }
                                        }
                                    }
                                    result.onSuccess { (url, message) ->
                                        releaseUrl = url
                                        updateResult = message
                                    }.onFailure { updateResult = "检查失败：${it.message ?: "网络不可用"}" }
                                    checkingUpdate = false
                                }
                            }) { focused ->
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                                    Text(if (checkingUpdate) "检查中…" else "检查更新", color = TextMain, fontSize = 12.sp)
                                }
                            }
                            if (releaseUrl != null) FocusSurface(Modifier.height(38.dp).width(120.dp), corner = 19.dp, scaleTo = 1.04f, onClick = {
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(releaseUrl))) }
                            }) { focused ->
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                                    Text("打开发布页", color = TextMain, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                GlassPanel(Modifier.fillMaxWidth(), corner = 18.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Text("播放后端", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            buildList {
                                add("auto" to "自动")
                                add("media3" to "Media3")
                                add("mpv" to if (mpvBroken) "mpv（已禁用）" else "mpv")
                            }.forEach { (key, label) ->
                                val active = playerEngine == key
                                FocusSurface(Modifier.widthIn(min = 76.dp, max = if (mpvBroken && key == "mpv") 126.dp else 94.dp).height(36.dp), corner = 18.dp, scaleTo = 1.04f, onClick = {
                                    if (key == "mpv" && mpvBroken) return@FocusSurface
                                    playerEngine = key
                                    prefs?.playerEngine = key
                                }) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (active && !mpvBroken) Accent.copy(alpha = .9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active && !mpvBroken) Color(0xFF06101F) else if (mpvBroken && key == "mpv") TextSub.copy(alpha = .6f) else TextMain, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        if (mpvBroken) {
                            Spacer(Modifier.height(8.dp))
                            FocusSurface(Modifier.height(36.dp).width(150.dp), corner = 18.dp, onClick = {
                                prefs?.mpvBroken = false
                                prefs?.mpvUnavailableReason = ""
                                mpvBroken = false
                            }) { focused ->
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                    Text("重置 mpv 状态", color = TextMain, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FocusSurface(Modifier.fillMaxWidth().height(44.dp), corner = 10.dp, onClick = { sourcePreferencesExpanded = !sourcePreferencesExpanded }) { focused ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("片源偏好", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(12.dp))
                                val sourceSummary = buildList {
                                    if (sourceDynamicRange != DynamicRangePref.UNSET) add(when (sourceDynamicRange) {
                                        DynamicRangePref.DOLBY_VISION -> "DV优先"
                                        DynamicRangePref.HDR -> "HDR优先"
                                        DynamicRangePref.SDR -> "SDR优先"
                                        else -> ""
                                    })
                                    if (sourceBitrate != NumericPref.UNSET) add(if (sourceBitrate == NumericPref.HIGH) "码率高优先" else "码率低优先")
                                    if (sourceFrameRate != NumericPref.UNSET) add(if (sourceFrameRate == NumericPref.HIGH) "帧率高优先" else "帧率低优先")
                                }.joinToString(" · ").ifBlank { "默认（取第一个源）" }
                                Text(sourceSummary, Modifier.weight(1f), color = TextSub, fontSize = 10.sp, maxLines = 1)
                                androidx.compose.material3.Icon(if (sourcePreferencesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = if (focused) Accent else TextSub, modifier = Modifier.size(18.dp))
                            }
                        }
                        AnimatedVisibility(visible = sourcePreferencesExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column {
                        listOf(
                            Triple("动态范围", sourceDynamicRange.name, listOf(
                                DynamicRangePref.UNSET to "不设置", DynamicRangePref.DOLBY_VISION to "DV优先",
                                DynamicRangePref.HDR to "HDR优先", DynamicRangePref.SDR to "SDR优先",
                            )),
                        ).forEach { (title, selected, options) ->
                            Text(title, color = TextSub, fontSize = 10.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                options.forEach { (value, label) ->
                                    val active = selected == value.name
                                    FocusSurface(Modifier.height(32.dp).widthIn(min = 72.dp).padding(0.dp), corner = 16.dp, onClick = {
                                        sourceDynamicRange = value
                                        prefs?.sourceDynamicRange = value
                                    }) { focused ->
                                        Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                            Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                        }
                                    }
                                }
                            }
                        }
                        listOf(
                            Triple("码率", sourceBitrate, { v: NumericPref -> sourceBitrate = v; prefs?.sourceBitratePreference = v }),
                            Triple("帧率", sourceFrameRate, { v: NumericPref -> sourceFrameRate = v; prefs?.sourceFrameRatePreference = v }),
                        ).forEach { (title, selected, save) ->
                            Spacer(Modifier.height(6.dp))
                            Text(title, color = TextSub, fontSize = 10.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(NumericPref.UNSET to "不设置", NumericPref.HIGH to "高优先", NumericPref.LOW to "低优先").forEach { (value, label) ->
                                    val active = selected == value
                                    FocusSurface(Modifier.height(32.dp).widthIn(min = 72.dp), corner = 16.dp, onClick = { save(value) }) { focused ->
                                        Box(Modifier.fillMaxSize().background(if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                            Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sourceReleaseGroups,
                            onValueChange = { sourceReleaseGroups = it; prefs?.sourceReleaseGroups = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("发布组关键词") },
                            placeholder = { Text("SPARKS,FGT") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            title = "匹配显示帧率",
                            desc = "默认关闭；仅在 API 30 及以上尝试无缝切换",
                            checked = matchDisplayFrameRate,
                            onChange = { matchDisplayFrameRate = it; prefs?.matchDisplayFrameRate = it },
                        )
                        }
                        }
                        Spacer(Modifier.height(6.dp))
                        Spacer(Modifier.height(12.dp))
                        listOf(
                            Triple("auto", "自动", "仅不支持的音轨本地软解"),
                            Triple("soft", "强制本地软解", "全部解码成 PCM"),
                            Triple("passthrough", "强制直通", "交给功放"),
                        ).forEach { (mode, title, description) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = audioDecodeMode == mode,
                                    onClick = {
                                        audioDecodeMode = mode
                                        prefs?.audioDecodeMode = mode
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(description, color = TextSub, fontSize = 10.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("自动：Media3 → mpv；mpv 硬解启动失败时自动改用软解。", color = TextSub, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))
                GlassPanel(Modifier.fillMaxWidth(), corner = 18.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Text("诊断", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        FocusSurface(Modifier.height(40.dp).width(180.dp), corner = 20.dp, onClick = { showDiagExport = true }) { focused ->
                            Box(Modifier.fillMaxSize().background(if (focused) Accent.copy(alpha = .9f) else Color(0x1AFFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                                Text("导出诊断包", color = if (focused) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FocusSurface(Modifier.height(40.dp).width(180.dp), corner = 20.dp, onClick = {
                            val report = com.lumina.tv.CrashHandler.crashFile(context).takeIf(java.io.File::exists)?.readText().orEmpty()
                            if (report.isNotBlank()) {
                                clipboard.setText(AnnotatedString(DiagExporter.sanitize(report)))
                                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            } else android.widget.Toast.makeText(context, "暂无崩溃报告", android.widget.Toast.LENGTH_SHORT).show()
                        }) { focused ->
                            Box(Modifier.fillMaxSize().background(if (focused) Accent.copy(alpha = .9f) else Color(0x1AFFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                                Text("复制崩溃报告", color = if (focused) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                // ---------- 关于（放在整页最后） ----------
                AboutPanel(Modifier.fillMaxWidth(), onLicenses = { licensesOpen = true })

                Spacer(Modifier.height(12.dp))
            }

            // 关于统一放在主列最后（见下方），不再单独占右栏
        }
    }
    if (licensesOpen) AlertDialog(
        onDismissRequest = { licensesOpen = false },
        title = { Text("开源许可") },
        text = { Text("Media3\nmpv\n本机 FFmpeg") },
        confirmButton = { TextButton(onClick = { licensesOpen = false }) { Text("关闭") } },
    )
    DiagExportDialog(visible = showDiagExport, onDismiss = { showDiagExport = false }) { outcome ->
        withContext(Dispatchers.IO) {
            DiagExporter.export(context, DiagExporter.environmentPayload(context, outcome), "environment")
        }
    }
}

@Composable
private fun AboutPanel(modifier: Modifier, onLicenses: () -> Unit) {
    GlassPanel(modifier, corner = 26.dp) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("关于", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Lumina TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = TextSub, fontSize = 12.sp)
            FocusSurface(Modifier.fillMaxWidth().height(40.dp), corner = 12.dp, onClick = onLicenses) { focused ->
                Box(Modifier.fillMaxSize().background(if (focused) Color(0x22FFFFFF) else Color.Transparent), contentAlignment = Alignment.CenterStart) {
                    Text("开源许可", Modifier.padding(horizontal = 12.dp), color = TextMain, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit, rowHeight: Int = 44) {
    FocusSurface(
        modifier = Modifier.fillMaxWidth().height(rowHeight.dp),
        corner = 14.dp,
        scaleTo = 1.02f,
        onClick = { onChange(!checked) },
    ) { focused ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) TextMain else TextMain.copy(alpha = 0.86f), fontSize = 13.sp)
                Text(desc, color = if (focused) TextSub else TextSub.copy(alpha = 0.78f), fontSize = 10.sp)
            }
            Switch(
                modifier = Modifier.focusable(false),
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
}
