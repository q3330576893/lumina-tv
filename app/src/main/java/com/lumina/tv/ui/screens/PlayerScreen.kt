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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource as ExoSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
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
    var degraded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var currentSourceId by remember { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var boosting by remember { mutableStateOf(false) }
    var nextTriggered by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentResolution by remember { mutableStateOf<String?>(null) }
    var subScale by remember { mutableFloatStateOf(AppRuntime.prefs?.subtitleScale ?: 1.0f) }
    var nextEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var nextCountdown by remember { mutableIntStateOf(0) }

    val introSeconds = remember(item.id) { AppRuntime.prefs?.introSecondsFor(item.id) ?: 0 }

    val hideFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    var okDownAt by remember(item.id) { mutableLongStateOf(0L) }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("LuminaTV/1.5 (Android)")
    }

    val exo = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(exo, item.id) {
        onDispose {
            exo.release()
        }
    }

    // 当前剧的集列表（切集用），优先缓存
    LaunchedEffect(item.seriesId) {
        val sid = item.seriesId ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        LibraryCache.episodesOf(sid, item.seasonId)?.let { episodes = it; return@LaunchedEffect }
        episodes = runCatching { c.getEpisodes(sid, item.seasonId) }.getOrDefault(emptyList())
        LibraryCache.putEpisodes(sid, item.seasonId, episodes)
    }

    fun loadSource(forceTranscode: Boolean, sourceId: String? = currentSourceId) {
        val c = client ?: return
        val resumePosition = if (exo.mediaItemCount > 0) {
            exo.currentPosition.coerceAtLeast(0L).let { current ->
                val total = exo.duration
                if (total > 0) current.coerceAtMost(total) else current
            }
        } else item.positionTicks / 10_000
        scope.launch {
            error = null
            buffering = true
            try {
                val target = c.resolvePlayback(item, forceTranscode, sourceId, resumePosition)
                currentSourceId = target.source?.id ?: sourceId
                currentResolution = target.source?.resolution
                    ?: if (target.direct) null else "转码"
                val videoSource: ExoSource =
                    DefaultMediaSourceFactory(httpFactory).createMediaSource(
                        androidx.media3.common.MediaItem.Builder().setUri(target.url)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(item.name)
                                    .build()
                            )
                            .build()
                    )
                val subs = target.source?.streams
                    ?.filter { it.type == "Subtitle" && it.isExternal }
                    .orEmpty()
                val srcId = target.source?.id
                var merged: ExoSource = videoSource
                if (subs.isNotEmpty() && srcId != null) {
                    val configs = subs.mapNotNull { st ->
                        val idx = st.index
                        runCatching {
                            androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
                                android.net.Uri.parse(c.subtitleUrl(item.id, srcId, idx, "vtt"))
                            )
                                .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                                .setLabel(st.displayTitle ?: st.language ?: "字幕$idx")
                                .setLanguage(st.language)
                                .build()
                        }.getOrNull()
                    }
                    if (configs.isNotEmpty()) {
                        merged = MergingMediaSource(videoSource, *configs.map {
                            DefaultMediaSourceFactory(httpFactory).createMediaSource(
                                androidx.media3.common.MediaItem.Builder().setUri(it.uri.toString())
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
        degraded = false
        reloadKey = 0
        currentSourceId = initialSourceId
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

    DisposableEffect(exo, item.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                if (!degraded) {
                    degraded = true
                    error = "直连失败，自动切换转码…"
                    reloadKey++
                } else {
                    buffering = false
                    val cause = generateSequence(err as Throwable?) { it.cause }
                        .take(4).mapNotNull { it.message?.takeIf(String::isNotBlank) }.joinToString(" · ")
                    error = buildString {
                        append("播放失败：${err.errorCodeName}")
                        if (cause.isNotBlank()) append("\n").append(cause.take(220))
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && degraded && error?.startsWith("直连失败") == true) error = null
                if (state == Player.STATE_ENDED && !nextTriggered &&
                    AppRuntime.prefs?.autoNextEpisode == true && item.seriesId != null
                ) {
                    nextTriggered = true
                    scope.launch {
                        val c = client ?: return@launch
                        val list = if (episodes.isNotEmpty()) episodes
                        else runCatching { c.getEpisodes(item.seriesId!!, item.seasonId) }.getOrDefault(emptyList())
                        val idx = list.indexOfFirst { it.id == item.id }
                        val next = list.getOrNull(idx + 1)
                        if (next != null) {
                            runCatching { c.reportStop(item.id, position) }
                            nextEpisode = next
                            nextCountdown = 5
                            while (nextCountdown > 0) {
                                delay(1000)
                                if (nextEpisode?.id != next.id) return@launch
                                nextCountdown--
                            }
                            if (nextEpisode?.id == next.id) {
                                nextEpisode = null
                                onPlayNext?.invoke(next)
                            }
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(1400)
            seekHint = null
        }
    }

    fun seekBy(delta: Long) {
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val to = (exo.currentPosition + delta).coerceIn(0L, max)
        exo.seekTo(to)
        position = to
        seekHint = if (delta > 0) "»»  快进 ${delta / 1000} 秒    ${formatTime(to)}"
        else "««  快退 ${-delta / 1000} 秒    ${formatTime(to)}"
    }

    LaunchedEffect(Unit) {
        var sinceReport = 0
        while (true) {
            delay(1000)
            position = exo.currentPosition
            duration = exo.duration.coerceAtLeast(0)
            sinceReport++
            if (sinceReport >= 10 && AppRuntime.client != null && duration > 0 && (exo.isPlaying || position > 0)) {
                sinceReport = 0
                AppRuntime.client?.reportProgress(item.id, position, !exo.isPlaying, currentSourceId)
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, boosting, optionMenu) {
        if (showControls && isPlaying && !optionMenu && !boosting) {
            delay(5000)
            showControls = false
        }
    }

    // 焦点：控制栏隐藏时由捕获层拿焦点，显示时把焦点交给播放按钮
    LaunchedEffect(showControls, optionMenu) {
        delay(80)
        if (!optionMenu) {
            if (showControls) runCatching { playFocus.requestFocus() }
            else runCatching { hideFocus.requestFocus() }
        }
    }

    BackHandler {
        when {
            optionMenu -> optionMenu = false
            showControls -> showControls = false
            else -> {
                scope.launch { AppRuntime.client?.reportStop(item.id, exo.currentPosition.coerceAtLeast(0L)) }
                exo.stop()
                onBack()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exo
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                }
            },
            update = { pv ->
                pv.resizeMode = resizeMode
                val prefs = AppRuntime.prefs
                pv.subtitleView?.apply {
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * subScale)
                    setBackgroundColor(if (prefs?.subtitleBg == true) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

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

        AnimatedVisibility(
            visible = boosting || seekHint != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text(
                if (boosting) "3x 快进中 »»" else seekHint.orEmpty(),
                color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 下一集倒计时卡片
        nextEpisode?.let { ne ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 96.dp)
                    .background(Color(0xE6070A12), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column {
                    Text(
                        "${nextCountdown} 秒后播放下一集",
                        color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "第${ne.indexNumber ?: 0}集 · ${ne.name}",
                        color = TextSub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusSurface(
                            modifier = Modifier.height(34.dp).width(96.dp),
                            corner = 17.dp, scaleTo = 1.04f,
                            onClick = {
                                scope.launch { runCatching { client?.reportStop(item.id, position) } }
                                nextEpisode = null
                                onPlayNext?.invoke(ne)
                            },
                        ) { focused ->
                            Box(
                                Modifier.fillMaxSize().background(
                                    Accent.copy(alpha = if (focused) 1f else 0.9f), RoundedCornerShape(17.dp)
                                ),
                                contentAlignment = Alignment.Center,
                            ) { Text("立即播放", color = Color(0xFF06101F), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                        FocusSurface(
                            modifier = Modifier.height(34.dp).width(80.dp),
                            corner = 17.dp, scaleTo = 1.04f,
                            onClick = { nextEpisode = null; nextCountdown = 0 },
                        ) { focused ->
                            Box(
                                Modifier.fillMaxSize().background(
                                    if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF), RoundedCornerShape(17.dp)
                                ),
                                contentAlignment = Alignment.Center,
                            ) { Text("取消", color = TextMain, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        // 隐藏控制栏时的按键捕获层：方向键 seek、OK 呼出控制栏、长按 OK 3 倍速
        if (!showControls && !optionMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(hideFocus)
                    .focusable()
                    .onKeyEvent { ev ->
                        when (ev.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                if (ev.type == KeyEventType.KeyDown) {
                                    if (ev.nativeKeyEvent.repeatCount == 0) okDownAt = System.currentTimeMillis()
                                    else if (!boosting && System.currentTimeMillis() - okDownAt > 480) {
                                        boosting = true
                                        exo.setPlaybackSpeed(3f)
                                    }
                                    true
                                } else {
                                    if (boosting) {
                                        boosting = false
                                        exo.setPlaybackSpeed(speed)
                                    } else {
                                        showControls = true
                                    }
                                    true
                                }
                            }
                            Key.DirectionLeft -> {
                                if (ev.type == KeyEventType.KeyDown) {
                                    seekBy(-10_000)
                                    showControls = true
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (ev.type == KeyEventType.KeyDown) {
                                    seekBy(10_000)
                                    showControls = true
                                }
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (ev.type == KeyEventType.KeyDown) showControls = true
                                true
                            }
                            Key.MediaPlayPause -> {
                                if (ev.type == KeyEventType.KeyDown) { if (exo.isPlaying) exo.pause() else exo.play() }
                                true
                            }
                            Key.MediaRewind -> {
                                if (ev.type == KeyEventType.KeyDown) exo.seekTo((exo.currentPosition - 30_000).coerceAtLeast(0))
                                true
                            }
                            Key.MediaFastForward -> {
                                if (ev.type == KeyEventType.KeyDown) exo.seekTo(exo.currentPosition + 30_000)
                                true
                            }
                            else -> false
                        }
                    }
            )
        }

        // 跳过片头
        if (introSeconds > 0 && position > 0 && position < introSeconds * 1000L && error == null && !showControls) {
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
            visible = showControls && !optionMenu,
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
                        listOfNotNull(
                            currentResolution?.takeIf { it.isNotBlank() },
                            if (degraded) "转码" else "直连",
                            "${speed}x",
                        ).joinToString(" · "),
                        color = TextSub.copy(alpha = 0.7f), fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00070A12), Color(0xE6070A12))))
                        .padding(horizontal = 36.dp, vertical = 18.dp)
                ) {
                    Column {
                        SeekBar(
                            position = position,
                            duration = duration,
                            onSeek = { target ->
                                exo.seekTo(target)
                                position = target
                            },
                        )
                        Spacer(Modifier.height(4.dp))
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
                                focusRequester = playFocus,
                            ) { if (exo.isPlaying) exo.pause() else exo.play() }
                            CtrlButton(Icons.Rounded.FastForward, "前进 10 秒") { exo.seekTo(exo.currentPosition + 10_000) }
                            if (item.seriesId != null) {
                                CtrlButton(Icons.Rounded.SkipNext, "下一集") {
                                    val idx = episodes.indexOfFirst { it.id == item.id }
                                    val next = episodes.getOrNull(idx + 1)
                                    if (next != null) {
                                        scope.launch { runCatching { client?.reportStop(item.id, position) } }
                                        onPlayNext?.invoke(next)
                                    }
                                }
                            }
                            CtrlButton(Icons.Rounded.Subtitles, "字幕 / 音轨 / 倍速 / 选集") {
                                optionMenu = true
                                showControls = false
                            }
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
                episodes = episodes,
                subScale = subScale,
                onSubScale = {
                    subScale = it
                    AppRuntime.prefs?.subtitleScale = it
                },
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
                onPickEpisode = { ep ->
                    optionMenu = false
                    scope.launch { runCatching { client?.reportStop(item.id, position) } }
                    onPlayNext?.invoke(ep)
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

/** 可聚焦进度条：左右键 ±15 秒，带焦点高亮。 */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionLeft -> { onSeek((position - 15_000).coerceAtLeast(0)); true }
                        Key.DirectionRight -> { onSeek((position + 15_000).coerceAtMost(duration)); true }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .background(Color(0x33FFFFFF), RoundedCornerShape(5.dp))
                .then(if (focused) Modifier.border(1.dp, Accent.copy(alpha = 0.8f), RoundedCornerShape(5.dp)) else Modifier)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(Brush.horizontalGradient(listOf(Accent, Accent2)), RoundedCornerShape(5.dp))
            )
        }
    }
}

/** 播放选项：倍速 / 字幕 / 音轨 / 画面比例 / 版本 / 选集 / 片头记忆。 */
@Composable
private fun OptionMenu(
    exo: ExoPlayer,
    item: MediaItem,
    speed: Float,
    resizeMode: Int,
    currentSourceId: String?,
    episodes: List<MediaItem>,
    subScale: Float,
    onSubScale: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
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

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.width(680.dp), corner = 22.dp) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("播放选项", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))

                // 选集
                if (episodes.isNotEmpty()) {
                    MenuLabel("选集（共 ${episodes.size} 集）")
                    Column(
                        Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        episodes.forEach { ep ->
                            val active = ep.id == item.id
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                corner = 10.dp, scaleTo = 1.0f,
                                onClick = { if (!active) onPickEpisode(ep) },
                            ) { focused ->
                                Row(
                                    Modifier.fillMaxSize().background(
                                        when {
                                            active -> Accent.copy(alpha = 0.9f)
                                            focused -> Color(0x2EFFFFFF)
                                            else -> Color(0x14FFFFFF)
                                        }, RoundedCornerShape(10.dp)
                                    ).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "第${ep.indexNumber ?: 0}集 · ${ep.name}",
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // 倍速
                MenuLabel("倍速播放")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEach { s ->
                        val active = kotlin.math.abs(speed - s) < 0.01f
                        MenuChip(
                            label = if (s == 1.0f) "1.0x" else "${s}x",
                            width = 78.dp, active = active, activeColor = Accent,
                            onClick = { onSpeed(s) },
                            focusRequester = if (s == 1.0f) firstFocus else null,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 字幕
                MenuLabel("字幕")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip(
                        label = "关闭字幕", width = 104.dp,
                        active = textDisabled,
                        activeColor = Accent,
                        onClick = {
                            textDisabled = true
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
                    listOf(0.85f to "小", 1.0f to "标准", 1.25f to "大", 1.5f to "特大").forEach { (s, label) ->
                        MenuChip(
                            label = label, width = 84.dp,
                            active = kotlin.math.abs(subScale - s) < 0.01f,
                            activeColor = Accent,
                            onClick = { onSubScale(s) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 音轨
                MenuLabel("音轨")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audioGroups.forEach { g ->
                      for (trackIndex in 0 until g.length) {
                        val key = g.mediaTrackGroup.id
                        val f0 = g.getTrackFormat(trackIndex)
                        val label = listOfNotNull(f0.label ?: f0.language ?: "音轨", f0.language, f0.sampleMimeType?.substringAfter('/')?.uppercase()).distinct().joinToString(" · ")
                        MenuChip(
                            label = label, width = 148.dp,
                            active = selectedAudio == (key to trackIndex),
                            activeColor = Accent,
                            onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(TrackSelectionOverride(g.mediaTrackGroup, trackIndex))
                                    .build()
                            },
                        )
                      }
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
    onClick: () -> Unit,
) {
    var m = Modifier.width(width).height(height)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    FocusSurface(
        modifier = m,
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
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var m = Modifier.size(if (primary) 52.dp else 44.dp)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    FocusSurface(
        modifier = m,
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
