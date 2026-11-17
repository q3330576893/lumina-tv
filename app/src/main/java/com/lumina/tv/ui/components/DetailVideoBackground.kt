package com.lumina.tv.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lumina.tv.data.PreviewVideoCache
import com.lumina.tv.data.PreviewCacheRecovery
import com.lumina.tv.data.previewResourceKey
import com.lumina.tv.diag.PageLog
import com.lumina.tv.player.PlayerLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Optional visual layer; callers keep their static artwork behind it as the fallback. */
@Composable
fun DetailVideoBackground(
    url: String?,
    mediaKey: String,
    previewStage: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    requestClient: OkHttpClient? = null,
    playing: Boolean,
    onStatus: (String) -> Unit,
    startAtMs: Long? = null,
    loopWholeItem: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var probeResult by remember(url, mediaKey) { mutableStateOf<String?>(null) }
    var probeAllowsCache by remember(url, mediaKey) { mutableStateOf(false) }
    var resourceKey by remember(url, mediaKey) { mutableStateOf(mediaKey) }

    LaunchedEffect(url, mediaKey) {
        probeResult = null
        probeAllowsCache = false
        if (url.isNullOrBlank()) {
            probeResult = "skip=no-preview-url"
            onStatus("skip=no-preview-url")
            return@LaunchedEffect
        }

        delay(1_500)
        val scheme = Uri.parse(url).scheme?.lowercase()
        probeResult = when {
            scheme == "file" || scheme == "content" -> "valid"
            scheme == "http" || scheme == "https" -> VideoSourceProbe.probe(url, mediaKey, requestHeaders, requestClient).let {
                probeAllowsCache = it.valid == true &&
                    it.contentType.orEmpty().let { type -> type.startsWith("video/", true) || type.startsWith("application/octet-stream", true) } &&
                    previewStage != "hls" && !it.prefix.trimStart().startsWith("#EXTM3U", true) && !url.contains(".m3u8", true)
                resourceKey = previewResourceKey(mediaKey, it.etag, it.lastModified, it.length)
                if (it.valid == true) "valid" else if (it.reason == "html") "skip=html" else "skip=err"
            }
            else -> "skip=url"
        }
        if (probeResult != "valid") onStatus("skip=playback-failed")
    }

    if (probeResult == "valid" && !url.isNullOrBlank()) {
        key(url, resourceKey) {
            DetailVideoPlayback(
                url = url,
                mediaKey = resourceKey,
                requestHeaders = requestHeaders,
                requestClient = requestClient,
                cacheAllowed = probeAllowsCache,
                stage = previewStage ?: when {
                    url.contains(".m3u8", true) -> "hls"
                    Uri.parse(url).getQueryParameter("static") == "true" -> "static"
                    else -> "direct"
                },
                playing = playing,
                onStatus = onStatus,
                startAtMs = startAtMs,
                loopWholeItem = loopWholeItem,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DetailVideoPlayback(
    url: String,
    mediaKey: String,
    requestHeaders: Map<String, String>,
    requestClient: OkHttpClient?,
    cacheAllowed: Boolean,
    stage: String,
    playing: Boolean,
    onStatus: (String) -> Unit,
    startAtMs: Long?,
    loopWholeItem: Boolean,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var firstFrame by remember(url, mediaKey) { mutableStateOf(false) }
    var failed by remember(url, mediaKey) { mutableStateOf(false) }
    val recovery = remember(url, mediaKey) { PreviewCacheRecovery() }
    var bypassCache by remember(url, mediaKey) { mutableStateOf(false) }
    val cachedBytesRead = remember(url, mediaKey, bypassCache) { java.util.concurrent.atomic.AtomicLong(0L) }
    var loopStartMs by remember(url) { mutableStateOf(0L) }
    var loopEndMs by remember(url) { mutableStateOf(0L) }
    var staticBytesLoaded by remember(url) { mutableStateOf(0L) }
    var staticBitrateEstimate by remember(url) { mutableStateOf(0L) }
    val isStaticFallback = remember(url) { Uri.parse(url).getQueryParameter("static") == "true" }
    val playerState = remember { mutableStateOf<ExoPlayer?>(null) }
    fun releasePlayer(reason: String) {
        val oldPlayer = playerState.value ?: return
        playerState.value = null
        oldPlayer.clearVideoSurface()
        oldPlayer.release()
        PlayerLifecycle.registry.released(oldPlayer, reason)
    }
    // Install ownership even before the first LaunchedEffect creates a player. Navigation can
    // remove the preview between creation and the following recomposition.
    DisposableEffect(playerState) {
        onDispose { releasePlayer("detail-preview-disposed") }
    }
    LaunchedEffect(url, mediaKey, bypassCache) {
        releasePlayer("detail-preview-replaced")
        firstFrame = false
        if (recovery.shouldRemove(freshFrame = false)) PreviewVideoCache.remove(context, mediaKey)
        val httpClient = requestClient ?: OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(PREVIEW_USER_AGENT)
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = if (cacheAllowed && !bypassCache) {
            CacheDataSource.Factory()
                .setCache(PreviewVideoCache.get(context))
                .setUpstreamDataSourceFactory(httpFactory)
                .setCacheKeyFactory { mediaKey }
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytes: Long) { cachedBytesRead.addAndGet(cachedBytes) }
                    override fun onCacheIgnored(reason: Int) = Unit
                })
        } else httpFactory
        val builder = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (cacheAllowed && !bypassCache) builder.setCustomCacheKey(mediaKey)
        val mediaItem = builder.build()
        val nextPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .apply {
                PlayerLifecycle.registry.created(this, "media3", "detail-preview")
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime, decoderName: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long,
                    ) { PlayerLifecycle.registry.decoderInitialized(this@apply, decoderName) }
                    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                        PlayerLifecycle.registry.decoderReleased(this@apply, decoderName)
                    }
                })
                volume = 0f
                setMediaItem(mediaItem)
            }
        playerState.value = nextPlayer
    }

    val player = playerState.value ?: return
    var videoAspectRatio by remember(player) { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
                if (recovery.shouldRemove(freshFrame = true)) PreviewVideoCache.remove(context, mediaKey)
                onStatus("play")
                if (isStaticFallback) {
                    PageLog.add("detailPreview stage=static-startup bitrate=${staticBitrateEstimate}bps bytes=${staticBytesLoaded}")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && loopWholeItem) {
                    player.seekTo(0L)
                    return
                }
                if (playbackState == Player.STATE_READY) {
                    val duration = player.duration.takeIf { it > 0L }
                    loopStartMs = if (loopWholeItem) 0L else
                        (startAtMs ?: duration?.times(22)?.div(100) ?: 0L)
                            .coerceAtMost((duration ?: Long.MAX_VALUE).coerceAtLeast(1L) - 1L)
                    loopEndMs = if (loopWholeItem) duration ?: Long.MAX_VALUE else
                        minOf(loopStartMs + LOOP_WINDOW_MS, duration ?: Long.MAX_VALUE)
                    if (player.currentPosition < loopStartMs || player.currentPosition >= loopEndMs) {
                        player.seekTo(loopStartMs)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cacheReadFailure = !bypassCache && generateSequence<Throwable>(error) { it.cause }.any {
                    it is Cache.CacheException || it is FileDataSource.FileDataSourceException
                }
                val malformedCachedInput = cachedBytesRead.get() > 0L && error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
                if (cacheAllowed && recovery.retry(cacheReadFailure, malformedCachedInput)) {
                    bypassCache = true
                    PageLog.add("detailPreview stage=$stage cache-retry=upstream-once")
                    return
                }
                failed = true
                val cause = (error.cause?.toString() ?: error.message.orEmpty()).lineSequence().firstOrNull().orEmpty().take(120)
                PageLog.add("detailPreview stage=$stage error=${error.errorCodeName} cause=$cause")
                onStatus("skip=playback-failed cause=$cause")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    DisposableEffect(player, isStaticFallback) {
        val listener = object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                if (isStaticFallback) {
                    staticBytesLoaded = totalBytesLoaded
                    staticBitrateEstimate = bitrateEstimate
                }
            }
        }
        player.addAnalyticsListener(listener)
        onDispose { player.removeAnalyticsListener(listener) }
    }

    LaunchedEffect(player, playing, failed) {
        if (playing && !failed) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        } else player.pause()
    }

    LaunchedEffect(player, playing, firstFrame, failed) {
        if (stage != "hls" || !playing || firstFrame || failed) return@LaunchedEffect
        delay(HLS_FIRST_FRAME_TIMEOUT_MS)
        if (!firstFrame && !failed) {
            failed = true
            PageLog.add("detailPreview stage=hls error=STARTUP_TIMEOUT cause=No first frame within ${HLS_FIRST_FRAME_TIMEOUT_MS}ms")
            onStatus("skip=playback-failed")
        }
    }

    LaunchedEffect(player, playing, failed) {
        if (failed) return@LaunchedEffect
        if (!firstFrame) onStatus("prep")
        while (true) {
            delay(250)
            if (playing && player.playbackState == Player.STATE_READY && player.currentPosition >= loopEndMs) {
                player.seekTo(loopStartMs)
            }
        }
    }

    if (!failed) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize().clipToBounds().graphicsLayer {
                alpha = if (firstFrame && playing) 1f else 0f
            },
            contentAlignment = Alignment.Center,
        ) {
            val containerAspectRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else videoAspectRatio
            val videoModifier = if (videoAspectRatio > 0f && videoAspectRatio > containerAspectRatio) {
                Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
            } else if (videoAspectRatio > 0f) {
                Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
            } else {
                Modifier.fillMaxSize()
            }
            AndroidView(
                modifier = videoModifier,
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                player.setVideoTextureView(this@apply)
                                if (playing) player.play()
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                                player.clearVideoTextureView(this@apply)
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                        }
                    }
                },
                update = { view ->
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    if (view.isAvailable) player.setVideoTextureView(view)
                },
                onRelease = { view ->
                    view.surfaceTextureListener = null
                    if (playerState.value === player) player.clearVideoTextureView(view)
                },
            )
        }
    }
}

data class VideoSourceProbeResult(
    val valid: Boolean?,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val prefix: String = "",
    val reason: String = "unknown",
    val etag: String? = null,
    val lastModified: String? = null,
    val length: String? = null,
)

object VideoSourceProbe {
    private data class Cached(val result: VideoSourceProbeResult, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun shouldProbe(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".m3u8") || !VIDEO_EXTENSION.matches(path)
    }

    suspend fun probe(url: String, mediaKey: String, headers: Map<String, String> = emptyMap(), requestClient: OkHttpClient? = null): VideoSourceProbeResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val key = "$mediaKey|$url"
            cache[key]?.takeIf { now - it.atMs < CACHE_TTL_MS }?.let { return@withContext it.result }
            val result = runCatching {
                val builder = Request.Builder().url(url)
                    .header("User-Agent", PREVIEW_USER_AGENT)
                if (!url.contains(".m3u8", true)) builder.header("Range", "bytes=0-511")
                headers.forEach { (name, value) -> builder.header(name, value) }
                (requestClient ?: client).newCall(builder.build()).apply {
                    timeout().timeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                    val source = response.body?.source()
                    val buffer = Buffer()
                    if (source != null) {
                        while (buffer.size < PROBE_BYTES) {
                            val count = source.read(buffer, PROBE_BYTES - buffer.size)
                            if (count == -1L) break
                        }
                    }
                    val bytes = buffer.readByteArray()
                    val snippet = bytes.toString(Charsets.ISO_8859_1).take(60)
                    val prefix = bytes.toString(Charsets.ISO_8859_1).removePrefix("\uFEFF").trimStart().lowercase()
                    val htmlOrJsonType = contentType.contains("text/html", true) ||
                        contentType.contains("application/json", true)
                    val textInvalid = prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
                        prefix.startsWith("<a href") || prefix.startsWith("{")
                    val hls = prefix.startsWith("#extm3u")
                    val mp4 = bytes.indices.any { i -> i + 4 <= bytes.size &&
                        bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                        bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte() }
                    val mkv = bytes.indices.any { i -> i + 4 <= bytes.size &&
                        bytes[i] == 0x1A.toByte() && bytes[i + 1] == 0x45.toByte() &&
                        bytes[i + 2] == 0xDF.toByte() && bytes[i + 3] == 0xA3.toByte() }
                    val videoContentType = contentType.startsWith("video/", true) ||
                        contentType.startsWith("application/octet-stream", true)
                    val valid = response.isSuccessful && !htmlOrJsonType && !textInvalid && (hls || mp4 || mkv || videoContentType)
                    VideoSourceProbeResult(
                        valid = valid,
                        statusCode = response.code,
                        contentType = contentType,
                        prefix = snippet,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        length = response.header("Content-Range")?.substringAfterLast('/') ?: response.header("Content-Length"),
                        reason = when {
                            !response.isSuccessful -> "http"
                            htmlOrJsonType || textInvalid -> "html"
                            valid -> "valid"
                            else -> "format"
                        },
                    )
                }
            }.getOrElse { VideoSourceProbeResult(valid = null, reason = "unknown") }
            // Successful entries revalidate their HTTP content version on every page entry (512 bytes).
            // Only failed probes use the short, identity-scoped negative cache.
            if (result.valid == false) cache[key] = Cached(result, now)
            cache.entries.removeAll { now - it.value.atMs >= CACHE_TTL_MS }
            result
        }

    private val VIDEO_EXTENSION = Regex(".*\\.(mkv|mp4|ts)$", RegexOption.IGNORE_CASE)
}

private const val LOOP_WINDOW_MS = 20_000L
private const val PROBE_TIMEOUT_SECONDS = 3L
private const val PROBE_BYTES = 512L
private const val CACHE_TTL_MS = 120_000L
private const val HLS_FIRST_FRAME_TIMEOUT_MS = 2_000L
private const val PREVIEW_USER_AGENT = "LuminaTV/1.5 (Android)"
