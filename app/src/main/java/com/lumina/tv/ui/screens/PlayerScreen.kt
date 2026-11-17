package com.lumina.tv.ui.screens

import com.lumina.tv.data.catchingRequest
import com.lumina.tv.data.PlaybackStartIntent
import com.lumina.tv.data.PlaybackProgressSession
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.TimelineOrigin

import android.os.Build
import android.text.format.DateFormat
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
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
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.data.WideImageCandidate
import com.lumina.tv.data.wideImageCandidates
import com.lumina.tv.data.chooseSimilar
import com.lumina.tv.data.toSourceOption
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.player.SubtitleRenderersFactory
import com.lumina.tv.player.MpvPlayerController
import com.lumina.tv.player.MpvAudioDiagnostics
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.player.PlaybackErrorCategory
import com.lumina.tv.player.PlaybackRangeProbe
import com.lumina.tv.player.classifyPlaybackError
import com.lumina.tv.player.OptionalVttDataSourceFactory
import com.lumina.tv.player.PlaybackPath
import com.lumina.tv.player.PlaybackPathFailure
import com.lumina.tv.player.PlaybackPathFailures
import com.lumina.tv.player.PlaybackDelivery
import com.lumina.tv.player.PlaybackFailureReason
import com.lumina.tv.player.selectPlaybackEngine
import com.lumina.tv.player.PlaybackRecoveryBudget
import com.lumina.tv.player.PlaybackWatchdog
import com.lumina.tv.player.WatchdogReason
import com.lumina.tv.player.StabilityMetrics
import com.lumina.tv.player.AudioFocusGuard
import com.lumina.tv.player.AudioCapabilities
import com.lumina.tv.player.AudioCodecMatrix
import com.lumina.tv.player.AudioRouteMonitor
import com.lumina.tv.player.AudioSupport
import com.lumina.tv.player.AudioTrackCandidate
import com.lumina.tv.player.bestAudioTrack
import com.lumina.tv.player.normalizeLanguage
import com.lumina.tv.player.preferredAudioStream
import com.lumina.tv.player.resolveSubtitlePreference
import com.lumina.tv.player.sourceHint
import com.lumina.tv.player.detect
import com.lumina.tv.diag.PageLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun formatTime(ms: Long): String {
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

private fun audioMime(codec: String?): String? {
    val value = codec.orEmpty().lowercase()
    return when {
        value.contains("eac3") || value.contains("e-ac-3") -> if (value.contains("joc") || value.contains("atmos")) "audio/eac3-joc" else "audio/eac3"
        value.contains("truehd") || value.contains("mlp") -> "audio/true-hd"
        value.contains("dts") && (value.contains("uhd") || value.contains("p2")) -> "audio/vnd.dts.uhd;profile=p2"
        value.contains("dts") && (value.contains("hd") || value.contains("ma") || value.contains("lbr")) -> "audio/vnd.dts.hd"
        value.contains("dts") || value.contains("dca") -> "audio/vnd.dts"
        value.contains("ac3") -> "audio/ac3"
        value.contains("ac4") -> "audio/ac4"
        value.contains("aac") || value.contains("mp4a") -> "audio/mp4a-latm"
        value.contains("opus") -> "audio/opus"
        value.contains("mp3") || value.contains("mpeg") -> "audio/mpeg"
        value.contains("pcm") || value.contains("lpcm") -> "audio/raw"
        value.contains("flac") -> "audio/flac"
        value.contains("vorbis") -> "audio/vorbis"
        else -> null
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
    startIntent: PlaybackStartIntent = PlaybackStartIntent.Resume,
    onBack: () -> Unit,
    onPlayNext: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    var audioCaps by remember(item.id) { mutableStateOf(runCatching { detect(context) }.getOrDefault(AudioCapabilities(emptyMap()))) }
    var audioSupport by remember(item.id) { mutableStateOf(AudioSupport.DECODE_ONLY) }
    val client = AppRuntime.client
    var failedBufferingImages by remember(item.id, client) { mutableStateOf<Set<String>>(emptySet()) }
    var tmdbBackdrop by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        tmdbBackdrop = runCatching {
            TmdbClient.lookup(item.type, item.seriesName ?: item.name, item.year)?.backdropUrl
        }.getOrNull()
    }
    val playerExceptionHandler = remember { CoroutineExceptionHandler { _, error -> android.util.Log.e("LuminaTV", "Player coroutine failed", error) } }
    val scope = rememberCoroutineScope { playerExceptionHandler }
    LaunchedEffect(Unit) { PlaybackCache.autoTrim(context) }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlHasFocus by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var clockText by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(true) }
    var isActuallyStalled by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showStallOverlay by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var estimatedBitrate by remember { mutableLongStateOf(0L) }
    var seekStepSeconds by remember { mutableIntStateOf(AppRuntime.prefs?.seekStepSeconds ?: 10) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(AppRuntime.prefs?.defaultSpeed ?: 1.0f) }
    var optionMenu by remember { mutableStateOf(false) }
    var quickPanel by remember { mutableStateOf(QuickPanel.NONE) }
    var exitPromptAt by remember { mutableLongStateOf(0L) }
    var showExitPrompt by remember { mutableStateOf(false) }
    var seasons by remember(item.seriesId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var quickEpisodes by remember(item.seriesId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedSeasonId by remember(item.seriesId, item.seasonId) { mutableStateOf(item.seasonId) }
    var seasonsLoading by remember { mutableStateOf(false) }
    var episodesLoading by remember { mutableStateOf(false) }
    var degraded by remember { mutableStateOf(false) }
    var audioDecodeMode by remember { mutableStateOf(AppRuntime.prefs?.audioDecodeMode ?: "auto") }
    var audioDecodeNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            val formatter = SimpleDateFormat(pattern, Locale.getDefault())
            while (true) {
                clockText = formatter.format(Date())
                delay(1000)
            }
        }
    }
    var softDecodeNoticeShown by remember(item.id) { mutableStateOf(false) }
    var rebuildRetry by remember(item.id) { mutableStateOf(false) }
    // 用户手动选过内核、或已确认 media3 解不了时，把内核钉住，后续重载不再强行弹回 media3
    var enginePinned by remember(item.id) { mutableStateOf<String?>(null) }
    // The resolved path selects the engine before loading; this is only the initial view.
    var activeEngine by remember(item.id) { mutableStateOf("media3") }
    var mpvBackend by remember(item.id) { mutableStateOf<MpvPlayerController?>(null) }
    var mpvState by remember(item.id) { mutableStateOf(MpvPlayerController.State()) }
    var currentSurface by remember(item.id) { mutableStateOf<Surface?>(null) }
    var currentSurfaceSize by remember(item.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var reloadKey by remember(item.id) { mutableIntStateOf(0) }
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
        mediaSources = catchingRequest { AppRuntime.client?.getMediaSources(item.id) }.getOrDefault(emptyList()).orEmpty()
    }
    var reloadForceTranscode by remember(item.id) { mutableStateOf(AppRuntime.prefs?.forceTranscode ?: false) }
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
    var networkRetryCount by remember(item.id) { mutableIntStateOf(0) }
    var sessionGeneration by remember(item.id) { mutableIntStateOf(0) }
    val watchdog = remember(item.id) { PlaybackWatchdog() }
    var recoveryBudget by remember(item.id) { mutableStateOf(PlaybackRecoveryBudget()) }
    val pathFailures = remember(item.id) { PlaybackPathFailures() }
    var currentPath by remember(item.id) { mutableStateOf<PlaybackPath?>(null) }
    var reloadPending by remember(item.id) { mutableStateOf(false) }
    var reloadUsesRecoveryAttempt by remember(item.id) { mutableStateOf(false) }
    val audioMpvAttempts = remember(item.id) { mutableSetOf<PlaybackPath>() }
    val audioMpvFailures = remember(item.id) { mutableSetOf<PlaybackPath>() }
    val audioAlternativeAttempts = remember(item.id) { mutableSetOf<String>() }
    var audioRecoveryJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var audioRecoveryTrack by remember(item.id) { mutableStateOf<AudioTrackCandidate?>(null) }
    var audioRecoverySourceId by remember(item.id) { mutableStateOf<String?>(null) }
    var media3AudioCandidates by remember(item.id) { mutableStateOf<List<AudioTrackCandidate>>(emptyList()) }
    var media3AudioSourceId by remember(item.id) { mutableStateOf<String?>(null) }
    var recovering by remember(item.id) { mutableStateOf(false) }
    var loadStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var readyAt by remember(item.id) { mutableLongStateOf(0L) }
    var firstFrameAt by remember(item.id) { mutableLongStateOf(0L) }
    var bytesLoaded by remember(item.id) { mutableLongStateOf(0L) }
    var pendingStartIntent by remember(item.id) { mutableStateOf<PlaybackStartIntent?>(startIntent) }
    var desiredPlaying by remember(item.id) { mutableStateOf(true) }
    var pendingPaused by remember(item.id) { mutableStateOf<Boolean?>(null) }
    var currentTarget by remember(item.id) { mutableStateOf<EmbyClient.PlaybackTarget?>(null) }
    var lastRequestedStartMs by remember(item.id) { mutableLongStateOf(0L) }
    var loadJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var phaseTimes by remember(item.id) { mutableStateOf(JSONObject()) }
    var droppedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var renderedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingCount by remember(item.id) { mutableIntStateOf(0) }
    var bufferingDurationMs by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var rebufferStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var loadingStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var loadingCount by remember(item.id) { mutableIntStateOf(0) }
    var loadingDurationMs by remember(item.id) { mutableLongStateOf(0L) }
    var lastPlaybackErrorJson by remember(item.id) { mutableStateOf<String?>(null) }
    var dvDecoderPath by remember(item.id) { mutableStateOf<String?>(null) }
    var activeVideoDecoder by remember(item.id) { mutableStateOf<String?>(null) }
    var firstFrameRendered by remember(item.id) { mutableStateOf(false) }
    var readyWithoutFrameSince by remember(item.id) { mutableLongStateOf(0L) }
    var playbackStatusNotice by remember(item.id) { mutableStateOf<String?>(null) }
    var failurePhase by remember(item.id) { mutableStateOf("未知") }
    var currentSourceId by remember(item.id) { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var boosting by remember { mutableStateOf(false) }
    var nextTriggered by remember(item.id) { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var prewarmedEpisodeId by remember(item.id) { mutableStateOf<String?>(null) }
    var prewarmJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentResolution by remember { mutableStateOf<String?>(null) }
    val seekStepMs = seekStepSeconds * 1000L
    var subScale by remember { mutableFloatStateOf(AppRuntime.prefs?.subtitleScale ?: 1.0f) }
    var subtitleColor by remember { mutableIntStateOf(AppRuntime.prefs?.subtitleColor ?: android.graphics.Color.WHITE) }
    var subtitleOutline by remember { mutableStateOf(AppRuntime.prefs?.subtitleOutline ?: true) }
    var subtitlePosition by remember { mutableFloatStateOf(AppRuntime.prefs?.subtitlePosition ?: 0.08f) }
    var subtitleBg by remember { mutableStateOf(AppRuntime.prefs?.subtitleBg ?: false) }
    var subtitlePreference by remember(item.id) { mutableStateOf(AppRuntime.prefs?.subtitlePreferenceFor(item.id)) }
    var subtitleOffsetMs by remember { mutableLongStateOf(AppRuntime.prefs?.subtitleOffsetMs ?: 0L) }
    var autoNextJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var nextEpisode by remember(item.id) { mutableStateOf<MediaItem?>(null) }
    var nextCountdown by remember(item.id) { mutableIntStateOf(0) }
    var seekHoldDirection by remember { mutableIntStateOf(0) }
    var seekHoldActive by remember { mutableStateOf(false) }
    var seekHoldDelta by remember { mutableLongStateOf(0L) }
    var seekHoldJob by remember { mutableStateOf<Job?>(null) }
    var seekInFlight by remember { mutableStateOf(false) }
    var pendingSeekTarget by remember { mutableStateOf<Long?>(null) }
    var seekTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var seekLastKeyAt by remember { mutableLongStateOf(0L) }
    var chapters by remember { mutableStateOf<List<MediaChapter>>(emptyList()) }
    var playbackMarkers by remember(item.id) { mutableStateOf<PlaybackMarkers?>(null) }
    var chapterPanel by remember { mutableStateOf(false) }
    var previewTargetMs by remember(item.id) { mutableStateOf<Long?>(null) }
    var previewFrame by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    var previewActive by remember(item.id) { mutableStateOf(false) }
    var previewDisabled by remember(item.id) { mutableStateOf(false) }
    var lastHoldPreviewAt by remember(item.id) { mutableLongStateOf(0L) }
    var subtitleSelectionApplied by remember(item.id) { mutableStateOf(false) }
    var audioSelectionApplied by remember(item.id) { mutableStateOf(false) }
    var selectedAudioStreamIndex by remember(item.id) { mutableStateOf(AppRuntime.prefs?.audioTrackFor(item.id)) }
    var progressSession by remember(item.id, client) { mutableStateOf<PlaybackProgressSession?>(null) }

    fun reportStopInAppScope(positionMs: Long) {
        val stopClient = client ?: return
        val session = progressSession ?: return
        // Admission and closing are synchronous; HTTP belongs to the client's application sender.
        stopClient.reportStop(session, positionMs.coerceAtLeast(0L), paused = !isPlaying)
    }

    LaunchedEffect(error, progressSession) {
        if (error != null) reportStopInAppScope(position)
    }

    val introSeconds = playbackMarkers?.introEndMs?.takeIf { it > 0L && (duration <= 0L || it < duration) }
        ?.div(1000L)?.toInt()
        ?: (AppRuntime.prefs?.introSecondsFor(item.id) ?: 0)
    val creditsStartMs = playbackMarkers?.creditsStartMs
    val currentCreditsStartMs by rememberUpdatedState(creditsStartMs)

    fun prewarmNext(next: MediaItem) {
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var ok = false
            PageLog.add("prewarm start nextId=${next.id} engine=$activeEngine")
            try {
                client?.prewarmPlayback(
                    next,
                    audioStreamIndex = AppRuntime.prefs?.audioTrackFor(next.id),
                    strategy = AppRuntime.prefs?.sourceStrategy ?: com.lumina.tv.data.SourceStrategy(),
                )
                ok = client != null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The completion log records failure without exception text, URLs or credentials.
            } finally {
                PageLog.add("prewarm done nextId=${next.id} ok=$ok ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
            }
        }
    }

    fun matchDisplayFrameRate(surface: Surface?, fps: Float?) {
        if (AppRuntime.prefs?.matchDisplayFrameRate != true || Build.VERSION.SDK_INT < 30 || surface == null || fps == null || fps <= 0f) return
        runCatching {
            surface.setFrameRate(
                fps,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        }.onFailure { failure ->
            PageLog.add("setFrameRate failed fps=$fps err=${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    // 片名优先用透明 Logo：电影取自身，剧集取详情页缓存的剧集信息（通常已命中）
    val titleLogo = remember(item.id) {
        val c = AppRuntime.client
        val own = c?.logoUrl(item)
        if (own != null) own
        else item.seriesId?.let { sid -> LibraryCache.detailStale(sid)?.item?.let { c?.logoUrl(it) } }
    }
    val displayTitle = buildString {
        append(item.seriesName ?: item.name)
        if (item.seriesId != null && item.indexNumber != null) {
            append(" · S")
            append((item.parentIndexNumber ?: 0).toString().padStart(2, '0'))
            append("E")
            append(item.indexNumber.toString().padStart(2, '0'))
        }
    }

    val hideFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekBarFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val speedFirstFocus = remember { FocusRequester() }
    val seasonFocuses = remember(item.seriesId) { mutableMapOf<String, FocusRequester>() }
    val episodeItemFocuses = remember(item.seriesId) { mutableMapOf<String, FocusRequester>() }
    var quickContentHasFocus by remember { mutableStateOf(false) }
    var restoreMenuFocusUntil by remember { mutableLongStateOf(0L) }
    var titleLogoFailed by remember(item.id) { mutableStateOf(false) }

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

    val exo = remember(item.id, rebuildRetry, fastStart) {
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
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                playWhenReady = true
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, resolveSubtitlePreference(
                        AppRuntime.prefs?.subtitlePreferenceFor(item.id),
                        AppRuntime.prefs?.preferredSubtitleLanguage,
                        emptyList(),
                    ) == "off")
                    .build()
            }
    }

    // Work that touches a player belongs to that exact instance, even across suspension.
    val currentExo by rememberUpdatedState(exo)
    val instanceJob = remember(exo, item.id) { SupervisorJob(scope.coroutineContext[Job]) }
    val instanceScope = remember(instanceJob) { CoroutineScope(scope.coroutineContext + instanceJob) }
    var media3LoadGeneration by remember(exo, item.id) { mutableIntStateOf(-1) }
    fun isCurrentLoad(generation: Int): Boolean =
        instanceJob.isActive && currentExo === exo && generation == sessionGeneration
    fun isCurrentMedia3(): Boolean = !reloadPending && activeEngine == "media3" &&
        isCurrentLoad(media3LoadGeneration) && exo.currentMediaItem?.mediaId == "${item.id}:$media3LoadGeneration"
    fun isCurrentMedia3Event(eventTime: AnalyticsListener.EventTime): Boolean {
        if (!isCurrentMedia3() || eventTime.timeline.isEmpty) return false
        val window = androidx.media3.common.Timeline.Window()
        if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return false
        return eventTime.timeline.getWindow(eventTime.windowIndex, window).mediaItem.mediaId == "${item.id}:$media3LoadGeneration"
    }

    DisposableEffect(exo, item.id) {
        onDispose {
            instanceJob.cancel()
            runCatching { exo.release() }
        }
    }


    // 当前剧的集列表（切集用），优先缓存
    LaunchedEffect(item.seriesId) {
        val sid = item.seriesId ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        LibraryCache.episodesOf(sid, item.seasonId)?.let { episodes = it; return@LaunchedEffect }
        episodes = catchingRequest { c.getEpisodes(sid, item.seasonId) }.getOrDefault(emptyList())
        LibraryCache.putEpisodes(sid, item.seasonId, episodes)
    }

    LaunchedEffect(item.id) {
        chapters = catchingRequest { client?.getChapters(item.id) }.getOrNull().orEmpty()
        playbackMarkers = catchingRequest { client?.getPlaybackMarkers(item.id) }.getOrNull()
    }

    LaunchedEffect(item.id, previewTargetMs, previewActive) {
        val target = previewTargetMs ?: return@LaunchedEffect
        if (!previewActive || previewDisabled || client == null) return@LaunchedEffect
        delay(160)
        previewFrame = catchingRequest { client.getTrickplayFrame(item.id, target) }.getOrNull()
    }

    fun cancelAutoNext() {
        autoNextJob?.cancel()
        autoNextJob = null
        nextEpisode = null
        nextCountdown = 0
    }

    fun switchEpisode(next: MediaItem, reportStop: Boolean = true) {
        val playNext = onPlayNext ?: return
        cancelAutoNext()
        sessionGeneration++
        loadJob?.cancel()
        rangeProbeJob?.cancel()
        audioRecoveryJob?.cancel()
        watchdog.reset()
        if (reportStop) reportStopInAppScope(position)
        playNext(next)
        // The new item owns its load timing and generation; no countdown resets them.
    }

    fun startAutoNext(reportStop: Boolean = true) {
        if (nextTriggered || AppRuntime.prefs?.autoNextEpisode != true || item.seriesId == null) return
        nextTriggered = true
        val generation = sessionGeneration
        autoNextJob = instanceScope.launch {
            val c = client ?: return@launch
            val next = c.getNextEpisode(item) ?: return@launch
            if (!isCurrentLoad(generation)) return@launch
            nextEpisode = next
            nextCountdown = ((AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) / 10).coerceIn(1, 6)
            while (nextCountdown > 0) {
                delay(1000)
                if (!isCurrentLoad(generation) || nextEpisode?.id != next.id) return@launch
                nextCountdown--
            }
            if (nextEpisode?.id == next.id) switchEpisode(next, reportStop)
        }
    }

    fun updateStalled(stalled: Boolean) {
        if (isActuallyStalled == stalled) return
        isActuallyStalled = stalled
        showStallOverlay = false
        val now = android.os.SystemClock.elapsedRealtime()
        PageLog.add("playbackStall active=$stalled engine=$activeEngine playing=$isPlaying loading=$isLoading")
        if (stalled) {
            bufferingStartedAt = now
            bufferingCount++
            if (firstFrameRendered && seekHint == null && !seekHoldActive && !previewActive) {
                StabilityMetrics.rebufferCount++
                rebufferStartedAt = now
            }
        } else {
            if (bufferingStartedAt > 0L) bufferingDurationMs += now - bufferingStartedAt
            if (rebufferStartedAt > 0L) StabilityMetrics.rebufferDurationMs += now - rebufferStartedAt
            bufferingStartedAt = 0L
            rebufferStartedAt = 0L
        }
    }

    LaunchedEffect(isActuallyStalled, firstFrameRendered, seekHint, seekHoldActive, previewActive, seekInFlight) {
        showStallOverlay = false
        if (isActuallyStalled && seekHint == null && !seekHoldActive && !previewActive && !seekInFlight) {
            // Keep the last frame visible across brief seeks and short buffer transitions.
            if (firstFrameRendered) delay(4_000)
            showStallOverlay = true
        }
    }

    fun updateLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        watchdog.onLoadingChanged(loading)
        val now = android.os.SystemClock.elapsedRealtime()
        if (loading) {
            loadingCount++
            loadingStartedAt = now
        } else if (loadingStartedAt > 0L) {
            loadingDurationMs += now - loadingStartedAt
            loadingStartedAt = 0L
        }
        phaseTimes.put("downloadLoadingCount", loadingCount).put("downloadLoadingDurationMs", loadingDurationMs)
    }

    fun requestPlaybackReload(positionMs: Long, generation: Int) {
        if (!isCurrentLoad(generation)) {
            PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration")
            return
        }
        if (reloadPending) return
        reportStopInAppScope(positionMs)
        reloadPending = true
        reloadUsesRecoveryAttempt = recovering
        media3LoadGeneration = -1
        pendingStartIntent = PlaybackStartIntent.Restore(positionMs.coerceAtLeast(0L))
        pendingPaused = !desiredPlaying
        reloadKey++
    }

    fun recordPathFailure(reason: PlaybackFailureReason, detail: String) {
        val path = currentPath ?: return
        pathFailures.record(PlaybackPathFailure(path, reason, sessionGeneration, detail))
        PageLog.add("pathFailed source=${path.sourceId} engine=${path.engine} path=${path.delivery} audio=${path.audioTrack} reason=$reason")
    }

    fun failureDiagnostics(): JSONArray = JSONArray(pathFailures.snapshot().map { failure ->
            JSONObject().put("source", failure.path.sourceId).put("engine", failure.path.engine)
                .put("delivery", failure.path.delivery.name).put("audioTrack", failure.path.audioTrack)
                .put("audioMode", failure.path.audioMode).put("requestVariant", failure.path.requestVariant)
                .put("reason", failure.reason.name).put("generation", failure.generation).put("detail", failure.detail)
        })

    fun beginManualAttempt() {
        audioRecoveryJob?.cancel()
        audioRecoveryJob = null
        recoveryBudget = PlaybackRecoveryBudget()
        pathFailures.clearForManualRetry()
        audioMpvAttempts.clear()
        audioMpvFailures.clear()
        audioAlternativeAttempts.clear()
        networkRetryCount = 0
        triedSources = emptySet()
        recovering = false
    }

    // Audio, watchdog, HTTP retries and engine fallback all enter the same budget once.
    fun recoverAutomatically(
        reason: String,
        generation: Int = sessionGeneration,
        minimumDelayMs: Long = 0L,
        stillNeeded: () -> Boolean = { true },
        action: () -> Unit,
    ) {
        if (!isCurrentLoad(generation) || reloadPending || audioRecoveryJob?.isActive == true) return
        if (recoveryBudget.exhausted) {
            mpvBackend?.release("recovery-budget-exhausted")
            mpvBackend = null
            media3LoadGeneration = -1
            exo.pause()
            updateStalled(false)
            playbackStatusNotice = null
            error = "自动恢复次数已用完，请重试或更换片源、音轨"
            return
        }
        audioRecoveryJob = instanceScope.launch {
            val waitMs = maxOf(minimumDelayMs, recoveryBudget.cooldownRemainingMs(android.os.SystemClock.elapsedRealtime()))
            if (waitMs > 0L) {
                playbackStatusNotice = "播放恢复需要稍候，将自动继续尝试"
                delay(waitMs)
            }
            if (!isCurrentLoad(generation)) return@launch
            if (!stillNeeded()) {
                audioRecoveryJob = null
                playbackStatusNotice = null
                return@launch
            }
            if (!recoveryBudget.tryAcquire(android.os.SystemClock.elapsedRealtime())) return@launch
            audioRecoveryJob = null
            recovering = true
            error = null
            playbackStatusNotice = null
            watchdog.reset()
            PageLog.add("automaticRecovery reason=$reason attempts=${recoveryBudget.attempts} gen=$generation")
            action()
            recovering = false
        }
    }

    fun requestAutomaticReload(positionMs: Long, generation: Int, reason: String) {
        recoverAutomatically(reason, generation) { requestPlaybackReload(positionMs, generation) }
    }

    fun audioSourceKey(): String = currentSourceId ?: playbackSource?.id ?: item.id

    fun canTryMpvPath(): Boolean = currentPath?.let { path ->
        selectPlaybackEngine(AppRuntime.prefs?.playerEngine, "mpv", false, false, path, pathFailures) == "mpv"
    } == true

    fun audioMpvKey(): PlaybackPath? = currentPath?.copy(engine = "mpv")

    fun audioFailureMessage(): String {
        val codec = playbackSource?.audioStreams?.let { streams ->
            streams.firstOrNull { it.index == selectedAudioStreamIndex } ?: streams.firstOrNull()
        }?.codec?.uppercase() ?: "未知编码"
        val sourceJson = runCatching {
            val sources = JSONObject(client?.lastPlaybackInfoJson.orEmpty()).optJSONArray("MediaSources")
            (0 until (sources?.length() ?: 0)).mapNotNull { sources?.optJSONObject(it) }
                .firstOrNull { it.optString("Id") == currentSourceId }
        }.getOrNull()
        val serverMessage = when {
            sourceJson == null -> "未取得可用的服务器转码信息"
            !sourceJson.optBoolean("SupportsTranscoding", false) || sourceJson.optString("TranscodingUrl").isBlank() ->
                "服务器也未提供转码"
            else -> "服务器转码也未能恢复播放"
        }
        val recoveryMessage = if (audioMpvKey() in audioMpvAttempts || audioMpvKey() in audioMpvFailures)
            "软解未能恢复" else "自动恢复次数已用完"
        return "该片音轨（$codec）本机无法硬解，$recoveryMessage，$serverMessage。请换片源或音轨。"
    }

    fun finishAudioRecovery(reason: String) {
        updateStalled(false)
        playbackStatusNotice = null
        error = audioFailureMessage()
        audioDecodeNotice = error
        PageLog.add("audioRecovery action=terminal reason=$reason attempts=${recoveryBudget.attempts}")
    }

    fun requestAudioRecovery(reason: String) {
        if (audioRecoveryJob?.isActive == true) return
        val sourceKey = audioSourceKey()
        val mpvKey = audioMpvKey() ?: return
        if (activeEngine == "mpv" && mpvKey !in audioMpvFailures) return
        recordPathFailure(PlaybackFailureReason.AUDIO_OUTPUT, reason)
        val tryMpv = activeEngine != "mpv" && mpvKey !in audioMpvAttempts && mpvKey !in audioMpvFailures &&
            canTryMpvPath()
        if (tryMpv && reason != "manual-unsupported") {
            // Exclude the track that actually failed. A manual unsupported choice must
            // still be able to return to the previously playing, supported track.
            media3AudioCandidates.filter { candidate ->
                exo.currentTracks.groups.any { group ->
                    group.mediaTrackGroup == candidate.group && group.isTrackSelected(candidate.index)
                }
            }.forEach { audioAlternativeAttempts += "$sourceKey:${it.group.id}:${it.index}" }
        }
        val alternative = media3AudioCandidates.takeIf { media3AudioSourceId == sourceKey }
            ?.firstOrNull { candidate ->
                candidate.supported && "$sourceKey:${candidate.group.id}:${candidate.index}" !in audioAlternativeAttempts &&
                    exo.currentTracks.groups.any { group ->
                        group.mediaTrackGroup == candidate.group && group.isTrackSupported(candidate.index)
                    }
            }
        val sourceJson = runCatching {
            val sources = JSONObject(client?.lastPlaybackInfoJson.orEmpty()).optJSONArray("MediaSources")
            (0 until (sources?.length() ?: 0)).mapNotNull { sources?.optJSONObject(it) }
                .firstOrNull { it.optString("Id") == currentSourceId }
        }.getOrNull()
        val canTranscode = sourceJson?.optBoolean("SupportsTranscoding", false) == true &&
            !sourceJson.optString("TranscodingUrl").isNullOrBlank() && !transcodeFallbackTried
        if (recoveryBudget.exhausted || (!tryMpv && alternative == null && !canTranscode)) {
            finishAudioRecovery(if (recoveryBudget.exhausted) "budget-exhausted" else "no-playable-audio")
            return
        }
        val generation = sessionGeneration
        val resume = position
        recoverAutomatically("audio:$reason", generation) {
            error = null
            playbackStatusNotice = null
            reloadForceTranscode = false
            updateStalled(true)
            if (tryMpv) {
                audioMpvAttempts += mpvKey
                audioDecodeNotice = "当前音轨本机无可用解码器，已切换软解内核"
                engineSwitchTrace.add("audio-recovery:mpv:$reason")
                PageLog.add("audioRecovery action=mpv reason=$reason attempts=${recoveryBudget.attempts}")
                enginePinned = "mpv"
                mpvState = MpvPlayerController.State()
                requestPlaybackReload(resume, generation)
            } else {
                enginePinned = "media3"
                mpvBackend?.release("audio-recovery")
                mpvBackend = null
                if (alternative != null) {
                    audioAlternativeAttempts += "$sourceKey:${alternative.group.id}:${alternative.index}"
                    audioRecoveryTrack = alternative
                    audioRecoverySourceId = sourceKey
                    audioDecodeNotice = "软解未能播放，正在切换本机支持的音轨"
                    PageLog.add("audioRecovery action=supported-track index=${alternative.index} attempts=${recoveryBudget.attempts}")
                } else {
                    transcodeFallbackTried = true
                    reloadForceTranscode = true
                    audioDecodeNotice = "本机无法播放该音轨，正在尝试服务器转码"
                    PageLog.add("audioRecovery action=transcode attempts=${recoveryBudget.attempts}")
                }
                requestPlaybackReload(resume, generation)
            }
        }
    }

    fun recordAudioMpvFailure(): Boolean {
        val key = audioMpvKey() ?: return false
        val firstFailure = audioMpvFailures.add(key)
        // Keep A1's supported-track/transcode chain after an mpv audio-path failure.
        if (key !in audioMpvAttempts) return false
        mpvBackend?.release("audio-mpv-failed")
        mpvBackend = null
        if (!firstFailure) return true
        PageLog.add("audioRecovery action=mpv-failed attempts=${recoveryBudget.attempts}")
        requestAudioRecovery("mpv-failed")
        return true
    }

    fun handleMpvFailure(kind: MpvPlayerController.FailureKind, detail: String, generation: Int) {
        if (!isCurrentLoad(generation) || reloadPending) return
        val reason = when (kind) {
            MpvPlayerController.FailureKind.INITIALIZATION -> PlaybackFailureReason.INITIALIZATION
            MpvPlayerController.FailureKind.SURFACE -> PlaybackFailureReason.SURFACE
            MpvPlayerController.FailureKind.HTTP -> PlaybackFailureReason.HTTP
            MpvPlayerController.FailureKind.DECODING -> PlaybackFailureReason.DECODING
            MpvPlayerController.FailureKind.FIRST_FRAME_TIMEOUT -> PlaybackFailureReason.FIRST_FRAME_TIMEOUT
            MpvPlayerController.FailureKind.LOAD -> PlaybackFailureReason.LOAD
        }
        recordPathFailure(reason, detail)
        if (recordAudioMpvFailure()) return
        mpvBackend?.release("mpv-failed:$reason")
        mpvBackend = null
        updateStalled(false)
        failurePhase = when (kind) {
            MpvPlayerController.FailureKind.HTTP -> "连接"
            MpvPlayerController.FailureKind.SURFACE -> "显示层"
            MpvPlayerController.FailureKind.INITIALIZATION -> "内核初始化"
            MpvPlayerController.FailureKind.FIRST_FRAME_TIMEOUT -> "首帧"
            else -> "解码"
        }
        audioDecodeNotice = "$detail，正在尝试可用播放路径"
        reloadForceTranscode = currentPath?.delivery == PlaybackDelivery.TRANSCODE
        requestAutomaticReload(position, generation, "mpv:$reason")
    }

    LaunchedEffect(item.id, mediaSources, initialSourceId, currentSourceId) {
        if (initialSourceId == null && currentSourceId == null) {
            val remembered = AppRuntime.prefs?.lastSourceFor(item.id)
            if (remembered != null && mediaSources.any { it.id == remembered }) {
                currentSourceId = remembered
                if (sessionGeneration > 0 && playbackSource?.id != remembered) {
                    requestPlaybackReload(position, sessionGeneration)
                }
            }
        }
    }

    val latestAudioRouteHandler by rememberUpdatedState<(AudioCapabilities) -> Unit> { changedCaps ->
            audioCaps = changedCaps
            val currentAudio = (playbackSource ?: mediaSources.firstOrNull { it.id == currentSourceId })
                ?.audioStreams?.firstOrNull { it.isDefault }
                ?: playbackSource?.audioStreams?.firstOrNull()
            val codec = listOfNotNull(currentAudio?.codec, currentAudio?.profile).joinToString(" ").lowercase()
            val mime = audioMime(codec)
            val enc = AudioCodecMatrix.encoding(mime, codec)
            audioSupport = AudioCodecMatrix.support(context, enc, currentAudio?.channels ?: 2, currentAudio?.sampleRate ?: 0, changedCaps, mime)
            audioDecodeNotice = when (audioSupport) {
                AudioSupport.PASSTHROUGH -> "音频输出已变化：当前音轨可直通"
                AudioSupport.DECODE_ONLY -> "音频输出已变化：改为本地解码"
                AudioSupport.UNSUPPORTED -> "音频输出已变化：当前音轨可能无法播放"
            }
            if (audioDecodeMode == "passthrough" && audioSupport != AudioSupport.PASSTHROUGH) {
                audioDecodeMode = "auto"
                AppRuntime.prefs?.audioDecodeMode = "auto"
                enginePinned = null
                requestPlaybackReload(position, sessionGeneration)
            }
    }
    DisposableEffect(context, item.id) {
        val monitor = AudioRouteMonitor(context) { changedCaps ->
            latestAudioRouteHandler(changedCaps)
        }
        monitor.register()
        onDispose { monitor.unregister() }
    }

    fun loadSource(
        forceTranscode: Boolean,
        sourceId: String? = currentSourceId,
        audioStreamIndex: Int? = AppRuntime.prefs?.audioTrackFor(item.id),
    ) {
        if (!instanceJob.isActive || currentExo !== exo) return
        val c = client ?: return
        reportStopInAppScope(position)
        // A native hw->sw retry is part of this same automatic path attempt.
        val recoveryAlreadyAcquired = reloadUsesRecoveryAttempt
        reloadUsesRecoveryAttempt = false
        reloadPending = false
        media3LoadGeneration = -1
        loadStartedAt = android.os.SystemClock.elapsedRealtime()
        readyAt = 0L
        firstFrameAt = 0L
        watchdog.reset()
        // Snapshot before releasing either engine. Zero is a valid restoration point.
        val loadIntent = pendingStartIntent ?: PlaybackStartIntent.Restore(position.coerceAtLeast(0L))
        val resumePosition = loadIntent.positionMs(item.positionTicks / 10_000L)
        val restorePaused = pendingPaused ?: !desiredPlaying
        pendingPaused = null
        lastRequestedStartMs = resumePosition
        audioRecoveryJob?.cancel()
        audioRecoveryJob = null
        cancelAutoNext()
        nextTriggered = false
        loadJob?.cancel()
        rangeProbeJob?.cancel()
        if (sessionGeneration > 0) StabilityMetrics.retryCount++
        val generation = ++sessionGeneration
        mpvBackend?.release("external-load-replaced")
        mpvBackend = null
        mpvState = MpvPlayerController.State()
        exo.pause()
        PageLog.add("playback reload gen=$generation fromGen=${generation - 1} positionMs=$resumePosition engine=$activeEngine")
        playStartAt = android.os.SystemClock.elapsedRealtime()
        phaseTimes = JSONObject().put("session", generation)
            .put("loadToReadyMs", JSONObject.NULL)
            .put("loadToFirstFrameMs", JSONObject.NULL)
            .put("readyToFirstFrameMs", JSONObject.NULL)
            .put("firstFrameMs", JSONObject.NULL)
        loadJob = instanceScope.launch {
            error = null
            updateStalled(false)
            firstFrameRendered = false
            updateLoading(false)
            updateStalled(true)
            readyWithoutFrameSince = 0L
            playbackStatusNotice = null
            bufferingTimedOut = false
            activeVideoDecoder = null
            dvDecoderPath = null
            failurePhase = "解析"
            try {
                if (forceTranscode) transcodeFallbackTried = true
                val maxStreamingBitrate = AppRuntime.prefs?.maxStreamingBitrate ?: 120_000_000L
                val strategy = AppRuntime.prefs?.sourceStrategy ?: com.lumina.tv.data.SourceStrategy()
                val cachedTarget = if (badHttpFallbackStage == 0) c.cachedPlaybackTarget(item.id, forceTranscode, sourceId, audioStreamIndex, forceDirectNext, maxStreamingBitrate, strategy,
                    loadIntent, resumePosition, manualAudioStreamSelection)
                    else null
                if (badHttpFallbackStage != 0) PageLog.add("prewarm miss nextId=${item.id} reason=fallback_request")
                val resolvedTarget = cachedTarget ?: c.resolvePlayback(
                        item, forceTranscode, sourceId, loadIntent, audioStreamIndex,
                        forceDirect = forceDirectNext,
                        removeSubtitleParams = badHttpFallbackStage == 1,
                        preferDirectStreamUrl = badHttpFallbackStage == 2,
                        manualAudioStreamSelection = manualAudioStreamSelection,
                        maxStreamingBitrate = maxStreamingBitrate,
                        strategy = strategy,
                    )
                val target = resolvedTarget
                if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                httpFactory.setDefaultRequestProperties(target.headers)
                phaseTimes.put("compatMappingMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
                negotiationDiagnostics = target.negotiationDiagnostics
                currentTarget = target
                position = target.startPositionMs
                lastRequestedStartMs = target.startPositionMs
                currentPlayMethod = target.playMethod
                PageLog.add("timeline requestStartMs=$resumePosition playMethod=${target.playMethod} direct=${target.direct} sourceId=${target.source?.id} url=${target.url.substringBefore('?').take(120)}")
                phaseTimes.put("requestedStartMs", resumePosition)
                    .put("playMethod", target.playMethod)
                    .put("direct", target.direct)
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
                rangeProbeJob = instanceScope.launch {
                    val probeResult = runCatching { PlaybackRangeProbe.probe(target.url, target.headers) }
                        .getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
                    if (isCurrentLoad(generation)) {
                        rangeProbeDiagnostics = probeResult
                        phaseTimes.put("rangeProbe", probeResult)
                    }
                }
                if (!isCurrentLoad(generation)) return@launch
                val defaultAudio = target.source.audioStreams.firstOrNull { it.index == target.audioStreamIndex }
                    ?: target.source?.audioStreams?.firstOrNull()
                val audioCodec = listOfNotNull(defaultAudio?.codec, defaultAudio?.profile).joinToString(" ").lowercase()
                val codecMime = audioMime(audioCodec)
                val audioEncoding = AudioCodecMatrix.encoding(codecMime, audioCodec)
                audioSupport = AudioCodecMatrix.support(
                    context, audioEncoding, defaultAudio?.channels ?: 2, defaultAudio?.sampleRate ?: 0, audioCaps,
                    codecMime,
                )
                // 统一走 media3 优先：不再因为音轨编码就提前把内核换成 mpv，
                // 由 media3 真正解码失败后再自动回落，避免“什么都用 MPV 转圈”。
                // DV Profile 7 是双层封装，media3 解不了基底+增强层，直接交给 mpv
                val videoStreamForDv = target.source?.streams?.firstOrNull { it.type == "Video" }
                val rangeType = videoStreamForDv?.videoRangeType.orEmpty().uppercase()
                val isDolbyVision = rangeType.contains("DOVI") || videoStreamForDv?.dvProfile != null
                val isDvP7 = isDolbyVision &&
                    (videoStreamForDv?.dvProfile == 7 || rangeType.contains("WITHEL") || rangeType.contains("WITH EL"))
                val sourceKey = target.source?.id ?: sourceId ?: item.id
                val plannedAudio = audioRecoveryTrack?.takeIf { audioRecoverySourceId == sourceKey }
                    ?.let { "media3:${it.group.id}:${it.index}" }
                    ?: preferredAudioStream(audioStreamIndex, AppRuntime.prefs?.preferredAudioLanguage,
                        target.source?.audioStreams.orEmpty())?.index?.toString()
                    ?: audioStreamIndex?.toString()
                val path = PlaybackPath(sourceKey, activeEngine, when {
                    target.playMethod.equals("Transcode", true) -> PlaybackDelivery.TRANSCODE
                    target.playMethod.equals("DirectStream", true) -> PlaybackDelivery.DIRECT_STREAM
                    else -> PlaybackDelivery.DIRECT_PLAY
                }, plannedAudio, audioDecodeMode, badHttpFallbackStage)
                val selectedEngine = selectPlaybackEngine(AppRuntime.prefs?.playerEngine, enginePinned,
                    isDvP7, isDolbyVision, path, pathFailures)
                if (selectedEngine == null) {
                    updateStalled(false)
                    error = "当前片源的播放路径均已失败，请重试或更换片源、音轨"
                    return@launch
                }
                currentPath = path.copy(engine = selectedEngine)
                val video = target.source?.streams?.firstOrNull { it.type == "Video" }
                val videoResolution = video?.let { stream ->
                    if (stream.width != null && stream.height != null) "${stream.width}x${stream.height}" else null
                } ?: target.source?.resolution
                PageLog.add("engineDecision codec=${video?.codec} res=$videoResolution dvProfile=${video?.dvProfile} dvRange=${video?.videoRangeType} hwDv=${DeviceCodecCapabilities.supportsDolbyVision} engine=$selectedEngine")
                phaseTimes.put("engineDecision", JSONObject()
                    .put("codec", video?.codec ?: JSONObject.NULL)
                    .put("resolution", videoResolution ?: JSONObject.NULL)
                    .put("dvProfile", video?.dvProfile ?: JSONObject.NULL)
                    .put("videoRange", video?.videoRangeType ?: JSONObject.NULL)
                    .put("hwDv", DeviceCodecCapabilities.supportsDolbyVision)
                    .put("engine", selectedEngine))
                if (isDvP7 && !softDecodeNoticeShown) {
                    audioDecodeNotice = "Dolby Vision Profile 7，已用 mpv 软解"
                    softDecodeNoticeShown = true
                }
                if (isDolbyVision && !isDvP7 && !DeviceCodecCapabilities.supportsDolbyVision) {
                    dvDecoderPath = "hevc-fallback"
                }
                if (audioCodec.isNotBlank() && audioSupport == AudioSupport.UNSUPPORTED && !softDecodeNoticeShown) {
                    audioDecodeNotice = "当前音轨（${audioCodec.trim().ifBlank { "未知 codec" }}）本机可能无法解码，可切换其他音轨或改软解"
                    softDecodeNoticeShown = true
                }
                activeEngine = selectedEngine
                currentResolution = target.source?.resolution
                    ?: if (target.direct) null else "转码"
                val cacheLimit = AppRuntime.prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L
                PlaybackCache.setLimit(cacheLimit)
                val upstreamFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(12_000)
                    .setReadTimeoutMs(30_000)
                    .setUserAgent("LuminaTV/1.5 (Android)")
                    .setDefaultRequestProperties(target.headers)
                val cacheFactory = if (cacheLimit > 0L) PlaybackCache.dataSourceFactory(context, upstreamFactory) else null
                val customKey = if (targetIsHls) null else PlaybackCache.mediaKey(
                    AppRuntime.session?.baseUrl ?: AppRuntime.prefs?.baseUrl.orEmpty(),
                    AppRuntime.session?.userId ?: AppRuntime.prefs?.userId.orEmpty(),
                    item.id,
                    target.source?.id,
                )
                val subs = target.source?.streams
                    ?.filter { it.type == "Subtitle" && it.isExternal }
                    .orEmpty()
                val srcId = target.source?.id
                progressSession = c.reportStart(item.id, target.startPositionMs, srcId, target.playMethod,
                    target.playSessionId, paused = restorePaused)
                if (activeEngine == "mpv") {
                    mpvBackend?.release("new-external-load")
                    mpvState = MpvPlayerController.State()
                    val backend = MpvPlayerController(context, generation, onSoftwareRetry = { owner, load, needed, retry ->
                        if (mpvBackend === owner && isCurrentLoad(load.generation)) {
                            val retryWithProgressSession = {
                                if (needed()) {
                                    reportStopInAppScope(position)
                                    progressSession = c.reportStart(item.id, position, srcId, target.playMethod,
                                        target.playSessionId, paused = !desiredPlaying)
                                    retry()
                                }
                            }
                            if (recoveryAlreadyAcquired) {
                                if (needed()) {
                                    PageLog.add("automaticRecovery reuse=mpv-software-retry gen=$generation attempts=${recoveryBudget.attempts}")
                                    retryWithProgressSession()
                                }
                            } else {
                                recoverAutomatically("mpv-software-retry", generation, stillNeeded = needed, action = retryWithProgressSession)
                            }
                        }
                    }) { owner, load, state ->
                        if (!isCurrentLoad(load.generation) || reloadPending || mpvBackend !== owner || activeEngine != "mpv") {
                            PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration")
                            return@MpvPlayerController
                        }
                        mpvState = state
                        if (state.subtitleNotice != null) {
                            if (AppRuntime.prefs?.subtitlePreferenceFor(item.id)?.startsWith("track:") == true) {
                                AppRuntime.prefs?.rememberSubtitleTrack(item.id, "auto")
                                playbackStatusNotice = state.subtitleNotice
                            }
                            subtitlePreference = "auto"
                        }
                        target.playerToContentPositionMs(state.positionMs)?.let { position = it }
                        duration = target.durationMs ?: target.playerToContentPositionMs(state.durationMs) ?: 0L
                        if (state.ready && !state.buffering && !firstFrameRendered) {
                            firstFrameRendered = true
                            matchDisplayFrameRate(currentSurface, mpvBackend?.containerFps())
                            firstFrameAt = android.os.SystemClock.elapsedRealtime()
                            if (readyAt == 0L) readyAt = firstFrameAt
                            val loadToReadyMs = (readyAt - loadStartedAt).coerceAtLeast(0L)
                            val loadToFirstFrameMs = (firstFrameAt - loadStartedAt).coerceAtLeast(0L)
                            val readyToFirstFrameMs = (firstFrameAt - readyAt).coerceAtLeast(0L)
                            phaseTimes.put("loadToReadyMs", loadToReadyMs)
                                .put("loadToFirstFrameMs", loadToFirstFrameMs)
                                .put("readyToFirstFrameMs", readyToFirstFrameMs)
                                .put("firstFrameMs", loadToFirstFrameMs)
                            val firstFrameMs = loadToFirstFrameMs
                            StabilityMetrics.firstFrameMs = firstFrameMs
                        }
                        isPlaying = state.playing && !state.buffering
                        // mpv buffering means paused-for-cache/startup, not background cache fill.
                        updateStalled(state.buffering && (state.playing || !state.ready) && !state.ended && state.error == null)
                        updateLoading(state.cacheSpeedBytesPerSecond > 0L)
                        watchdog.onPlaybackStateChanged(if (isActuallyStalled) Player.STATE_BUFFERING else if (state.ready) Player.STATE_READY else Player.STATE_IDLE)
                        if (state.audioSilentCodec != null && audioMpvKey() in audioMpvAttempts) {
                            recordPathFailure(PlaybackFailureReason.AUDIO_OUTPUT, "audio-output-unavailable")
                            recordAudioMpvFailure()
                            return@MpvPlayerController
                        }
                        if (state.ended) {
                            reportStopInAppScope(position)
                            startAutoNext()
                        }
                        if (state.error != null) {
                            handleMpvFailure(state.failureKind ?: MpvPlayerController.FailureKind.LOAD, state.error, generation)
                        }
                    }.also { mpvBackend = it }
                    val initialization = backend.initialize()
                    if (initialization is MpvPlayerController.InitializationResult.Failed) {
                        handleMpvFailure(MpvPlayerController.FailureKind.INITIALIZATION, initialization.reason, generation)
                        return@launch
                    }
                    val mpvAudioStreams = playbackSource?.audioStreams
                        ?: mediaSources.firstOrNull { it.id == currentSourceId }?.audioStreams
                        ?: mediaSources.firstOrNull()?.audioStreams.orEmpty()
                    backend.setAudioStreams(mpvAudioStreams)
                    val mpvSubtitleStreams = playbackSource?.subtitleStreams
                        ?: mediaSources.firstOrNull { it.id == currentSourceId }?.subtitleStreams
                        ?: mediaSources.firstOrNull()?.subtitleStreams.orEmpty()
                    backend.setSubtitleStreams(mpvSubtitleStreams)
                    val subtitleSp = when { subScale >= 2.3f -> 38.4f; subScale >= 1.9f -> 32f; subScale >= 1.5f -> 25.6f; subScale >= 1.2f -> 20.8f; else -> 16f }
                    backend.setSubtitleFontSize((subtitleSp * context.resources.displayMetrics.density).toInt())
                    backend.setSubtitleStyle(subtitleColor, subtitleOutline, subtitlePosition, subtitleBg)
                    val preferredAudio = preferredAudioStream(audioStreamIndex, AppRuntime.prefs?.preferredAudioLanguage, mpvAudioStreams)
                    selectedAudioStreamIndex = target.audioStreamIndex ?: preferredAudio?.index
                    backend.setAudioTrack(selectedAudioStreamIndex ?: -1)
                    val resolvedSubtitle = resolveSubtitlePreference(
                        AppRuntime.prefs?.subtitlePreferenceFor(item.id),
                        AppRuntime.prefs?.preferredSubtitleLanguage,
                        mpvSubtitleStreams,
                    )
                    subtitlePreference = resolvedSubtitle
                    when {
                        resolvedSubtitle == "off" -> backend.disableSubtitle()
                        resolvedSubtitle.startsWith("track:") -> {
                            val index = resolvedSubtitle.removePrefix("track:").removePrefix("emby:").toIntOrNull()
                            if (index != null && mpvSubtitleStreams.any { it.index == index }) backend.setSubtitleTrack(index)
                            else backend.enableSubtitleAuto()
                        }
                        else -> backend.enableSubtitleAuto()
                    }
                    pendingStartIntent = null
                    var waitedMs = 0
                    while (currentSurface?.isValid != true && waitedMs < 1_500) {
                        delay(50)
                        if (!isCurrentLoad(generation)) return@launch
                        waitedMs += 50
                    }
                    val surface = currentSurface?.takeIf { it.isValid }
                    if (surface == null) {
                        handleMpvFailure(MpvPlayerController.FailureKind.SURFACE, "播放画面未就绪（Surface 未创建）", generation)
                        return@launch
                    }
                    if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                    backend.attachSurface(surface)
                    if (!isCurrentLoad(generation) || reloadPending || mpvBackend !== backend) return@launch
                    val externalSubtitles = subs.mapNotNull { stream ->
                        srcId?.let { c.subtitleUrl(item.id, it, stream.index, "vtt") }
                    }
                    backend.setSpeed(if (boosting) 3f else speed)
                    if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                    backend.load(target.url, target.playerStartPositionMs, externalSubtitles, target.headers, paused = restorePaused)
                    pendingStartIntent = null
                } else {
                    val configs = if (srcId == null) emptyList() else subs.map { st ->
                        androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
                            android.net.Uri.parse(c.subtitleUrl(item.id, srcId, st.index, "vtt"))
                        )
                            .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                            .setId(st.index.toString())
                            .setLabel(st.displayTitle ?: st.language ?: "字幕${st.index}")
                            .setLanguage(st.language)
                            .setSelectionFlags(if (st.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    }
                    val subtitleHttpFactory = DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000)
                        .setUserAgent("LuminaTV/1.5 (Android)")
                    val optionalFactory = OptionalVttDataSourceFactory(
                        cacheFactory ?: upstreamFactory,
                        subtitleHttpFactory,
                        configs.associate { it.uri to it.id.orEmpty() },
                    ) { id, reason ->
                        instanceScope.launch {
                            if (isCurrentLoad(generation) && activeEngine == "media3") {
                                audioDecodeNotice = "外挂字幕加载失败，视频继续播放；可在字幕菜单换轨或关闭字幕"
                                PageLog.add("externalSubtitle action=empty-fallback id=$id reason=$reason")
                            }
                        }
                    }
                    val videoItem = androidx.media3.common.MediaItem.Builder().setUri(target.url)
                        .setMediaId("${item.id}:$generation")
                        .setSubtitleConfigurations(configs)
                        .apply { if (customKey != null) setCustomCacheKey(customKey) }
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.name).build())
                        .build()
                    val videoSource = DefaultMediaSourceFactory(optionalFactory).createMediaSource(videoItem)
                    subtitleSelectionApplied = false
                    audioSelectionApplied = false
                    selectedAudioStreamIndex = target.audioStreamIndex
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, resolveSubtitlePreference(
                            AppRuntime.prefs?.subtitlePreferenceFor(item.id),
                            AppRuntime.prefs?.preferredSubtitleLanguage,
                            target.source?.subtitleStreams.orEmpty(),
                        ) == "off")
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                    if (!isCurrentLoad(generation)) return@launch
                    media3LoadGeneration = generation
                    exo.setMediaSource(videoSource)
                    exo.prepare()
                    pendingStartIntent = null
                    target.playerStartPositionMs?.let(exo::seekTo)
                    exo.playWhenReady = !restorePaused
                    exo.setPlaybackSpeed(if (boosting) 3f else speed)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                updateStalled(false)
                if (failurePhase == "未知") failurePhase = "解析"
                val reason = playbackNetworkMessage(e)
                // PlaybackInfo/HTTP negotiation is shared by both engines.
                // A request failure is not evidence that mpv initialization failed.
                error = reason

            }
        }
    }

    fun buildDiagPayload(outcome: String): DiagPayload {
        phaseTimes.put("failedPaths", failureDiagnostics()).put("recoveryAttempts", recoveryBudget.attempts)
        return buildPlayerDiagPayload(
            context, outcome, activeEngine, mpvBackend, item, playbackSource, currentPlaybackUrl,
            audioDecodeMode, audioSupport.name, degraded, engineSwitchTrace, exo, failurePhase, lastPlaybackErrorJson,
            mpvState, client?.lastPlaybackInfoJson, controlsVisible, isActuallyStalled, error, position,
            activeVideoDecoder, dvDecoderPath, playbackStatusNotice,
            negotiationDiagnostics, rangeProbeDiagnostics, phaseTimes, droppedFrames, renderedFrames, bufferingCount, bufferingDurationMs,
            currentSourceId, currentPlayMethod, duration,
        )
    }

    LaunchedEffect(item.id) {
        StabilityMetrics.reset()
        MpvAudioDiagnostics.beginSession(context)
        degraded = false
        AppRuntime.screenLabel = "播放"
        AppRuntime.nowPlaying = item.seriesName?.let { "${it} · ${item.name}" } ?: item.name
        AppRuntime.currentVideoRange = "SDR"
        AppRuntime.playingItemId = item.id
        if (client == null) {
            error = "未登录"
            return@LaunchedEffect
        }
    }
    LaunchedEffect(activeEngine) {
        AppRuntime.activePlayerEngine = activeEngine
        if (activeEngine != "media3") {
            playbackStatusNotice = null
        }
    }

    LaunchedEffect(exo, mpvBackend, subtitleOffsetMs) {
        subtitleRenderersFactory.subtitleOffsetMs = subtitleOffsetMs
        mpvBackend?.setSubtitleOffset(subtitleOffsetMs)
        AppRuntime.prefs?.subtitleOffsetMs = subtitleOffsetMs
    }

    // One entry point for initial load, rebuild, engine switch and explicit reload.
    LaunchedEffect(exo, item.id, reloadKey) {
        loadSource(forceTranscode = reloadForceTranscode)
    }

    fun seekPlayerTo(targetMs: Long) {
        val playerPosition = currentTarget?.contentToPlayerPositionMs(targetMs)
        if (playerPosition == null) {
            playbackStatusNotice = "This source does not support verified seeking yet"
            return
        }
        when (activeEngine) {
            "mpv" -> mpvBackend?.seekTo(playerPosition)
            else -> exo.seekTo(playerPosition)
        }
        position = targetMs.coerceAtLeast(0L)
        previewFrame = null
        previewTargetMs = null
    }

    fun togglePlayback() {
        desiredPlaying = !desiredPlaying
        when (activeEngine) {
            "mpv" -> if (desiredPlaying) mpvBackend?.play() else mpvBackend?.pause()
            else -> if (desiredPlaying) exo.play() else exo.pause()
        }
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        when (activeEngine) {
            "mpv" -> mpvBackend?.setSpeed(value)
            else -> exo.setPlaybackSpeed(value)
        }
    }

    DisposableEffect(exo, item.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                if (!isCurrentMedia3() || audioRecoveryJob?.isActive == true) return
                val errorClass = classifyPlaybackError(err)
                android.util.Log.e("LuminaTV", "Media3 playback failed code=${err.errorCode}", err)
                PageLog.add("playbackError category=${errorClass.category.name} code=${err.errorCode} action=${errorClass.action}")
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
                    .put("action", errorClass.action)
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
                val audioDecodingUnsupported = decodingUnsupported && (
                    (err as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true ||
                        audioSupport == AudioSupport.UNSUPPORTED)
                if (decodingUnsupported || containerUnsupported) {
                    recordPathFailure(if (decodingUnsupported) PlaybackFailureReason.DECODING else PlaybackFailureReason.PARSING, err.errorCodeName)
                }
                if (errorClass.category == PlaybackErrorCategory.HTTP_PERMISSION ||
                    (errorClass.category == PlaybackErrorCategory.NETWORK_TRANSIENT && networkRetryCount >= 2)) {
                    recordPathFailure(PlaybackFailureReason.HTTP, err.errorCodeName)
                }
                val currentIsHls = currentPlaybackUrl?.let {
                    it.contains("master.m3u8", true) || it.contains(".m3u8", true)
                } == true
                val isBadHttpStatus = err.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    generateSequence(err as Throwable?) { it.cause }
                        .any { (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 400 }
                val probeContentType = (rangeProbeDiagnostics.opt("contentType") as? String)
                    .orEmpty().substringBefore(';').trim().lowercase()
                val directProbePending = containerUnsupported &&
                    currentPlaybackUrl?.contains(".m3u8", true) != true &&
                    rangeProbeDiagnostics.optString("state") == "pending"
                val nonVideoResponse = containerUnsupported &&
                    currentPlaybackUrl?.contains(".m3u8", true) != true &&
                    probeContentType.isNotBlank() && probeContentType != "application/octet-stream" &&
                    !probeContentType.startsWith("video/")
                if (directProbePending) {
                    val generation = sessionGeneration
                    updateStalled(true)
                    error = null
                    instanceScope.launch {
                        rangeProbeJob?.join()
                        if (!isCurrentLoad(generation) || activeEngine != "media3") return@launch
                        val contentType = (rangeProbeDiagnostics.opt("contentType") as? String)
                            .orEmpty().substringBefore(';').trim().lowercase()
                        if (contentType.isNotBlank() && contentType != "application/octet-stream" && !contentType.startsWith("video/")) {
                            val typeLabel = when {
                                contentType.startsWith("image/") -> "图片"
                                contentType.startsWith("text/") -> "网页或文本"
                                else -> "非视频内容（$contentType）"
                            }
                            failurePhase = "直链校验"
                            error = "片源直链失效：服务器返回了$typeLabel（网盘链接可能已过期）"
                            rangeProbeDiagnostics.put("nonVideoResponse", true)
                            lastPlaybackErrorJson = JSONObject(lastPlaybackErrorJson.orEmpty())
                                .put("contentType", contentType)
                                .put("statusCode", rangeProbeDiagnostics.opt("status") ?: JSONObject.NULL)
                                .put("finalUrlHost", (rangeProbeDiagnostics.opt("finalUrlHost") as? String) ?: JSONObject.NULL)
                                .toString(2)
                            engineSwitchTrace.add("non-video-response:$contentType")
                            updateStalled(false)
                        } else {
                            transcodeFallbackTried = true
                            forceDirectNext = false
                            degraded = true
                            enginePinned = "media3"
                            reloadForceTranscode = true
                            failurePhase = "解封装"
                            audioDecodeNotice = "直连容器无法解析，正在尝试转码"
                            engineSwitchTrace.add("transcode-fallback:${err.errorCodeName}")
                            requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                        }
                    }
                } else if (nonVideoResponse) {
                    val typeLabel = when {
                        probeContentType.startsWith("image/") -> "图片"
                        probeContentType.startsWith("text/") -> "网页或文本"
                        else -> "非视频内容（$probeContentType）"
                    }
                    failurePhase = "直链校验"
                    error = "片源直链失效：服务器返回了$typeLabel（网盘链接可能已过期）"
                    rangeProbeDiagnostics.put("nonVideoResponse", true)
                    lastPlaybackErrorJson = JSONObject(lastPlaybackErrorJson.orEmpty())
                        .put("contentType", probeContentType)
                        .put("statusCode", rangeProbeDiagnostics.opt("status") ?: JSONObject.NULL)
                        .put("finalUrlHost", (rangeProbeDiagnostics.opt("finalUrlHost") as? String) ?: JSONObject.NULL)
                        .toString(2)
                    engineSwitchTrace.add("non-video-response:$probeContentType")
                    updateStalled(false)
                } else if (errorClass.category == PlaybackErrorCategory.NETWORK_TRANSIENT && networkRetryCount < 2) {
                    val retryIndex = networkRetryCount++
                    val generation = sessionGeneration
                    updateStalled(true)
                    error = null
                    playbackStatusNotice = "网络波动，${retryIndex + 1}/2 秒后重试"
                    recoverAutomatically("network", generation, if (retryIndex == 0) 1_000L else 3_000L) {
                        if (isCurrentMedia3()) {
                            playbackStatusNotice = null
                            updateStalled(true)
                            exo.prepare()
                        }
                    }
                } else if (errorClass.category == PlaybackErrorCategory.HTTP_PERMISSION) {
                    updateStalled(false)
                    error = "媒体服务器拒绝了播放请求（权限或认证失败）"
                } else if (isBadHttpStatus && badHttpFallbackStage < 2) {
                    badHttpFallbackStage++
                    updateStalled(true)
                    error = null
                    audioDecodeNotice = if (badHttpFallbackStage == 1) "HTTP 400，正在尝试移除字幕转码参数" else "字幕参数回退失败，正在尝试服务器直连地址"
                    engineSwitchTrace.add("http-400-fallback:$badHttpFallbackStage")
                    requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                } else if (audioDecodeMode == "passthrough" && decodingUnsupported) {
                    requestAudioRecovery("passthrough-unsupported")
                } else if (audioDecodingUnsupported) {
                    requestAudioRecovery("decoder-unsupported")
                } else if (audioDecodeMode != "passthrough" && decodingUnsupported && canTryMpvPath()) {
                    updateStalled(false)
                    audioDecodeNotice = "本机解码不支持该音轨，已自动切到 mpv 本地软解"
                    enginePinned = "mpv"
                    reloadForceTranscode = false
                    requestAutomaticReload(position, sessionGeneration, "media3-decoding")
                } else if (containerUnsupported && currentIsHls && !directFallbackTried) {
                    directFallbackTried = true
                    forceDirectNext = true
                    degraded = false
                    enginePinned = null
                    reloadForceTranscode = false
                    updateStalled(true)
                    error = null
                    audioDecodeNotice = "转码流不可用，已改用直连播放"
                    engineSwitchTrace.add("direct-fallback:${err.errorCodeName}")
                    requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                } else if (containerUnsupported && currentIsHls) {
                    updateStalled(false)
                    error = "该片源无法播放（转码流与直连都失败）"
                    engineSwitchTrace.add("container-terminal:hls:${err.errorCodeName}")
                } else if (containerUnsupported && !currentIsHls && !transcodeFallbackTried) {
                    transcodeFallbackTried = true
                    forceDirectNext = false
                    degraded = true
                    enginePinned = "media3"
                    reloadForceTranscode = true
                    updateStalled(true)
                    error = null
                    audioDecodeNotice = "直连容器无法解析，正在尝试转码"
                    engineSwitchTrace.add("transcode-fallback:${err.errorCodeName}")
                    requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                } else if (containerUnsupported) {
                    updateStalled(false)
                    error = "该片源容器不受支持，转码后仍失败"
                    engineSwitchTrace.add("container-terminal:direct:${err.errorCodeName}")
                } else if (!degraded) {
                    degraded = true
                    transcodeFallbackTried = true
                    reloadForceTranscode = true
                    error = "直连失败，自动切换转码…"
                    requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                } else if (!rebuildRetry && !containerUnsupported) {
                    error = "媒体服务器转码流失败，重试转码路径…"
                    reloadForceTranscode = true
                    engineSwitchTrace.add("server-transcode-retry:${err.errorCodeName}")
                    val generation = sessionGeneration
                    recoverAutomatically("media3-rebuild", generation) {
                        rebuildRetry = true
                        requestPlaybackReload(position, generation)
                    }
                } else if (mediaSources.size > 1 &&
                    mediaSources.any { it.id != currentSourceId && it.id !in triedSources }
                ) {
                    val tried = triedSources + setOfNotNull(currentSourceId)
                    val candidates = mediaSources.mapIndexed { index, source -> source.toSourceOption(index) }
                        .filter { it.id != currentSourceId && it.id !in tried }
                    val currentOption = mediaSources.firstOrNull { it.id == currentSourceId }?.let { source ->
                        source.toSourceOption(mediaSources.indexOf(source))
                    } ?: playbackSource?.toSourceOption(mediaSources.indexOfFirst { it.id == playbackSource?.id }.coerceAtLeast(0))
                    val nextOption = currentOption?.let { chooseSimilar(candidates, it) }
                    val next = mediaSources.first { it.id == nextOption?.id }
                    triedSources = tried + next.id
                    currentSourceId = next.id
                    directFallbackTried = false
                    transcodeFallbackTried = false
                    degraded = false
                    forceDirectNext = false
                    enginePinned = null
                    reloadForceTranscode = false
                    updateStalled(true)
                    error = null
                    playbackSource = next
                    audioDecodeNotice = "当前版本播不了，换「${next.displayLabel}」再试"
                    engineSwitchTrace.add("source-fallback:${next.id}:${err.errorCodeName}")
                    requestAutomaticReload(position, sessionGeneration, "media3:${err.errorCodeName}")
                } else if (decodingUnsupported && canTryMpvPath()) {
                    enginePinned = "mpv"
                    updateStalled(true)
                    error = null
                    val dvPathMessage = if (playbackSource?.streams?.firstOrNull { it.type == "Video" }?.let {
                            it.videoRangeType.orEmpty().contains("DOVI", true) || it.dvProfile != null
                        } == true) "；DV 解码路径=${dvDecoderPath ?: "待确认"}" else ""
                    audioDecodeNotice = "Media3 无法播放该文件（${err.errorCodeName}$dvPathMessage），已自动切换 mpv"
                    engineSwitchTrace.add("mpv-auto-fallback:${err.errorCodeName}")
                    reloadForceTranscode = false
                    requestAutomaticReload(position, sessionGeneration, "media3-decoding")
                } else {
                    updateStalled(false)
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
                if (!isCurrentMedia3()) return
                updateStalled(state == Player.STATE_BUFFERING && exo.playWhenReady)
                watchdog.onPlaybackStateChanged(state)
                if (state == Player.STATE_READY) {
                    if (!phaseTimes.has("seekOffsetMs")) {
                        val actual = exo.currentPosition
                        val requested = lastRequestedStartMs
                        val offset = requested - actual
                        phaseTimes.put("readyPositionMs", actual).put("seekOffsetMs", offset)
                        PageLog.add("timeline ready gen=$sessionGeneration request=$requested actual=$actual offset=$offset duration=${exo.duration}")
                    }
                    if (readyAt == 0L) readyAt = android.os.SystemClock.elapsedRealtime()
                    val loadToReadyMs = (readyAt - loadStartedAt).coerceAtLeast(0L)
                    phaseTimes.put("loadToReadyMs", loadToReadyMs)
                    if (!firstFrameRendered) readyWithoutFrameSince = System.currentTimeMillis()
                    if (degraded) error = null
                } else if (state == Player.STATE_BUFFERING) {
                    playbackStatusNotice = null
                }
                if (state == Player.STATE_ENDED) {
                    reportStopInAppScope(position)
                    startAutoNext()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!isCurrentMedia3()) return
                val embyAudio = playbackSource?.audioStreams.orEmpty()
                val embySubs = playbackSource?.subtitleStreams.orEmpty()
                val mediaAudio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                    (0 until group.length).map { index -> group to index }
                }
                val mediaSubs = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
                    (0 until group.length).map { index -> group to index }
                }
                fun mappingSummary(streams: List<MediaStream>, candidates: List<Pair<Tracks.Group, Int>>): String =
                    streams.take(8).joinToString { stream ->
                        val direct = candidates.firstOrNull { (group, index) -> group.getTrackFormat(index).id?.toIntOrNull() == stream.index }
                        val fallback = direct ?: candidates.firstOrNull { (group, index) ->
                            val format = group.getTrackFormat(index)
                            (normalizeLanguage(stream.language)?.let { it == normalizeLanguage(format.language) } == true) ||
                                (!stream.title.isNullOrBlank() && format.label?.contains(stream.title, true) == true)
                        }
                        "${stream.index}->${fallback?.let { it.first.mediaTrackGroup.id + ":" + it.second } ?: "?"}${if (direct != null) "(id)" else if (fallback != null) "(fallback)" else "(unmapped)"}"
                    }
                val audioCandidates = mediaAudio.map { (group, index) ->
                    AudioTrackCandidate(group.mediaTrackGroup, index, group.getTrackFormat(index), group.isTrackSupported(index))
                }
                media3AudioCandidates = audioCandidates
                media3AudioSourceId = audioSourceKey()
                val audioMapping = embyAudio.take(8).joinToString { stream ->
                    val match = bestAudioTrack(stream, audioCandidates, preferredLanguage = AppRuntime.prefs?.preferredAudioLanguage)
                    "${stream.index}->${match?.let { it.group.id + ":" + it.index } ?: "?"}${if (match?.format?.id?.toIntOrNull() == stream.index) "(id)" else if (match != null) "(fallback)" else "(unmapped)"}"
                }
                PageLog.add("Media3 audio mapping count=${embyAudio.size}/${mediaAudio.size} $audioMapping")
                PageLog.add("Media3 subtitle mapping count=${embySubs.size}/${mediaSubs.size} ${mappingSummary(embySubs, mediaSubs)}")
                if (!audioSelectionApplied && audioCandidates.isNotEmpty()) {
                    val rememberedAudio = AppRuntime.prefs?.audioTrackFor(item.id)
                    val preferredLanguage = AppRuntime.prefs?.preferredAudioLanguage
                    val stream = preferredAudioStream(rememberedAudio, preferredLanguage, embyAudio)
                    val language = normalizeLanguage(preferredLanguage)
                    // A remembered manual choice must also win when the decoder has no exact Emby ID.
                    val supportedCandidates = audioCandidates.filter { it.supported }
                    val recovered = audioRecoveryTrack?.takeIf { audioRecoverySourceId == audioSourceKey() }?.let { recovery ->
                        supportedCandidates.firstOrNull { it.group == recovery.group && it.index == recovery.index }
                    }
                    val selected = recovered ?: stream?.let { bestAudioTrack(it, supportedCandidates,
                        preferredLanguage = preferredLanguage.takeIf { rememberedAudio == null }) }
                        ?: supportedCandidates.firstOrNull {
                            rememberedAudio == null && language != null && normalizeLanguage(it.format.language) == language
                        }
                    audioSelectionApplied = true
                    if (selected?.supported == true && mediaAudio.any { (group, index) ->
                            group.mediaTrackGroup == selected.group && index == selected.index && group.isTrackSupported(index)
                        }) {
                        selectedAudioStreamIndex = if (recovered != null) null else stream?.index ?: selected.format.id?.toIntOrNull()
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(TrackSelectionOverride(selected.group, selected.index))
                            .build()
                    }
                }
                if (audioCandidates.isNotEmpty() && audioCandidates.none { it.supported }) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                    PageLog.add("audioCapability action=no-override supported=0 total=${audioCandidates.size}")
                    requestAudioRecovery("no-supported-track")
                }
                val hasAudioTrack = audioCandidates.isNotEmpty()
                if (!hasAudioTrack && playbackSource?.audioStreams?.isNotEmpty() == true && !noAudioRetryTried) {
                    val generation = sessionGeneration
                    instanceScope.launch {
                        delay(3_000)
                        if (isCurrentLoad(generation) && activeEngine == "media3" &&
                            exo.playbackState == Player.STATE_READY &&
                            exo.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 } && !noAudioRetryTried
                        ) {
                            noAudioRetryTried = true
                            requestAudioRecovery("missing-audio-track")
                        }
                    }
                }
                if (subtitleSelectionApplied) return
                val rememberedPreference = AppRuntime.prefs?.subtitlePreferenceFor(item.id)
                val preferredLanguage = AppRuntime.prefs?.preferredSubtitleLanguage
                // 按片记忆 > 全局偏好 > auto（两种内核共用同一个解析函数）。
                val resolvedSubtitle = resolveSubtitlePreference(rememberedPreference, preferredLanguage, embySubs)
                subtitlePreference = resolvedSubtitle
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                if (resolvedSubtitle == "off") {
                    subtitleSelectionApplied = true
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    return
                }
                if (textGroups.isEmpty()) return
                val all = textGroups.flatMap { group ->
                    (0 until group.length).map { index -> Triple(group, index, group.getTrackFormat(index)) }
                }
                fun findSubtitle(key: String): Triple<Tracks.Group, Int, androidx.media3.common.Format>? {
                    all.firstOrNull { (group, index, _) -> group.mediaTrackGroup.id + ":" + index == key }?.let { return it }
                    val streamIndex = key.removePrefix("emby:").substringBefore(':').toIntOrNull() ?: return null
                    all.firstOrNull { (_, _, format) -> format.id?.toIntOrNull() == streamIndex }?.let { return it }
                    val stream = embySubs.firstOrNull { it.index == streamIndex }
                    if (stream != null) {
                        val language = normalizeLanguage(stream.language)
                        all.firstOrNull { (_, _, format) ->
                            val candidateLanguage = normalizeLanguage(format.language)
                            listOfNotNull(stream.displayTitle, stream.title).any { it.isNotBlank() && it.equals(format.label, true) } &&
                                (language == null || candidateLanguage == null || candidateLanguage == "und" || candidateLanguage == language)
                        }?.let { return it }
                        all.firstOrNull { (_, _, format) -> language != null && normalizeLanguage(format.language) == language }
                            ?.let { return it }
                    }
                    // Retain the old local-index fallback only for legacy keys, never for Emby indices.
                    return if (key.startsWith("emby:")) null else all.firstOrNull { (_, index, _) -> index == streamIndex }
                }
                val preferred = normalizeLanguage(preferredLanguage)
                val requested = resolvedSubtitle.takeIf { it.startsWith("track:") }?.removePrefix("track:")?.let(::findSubtitle)
                    ?: all.firstOrNull { (_, _, format) ->
                        rememberedPreference == null && preferred != null && normalizeLanguage(format.language) == preferred
                    }
                if (rememberedPreference?.startsWith("track:") == true && requested == null) {
                    AppRuntime.prefs?.rememberSubtitleTrack(item.id, "auto")
                    playbackStatusNotice = "记住的字幕轨不存在，已恢复自动选择"
                }
                // No language match (or a lost remembered track) falls back to the source's default.
                val selected = requested ?: embySubs.firstOrNull { it.isDefault }?.let { findSubtitle("emby:${it.index}") }
                subtitlePreference = requested?.let { (group, index, _) -> "track:${group.mediaTrackGroup.id}:$index" } ?: "auto"
                subtitleSelectionApplied = true
                if (selected != null) {
                    val (group, index, _) = selected
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                } else {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                }
            }

            override fun onIsPlayingChanged(p: Boolean) {
                if (!isCurrentMedia3()) return
                isPlaying = p
                if (p) updateStalled(false)
                if (p) AudioFocusGuard.record("playback-resumed")
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isCurrentMedia3()) return
                updateStalled(playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    AudioFocusGuard.record("loss paused=${!playWhenReady}")
                }
            }
            override fun onIsLoadingChanged(loading: Boolean) {
                if (!isCurrentMedia3()) return
                updateLoading(loading)
            }

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
                if (!isCurrentMedia3Event(eventTime)) return
                activeVideoDecoder = decoderName
                phaseTimes.put("decoderInitMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                firstFrameRendered = true
                updateStalled(exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady)
                phaseTimes.put("firstFramePositionMs", exo.currentPosition)
                firstFrameAt = android.os.SystemClock.elapsedRealtime()
                val effectiveReadyAt = readyAt.takeIf { it > 0L } ?: firstFrameAt
                if (readyAt == 0L) readyAt = effectiveReadyAt
                val loadToReadyMs = (effectiveReadyAt - loadStartedAt).coerceAtLeast(0L)
                val loadToFirstFrameMs = (firstFrameAt - loadStartedAt).coerceAtLeast(0L)
                val readyToFirstFrameMs = (firstFrameAt - effectiveReadyAt).coerceAtLeast(0L)
                phaseTimes.put("loadToReadyMs", loadToReadyMs)
                    .put("loadToFirstFrameMs", loadToFirstFrameMs)
                    .put("readyToFirstFrameMs", readyToFirstFrameMs)
                    .put("firstFrameMs", loadToFirstFrameMs)
                StabilityMetrics.firstFrameMs = loadToFirstFrameMs
                readyWithoutFrameSince = 0L
                playbackStatusNotice = null
                matchDisplayFrameRate(output as? Surface, exo.videoFormat?.frameRate)
                val generation = sessionGeneration
                instanceScope.launch {
                    delay(2_000)
                    if (isCurrentLoad(generation) && firstFrameRendered) {
                        phaseTimes.put("stableOutputMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
                    }
                }
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFramesCount: Int, elapsedMs: Long) {
                if (!isCurrentMedia3Event(eventTime)) return
                droppedFrames += droppedFramesCount
            }

            override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                if (!isCurrentMedia3Event(eventTime)) return
                renderedFrames += frameCount
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                estimatedBitrate = bitrateEstimate.coerceAtLeast(0L)
                bytesLoaded = totalBytesLoaded.toLong()
            }
        }
        exo.addAnalyticsListener(analytics)
        onDispose { exo.removeAnalyticsListener(analytics) }
    }

    DisposableEffect(item.id) {
        onDispose {
            reportStopInAppScope(position)
            prewarmJob?.cancel()
            autoNextJob?.cancel()
            watchdog.reset()
            val session = JSONObject()
                .put("itemId", item.id).put("itemName", item.name).put("engine", activeEngine)
                .put("positionMs", position).put("durationMs", duration)
                .put("codec", playbackSource?.streams?.firstOrNull { it.type == "Video" }?.codec ?: JSONObject.NULL)
                .put("videoRange", AppRuntime.currentVideoRange)
                .put("decoderName", activeVideoDecoder ?: JSONObject.NULL)
                .put("surfaceSize", currentSurfaceSize?.let { "${it.first}x${it.second}" } ?: JSONObject.NULL)
                .put("phaseTimes", phaseTimes)
                .put("startedAt", playStartAt).put("endedAt", android.os.SystemClock.elapsedRealtime())
                .put("endReason", error ?: playbackStatusNotice ?: "player-exit")
            runCatching {
                val root = context.getExternalFilesDir(null) ?: context.filesDir
                java.io.File(root, "last-session.json").writeText(session.toString(2), Charsets.UTF_8)
            }
            val backend = mpvBackend
            backend?.release("player-disposed")
            if (mpvBackend === backend) mpvBackend = null
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            sessionGeneration++
            audioRecoveryJob?.cancel()
            loadJob?.cancel()
        }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(2000)
            seekHint = null
        }
    }

    fun seekBy(delta: Long) {
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val current = pendingSeekTarget ?: position
        val to = (current + delta).coerceIn(0L, max)
        val step = kotlin.math.abs(delta) / 1000
        val targetSeconds = (to / 1000L).coerceAtLeast(0L)
        val targetTime = "%02d:%02d".format(targetSeconds / 60, targetSeconds % 60)
        seekHint = if (delta > 0) "+${step}s »  $targetTime" else "« ${step}s  $targetTime"
        if (seekInFlight) {
            pendingSeekTarget = to
            return
        }
        seekInFlight = true
        seekPlayerTo(to)
        seekTimeoutJob?.cancel()
        val generation = sessionGeneration
        seekTimeoutJob = instanceScope.launch {
            delay(400)
            if (!isCurrentLoad(generation)) return@launch
            seekInFlight = false
            pendingSeekTarget?.also { pendingSeekTarget = null; seekPlayerTo(it) }
        }
        if (seekHoldActive && android.os.SystemClock.elapsedRealtime() - lastHoldPreviewAt >= 700L) {
            previewFrame = null
            previewTargetMs = to
            lastHoldPreviewAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun beginSeekHold(direction: Int, repeatCount: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        seekLastKeyAt = now
        if (repeatCount > 0) {
            return
        }
        seekHoldJob?.cancel()
        seekHoldDirection = direction
        seekHoldActive = false
        seekHoldDelta = direction * seekStepMs
        seekBy(direction * seekStepMs)
        val generation = sessionGeneration
        seekHoldJob = instanceScope.launch {
            delay(600)
            if (!isCurrentLoad(generation)) return@launch
            if (seekHoldDirection != direction) return@launch
            seekHoldActive = true
            previewActive = true
            while (isCurrentLoad(generation) && seekHoldDirection == direction && android.os.SystemClock.elapsedRealtime() - seekLastKeyAt < 1_500L && kotlin.math.abs(seekHoldDelta) < 120_000L) {
                seekBy(direction * seekStepMs)
                seekHoldDelta += direction * seekStepMs
                delay(350)
            }
            seekHoldJob = null
            previewActive = false
            previewFrame = null
            previewTargetMs = null
            seekHoldDirection = 0
            seekHoldActive = false
            seekHoldDelta = 0L
        }
    }

    fun endSeekHold(direction: Int) {
        if (seekHoldDirection != direction) return
        seekHoldJob?.cancel()
        seekHoldJob = null
        previewActive = false
        previewFrame = null
        previewTargetMs = null
        seekHoldDirection = 0
        seekHoldActive = false
        seekHoldDelta = 0L
    }

    fun requestWatchdogRecovery(reason: WatchdogReason) {
        if (recovering || audioRecoveryJob?.isActive == true || optionMenu || error != null || reloadPending) return
        val generation = sessionGeneration
        val recoveryPosition = position
        recoverAutomatically("watchdog:${reason.name}", generation, 300L,
            stillNeeded = { position == recoveryPosition || isActuallyStalled || !firstFrameRendered }) {
            recordPathFailure(PlaybackFailureReason.WATCHDOG, reason.name)
            if (recoveryBudget.attempts > 1) {
                val next = mediaSources.firstOrNull { it.id != currentSourceId && it.id !in triedSources }
                if (next != null) {
                    triedSources = triedSources + listOfNotNull(currentSourceId)
                    currentSourceId = next.id
                    enginePinned = null
                    badHttpFallbackStage = 0
                    directFallbackTried = false
                    transcodeFallbackTried = false
                }
            }
            reloadForceTranscode = currentPath?.delivery == PlaybackDelivery.TRANSCODE
            requestPlaybackReload(recoveryPosition, generation)
        }
    }

    LaunchedEffect(exo, item.id) {
        var sinceReport = 0
        var bufferingSince = 0L
        while (true) {
            delay(500)
            if (!instanceJob.isActive || currentExo !== exo) return@LaunchedEffect
            val target = currentTarget
            val playerPosition = when (activeEngine) {
                "mpv" -> mpvBackend?.positionMs()
                else -> exo.currentPosition.takeIf { isCurrentMedia3() }
            }
            if (!reloadPending) playerPosition?.let { target?.playerToContentPositionMs(it) }?.let { position = it }
            duration = target?.durationMs ?: target?.playerToContentPositionMs(when (activeEngine) {
                "mpv" -> mpvBackend?.durationMs() ?: 0L
                else -> exo.duration.coerceAtLeast(0L)
            }) ?: 0L
            val contentTimelineKnown = target?.timelineOrigin == TimelineOrigin.CONTENT
            val ownsEngine = if (activeEngine == "media3") isCurrentMedia3() else mpvBackend != null && !reloadPending
            val reason = if (ownsEngine) watchdog.onTick(
                positionMs = playerPosition ?: 0L,
                bufferedMs = if (activeEngine == "media3") exo.bufferedPosition else playerPosition ?: 0L,
                state = if (activeEngine == "media3") exo.playbackState else if (isActuallyStalled) Player.STATE_BUFFERING else if (mpvState.ready) Player.STATE_READY else Player.STATE_IDLE,
                playWhenReady = if (activeEngine == "media3") exo.playWhenReady else mpvState.playing,
                isSeeking = seekHint != null || seekHoldActive || previewActive,
                sessionGeneration = sessionGeneration,
                durationMs = duration,
                bytesLoaded = bytesLoaded,
                isLoading = isLoading,
            ) else null
            if (reason != null) requestWatchdogRecovery(reason)
            val creditsSkipSeconds = AppRuntime.prefs?.endCreditsSkipSeconds ?: 30
            val validCreditsStart = currentCreditsStartMs?.takeIf { it > 0L && it < duration }
            if (contentTimelineKnown && creditsSkipSeconds > 0 && duration > 0 && item.seriesId != null && !nextTriggered &&
                ((validCreditsStart != null && position >= validCreditsStart) ||
                    (validCreditsStart == null && creditsSkipSeconds > 0 && position >= duration - creditsSkipSeconds * 1000L))
            ) {
                startAutoNext(reportStop = false)
            }
            if (contentTimelineKnown && duration > 0 && item.seriesId != null && prewarmedEpisodeId == null &&
                position >= duration - 30_000L
            ) {
                prewarmedEpisodeId = "pending"
                instanceScope.launch {
                    val c = client ?: return@launch
                    val next = catchingRequest { c.getNextEpisode(item) }.getOrNull()
                    if (next != null) {
                        prewarmedEpisodeId = next.id
                        prewarmNext(next)
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
            val reportSession = progressSession
            if (contentTimelineKnown && error == null && !reloadPending && reportSession != null && !reportSession.isClosed &&
                sinceReport >= 10 && duration > 0 && ((activeEngine == "mpv" && mpvState.playing) || (activeEngine == "media3" && exo.isPlaying) || position > 0)) {
                sinceReport = 0
                val paused = if (activeEngine == "mpv") !mpvState.playing else !exo.isPlaying
                client?.reportProgress(reportSession, position, paused)
            }
        }
    }

    LaunchedEffect(controlsVisible, lastInteractionAt, isPlaying, boosting, optionMenu, quickPanel, controlHasFocus) {
        if (controlsVisible && isPlaying && !optionMenu && quickPanel == QuickPanel.NONE && !boosting) {
            val remaining = (lastInteractionAt + 5_000L - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(remaining)
            if (android.os.SystemClock.elapsedRealtime() - lastInteractionAt >= 5_000L && !controlHasFocus) controlsVisible = false
        }
    }

    // 焦点：控制栏隐藏时由捕获层拿焦点，显示时把焦点交给播放按钮
    LaunchedEffect(controlsVisible, optionMenu, quickPanel) {
        delay(80)
        if (!optionMenu && quickPanel == QuickPanel.NONE &&
            android.os.SystemClock.elapsedRealtime() >= restoreMenuFocusUntil
        ) {
            if (controlsVisible) {
                if (!controlHasFocus) runCatching { playFocus.requestFocus() }
            } else {
                runCatching { hideFocus.requestFocus() }
            }
        }
    }

    LaunchedEffect(item.seriesId) {
        val sid = item.seriesId ?: return@LaunchedEffect
        seasonsLoading = true
        seasons = catchingRequest { client?.getSeasons(sid) }.getOrDefault(emptyList()).orEmpty()
        seasonsLoading = false
    }
    var seasonChangePendingFocus by remember { mutableStateOf(false) }
    LaunchedEffect(quickPanel, selectedSeasonId, item.seriesId) {
        if (quickPanel != QuickPanel.EPISODES) return@LaunchedEffect
        val sid = item.seriesId ?: return@LaunchedEffect
        episodesLoading = true
        quickEpisodes = LibraryCache.episodesOf(sid, selectedSeasonId)
            ?: catchingRequest { client?.getEpisodes(sid, selectedSeasonId) }.getOrDefault(null).orEmpty()
        episodesLoading = false
    }
    LaunchedEffect(quickPanel, seasons) {
        if (quickPanel == QuickPanel.NONE) return@LaunchedEffect
        quickContentHasFocus = false
        val firstFocus = if (quickPanel == QuickPanel.SPEED) {
            speedFirstFocus
        } else {
            seasons.firstOrNull()?.let { seasonFocuses.getOrPut(it.id) { FocusRequester() } }
                ?: quickEpisodes.firstOrNull()?.let { episodeItemFocuses.getOrPut(it.id) { FocusRequester() } }
        }
        val triggerFocus = if (quickPanel == QuickPanel.SPEED) speedFocus else episodeFocus
        if (firstFocus == null) return@LaunchedEffect
        repeat(15) {
            delay(50)
            lastInteractionAt = android.os.SystemClock.elapsedRealtime()
            runCatching { firstFocus.requestFocus() }
            if (quickContentHasFocus) return@LaunchedEffect
        }
        runCatching { triggerFocus.requestFocus() }
    }
    LaunchedEffect(quickPanel, selectedSeasonId, quickEpisodes, episodesLoading, seasonChangePendingFocus) {
        if (quickPanel != QuickPanel.EPISODES) {
            seasonChangePendingFocus = false
            return@LaunchedEffect
        }
        if (!seasonChangePendingFocus || episodesLoading) return@LaunchedEffect
        val firstEpisode = quickEpisodes.firstOrNull() ?: run {
            seasonChangePendingFocus = false
            return@LaunchedEffect
        }
        val requester = episodeItemFocuses.getOrPut(firstEpisode.id) { FocusRequester() }
        repeat(15) {
            delay(50)
            if (runCatching { requester.requestFocus(); true }.getOrDefault(false)) {
                seasonChangePendingFocus = false
                return@LaunchedEffect
            }
        }
        seasonChangePendingFocus = false
    }

    LaunchedEffect(showExitPrompt, exitPromptAt) {
        if (showExitPrompt) {
            delay(2_000)
            if (android.os.SystemClock.elapsedRealtime() - exitPromptAt >= 2_000L) {
                showExitPrompt = false
                exitPromptAt = 0L
            }
        }
    }

    fun exitPlayback() {
        sessionGeneration++
        instanceJob.cancel()
        cancelAutoNext()
        showExitPrompt = false
        exitPromptAt = 0L
        reportStopInAppScope(position)
        if (activeEngine == "mpv") mpvBackend?.pause() else exo.stop()
        onBack()
    }

    fun closeOptionMenu() {
        restoreMenuFocusUntil = android.os.SystemClock.elapsedRealtime() + 300L
        optionMenu = false
        scope.launch {
            delay(80)
            runCatching { menuFocus.requestFocus() }
        }
    }

    fun closeQuickPanel(triggerFocus: FocusRequester) {
        restoreMenuFocusUntil = android.os.SystemClock.elapsedRealtime() + 300L
        quickPanel = QuickPanel.NONE
        scope.launch {
            delay(80)
            runCatching { triggerFocus.requestFocus() }
        }
    }

    BackHandler {
        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
        when {
            chapterPanel -> {
                chapterPanel = false
                scope.launch { delay(80); runCatching { menuFocus.requestFocus() } }
            }
            optionMenu -> closeOptionMenu()
            quickPanel != QuickPanel.NONE -> {
                val was = quickPanel
                closeQuickPanel(if (was == QuickPanel.SPEED) speedFocus else episodeFocus)
            }
            controlsVisible -> {
                controlsVisible = false
                showExitPrompt = true
                exitPromptAt = android.os.SystemClock.elapsedRealtime()
            }
            showExitPrompt && android.os.SystemClock.elapsedRealtime() - exitPromptAt < 2_000L -> exitPlayback()
            else -> {
                showExitPrompt = true
                exitPromptAt = android.os.SystemClock.elapsedRealtime()
                controlsVisible = false
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key != Key.Back) {
                lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                if (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                ) {
                    showExitPrompt = false
                    exitPromptAt = 0L
                }
                if (event.key != Key.DirectionLeft && event.key != Key.DirectionRight && !controlsVisible) {
                    controlsVisible = true
                }
            }
            false
        },
    ) {
        if (activeEngine == "media3") AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exo
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    keepScreenOn = true
                }
            },
            update = { pv ->
                if (pv.player !== exo) pv.player = exo
                pv.resizeMode = resizeMode
                pv.subtitleView?.apply {
                    visibility = android.view.View.VISIBLE
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(0.0533f * subScale.coerceIn(0.5f, 3f))
                    setBackgroundColor(if (subtitleBg) android.graphics.Color.argb(128, 0, 0, 0) else android.graphics.Color.TRANSPARENT)
                    setStyle(androidx.media3.ui.CaptionStyleCompat(
                        subtitleColor,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        if (subtitleOutline) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                        if (subtitleOutline) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT,
                        null,
                    ))
                    setBottomPaddingFraction(subtitlePosition.coerceIn(0f, 0.25f))
                }
            },
            onRelease = { pv -> pv.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (activeEngine != "media3") key(item.id) { AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    keepScreenOn = true
                    currentSurface = holder.surface.takeIf { it.isValid }
                    val callback = object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            currentSurface = holder.surface
                            mpvBackend?.attachSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            currentSurface = holder.surface
                            currentSurfaceSize = width to height
                            mpvBackend?.attachSurface(holder.surface, width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (currentSurface === holder.surface) {
                                currentSurface = null
                                currentSurfaceSize = null
                                mpvBackend?.detachSurface()
                            }
                        }
                    }
                    tag = callback
                    holder.addCallback(callback)
                }
            },
            update = { view -> if (view.holder.surface.isValid) {
                currentSurface = view.holder.surface
                currentSurfaceSize = view.width to view.height
                mpvBackend?.attachSurface(view.holder.surface, view.width, view.height)
            } },
            onRelease = { view ->
                (view.tag as? SurfaceHolder.Callback)?.let { view.holder.removeCallback(it) }
                if (currentSurface === view.holder.surface) {
                    currentSurface = null
                    currentSurfaceSize = null
                    mpvBackend?.detachSurface()
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) }

        val isBufferingForDisplay = isActuallyStalled && showStallOverlay &&
            error == null && playbackStatusNotice == null
        if (isBufferingForDisplay) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val seriesBackdrop = item.seriesId?.let { sid ->
                    LibraryCache.detailStale(sid)?.item?.let { client?.backdropUrl(it, maxWidth = 1280, quality = 80) }
                }
                val bufferingImages = remember(item, client, tmdbBackdrop, seriesBackdrop) {
                    client?.wideImageCandidates(item, maxWidth = 1280, quality = 80, tmdbUrl = tmdbBackdrop)
                        .orEmpty().toMutableList().apply {
                            // Keep the cached series fallback ahead of TMDB and the final poster.
                            seriesBackdrop?.let { image ->
                                val fallbackIndex = indexOfFirst { it.source == "tmdb" || it.source == "poster" }
                                    .takeIf { it >= 0 } ?: size
                                add(fallbackIndex, WideImageCandidate(image, "parent"))
                            }
                        }.distinctBy { it.url }
                }
                val bufferingImageIndex = bufferingImages.indexOfFirst { it.url !in failedBufferingImages }
                val bufferingImage = bufferingImages.getOrNull(bufferingImageIndex)
                var bufferingImageLoaded by remember(item.id, bufferingImage?.url) { mutableStateOf(false) }
                var bufferingRatio by remember(item.id, bufferingImage?.url) { mutableStateOf<Pair<Int, Int>?>(null) }
                val ratio = bufferingRatio
                val isPoster = bufferingImage?.source == "poster"
                val isWideScreen = ratio?.let { (width, height) ->
                    height > 0 && kotlin.math.abs(width.toFloat() / height - 16f / 9f) / (16f / 9f) <= 0.08f
                } == true
                fun rejectBufferingImage() {
                    val image = bufferingImage?.url ?: return
                    if (image in failedBufferingImages) return
                    PageLog.add("bufferingImg candidates=${bufferingImages.size} picked=$bufferingImageIndex source=${bufferingImage.source} ok=false")
                    failedBufferingImages = failedBufferingImages + image
                }
                LaunchedEffect(bufferingImageIndex, bufferingImage, bufferingRatio) {
                    if (bufferingImage == null) {
                        PageLog.add("bufferingImg candidates=${bufferingImages.size} picked=-1 ratio=0x0 scale=crop")
                    } else if (bufferingImageLoaded) {
                        val (width, height) = bufferingRatio ?: (0 to 0)
                        PageLog.add("bufferingImg candidates=${bufferingImages.size} picked=$bufferingImageIndex source=${bufferingImage.source} ratio=${width}x$height scale=${if (isWideScreen && !isPoster) "fit" else "crop"}")
                    }
                }
                if (bufferingImage != null) {
                    AsyncImage(
                        model = bufferingImage.url,
                        contentDescription = null,
                        contentScale = if (isWideScreen && !isPoster) ContentScale.Fit else ContentScale.Crop,
                        alignment = Alignment.Center,
                        onSuccess = {
                            val width = it.result.drawable.intrinsicWidth
                            val height = it.result.drawable.intrinsicHeight
                            if (width <= 0 || height <= 0 || (!isPoster && width <= height)) {
                                rejectBufferingImage()
                            } else {
                                bufferingImageLoaded = true
                                bufferingRatio = width to height
                            }
                        },
                        onError = { rejectBufferingImage() },
                        modifier = Modifier.fillMaxSize().then(if (ratio == null) Modifier.alpha(0f) else Modifier),
                    )
                }
                if (bufferingImage == null) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF263142), Color(0xFF10141D)))))
                    Text(item.seriesName ?: item.name, color = TextMain, fontSize = 24.sp, maxLines = 2,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp))
                    Text("暂无横版剧照", color = TextSub, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
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

        mpvState.audioSilentCodec?.let { codec ->
            Text(
                "当前音轨无音频输出（$codec），按菜单可切换其他音轨",
                color = Color(0xFFFFD28A),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp)
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
                    FocusSurface(Modifier.width(132.dp).height(40.dp), corner = 20.dp, onClick = {
                        error = null
                        playbackStatusNotice = null
                        beginManualAttempt()
                        requestPlaybackReload(position, sessionGeneration)
                    }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Accent.copy(alpha = .9f) else Color(0x22FFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            Text("重试", color = if (focused) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                        }
                    }
                    if (mediaSources.size > 1) {
                        FocusSurface(Modifier.width(132.dp).height(40.dp), corner = 20.dp, onClick = {
                            val current = mediaSources.firstOrNull { it.id == currentSourceId } ?: playbackSource
                            val candidates = mediaSources.mapIndexed { index, source -> source.toSourceOption(index) }
                                .filter { it.id != currentSourceId }
                            val currentOption = current?.toSourceOption(mediaSources.indexOfFirst { it.id == current.id }.coerceAtLeast(0))
                            val nextId = currentOption?.let { chooseSimilar(candidates, it).id }
                            val next = mediaSources.firstOrNull { it.id == nextId }
                            if (next != null) {
                                currentSourceId = next.id
                                enginePinned = null
                                audioRecoveryTrack = null
                                audioRecoverySourceId = null
                                AppRuntime.prefs?.rememberSource(item.id, next.id)
                                playbackSource = next
                                forceDirectNext = false
                                directFallbackTried = false
                                transcodeFallbackTried = false
                                triedSources = emptySet()
                                reloadForceTranscode = false
                                error = null
                                requestPlaybackReload(position, sessionGeneration)
                            }
                        }) { focused ->
                            Box(Modifier.fillMaxSize().background(if (focused) Accent.copy(alpha = .9f) else Color(0x22FFFFFF), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                                Text("换源", color = if (focused) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                            }
                        }
                    }
                    if (bufferingTimedOut) {
                    Spacer(Modifier.height(12.dp))
                    FocusSurface(Modifier.width(190.dp).height(40.dp), corner = 20.dp, onClick = {
                        bufferingTimedOut = false
                        reloadForceTranscode = true
                        requestPlaybackReload(position, sessionGeneration)
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
                                    controlsVisible = true
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

        if (seekHint != null && !optionMenu) {
            Text(
                seekHint.orEmpty(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        AnimatedVisibility(
            visible = boosting,
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
                                switchEpisode(ne)
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
                            onClick = { cancelAutoNext() },
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
        if ((!controlsVisible || seekHoldDirection != 0) && !optionMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(hideFocus)
                    .focusable()
                    .onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && nextEpisode != null) {
                            cancelAutoNext()
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
                                        controlsVisible = true
                                    }
                                    true
                                }
                            }
                            Key.DirectionLeft -> {
                                if (ev.type == KeyEventType.KeyDown) beginSeekHold(-1, ev.nativeKeyEvent.repeatCount) else endSeekHold(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                if (ev.type == KeyEventType.KeyDown) beginSeekHold(1, ev.nativeKeyEvent.repeatCount) else endSeekHold(1)
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (ev.type == KeyEventType.KeyDown) controlsVisible = true
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
        if (introSeconds > 0 && position > 0 && position < introSeconds * 1000L && error == null && !controlsVisible) {
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
            visible = controlsVisible && !optionMenu && !isBufferingForDisplay,
            enter = fadeIn(animationSpec = tween(200)),
            exit = if (isBufferingForDisplay) ExitTransition.None else fadeOut(animationSpec = tween(200)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00070A12), Color(0xE6070A12))))
                        .padding(horizontal = 36.dp, vertical = 18.dp),
                ) {
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
                    if (item.seriesId != null && item.indexNumber != null) {
                        Text(
                            "第 ${item.indexNumber} 集",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    SeekBar(
                        position = position,
                        duration = duration,
                        focusRequester = seekBarFocus,
                        upFocus = playFocus,
                        stepMs = seekStepMs,
                        onFocusedChange = { controlHasFocus = it },
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
                }

                val centerFocusProps: Modifier = if (duration > 0L) {
                    Modifier.focusProperties {
                        right = speedFocus
                        down = seekBarFocus
                    }
                } else {
                    Modifier.focusProperties {
                        right = speedFocus
                        down = speedFocus
                    }
                }
                Box(
                    Modifier.align(Alignment.Center)
                        .onFocusChanged { controlHasFocus = it.isFocused }
                        .then(centerFocusProps),
                ) {
                    CtrlButton(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                        primary = true,
                        focusRequester = playFocus,
                    ) { togglePlayback() }
                }

                Box(Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp).widthIn(max = 320.dp)) {
                    if (!titleLogo.isNullOrBlank() && !titleLogoFailed) {
                        AsyncImage(
                            model = titleLogo,
                            contentDescription = displayTitle,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            onError = { titleLogoFailed = true },
                            modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 52.dp),
                        )
                    } else {
                        Text(
                            displayTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        if (speed == 1f) clockText else "$clockText  ${"%.1f".format(Locale.US, speed)}x",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                    val networkBytesPerSecond = if (activeEngine == "mpv") {
                        mpvState.cacheSpeedBytesPerSecond
                    } else {
                        estimatedBitrate / 8L
                    }
                    if (networkBytesPerSecond > 0L) {
                        val networkSpeed = if (networkBytesPerSecond >= 1_000_000L) {
                            "%.1f MB/s".format(Locale.US, networkBytesPerSecond / 1_000_000.0)
                        } else {
                            "${(networkBytesPerSecond / 1_000L).coerceAtLeast(1L)} KB/s"
                        }
                        Text(networkSpeed, color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasEpisodeButton = item.seriesId != null
                    OsdIconButton(Icons.Rounded.FastForward, "倍速播放", speedFocus, playFocus, if (hasEpisodeButton) episodeFocus else menuFocus, playFocus, { controlHasFocus = it }) {
                        if (quickPanel == QuickPanel.SPEED) closeQuickPanel(speedFocus)
                        else quickPanel = QuickPanel.SPEED
                        optionMenu = false
                    }
                    if (hasEpisodeButton) {
                        OsdIconButton(Icons.Rounded.PlaylistPlay, "选集", episodeFocus, speedFocus, menuFocus, playFocus, { controlHasFocus = it }) {
                            if (quickPanel == QuickPanel.EPISODES) closeQuickPanel(episodeFocus)
                            else quickPanel = QuickPanel.EPISODES
                            optionMenu = false
                        }
                    }
                    OsdIconButton(Icons.Rounded.MoreVert, "更多播放选项", menuFocus, if (hasEpisodeButton) episodeFocus else speedFocus, playFocus, playFocus, { controlHasFocus = it }) {
                        quickPanel = QuickPanel.NONE
                        optionMenu = true
                    }
                }
            }
        }

        if (quickPanel != QuickPanel.NONE && controlsVisible && !optionMenu) {
            val triggerFocus = if (quickPanel == QuickPanel.SPEED) speedFocus else episodeFocus
            val speedItemFocuses = remember { SPEEDS.indices.map { if (it == 0) speedFirstFocus else FocusRequester() } }
            val panelWidth = if (quickPanel == QuickPanel.SPEED) 260.dp else 420.dp
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 164.dp)
                    .width(panelWidth).heightIn(max = 300.dp)
                    .background(Color(0xF20B1018), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x553B4655), RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Text(
                    if (quickPanel == QuickPanel.SPEED) "倍速播放" else "选集",
                    color = TextSub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                if (quickPanel == QuickPanel.SPEED) {
                    LazyColumn(
                        Modifier.heightIn(max = 250.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(SPEEDS.size) { index ->
                            val s = SPEEDS[index]
                            val active = kotlin.math.abs(speed - s) < 0.01f
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                                    .focusRequester(speedItemFocuses[index])
                                    .focusProperties {
                                        up = if (index == 0) triggerFocus else FocusRequester.Default
                                        down = if (index == SPEEDS.lastIndex) triggerFocus else FocusRequester.Default
                                        left = speedItemFocuses[index]
                                        right = speedItemFocuses[index]
                                    },
                                corner = 8.dp,
                                scaleTo = 1.08f,
                                onFocusChanged = { if (it) quickContentHasFocus = true },
                                onClick = {
                                    setPlaybackSpeed(s)
                                    closeQuickPanel(speedFocus)
                                },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (active) Accent else if (focused) Color(0x55FFFFFF) else Color(0x18FFFFFF),
                                        RoundedCornerShape(8.dp),
                                    ).then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (s == 1f) "1.0x" else "${s}x",
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (seasons.isNotEmpty()) {
                        LazyRow(
                            Modifier.fillMaxWidth().height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowItemsIndexed(seasons, key = { _, season -> season.id }) { index, season ->
                                val seasonFocus = seasonFocuses.getOrPut(season.id) { FocusRequester() }
                                val active = season.id == selectedSeasonId
                                var seasonFocused by remember(season.id) { mutableStateOf(false) }
                                Box(
                                    Modifier.height(40.dp)
                                        .widthIn(min = 64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                active -> Accent
                                                seasonFocused -> Color(0x33FFFFFF)
                                                else -> Color(0x14FFFFFF)
                                            }
                                        )
                                        .border(
                                            if (seasonFocused) 2.dp else 1.dp,
                                            if (seasonFocused) Color.White else Color(0x22FFFFFF),
                                            RoundedCornerShape(8.dp),
                                        )
                                        .focusRequester(seasonFocus)
                                        .focusProperties {
                                            left = FocusRequester.Default
                                            right = FocusRequester.Default
                                            up = triggerFocus
                                            down = if (!episodesLoading && quickEpisodes.isNotEmpty() && season.id == selectedSeasonId) {
                                                episodeItemFocuses.getOrPut(quickEpisodes.first().id) { FocusRequester() }
                                            } else FocusRequester.Default
                                        }
                                        .focusable()
                                        .onFocusChanged {
                                            seasonFocused = it.isFocused
                                            if (it.isFocused) quickContentHasFocus = true
                                        }
                                        .clickable {
                                            if (selectedSeasonId != season.id) {
                                                seasonChangePendingFocus = true
                                                selectedSeasonId = season.id
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        season.name,
                                        color = if (active) Color(0xFF06101F) else TextMain,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f, fill = false)) {
                        if (seasonsLoading || episodesLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(20.dp),
                                color = Accent,
                                strokeWidth = 2.dp,
                            )
                        } else if (quickEpisodes.isEmpty()) {
                            Text(
                                "本季暂无集数",
                                color = TextSub,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                modifier = Modifier.heightIn(max = if (seasons.isEmpty()) 250.dp else 205.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                gridItemsIndexed(quickEpisodes, key = { _, ep -> ep.id }) { index, ep ->
                                    val episodeFocus = episodeItemFocuses.getOrPut(ep.id) { FocusRequester() }
                                    val active = ep.id == item.id
                                    FocusSurface(
                                        modifier = Modifier.size(width = 64.dp, height = 40.dp)
                                            .focusRequester(episodeFocus)
                                            .focusProperties {
                                                left = FocusRequester.Default
                                                right = FocusRequester.Default
                                                up = if (index < 5) {
                                                    seasonFocuses[selectedSeasonId] ?: FocusRequester.Default
                                                } else FocusRequester.Default
                                                down = if (index / 5 == quickEpisodes.lastIndex / 5) triggerFocus else FocusRequester.Default
                                            },
                                        corner = 6.dp,
                                        scaleTo = 1.08f,
                                        onFocusChanged = { if (it) quickContentHasFocus = true },
                                        onClick = {
                                            if (!active) {
                                                quickPanel = QuickPanel.NONE
                                                reportStopInAppScope(position)
                                                onPlayNext?.invoke(ep)
                                            }
                                        },
                                    ) { focused ->
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                if (active) Accent else if (focused) Color(0x55FFFFFF) else Color(0x18FFFFFF),
                                                RoundedCornerShape(6.dp),
                                            ).then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                ep.indexNumber?.toString() ?: "-",
                                                color = if (active) Color(0xFF06101F) else TextMain,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                textAlign = TextAlign.Center,
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

        if (showExitPrompt) {
            Text(
                "再按一次返回退出播放",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .background(Color(0xCC0B1018), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = TextMain,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = optionMenu,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.98f),
        ) {
            OptionMenu(
                exo = exo,
                item = item,
                resizeMode = resizeMode,
                currentSourceId = currentSourceId,
                subScale = subScale,
                audioDecodeMode = audioDecodeMode,
                audioSupport = audioSupport,
                seekStepSeconds = seekStepSeconds,
                subtitleOffsetMs = subtitleOffsetMs,
                activeEngine = activeEngine,
                audioCaps = audioCaps,
                subtitleStreams = playbackSource?.subtitleStreams
                    ?: mediaSources.firstOrNull { it.id == currentSourceId }?.subtitleStreams
                    ?: mediaSources.firstOrNull()?.subtitleStreams.orEmpty(),
                subtitlePreference = subtitlePreference,
                currentMpvSubtitleIndex = mpvBackend?.currentSubtitleEmbyIndex(),
                currentMpvSubtitleDisabled = mpvBackend?.isSubtitleDisabled() == true,
                audioStreams = playbackSource?.audioStreams
                    ?: mediaSources.firstOrNull { it.id == currentSourceId }?.audioStreams
                    ?: mediaSources.firstOrNull()?.audioStreams.orEmpty(),
                selectedAudioStreamIndex = selectedAudioStreamIndex,
                onAudioStreamSelected = { streamIndex, override, unsupported ->
                    audioSelectionApplied = true
                    selectedAudioStreamIndex = streamIndex
                    audioRecoveryJob?.cancel()
                    audioRecoveryJob = null
                    audioRecoveryTrack = null
                    audioRecoverySourceId = null
                    currentPath = currentPath?.copy(audioTrack = streamIndex.toString())
                    manualAudioStreamSelection = true
                    AppRuntime.prefs?.rememberAudioTrack(item.id, streamIndex)
                    if (activeEngine == "mpv") {
                        mpvBackend?.setAudioTrack(streamIndex)
                    } else if (unsupported || (override != null && !exo.currentTracks.groups.any { group ->
                            group.mediaTrackGroup == override.mediaTrackGroup && override.trackIndices.all { group.isTrackSupported(it) }
                        })) {
                        audioDecodeNotice = "本机不支持所选音轨，正在尝试软解内核"
                        PageLog.add("audioCapability action=manual-unsupported index=$streamIndex")
                        requestAudioRecovery("manual-unsupported")
                    } else if (override != null) {
                        audioRecoveryTrack = null
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(override)
                            .build()
                    } else {
                        reloadForceTranscode = false
                        requestPlaybackReload(position, sessionGeneration)
                    }
                },
                onSubtitleSelected = { key ->
                    subtitleSelectionApplied = true
                    val stored = when {
                        key == null -> "off"
                        key == "auto" -> "auto"
                        else -> "track:$key"
                    }
                    AppRuntime.prefs?.rememberSubtitleTrack(item.id, stored)
                    subtitlePreference = stored
                    if (activeEngine == "mpv") {
                        if (key == null) mpvBackend?.disableSubtitle()
                        else if (key == "auto") mpvBackend?.enableSubtitleAuto()
                        else key.removePrefix("emby:").toIntOrNull()?.let { mpvBackend?.setSubtitleTrack(it) }
                    } else {
                        when {
                            key == null -> exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                            key == "auto" -> exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                            else -> {
                                val id = key.substringBeforeLast(':', "")
                                val index = key.substringAfterLast(':').toIntOrNull()
                                val group = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.mediaTrackGroup.id == id }
                                if (group != null && index != null && index in 0 until group.length) {
                                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                }
                            }
                        }
                    }
                },
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
                        "passthrough" -> "已尝试音频直通（设备报告支持，实际输出以电视/功放为准）"
                        else -> "已切换到自动音频解码"
                    }
                    // 显式选择才切内核：默认永远是 media3，避免"什么都用 MPV 转圈"
                    when (mode) {
                        "soft" -> enginePinned = "mpv"
                        "passthrough" -> enginePinned = "media3"
                        else -> enginePinned = null
                    }
                    engineSwitchTrace.add("audio-decode-mode=$mode")
                    reloadForceTranscode = false
                    requestPlaybackReload(position, sessionGeneration)
                },
                onSeekStepChanged = {
                    seekStepSeconds = it
                    AppRuntime.prefs?.seekStepSeconds = it
                },
                onSwitchEngine = {
                    error = "正在切换播放内核…"
                    val engines = listOf("media3", "mpv")
                    if (engines.size > 1) {
                        val next = engines[(engines.indexOf(activeEngine).coerceAtLeast(0) + 1) % engines.size]
                        enginePinned = next
                        reloadForceTranscode = false
                        requestPlaybackReload(position, sessionGeneration)
                    }
                },
                onlyMedia3Available = false,
                onResizeMode = { resizeMode = it },
                onSwitchSource = { sid ->
                    closeOptionMenu()
                    AppRuntime.prefs?.rememberSource(item.id, sid)
                    directFallbackTried = false
                    transcodeFallbackTried = false
                    forceDirectNext = false
                    degraded = false
                    currentSourceId = sid
                    enginePinned = null
                    audioRecoveryTrack = null
                    audioRecoverySourceId = null
                    reloadForceTranscode = AppRuntime.prefs?.forceTranscode ?: false
                    requestPlaybackReload(position, sessionGeneration)
                },
                onRememberIntro = {
                    AppRuntime.prefs?.setIntroSeconds(item.id, (position / 1000L).toInt())
                    closeOptionMenu()
                },
                onClose = { closeOptionMenu() },
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
                            beginManualAttempt()
                            enginePinned = "mpv"
                            reloadForceTranscode = false
                            requestPlaybackReload(position, sessionGeneration)
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

private enum class QuickPanel { NONE, SPEED, EPISODES }

@Composable
private fun OsdIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    requester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    play: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    FocusSurface(
        modifier = Modifier.size(36.dp).focusRequester(requester).focusProperties {
            this.left = left
            this.right = right
            up = play
            down = play
        },
        corner = 6.dp,
        scaleTo = 1.15f,
        onFocusChanged = onFocusChanged,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize()
                .background(if (focused) Color(0xFFE8ECF2) else Color(0x22FFFFFF), RoundedCornerShape(6.dp))
                .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (focused) Color(0xFF0B1018) else TextMain,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
