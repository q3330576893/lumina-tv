package com.lumina.tv.ui.screens

import android.os.Build
import android.util.TypedValue
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
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaChapter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

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
    val scope = rememberCoroutineScope()

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
    var lastPlaybackErrorJson by remember(item.id) { mutableStateOf<String?>(null) }
    var failurePhase by remember(item.id) { mutableStateOf("未知") }
    var currentSourceId by remember { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var boosting by remember { mutableStateOf(false) }
    var nextTriggered by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentResolution by remember { mutableStateOf<String?>(null) }
    var subScale by remember { mutableFloatStateOf(when (AppRuntime.prefs?.subtitleScale) { null -> 1.3f; 1.5f -> 2.0f; else -> AppRuntime.prefs?.subtitleScale ?: 1.3f }) }
    var subtitleOffsetMs by remember { mutableLongStateOf(AppRuntime.prefs?.subtitleOffsetMs ?: 0L) }
    var nextEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var nextCountdown by remember { mutableIntStateOf(0) }
    var chapters by remember { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var chapterPanel by remember { mutableStateOf(false) }
    var subtitleSelectionApplied by remember(item.id) { mutableStateOf(false) }

    val introSeconds = remember(item.id) { AppRuntime.prefs?.introSecondsFor(item.id) ?: 0 }

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

    val exo = remember(item.id, ffmpegFallback) {
        val preference = AppRuntime.prefs?.decoderPreference ?: "auto"
        val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
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
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
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

    LaunchedEffect(item.id) { chapters = runCatching { client?.getChapters(item.id) }.getOrNull().orEmpty() }

    fun startAutoNext() {
        if (nextTriggered || AppRuntime.prefs?.autoNextEpisode != true || item.seriesId == null) return
        nextTriggered = true
        scope.launch {
            val c = client ?: return@launch
            val list = if (episodes.isNotEmpty()) episodes
            else runCatching { c.getEpisodes(item.seriesId, item.seasonId) }.getOrDefault(emptyList())
            val idx = list.indexOfFirst { it.id == item.id }
            val next = list.getOrNull(idx + 1) ?: return@launch
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
        scope.launch {
            error = null
            buffering = true
            failurePhase = "解析"
            try {
                val target = c.resolvePlayback(
                    item, forceTranscode, sourceId, resumePosition, audioStreamIndex,
                    forceDirect = forceDirectNext,
                )
                failurePhase = "连接"
                currentSourceId = target.source?.id ?: sourceId
                playbackSource = target.source
                currentPlaybackUrl = target.url
                val defaultAudio = target.source?.audioStreams?.firstOrNull { it.isDefault }
                    ?: target.source?.audioStreams?.firstOrNull()
                val audioCodec = listOfNotNull(defaultAudio?.codec, defaultAudio?.profile).joinToString(" ").lowercase()
                val unsupportedAudio = listOf("truehd", "dts", "dca", "mlp", "atmos", "eac3").any(audioCodec::contains)
                // 统一走 media3 优先：不再因为音轨编码就提前把内核换成 mpv，
                // 由 media3 真正解码失败后再自动回落，避免“什么都用 MPV 转圈”。
                val selectedEngine = enginePinned
                    ?: if (AppRuntime.prefs?.playerEngine == "mpv") "mpv" else "media3"
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
                if (activeEngine == "mpv") {
                    val backend = mpvBackend ?: MpvPlayerController(context) { state ->
                        mpvState = state
                        position = state.positionMs
                        if (state.durationMs > 0L) duration = state.durationMs
                        buffering = state.buffering
                        isPlaying = state.playing
                        if (state.ended) startAutoNext()
                        if (state.error != null) {
                            if (state.error.contains("12s 无首帧") || state.error.contains("不支持 mpv")) {
                                AppRuntime.prefs?.mpvBroken = true
                            }
                            error = if (AppRuntime.prefs?.playerEngine == "auto") {
                                "该设备不支持 mpv 内核，已回退 Media3"
                            } else {
                                "mpv 内核不可用（${state.error}），已临时回退 Media3，可在设置里重选"
                            }
                            mpvBackend?.release()
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
                        mpvBackend = null
                        runCatching { backend.release() }
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
                        backend.release()
                        mpvBackend = null
                        error = "播放画面未就绪（Surface 未创建），已回退 Media3"
                        degraded = true
                        ffmpegFallback = true
                        activeEngine = "media3"
                        reloadKey++
                        return@launch
                    }
                    backend.attachSurface(surface)
                    val externalSubtitles = subs.mapNotNull { stream ->
                        srcId?.let { c.subtitleUrl(item.id, it, stream.index, "vtt") }
                    }
                    backend.setSpeed(if (boosting) 3f else speed)
                    backend.load(target.url, target.startPositionMs, externalSubtitles, target.headers)
                } else {
                    exo.setMediaSource(merged)
                    exo.prepare()
                    exo.seekTo(target.startPositionMs.coerceAtLeast(0))
                    exo.playWhenReady = true
                    exo.setPlaybackSpeed(if (boosting) 3f else speed)
                }
                c.reportStart(item.id, target.startPositionMs, target.source?.id, if (target.direct) "DirectPlay" else "Transcode")
            } catch (e: Exception) {
                buffering = false
                if (failurePhase == "未知") failurePhase = "解析"
                val reason = e.message ?: "播放失败"
                if (activeEngine == "mpv") {
                    mpvBackend?.release()
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
        val meta = DiagExporter.baseMeta(context, outcome)
            .put("engine", JSONObject()
                .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown")
                .put("active", activeEngine)
                .put("mpvAvailable", mpvBackend?.unavailableReason == null && mpvBackend != null)
                .put("kernels", JSONArray().put("media3").put("mpv")))
            .put("media", JSONObject()
                .put("id", item.id).put("name", item.name).put("seriesName", item.seriesName ?: JSONObject.NULL)
                .put("container", playbackSource?.container ?: JSONObject.NULL)
                .put("size", playbackSource?.size ?: 0L)
                .put("isStrm", playbackSource?.container?.contains("strm", true) == true))
            .put("playback", JSONObject().put("url", currentPlaybackUrl ?: JSONObject.NULL))
            .put("audioDecode", JSONObject()
                .put("audioDecodeMode", audioDecodeMode)
                .put("audioDecodePath", when {
                    audioDecodeMode == "passthrough" -> "passthrough"
                    activeEngine == "mpv" -> "local-soft-mpv"
                    else -> "direct"
                })
                .put("audioCodec", playbackSource?.audioStreams?.firstOrNull { it.isDefault }?.codec ?: playbackSource?.audioStreams?.firstOrNull()?.codec ?: JSONObject.NULL)
                .put("channels", playbackSource?.audioStreams?.firstOrNull { it.isDefault }?.channels ?: playbackSource?.audioStreams?.firstOrNull()?.channels ?: JSONObject.NULL)
                .put("transcoded", playbackSource?.let { !it.supportsDirectPlay || degraded } ?: false))
            .put("engineSwitchTrace", JSONArray(engineSwitchTrace))
        val streams = JSONArray().apply {
            playbackSource?.streams.orEmpty().forEach { stream ->
                put(JSONObject()
                    .put("Index", stream.index).put("Type", stream.type).put("Codec", stream.codec ?: JSONObject.NULL)
                    .put("Profile", stream.profile ?: JSONObject.NULL).put("Level", stream.level ?: JSONObject.NULL)
                    .put("Width", stream.width ?: JSONObject.NULL).put("Height", stream.height ?: JSONObject.NULL)
                    .put("BitRate", stream.bitRate ?: JSONObject.NULL).put("Channels", stream.channels ?: JSONObject.NULL)
                    .put("SampleRate", stream.sampleRate ?: JSONObject.NULL).put("IsDefault", stream.isDefault)
                    .put("DisplayTitle", stream.displayTitle ?: JSONObject.NULL).put("Language", stream.language ?: JSONObject.NULL))
            }
        }
        val tracks = JSONArray().apply {
            exo.currentTracks.groups.forEach { group ->
                val formats = JSONArray()
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    formats.put(JSONObject()
                        .put("id", f.id ?: JSONObject.NULL).put("label", f.label ?: JSONObject.NULL)
                        .put("language", f.language ?: JSONObject.NULL).put("sampleMimeType", f.sampleMimeType ?: JSONObject.NULL)
                        .put("codecs", f.codecs ?: JSONObject.NULL).put("bitrate", f.bitrate)
                        .put("width", f.width).put("height", f.height).put("sampleRate", f.sampleRate)
                        .put("channelCount", f.channelCount).put("selected", group.isTrackSelected(i))
                        .put("supported", group.isTrackSupported(i)))
                }
                put(JSONObject().put("type", group.type).put("length", group.length).put("formats", formats))
            }
        }
        val errors = JSONObject()
            .put("failurePhase", failurePhase)
            .put("media3", lastPlaybackErrorJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("mpv", mpvState.error ?: "No mpv error recorded")
            .put("mpvSoftwareRetryCount", mpvBackend?.softwareRetryCount() ?: 0)
            .put("audioDecodeFailure", AppRuntime.prefs?.lastPlaybackAudioFailure ?: "")
        return DiagPayload(
            metaJson = meta.toString(2),
            playbackInfoJson = client?.lastPlaybackInfoJson ?: JSONObject().put("reason", "PlaybackInfo was not captured").toString(2),
            streamsJson = JSONObject().put("MediaStreams", streams).toString(2),
            tracksJson = JSONObject().put("groups", tracks).toString(2),
            errorsJson = errors.toString(2),
            mpvLog = mpvBackend?.diagnosticLog()?.ifBlank { "No mpv log messages captured" } ?: "mpv log unavailable: mpv backend was not active",
            appLog = DiagExporter.appLogTail(),
            uiText = "showControls=$showControls\nbuffering=$buffering\nerror=${error ?: "none"}\npositionMs=$position\nactiveEngine=$activeEngine\ndegraded=$degraded",
        )
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
        if (activeEngine == "media3") loadSource(AppRuntime.prefs?.forceTranscode ?: false, initialSourceId)
    }

    LaunchedEffect(exo, subtitleOffsetMs) {
        subtitleRenderersFactory.subtitleOffsetMs = subtitleOffsetMs
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
            error = "mpv 内核也无法播放：${mpvErr.take(160)}\n可导出诊断包反馈。"
        }
    }

    DisposableEffect(exo, item.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                if (activeEngine != "media3") return
                failurePhase = when {
                    err.errorCodeName.contains("IO", true) -> "连接"
                    err.errorCodeName.contains("DEMUX", true) || err.errorCodeName.contains("PARSING", true) -> "解封装"
                    err.errorCodeName.contains("DECOD", true) -> "解码"
                    err.errorCodeName.contains("RENDER", true) -> "渲染"
                    else -> "解码"
                }
                lastPlaybackErrorJson = JSONObject()
                    .put("errorCodeName", err.errorCodeName)
                    .put("errorCode", err.errorCode)
                    .put("message", err.message)
                    .put("causes", JSONArray().apply {
                        generateSequence(err as Throwable?) { it.cause }.take(5)
                            .forEach { put(it.message ?: it::class.java.name) }
                    }).toString(2)
                // 容器/解封装级失败：转码通常能救一次，但再转第二次基本无意义，直接交给 mpv 更快
                val containerUnsupported = err.errorCodeName.contains("PARSING", true) ||
                    err.errorCodeName.contains("DEMUX", true) ||
                    err.errorCodeName.contains("CONTAINER", true)
                if (audioDecodeMode == "passthrough" && err.errorCodeName.contains("DECOD", true)) {
                    buffering = false
                    audioDecodeNotice = "设备不支持该音轨直通，建议改回自动"
                    error = "设备不支持该音轨直通，建议改回自动"
                } else if (audioDecodeMode != "passthrough" && err.errorCodeName.contains("DECOD", true) && !mpvFallbackTried) {
                    buffering = false
                    mpvFallbackTried = true
                    audioDecodeNotice = "本机解码不支持该音轨，已自动切到 mpv 本地软解"
                    enginePinned = "mpv"
                    activeEngine = "mpv"
                } else if (containerUnsupported && !directFallbackTried &&
                    (degraded || currentPlaybackUrl?.contains("master.m3u8", true) == true)
                ) {
                    // 失败的是服务端转码流（master.m3u8 解析不了），改拉原文件直连
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
                } else if (!degraded) {
                    degraded = true
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
                } else if (!mpvFallbackTried) {
                    // Media3 已经把直连、转码都试过还不行 —— 直接换 mpv，不再让用户停在错误页
                    mpvFallbackTried = true
                    enginePinned = "mpv"
                    buffering = true
                    error = null
                    audioDecodeNotice = "Media3 无法播放该文件（${err.errorCodeName}），已自动切换 mpv"
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
                        if (cause.isNotBlank()) append("\n").append(cause.take(180))
                        // 把容器/编码/尝试次数带出来，截图就能定位问题
                        append("\n容器=").append(srcInfo?.container ?: "?")
                            .append(" 编码=").append(vCodec ?: "?")
                            .append(" 版本=")
                            .append(mediaSources.firstOrNull { it.id == currentSourceId }?.displayLabel ?: "默认")
                    }.let { message ->
                        val urlLine = message.substringBefore('\n')
                        message.substringAfter('\n') + "\n" + urlLine
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (activeEngine != "media3") return
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playStartAt = android.os.SystemClock.elapsedRealtime()
                    if (degraded) error = null
                }
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

    DisposableEffect(mpvBackend, item.id) {
        onDispose { mpvBackend?.release(); mpvBackend = null }
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
        seekHint = if (delta > 0) "»»  快进 ${delta / 1000} 秒    ${formatTime(to)}"
        else "««  快退 ${-delta / 1000} 秒    ${formatTime(to)}"
    }

    LaunchedEffect(Unit) {
        var sinceReport = 0
        var bufferingSince = 0L
        var bufferingPosition = -1L
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
            if (activeEngine == "media3" && exo.playbackState == Player.STATE_BUFFERING) {
                val now = System.currentTimeMillis()
                val currentPosition = exo.currentPosition
                if (bufferingSince == 0L || currentPosition != bufferingPosition) {
                    bufferingSince = now
                    bufferingPosition = currentPosition
                } else if (!bufferingTimedOut && now - bufferingSince >= 20_000L) {
                    bufferingTimedOut = true
                    error = "缓冲超时，建议切换到转码播放"
                }
            } else {
                bufferingSince = 0L
                bufferingPosition = exo.currentPosition
            }
            sinceReport++
            if (sinceReport >= 10 && AppRuntime.client != null && duration > 0 && ((activeEngine == "mpv" && mpvState.playing) || (activeEngine == "media3" && exo.isPlaying) || position > 0)) {
                sinceReport = 0
                val paused = if (activeEngine == "mpv") !mpvState.playing else !exo.isPlaying
                AppRuntime.client?.reportProgress(item.id, position, paused, currentSourceId)
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
                scope.launch { AppRuntime.client?.reportStop(item.id, position.coerceAtLeast(0L)) }
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
                val prefs = AppRuntime.prefs
                pv.subtitleView?.apply {
                    val subtitleSp = when {
                        subScale >= 2.3f -> 38.4f
                        subScale >= 1.9f -> 32f
                        subScale >= 1.5f -> 25.6f
                        subScale >= 1.2f -> 20.8f
                        else -> 16f
                    }
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSp)
                    setBackgroundColor(if (prefs?.subtitleBg == true) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT)
                    setStyle(androidx.media3.ui.CaptionStyleCompat(
                        prefs?.subtitleColor ?: android.graphics.Color.WHITE,
                        if (prefs?.subtitleBg == true) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        if (prefs?.subtitleOutline != false) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                        android.graphics.Color.BLACK,
                        null,
                    ))
                    setBottomPaddingFraction((prefs?.subtitlePosition ?: 0.08f) + if (subtitleSp >= 38f) 0.05f else if (subtitleSp >= 32f) 0.035f else 0f)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        else AndroidView(
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

        if ((if (activeEngine == "mpv") mpvState.buffering else buffering) && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("正在缓冲…", color = TextSub, fontSize = 12.sp)
                }
            }
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
                onClick = { seekPlayerTo(introSeconds * 1000L) },
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

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.width(680.dp), corner = 22.dp, fillColor = Color(0x7510141D)) {
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
                    modifier = Modifier.fillMaxWidth().height(38.dp),
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
                        MenuChip(label = label, width = 112.dp, active = audioDecodeMode == mode, activeColor = Accent,
                            onClick = { onAudioDecodeMode(mode) })
                    }
                }

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

                Spacer(Modifier.height(8.dp))
                MenuLabel("字幕同步 ${if (subtitleOffsetMs >= 0) "+" else ""}${subtitleOffsetMs}ms")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-500L, -100L, 0L, 100L, 500L).forEach { step ->
                        MenuChip(
                            label = if (step == 0L) "重置" else if (step > 0) "+${step}ms" else "${step}ms",
                            width = 90.dp,
                            active = step == 0L && subtitleOffsetMs == 0L,
                            activeColor = Accent,
                            onClick = { onSubtitleOffset(if (step == 0L) 0L else subtitleOffsetMs + step) },
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
