package com.lumina.tv.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lumina.tv.data.PreviewVideoCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Optional visual layer; callers keep their static artwork behind it as the fallback. */
@Composable
fun DetailVideoBackground(
    url: String?,
    mediaKey: String,
    playing: Boolean,
    onStatus: (String) -> Unit,
    startAtMs: Long? = null,
    loopWholeItem: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var probeResult by remember(url, mediaKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(url, mediaKey) {
        probeResult = null
        if (url.isNullOrBlank()) {
            probeResult = "skip=url"
        } else {
            val scheme = Uri.parse(url).scheme?.lowercase()
            probeResult = when {
                scheme == "file" || scheme == "content" -> "valid"
                scheme == "http" || scheme == "https" -> probeVideo(url)
                else -> "skip=url"
            }
        }
        if (probeResult != "valid") onStatus(probeResult ?: "skip=err")
    }

    if (probeResult == "valid" && !url.isNullOrBlank()) {
        DetailVideoPlayback(
            url = url,
            mediaKey = mediaKey,
            playing = playing,
            onStatus = onStatus,
            startAtMs = startAtMs,
            loopWholeItem = loopWholeItem,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailVideoPlayback(
    url: String,
    mediaKey: String,
    playing: Boolean,
    onStatus: (String) -> Unit,
    startAtMs: Long?,
    loopWholeItem: Boolean,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var firstFrame by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var loopStartMs by remember(url) { mutableStateOf(0L) }
    var loopEndMs by remember(url) { mutableStateOf(0L) }
    val player = remember(url, mediaKey) {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent("LuminaTV/1.5 (Android)")
        val cacheKey = mediaKey.split(':').takeLast(2).let { parts ->
            if (parts.size == 2) "bg:${parts[0]}:${parts[1]}" else "bg:$mediaKey"
        }
        val cacheFactory = CacheDataSource.Factory()
            .setCache(PreviewVideoCache.get(context))
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheKeyFactory { dataSpec -> dataSpec.key ?: dataSpec.uri.toString() }
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(cacheKey)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(cacheFactory))
            .build()
            .apply {
                volume = 0f
                setMediaItem(mediaItem)
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
                onStatus("play")
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
                failed = true
                onStatus("skip=err")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }

    LaunchedEffect(player, playing, failed) {
        if (playing && !failed) player.play() else player.pause()
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
        Box(
            modifier = modifier.fillMaxSize().clipToBounds().graphicsLayer {
                alpha = if (firstFrame && playing) 1f else 0f
            },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
            )
        }
    }
}

private suspend fun probeVideo(url: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-511")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext "skip=err"
            if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                return@withContext "skip=html"
            }
            val source = response.body?.source() ?: return@withContext "skip=err"
            val buffer = Buffer()
            while (buffer.size < PROBE_BYTES) {
                val count = source.read(buffer, PROBE_BYTES - buffer.size)
                if (count == -1L) break
            }
            val bytes = buffer.readByteArray()
            val prefix = bytes.toString(Charsets.ISO_8859_1)
                .removePrefix("\uFEFF")
                .trimStart()
                .lowercase()
            val html = prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
                prefix.startsWith("<a href")
            if (html) return@withContext "skip=html"
            val hls = prefix.startsWith("#extm3u")
            val mp4 = bytes.indices.any { index ->
                index + 4 <= bytes.size && bytes[index] == 'f'.code.toByte() &&
                    bytes[index + 1] == 't'.code.toByte() && bytes[index + 2] == 'y'.code.toByte() &&
                    bytes[index + 3] == 'p'.code.toByte()
            }
            val mkv = bytes.indices.any { index ->
                index + 4 <= bytes.size && bytes[index] == 0x1A.toByte() &&
                    bytes[index + 1] == 0x45.toByte() && bytes[index + 2] == 0xDF.toByte() &&
                    bytes[index + 3] == 0xA3.toByte()
            }
            if (hls || mp4 || mkv) "valid" else "skip=err"
        }
    }.getOrDefault("skip=err")
}

private const val LOOP_WINDOW_MS = 20_000L
private const val PROBE_TIMEOUT_SECONDS = 3L
private const val PROBE_BYTES = 512L
