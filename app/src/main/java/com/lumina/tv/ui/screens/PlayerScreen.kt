package com.lumina.tv.ui.screens

import com.lumina.tv.data.catchingRequest
import com.lumina.tv.data.PlaybackStartIntent
import com.lumina.tv.data.playbackReloadIntent
import com.lumina.tv.data.PlaybackProgressSession
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.EmbyException
import com.lumina.tv.data.RequestTimeoutException
import com.lumina.tv.data.TimelineOrigin
import com.lumina.tv.data.TimelinePosition
import com.lumina.tv.player.PendingSeekConfirmation
import com.lumina.tv.player.SeekConfirmation
import com.lumina.tv.player.CERTIFICATE_ERROR_MESSAGE
import com.lumina.tv.player.MpvTlsPolicy
import com.lumina.tv.player.MpvRetirementFence
import com.lumina.tv.player.ServerCertificateErrors
import com.lumina.tv.player.isCertificateFailure
import com.lumina.tv.player.HiddenPlaybackKey
import com.lumina.tv.player.HiddenPlaybackInputState
import com.lumina.tv.player.HiddenPlaybackEvent
import com.lumina.tv.player.HiddenPlaybackAction
import com.lumina.tv.player.HIDDEN_OK_HOLD_MS
import com.lumina.tv.player.reduceHiddenPlaybackInput
import com.lumina.tv.player.hiddenTouchEnabled
import com.lumina.tv.player.playbackControlsOverlayVisible
import com.lumina.tv.player.trickplayCanPresent

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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import com.lumina.tv.data.resolveTmdbMetadataItem
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
import com.lumina.tv.player.MpvAvailabilityProbe
import com.lumina.tv.player.mpvLibrariesInstalled
import com.lumina.tv.player.PlaybackLoadDetails
import com.lumina.tv.player.PlaybackLoadKey
import com.lumina.tv.player.shouldSyncMpvAudioTrack
import com.lumina.tv.player.audioMimeForSourceHint
import com.lumina.tv.player.MpvAudioDiagnostics
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.player.PlaybackErrorCategory
import com.lumina.tv.player.PlaybackErrorRoute
import com.lumina.tv.player.playbackErrorRoute
import com.lumina.tv.player.shouldRetryNetworkPlayback
import com.lumina.tv.player.shouldRecoverAudioWithoutSelection
import com.lumina.tv.player.PlaybackRangeProbe
import com.lumina.tv.player.classifyPlaybackError
import com.lumina.tv.player.OptionalVttDataSourceFactory
import com.lumina.tv.player.PlaybackPath
import com.lumina.tv.player.PlaybackPathFailure
import com.lumina.tv.player.PlaybackPathFailures
import com.lumina.tv.player.readDisplayHdrTypes
import com.lumina.tv.player.supportsContentHdr
import com.lumina.tv.player.displayHdrDiagnostic
import com.lumina.tv.player.PlaybackDelivery
import com.lumina.tv.player.PlaybackFailureReason
import com.lumina.tv.player.mpvWasExplicitlyRequested
import com.lumina.tv.player.selectPlaybackEngine
import com.lumina.tv.player.shouldAutomaticallyTryMpvAudioFallback
import com.lumina.tv.player.PlaybackRecoveryBudget
import com.lumina.tv.player.PlayerLifecycle
import com.lumina.tv.player.PlayerHostLifecycleEvent
import com.lumina.tv.player.reducePlayerHostLifecycle
import com.lumina.tv.player.PlaybackSpeedState
import com.lumina.tv.player.CreditsCountdown
import com.lumina.tv.player.creditsWindowStartMs
import com.lumina.tv.player.shouldCancelCreditsForSeek
import com.lumina.tv.player.ContainerRecovery
import com.lumina.tv.player.containerRecovery
import com.lumina.tv.player.playbackSourceJson
import com.lumina.tv.player.serverTranscodingAvailable
import com.lumina.tv.player.withStopPositionSample
import com.lumina.tv.player.PlaybackWatchdog
import com.lumina.tv.player.WatchdogReason
import com.lumina.tv.player.StabilityMetrics
import com.lumina.tv.player.AudioFocusGuard
import com.lumina.tv.player.AudioCapabilities
import com.lumina.tv.player.selectedAudioForRouteChange
import com.lumina.tv.player.audioRouteNeedsRecovery
import com.lumina.tv.player.AudioCodecMatrix
import com.lumina.tv.player.AudioRouteMonitor
import com.lumina.tv.player.AudioSupport
import com.lumina.tv.player.AudioTrackCandidate
import com.lumina.tv.player.TrackMapping
import com.lumina.tv.player.TrackMappingContext
import com.lumina.tv.player.Media3TrackKey
import com.lumina.tv.player.media3TrackMapping
import com.lumina.tv.player.media3OrderEvidence
import com.lumina.tv.player.candidateFor
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        isCertificateFailure(error) -> CERTIFICATE_ERROR_MESSAGE
        causeChain.any { it is RequestTimeoutException } -> "播放请求超时，请重试"
        status == 401 || status == 403 || message.contains("HTTP 401") || message.contains("HTTP 403") -> "登录已失效，请重新登录"
        status != null && status >= 500 -> "服务器错误（$status）"
        causeChain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } ||
            message.contains("connection reset", true) || message.contains("timeout", true) || message.contains("timed out", true) -> "网络中断，请检查连接后重试"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "播放请求失败，请重试"
    }
}

/** Complete a failed preparation only while its load still owns the screen. */
internal suspend fun finishPlaybackLoadFailure(
    failure: Exception,
    ownsLoad: () -> Boolean,
    updateStalled: (Boolean) -> Unit,
    clearTarget: () -> Unit,
    publishError: (String) -> Unit,
): Boolean {
    if (failure is CancellationException) throw failure
    currentCoroutineContext().ensureActive()
    if (!ownsLoad()) return false
    clearTarget()
    updateStalled(false)
    publishError(playbackNetworkMessage(failure))
    return true
}

private fun audioMime(codec: String?): String? = audioMimeForSourceHint(codec.orEmpty())


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
    var tmdbBackdrop by remember(item.id, client) { mutableStateOf<String?>(null) }
    LaunchedEffect(item, client) {
        tmdbBackdrop = null
        val metadata = resolveTmdbMetadataItem(item) { seriesId ->
            LibraryCache.detail(seriesId)?.item ?: client?.getItem(seriesId)
        } ?: return@LaunchedEffect
        tmdbBackdrop = catchingRequest {
            TmdbClient.lookup(metadata.type, metadata.name, metadata.year)?.backdropUrl
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
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var speedState by remember { mutableStateOf(PlaybackSpeedState(AppRuntime.prefs?.defaultSpeed ?: 1.0f)) }
    var optionMenu by remember { mutableStateOf(false) }
    var quickPanel by remember { mutableStateOf(QuickPanel.NONE) }
    var exitPromptAt by remember { mutableLongStateOf(0L) }
    var showExitPrompt by remember { mutableStateOf(false) }
    var seasons by remember(item.seriesId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var quickEpisodes by remember(item.seriesId) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedSeasonId by remember(item.seriesId, item.seasonId) { mutableStateOf(item.seasonId) }
    var seasonsLoading by remember { mutableStateOf(false) }
    var episodesLoading by remember { mutableStateOf(false) }
    var episodesLoadFailed by remember { mutableStateOf(false) }
    var episodeRetry by remember { mutableIntStateOf(0) }
    val episodePages = remember(item.seriesId, client, LibraryCache.userDataRevision) { EpisodePickerPages<MediaItem>() }
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
    // 保留用户本次播放明确选择的内核，后续重载不覆盖该选择。
    var enginePinned by remember(item.id) { mutableStateOf<String?>(null) }
    val mpvInstalled = remember(context) { mpvLibrariesInstalled(context) }
    var mpvInitializationFailed by remember { mutableStateOf(false) }
    val mpvAvailable = mpvInstalled && !mpvInitializationFailed
    var mpvMenuTrackRevision by remember { mutableIntStateOf(0) }
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
    val loadCoordinator = remember(item.id) { com.lumina.tv.player.PlaybackLoadCoordinator(initialSourceId) }
    val badHttpFallbackStage by loadCoordinator::badHttpFallbackStage
    var manualAudioStreamSelection by remember(item.id) { mutableStateOf(false) }
    var transcodeFallbackTried by remember(item.id) { mutableStateOf(false) }
    // 同一部片可能有多个版本（不同压制/大小），一个播不了就换下一个
    var mediaSources by remember(item.id) { mutableStateOf<List<MediaSource>>(emptyList()) }
    var triedSources by remember(item.id) { mutableStateOf<Set<String>>(emptySet()) }
    var reloadForceTranscode by remember(item.id) { mutableStateOf(AppRuntime.prefs?.forceTranscode ?: false) }
    var playbackSource by remember(item.id) { mutableStateOf<MediaSource?>(null) }
    var noAudioRetryTried by remember(item.id) { mutableStateOf(false) }
    var bufferingTimedOut by remember(item.id) { mutableStateOf(false) }
    var showDiagExport by remember { mutableStateOf(false) }
    var showMpvConfirm by remember { mutableStateOf(false) }
    var playStartAt by remember(item.id) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var engineSwitchTrace by remember(item.id) { mutableStateOf(mutableListOf<String>()) }
    var loadDetails by remember(item.id) {
        mutableStateOf(PlaybackLoadDetails(PlaybackLoadKey(0, item.id, initialSourceId)))
    }
    var rangeProbeJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var networkRetryCount by remember(item.id) { mutableIntStateOf(0) }
    var sessionGeneration by loadCoordinator::generation
    val playbackDiagnostics = remember(item.id) { com.lumina.tv.player.PlaybackDiagnostics { PageLog.add(it) } }
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
    val initialStartIntent = remember(item.id) { startIntent }
    var recoveryStartIntent by remember(item.id) { mutableStateOf(initialStartIntent) }
    var pendingStartIntent by remember(item.id) { mutableStateOf<PlaybackStartIntent?>(initialStartIntent) }
    var desiredPlaying by loadCoordinator::desiredPlaying
    var currentTarget by remember(item.id) { mutableStateOf<EmbyClient.PlaybackTarget?>(null) }
    var lastPlayerPositionMs by remember(item.id) { mutableStateOf<Long?>(null) }
    var media3PositionAvailable by remember(item.id) { mutableStateOf(false) }
    var strmRestartNoticePending by remember(item.id) { mutableStateOf(false) }
    var strmRestartNoticeShown by remember(item.id) { mutableStateOf(false) }
    var strmResumeFallback by remember(item.id) { mutableStateOf<JSONObject?>(null) }
    var lastRequestedStartMs by remember(item.id) { mutableLongStateOf(0L) }
    var loadJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var phaseTimes by remember(item.id) { mutableStateOf(JSONObject()) }
    var droppedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var renderedFrames by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingCount by remember(item.id) { mutableIntStateOf(0) }
    var bufferingDurationMs by remember(item.id) { mutableLongStateOf(0L) }
    var bufferingStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var loadingStartedAt by remember(item.id) { mutableLongStateOf(0L) }
    var loadingCount by remember(item.id) { mutableIntStateOf(0) }
    var loadingDurationMs by remember(item.id) { mutableLongStateOf(0L) }
    var dvDecoderPath by remember(item.id) { mutableStateOf<String?>(null) }
    var activeVideoDecoder by remember(item.id) { mutableStateOf<String?>(null) }
    var firstFrameRendered by remember(item.id) { mutableStateOf(false) }
    var resumedByBackground by remember(item.id) { mutableStateOf(false) }
    var readyWithoutFrameSince by remember(item.id) { mutableLongStateOf(0L) }
    var playbackStatusNotice by remember(item.id) { mutableStateOf<String?>(null) }
    var failurePhase by remember(item.id) { mutableStateOf("未知") }
    var currentSourceId by remember(item.id) { mutableStateOf(initialSourceId) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
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
    var subtitlePreference by remember(item.id) { mutableStateOf(AppRuntime.prefs?.subtitlePreferenceFor(item.id, playbackSource)) }
    var subtitleOffsetMs by remember { mutableLongStateOf(AppRuntime.prefs?.subtitleOffsetMs ?: 0L) }
    var seekHoldDirection by remember { mutableIntStateOf(0) }
    var seekHoldActive by remember { mutableStateOf(false) }
    var seekHoldDelta by remember { mutableLongStateOf(0L) }
    var seekHoldJob by remember { mutableStateOf<Job?>(null) }
    var hiddenInputState by remember(item.id) { mutableStateOf(HiddenPlaybackInputState()) }
    var seekInFlight by remember { mutableStateOf(false) }
    var pendingSeekTarget by remember { mutableStateOf<Long?>(null) }
    var seekTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var seekConfirmation by remember(item.id) { mutableStateOf<PendingSeekConfirmation?>(null) }
    var seekConfirmationJob by remember(item.id) { mutableStateOf<Job?>(null) }
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
    var pendingManualAudio by remember(item.id) { mutableStateOf<Pair<String, Int>?>(null) }
    fun rememberConfirmedAudio(index: Int?) {
        val source = playbackSource ?: return
        val pending = pendingManualAudio ?: return
        if (pending.first == com.lumina.tv.player.trackMemoryIdentity(source, "Audio") && pending.second == index) {
            AppRuntime.prefs?.rememberAudioTrack(item.id, pending.second, source)
            pendingManualAudio = null
        }
    }
    var audioMapping by remember(item.id) { mutableStateOf(TrackMapping<Media3TrackKey>(emptyList())) }
    var subtitleMapping by remember(item.id) { mutableStateOf(TrackMapping<Media3TrackKey>(emptyList())) }
    var audioSelectionApplied by remember(item.id) { mutableStateOf(false) }
    var selectedAudioStreamIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    var progressSession by remember(item.id, client) { mutableStateOf<PlaybackProgressSession?>(null) }
    var exitPending by remember(item.id) { mutableStateOf(false) }

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
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
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
    val episodeRetryFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val speedFirstFocus = remember { FocusRequester() }
    val seasonFocuses = remember(item.seriesId) { mutableMapOf<String, FocusRequester>() }
    val episodeItemFocuses = remember(item.seriesId) { mutableMapOf<String, FocusRequester>() }
    var quickContentHasFocus by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    var restoreMenuFocusUntil by remember { mutableLongStateOf(0L) }
    var titleLogoFailed by remember(item.id) { mutableStateOf(false) }


    val httpFactory = remember(client) {
        OkHttpDataSource.Factory(client?.playbackHttpClient ?: okhttp3.OkHttpClient())
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
                PlayerLifecycle.registry.created(this, "media3", "playback:${item.id}")
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    activeEngine != "mpv")
                playWhenReady = true
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, resolveSubtitlePreference(
                        AppRuntime.prefs?.subtitlePreferenceFor(item.id, playbackSource),
                        AppRuntime.prefs?.preferredSubtitleLanguage,
                        emptyList(),
                    ) == "off")
                    .build()
            }
    }

    DisposableEffect(exo, activeEngine) {
        exo.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            activeEngine != "mpv",
        )
        onDispose { }
    }

    // Work that touches a player belongs to that exact instance, even across suspension.
    val currentExo by rememberUpdatedState(exo)
    val attachedPlayerView = remember(exo) { java.util.concurrent.atomic.AtomicReference<PlayerView?>(null) }
    val instanceJob = remember(exo, item.id) { SupervisorJob(scope.coroutineContext[Job]) }
    val instanceScope = remember(instanceJob) { CoroutineScope(scope.coroutineContext + instanceJob) }
    val creditsCountdown = remember(exo, item.id) { CreditsCountdown<MediaItem>(instanceScope, { PageLog.add(it) }) }
    var media3LoadGeneration by remember(exo, item.id) { mutableIntStateOf(-1) }
    fun isCurrentLoad(generation: Int): Boolean =
        instanceJob.isActive && currentExo === exo && loadCoordinator.accepts(generation)
    fun isCurrentMedia3(): Boolean = !reloadPending && activeEngine == "media3" &&
        isCurrentLoad(media3LoadGeneration) && exo.currentMediaItem?.mediaId == "${item.id}:$media3LoadGeneration"
    fun isCurrentMedia3Event(eventTime: AnalyticsListener.EventTime): Boolean {
        if (!isCurrentMedia3() || eventTime.timeline.isEmpty) return false
        val window = androidx.media3.common.Timeline.Window()
        if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return false
        return eventTime.timeline.getWindow(eventTime.windowIndex, window).mediaItem.mediaId == "${item.id}:$media3LoadGeneration"
    }

    fun showTimelineNotice(message: String) {
        playbackStatusNotice = message
        scope.launch {
            delay(4_000L)
            if (playbackStatusNotice == message) playbackStatusNotice = null
        }
    }

    fun observedPlayerPositionMs(): Long? = runCatching { when (activeEngine) {
        "mpv" -> mpvBackend?.observedPositionMs()
        // Media3 masks its position with the requested seek until a frame is rendered.
        else -> if (media3PositionAvailable && !reloadPending && exo.playbackState != Player.STATE_IDLE &&
            exo.currentMediaItem?.mediaId == "${item.id}:$media3LoadGeneration") {
            exo.currentPosition.takeIf { it >= 0L }
        } else null
    } }.getOrNull()

    fun timelinePosition(playerMs: Long?): TimelinePosition = currentTarget?.timeline?.position(playerMs)
        ?: TimelinePosition(playerMs, null)

    fun recordTimelineDiagnostics(sample: TimelinePosition) {
        val target = currentTarget
        val calibration = target?.timeline?.calibration
        phaseTimes.put("timeline", JSONObject()
            .put("origin", target?.timelineOrigin?.name ?: "UNKNOWN")
            .put("offsetMs", calibration?.offsetMs ?: JSONObject.NULL)
            .put("confidence", calibration?.confidence?.name ?: "UNMEASURED")
            .put("evidence", calibration?.evidence ?: "no-playback-target")
            .put("locked", target?.timeline?.locked ?: false))
        phaseTimes.put("progress", JSONObject()
            .put("contentPos", sample.contentPositionMs ?: JSONObject.NULL)
            .put("playerPos", sample.playerPositionMs ?: JSONObject.NULL)
            .put("timelineUnverified", sample.timelineUnverified))
    }

    fun reportStopInAppScope() {
        val stopClient = client ?: return
        val session = progressSession?.takeUnless { it.isClosed } ?: return
        withStopPositionSample(observedPlayerPositionMs(), lastPlayerPositionMs,
            onMissing = { PageLog.add("progress: skip Stop reason=no-position-sample") }) { playerMs ->
            val sample = timelinePosition(playerMs)
            recordTimelineDiagnostics(sample)
            // Snapshot the actual player before release; a request/seek target is never a Stop fallback.
            stopClient.reportStop(session, sample.reportPositionMs, paused = !isPlaying,
                timelineUnverified = sample.timelineUnverified, playerPositionMs = sample.playerPositionMs)
            phaseTimes.put("lastStop", stopClient.lastProgressDiagnostics)
            PageLog.add("progress: event=Stop contentPos=${sample.contentPositionMs} playerPos=${sample.playerPositionMs} unverified=${sample.timelineUnverified}")
        }
    }

    fun confirmSeek(playerMs: Long?, now: Long, commandFailed: Boolean = false) {
        val pending = seekConfirmation ?: return
        val result = if (commandFailed) SeekConfirmation.ROLLED_BACK else pending.evaluate(playerMs, now)
        if (result == SeekConfirmation.PENDING) return
        seekConfirmation = null
        seekConfirmationJob?.cancel()
        phaseTimes.put("seekConfirmation", JSONObject().put("state", result.name)
            .put("targetMs", pending.displayTargetMs).put("playerTargetMs", pending.playerTargetMs)
            .put("fromPlayerMs", pending.fromPlayerMs ?: JSONObject.NULL)
            .put("commandFailed", commandFailed)
            .put("playerPos", playerMs ?: JSONObject.NULL).put("elapsedMs", now - pending.requestedAtMs))
        if (result == SeekConfirmation.ROLLED_BACK) {
            seekHint = null
            showTimelineNotice("跳转未生效，已恢复实际播放位置")
        }
    }

    fun observeTimelinePosition(target: EmbyClient.PlaybackTarget, playerMs: Long?, playerDur: Long?, now: Long) {
        if (target !== currentTarget || reloadPending) return
        if (target.timeline.sample(playerMs, playerDur, now)) {
            val calibration = target.timeline.calibration
            target.streamStartOffsetMs = calibration.offsetMs
            loadDetails.negotiationDiagnostics.put("streamStartOffsetMs", calibration.offsetMs ?: JSONObject.NULL)
            PageLog.add("timeline: origin=${target.timelineOrigin} offset=${calibration.offsetMs} confidence=${calibration.confidence} evidence=${calibration.evidence}")
        }
        if (playerMs != null) {
            lastPlayerPositionMs = playerMs
            position = target.timeline.position(playerMs).reportPositionMs
            recoveryStartIntent = playbackReloadIntent(recoveryStartIntent, target.timeline.position(playerMs))
        }
        confirmSeek(playerMs, now)
        val sample = target.timeline.position(lastPlayerPositionMs)
        recordTimelineDiagnostics(sample)
        duration = target.durationMs ?: playerDur?.takeIf { it > 0L }?.let {
            target.playerToContentPositionMs(it) ?: it
        } ?: duration
    }

    LaunchedEffect(error, progressSession) {
        if (error != null) reportStopInAppScope()
    }

    DisposableEffect(exo, item.id) {
        // Also reopen the observation gate if a seek is ignored and ordinary frames keep arriving.
        // We use the callback as frame evidence, not its container-dependent presentation timestamp.
        val listener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { _, _, _, _ ->
            if (!media3PositionAvailable) instanceScope.launch {
                if (isCurrentMedia3() && exo.playbackState == Player.STATE_READY && !media3PositionAvailable) {
                    media3PositionAvailable = true
                    currentTarget?.let { target ->
                        observeTimelinePosition(target, exo.currentPosition.takeIf { it >= 0L }, exo.duration,
                            android.os.SystemClock.elapsedRealtime())
                    }
                }
            }
        }
        exo.setVideoFrameMetadataListener(listener)
        onDispose { exo.clearVideoFrameMetadataListener(listener) }
    }

    DisposableEffect(exo, item.id) {
        onDispose {
            reportStopInAppScope()
            instanceJob.cancel()
            attachedPlayerView.getAndSet(null)?.player = null
            runCatching { exo.clearVideoSurface(); exo.release() }
                .onSuccess { PlayerLifecycle.registry.released(exo, "playback-disposed") }
        }
    }


    LaunchedEffect(item.id) {
        chapters = catchingRequest { client?.getChapters(item.id) }.getOrNull().orEmpty()
        playbackMarkers = catchingRequest { client?.getPlaybackMarkers(item.id) }.getOrNull()
    }

    val isBufferingForDisplay = isActuallyStalled && showStallOverlay &&
        error == null && playbackStatusNotice == null
    val controlsOverlayVisible = playbackControlsOverlayVisible(controlsVisible, seekHoldActive, optionMenu, isBufferingForDisplay)
    val canShowTrickplay = trickplayCanPresent(controlsOverlayVisible, previewActive, duration,
        previewDisabled || quickPanel != QuickPanel.NONE || error != null)
    LaunchedEffect(item.id, sessionGeneration, previewTargetMs, canShowTrickplay, client) {
        previewFrame = null
        val target = previewTargetMs ?: return@LaunchedEffect
        if (!canShowTrickplay || client == null) return@LaunchedEffect
        val generation = sessionGeneration
        delay(160)
        val frame = catchingRequest { client.getTrickplayFrame(item.id, target) }.getOrNull()
        if (isCurrentLoad(generation)) previewFrame = frame
    }

    fun cancelCreditsCountdown(reason: String, allowRestart: Boolean = true) {
        creditsCountdown.cancel(reason, allowRestart)
    }

    fun playbackPaused(): Boolean = !desiredPlaying || when (activeEngine) {
        "mpv" -> !mpvState.playing && !mpvState.ended
        else -> !exo.playWhenReady
    }

    fun switchEpisode(next: MediaItem, reportStop: Boolean = true) {
        val playNext = onPlayNext ?: return
        cancelCreditsCountdown("episode-switch")
        sessionGeneration++
        loadJob?.cancel()
        rangeProbeJob?.cancel()
        audioRecoveryJob?.cancel()
        watchdog.reset()
        if (reportStop) reportStopInAppScope()
        playNext(next)
        // The new item owns its load timing and generation; no countdown resets them.
    }

    fun startAutoNext(reportStop: Boolean = true) {
        if (AppRuntime.prefs?.autoNextEpisode != true || item.seriesId == null || playbackPaused()) return
        val generation = sessionGeneration
        creditsCountdown.start(
            seconds = ((AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) / 10).coerceIn(1, 6),
            next = { client?.getNextEpisode(item)?.takeIf { isCurrentLoad(generation) } },
            canContinue = { isCurrentLoad(generation) && !playbackPaused() },
            onComplete = { next -> switchEpisode(next, reportStop) },
        )
    }

    fun updateStalled(stalled: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        playbackDiagnostics.identity?.takeIf { it.generation == sessionGeneration }?.let { load ->
            playbackDiagnostics.stalled(load, stalled, now,
                firstFrameRendered && seekHint == null && !seekHoldActive && !previewActive)
        }
        if (isActuallyStalled == stalled) return
        isActuallyStalled = stalled
        showStallOverlay = false
        PageLog.add("playbackStall session=${playbackDiagnostics.sessionId} load=${playbackDiagnostics.identity} active=$stalled engine=$activeEngine playing=$isPlaying loading=$isLoading")
        if (stalled) {
            bufferingStartedAt = now
            bufferingCount++
        } else {
            if (bufferingStartedAt > 0L) bufferingDurationMs += now - bufferingStartedAt
            bufferingStartedAt = 0L
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

    fun requestPlaybackReload(generation: Int, explicitIntent: PlaybackStartIntent? = null) {
        if (!isCurrentLoad(generation)) {
            PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration")
            return
        }
        if (reloadPending) return
        val sample = (observedPlayerPositionMs() ?: lastPlayerPositionMs)?.let(::timelinePosition)
        val retryIntent = explicitIntent ?: playbackReloadIntent(recoveryStartIntent, sample)
        reportStopInAppScope()
        reloadPending = true
        reloadUsesRecoveryAttempt = recovering
        media3LoadGeneration = -1
        pendingStartIntent = retryIntent
        recoveryStartIntent = retryIntent
        reloadKey++
    }

    fun checkStrmResume(target: EmbyClient.PlaybackTarget, now: Long) {
        if (target !== currentTarget || reloadPending || !target.isStrmDirectLink) return
        if (target.timeline.shouldRestartStrmFromZero(lastPlayerPositionMs, now)) {
            strmResumeFallback = JSONObject().put("action", "restart-from-zero")
                .put("requestedStartMs", target.startPositionMs)
                .put("lastPlayerPositionMs", lastPlayerPositionMs ?: JSONObject.NULL)
            phaseTimes.put("strmResumeFallback", strmResumeFallback)
            PageLog.add("timeline: STRM resume not reached in 8000ms; restarting from zero")
            strmRestartNoticePending = !strmRestartNoticeShown
            requestPlaybackReload(sessionGeneration, PlaybackStartIntent.FromStart)
        }
    }

    fun onTimelineFirstFrame(target: EmbyClient.PlaybackTarget, now: Long) {
        if (!target.timeline.firstFrame(now)) return
        val generation = sessionGeneration
        val window = target.timeline
        instanceScope.launch {
            delay(8_000L)
            if (!isCurrentLoad(generation) || currentTarget !== target || target.timeline !== window || reloadPending) return@launch
            observeTimelinePosition(target, observedPlayerPositionMs(), null, android.os.SystemClock.elapsedRealtime())
            checkStrmResume(target, android.os.SystemClock.elapsedRealtime())
        }
    }

    fun recordPathFailure(reason: PlaybackFailureReason, detail: String) {
        val path = currentPath ?: return
        val failure = PlaybackPathFailure(path, reason, sessionGeneration, detail)
        if (path.engine == "mpv" && reason in setOf(
                PlaybackFailureReason.LOAD, PlaybackFailureReason.HTTP,
                PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.DECODING)) {
            pathFailures.recordEngineFailure(failure)
        } else {
            pathFailures.record(failure)
        }
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

    fun requestAutomaticReload(generation: Int, reason: String) {
        recoverAutomatically(reason, generation) { requestPlaybackReload(generation) }
    }

    fun showCertificateError(engine: String) {
        // A queued cooldown/fallback must not later overwrite a terminal certificate decision.
        audioRecoveryJob?.cancel()
        audioRecoveryJob = null
        recovering = false
        client?.baseUrl?.let(ServerCertificateErrors::record)
        loadDetails.lastPlaybackErrorJson = JSONObject().put("engine", engine).put("category", "CERT_INVALID").put("action", "check_certificate").toString()
        PageLog.add("playbackError category=CERT_INVALID engine=$engine action=check_certificate")
        updateStalled(false)
        playbackStatusNotice = null
        failurePhase = "证书验证"
        exo.pause()
        error = CERTIFICATE_ERROR_MESSAGE
    }

    fun audioSourceKey(): String = currentSourceId ?: playbackSource?.id ?: item.id

    fun currentLoadKey() = PlaybackLoadKey(sessionGeneration, item.id, currentSourceId ?: loadCoordinator.sourceId)

    fun currentPlaybackSourceJson() =
        playbackSourceJson(loadDetails.playbackInfoJson(currentLoadKey()), currentSourceId)

    fun canTryMpvPath(path: PlaybackPath? = currentPath): Boolean = path?.let {
        com.lumina.tv.player.mpvWasExplicitlyRequested(AppRuntime.prefs?.playerEngine, enginePinned) &&
        selectPlaybackEngine(AppRuntime.prefs?.playerEngine, "mpv", it, pathFailures,
            mpvInstalled && !mpvInitializationFailed) == "mpv"
    } == true

    fun audioMpvKey(): PlaybackPath? = currentPath?.copy(engine = "mpv")

    fun audioFailureMessage(): String {
        val codec = playbackSource?.audioStreams?.let { streams ->
            streams.firstOrNull { it.index == selectedAudioStreamIndex } ?: streams.firstOrNull()
        }?.codec?.uppercase() ?: "未知编码"
        val sourceJson = currentPlaybackSourceJson()
        val serverMessage = when {
            sourceJson == null -> "未取得可用的服务器转码信息"
            !serverTranscodingAvailable(sourceJson) -> "服务器也未提供转码"
            else -> "服务器转码也未能恢复播放"
        }
        val recoveryMessage = if (audioMpvKey() in audioMpvAttempts || audioMpvKey() in audioMpvFailures)
            "软解未能恢复" else "自动恢复次数已用完"
        return "该片音轨（$codec）本机无法硬解，$recoveryMessage，$serverMessage。请换片源或音轨。"
    }

    fun retryAlternateSource(generation: Int, reason: String): Boolean {
        if (!isCurrentLoad(generation) || reloadPending || mediaSources.size <= 1) return false
        if (mediaSources.none { it.id != currentSourceId && it.id !in triedSources }) return false
        recordPathFailure(PlaybackFailureReason.HTTP, "source-fallback:$reason")
        val tried = triedSources + setOfNotNull(currentSourceId)
        val candidates = mediaSources.mapIndexed { index, source -> source.toSourceOption(index) }
            .filter { it.id != currentSourceId && it.id !in tried }
        val currentOption = mediaSources.firstOrNull { it.id == currentSourceId }?.let { source ->
            source.toSourceOption(mediaSources.indexOf(source))
        } ?: playbackSource?.toSourceOption(mediaSources.indexOfFirst { it.id == playbackSource?.id }.coerceAtLeast(0))
        val nextOption = currentOption?.let { chooseSimilar(candidates, it) } ?: return false
        val next = mediaSources.firstOrNull { it.id == nextOption.id } ?: return false
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
        engineSwitchTrace.add("source-fallback:${next.id}:$reason")
        requestAutomaticReload(generation, "media3:$reason")
        return true
    }

    fun recoverUnsupportedContainer(errorCodeName: String, generation: Int) {
        val canTranscode = serverTranscodingAvailable(currentPlaybackSourceJson())
        // Recheck mpv even after a transcode attempt, before declaring a terminal container failure.
        when (containerRecovery(canTryMpvPath(), canTranscode, transcodeFallbackTried)) {
            ContainerRecovery.MPV -> {
                enginePinned = "mpv"
                reloadForceTranscode = false
                forceDirectNext = false
                updateStalled(true)
                error = null
                failurePhase = "解封装"
                audioDecodeNotice = "直连容器无法解析，正在尝试 mpv"
                engineSwitchTrace.add("mpv-fallback:container:$errorCodeName")
                requestAutomaticReload(generation, "media3:$errorCodeName")
            }
            ContainerRecovery.TRANSCODE -> {
                transcodeFallbackTried = true
                forceDirectNext = false
                degraded = true
                enginePinned = "media3"
                reloadForceTranscode = true
                updateStalled(true)
                error = null
                failurePhase = "解封装"
                audioDecodeNotice = "直连容器无法解析，正在尝试转码"
                engineSwitchTrace.add("transcode-fallback:$errorCodeName")
                requestAutomaticReload(generation, "media3:$errorCodeName")
            }
            ContainerRecovery.TERMINAL -> {
                updateStalled(false)
                error = if (transcodeFallbackTried) "该片源容器不受支持，转码后仍失败"
                    else "该片源容器不受支持，且无可用的 mpv 或服务器转码路径"
                engineSwitchTrace.add("container-terminal:direct:$errorCodeName")
            }
        }
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
        val automaticMpvFallback = currentPath?.let { path ->
            shouldAutomaticallyTryMpvAudioFallback(PlaybackFailureReason.AUDIO_OUTPUT, path, pathFailures,
                mpvInstalled && !mpvInitializationFailed,
                mpvWasExplicitlyRequested(AppRuntime.prefs?.playerEngine, enginePinned))
        } == true
        val tryMpv = activeEngine != "mpv" && mpvKey !in audioMpvAttempts && mpvKey !in audioMpvFailures &&
            (canTryMpvPath() || automaticMpvFallback)
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
        val sourceJson = currentPlaybackSourceJson()
        val canTranscode = sourceJson?.optBoolean("SupportsTranscoding", false) == true &&
            !sourceJson.optString("TranscodingUrl").isNullOrBlank() && !transcodeFallbackTried
        if (recoveryBudget.exhausted || (!tryMpv && alternative == null && !canTranscode)) {
            finishAudioRecovery(if (recoveryBudget.exhausted) "budget-exhausted" else "no-playable-audio")
            return
        }
        val generation = sessionGeneration
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
                requestPlaybackReload(generation)
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
                requestPlaybackReload(generation)
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
        if (kind == MpvPlayerController.FailureKind.INITIALIZATION) {
            mpvInitializationFailed = true
            loadDetails.mpvInitializationError = detail
        }
        if (kind == MpvPlayerController.FailureKind.CERT_INVALID) {
            showCertificateError("mpv")
            mpvBackend?.release("certificate-invalid")
            mpvBackend = null
            return
        }
        val reason = when (kind) {
            MpvPlayerController.FailureKind.INITIALIZATION -> PlaybackFailureReason.INITIALIZATION
            MpvPlayerController.FailureKind.SURFACE -> PlaybackFailureReason.SURFACE
            MpvPlayerController.FailureKind.HTTP -> PlaybackFailureReason.HTTP
            MpvPlayerController.FailureKind.DECODING -> PlaybackFailureReason.DECODING
            MpvPlayerController.FailureKind.FIRST_FRAME_TIMEOUT -> PlaybackFailureReason.FIRST_FRAME_TIMEOUT
            MpvPlayerController.FailureKind.LOAD -> PlaybackFailureReason.LOAD
            MpvPlayerController.FailureKind.CERT_INVALID -> error("Certificate errors are terminal")
        }
        recordPathFailure(reason, detail)
        if (recordAudioMpvFailure()) return
        mpvBackend?.release("mpv-failed:$reason")
        mpvBackend = null
        enginePinned = "media3"
        updateStalled(false)
        failurePhase = when (kind) {
            MpvPlayerController.FailureKind.HTTP -> "连接"
            MpvPlayerController.FailureKind.SURFACE -> "显示层"
            MpvPlayerController.FailureKind.INITIALIZATION -> "内核初始化"
            MpvPlayerController.FailureKind.FIRST_FRAME_TIMEOUT -> "首帧"
            else -> "解码"
        }
        audioDecodeNotice = "mpv 播放失败（$reason），已切回内置内核 Media3"
        reloadForceTranscode = currentPath?.delivery == PlaybackDelivery.TRANSCODE
        requestAutomaticReload(generation, "mpv:$reason")
    }

    LaunchedEffect(item.id, mediaSources, initialSourceId, currentSourceId) {
        if (initialSourceId == null && currentSourceId == null) {
            val remembered = AppRuntime.prefs?.lastSourceFor(item.id)
            if (remembered != null && mediaSources.any { it.id == remembered }) {
                currentSourceId = remembered
                if (sessionGeneration > 0 && playbackSource?.id != remembered) {
                    requestPlaybackReload(sessionGeneration)
                }
            }
        }
    }

    val latestAudioRouteHandler by rememberUpdatedState<(AudioCapabilities) -> Unit> { changedCaps ->
            audioCaps = changedCaps
            val source = playbackSource ?: mediaSources.firstOrNull { it.id == currentSourceId }
            val mpvSelected = if (activeEngine == "mpv") mpvBackend?.currentAudioEmbyIndex() else null
            val currentAudio = selectedAudioForRouteChange(
                source?.audioStreams.orEmpty(),
                activeEngine,
                selectedAudioStreamIndex,
                mpvSelected,
            )
            if (currentAudio == null) {
                PageLog.add("audioRoute selection=unknown action=preserve-user-preference")
                return@rememberUpdatedState
            }
            val codec = listOfNotNull(currentAudio?.codec, currentAudio?.profile).joinToString(" ").lowercase()
            val mime = audioMime(codec)
            val enc = AudioCodecMatrix.encoding(mime, codec)
            audioSupport = AudioCodecMatrix.support(context, enc, currentAudio?.channels ?: 2, currentAudio?.sampleRate ?: 0, changedCaps, mime)
            audioDecodeNotice = when (audioSupport) {
                AudioSupport.PASSTHROUGH -> "音频输出已变化：当前音轨可直通"
                AudioSupport.DECODE_ONLY -> null
                AudioSupport.UNSUPPORTED -> "音频输出已变化：当前音轨可能无法播放"
            }
            if (audioRouteNeedsRecovery(selectionKnown = true, support = audioSupport)) {
                PageLog.add("audioRoute action=recover selectedIndex=${currentAudio.index} support=${audioSupport.name} mode=$audioDecodeMode")
                if (activeEngine == "mpv") {
                    audioDecodeNotice = "当前音频输出不支持已选轨道，请选择其他音轨"
                } else {
                    requestAudioRecovery("audio-route-unsupported")
                }
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
        audioStreamIndex: Int? = playbackSource?.takeIf { it.id == sourceId }?.let { source ->
            pendingManualAudio?.takeIf { it.first == com.lumina.tv.player.trackMemoryIdentity(source, "Audio") }?.second
                ?: AppRuntime.prefs?.audioTrackFor(item.id, source)
        },
    ) {
        if (!instanceJob.isActive || currentExo !== exo) return
        val c = client ?: return
        audioMapping = TrackMapping(emptyList())
        subtitleMapping = TrackMapping(emptyList())
        selectedAudioStreamIndex = null
        reportStopInAppScope()
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
        val loadIntent = pendingStartIntent ?: recoveryStartIntent
        val resumePosition = loadIntent.positionMs(item.positionTicks / 10_000L)
        lastRequestedStartMs = resumePosition
        audioRecoveryJob?.cancel()
        audioRecoveryJob = null
        cancelCreditsCountdown("load")
        speedState = speedState.forNewLoad()
        loadJob?.cancel()
        rangeProbeJob?.cancel()
        val generation = loadCoordinator.beginLoad(sourceId)
        seekConfirmationJob?.cancel()
        seekConfirmation = null
        pendingSeekTarget = null
        seekTimeoutJob?.cancel()
        seekInFlight = false
        lastPlayerPositionMs = null
        media3PositionAvailable = false
        currentTarget = null
        val details = PlaybackLoadDetails(PlaybackLoadKey(generation, item.id, sourceId))
        loadDetails = details
        error = null
        playbackStatusNotice = null
        activeVideoDecoder = null
        dvDecoderPath = null
        failurePhase = "解析"
        playbackDiagnostics.begin(generation, loadStartedAt)
        mpvBackend?.release("external-load-replaced")
        mpvBackend = null
        mpvState = MpvPlayerController.State()
        exo.pause()
        // pause() retains MediaCodec and its buffers. Retire the old media before negotiating
        // another load/engine, so mpv cannot start alongside a suspended Media3 decoder.
        exo.stop()
        exo.clearMediaItems()
        PageLog.add("playback reload session=${playbackDiagnostics.sessionId} load=${playbackDiagnostics.identity} gen=$generation fromGen=${generation - 1} positionMs=$resumePosition engine=$activeEngine")
        playStartAt = android.os.SystemClock.elapsedRealtime()
        phaseTimes = JSONObject().put("identity", playbackDiagnostics.identityJson())
            .put("strmResumeFallback", strmResumeFallback ?: JSONObject.NULL)
            .put("loadToReadyMs", JSONObject.NULL)
            .put("loadToFirstFrameMs", JSONObject.NULL)
            .put("readyToFirstFrameMs", JSONObject.NULL)
            .put("firstFrameMs", JSONObject.NULL)
        loadJob = instanceScope.launch {
            updateStalled(false)
            firstFrameRendered = false
            updateLoading(false)
            updateStalled(true)
            readyWithoutFrameSince = 0L
            bufferingTimedOut = false
            try {
                MpvRetirementFence.await()
                if (!isCurrentLoad(generation) || reloadPending) return@launch
                if (forceTranscode) transcodeFallbackTried = true
                val maxStreamingBitrate = AppRuntime.prefs?.maxStreamingBitrate ?: 120_000_000L
                val strategy = AppRuntime.prefs?.sourceStrategy ?: com.lumina.tv.data.SourceStrategy()
                val preparedTracks = c.preparePlaybackTracks(item.id, sourceId, audioStreamIndex, strategy,
                    playbackSource?.takeIf { it.id == sourceId }?.let { com.lumina.tv.player.trackMemoryIdentity(it, "Audio") })
                if (!isCurrentLoad(generation) || reloadPending || !loadCoordinator.resolveSource(generation, preparedTracks.source.id)) return@launch
                details.bindSource(preparedTracks.source.id)
                if (client === c && AppRuntime.client === c && preparedTracks.availableSources.isNotEmpty()) {
                    mediaSources = preparedTracks.availableSources
                }
                val requestVariant = loadCoordinator.requestVariant
                val cachedTarget = if (requestVariant.stage == 0) c.cachedPlaybackTarget(item.id, forceTranscode, sourceId, audioStreamIndex, forceDirectNext, maxStreamingBitrate, strategy,
                    loadIntent, resumePosition, manualAudioStreamSelection, trackIntent = preparedTracks.intent)
                    else null
                if (requestVariant.stage != 0) PageLog.add("prewarm miss nextId=${item.id} reason=fallback_request")
                val resolvedTarget = cachedTarget ?: c.resolvePlayback(
                        item, forceTranscode, sourceId, loadIntent, audioStreamIndex,
                        forceDirect = forceDirectNext,
                        removeSubtitleParams = requestVariant.removeSubtitleParams,
                        preferDirectStreamUrl = requestVariant.preferDirectStreamUrl,
                        manualAudioStreamSelection = manualAudioStreamSelection,
                        maxStreamingBitrate = maxStreamingBitrate,
                        strategy = strategy,
                        preparedTracks = preparedTracks,
                        playbackInfoCapture = details.playbackInfoCapture,
                    )
                // Cached negotiation is reusable; runtime measurements belong to this load only.
                val target = resolvedTarget.copy(negotiationDiagnostics = JSONObject(resolvedTarget.negotiationDiagnostics.toString()))
                if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                if (reloadPending || !loadCoordinator.resolveSource(generation, target.source.id)) return@launch
                httpFactory.setDefaultRequestProperties(target.headers)
                phaseTimes.put("compatMappingMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
                details.acceptTarget(target)
                currentTarget = target
                recordTimelineDiagnostics(target.timeline.position(null))
                lastRequestedStartMs = target.startPositionMs
                PageLog.add("timeline requestStartMs=$resumePosition playMethod=${target.playMethod} direct=${target.direct} sourceId=${target.source?.id} url=${target.url.substringBefore('?').take(120)}")
                phaseTimes.put("requestedStartMs", resumePosition)
                    .put("playMethod", target.playMethod)
                    .put("direct", target.direct)
                failurePhase = "连接"
                currentSourceId = target.source?.id ?: sourceId
                playbackSource = target.source
                if (pendingManualAudio?.first != com.lumina.tv.player.trackMemoryIdentity(target.source, "Audio")) {
                    pendingManualAudio = null
                }
                val sourceVideoRange = target.source?.streams?.firstOrNull { it.type == "Video" }?.videoRangeType.orEmpty().uppercase()
                AppRuntime.currentVideoRange = when {
                    sourceVideoRange.contains("DOVI") || sourceVideoRange.contains("DOLBY") || sourceVideoRange == "DV" -> "DV"
                    sourceVideoRange.contains("HLG") -> "HLG"
                    sourceVideoRange.contains("HDR10") || sourceVideoRange.contains("HDR") -> "HDR10"
                    else -> "SDR"
                }
                val targetIsHls = target.url.contains("master.m3u8", true) || target.url.contains(".m3u8", true)
                if (targetIsHls) transcodeFallbackTried = true
                rangeProbeJob = instanceScope.launch {
                    val probeResult = runCatching { PlaybackRangeProbe.probe(target.url, target.headers, c.playbackHttpClient) }
                        .getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
                    if (isCurrentLoad(generation) && currentTarget === target) {
                        details.rangeProbeDiagnostics = probeResult
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
                // Keep format and display capability in diagnostics; mpv requires an explicit engine choice.
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
                val displayHdrTypes = readDisplayHdrTypes(context)
                val contentHdr = rangeType.takeUnless { isDolbyVision }
                val selectedEngine = selectPlaybackEngine(AppRuntime.prefs?.playerEngine, enginePinned,
                    path, pathFailures, mpvInstalled && !mpvInitializationFailed)
                if (selectedEngine == null) {
                    throw EmbyException("当前片源的播放路径均已失败，请重试或更换片源、音轨")
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
                    .put("displayHdr", displayHdrDiagnostic(displayHdrTypes)?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("dvProfile7", isDvP7)
                    .put("displaySupportsContentHdr", contentHdr?.let { supportsContentHdr(displayHdrTypes, it) } ?: JSONObject.NULL)
                    .put("engine", selectedEngine))
                if (isDvP7 && !softDecodeNoticeShown) {
                    audioDecodeNotice = if (selectedEngine == "mpv") "Dolby Vision Profile 7，已按你的选择使用 mpv"
                        else "Dolby Vision Profile 7，先尝试 Media3；如无法播放可手动切换 mpv"
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
                exo.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    selectedEngine != "mpv",
                )
                currentResolution = target.source?.resolution
                    ?: if (target.direct) null else "转码"
                val cacheLimit = AppRuntime.prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L
                PlaybackCache.setLimit(cacheLimit)
                val upstreamFactory = OkHttpDataSource.Factory(c.playbackHttpClient)
                    .setUserAgent("LuminaTV/1.5 (Android)")
                    .setDefaultRequestProperties(target.headers)
                val cacheFactory = if (cacheLimit > 0L) PlaybackCache.dataSourceFactory(context, upstreamFactory) else null
                val customKey = if (targetIsHls) null else target.source?.let { source -> PlaybackCache.mediaKey(
                    AppRuntime.session?.baseUrl ?: AppRuntime.prefs?.baseUrl.orEmpty(),
                    AppRuntime.session?.userId ?: AppRuntime.prefs?.userId.orEmpty(),
                    item.id,
                    source,
                    target,
                ) }
                val targetCacheFactory = cacheFactory.takeIf { customKey != null }
                val subs = target.source?.streams
                    ?.filter { it.type == "Subtitle" && it.isExternal }
                    .orEmpty()
                val srcId = target.source?.id
                if (activeEngine == "mpv") {
                    mpvBackend?.release("new-external-load")
                    mpvState = MpvPlayerController.State()
                    val caBundle = MpvTlsPolicy.platformCaBundle(context.cacheDir)
                    if (!isCurrentLoad(generation)) { caBundle.delete(); return@launch }
                    val backend = MpvPlayerController(context, generation, caBundle, c.playbackHttpClient, onSoftwareRetry = { owner, load, needed, retry ->
                        if (mpvBackend === owner && isCurrentLoad(load.generation)) {
                            val retryWithProgressSession = {
                                if (needed()) {
                                    playbackDiagnostics.retry(load, android.os.SystemClock.elapsedRealtime())
                                    reportStopInAppScope()
                                    speedState = speedState.forNewLoad()
                                    owner.setSpeed(speedState.userSpeed)
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
                    }, desiredPlaying = {
                        loadCoordinator.playingFor(generation)?.takeIf { isCurrentLoad(generation) && !reloadPending }
                    }, onPhysicalLoad = { owner, load, paused ->
                        if (mpvBackend === owner && isCurrentLoad(load.generation) && !reloadPending) {
                            progressSession = c.reportStart(item.id, target.startPositionMs, srcId, target.playMethod,
                                target.playSessionId, paused = paused)
                        }
                    }) { owner, load, state ->
                        if (!isCurrentLoad(load.generation) || reloadPending || mpvBackend !== owner || activeEngine != "mpv") {
                            PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration")
                            return@MpvPlayerController
                        }
                        if (playbackDiagnostics.identity != load && (playbackDiagnostics.identity?.physicalId ?: 0L) > 0L) {
                            target.resetTimeline()
                            lastPlayerPositionMs = null
                            seekConfirmationJob?.cancel()
                            seekConfirmation = null
                        }
                        playbackDiagnostics.activate(load, android.os.SystemClock.elapsedRealtime())
                        phaseTimes.put("identity", playbackDiagnostics.identityJson())
                        val becameReady = state.ready && !mpvState.ready
                        if (becameReady || state.trackListChanged) mpvMenuTrackRevision++
                        val pendingAudio = pendingManualAudio
                        val mappedSource = playbackSource
                        val hasPendingManualAudio = pendingAudio != null && mappedSource != null &&
                            pendingAudio.first == com.lumina.tv.player.trackMemoryIdentity(mappedSource, "Audio")
                        mpvState = state
                        if (shouldSyncMpvAudioTrack(
                                state.ready,
                                becameReady,
                                state.trackListChanged,
                                hasPendingManualAudio,
                            )) {
                            rememberConfirmedAudio(owner.currentAudioEmbyIndex())
                        }
                        if (state.subtitleNotice != null) {
                            playbackStatusNotice = state.subtitleNotice
                            if (state.subtitleNotice.contains("CERT_INVALID")) ServerCertificateErrors.record(c.baseUrl)
                            if (state.subtitleFallbackToAuto) subtitlePreference = "auto"
                        }
                        state.httpHeadersNotice?.let { playbackStatusNotice = it }
                        if (state.ready && !state.buffering) {
                            onTimelineFirstFrame(target, android.os.SystemClock.elapsedRealtime())
                            playbackDiagnostics.firstFrame(load, android.os.SystemClock.elapsedRealtime(), "mpv.ready (no rendered-frame callback)")
                        }
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
                        }
                        observeTimelinePosition(target, owner.observedPositionMs(), state.durationMs,
                            android.os.SystemClock.elapsedRealtime())
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
                            reportStopInAppScope()
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
                    backend.diagnosticLoadIdentity()?.let { load ->
                        playbackDiagnostics.activate(load, android.os.SystemClock.elapsedRealtime())
                        phaseTimes.put("identity", playbackDiagnostics.identityJson())
                    }
                    val mpvAudioStreams = playbackSource?.audioStreams
                        ?: mediaSources.firstOrNull { it.id == currentSourceId }?.audioStreams
                        ?: mediaSources.firstOrNull()?.audioStreams.orEmpty()
                    backend.setTrackMappingContext(TrackMappingContext(originalFile = target.originalFile,
                        negotiatedIndex = target.audioStreamIndex, singleOutput = !target.direct), target.trackIntent?.subtitleIndex)
                    backend.setAudioStreams(mpvAudioStreams)
                    val mpvSubtitleStreams = playbackSource?.subtitleStreams
                        ?: mediaSources.firstOrNull { it.id == currentSourceId }?.subtitleStreams
                        ?: mediaSources.firstOrNull()?.subtitleStreams.orEmpty()
                    backend.setSubtitleStreams(mpvSubtitleStreams)
                    val subtitleSp = when { subScale >= 2.3f -> 38.4f; subScale >= 1.9f -> 32f; subScale >= 1.5f -> 25.6f; subScale >= 1.2f -> 20.8f; else -> 16f }
                    backend.setSubtitleFontSize((subtitleSp * context.resources.displayMetrics.density).toInt())
                    backend.setSubtitleStyle(subtitleColor, subtitleOutline, subtitlePosition, subtitleBg)
                    backend.setAudioTrack(target.audioStreamIndex ?: -1)
                    val resolvedSubtitle = target.trackIntent?.subtitlePreference ?: "auto"
                    subtitlePreference = resolvedSubtitle
                    when {
                        resolvedSubtitle == "off" || target.trackIntent?.requestedSubtitleIndex == -1 -> backend.disableSubtitle()
                        resolvedSubtitle.startsWith("track:") -> {
                            val index = resolvedSubtitle.removePrefix("track:").removePrefix("emby:").toIntOrNull()
                            if (index != null && mpvSubtitleStreams.any { it.index == index }) backend.setSubtitleTrack(index)
                            else backend.enableSubtitleAuto()
                        }
                        target.trackIntent?.subtitleIndex != null -> backend.setSubtitleTrack(target.trackIntent.subtitleIndex)
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
                    backend.setExternalSubtitleIdentities(externalSubtitles.zip(subs.map { it.index }).toMap())
                    backend.setSpeed(speedState.effectiveSpeed)
                    backend.setAspectMode(resizeMode)
                    if (!isCurrentLoad(generation)) { PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration"); return@launch }
                    backend.load(target.url, target.playerStartPositionMs, externalSubtitles, target.headers, paused = !desiredPlaying)
                    pendingStartIntent = null
                } else {
                    val configs = if (srcId == null) emptyList() else subs.map { st ->
                        androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
                            android.net.Uri.parse(c.subtitleUrl(item.id, srcId, st.index, "vtt"))
                        )
                            .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                            .setId(com.lumina.tv.player.EXTERNAL_SUBTITLE_ID_PREFIX + st.index)
                            .setLabel(st.displayTitle ?: st.language ?: "字幕${st.index}")
                            .setLanguage(st.language)
                            .setSelectionFlags(if (st.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    }
                    val subtitleHttpFactory = OkHttpDataSource.Factory(c.playbackHttpClient.newBuilder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build())
                        .setUserAgent("LuminaTV/1.5 (Android)")
                    val optionalFactory = OptionalVttDataSourceFactory(
                        targetCacheFactory ?: upstreamFactory,
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
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, target.trackIntent?.subtitlePreference == "off")
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                    if (!isCurrentLoad(generation)) return@launch
                    media3LoadGeneration = generation
                    if (!loadCoordinator.commit(generation) { playing ->
                        exo.playWhenReady = playing
                        progressSession = c.reportStart(item.id, target.startPositionMs, srcId, target.playMethod,
                            target.playSessionId, paused = !playing)
                    }) return@launch
                    exo.setMediaSource(videoSource)
                    exo.prepare()
                    pendingStartIntent = null
                    target.playerStartPositionMs?.let(exo::seekTo)
                    exo.setPlaybackSpeed(speedState.effectiveSpeed)
                }
            } catch (e: Exception) {
                val handled = finishPlaybackLoadFailure(e,
                    ownsLoad = { isCurrentLoad(generation) && !reloadPending },
                    updateStalled = ::updateStalled,
                    clearTarget = {
                        currentTarget = null
                        media3LoadGeneration = -1
                        rangeProbeJob?.cancel()
                    },
                ) { message ->
                    details.recordFailure(e)
                    if (failurePhase == "未知") failurePhase = "解析"
                    // PlaybackInfo/HTTP negotiation is shared by both engines.
                    if (isCertificateFailure(e)) showCertificateError(activeEngine)
                    error = message
                }
                if (!handled) PageLog.add("stale callback dropped gen=$generation/cur=$sessionGeneration")
            }
        }
    }

    fun buildDiagPayload(outcome: String): DiagPayload {
        phaseTimes.put("failedPaths", failureDiagnostics()).put("recoveryAttempts", recoveryBudget.attempts)
        val load = loadDetails.snapshot(currentLoadKey())
        val source = playbackSource?.takeIf { load.current && load.urlType in setOf("static", "hls") && it.id == load.key.sourceId }
        return buildPlayerDiagPayload(
            context, outcome, activeEngine, MpvAvailabilityProbe.process.snapshot(context),
            mpvBackend.takeIf { load.current }, item, source, load,
            audioDecodeMode, audioSupport.name, degraded, engineSwitchTrace, exo,
            if (load.current) failurePhase else "stale", mpvState.takeIf { load.current } ?: MpvPlayerController.State(),
            controlsVisible, isActuallyStalled, error.takeIf { load.current }, position,
            activeVideoDecoder.takeIf { load.current }, dvDecoderPath.takeIf { load.current }, playbackStatusNotice.takeIf { load.current },
            phaseTimes.takeIf { load.current } ?: JSONObject(), droppedFrames, renderedFrames, bufferingCount, bufferingDurationMs,
            duration, playbackDiagnostics, selectedAudioStreamIndex,
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

    LaunchedEffect(activeEngine, mpvBackend, resizeMode) {
        if (activeEngine == "mpv") mpvBackend?.setAspectMode(resizeMode)
    }

    // One entry point for initial load, rebuild, engine switch and explicit reload.
    LaunchedEffect(exo, item.id, reloadKey) {
        com.lumina.tv.ui.components.DetailPreviewPlayer.awaitRelease()
        loadSource(forceTranscode = reloadForceTranscode)
    }

    fun seekPlayerTo(targetMs: Long, chapter: Boolean = false) {
        val target = currentTarget
        val displayTarget = targetMs.coerceAtLeast(0L)
        val creditsWindow = creditsWindowStartMs(duration, currentCreditsStartMs, AppRuntime.prefs?.endCreditsSkipSeconds ?: 30)
        if (shouldCancelCreditsForSeek(position, displayTarget, creditsWindow, chapter)) {
            cancelCreditsCountdown(if (chapter) "chapter-before-credits" else "seek-before-credits")
        }
        val playerPosition = target?.contentToPlayerPositionMs(displayTarget) ?: displayTarget
        val now = android.os.SystemClock.elapsedRealtime()
        val beforeSeek = observedPlayerPositionMs() ?: lastPlayerPositionMs
        target?.timeline?.beforeSeek()
        seekConfirmationJob?.cancel()
        seekConfirmation = PendingSeekConfirmation(displayTarget, playerPosition, beforeSeek, now)
        phaseTimes.put("seekConfirmation", JSONObject().put("state", SeekConfirmation.PENDING.name)
            .put("targetMs", displayTarget).put("playerTargetMs", playerPosition)
            .put("timelineUnverified", target?.timeline?.calibration?.offsetMs == null))
        val attempted = runCatching {
            when (activeEngine) {
                "mpv" -> checkNotNull(mpvBackend).seekTo(playerPosition)
                else -> {
                    media3PositionAvailable = false
                    exo.seekTo(playerPosition)
                }
            }
        }
        if (attempted.isFailure) {
            confirmSeek(null, now, commandFailed = true)
        } else {
            val generation = sessionGeneration
            seekConfirmationJob = instanceScope.launch {
                delay(PendingSeekConfirmation.TIMEOUT_MS)
                if (isCurrentLoad(generation)) {
                    val observed = observedPlayerPositionMs()
                    target?.let { observeTimelinePosition(it, observed, null, android.os.SystemClock.elapsedRealtime()) }
                    confirmSeek(observed, android.os.SystemClock.elapsedRealtime())
                }
            }
        }
        if (!seekHoldActive) {
            previewFrame = null
            previewTargetMs = null
        }
    }

    fun togglePlayback() {
        if (exitPending) return
        desiredPlaying = !desiredPlaying
        if (!desiredPlaying) cancelCreditsCountdown("pause")
        when (activeEngine) {
            "mpv" -> if (desiredPlaying) mpvBackend?.play() else mpvBackend?.pause()
            else -> if (desiredPlaying) exo.play() else exo.pause()
        }
    }

    fun setPlaybackSpeed(value: Float) {
        when (activeEngine) {
            "mpv" -> mpvBackend?.setSpeed(value)
            else -> exo.setPlaybackSpeed(value)
        }
    }

    fun selectUserSpeed(value: Float) {
        speedState = speedState.select(value)
        AppRuntime.prefs?.defaultSpeed = speedState.userSpeed
        setPlaybackSpeed(speedState.userSpeed)
    }

    fun setBoostActive(active: Boolean) {
        speedState = speedState.boost(active)
        setPlaybackSpeed(speedState.effectiveSpeed)
    }

    DisposableEffect(exo, item.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: androidx.media3.common.PlaybackException) {
                if (!isCurrentMedia3()) return
                val errorClass = classifyPlaybackError(err)
                android.util.Log.e("LuminaTV", "Media3 playback failed code=${err.errorCode}", err)
                PageLog.add("playbackError category=${errorClass.category.name} code=${err.errorCode} action=${errorClass.action}")
                failurePhase = when {
                    errorClass.category == PlaybackErrorCategory.NETWORK_TRANSIENT || errorClass.category == PlaybackErrorCategory.HTTP_PERMISSION -> "连接"
                    errorClass.category == PlaybackErrorCategory.PARSING -> "解封装"
                    errorClass.category == PlaybackErrorCategory.DECODING -> "解码"
                    else -> "未知"
                }
                loadDetails.lastPlaybackErrorJson = JSONObject()
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
                val errorRoute = playbackErrorRoute(errorClass.category)
                if (errorRoute == PlaybackErrorRoute.CERTIFICATE) {
                    showCertificateError("media3")
                    return
                }
                if (errorRoute == PlaybackErrorRoute.AUTHENTICATION) {
                    audioRecoveryJob?.cancel()
                    audioRecoveryJob = null
                    recovering = false
                    playbackStatusNotice = null
                    failurePhase = "认证"
                    updateStalled(false)
                    error = "登录已失效，请重新登录"
                    exo.pause()
                    PageLog.add("playbackRecovery route=authentication action=terminal no-transcode")
                    return
                }
                if (audioRecoveryJob?.isActive == true) return
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
                val currentPlaybackUrl = loadDetails.currentPlaybackUrl
                val currentIsHls = currentPlaybackUrl?.let {
                    it.contains("master.m3u8", true) || it.contains(".m3u8", true)
                } == true
                val isBadHttpStatus = err.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    generateSequence(err as Throwable?) { it.cause }
                        .any { (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 400 }
                val rangeProbeDiagnostics = loadDetails.rangeProbeDiagnostics
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
                        val rangeProbeDiagnostics = loadDetails.rangeProbeDiagnostics
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
                            loadDetails.lastPlaybackErrorJson = JSONObject(loadDetails.lastPlaybackErrorJson.orEmpty())
                                .put("contentType", contentType)
                                .put("statusCode", rangeProbeDiagnostics.opt("status") ?: JSONObject.NULL)
                                .put("finalUrlHost", (rangeProbeDiagnostics.opt("finalUrlHost") as? String) ?: JSONObject.NULL)
                                .toString(2)
                            engineSwitchTrace.add("non-video-response:$contentType")
                            updateStalled(false)
                        } else {
                            recoverUnsupportedContainer(err.errorCodeName, generation)
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
                    loadDetails.lastPlaybackErrorJson = JSONObject(loadDetails.lastPlaybackErrorJson.orEmpty())
                        .put("contentType", probeContentType)
                        .put("statusCode", rangeProbeDiagnostics.opt("status") ?: JSONObject.NULL)
                        .put("finalUrlHost", (rangeProbeDiagnostics.opt("finalUrlHost") as? String) ?: JSONObject.NULL)
                        .toString(2)
                    engineSwitchTrace.add("non-video-response:$probeContentType")
                    updateStalled(false)
                } else if (errorRoute == PlaybackErrorRoute.SOURCE_GONE) {
                    if (!retryAlternateSource(sessionGeneration, err.errorCodeName)) {
                        updateStalled(false)
                        val status = generateSequence(err as Throwable?) { it.cause }
                            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode
                        error = "当前片源不可用${status?.let { "（HTTP $it）" }.orEmpty()}，请选择其他版本"
                        PageLog.add("playbackRecovery route=source-gone action=terminal sourceFallback=unavailable")
                    }
                } else if (errorRoute == PlaybackErrorRoute.AUDIO_OUTPUT) {
                    PageLog.add("playbackRecovery route=audio-output action=audio-recovery")
                    requestAudioRecovery("media3-audio-output")
                } else if (errorRoute == PlaybackErrorRoute.RESOURCE_RETRY) {
                    val generation = sessionGeneration
                    updateStalled(true)
                    error = null
                    playbackStatusNotice = "音频资源已释放，正在恢复播放"
                    recoverAutomatically("media3-resource-reclaimed", generation, 300L) {
                        playbackStatusNotice = null
                        if (isCurrentMedia3()) exo.prepare()
                    }
                } else if (shouldRetryNetworkPlayback(errorClass.category, networkRetryCount)) {
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
                } else if (errorRoute == PlaybackErrorRoute.NETWORK_RETRY) {
                    updateStalled(false)
                    playbackStatusNotice = null
                    failurePhase = "网络"
                    error = "媒体服务器暂时不可用，请稍后重试"
                    PageLog.add("playbackRecovery route=network-retry action=terminal retryLimit=2")
                } else if (errorRoute == PlaybackErrorRoute.PERMISSION_DENIED) {
                    updateStalled(false)
                    error = "媒体服务器拒绝了播放请求（权限或认证失败）"
                } else if (isBadHttpStatus && loadCoordinator.advanceHttp400(sessionGeneration)) {
                    updateStalled(true)
                    error = null
                    audioDecodeNotice = if (badHttpFallbackStage == 1) "HTTP 400，正在尝试移除字幕转码参数" else "字幕参数回退失败，正在尝试服务器直连地址"
                    engineSwitchTrace.add("http-400-fallback:$badHttpFallbackStage")
                    requestAutomaticReload(sessionGeneration, "media3:${err.errorCodeName}")
                } else if (audioDecodeMode == "passthrough" && decodingUnsupported) {
                    requestAudioRecovery("passthrough-unsupported")
                } else if (audioDecodingUnsupported) {
                    requestAudioRecovery("decoder-unsupported")
                } else if (audioDecodeMode != "passthrough" && decodingUnsupported && canTryMpvPath()) {
                    updateStalled(false)
                    audioDecodeNotice = "本机解码不支持该音轨，已自动切到 mpv 本地软解"
                    enginePinned = "mpv"
                    reloadForceTranscode = false
                    requestAutomaticReload(sessionGeneration, "media3-decoding")
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
                    requestAutomaticReload(sessionGeneration, "media3:${err.errorCodeName}")
                } else if (containerUnsupported && currentIsHls) {
                    updateStalled(false)
                    error = "该片源无法播放（转码流与直连都失败）"
                    engineSwitchTrace.add("container-terminal:hls:${err.errorCodeName}")
                } else if (containerUnsupported) {
                    recoverUnsupportedContainer(err.errorCodeName, sessionGeneration)
                } else if (!degraded) {
                    degraded = true
                    transcodeFallbackTried = true
                    reloadForceTranscode = true
                    error = "直连失败，自动切换转码…"
                    requestAutomaticReload(sessionGeneration, "media3:${err.errorCodeName}")
                } else if (!rebuildRetry && !containerUnsupported) {
                    error = "媒体服务器转码流失败，重试转码路径…"
                    reloadForceTranscode = true
                    engineSwitchTrace.add("server-transcode-retry:${err.errorCodeName}")
                    val generation = sessionGeneration
                    recoverAutomatically("media3-rebuild", generation) {
                        rebuildRetry = true
                        requestPlaybackReload(generation)
                    }
                } else if (retryAlternateSource(sessionGeneration, err.errorCodeName)) {
                    // The existing ordered source fallback has taken ownership of this failure.
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
                    requestAutomaticReload(sessionGeneration, "media3-decoding")
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
                    if (exo.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) media3PositionAvailable = true
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
                    reportStopInAppScope()
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
                val audioCandidates = mediaAudio.map { (group, index) ->
                    AudioTrackCandidate(group.mediaTrackGroup, index, group.getTrackFormat(index), group.isTrackSupported(index))
                }
                media3AudioCandidates = audioCandidates
                media3AudioSourceId = audioSourceKey()
                val target = currentTarget
                val original = target?.originalFile == true
                val table = media3TrackMapping(embyAudio, audioCandidates, TrackMappingContext(
                    originalFile = original, orderEvidence = media3OrderEvidence(original, playbackSource?.container),
                    negotiatedIndex = target?.audioStreamIndex, singleOutput = target?.direct == false))
                if (table != audioMapping) PageLog.add("Media3 audio mapping load=$sessionGeneration ${table.summary()}")
                audioMapping = table
                val textTable = media3TrackMapping(embySubs, mediaSubs.map { (group, index) ->
                    AudioTrackCandidate(group.mediaTrackGroup, index, group.getTrackFormat(index), group.isTrackSupported(index))
                }, TrackMappingContext(originalFile = original, negotiatedIndex = target?.trackIntent?.subtitleIndex,
                    singleOutput = target?.direct == false))
                if (textTable != subtitleMapping) PageLog.add("Media3 subtitle mapping load=$sessionGeneration ${textTable.summary()}")
                subtitleMapping = textTable
                selectedAudioStreamIndex = mediaAudio.firstOrNull { (group, index) -> group.isTrackSelected(index) }
                    ?.let { (group, index) -> table.embyFor(Media3TrackKey(group.mediaTrackGroup, index)) }
                rememberConfirmedAudio(selectedAudioStreamIndex)
                if (!audioSelectionApplied && audioCandidates.isNotEmpty()) {
                    val streamIndex = target?.audioStreamIndex
                    val supportedCandidates = audioCandidates.filter { it.supported }
                    val recovered = audioRecoveryTrack?.takeIf { audioRecoverySourceId == audioSourceKey() }?.let { recovery ->
                        supportedCandidates.firstOrNull { it.group == recovery.group && it.index == recovery.index }
                    }
                    val selected = recovered ?: streamIndex?.let { table.candidateFor(it, audioCandidates) }?.takeIf { it.supported }
                    audioSelectionApplied = true
                    if (selected != null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(TrackSelectionOverride(selected.group, selected.index)).build()
                    }
                }
                if (audioCandidates.isNotEmpty() && audioCandidates.none { it.supported }) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                    // isTrackSupported() reflects the renderer's sink configuration at this instant and
                    // can report false for a track that then plays fine. Clearing overrides and letting
                    // ExoPlayer select is enough; real audio failures still arrive on onPlayerError and
                    // route to AUDIO_OUTPUT recovery. Declaring the path failed here costs a working
                    // engine switch (and used to cascade into a dead end when mpv was also unavailable).
                    PageLog.add("audioCapability action=no-override-deferred supported=0 total=${audioCandidates.size}")
                }
                val sourceHasAudio = playbackSource?.audioStreams?.isNotEmpty() == true
                val selectedMediaAudio = mediaAudio.any { (group, index) -> group.isTrackSelected(index) }
                if (shouldRecoverAudioWithoutSelection(sourceHasAudio, selectedMediaAudio, noAudioRetryTried)) {
                    val generation = sessionGeneration
                    PageLog.add("audioCapability action=deferred-selection-check candidates=${audioCandidates.size}")
                    instanceScope.launch {
                        delay(3_000)
                        val selectedAudioAfterWait = exo.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .any { group -> (0 until group.length).any(group::isTrackSelected) }
                        if (isCurrentLoad(generation) && activeEngine == "media3" &&
                            exo.playbackState == Player.STATE_READY &&
                            shouldRecoverAudioWithoutSelection(
                                sourceHasAudio = playbackSource?.audioStreams?.isNotEmpty() == true,
                                selectedAudioTrack = selectedAudioAfterWait,
                                recoveryAlreadyTried = noAudioRetryTried,
                            )
                        ) {
                            noAudioRetryTried = true
                            val audioGroupsExist = exo.currentTracks.groups
                                .any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
                            val reason = if (audioGroupsExist) "audio-track-not-selected" else "missing-audio-track"
                            PageLog.add("audioCapability action=recover-after-selection-wait reason=$reason")
                            requestAudioRecovery(reason)
                        }
                    }
                }
                if (subtitleSelectionApplied) return
                // Use the same source-bound intent that was sent to PlaybackInfo.
                val resolvedSubtitle = currentTarget?.trackIntent?.subtitlePreference ?: "auto"
                subtitlePreference = resolvedSubtitle
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                if (resolvedSubtitle == "off" || currentTarget?.trackIntent?.requestedSubtitleIndex == -1) {
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
                    val streamIndex = key.removePrefix("emby:").toIntOrNull() ?: return null
                    val mapped = subtitleMapping.playerFor(streamIndex) ?: return null
                    return all.singleOrNull { (group, index, _) -> group.mediaTrackGroup == mapped.group && index == mapped.index }
                }
                val selected = currentTarget?.trackIntent?.subtitleIndex?.let { findSubtitle("emby:$it") }
                if (resolvedSubtitle.startsWith("track:") && selected == null && currentTarget?.direct == true) {
                    playbackStatusNotice = "字幕对应关系不确定，可在菜单选择播放器字幕"
                }
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
                if (!playWhenReady) cancelCreditsCountdown("media3-pause")
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
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                playbackDiagnostics.identity?.let { playbackDiagnostics.decoder(it, true, decoderName) }
            }

            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (!isCurrentMedia3Event(eventTime)) return
                playbackDiagnostics.identity?.let { playbackDiagnostics.decoder(it, true, null) }
            }

            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                PlayerLifecycle.registry.decoderReleased(exo, decoderName)
                if (!isCurrentMedia3Event(eventTime)) return
                playbackDiagnostics.identity?.let { playbackDiagnostics.decoder(it, false, null) }
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                playbackDiagnostics.identity?.let { load ->
                    playbackDiagnostics.audioTrack(load, audioTrackConfig.encoding, audioTrackConfig.sampleRate,
                        audioTrackConfig.channelConfig, audioTrackConfig.offload)
                }
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                playbackDiagnostics.identity?.let { playbackDiagnostics.releaseAudioTrack(it) }
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                PlayerLifecycle.registry.decoderInitialized(exo, decoderName)
                if (!isCurrentMedia3Event(eventTime)) return
                activeVideoDecoder = decoderName
                playbackDiagnostics.identity?.let { playbackDiagnostics.decoder(it, false, decoderName) }
                phaseTimes.put("decoderInitMs", android.os.SystemClock.elapsedRealtime() - playStartAt)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                if (!isCurrentMedia3Event(eventTime)) return
                media3PositionAvailable = true
                val now = android.os.SystemClock.elapsedRealtime()
                currentTarget?.let { target ->
                    onTimelineFirstFrame(target, now)
                    observeTimelinePosition(target, exo.currentPosition.takeIf { it >= 0L }, exo.duration, now)
                }
                firstFrameRendered = true
                updateStalled(exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady)
                phaseTimes.put("firstFramePositionMs", exo.currentPosition)
                if (firstFrameAt == 0L) firstFrameAt = now
                val effectiveReadyAt = readyAt.takeIf { it > 0L } ?: firstFrameAt
                if (readyAt == 0L) readyAt = effectiveReadyAt
                val loadToReadyMs = (effectiveReadyAt - loadStartedAt).coerceAtLeast(0L)
                val loadToFirstFrameMs = (firstFrameAt - loadStartedAt).coerceAtLeast(0L)
                val readyToFirstFrameMs = (firstFrameAt - effectiveReadyAt).coerceAtLeast(0L)
                phaseTimes.put("loadToReadyMs", loadToReadyMs)
                    .put("loadToFirstFrameMs", loadToFirstFrameMs)
                    .put("readyToFirstFrameMs", readyToFirstFrameMs)
                    .put("firstFrameMs", loadToFirstFrameMs)
                playbackDiagnostics.identity?.let { playbackDiagnostics.firstFrame(it, firstFrameAt, "Media3.onRenderedFirstFrame") }
                readyWithoutFrameSince = 0L
                if (strmRestartNoticePending) {
                    strmRestartNoticePending = false
                    strmRestartNoticeShown = true
                    showTimelineNotice("此片源未能续播，已从头播放")
                } else if (seekConfirmation == null && phaseTimes.optJSONObject("seekConfirmation")?.optString("state") != "ROLLED_BACK") {
                    playbackStatusNotice = null
                }
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
            reportStopInAppScope()
            prewarmJob?.cancel()
            cancelCreditsCountdown("player-disposed")
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
        val current = pendingSeekTarget ?: seekConfirmation?.displayTargetMs ?: position
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

    fun dispatchHiddenInput(event: HiddenPlaybackEvent): Boolean {
        val transition = reduceHiddenPlaybackInput(hiddenInputState, event, android.os.SystemClock.elapsedRealtime(),
            hiddenTouchEnabled(controlsVisible, optionMenu, quickPanel != QuickPanel.NONE, error != null))
        hiddenInputState = transition.state
        transition.actions.forEach { action -> when (action) {
            HiddenPlaybackAction.ShowControls -> controlsVisible = true
            is HiddenPlaybackAction.Boost -> setBoostActive(action.enabled)
            is HiddenPlaybackAction.BeginSeek -> beginSeekHold(action.direction, action.repeat)
            is HiddenPlaybackAction.EndSeek -> endSeekHold(action.direction)
        } }
        return transition.consumed
    }

    LaunchedEffect(hiddenInputState.key, hiddenInputState.downAtMs) {
        if (hiddenInputState.key == HiddenPlaybackKey.OK) {
            delay(HIDDEN_OK_HOLD_MS)
            dispatchHiddenInput(HiddenPlaybackEvent.HoldTimeout)
        }
    }
    LaunchedEffect(optionMenu, quickPanel, error, sessionGeneration) {
        // A new load, menu or terminal error cancels the previous capture; KeyUp cannot fire short.
        dispatchHiddenInput(HiddenPlaybackEvent.Cancel)
    }
    val inputLifecycleOwner = LocalLifecycleOwner.current
    val hostActiveEngine by rememberUpdatedState(activeEngine)
    val hostMpvBackend by rememberUpdatedState(mpvBackend)
    val hostResumedByBackground by rememberUpdatedState(resumedByBackground)
    val hostSessionGeneration by rememberUpdatedState(sessionGeneration)
    DisposableEffect(inputLifecycleOwner, exo, item.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) dispatchHiddenInput(HiddenPlaybackEvent.Cancel)
            if (event == Lifecycle.Event.ON_STOP) {
                // Intent also covers negotiation, Surface waits and temporary audio-focus holds.
                val playbackRequested = loadCoordinator.desiredPlaying
                val transition = reducePlayerHostLifecycle(
                    hostResumedByBackground,
                    PlayerHostLifecycleEvent.ON_STOP,
                    playbackRequested,
                )
                resumedByBackground = transition.resumedByBackground
                if (transition.pausePlayback) {
                    desiredPlaying = false
                    cancelCreditsCountdown("host-stop")
                    if (hostActiveEngine == "mpv") hostMpvBackend?.pause() else exo.pause()
                    isPlaying = false
                    PageLog.add("playbackLifecycle event=ON_STOP action=pause engine=$hostActiveEngine generation=$hostSessionGeneration")
                }
            } else if (event == Lifecycle.Event.ON_START) {
                val transition = reducePlayerHostLifecycle(
                    hostResumedByBackground,
                    PlayerHostLifecycleEvent.ON_START,
                    playbackRequested = false,
                )
                resumedByBackground = transition.resumedByBackground
                if (transition.restoreControls) {
                    controlsVisible = true
                    lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                    showTimelineNotice("已从后台返回，播放保持暂停")
                    PageLog.add("playbackLifecycle event=ON_START action=show-controls-paused generation=$hostSessionGeneration")
                }
            }
        }
        inputLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            inputLifecycleOwner.lifecycle.removeObserver(observer)
            seekHoldJob?.cancel()
            seekHoldJob = null
            seekHoldDirection = 0
            seekHoldActive = false
            seekHoldDelta = 0L
            hiddenInputState = HiddenPlaybackInputState()
            speedState = speedState.boost(false)
            previewActive = false
            previewFrame = null
            previewTargetMs = null
        }
    }

    fun requestWatchdogRecovery(reason: WatchdogReason) {
        if (recovering || audioRecoveryJob?.isActive == true || optionMenu || error != null || reloadPending) return
        val generation = sessionGeneration
        val recoveryPosition = position
        val mpvLoad = mpvBackend?.diagnosticLoadIdentity().takeIf { activeEngine == "mpv" }
        val bufferedAtHit = mpvState.bufferedPositionMs
        recoverAutomatically("watchdog:${reason.name}", generation, 300L,
            stillNeeded = {
                val waitingForMpvCache = mpvLoad != null &&
                    (reason == WatchdogReason.BUFFERING_NO_LOAD || reason == WatchdogReason.BUFFERING_NO_PROGRESS)
                !playbackPaused() && seekConfirmation == null && !seekInFlight && !seekHoldActive &&
                    (mpvLoad == null || mpvBackend?.diagnosticLoadIdentity() == mpvLoad) &&
                    if (waitingForMpvCache) {
                        isActuallyStalled && mpvState.cacheSpeedBytesPerSecond == 0L && mpvState.bufferedPositionMs == bufferedAtHit
                    } else position == recoveryPosition || isActuallyStalled || !firstFrameRendered
            }) {
            recordPathFailure(PlaybackFailureReason.WATCHDOG, reason.name)
            if (recoveryBudget.attempts > 1) {
                val next = mediaSources.firstOrNull { it.id != currentSourceId && it.id !in triedSources }
                if (next != null) {
                    triedSources = triedSources + listOfNotNull(currentSourceId)
                    currentSourceId = next.id
                    enginePinned = null
                    directFallbackTried = false
                    transcodeFallbackTried = false
                }
            }
            reloadForceTranscode = currentPath?.delivery == PlaybackDelivery.TRANSCODE
            requestPlaybackReload(generation)
        }
    }

    LaunchedEffect(exo, item.id) {
        var sinceReport = 0
        var bufferingSince = 0L
        while (true) {
            delay(500)
            if (!instanceJob.isActive || currentExo !== exo) return@LaunchedEffect
            val target = currentTarget
            val now = android.os.SystemClock.elapsedRealtime()
            val playerPosition = observedPlayerPositionMs()
            val playerDuration = when (activeEngine) {
                "mpv" -> mpvBackend?.durationMs()
                else -> exo.duration.takeIf { isCurrentMedia3() && it > 0L }
            }
            target?.let { observeTimelinePosition(it, playerPosition, playerDuration, now) }
            target?.let { checkStrmResume(it, now) }
            if (activeEngine == "mpv" && strmRestartNoticePending && mpvState.ready && !reloadPending && target?.startPositionMs == 0L) {
                strmRestartNoticePending = false
                strmRestartNoticeShown = true
                showTimelineNotice("此片源未能续播，已从头播放")
            }
            val contentTimelineKnown = target?.timelineOrigin == TimelineOrigin.CONTENT
            val watchdogMpvLoad = if (activeEngine == "mpv") mpvBackend?.diagnosticLoadIdentity() else null
            val ownsEngine = if (activeEngine == "media3") isCurrentMedia3() else watchdogMpvLoad != null && !reloadPending
            val reason = if (ownsEngine) watchdog.onTick(
                positionMs = playerPosition ?: 0L,
                bufferedMs = if (activeEngine == "media3") exo.bufferedPosition.takeIf { it >= 0L } else mpvState.bufferedPositionMs,
                state = if (activeEngine == "media3") exo.playbackState else if (isActuallyStalled) Player.STATE_BUFFERING else if (mpvState.ready) Player.STATE_READY else Player.STATE_IDLE,
                playWhenReady = if (activeEngine == "media3") exo.playWhenReady else mpvState.playing,
                isSeeking = seekConfirmation != null || seekInFlight || seekHint != null || seekHoldActive || previewActive,
                sessionGeneration = sessionGeneration,
                durationMs = duration,
                bytesLoaded = bytesLoaded,
                isLoading = isLoading,
                mpvLoadId = watchdogMpvLoad?.physicalId,
                downloadInProgress = watchdogMpvLoad != null && mpvState.cacheSpeedBytesPerSecond > 0L,
                nowMs = now,
            ) else null
            if (reason != null) {
                playbackDiagnostics.identity?.takeIf { it.generation == sessionGeneration }?.let {
                    playbackDiagnostics.watchdog(it, android.os.SystemClock.elapsedRealtime())
                }
                requestWatchdogRecovery(reason)
            }
            val creditsSkipSeconds = AppRuntime.prefs?.endCreditsSkipSeconds ?: 30
            val creditsWindow = creditsWindowStartMs(duration, currentCreditsStartMs, creditsSkipSeconds)
            if (playbackPaused() && creditsCountdown.active) cancelCreditsCountdown("pause-observed")
            if (contentTimelineKnown && creditsWindow != null && item.seriesId != null && !creditsCountdown.triggered &&
                !playbackPaused() && seekConfirmation == null && !seekInFlight && !seekHoldActive && !previewActive &&
                position >= creditsWindow
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
            if (error == null && !reloadPending && reportSession != null && !reportSession.isClosed &&
                sinceReport >= 10 && lastPlayerPositionMs != null) {
                sinceReport = 0
                val paused = if (activeEngine == "mpv") !mpvState.playing else !exo.isPlaying
                val sample = timelinePosition(lastPlayerPositionMs)
                client?.reportProgress(reportSession, sample.reportPositionMs, paused,
                    timelineUnverified = sample.timelineUnverified, playerPositionMs = sample.playerPositionMs)
                phaseTimes.put("lastProgress", client?.lastProgressDiagnostics ?: JSONObject.NULL)
                PageLog.add("progress: event=Progress contentPos=${sample.contentPositionMs} playerPos=${sample.playerPositionMs} unverified=${sample.timelineUnverified}")
            }
        }
    }

    LaunchedEffect(controlsVisible, lastInteractionAt, isPlaying, speedState.boostActive, optionMenu, quickPanel, controlHasFocus, hiddenInputState.active) {
        if (controlsVisible && isPlaying && !optionMenu && quickPanel == QuickPanel.NONE && !speedState.boostActive && !hiddenInputState.active) {
            val remaining = (lastInteractionAt + 5_000L - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(remaining)
            if (android.os.SystemClock.elapsedRealtime() - lastInteractionAt >= 5_000L && !controlHasFocus) controlsVisible = false
        }
    }

    // 焦点：控制栏隐藏时由捕获层拿焦点，显示时把焦点交给播放按钮
    LaunchedEffect(controlsVisible, optionMenu, quickPanel, hiddenInputState.active) {
        delay(80)
        if (!optionMenu && quickPanel == QuickPanel.NONE &&
            android.os.SystemClock.elapsedRealtime() >= restoreMenuFocusUntil
        ) {
            if (controlsVisible && !hiddenInputState.active) {
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
    LaunchedEffect(quickPanel, selectedSeasonId, item.seriesId, episodePages, episodeRetry) {
        if (quickPanel != QuickPanel.EPISODES) return@LaunchedEffect
        val sid = item.seriesId ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val seasonId = selectedSeasonId
        episodesLoadFailed = false
        val cached = episodePages.cached(seasonId) { LibraryCache.episodesOf(sid, seasonId) }
        if (cached != null) {
            quickEpisodes = cached
            episodesLoading = false
            return@LaunchedEffect
        }
        quickEpisodes = LibraryCache.episodesStaleOf(sid, seasonId).orEmpty()
        episodesLoading = quickEpisodes.isEmpty()
        val revision = LibraryCache.userDataRevision
        val result = episodePages.fetch(seasonId, { c.getEpisodes(sid, seasonId) }) {
            LibraryCache.putEpisodes(sid, seasonId, it, revision)
        }
        if (result.error == null) quickEpisodes = result.episodes
        episodesLoadFailed = result.error != null
        episodesLoading = false
    }
    LaunchedEffect(quickPanel, seasons.firstOrNull()?.id, quickEpisodes.firstOrNull()?.id, episodesLoadFailed) {
        if (quickPanel == QuickPanel.NONE || quickContentHasFocus || inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        val firstFocus = if (quickPanel == QuickPanel.SPEED) {
            speedFirstFocus
        } else {
            seasons.firstOrNull()?.let { seasonFocuses.getOrPut(it.id) { FocusRequester() } }
                ?: quickEpisodes.firstOrNull()?.let { episodeItemFocuses.getOrPut(it.id) { FocusRequester() } }
                ?: episodeRetryFocus.takeIf { episodesLoadFailed }
        }
        if (firstFocus == null) return@LaunchedEffect
        withFrameNanos { }
        if (!quickContentHasFocus) runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(quickPanel, selectedSeasonId, quickEpisodes, episodesLoading, seasonChangePendingFocus) {
        if (quickPanel != QuickPanel.EPISODES) {
            seasonChangePendingFocus = false
            return@LaunchedEffect
        }
        if (!seasonChangePendingFocus || episodesLoading) return@LaunchedEffect
        if (inputMode.inputMode != InputMode.Keyboard) {
            seasonChangePendingFocus = false
            return@LaunchedEffect
        }
        val firstEpisode = quickEpisodes.firstOrNull() ?: run {
            seasonChangePendingFocus = false
            return@LaunchedEffect
        }
        val requester = episodeItemFocuses.getOrPut(firstEpisode.id) { FocusRequester() }
        withFrameNanos { }
        runCatching { requester.requestFocus() }
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
        if (exitPending) return
        exitPending = true
        sessionGeneration++
        instanceJob.cancel()
        cancelCreditsCountdown("player-exit")
        showExitPrompt = false
        exitPromptAt = 0L
        reportStopInAppScope()
        if (activeEngine == "mpv") mpvBackend?.pause() else exo.stop()
        scope.launch {
            // Graceful exit gives the Stop disk barrier at most 2s without blocking the UI.
            client?.awaitProgressPersistence()
            onBack()
        }
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
        runCatching { triggerFocus.requestFocus() }
    }

    fun dismissSpeedPanel() {
        if (quickPanel != QuickPanel.SPEED) return
        restoreMenuFocusUntil = android.os.SystemClock.elapsedRealtime() + 300L
        quickPanel = QuickPanel.NONE
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
            val capturedKey = when (event.key) {
                Key.DirectionCenter, Key.Enter -> HiddenPlaybackKey.OK
                Key.DirectionLeft -> HiddenPlaybackKey.LEFT
                Key.DirectionRight -> HiddenPlaybackKey.RIGHT
                else -> null
            }
            if (capturedKey != null && (!controlsVisible || hiddenInputState.active) &&
                !optionMenu && quickPanel == QuickPanel.NONE && error == null) {
                val input = if (event.nativeKeyEvent.isCanceled) HiddenPlaybackEvent.Cancel else when (event.type) {
                    KeyEventType.KeyDown -> HiddenPlaybackEvent.Down(capturedKey, event.nativeKeyEvent.repeatCount)
                    KeyEventType.KeyUp -> HiddenPlaybackEvent.Up(capturedKey)
                    else -> null
                }
                if (input != null) {
                    lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                    if (input is HiddenPlaybackEvent.Down && creditsCountdown.nextItem != null) cancelCreditsCountdown("remote-key", allowRestart = false)
                    if (dispatchHiddenInput(input) || !controlsVisible) return@onPreviewKeyEvent true
                }
            }
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
                    attachedPlayerView.set(this)
                    useController = false
                    player = exo
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    keepScreenOn = true
                }
            },
            update = { pv ->
                attachedPlayerView.set(pv)
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
            onRelease = { pv ->
                pv.player = null
                attachedPlayerView.compareAndSet(pv, null)
            },
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

        // Above both native surfaces, below buttons/menus/countdown: only blank hidden taps hit it.
        if (hiddenTouchEnabled(controlsVisible, optionMenu, quickPanel != QuickPanel.NONE, error != null)) {
            Box(Modifier.fillMaxSize().pointerInput(item.id) {
                detectTapGestures(onTap = {
                    lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                    dispatchHiddenInput(HiddenPlaybackEvent.Tap)
                })
            })
        }
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
                        requestPlaybackReload(sessionGeneration)
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
                                requestPlaybackReload(sessionGeneration)
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
                        requestPlaybackReload(sessionGeneration)
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
                                    seekPlayerTo(chapter.startMs, chapter = true)
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
            visible = speedState.boostActive,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text(
                if (speedState.boostActive) "3x 快进中 »»" else seekHint.orEmpty(),
                color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 下一集倒计时卡片
        creditsCountdown.nextItem?.let { ne ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 96.dp)
                    .background(Color(0xE6070A12), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column {
                    Text(
                        "${creditsCountdown.remainingSeconds} 秒后播放下一集",
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
                            onClick = { cancelCreditsCountdown("cancel-button", allowRestart = false) },
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

        // Stable focus receiver. Root preview owns OK/Left/Right until KeyUp; visibility never unmounts it.
        run {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(hideFocus)
                    .focusProperties {
                        canFocus = hiddenTouchEnabled(controlsVisible, optionMenu, quickPanel != QuickPanel.NONE, error != null) || hiddenInputState.active
                    }
                    .onFocusChanged { if (!it.isFocused) dispatchHiddenInput(HiddenPlaybackEvent.Cancel) }
                    .focusable()
                    .onKeyEvent { ev ->
                        if (controlsVisible || optionMenu || quickPanel != QuickPanel.NONE || error != null) return@onKeyEvent false
                        if (ev.type == KeyEventType.KeyDown && creditsCountdown.nextItem != null) {
                            cancelCreditsCountdown("remote-key", allowRestart = false)
                        }
                        when (ev.key) {
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
            visible = controlsOverlayVisible,
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
                    if (canShowTrickplay && frame != null && target != null) {
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
                            dismissSpeedPanel()
                            seekPlayerTo(target)
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
                        if (desiredPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (desiredPlaying) "暂停" else "播放",
                        primary = true,
                        focusRequester = playFocus,
                    ) { dismissSpeedPanel(); togglePlayback() }
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
                        if (speedState.userSpeed == 1f) clockText else "$clockText  ${"%.1f".format(Locale.US, speedState.userSpeed)}x",
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
                        else {
                            quickContentHasFocus = false
                            quickPanel = QuickPanel.SPEED
                        }
                        optionMenu = false
                    }
                    if (hasEpisodeButton) {
                        OsdIconButton(Icons.Rounded.PlaylistPlay, "选集", episodeFocus, speedFocus, menuFocus, playFocus, { controlHasFocus = it }) {
                            if (quickPanel == QuickPanel.EPISODES) closeQuickPanel(episodeFocus)
                            else {
                                quickContentHasFocus = false
                                quickPanel = QuickPanel.EPISODES
                            }
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
                        Modifier.heightIn(max = 250.dp)
                            .onFocusChanged {
                                if (it.hasFocus) quickContentHasFocus = true
                                else if (quickContentHasFocus) dismissSpeedPanel()
                            }
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(SPEEDS.size) { index ->
                            val s = SPEEDS[index]
                            val active = kotlin.math.abs(speedState.userSpeed - s) < 0.01f
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                                    .focusRequester(speedItemFocuses[index])
                                    .focusProperties {
                                        up = if (index == 0) triggerFocus else FocusRequester.Default
                                        down = if (index == SPEEDS.lastIndex) triggerFocus else FocusRequester.Default
                                        left = playFocus
                                        right = if (item.seriesId != null) episodeFocus else menuFocus
                                    },
                                corner = 8.dp,
                                scaleTo = 1.08f,
                                onClick = {
                                    selectUserSpeed(s)
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
                                        .onFocusChanged {
                                            seasonFocused = it.isFocused
                                            if (it.isFocused) quickContentHasFocus = true
                                        }
                                        .clickable {
                                            if (selectedSeasonId != season.id) {
                                                seasonChangePendingFocus = true
                                                val sid = item.seriesId
                                                val cached = if (sid == null) null else episodePages.cached(season.id) {
                                                    LibraryCache.episodesOf(sid, season.id)
                                                }
                                                quickEpisodes = cached ?: sid?.let { LibraryCache.episodesStaleOf(it, season.id) }.orEmpty()
                                                episodesLoading = cached == null && quickEpisodes.isEmpty()
                                                episodesLoadFailed = false
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
                    if (episodesLoadFailed) {
                        FocusSurface(
                            modifier = Modifier.height(32.dp).fillMaxWidth(),
                            focusRequester = episodeRetryFocus,
                            onClick = {
                                seasonChangePendingFocus = true
                                episodeRetry++
                            },
                        ) {
                            Text("剧集读取失败 · 点此重试", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = TextMain, fontSize = 12.sp)
                        }
                    }
                    Box(Modifier.weight(1f, fill = false)) {
                        if (quickEpisodes.isEmpty() && (seasonsLoading || episodesLoading)) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(20.dp),
                                color = Accent,
                                strokeWidth = 2.dp,
                            )
                        } else if (quickEpisodes.isEmpty() && !episodesLoadFailed) {
                            Text(
                                "本季暂无集数",
                                color = TextSub,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else if (quickEpisodes.isNotEmpty()) {
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
                                            if (active) {
                                                closeQuickPanel(triggerFocus)
                                            } else {
                                                quickPanel = QuickPanel.NONE
                                                reportStopInAppScope()
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
            val menuMpv = mpvBackend.takeIf { activeEngine == "mpv" }
            val mpvTracks = remember(menuMpv, mpvMenuTrackRevision, subtitlePreference) { menuMpv?.menuTracks() }
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
                currentMpvSubtitleIndex = mpvTracks?.subtitleIndex,
                currentMpvSubtitleDisabled = mpvTracks?.subtitleDisabled == true,
                audioStreams = playbackSource?.audioStreams
                    ?: mediaSources.firstOrNull { it.id == currentSourceId }?.audioStreams
                    ?: mediaSources.firstOrNull()?.audioStreams.orEmpty(),
                selectedAudioStreamIndex = if (activeEngine == "mpv") mpvTracks?.audioIndex else selectedAudioStreamIndex,
                audioMapping = audioMapping,
                subtitleMapping = subtitleMapping,
                mpvUncertainAudio = mpvTracks?.uncertainAudio.orEmpty(),
                currentMpvAudioId = mpvTracks?.playerAudioId,
                onPlayerAudioSelected = { key, mpvId ->
                    // A real player track is selectable even without an Emby identity; never persist it.
                    pendingManualAudio = null
                    audioSelectionApplied = true
                    if (activeEngine == "mpv" && mpvId != null) {
                        mpvBackend?.selectPlayerAudioTrack(mpvId)
                        mpvMenuTrackRevision++
                    }
                    if (activeEngine == "media3" && key != null) {
                        val supported = exo.currentTracks.groups.any { it.mediaTrackGroup == key.group &&
                            key.index in 0 until it.length && it.isTrackSupported(key.index) }
                        if (supported) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(TrackSelectionOverride(key.group, key.index)).build()
                        else playbackStatusNotice = "本机不支持这条音轨"
                    }
                },
                onAudioStreamSelected = { streamIndex, override, unsupported ->
                    val source = playbackSource
                    if (source != null && source.audioStreams.any { it.index == streamIndex }) {
                        audioSelectionApplied = true
                        audioRecoveryJob?.cancel()
                        audioRecoveryJob = null
                        audioRecoveryTrack = null
                        audioRecoverySourceId = null
                        val requiresNegotiation = currentTarget?.direct == false
                        val mapped = if (activeEngine == "mpv")
                            mpvBackend?.hasAudioMapping(streamIndex) == true else audioMapping.playerFor(streamIndex) != null
                        if (mapped || requiresNegotiation) currentPath = currentPath?.copy(audioTrack = streamIndex.toString())
                        pendingManualAudio = com.lumina.tv.player.trackMemoryIdentity(source, "Audio") to streamIndex
                        manualAudioStreamSelection = true
                        if (requiresNegotiation) {
                            loadSource(forceTranscode = currentTarget?.playMethod == "Transcode", audioStreamIndex = streamIndex)
                        } else if (!mapped) {
                            pendingManualAudio = null
                            playbackStatusNotice = "这条音轨的对应关系不确定，请选择下方播放器音轨"
                            PageLog.add("trackSelection action=unresolved embyIndex=$streamIndex")
                        } else if (activeEngine == "mpv") {
                            mpvBackend?.setAudioTrack(streamIndex)
                            mpvMenuTrackRevision++
                            rememberConfirmedAudio(mpvBackend?.currentAudioEmbyIndex())
                        } else if (unsupported) {
                            audioDecodeNotice = "本机不支持所选音轨，正在尝试兼容播放路径"
                            PageLog.add("audioCapability action=manual-unsupported index=$streamIndex")
                            requestAudioRecovery("manual-unsupported")
                        } else if (override != null && exo.currentTracks.groups.any { group ->
                                group.mediaTrackGroup == override.mediaTrackGroup && override.trackIndices.all { group.isTrackSupported(it) }
                            }) {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(override).build()
                        }
                    }
                },
                onServerSubtitleSelected = { index ->
                    playbackSource?.let { source ->
                        AppRuntime.prefs?.rememberSubtitleTrack(item.id, "track:emby:$index", source)
                        subtitlePreference = "track:emby:$index"
                        subtitleSelectionApplied = true
                        val mapped = subtitleMapping.playerFor(index)
                        if (currentTarget?.direct == false) {
                            loadSource(forceTranscode = currentTarget?.playMethod == "Transcode")
                        } else if (activeEngine == "mpv") {
                            mpvBackend?.setSubtitleTrack(index)
                        } else if (mapped != null && exo.currentTracks.groups.any {
                                it.mediaTrackGroup == mapped.group && it.isTrackSupported(mapped.index)
                            }) {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .addOverride(TrackSelectionOverride(mapped.group, mapped.index)).build()
                        } else {
                            playbackStatusNotice = "字幕对应关系不确定，可在菜单选择播放器字幕"
                        }
                    }
                },
                onSubtitleSelected = { key ->
                    subtitleSelectionApplied = true
                    val stored = when {
                        key == null -> "off"
                        key == "auto" -> "auto"
                        else -> "track:$key"
                    }
                    val localKey = key?.takeUnless { it == "auto" }?.let {
                        val groupId = it.substringBeforeLast(':', "")
                        val trackIndex = it.substringAfterLast(':').toIntOrNull()
                        exo.currentTracks.groups.firstOrNull { group -> group.type == C.TRACK_TYPE_TEXT && group.mediaTrackGroup.id == groupId }
                            ?.let { group -> trackIndex?.let { index -> subtitleMapping.embyFor(Media3TrackKey(group.mediaTrackGroup, index)) } }
                    }
                    val serverPreference = when {
                        key == null || key == "auto" -> stored
                        key.startsWith("emby:") -> stored
                        localKey != null -> "track:emby:$localKey"
                        else -> null
                    }
                    if (serverPreference != null) AppRuntime.prefs?.rememberSubtitleTrack(item.id, serverPreference, playbackSource)
                    subtitlePreference = stored
                    if (currentTarget?.direct == false && serverPreference != null) {
                        loadSource(forceTranscode = currentTarget?.playMethod == "Transcode")
                    } else if (key == "auto") {
                        loadSource(forceTranscode = false)
                    } else if (activeEngine == "mpv") {
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
                        "soft" -> if (activeEngine == "mpv") "全部音轨将在 mpv 本地软解"
                            else "使用本地软解时，请在「播放内核」中选择 mpv"
                        "passthrough" -> "已尝试音频直通（设备报告支持，实际输出以电视/功放为准）"
                        else -> "已切换到自动音频解码"
                    }
                    if (mode == "passthrough") enginePinned = "media3"
                    engineSwitchTrace.add("audio-decode-mode=$mode")
                    reloadForceTranscode = false
                    requestPlaybackReload(sessionGeneration)
                },
                onSeekStepChanged = {
                    seekStepSeconds = it
                    AppRuntime.prefs?.seekStepSeconds = it
                },
                onSwitchEngine = {
                    val next = if (activeEngine == "mpv") "media3" else "mpv"
                    if (next == "media3" || mpvAvailable) {
                        beginManualAttempt()
                        closeOptionMenu()
                        enginePinned = next
                        reloadForceTranscode = false
                        error = "正在切换播放内核…"
                        requestPlaybackReload(sessionGeneration)
                    }
                },
                onlyMedia3Available = !mpvAvailable,
                onResizeMode = { resizeMode = it },
                onSwitchSource = { sid ->
                    closeOptionMenu()
                    pendingManualAudio = null
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
                    requestPlaybackReload(sessionGeneration)
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
                            requestPlaybackReload(sessionGeneration)
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
        val payload = buildDiagPayload(outcome)
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
