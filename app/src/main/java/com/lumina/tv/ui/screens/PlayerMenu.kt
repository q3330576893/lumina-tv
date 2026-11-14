package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.MediaSource
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.TvHintBar
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

@Composable
internal fun OptionMenu(
    exo: ExoPlayer,
    item: MediaItem,
    speed: Float,
    resizeMode: Int,
    currentSourceId: String?,
    episodes: List<MediaItem>,
    subScale: Float,
    audioDecodeMode: String,
    subtitleOffsetMs: Long,
    activeEngine: String,
    audioStreams: List<MediaStream>,
    selectedAudioStreamIndex: Int?,
    onAudioStreamSelected: (Int, TrackSelectionOverride?) -> Unit,
    onlyMedia3Available: Boolean,
    onSubtitleSelected: (String?) -> Unit,
    onSubtitleOffset: (Long) -> Unit,
    onSubScale: (Float) -> Unit,
    onAudioDecodeMode: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onSwitchEngine: () -> Unit,
    onResizeMode: (Int) -> Unit,
    onSwitchSource: (String) -> Unit,
    onPickEpisode: (MediaItem) -> Unit,
    onRememberIntro: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    val client = AppRuntime.client
    var sources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    LaunchedEffect(item.id) {
        sources = runCatching { client?.getMediaSources(item.id) }.getOrDefault(emptyList()).orEmpty()
    }

    // 轨道状态化：切换后 UI 立刻刷新
    var tracks by remember { mutableStateOf(exo.currentTracks) }
    var textDisabled by remember {
        mutableStateOf(exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
                textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }

    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }

    var selectedText: Pair<String, Int>? = null
    var selectedAudio: Pair<String, Int>? = null
    for (g in tracks.groups) {
        for (i in 0 until g.length) {
            if (g.isTrackSelected(i)) {
                when (g.type) {
                    C.TRACK_TYPE_TEXT -> selectedText = g.mediaTrackGroup.id to i
                    C.TRACK_TYPE_AUDIO -> selectedAudio = g.mediaTrackGroup.id to i
                }
            }
        }
    }

    val hasEpisodes = episodes.isNotEmpty()
    val tabs = if (hasEpisodes) listOf("选集", "字幕", "音轨", "更多") else listOf("字幕", "音轨", "更多")
    var selectedTab by remember(item.id, hasEpisodes) { mutableStateOf(if (hasEpisodes) "选集" else "字幕") }
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        contentHasFocus = false
        repeat(15) {
            delay(50)
            runCatching { contentFocus.requestFocus() }
            if (contentHasFocus) return@LaunchedEffect
        }
        runCatching { tabFocus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.width(680.dp), corner = 22.dp, fillColor = Color(0x7510141D)) {
            Column(
                Modifier.padding(22.dp).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { runCatching { tabFocus.requestFocus() }; true }
                        Key.DirectionDown -> { runCatching { contentFocus.requestFocus() }; true }
                        else -> false
                    }
                },
            ) {
                TvHintBar("上下键切换轨道 · 返回键关闭面板")
                Text("播放选项", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth().height(44.dp).focusRequester(tabFocus).focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
                            val next = when (event.key) {
                                Key.DirectionLeft -> (index - 1).coerceAtLeast(0)
                                Key.DirectionRight -> (index + 1).coerceAtMost(tabs.lastIndex)
                                else -> return@onPreviewKeyEvent false
                            }
                            selectedTab = tabs[next]
                            runCatching { contentFocus.requestFocus() }
                            true
                        },
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        Column(
                            Modifier.height(44.dp).clickable {
                                selectedTab = tab
                                runCatching { contentFocus.requestFocus() }
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(tab, color = if (selectedTab == tab) TextMain else TextSub,
                                fontSize = 14.sp, fontWeight = if (selectedTab == tab) FontWeight.Medium else FontWeight.Normal)
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.width(28.dp).height(2.dp).background(if (selectedTab == tab) Accent else Color.Transparent))
                        }
                    }
                }

                // 选集
                if (episodes.isNotEmpty()) {
                    if (selectedTab == "选集") {
                    MenuLabel("第 ${item.indexNumber ?: 1} 集 / 共 ${episodes.size} 集")
                    LazyRow(Modifier.heightIn(min = 180.dp, max = 240.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(episodes, key = { it.id }) { ep ->
                            val active = ep.id == item.id
                            FocusSurface(
                                modifier = Modifier.width(160.dp).height(90.dp)
                                    .then(if (ep == episodes.firstOrNull()) Modifier.focusRequester(contentFocus).onFocusChanged { contentHasFocus = it.isFocused } else Modifier),
                                corner = 10.dp, scaleTo = 1.0f,
                                onClick = { if (!active) onPickEpisode(ep) },
                            ) { focused ->
                                Column(
                                    Modifier.fillMaxSize().background(
                                        when {
                                            active -> Accent.copy(alpha = 0.9f)
                                            focused -> Color(0x2EFFFFFF)
                                            else -> Color(0x14FFFFFF)
                                        }, RoundedCornerShape(10.dp)
                                    ).padding(8.dp),
                                    verticalArrangement = Arrangement.Bottom,
                                ) {
                                    Text(
                                        "第 ${ep.indexNumber ?: 0} 集 · ${ep.name}",
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x55000000), RoundedCornerShape(2.dp))) {
                                        Box(Modifier.fillMaxWidth((ep.playedPercentage / 100.0).toFloat().coerceIn(0f, 1f)).height(3.dp).background(Accent, RoundedCornerShape(2.dp)))
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                if (selectedTab == "更多") {
                Spacer(Modifier.height(8.dp))
                if (onlyMedia3Available) Box(
                    Modifier.fillMaxWidth().height(38.dp).background(Color(0x14FFFFFF), RoundedCornerShape(10.dp)),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("播放内核", color = TextSub, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("M3（当前设备仅支持此内核）", color = TextSub, fontSize = 11.sp)
                    }
                } else FocusSurface(
                    modifier = Modifier.fillMaxWidth().height(38.dp).focusRequester(contentFocus)
                        .onFocusChanged { contentHasFocus = it.isFocused },
                    corner = 10.dp, scaleTo = 1.0f,
                    onClick = onSwitchEngine,
                ) { focused ->
                    Row(
                        Modifier.fillMaxSize().background(
                            if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF),
                            RoundedCornerShape(10.dp),
                        ).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("播放内核", color = if (focused) Accent else TextMain, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (activeEngine == "mpv") "mpv" else "M3",
                            color = TextSub,
                            fontSize = 12.sp,
                        )
                    }
                }

                // 倍速
                MenuLabel("倍速播放")
                Spacer(Modifier.height(8.dp))
                MenuLabel("音频解码")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "自动", "soft" to "本地软解", "passthrough" to "强制直通").forEach { (mode, label) ->
                        MenuChip(
                            label = label, width = 112.dp, active = audioDecodeMode == mode, activeColor = Accent,
                            focusRequester = if (onlyMedia3Available && mode == "auto") contentFocus else null,
                            onFocusChanged = if (onlyMedia3Available && mode == "auto") ({ contentHasFocus = it }) else null,
                            onClick = { onAudioDecodeMode(mode) },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEach { s ->
                        val active = kotlin.math.abs(speed - s) < 0.01f
                        MenuChip(
                            label = if (s == 1.0f) "1.0x" else "${s}x",
                            width = 78.dp, active = active, activeColor = Accent,
                            onClick = { onSpeed(s) },
                            focusRequester = null,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                MenuLabel("字幕同步 ${if (subtitleOffsetMs >= 0) "+" else ""}${subtitleOffsetMs}ms")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-100L, 0L, 100L).forEach { step ->
                        MenuChip(
                            label = if (step == 0L) "重置" else if (step > 0) "+${step}ms" else "${step}ms",
                            width = 90.dp,
                            active = step == 0L && subtitleOffsetMs == 0L,
                            activeColor = Accent,
                            onClick = { onSubtitleOffset(if (step == 0L) 0L else subtitleOffsetMs + step) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip(label = "-0.5s", width = 90.dp, active = false, activeColor = Accent,
                        onClick = { onSubtitleOffset(subtitleOffsetMs - 500L) })
                    MenuChip(label = "+0.5s", width = 90.dp, active = false, activeColor = Accent,
                        onClick = { onSubtitleOffset(subtitleOffsetMs + 500L) })
                }

                Spacer(Modifier.height(16.dp))

                }
                if (selectedTab == "字幕") {
                // 字幕
                MenuLabel("字幕")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip(
                        label = "关闭字幕", width = 104.dp,
                        active = textDisabled,
                        activeColor = Accent,
                        focusRequester = contentFocus,
                        onFocusChanged = { contentHasFocus = it },
                        onClick = {
                            textDisabled = true
                            onSubtitleSelected(null)
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        },
                    )
                    textGroups.forEach { g ->
                      for (trackIndex in 0 until g.length) {
                        val key = g.mediaTrackGroup.id
                        val f0 = g.getTrackFormat(trackIndex)
                        val label = buildString {
                            append(f0.label ?: f0.language ?: "字幕")
                            if (f0.language != null) append(" (${f0.language})")
                        }
                        MenuChip(
                            label = label, width = 148.dp,
                            active = !textDisabled && selectedText == (key to trackIndex),
                            activeColor = Accent,
                            onClick = {
                                textDisabled = false
                                onSubtitleSelected(key + ":" + trackIndex)
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .addOverride(TrackSelectionOverride(g.mediaTrackGroup, trackIndex))
                                    .build()
                            },
                        )
                      }
                    }
                }
                if (textGroups.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("没有检测到内嵌字幕轨道（外挂字幕会自动加载）", color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                Spacer(Modifier.height(10.dp))
                MenuLabel("字幕字号")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f to "小", 1.3f to "标准", 1.6f to "大", 2.0f to "特大", 2.4f to "超大").forEach { (s, label) ->
                        MenuChip(
                            label = label, width = 84.dp,
                            active = kotlin.math.abs(subScale - s) < 0.01f,
                            activeColor = Accent,
                            onClick = { onSubScale(s) },
                        )
                    }
                }

                }
                if (selectedTab == "音轨") {
                Spacer(Modifier.height(16.dp))

                // 音轨
                MenuLabel("音轨")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audioStreams.forEach { stream ->
                        val label = listOfNotNull(stream.displayTitle ?: stream.title, stream.language, stream.codec?.uppercase()).distinct().joinToString(" · ").ifBlank { "音轨 ${stream.index}" }
                        val decodedTrack = audioGroups.asSequence().flatMap { group ->
                            (0 until group.length).asSequence().map { index -> group to index }
                        }.firstOrNull { (group, index) ->
                            val format = group.getTrackFormat(index)
                            val sameLanguage = stream.language.isNullOrBlank() ||
                                format.language.equals(stream.language, ignoreCase = true)
                            val sameCodec = stream.codec.isNullOrBlank() ||
                                format.sampleMimeType?.substringAfter('/')?.equals(stream.codec, true) == true
                            sameLanguage && sameCodec
                        }
                        MenuChip(
                            label = label, width = 148.dp,
                            active = selectedAudioStreamIndex == stream.index ||
                                (selectedAudioStreamIndex == null && stream.isDefault),
                            activeColor = Accent,
                            focusRequester = if (stream == audioStreams.firstOrNull()) contentFocus else null,
                            onFocusChanged = if (stream == audioStreams.firstOrNull()) ({ contentHasFocus = it }) else null,
                            onClick = {
                                val override = decodedTrack?.let { (group, index) ->
                                    TrackSelectionOverride(group.mediaTrackGroup, index)
                                }
                                onAudioStreamSelected(stream.index, override)
                            },
                        )
                    }
                    if (audioStreams.isEmpty() && audioGroups.isEmpty()) {
                        Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
                            Text("单音轨", color = TextSub.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }

                }
                if (selectedTab == "更多") {
                Spacer(Modifier.height(16.dp))

                // 画面比例
                MenuLabel("画面比例")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪填充",
                        AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
                    ).forEach { (mode, label) ->
                        MenuChip(
                            label = label, width = 108.dp,
                            active = resizeMode == mode, activeColor = Accent,
                            onClick = { onResizeMode(mode) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 版本（多源）
                if (sources.size > 1) {
                    MenuLabel("版本（${sources.size} 个源）")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEach { s ->
                            val active = s.id == currentSourceId
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                corner = 12.dp, scaleTo = 1.0f,
                                onClick = { onSwitchSource(s.id) },
                            ) { focused ->
                                Row(
                                    Modifier.fillMaxSize().background(
                                        when {
                                            active -> Accent.copy(alpha = 0.9f)
                                            focused -> Color(0x2EFFFFFF)
                                            else -> Color(0x14FFFFFF)
                                        }, RoundedCornerShape(12.dp)
                                    ).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        s.displayLabel + if (active) "  ✓" else "",
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // 片头记忆
                MenuLabel("片头")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MenuChip(
                        label = "记住片头到此为止", width = 176.dp, active = false,
                        activeColor = Accent,
                        onClick = onRememberIntro,
                    )
                    Text("之后播放本集自动出现跳过按钮", color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                Spacer(Modifier.height(18.dp))
                MenuChip(
                    label = "关闭", width = 120.dp, active = false,
                    activeColor = Accent, height = 42.dp,
                    onClick = onClose,
                )
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, color = TextSub, fontSize = 11.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MenuChip(
    label: String,
    width: Dp,
    active: Boolean,
    activeColor: Color,
    height: Dp = 38.dp,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var m = Modifier.width(width).height(height)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    val content: @Composable (Boolean) -> Unit = { focused ->
        Box(
            Modifier.fillMaxSize().background(
                when {
                    active -> activeColor.copy(alpha = 0.9f)
                    focused -> Color(0x2EFFFFFF)
                    !enabled -> Color(0x0AFFFFFF)
                    else -> Color(0x14FFFFFF)
                }, RoundedCornerShape(12.dp)
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (active) Color(0xFF06101F) else if (enabled) TextMain else TextSub.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    if (enabled) FocusSurface(
        modifier = m,
        corner = 12.dp, scaleTo = 1f,
        onFocusChanged = onFocusChanged,
        onClick = onClick,
    ) { focused -> content(focused) } else Box(m) { content(false) }
}

