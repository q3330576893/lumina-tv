package com.lumina.tv.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lumina.tv.data.PreviewVideoCache
import com.lumina.tv.data.PreviewCacheRecovery
import com.lumina.tv.data.previewResourceKey
import com.lumina.tv.data.catchingRequest
import com.lumina.tv.data.readCancellable
import com.lumina.tv.data.withRequestBudget
import com.lumina.tv.diag.PageLog
import com.lumina.tv.player.detailPreviewShouldPlay
import kotlinx.coroutines.delay
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

        delay(300)
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
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val hostLifecycleOwner = LocalLifecycleOwner.current
    var hostResumed by remember(hostLifecycleOwner) {
        mutableStateOf(hostLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val previewShouldPlay = detailPreviewShouldPlay(playing, hostResumed)
    val playingRequested by rememberUpdatedState(playing)
    val publishStatus by rememberUpdatedState(onStatus)
    val recovery = remember(url, mediaKey) { PreviewCacheRecovery() }
    var bypassCache by remember(url, mediaKey) { mutableStateOf(false) }

    // A retry owns a new texture and first-frame state along with its player.
    key(url, mediaKey, bypassCache, startAtMs, loopWholeItem) {
        var firstFrame by remember { mutableStateOf(false) }
        var surfaceAttached by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var videoAspectRatio by remember { mutableFloatStateOf(0f) }
        val cachedBytesRead = remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val controller = remember {
            DetailPreviewPlayer(
                loopWholeItem, startAtMs,
                onSurfaceChanged = { attached ->
                    surfaceAttached = attached
                    firstFrame = false
                    if (attached) publishStatus("prep") else publishStatus("paused")
                },
                onFirstFrame = { firstFrame = true },
                onVideoSize = { videoAspectRatio = it },
                onError = { error ->
                    val cacheReadFailure = error != null && generateSequence<Throwable>(error) { it.cause }.any {
                        it is Cache.CacheException || it is FileDataSource.FileDataSourceException
                    }
                    val malformedCachedInput = cachedBytesRead.get() > 0L && error?.errorCode in setOf(
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                    )
                    if (cacheAllowed && synchronized(recovery) { recovery.retry(cacheReadFailure, malformedCachedInput) }) {
                        bypassCache = true
                        publishStatus("prep")
                        PageLog.add("detailPreview stage=$stage cache-retry=upstream-once")
                    } else {
                        failed = true
                        val cause = (error?.cause?.toString() ?: error?.message.orEmpty())
                            .lineSequence().firstOrNull().orEmpty().take(120)
                        PageLog.add("detailPreview stage=$stage error=${error?.errorCodeName} cause=$cause")
                        publishStatus("skip=playback-failed cause=$cause")
                    }
                },
            )
        }
        val usingCache = cacheAllowed && !bypassCache
        DisposableEffect(controller) {
            controller.start(
                build = { looper ->
                    if (!usingCache && synchronized(recovery) { recovery.shouldRemove(freshFrame = false) }) {
                        PreviewVideoCache.remove(context, mediaKey)
                    }
                    val httpClient = requestClient ?: OkHttpClient.Builder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                    val httpFactory = OkHttpDataSource.Factory(httpClient)
                        .setUserAgent(PREVIEW_USER_AGENT)
                        .setDefaultRequestProperties(requestHeaders)
                    val upstream = DefaultDataSource.Factory(context, httpFactory)
                    val dataSourceFactory = if (usingCache) {
                        CacheDataSource.Factory()
                            .setCache(PreviewVideoCache.get(context))
                            .setUpstreamDataSourceFactory(upstream)
                            .setCacheKeyFactory { mediaKey }
                            .setEventListener(object : CacheDataSource.EventListener {
                                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytes: Long) {
                                    cachedBytesRead.addAndGet(cachedBytes)
                                }
                                override fun onCacheIgnored(reason: Int) = Unit
                            })
                    } else upstream
                    val media = MediaItem.Builder().setUri(url)
                    if (stage == "hls") media.setMimeType(MimeTypes.APPLICATION_M3U8)
                    if (usingCache) media.setCustomCacheKey(mediaKey)
                    ExoPlayer.Builder(context)
                        .setLooper(looper)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
                        .build().apply {
                            setAudioAttributes(AudioAttributes.DEFAULT, false)
                            trackSelectionParameters = trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build()
                            setMediaItem(media.build())
                        }
                },
                onWorkerFirstFrame = {
                    if (!usingCache && synchronized(recovery) { recovery.shouldRemove(freshFrame = true) }) {
                        PreviewVideoCache.remove(context, mediaKey)
                    }
                },
            )
            onDispose { controller.close() }
        }

        DisposableEffect(hostLifecycleOwner, controller) {
            val observer = LifecycleEventObserver { _, _ ->
                hostResumed = hostLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                controller.setPlaying(!failed && detailPreviewShouldPlay(playingRequested, hostResumed))
            }
            hostLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { hostLifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(controller, previewShouldPlay, failed, firstFrame) {
            controller.setPlaying(previewShouldPlay && !failed)
            publishStatus(if (previewShouldPlay && firstFrame && !failed) "play" else "paused")
        }

        LaunchedEffect(controller, previewShouldPlay, surfaceAttached, firstFrame, failed) {
            if (stage != "hls" || !previewShouldPlay || !surfaceAttached || firstFrame || failed) return@LaunchedEffect
            delay(HLS_FIRST_FRAME_TIMEOUT_MS)
            failed = true
            PageLog.add("detailPreview stage=hls error=STARTUP_TIMEOUT cause=No first frame within ${HLS_FIRST_FRAME_TIMEOUT_MS}ms")
            publishStatus("skip=playback-failed")
        }

        if (!failed) {
            BoxWithConstraints(
                modifier = modifier.fillMaxSize().clipToBounds().graphicsLayer {
                    alpha = if (firstFrame && previewShouldPlay) 1f else 0f
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
                            surfaceTextureListener = controller
                        }
                    },
                )
            }
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

    suspend fun probe(url: String, mediaKey: String, headers: Map<String, String> = emptyMap(), requestClient: OkHttpClient? = null): VideoSourceProbeResult {
            val now = System.currentTimeMillis()
            val key = "$mediaKey|$url"
            cache[key]?.takeIf { now - it.atMs < CACHE_TTL_MS }?.let { return it.result }
            val result = catchingRequest {
                val builder = Request.Builder().url(url)
                    .header("User-Agent", PREVIEW_USER_AGENT)
                if (!url.contains(".m3u8", true)) builder.header("Range", "bytes=0-511")
                headers.forEach { (name, value) -> builder.header(name, value) }
                withRequestBudget(PROBE_TIMEOUT_SECONDS * 1000L) {
                    (requestClient ?: client).readCancellable(builder.build()) { response ->
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
                }
            }.getOrElse { VideoSourceProbeResult(valid = null, reason = "unknown") }
            // Successful entries revalidate their HTTP content version on every page entry (512 bytes).
            // Only failed probes use the short, identity-scoped negative cache.
            if (result.valid == false) cache[key] = Cached(result, now)
            cache.entries.removeAll { now - it.value.atMs >= CACHE_TTL_MS }
            return result
    }

    private val VIDEO_EXTENSION = Regex(".*\\.(mkv|mp4|ts)$", RegexOption.IGNORE_CASE)
}

private const val PROBE_TIMEOUT_SECONDS = 3L
private const val PROBE_BYTES = 512L
private const val CACHE_TTL_MS = 120_000L
private const val HLS_FIRST_FRAME_TIMEOUT_MS = 5_000L
private const val PREVIEW_USER_AGENT = "LuminaTV/1.5 (Android)"
