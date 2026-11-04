package com.lumina.tv.ui.screens

import android.os.Build
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource as ExoSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/** 长按 OK 快进的按下时刻（跨重组保持，单播放器会话内唯一） */
private var boostStartAt = 0L

@Composable
fun PlayerScreen(
    item: MediaItem,
    initialSourceId: String? = null,
    onBack: () -> Unit,
    onPlayNext: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val client = AppRuntime.client
    val scope = rememberCoroutineScope()

    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(AppRuntime.prefs?.defaultSpeed ?: 1.0f) }
    var optionMenu by remember { mutableStateOf(false) }
    var degraded by remember { mutableStateOf(false) } // 是否已自动降级到转码
    var reloadKey by remember { mutableIntStateOf(0) }
    var currentSourceId by remember { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var boosting by remember { mutableStateOf(false) } // 长按 OK 3 倍快进中
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var nextTriggered by remember { mutableStateOf(false) }

    val introSeconds = remember(item.id) { AppRuntime.prefs?.introSecondsFor(item.id) ?: 0 }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("LuminaTV/1.4 (Android)")
    }

    val exo = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply { playWhenReady = true }
    }

    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                if (client != null && position > 0) {
                    kotlinx.coroutines.GlobalScope.launch { client.reportStop(item.id, position) }
                }
            }
            exo.release()
        }
    }

    // 装载播放源：direct 失败自动降级转码；支持指定源
    fun loadSource(forceTranscode: Boolean, sourceId: String? = currentSourceId) {
        val c = client ?: return
        scope.launch {
            error = null
            buffering = true
            try {
                val target = c.resolvePlayback(item, forceTranscode, sourceId)
                currentSourceId = target.source?.id ?: sourceId
                val videoSource: ExoSource =
                    DefaultMediaSourceFactory(httpFactory).createMediaSource(
                        ExoMediaItem.Builder().setUri(target.url).build()
                    )
                // 外挂字幕合并
                val subs = target.source?.streams
                    ?.filter { it.type == "Subtitle" && it.isExternal }
                    .orEmpty()
                val srcId = target.source?.id
                var merged: ExoSource = videoSource
                if (subs.isNotEmpty() && srcId != null) {
                    val configs = subs.mapNotNull { st ->
                        val idx = st.index
                        val mime = when (st.codec?.lowercase()) {
                            "vtt" -> MimeTypes.TEXT_VTT
                            "subrip", "srt" -> MimeTypes.APPLICATION_SUBRIP
                            else -> MimeTypes.APPLICATION_SUBRIP
                        }
                        runCatching {
                            ExoMediaItem.SubtitleConfiguration.Builder(
                                android.net.Uri.parse(c.subtitleUrl(item.id, srcId, idx, "vtt"))
                            )
                                .setMimeType(mime)
                                .setLabel(st.displayTitle ?: st.language ?: "字幕${idx}")
                                .setLanguage(st.language)
                                .build()
                        }.getOrNull()
                    }
                    if (configs.isNotEmpty()) {
                        merged = MergingMediaSource(videoSource, *configs.map {
                            DefaultMediaSourceFactory(httpFactory).createMediaSource(
                                ExoMediaItem.Builder().setUri(it.uri.toString())
                                    .setSubtitleConfigurations(listOf(it)).build()
                            )
                        }.toTypedArray())
                    }
                }
                exo.setMediaSource(merged)
                exo.prepare()
                exo.seekTo(target.startPositionMs.coerceAtLeast(0))
                exo.playWhenReady = true
                exo.setPlaybackSpeed(if (boosting) 3f else speed)
                c.reportStart(item.id, target.startPositionMs, target.source?.id, if (target.direct) "DirectPlay" else "Transcode")
            } catch (e: Exception) {
                buffering = false
                error = e.message ?: "播放失败"
            }
        }
    }

    LaunchedEffect(item.id) {
        AppRuntime.screenLabel = "播放"
        AppRuntime.nowPlaying = item.seriesName?.let { "${it} · ${item.name}" } ?: item.name
        if (client == null) {
            error = "未登录"
            return@LaunchedEffect
        }
        loadSource(AppRuntime.prefs?.forceTranscode ?: false, initialSourceId)
    }

    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) loadSource(forceTranscode = true)
    }

    // 播放器事件：错误自动降级 + 缓冲状态 + 帧率匹配 + 自动连播
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: PlaybackException) {
                if (!degraded) {
                    degraded = true
                    error = "直连失败，自动切换转码…"
                    reloadKey++
                } else {
                    buffering = false
                    error = "播放失败：${err.errorCodeName}"
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = if (degraded && error?.startsWith("直连失败") == true) null else error
                if (state == Player.STATE_ENDED && !nextTriggered &&
                    AppRuntime.prefs?.autoNextEpisode == true && item.seriesId != null
                ) {
                    nextTriggered = true
                    scope.launch {
                        val c = client ?: return@launch
                        val episodes = runCatching { c.getEpisodes(item.seriesId!!, item.seasonId) }.getOrDefault(emptyList())
                        val idx = episodes.indexOfFirst { it.id == item.id }
                        val next = episodes.getOrNull(idx + 1)
                        if (next != null) {
                            runCatching { c.reportStop(item.id, 0) }
                            onPlayNext?.invoke(next)
                        }
                    }
                }
            }
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // 状态轮询 + 进度上报
    LaunchedEffect(Unit) {
        var sinceReport = 0
        while (true) {
            delay(1000)
            position = exo.currentPosition
            duration = exo.duration.coerceAtLeast(0)
            sinceReport++
            if (sinceReport >= 10 && AppRuntime.client != null && duration > 0) {
                sinceReport = 0
                AppRuntime.client?.reportProgress(item.id, position, !exo.isPlaying, currentSourceId)
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, boosting) {
        if (showControls && isPlaying && !optionMenu && !boosting) {
            delay(4500)
            showControls = false
        }
    }

    BackHandler {
        if (optionMenu) optionMenu = false
        else if (showControls) showControls = false
        else {
            scope.launch { AppRuntime.client?.reportStop(item.id, position) }
            exo.stop()
            onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (ev.type == KeyEventType.KeyDown) {
                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                // 按下：记录时刻并呼出控制栏；短按在松开时切换播放
                                boostStartAt = System.currentTimeMillis()
                                showControls = true
                            } else if (!boosting && System.currentTimeMillis() - boostStartAt > 500) {
                                // 长按 OK：3 倍速快进
                                boosting = true
                                exo.setPlaybackSpeed(3f)
                            }
                            true
                        } else if (ev.type == KeyEventType.KeyUp) {
                            if (boosting) {
                                boosting = false
                                exo.setPlaybackSpeed(speed)
                            } else if (System.currentTimeMillis() - boostStartAt < 900) {
                                if (exo.isPlaying) exo.pause() else exo.play()
                            }
                            true
                        } else false
                    }
                    Key.DirectionLeft -> { if (ev.type == KeyEventType.KeyDown) { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); showControls = true }; true }
                    Key.DirectionRight -> { if (ev.type == KeyEventType.KeyDown) { exo.seekTo(exo.currentPosition + 10_000); showControls = true }; true }
                    Key.DirectionUp, Key.DirectionDown -> { if (ev.type == KeyEventType.KeyDown) showControls = true; true }
                    Key.MediaPlayPause -> { if (ev.type == KeyEventType.KeyDown) { if (exo.isPlaying) exo.pause() else exo.play() }; true }
                    Key.MediaRewind -> { if (ev.type == KeyEventType.KeyDown) exo.seekTo((exo.currentPosition - 30_000).coerceAtLeast(0)); true }
                    Key.MediaFastForward -> { if (ev.type == KeyEventType.KeyDown) exo.seekTo(exo.currentPosition + 30_000); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exo
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true // 屏幕常亮保险
                    playerViewRef = this
                }
            },
            update = { pv ->
                pv.resizeMode = resizeMode
                // 字幕样式：字号缩放 + 背景底色
                val prefs = AppRuntime.prefs
                pv.subtitleView?.apply {
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * (prefs?.subtitleScale ?: 1.0f))
                    setBackgroundColor(if (prefs?.subtitleBg == true) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(Unit) { focus.requestFocus() }

        if (buffering && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("正在缓冲…", color = TextSub, fontSize = 12.sp)
                }
            }
        }

        error?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, color = Color(0xFFFF8A9A), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 40.dp))
            }
        }

        // 长按快进角标
        AnimatedVisibility(
            visible = boosting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text(
                "3x 快进中 »»",
                color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 跳过片头
        if (introSeconds > 0 && position > 0 && position < introSeconds * 1000L && error == null) {
            FocusSurface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 120.dp)
                    .height(40.dp).width(130.dp),
                corner = 20.dp,
                onClick = { exo.seekTo(introSeconds * 1000L) },
            ) { focused ->
                Box(
                    Modifier.fillMaxSize().background(
                        if (focused) Color(0xCC070A12) else Color(0x99070A12), RoundedCornerShape(20.dp)
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text("跳过片头 »", color = TextMain, fontSize = 13.sp) }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xD9070A12), Color(0x00070A12))))
                        .padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 30.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.seriesName ?: item.name,
                            color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (item.seriesName != null) {
                            Text(item.name, color = TextSub, fontSize = 12.sp)
                        }
                    }
                    Text(
                        "${if (degraded) "转码" else "直连"} · ${speed}x",
                        color = TextSub.copy(alpha = 0.7f), fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00070A12), Color(0xE6070A12))))
                        .padding(horizontal = 36.dp, vertical = 22.dp)
                ) {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().height(4.dp)
                                .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
                        ) {
                            val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            Box(
                                Modifier.fillMaxWidth(ratio).height(4.dp)
                                    .background(Brush.horizontalGradient(listOf(Accent, Accent2)), RoundedCornerShape(2.dp))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(formatTime(position), color = TextSub, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(duration), color = TextSub, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CtrlButton(Icons.Rounded.FastRewind, "后退 10 秒") { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)) }
                            CtrlButton(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (isPlaying) "暂停" else "播放",
                                primary = true,
                            ) { if (exo.isPlaying) exo.pause() else exo.play() }
                            CtrlButton(Icons.Rounded.FastForward, "前进 10 秒") { exo.seekTo(exo.currentPosition + 10_000) }
                            Spacer(Modifier.width(6.dp))
                            CtrlButton(Icons.Rounded.Subtitles, "字幕 / 音轨 / 倍速 / 版本") { optionMenu = true }
                            Spacer(Modifier.weight(1f))
                            Text(item.subtitle, color = TextSub.copy(alpha = 0.75f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (optionMenu) {
            OptionMenu(
                exo = exo,
                item = item,
                speed = speed,
                resizeMode = resizeMode,
                currentSourceId = currentSourceId,
                onSpeed = {
                    speed = it
                    exo.setPlaybackSpeed(it)
                },
                onResizeMode = { resizeMode = it },
                onSwitchSource = { sid ->
                    optionMenu = false
                    degraded = false
                    loadSource(AppRuntime.prefs?.forceTranscode ?: false, sid)
                },
                onRememberIntro = {
                    AppRuntime.prefs?.setIntroSeconds(item.id, (position / 1000L).toInt())
                    optionMenu = false
                },
                onClose = { optionMenu = false },
            )
        }
    }
}

/** 播放选项：倍速 / 字幕轨道 / 音轨轨道 / 画面比例 / 版本 / 片头记忆。 */
@Composable
private fun OptionMenu(
    exo: ExoPlayer,
    item: MediaItem,
    speed: Float,
    resizeMode: Int,
    currentSourceId: String?,
    onSpeed: (Float) -> Unit,
    onResizeMode: (Int) -> Unit,
    onSwitchSource: (String) -> Unit,
    onRememberIntro: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    val client = AppRuntime.client
    var sources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    LaunchedEffect(Unit) {
        sources = runCatching { client?.getMediaSources(item.id) }.getOrDefault(emptyList()).orEmpty()
    }

    val textGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    val audioGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
    var textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    var selectedTextKey: String? = null
    var selectedAudioKey: String? = null
    for (g in exo.currentTracks.groups) {
        for (i in 0 until g.length) {
            if (g.isTrackSelected(i)) {
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_TEXT -> selectedTextKey = "${g.mediaTrackGroup.id}"
                    C.TRACK_TYPE_AUDIO -> selectedAudioKey = "${g.mediaTrackGroup.id}"
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.width(620.dp), corner = 22.dp) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("播放选项", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))

                // 倍速
                MenuLabel("倍速播放")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEach { s ->
                        val active = kotlin.math.abs(speed - s) < 0.01f
                        MenuChip(
                            label = if (s == 1.0f) "1.0x" else "${s}x",
                            width = 76.dp, active = active, activeColor = Accent,
                            onClick = { onSpeed(s) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 字幕
                MenuLabel("字幕")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip(
                        label = "关闭字幕", width = 110.dp,
                        active = !textDisabled && selectedTextKey == null,
                        activeColor = Accent,
                        onClick = {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            textDisabled = true
                        },
                    )
                    textGroups.forEach { g ->
                        val key = g.mediaTrackGroup.id
                        val label = buildString {
                            val f0 = g.getTrackFormat(0)
                            append(f0.label ?: f0.language ?: "轨道")
                            if (f0.language != null) append(" (${f0.language})")
                        }
                        val active = !exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && selectedTextKey == key
                        MenuChip(
                            label = label, width = 150.dp, active = active, activeColor = Accent,
                            onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .addOverride(TrackSelectionOverride(g.mediaTrackGroup, 0))
                                    .build()
                                textDisabled = false
                            },
                        )
                    }
                }
                if (textGroups.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("当前片源没有检测到字幕轨道（外挂字幕已自动加载）", color = TextSub.copy(alpha = 0.7f), fontSize = 10.sp)
                }

                Spacer(Modifier.height(16.dp))

                // 音轨
                MenuLabel("音轨")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audioGroups.forEach { g ->
                        val key = g.mediaTrackGroup.id
                        val f0 = g.getTrackFormat(0)
                        val label = f0.label ?: f0.language ?: "音轨"
                        val active = selectedAudioKey == key
                        MenuChip(
                            label = label, width = 150.dp, active = active, activeColor = Accent,
                            onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(TrackSelectionOverride(g.mediaTrackGroup, 0))
                                    .build()
                            },
                        )
                    }
                    if (audioGroups.isEmpty()) {
                        Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
                            Text("单音轨", color = TextSub.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }

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
                            label = label, width = 110.dp,
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
                                    Modifier.fillMaxSize()
                                        .background(
                                            when {
                                                active -> Accent.copy(alpha = 0.9f)
                                                focused -> Color(0x2EFFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }, RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        s.displayLabel + if (active) "  ✓" else "",
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                        label = "记住片头到此为止", width = 180.dp, active = false,
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
    onClick: () -> Unit,
) {
    FocusSurface(
        modifier = Modifier.width(width).height(height),
        corner = 12.dp, scaleTo = 1f,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                when {
                    active -> activeColor.copy(alpha = 0.9f)
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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    FocusSurface(
        modifier = Modifier.size(if (primary) 52.dp else 44.dp),
        corner = if (primary) 26.dp else 22.dp,
        scaleTo = 1.1f,
        onClick = onClick,
    ) { focused ->
        val bg = if (primary) {
            Brush.horizontalGradient(
                listOf(
                    Accent.copy(alpha = if (focused) 1f else 0.9f),
                    Accent2.copy(alpha = if (focused) 1f else 0.9f),
                )
            )
        } else {
            SolidColor(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
        }
        Box(
            Modifier.fillMaxSize().background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = if (primary) Color(0xFF06101F) else TextMain,
                modifier = Modifier.size(if (primary) 26.dp else 20.dp),
            )
        }
    }
}
