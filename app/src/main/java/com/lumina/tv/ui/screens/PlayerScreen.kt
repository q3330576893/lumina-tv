package com.lumina.tv.ui.screens

import android.os.Build
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource as ExoSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.PlaybackCache
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.DeviceCodecCapabilities
import com.lumina.tv.data.MediaChapter
import com.lumina.tv.data.PlaybackMarkers
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.MediaSource
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.player.SubtitleRenderersFactory
import com.lumina.tv.player.MpvPlayerController
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.player.PlaybackErrorCategory
import com.lumina.tv.player.PlaybackRangeProbe
import com.lumina.tv.player.classifyPlaybackError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private inline fun CoroutineScope.safeLaunch(crossinline block: suspend () -> Unit) =
    launch {
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("LuminaTV", "Async player operation failed", e) }
    }

private fun playbackNetworkMessage(error: Throwable): String {
    val causeChain = generateSequence(error) { it.cause }.toList()
    val message = causeChain.joinToString(" ") { it.message.orEmpty() }
    val status = causeChain.filterIsInstance<com.lumina.tv.data.EmbyException>().firstNotNullOfOrNull { it.httpStatus }
    return when {
        status == 401 || status == 403 || message.contains("HTTP 401") || message.contains("HTTP 403") -> "登录已失效，请重新登录"
        status != null && status >= 500 -> "服务器错误（$status）"
        causeChain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } ||
            message.contains("connection reset", true) || message.contains("timeout", true) || message.contains("timed out", true) -> "网络中断，请检查连接后重试"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "播放请求失败，请重试"
    }
}


private fun media3AudioTracks(tracks: Tracks): List<MediaStream> = tracks.groups
    .filter { it.type == C.TRACK_TYPE_AUDIO }
    .flatMap { group ->
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            MediaStream(
                index = format.id?.toIntOrNull() ?: index,
                type = "Audio",
                codec = format.sampleMimeType?.substringAfter('/') ?: format.codecs,
                language = format.language,
                title = format.label,
                isDefault = group.isTrackSelected(index),
                isExternal = false,
                displayTitle = format.label,
                channels = format.channelCount.takeIf { it > 0 },
                sampleRate = format.sampleRate.takeIf { it > 0 },
            )
        }
    }

@Composable
fun PlayerScreen(
    item: MediaItem,
    initialSourceId: String? = null,
    onBack: () -> Unit,
    onPlayNext: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val client = AppRuntime.client
    val playerExceptionHandler = remember { CoroutineExceptionHandler { _, error -> android.util.Log.e("LuminaTV", "Player coroutine failed", error) } }
    val scope = rememberCoroutineScope { playerExceptionHandler }

    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var estimatedBitrate by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(AppRuntime.prefs?.defaultSpeed ?: 1.0f) }
    var optionMenu by remember { mutableStateOf(false) }
    var degraded by remember { mutableStateOf(false) }
    var audioDecodeMode by remember { mutableStateOf(AppRuntime.prefs?.audioDecodeMode ?: "auto") }
    var audioDecodeNotice by remember { mutableStateOf<String?>(null) }
    var softDecodeNoticeShown by remember(item.id) { mutableStateOf(false) }
    var mpvFallbackTried by remember(item.id) { mutableStateOf(false) }
    var ffmpegFallback by remember(item.id) { mutableStateOf(false) }
    // 用户手动选过内核、或已确认 media3 解不了时，把内核钉住，后续重载不再强行弹回 media3
    var enginePinned by remember(item.id) { mutableStateOf<String?>(null) }
    var activeEngine by remember(item.id) {
        // media3 永远是默认内核；只有用户在设置里显式选了 mpv 才直接用 mpv。
        // 音频档位（auto/soft/passthrough）只影响解码策略，不再有强制切内核的权力。
        mutableStateOf(if (AppRuntime.prefs?.playerEngine == "mpv") "mpv" else "media3")
    }
    var mpvBackend by remember(item.id) { mutableStateOf<MpvPlayerController?>(null) }
    var mpvState by remember(item.id) { mutableStateOf(MpvPlayerController.State()) }
    var currentSurface by remember(item.id) { mutableStateOf<Surface?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // 转码流（HLS）解析失败时改拉原文件直连
    var forceDirectNext by remember(item.id) { mutableStateOf(false) }
    var directFallbackTried by remember(item.id) { mutableStateOf(false) }
    var badHttpFallbackStage by remember(item.id) { mutableIntStateOf(0) }
    var manualAudioStreamSelection by remember(item.id) { mutableStateOf(false) }
    var transcodeFallbackTried by remember(item.id) { mutableStateOf(false) }
    // 同一部片可能有多个版本（不同压制/大小），一个播不了就换下一个
    var mediaSources by remember(item.id) { mutableStateOf<List<MediaSource>>(emptyList()) }
    var triedSources by remember(item.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(item.id) {
        mediaSources = runCatching { AppRuntime.client?.getMediaSources(item.id) }.getOrDefault(emptyList()).orEmpty()
    }
    var reloadForceTranscode by remember { mutableStateOf(true) }
    var playbackSource by remember(item.id) { mutableStateOf<MediaSource?>(null) }
    var noAudioRetryTried by remember(item.id) { mutableStateOf(false) }
    var bufferingTimedOut by remember(item.id) { mutableStateOf(false) }
    var showDiagExport by remember { mutableStateOf(false) }
    var showMpvConfirm by remember { mutableStateOf(false) }
    var playStartAt by remember(item.id) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var engineSwitchTrace by remember(item.id) { mutableStateOf(mutableListOf<String>()) }
    var currentPlaybackUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var currentPlayMethod by remember(item.id) { mutableStateOf("DirectPlay") }
    var negotiationDiagnostics by remember(item.id) { mutableStateOf(JSONObject()) }
    var rangeProbeDiagnostics by remember(item.id) { mutableStateOf(JSONObject()) }
    var rangeProbeJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var networkRetryJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var networkRetryCount by remember(item.id) { mutableIntStateOf(0) }
    var sessionGeneration by remember(item.id) { mutableIntStateOf(0) }
    var loadJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var phaseTimes by remember(item.id) { mutableStateOf(JSONObject()) }
    var droppedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var renderedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingCount by remember(item.id) { mutableIntStateOf(0) }
    var bufferingDurationMs by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var lastPlaybackErrorJson by remember(item.id) { mutableStateOf<String?>(null) }
    var dvDecoderPath by remember(item.id) { mutableStateOf<String?>(null) }
    var activeVideoDecoder by remember(item.id) { mutableStateOf<String?>(null) }
    var firstFrameRendered by remember(item.id) { mutableStateOf(false) }
    var readyWithoutFrameSince by remember(item.id) { mutableLongStateOf(0L) }
    var playbackStatusNotice by remember(item.id) { mutableStateOf<String?>(null) }
    var failurePhase by remember(item.id) { mutableStateOf("未知") }
    var currentSourceId by remember { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var boosting by remember { mutableStateOf(false) }
    var nextTriggered by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var prewarmedEpisodeId by remember(item.id) { mutableStateOf<String?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentResolution by remember { mutableStateOf<String?>(null) }
    var subScale by remember { mutableFloatStateOf(when (AppRuntime.prefs?.subtitleScale) { null -> 1.3f; 1.5f -> 2.0f; else -> AppRuntime.prefs?.subtitleScale ?: 1.3f }) }
    var subtitleOffsetMs by remember { mutableLongStateOf(AppRuntime.prefs?.subtitleOffsetMs ?: 0L) }
    var nextEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var nextCountdown by remember { mutableIntStateOf(0) }
    var seekHoldDirection by remember { mutableIntStateOf(0) }
    var seekHoldActive by remember { mutableStateOf(false) }
    var seekHoldDelta by remember { mutableLongStateOf(0L) }
    var seekHoldJob by remember { mutableStateOf<Job?>(null) }
    var chapters by remember { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var playbackMarkers by remember(item.id) { mutableStateOf<PlaybackMarkers?>(null) }
    var chapterPanel by remember { mutableStateOf(false) }
    var previewTargetMs by remember(item.id) { mutableStateOf<Long?>(null) }
    var previewFrame by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    var previewActive by remember(item.id) { mutableStateOf(false) }
    var previewDisabled by remember(item.id) { mutableStateOf(false) }
    var lastHoldPreviewAt by remember(item.id) { mutableLongStateOf(0L) }
    var subtitleSelectionApplied by remember(item.id) { mutableStateOf(false) }

    val introSeconds = playbackMarkers?.introEndMs?.takeIf { it > 0L && (duration <= 0L || it < duration) }
        ?.div(1000L)?.toInt()
        ?: (AppRuntime.prefs?.introSecondsFor(item.id) ?: 0)
    val creditsStartMs = playbackMarkers?.creditsStartMs
    val currentCreditsStartMs by rememberUpdatedState(creditsStartMs)

    // 片名优先用透明 Logo：电影取自身，剧集取详情页缓存的剧集信息（通常已命中）
    val titleLogo = remember(item.id) {
        val c = AppRuntime.client
        val own = c?.logoUrl(item)
        if (own != null) own
        else item.seriesId?.let { sid -> LibraryCache.detailStale(sid)?.item?.let { c?.logoUrl(it) } }
    }

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

    val subtitleRenderersFactory = remember(item.id) { SubtitleRenderersFactory(context) }
    val fastStart = AppRuntime.prefs?.fastStart != false

    val exo = remember(item.id, ffmpegFallback, fastStart) {
        val preference = AppRuntime.prefs?.decoderPreference ?: "auto"
        val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            val dvMime = mimeType.equals("video/dolby-vision", ignoreCase = true)
            val dvDecoders = if (dvMime) {
                MediaCodecSelector.DEFAULT.getDecoderInfos("video/dolby-vision", secure, tunneling)
            } else emptyList()
            val usedFallback = dvMime && dvDecoders.isEmpty()
            val decoders = when {
                dvMime && dvDecoders.isNotEmpty() -> dvDecoders
                dvMime -> MediaCodecSelector.DEFAULT.getDecoderInfos("video/hevc", secure, tunneling)
                else -> MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
            if (dvMime) dvDecoderPath = if (usedFallback) "hevc-fallback" else "dolby-vision"
            when (preference) {
                "hardware" -> decoders.sortedByDescending { it.hardwareAccelerated }
                "software" -> decoders.sortedByDescending { it.softwareOnly }
                else -> decoders
            }
        }
        subtitleRenderersFactory.subtitleOffsetMs = subtitleOffsetMs
        val renderers = subtitleRenderersFactory
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
        val playerBuilder = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        if (fastStart) {
            playerBuilder.setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,
                    50_000,
                    1_000,
                    2_000,
                )
                .build())
        }
        playerBuilder
            .build()
            .apply {
                playWhenReady = true
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguages("zh", "chi", "zho")
                    .build()
            }
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

    LaunchedEffect(item.id) {
        chapters = runCatching { client?.getChapters(item.id) }.getOrNull().orEmpty()
        playbackMarkers = runCatching { client?.getPlaybackMarkers(item.id) }.getOrNull()
    }

    LaunchedEffect(item.id, previewTargetMs, previewActive) {
        val target = previewTargetMs ?: return@LaunchedEffect
        if (!previewActive || previewDisabled || client == null) return@LaunchedEffect
        delay(160)
        previewFrame = runCatching { client.getTrickplayFrame(item.id, target) }.getOrNull()
    }

    fun startAutoNext() {
        if (nextTriggered || AppRuntime.prefs?.autoNextEpisode != true || item.seriesId == null) return
        nextTriggered = true
        loadJob?.cancel()
        networkRetryJob?.cancel()
        rangeProbeJob?.cancel()
        val generation = ++sessionGeneration
        playStartAt = android.os.SystemClock.elapsedRealtime()
        phaseTimes = JSONObject().put("session", generation).put("decoderInitMs", JSONObject.NULL)
            .put("compatMappingMs", JSONObject.NULL).put("firstFrameMs", JSONObject.NULL).put("stableOutputMs", JSONObject.NULL)
        loadJob = scope.launch {
            val c = client ?: return@launch
            val next = runCatching { c.getNextEpisode(item) }.getOrNull() ?: return@launch
            runCatching { c.reportStop(item.id, position) }
            nextEpisode = next
            nextCountdown = ((AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) / 10).coerceIn(1, 6)
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

    fun loadSource(
        forceTranscode: Boolean,
        sourceId: String? = currentSourceId,
        audioStreamIndex: Int? = AppRuntime.prefs?.audioTrackFor(item.id),
    ) {
        val c = client ?: return
        val resumePosition = when (activeEngine) {
            "mpv" -> mpvBackend?.positionMs()?.takeIf { it > 0 } ?: position.takeIf { it > 0 } ?: item.positionTicks / 10_000
            else -> if (exo.mediaItemCount > 0) {
            exo.currentPosition.coerceAtLeast(0L).let { current ->
                val total = exo.duration
                if (total > 0) current.coerceAtMost(total) else current
            }
            } else position.takeIf { it > 0 } ?: item.positionTicks / 10_000
        }
        loadJob?.cancel()
        networkRetryJob?.cancel()
        rangeProbeJob?.cancel()
        val generation = ++sessionGeneration
        playStartAt = android.os.SystemClock.elapsedRealtime()
        phaseTimes = JSONObject().put("session", generation)
        loadJob = scope.launch {
            error = null
            buffering = true
            firstFrameRendered = false
            readyWithoutFrameSince = 0L
            playbackStatusNotice = null
            bufferingTimedOut = false
            activeVideoDecoder = null
            dvDecoderPath = null
            failurePhase = "解析"
            try {
                if (forceTranscode) transcodeFallbackTried = true
                val maxStreamingBitrate = AppRuntime.prefs?.maxStreamingBitrate ?: 120_000_000L
                val cachedTarget = if (badHttpFallbackStage == 0) c.cachedPlaybackTarget(item.id, forceTranscode, sourceId, audioStreamIndex, forceDirectNext, maxStreamingBitrate)
                    ?.copy(startPositionMs = resumePosition)
                    else null
                val resolvedTarget = cachedTarget ?: c.resolvePlayback(
                        item, forceTranscode, sourceId, resumePosition, audioStreamIndex,
                        forceDirect = forceDirectNext,
                        removeSubtitleParams = badHttpFallbackStage == 1,
                        preferDirectStreamUrl = badHttpFallbackStage == 2,
                        manualAudioStreamSelection = manualAudioStreamSelection,
                        maxStreamingBitrate = maxStreamingBitrate,
                    )
                val target = resolvedTarget
                if (generation != sessionGeneration) return@launch
                phaseTimes.put("compatMappingMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
                negotiationDiagnostics = target.negotiationDiagnostics
                currentPlayMethod = target.playMethod
                failurePhase = "连接"
                currentSourceId = target.source?.id ?: sourceId
                playbackSource = target.source
                val sourceVideoRange = target.source?.streams?.firstOrNull { it.type == "Video" }?.videoRangeType.orEmpty().uppercase()
                AppRuntime.currentVideoRange = when {
                    sourceVideoRange.contains("DOVI") || sourceVideoRange.contains("DOLBY") || sourceVideoRange == "DV" -> "DV"
                    sourceVideoRange.contains("HLG") -> "HLG"
                    sourceVideoRange.contains("HDR10") || sourceVideoRange.contains("HDR") -> "HDR10"
                    else -> "SDR"
                }
                currentPlaybackUrl = target.url
                rangeProbeDiagnostics = JSONObject().put("state", "pending")
                val targetIsHls = target.url.contains("master.m3u8", true) || target.url.contains(".m3u8", true)
                if (targetIsHls) transcodeFallbackTried = true
                rangeProbeJob = scope.launch {
                    val probeResult = runCatching { PlaybackRangeProbe.probe(target.url, target.headers) }
                        .getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
                    if (generation == sessionGeneration) {
                        rangeProbeDiagnostics = probeResult
                        phaseTimes.put("rangeProbe", probeResult)
                    }
                }
                if (generation != sessionGeneration) return@launch
                val defaultAudio = target.source?.audioStreams?.firstOrNull { it.isDefault }
                    ?: target.source?.audioStreams?.firstOrNull()
                val audioCodec = listOfNotNull(defaultAudio?.codec, defaultAudio?.profile).joinToString(" ").lowercase()
                val unsupportedAudio = listOf("truehd", "dts", "dca", "mlp", "atmos", "eac3").any(audioCodec::contains)
                // 统一走 media3 优先：不再因为音轨编码就提前把内核换成 mpv，
                // 由 media3 真正解码失败后再自动回落，避免“什么都用 MPV 转圈”。
                // DV Profile 7 是双层封装，media3 解不了基底+增强层，直接交给 mpv
                val videoStreamForDv = target.source?.streams?.firstOrNull { it.type == "Video" }
                val rangeType = videoStreamForDv?.videoRangeType.orEmpty().uppercase()
                val isDolbyVision = rangeType.contains("DOVI") || videoStreamForDv?.dvProfile != null
                val isDvP7 = isDolbyVision &&
                    (videoStreamForDv?.dvProfile == 7 || rangeType.contains("WITHEL") || rangeType.contains("WITH EL"))
                val selectedEngine = enginePinned
                    ?: when {
                        isDvP7 -> "mpv"
                        isDolbyVision -> "media3"
                        AppRuntime.prefs?.playerEngine == "mpv" -> "mpv"
                        else -> "media3"
                    }
                if (isDvP7 && !softDecodeNoticeShown) {
                    audioDecodeNotice = "Dolby Vision Profile 7，已用 mpv 软解"
                    softDecodeNoticeShown = true
                }
                if (isDolbyVision && !isDvP7 && !DeviceCodecCapabilities.supportsDolbyVision) {
                    dvDecoderPath = "hevc-fallback"
                }
                if (audioDecodeMode == "auto" && unsupportedAudio && !softDecodeNoticeShown) {
                    audioDecodeNotice = "检测到 ${audioCodec.trim().ifBlank { "高级" }} 音轨，先试本机硬解，失败会自动转本地软解"
                    softDecodeNoticeShown = true
                }
                if (activeEngine != selectedEngine) {
                    activeEngine = selectedEngine
                    return@launch
                }
                currentResolution = target.source?.resolution
                    ?: if (target.direct) null else "转码"
                // 透传 STRM 的 Referer/User-Agent 等请求头
                if (target.headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(target.headers)
                val cacheLimit = AppRuntime.prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L
                PlaybackCache.setLimit(cacheLimit)
                val upstreamFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(12_000)
                    .setReadTimeoutMs(30_000)
                    .setUserAgent("LuminaTV/1.5 (Android)")
                    .setDefaultRequestProperties(target.headers)
                val cacheFactory = if (cacheLimit > 0L) PlaybackCache.dataSourceFactory(context, upstreamFactory) else null
                val sourceFactory = cacheFactory?.let { DefaultMediaSourceFactory(it) } ?: DefaultMediaSourceFactory(httpFactory)
                val customKey = if (targetIsHls) null else PlaybackCache.mediaKey(
                    AppRuntime.session?.baseUrl ?: AppRuntime.prefs?.baseUrl.orEmpty(),
                    AppRuntime.session?.userId ?: AppRuntime.prefs?.userId.orEmpty(),
                    item.id,
                    target.source?.id,
                )
                val videoSource: ExoSource =
                    sourceFactory.createMediaSource(
                        androidx.media3.common.MediaItem.Builder().setUri(target.url)
                            .apply { if (customKey != null) setCustomCacheKey(customKey) }
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
                if (activeEngine == "mpv") {
                    val backend = mpvBackend ?: MpvPlayerController(context) { state ->
                        mpvState = state
                        position = state.positionMs
                        if (state.durationMs > 0L) duration = state.durationMs
                        buffering = state.buffering
                        isPlaying = state.playing
                        if (state.ended) startAutoNext()
                        if (state.error != null) {
                            if (state.error.contains("不支持 mpv")) {
                                AppRuntime.prefs?.mpvBroken = true
                                AppRuntime.prefs?.mpvUnavailableReason = state.error
                            }
                            error = if (AppRuntime.prefs?.playerEngine == "auto") {
                                "该设备不支持 mpv 内核，已回退 Media3"
                            } else {
                                "mpv 内核不可用（${state.error}），已临时回退 Media3，可在设置里重选"
                            }
                            mpvBackend?.release("mpv-state-error")
                            mpvBackend = null
                            degraded = true
                            ffmpegFallback = true
                            activeEngine = "media3"
                            reloadKey++
                        }
                    }.also { mpvBackend = it }
                    val subtitleSp = when { subScale >= 2.3f -> 38.4f; subScale >= 1.9f -> 32f; subScale >= 1.5f -> 25.6f; subScale >= 1.2f -> 20.8f; else -> 16f }
                    backend.setSubtitleFontSize((subtitleSp * context.resources.displayMetrics.density).toInt())
                    backend.setAudioTrack(audioStreamIndex ?: defaultAudio?.index ?: -1)
                    val unavailable = backend.unavailableReason
                    if (unavailable != null) {
                        AppRuntime.prefs?.mpvBroken = true
                        AppRuntime.prefs?.mpvUnavailableReason = unavailable
                        mpvBackend = null
                        runCatching { backend.release("mpv-unavailable") }
                        degraded = true
                        ffmpegFallback = true
                        error = if (AppRuntime.prefs?.playerEngine == "auto") {
                            "该设备不支持 mpv 内核，已回退 Media3"
                        } else {
                            "mpv 内核不可用（$unavailable），已临时回退 Media3，可在设置里重选"
                        }
                        activeEngine = "media3"
                        reloadKey++
                        return@launch
                    }
                    var waitedMs = 0
                    while (currentSurface?.isValid != true && waitedMs < 1_500) {
                        delay(50)
                        waitedMs += 50
                    }
                    val surface = currentSurface?.takeIf { it.isValid }
                    if (surface == null) {
                        backend.release("surface-not-ready")
                        mpvBackend = null
                        error = "播放画面未就绪（Surface 未创建），已回退 Media3"
                        degraded = true
                        ffmpegFallback = true
                        activeEngine = "media3"
                        reloadKey++
                        return@launch
                    }
                    if (generation != sessionGeneration) return@launch
                    backend.attachSurface(surface)
                    val externalSubtitles = subs.mapNotNull { stream ->
                        srcId?.let { c.subtitleUrl(item.id, it, stream.index, "vtt") }
                    }
                    backend.setSpeed(if (boosting) 3f else speed)
                    if (generation != sessionGeneration) return@launch
                    backend.load(target.url, target.startPositionMs, externalSubtitles, target.headers)
                } else {
                    exo.setMediaSource(merged)
                    exo.prepare()
                    exo.seekTo(target.startPositionMs.coerceAtLeast(0))
                    exo.playWhenReady = true
                    exo.setPlaybackSpeed(if (boosting) 3f else speed)
                }
                if (generation == sessionGeneration) {
                    try { c.reportStart(item.id, target.startPositionMs, target.source?.id, target.playMethod) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { android.util.Log.w("LuminaTV", "Playback start report failed", e) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != sessionGeneration) return@launch
                buffering = false
                if (failurePhase == "未知") failurePhase = "解析"
                val reason = playbackNetworkMessage(e)
                if (activeEngine == "mpv") {
                    mpvBackend?.release("playback-start-failed: ${e.javaClass.simpleName}")
                    mpvBackend = null
                    error = "mpv 初始化失败，正在回退 Media3：$reason"
                    activeEngine = "media3"
                    degraded = true
                    ffmpegFallback = true
                    reloadKey++
                } else {
                    error = reason
                }
            }
        }
    }

    fun buildDiagPayload(outcome: String): DiagPayload {
        return buildPlayerDiagPayload(
            context, outcome, activeEngine, mpvBackend, item, playbackSource, currentPlaybackUrl,
            audioDecodeMode, degraded, engineSwitchTrace, exo, failurePhase, lastPlaybackErrorJson,
            mpvState, client?.lastPlaybackInfoJson, showControls, buffering, error, position,
            activeVideoDecoder, dvDecoderPath, playbackStatusNotice,
            negotiationDiagnostics, rangeProbeDiagnostics, phaseTimes, droppedFrames, renderedFrames, bufferingCount, bufferingDurationMs,
            currentSourceId, currentPlayMethod, duration,
        )
    }

    LaunchedEffect(item.id) {
        degraded = false
        reloadKey = 0
        currentSourceId = initialSourceId
        AppRuntime.screenLabel = "播放"
        AppRuntime.nowPlaying = item.seriesName?.let { "${it} · ${item.name}" } ?: item.name
        AppRuntime.currentVideoRange = "SDR"
        AppRuntime.playingItemId = item.id
        if (client == null) {
            error = "未登录"
            return@LaunchedEffect
        }
        if (activeEngine == "media3") loadSource(AppRuntime.prefs?.forceTranscode ?: false, initialSourceId)
    }
    LaunchedEffect(activeEngine) {
        AppRuntime.activePlayerEngine = activeEngine
        if (activeEngine != "media3") {
            networkRetryJob?.cancel()
            networkRetryJob = null
            playbackStatusNotice = null
        }
    }

    LaunchedEffect(exo, mpvBackend, subtitleOffsetMs) {
        subtitleRenderersFactory.subtitleOffsetMs = subtitleOffsetMs
        mpvBackend?.setSubtitleOffset(subtitleOffsetMs)
        AppRuntime.prefs?.subtitleOffsetMs = subtitleOffsetMs
    }

    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            loadSource(forceTranscode = reloadForceTranscode)
            reloadForceTranscode = true
        }
    }

    LaunchedEffect(activeEngine) {
        when (activeEngine) {
            "mpv" -> {
                exo.pause()
                loadSource(forceTranscode = false)
            }
            "media3" -> if (mpvBackend != null) {
                mpvBackend?.pause()
                loadSource(forceTranscode = ffmpegFallback)
            }
        }
    }

    fun seekPlayerTo(targetMs: Long) {
        when (activeEngine) {
            "mpv" -> mpvBackend?.seekTo(targetMs)
            else -> exo.seekTo(targetMs)
        }
        position = targetMs.coerceAtLeast(0L)
        previewFrame = null
        previewTargetMs = null
    }

    fun togglePlayback() {
        when (activeEngine) {
            "mpv" -> if (mpvState.playing) mpvBackend?.pause() else mpvBackend?.play()
            else -> if (exo.isPlaying) exo.pause() else exo.play()
        }
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        when (activeEngine) {
            "mpv" -> mpvBackend?.setSpeed(value)
            else -> exo.setPlaybackSpeed(value)
        }
    }

    // mpv 侧的错误只存在 State 里，不显示出来就会一直黑屏，这里兜底提示
    LaunchedEffect(activeEngine, mpvState.error) {
        val mpvErr = mpvState.error
        if (activeEngine == "mpv" && !mpvErr.isNullOrBlank()) {
            buffering = false
            failurePhase = if (mpvErr.contains("首帧") || mpvErr.contains("出画")) "首帧" else "解码"
            error = "mpv 内核也无法播放：${mpvErr.take(160)}\n可导出诊断包反馈。"
        }
    }

    DisposableEffect(exo, item.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                if (activeEngine != "media3") return
                val errorClass = classifyPlaybackError(err)
                android.util.Log.e("LuminaTV", "Media3 playback failed code=${err.errorCode}", err)
                failurePhase = when {
                    errorClass.category == PlaybackErrorCategory.NETWORK_TRANSIENT || errorClass.category == PlaybackErrorCategory.HTTP_PERMISSION -> "连接"
                    errorClass.category == PlaybackErrorCategory.PARSING -> "解封装"
                    errorClass.category == PlaybackErrorCategory.DECODING -> "解码"
                    else -> "未知"
                }
                lastPlaybackErrorJson = JSONObject()
                    .put("engine", activeEngine)
                    .put("errorCodeName", err.errorCodeName)
                    .put("errorCode", err.errorCode)
                    .put("category", errorClass.category.name)
                    .put("message", err.message)
                    .put("decoderName", activeVideoDecoder ?: JSONObject.NULL)
                    .put("dolbyVision", playbackSource?.streams?.firstOrNull { it.type == "Video" }?.let {
                        it.videoRangeType.orEmpty().contains("DOVI", true) || it.dvProfile != null
                    } ?: false)
                    .put("dvProfile", playbackSource?.streams?.firstOrNull { it.type == "Video" }?.dvProfile ?: JSONObject.NULL)
                    .put("videoRangeType", playbackSource?.streams?.firstOrNull { it.type == "Video" }?.videoRangeType ?: JSONObject.NULL)
                    .put("decoderPath", dvDecoderPath ?: JSONObject.NULL)
                    .put("causes", JSONArray().apply {
                        generateSequence(err as Throwable?) { it.cause }
                            .forEach { put(it.message ?: it::class.java.name) }
                    })
                    .put("exceptionChain", android.util.Log.getStackTraceString(err))
                    .toString(2)
                val containerUnsupported = errorClass.category == PlaybackErrorCategory.PARSING
                val decodingUnsupported = errorClass.category == PlaybackErrorCategory.DECODING
                val currentIsHls = currentPlaybackUrl?.let {
                    it.contains("master.m3u8", true) || it.contains(".m3u8", true)
                } == true
                val isBadHttpStatus = err.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    generateSequence(err as Throwable?) { it.cause }
                        .any { (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 400 }
                if (errorClass.category == PlaybackErrorCategory.NETWORK_TRANSIENT && networkRetryCount < 2) {
                    val retryIndex = networkRetryCount++
                    val generation = sessionGeneration
                    networkRetryJob?.cancel()
                    buffering = true
                    error = null
                    playbackStatusNotice = "网络波动，${retryIndex + 1}/2 秒后重试"
                    networkRetryJob = scope.launch {
                        delay(if (retryIndex == 0) 1_000L else 3_000L)
                        if (generation == sessionGeneration && activeEngine == "media3") {
                            playbackStatusNotice = null
                            buffering = true
                            exo.prepare()
                        }
                    }
                } else if (errorClass.category == PlaybackErrorCategory.HTTP_PERMISSION) {
                    buffering = false
                    error = "媒体服务器拒绝了播放请求（权限或认证失败）"
                } else if (isBadHttpStatus && badHttpFallbackStage < 2) {
                    badHttpFallbackStage++
                    buffering = true
                    error = null
                    audioDecodeNotice = if (badHttpFallbackStage == 1) "HTTP 400，正在尝试移除字幕转码参数" else "字幕参数回退失败，正在尝试服务器直连地址"
                    engineSwitchTrace.add("http-400-fallback:$badHttpFallbackStage")
                    reloadKey++
                } else if (audioDecodeMode == "passthrough" && decodingUnsupported) {
                    buffering = false
                    audioDecodeNotice = "设备不支持该音轨直通，建议改回自动"
                    error = "设备不支持该音轨直通，建议改回自动"
                } else if (audioDecodeMode != "passthrough" && decodingUnsupported && !mpvFallbackTried) {
                    buffering = false
                    mpvFallbackTried = true
                    audioDecodeNotice = "本机解码不支持该音轨，已自动切到 mpv 本地软解"
                    enginePinned = "mpv"
                    activeEngine = "mpv"
                } else if (containerUnsupported && currentIsHls && !directFallbackTried) {
                    directFallbackTried = true
                    forceDirectNext = true
                    degraded = false
                    enginePinned = null
                    reloadForceTranscode = false
                    buffering = true
                    error = null
                    audioDecodeNotice = "转码流不可用，已改用直连播放"
                    engineSwitchTrace.add("direct-fallback:${err.errorCodeName}")
                    reloadKey++
                } else if (containerUnsupported && currentIsHls) {
                    buffering = false
                    error = "该片源无法播放（转码流与直连都失败）"
                    engineSwitchTrace.add("container-terminal:hls:${err.errorCodeName}")
                } else if (containerUnsupported && !currentIsHls && !transcodeFallbackTried) {
                    transcodeFallbackTried = true
                    forceDirectNext = false
                    degraded = true
                    enginePinned = "media3"
                    reloadForceTranscode = true
                    buffering = true
                    error = null
                    audioDecodeNotice = "直连容器无法解析，正在尝试转码"
                    engineSwitchTrace.add("transcode-fallback:${err.errorCodeName}")
                    reloadKey++
                } else if (containerUnsupported) {
                    buffering = false
                    error = "该片源容器不受支持，转码后仍失败"
                    engineSwitchTrace.add("container-terminal:direct:${err.errorCodeName}")
                } else if (!degraded) {
                    degraded = true
                    transcodeFallbackTried = true
                    reloadForceTranscode = true
                    error = "直连失败，自动切换转码…"
                    reloadKey++
                } else if (!ffmpegFallback && !containerUnsupported) {
                    error = "媒体服务器转码流失败，重试转码路径…"
                    ffmpegFallback = true
                    reloadForceTranscode = true
                    engineSwitchTrace.add("server-transcode-retry:${err.errorCodeName}")
                    reloadKey++
                } else if (mediaSources.size > 1 &&
                    mediaSources.any { it.id != currentSourceId && it.id !in triedSources }
                ) {
                    val tried = triedSources + setOfNotNull(currentSourceId)
                    val next = mediaSources.first { it.id != currentSourceId && it.id !in tried }
                    triedSources = tried + next.id
                    currentSourceId = next.id
                    directFallbackTried = false
                    transcodeFallbackTried = false
                    degraded = false
                    forceDirectNext = false
                    enginePinned = null
                    reloadForceTranscode = false
                    buffering = true
                    error = null
                    playbackSource = next
                    audioDecodeNotice = "当前版本播不了，换「${next.displayLabel}」再试"
                    engineSwitchTrace.add("source-fallback:${next.id}:${err.errorCodeName}")
                    reloadKey++
                } else if (decodingUnsupported && !mpvFallbackTried) {
                    mpvFallbackTried = true
                    enginePinned = "mpv"
                    buffering = true
                    error = null
                    val dvPathMessage = if (playbackSource?.streams?.firstOrNull { it.type == "Video" }?.let {
                            it.videoRangeType.orEmpty().contains("DOVI", true) || it.dvProfile != null
                        } == true) "；DV 解码路径=${dvDecoderPath ?: "待确认"}" else ""
                    audioDecodeNotice = "Media3 无法播放该文件（${err.errorCodeName}$dvPathMessage），已自动切换 mpv"
                    engineSwitchTrace.add("mpv-auto-fallback:${err.errorCodeName}")
                    activeEngine = "mpv"
                } else {
                    buffering = false
                    val cause = generateSequence(err as Throwable?) { it.cause }
                        .take(4).mapNotNull { it.message?.takeIf(String::isNotBlank) }.joinToString(" · ")
                    val srcInfo = playbackSource
                    val vCodec = srcInfo?.streams?.firstOrNull { it.type == "Video" }?.codec
                    val playbackUrlPath = runCatching {
                        android.net.Uri.parse(currentPlaybackUrl).path.orEmpty().takeLast(40)
                    }.getOrDefault("")
                    error = buildString {
                        append("URL=").append(playbackUrlPath).append("\n")
                        append("${failurePhase}阶段失败：")
                        append("播放失败：${err.errorCodeName}")
                        if (playbackSource?.streams?.firstOrNull { it.type == "Video" }?.let {
                                it.videoRangeType.orEmpty().contains("DOVI", true) || it.dvProfile != null
                            } == true) {
                            append("\nDV 解码路径=").append(dvDecoderPath ?: "待确认")
                        }
                        if (cause.isNotBlank()) append("\n").append(cause.take(180))
                        // 把容器/编码/尝试次数带出来，截图就能定位问题
                        append("\n容器=").append(srcInfo?.container ?: "?")
                            .append(" 编码=").append(vCodec ?: "?")
                            .append(" 版本=")
                        if (currentPlaybackUrl?.contains(".strm", true) == true || playbackSource?.directStreamUrl?.startsWith("http") == true) {
                            append(" STRM")
                        }
                        append(mediaSources.firstOrNull { it.id == currentSourceId }?.displayLabel ?: "默认")
                    }.let { message ->
                        val urlLine = message.substringBefore('\n')
                        message.substringAfter('\n') + "\n" + urlLine
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (activeEngine != "media3") return
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_BUFFERING && bufferingStartedAt == 0L) {
                    bufferingStartedAt = android.os.SystemClock.elapsedRealtime()
                    bufferingCount++
                } else if (state != Player.STATE_BUFFERING && bufferingStartedAt > 0L) {
                    bufferingDurationMs += android.os.SystemClock.elapsedRealtime() - bufferingStartedAt
                    bufferingStartedAt = 0L
                }
                if (state == Player.STATE_READY) {
                    playStartAt = android.os.SystemClock.elapsedRealtime()
                    if (!firstFrameRendered) readyWithoutFrameSince = System.currentTimeMillis()
                    if (degraded) error = null
                } else if (state == Player.STATE_BUFFERING) {
                    playbackStatusNotice = null
                }
                if (state == Player.STATE_ENDED && !nextTriggered &&
                    AppRuntime.prefs?.autoNextEpisode == true && item.seriesId != null
                ) {
                    nextTriggered = true
                    scope.launch {
                        val c = client ?: return@launch
                        val next = runCatching { c.getNextEpisode(item) }.getOrNull()
                        if (next != null) {
                            runCatching { c.reportStop(item.id, position) }
                            nextEpisode = next
                            nextCountdown = ((AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) / 10).coerceIn(1, 6)
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

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (activeEngine != "media3") return
                val hasAudioTrack = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
                if (!hasAudioTrack && playbackSource?.audioStreams?.isNotEmpty() == true && !noAudioRetryTried) {
                    scope.launch {
                        delay(3_000)
                        if (activeEngine == "media3" && exo.playbackState == Player.STATE_READY &&
                            exo.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 } &&
                            !noAudioRetryTried
                        ) {
                            noAudioRetryTried = true
                            val codec = playbackSource?.audioStreams?.firstOrNull { it.isDefault }?.codec
                                ?: playbackSource?.audioStreams?.firstOrNull()?.codec ?: "音轨"
                            error = "该音轨（$codec）本机无法解码，正在尝试转码播放"
                            AppRuntime.prefs?.lastPlaybackAudioFailure = "$codec 音频解码失败"
                            if (audioDecodeMode == "passthrough") {
                                audioDecodeNotice = "设备不支持该音轨直通，建议改回自动"
                                error = "设备不支持该音轨直通，建议改回自动"
                            } else if (audioDecodeMode != "passthrough" && activeEngine == "media3") {
                                audioDecodeNotice = "该音轨已自动切到 mpv 本地软解"
                                enginePinned = "mpv"
                                activeEngine = "mpv"
                            } else {
                                error = "该音轨（$codec）无法播放"
                            }
                        }
                    }
                }
                if (subtitleSelectionApplied) return
                val remembered = AppRuntime.prefs?.subtitleTrackFor(item.id)
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                if (textGroups.isEmpty()) return
                if (remembered == "-1") {
                    subtitleSelectionApplied = true
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    return
                }
                val all = textGroups.flatMap { group ->
                    (0 until group.length).map { index -> Triple(group, index, group.getTrackFormat(index)) }
                }
                val selected = remembered?.let { key ->
                    all.firstOrNull { (group, index, _) -> group.mediaTrackGroup.id + ":" + index == key }
                        ?: key.substringBefore(':').toIntOrNull()?.let { streamIndex ->
                            all.firstOrNull { (_, index, format) -> format.id?.toIntOrNull() == streamIndex || index == streamIndex }
                        }
                }
                val chinese = all.firstOrNull { (_, _, format) ->
                    val description = listOfNotNull(format.language, format.label, format.id).joinToString(" ")
                    description.contains(Regex("zh|chi|zho|中文|简体|繁體|繁体|中字", RegexOption.IGNORE_CASE))
                }
                val keepDefault = all.firstOrNull { (group, index, _) -> group.isTrackSelected(index) }
                val choice = selected ?: chinese ?: keepDefault
                subtitleSelectionApplied = true
                if (choice != null) {
                    val (group, index, _) = choice
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                } else {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            }

            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    DisposableEffect(exo) {
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                activeVideoDecoder = decoderName
                phaseTimes.put("decoderInitMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                firstFrameRendered = true
                phaseTimes.put("firstFrameMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
                readyWithoutFrameSince = 0L
                playbackStatusNotice = null
                scope.launch { delay(2_000); if (firstFrameRendered) phaseTimes.put("stableOutputMs", android.os.SystemClock.elapsedRealtime() - playStartAt) }
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFramesCount: Int, elapsedMs: Long) {
                droppedFrames += droppedFramesCount
            }

            override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                renderedFrames += frameCount
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                estimatedBitrate = bitrateEstimate.coerceAtLeast(0L)
            }
        }
        exo.addAnalyticsListener(analytics)
        onDispose { exo.removeAnalyticsListener(analytics) }
    }

    DisposableEffect(item.id) {
        onDispose {
            val backend = mpvBackend
            backend?.release("player-disposed")
            if (mpvBackend === backend) mpvBackend = null
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            sessionGeneration++
            loadJob?.cancel()
        }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(1400)
            seekHint = null
        }
    }

    fun seekBy(delta: Long) {
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val current = if (activeEngine == "mpv") mpvBackend?.positionMs() ?: position else exo.currentPosition
        val to = (current + delta).coerceIn(0L, max)
        when (activeEngine) {
            "mpv" -> mpvBackend?.seekTo(to)
            else -> exo.seekTo(to)
        }
        position = to
        if (seekHoldActive && android.os.SystemClock.elapsedRealtime() - lastHoldPreviewAt >= 700L) {
            previewFrame = null
            previewTargetMs = to
            lastHoldPreviewAt = android.os.SystemClock.elapsedRealtime()
        }
        seekHint = if (delta > 0) "»»  快进 ${delta / 1000} 秒    ${formatTime(to)}"
        else "««  快退 ${-delta / 1000} 秒    ${formatTime(to)}"
    }

    fun beginSeekHold(direction: Int) {
        if (seekHoldDirection == direction) return
        seekHoldJob?.cancel()
        seekHoldDirection = direction
        seekHoldActive = false
        seekHoldDelta = 0L
        seekHoldJob = scope.launch {
            delay(500)
            if (seekHoldDirection != direction) return@launch
            seekHoldActive = true
            previewActive = true
            while (seekHoldDirection == direction) {
                seekBy(direction * 5_000L)
                seekHoldDelta += direction * 5_000L
                val seconds = kotlin.math.abs(seekHoldDelta) / 1000
                seekHint = if (direction > 0) "快进 +${seconds} 秒" else "快退 -${seconds} 秒"
                delay(120)
            }
        }
    }

    fun endSeekHold(direction: Int) {
        if (seekHoldDirection != direction) return
        seekHoldJob?.cancel()
        seekHoldJob = null
        if (!seekHoldActive) seekBy(direction * 10_000L)
        previewActive = false
        previewFrame = null
        previewTargetMs = null
        seekHoldDirection = 0
        seekHoldActive = false
        seekHoldDelta = 0L
    }

    LaunchedEffect(Unit) {
        var sinceReport = 0
        var bufferingSince = 0L
        while (true) {
            delay(1000)
            position = when (activeEngine) {
                "mpv" -> mpvBackend?.positionMs() ?: position
                else -> exo.currentPosition
            }
            duration = when (activeEngine) {
                "mpv" -> mpvBackend?.durationMs() ?: duration
                else -> exo.duration.coerceAtLeast(0)
            }
            val endSkipSeconds = AppRuntime.prefs?.endSkipSeconds ?: 30
            val validCreditsStart = currentCreditsStartMs?.takeIf { it > 0L && it < duration }
            if (endSkipSeconds > 0 && duration > 0 && item.seriesId != null && !nextTriggered &&
                ((validCreditsStart != null && position >= validCreditsStart) ||
                    (validCreditsStart == null && endSkipSeconds > 0 && position >= duration - endSkipSeconds * 1000L))
            ) {
                nextTriggered = true
                scope.launch {
                    val c = client ?: return@launch
                    val next = runCatching { c.getNextEpisode(item) }.getOrNull() ?: return@launch
                    nextEpisode = next
                    runCatching { c.prewarmPlayback(next, audioStreamIndex = AppRuntime.prefs?.audioTrackFor(next.id)) }
                    nextCountdown = ((AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) / 10).coerceIn(1, 6)
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
            if (duration > 0 && item.seriesId != null && prewarmedEpisodeId == null &&
                position >= duration - 90_000L
            ) {
                prewarmedEpisodeId = "pending"
                scope.launch {
                    val c = client ?: return@launch
                    val next = runCatching { c.getNextEpisode(item) }.getOrNull()
                    if (next != null) {
                        prewarmedEpisodeId = next.id
                        runCatching { c.prewarmPlayback(next, audioStreamIndex = AppRuntime.prefs?.audioTrackFor(next.id)) }
                    } else prewarmedEpisodeId = "none"
                }
            }
            if (activeEngine == "media3" && exo.playbackState == Player.STATE_BUFFERING) {
                val now = System.currentTimeMillis()
                if (bufferingSince == 0L) {
                    bufferingSince = now
                } else if (!bufferingTimedOut && !firstFrameRendered && now - bufferingSince >= 15_000L) {
                    bufferingTimedOut = true
                    playbackStatusNotice = "缓冲超时：服务端未返回数据"
                    failurePhase = "连接"
                }
            } else {
                bufferingSince = 0L
            }
            if (activeEngine == "media3" && exo.playbackState == Player.STATE_READY &&
                !firstFrameRendered && readyWithoutFrameSince > 0L &&
                System.currentTimeMillis() - readyWithoutFrameSince >= 8_000L
            ) {
                playbackStatusNotice = "已就绪但未出画面，可能是解码器或显示层问题"
                failurePhase = "首帧"
            }
            sinceReport++
            if (sinceReport >= 10 && AppRuntime.client != null && duration > 0 && ((activeEngine == "mpv" && mpvState.playing) || (activeEngine == "media3" && exo.isPlaying) || position > 0)) {
                sinceReport = 0
                val paused = if (activeEngine == "mpv") !mpvState.playing else !exo.isPlaying
                runCatching { AppRuntime.client?.reportProgress(item.id, position, paused, currentSourceId, currentPlayMethod) }
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
            chapterPanel -> chapterPanel = false
            optionMenu -> optionMenu = false
            showControls -> showControls = false
            else -> {
                scope.safeLaunch { AppRuntime.client?.reportStop(item.id, position.coerceAtLeast(0L)) }
                if (activeEngine == "mpv") mpvBackend?.pause() else exo.stop()
                onBack()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (activeEngine == "media3") AndroidView(
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
                pv.subtitleView?.apply {
                    visibility = android.view.View.VISIBLE
                    val subtitleColor = when {
                        subScale >= 1.6f -> android.graphics.Color.WHITE
                        subScale >= 1.25f -> android.graphics.Color.rgb(217, 217, 217)
                        else -> android.graphics.Color.rgb(179, 179, 179)
                    }
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(0.0533f)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setStyle(androidx.media3.ui.CaptionStyleCompat(
                        subtitleColor,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        null,
                    ))
                    setBottomPaddingFraction(0.08f)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (activeEngine != "media3") AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    keepScreenOn = true
                    currentSurface = holder.surface.takeIf { it.isValid }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            currentSurface = holder.surface
                            mpvBackend?.attachSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            currentSurface = holder.surface
                            mpvBackend?.attachSurface(holder.surface, width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (currentSurface === holder.surface) currentSurface = null
                            mpvBackend?.detachSurface()
                        }
                    })
                }
            },
            update = { view -> if (view.holder.surface.isValid) {
                currentSurface = view.holder.surface
                mpvBackend?.attachSurface(view.holder.surface, view.width, view.height)
            } },
            modifier = Modifier.fillMaxSize(),
        )

        if ((if (activeEngine == "mpv") mpvState.buffering else buffering) && error == null && playbackStatusNotice == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("正在缓冲…", color = TextSub, fontSize = 12.sp)
                }
            }
        }

        playbackStatusNotice?.let { notice ->
            Text(
                notice,
                color = Color(0xFFFFD28A),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                    .background(Color(0xAA10141D), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        error?.let {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(it, color = Color(0xFFFF8A9A), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 40.dp))
                if (bufferingTimedOut || error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (bufferingTimedOut) {
                    Spacer(Modifier.height(12.dp))
                    FocusSurface(Modifier.width(190.dp).height(40.dp), corner = 20.dp, onClick = {
                        bufferingTimedOut = false
                        reloadForceTranscode = true
                        reloadKey++
                    }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Accent.copy(alpha = .9f) else Color(0x22FFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            Text("立即切换转码", color = if (focused) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                        }
                    }
                    }
                    FocusSurface(Modifier.width(164.dp).height(40.dp), corner = 20.dp, onClick = { showDiagExport = true }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x22FFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            Text("导出诊断包", color = if (focused) Accent else TextMain, fontSize = 12.sp)
                        }
                    }
                    }
                }
            }
        }

        if (chapterPanel && chapters.isNotEmpty()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                GlassPanel(Modifier.width(520.dp).heightIn(max = 560.dp), corner = 20.dp, fillColor = Color(0xD910141D)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("章节与片段", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            CtrlButton(Icons.Rounded.Pause, "关闭章节列表") { chapterPanel = false }
                        }
                        LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(chapters) { chapter ->
                                FocusSurface(Modifier.fillMaxWidth().height(42.dp), corner = 8.dp, scaleTo = 1f, onClick = {
                                    seekPlayerTo(chapter.startMs)
                                    position = chapter.startMs
                                    chapterPanel = false
                                    showControls = true
                                }) { focused ->
                                    Row(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x12FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(formatTime(chapter.startMs), color = Accent, fontSize = 12.sp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(chapter.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.weight(1f))
                                        if (chapter.name.contains(Regex("intro|opening|片头", RegexOption.IGNORE_CASE))) Text("片头", color = TextSub, fontSize = 10.sp)
                                        if (chapter.name.contains(Regex("credit|ending|片尾|字幕", RegexOption.IGNORE_CASE))) Text("片尾", color = TextSub, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
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
                        if (ev.type == KeyEventType.KeyDown && nextEpisode != null) {
                            nextEpisode = null
                            nextCountdown = 0
                        }
                        when (ev.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                if (ev.type == KeyEventType.KeyDown) {
                                    if (ev.nativeKeyEvent.repeatCount == 0) okDownAt = System.currentTimeMillis()
                                    else if (!boosting && System.currentTimeMillis() - okDownAt > 480) {
                                        boosting = true
                                        setPlaybackSpeed(3f)
                                    }
                                    true
                                } else {
                                    if (boosting) {
                                        boosting = false
                                        setPlaybackSpeed(speed)
                                    } else {
                                        showControls = true
                                    }
                                    true
                                }
                            }
                            Key.DirectionLeft -> {
                                if (ev.type == KeyEventType.KeyDown) beginSeekHold(-1) else endSeekHold(-1)
                                showControls = true
                                true
                            }
                            Key.DirectionRight -> {
                                if (ev.type == KeyEventType.KeyDown) beginSeekHold(1) else endSeekHold(1)
                                showControls = true
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (ev.type == KeyEventType.KeyDown) showControls = true
                                true
                            }
                            Key.MediaPlayPause -> {
                                if (ev.type == KeyEventType.KeyDown) togglePlayback()
                                true
                            }
                            Key.MediaRewind -> {
                                if (ev.type == KeyEventType.KeyDown) seekBy(-30_000)
                                true
                            }
                            Key.MediaFastForward -> {
                                if (ev.type == KeyEventType.KeyDown) seekBy(30_000)
                                true
                            }
                            else -> {
                                val nativeCode = ev.nativeKeyEvent.keyCode
                                when {
                                    ev.type == KeyEventType.KeyDown && nativeCode in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9 && duration > 0 -> {
                                        val percent = nativeCode - android.view.KeyEvent.KEYCODE_0
                                        seekPlayerTo(duration * percent / 10)
                                        seekHint = "跳到 ${percent * 10}%"
                                        true
                                    }
                                    ev.type == KeyEventType.KeyDown && nativeCode == android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                                        subtitleOffsetMs = (subtitleOffsetMs - 250L).coerceIn(-10_000L, 10_000L)
                                        AppRuntime.prefs?.subtitleOffsetMs = subtitleOffsetMs
                                        seekHint = "字幕偏移 ${subtitleOffsetMs}ms"
                                        true
                                    }
                                    ev.type == KeyEventType.KeyDown && nativeCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                                        subtitleOffsetMs = (subtitleOffsetMs + 250L).coerceIn(-10_000L, 10_000L)
                                        AppRuntime.prefs?.subtitleOffsetMs = subtitleOffsetMs
                                        seekHint = "字幕偏移 ${subtitleOffsetMs}ms"
                                        true
                                    }
                                    else -> false
                                }
                            }
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
                onClick = { seekPlayerTo(maxOf(position, introSeconds * 1000L)) },
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
                        if (!titleLogo.isNullOrBlank()) {
                            AsyncImage(
                                model = titleLogo,
                                contentDescription = item.seriesName ?: item.name,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier.heightIn(max = 44.dp).widthIn(max = 300.dp),
                            )
                        } else {
                            Text(
                                item.seriesName ?: item.name,
                                color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.seriesName != null) {
                            Text(item.name, color = TextSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        listOfNotNull(
                            currentResolution?.takeIf { it.isNotBlank() },
                            if (estimatedBitrate > 0L) "%.1f MB/s".format(estimatedBitrate / 8_000_000.0) else null,
                            if (degraded) "转码" else "直连",
                            if (activeEngine == "mpv") "mpv 本地软解" else "media3",
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
                        val frame = previewFrame
                        val target = previewTargetMs
                        if (previewActive && frame != null && target != null && duration > 0) {
                            BoxWithConstraints(Modifier.fillMaxWidth().height(100.dp)) {
                                val previewWidth = 160.dp
                                val x = (maxWidth * (target.toFloat() / duration).coerceIn(0f, 1f) - previewWidth / 2)
                                    .coerceIn(0.dp, (maxWidth - previewWidth).coerceAtLeast(0.dp))
                                Box(
                                    Modifier.offset(x = x).width(previewWidth).height(90.dp)
                                        .background(Color(0xE6070A12), RoundedCornerShape(6.dp)),
                                ) {
                                    AsyncImage(
                                        model = frame,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        onError = {
                                            previewDisabled = true
                                            previewFrame = null
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Text(
                                        formatTime(target),
                                        color = TextMain,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .background(Color(0xAA070A12)).padding(vertical = 3.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                        SeekBar(
                            position = position,
                            duration = duration,
                            onSeek = { target ->
                                seekPlayerTo(target)
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
                            CtrlButton(Icons.Rounded.FastRewind, "后退 10 秒") { seekBy(-10_000) }
                            CtrlButton(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (isPlaying) "暂停" else "播放",
                                primary = true,
                                focusRequester = playFocus,
                            ) { togglePlayback() }
                            CtrlButton(Icons.Rounded.FastForward, "前进 10 秒") { seekBy(10_000) }
                            val currentEpisodeIndex = episodes.indexOfFirst { it.id == item.id }
                            if (currentEpisodeIndex >= 0 && currentEpisodeIndex < episodes.lastIndex) {
                                CtrlButton(Icons.Rounded.SkipNext, "下一集") {
                                    val next = episodes.getOrNull(currentEpisodeIndex + 1)
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
                            if (chapters.isNotEmpty()) CtrlButton(Icons.Rounded.Bookmarks, "章节与片段") { chapterPanel = true; showControls = false }
                            Spacer(Modifier.weight(1f))
                            Text(item.subtitle, color = TextSub.copy(alpha = 0.75f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = optionMenu,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.98f),
        ) {
            OptionMenu(
                exo = exo,
                item = item,
                speed = speed,
                resizeMode = resizeMode,
                currentSourceId = currentSourceId,
                episodes = episodes,
                subScale = subScale,
                audioDecodeMode = audioDecodeMode,
                subtitleOffsetMs = subtitleOffsetMs,
                activeEngine = activeEngine,
                audioStreams = if (activeEngine == "media3") media3AudioTracks(exo.currentTracks) else emptyList(),
                selectedAudioStreamIndex = AppRuntime.prefs?.audioTrackFor(item.id),
                onAudioStreamSelected = { streamIndex, override ->
                    manualAudioStreamSelection = true
                    AppRuntime.prefs?.rememberAudioTrack(item.id, streamIndex)
                    if (override != null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(override)
                            .build()
                    } else {
                        reloadForceTranscode = false
                        reloadKey++
                    }
                    if (activeEngine == "mpv") mpvBackend?.setAudioTrack(streamIndex)
                },
                onSubtitleSelected = { AppRuntime.prefs?.rememberSubtitleTrack(item.id, it) },
                onSubtitleOffset = {
                    subtitleOffsetMs = it.coerceIn(-10_000L, 10_000L)
                    mpvBackend?.setSubtitleOffset(subtitleOffsetMs)
                    AppRuntime.prefs?.subtitleOffsetMs = subtitleOffsetMs
                },
                onSubScale = {
                    subScale = it
                    AppRuntime.prefs?.subtitleScale = it
                    val subtitleSp = when { it >= 2.3f -> 38.4f; it >= 1.9f -> 32f; it >= 1.5f -> 25.6f; it >= 1.2f -> 20.8f; else -> 16f }
                    mpvBackend?.setSubtitleFontSize((subtitleSp * context.resources.displayMetrics.density).toInt())
                },
                onAudioDecodeMode = { mode ->
                    audioDecodeMode = mode
                    AppRuntime.prefs?.audioDecodeMode = mode
                    audioDecodeNotice = when (mode) {
                        "soft" -> "全部音轨将在本地软解（已切到 mpv）"
                        "passthrough" -> "已切换到音频直通"
                        else -> "已切换到自动音频解码"
                    }
                    // 显式选择才切内核：默认永远是 media3，避免"什么都用 MPV 转圈"
                    when (mode) {
                        "soft" -> { enginePinned = "mpv"; if (activeEngine != "mpv") activeEngine = "mpv" }
                        "passthrough" -> { enginePinned = "media3"; if (activeEngine != "media3") activeEngine = "media3" }
                        else -> enginePinned = null
                    }
                    engineSwitchTrace.add("audio-decode-mode=$mode")
                    reloadForceTranscode = false
                    reloadKey++
                },
                onSpeed = {
                    setPlaybackSpeed(it)
                },
                onSwitchEngine = {
                    error = "正在切换播放内核…"
                    val engines = listOf("media3", "mpv")
                    if (engines.size > 1) {
                        val next = engines[(engines.indexOf(activeEngine).coerceAtLeast(0) + 1) % engines.size]
                        enginePinned = next
                        activeEngine = next
                    }
                },
                onlyMedia3Available = false,
                onResizeMode = { resizeMode = it },
                onSwitchSource = { sid ->
                    optionMenu = false
                    directFallbackTried = false
                    transcodeFallbackTried = false
                    forceDirectNext = false
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
        audioDecodeNotice?.let { notice ->
            Text(notice, Modifier.align(Alignment.BottomEnd).padding(24.dp).background(Color(0xAA10141D), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp), color = TextMain, fontSize = 11.sp)
            LaunchedEffect(notice) { delay(3_000); audioDecodeNotice = null }
        }
    }
    if (showMpvConfirm) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.width(480.dp), corner = 18.dp, fillColor = Color(0xF018202B)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Media3 无法播放该片，是否尝试 mpv 内核？", color = TextMain, fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusSurface(Modifier.width(130.dp).height(42.dp), corner = 10.dp, onClick = {
                            engineSwitchTrace.add("user-confirmed-mpv")
                            showMpvConfirm = false
                            activeEngine = "mpv"
                        }) { focused ->
                            Box(Modifier.fillMaxSize().background(if (focused) Accent else Color(0x22FFFFFF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Text("尝试", color = if (focused) Color(0xFF06101F) else TextMain)
                            }
                        }
                        FocusSurface(Modifier.width(130.dp).height(42.dp), corner = 10.dp, onClick = {
                            engineSwitchTrace.add("user-declined-mpv")
                            showMpvConfirm = false
                            error = "Media3 播放失败。可导出诊断包或手动切换播放内核。"
                        }) { focused ->
                            Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x12FFFFFF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Text("取消", color = TextMain)
                            }
                        }
                    }
                }
            }
        }
    }
    DiagExportDialog(visible = showDiagExport, onDismiss = { showDiagExport = false }) { outcome ->
        val payload = buildDiagPayload(outcome).copy(appLog = withContext(Dispatchers.IO) { DiagExporter.appLogTail() })
        withContext(Dispatchers.IO) { DiagExporter.export(context, payload, "playback") }
    }
}
