package com.lumina.tv.ui.components

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.lumina.tv.diag.PageLog
import com.lumina.tv.player.PlayerLifecycle
import kotlinx.coroutines.CompletableDeferred

/** The preview player, including release, lives on one looper. TextureView stays on main. */
internal class DetailPreviewPlayer(
    private val loopWholeItem: Boolean,
    private val startAtMs: Long?,
    private val onSurfaceChanged: (Boolean) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onVideoSize: (Float) -> Unit,
    private val onError: (PlaybackException?) -> Unit,
) : TextureView.SurfaceTextureListener {
    private class Output(val texture: SurfaceTexture, val surface: Surface)

    companion object {
        private val worker by lazy { Handler(HandlerThread("detail-preview").apply { start() }.looper) }
        private val main by lazy { Handler(Looper.getMainLooper()) }

        // Written on main as the outgoing composition is disposed.
        private var lastRelease: CompletableDeferred<Unit>? = null

        suspend fun awaitRelease() {
            lastRelease?.await()
        }
    }

    @Volatile private var closed = false
    private var viewOutput: Output? = null // Main
    private var rendererOutput: Output? = null // Worker
    private var player: ExoPlayer? = null
    private var wantsPlayback = false
    private var loopStartMs = 0L
    private var loopEndMs = Long.MAX_VALUE
    private val loopCheck = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (closed || !current.playWhenReady || rendererOutput == null) return
            if (current.playbackState == Player.STATE_READY && current.currentPosition >= loopEndMs) {
                current.seekTo(loopStartMs)
            }
            worker.postDelayed(this, 250L)
        }
    }

    fun start(build: (Looper) -> ExoPlayer, onWorkerFirstFrame: () -> Unit) {
        worker.post {
            if (closed) return@post
            try {
                val current = build(worker.looper)
                player = current
                PlayerLifecycle.registry.created(current, "media3", "detail-preview")
                current.volume = 0f
                if (loopWholeItem) current.repeatMode = Player.REPEAT_MODE_ONE
                current.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                            deliver { onVideoSize(aspect) }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (loopWholeItem) return
                        if (state == Player.STATE_READY) {
                            val duration = current.duration.takeIf { it > 0 }
                            loopStartMs = (startAtMs ?: duration?.times(22)?.div(100) ?: 0L)
                                .coerceIn(0L, (duration ?: Long.MAX_VALUE) - 1L)
                            loopEndMs = minOf(loopStartMs + 20_000L, duration ?: Long.MAX_VALUE)
                            if (current.currentPosition !in loopStartMs until loopEndMs) current.seekTo(loopStartMs)
                        } else if (state == Player.STATE_ENDED) {
                            current.seekTo(loopStartMs)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        deliver { onError(error) }
                    }
                })
                current.addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                        val rendered = rendererOutput ?: return
                        if (closed || output !== rendered.surface) return
                        onWorkerFirstFrame()
                        deliver { if (viewOutput === rendered) onFirstFrame() }
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime, decoderName: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long,
                    ) = PlayerLifecycle.registry.decoderInitialized(current, decoderName)

                    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) =
                        PlayerLifecycle.registry.decoderReleased(current, decoderName)
                })
                rendererOutput?.let { current.setVideoSurface(it.surface) }
                updatePlayback()
            } catch (failure: Exception) {
                PageLog.add("detailPreview player-init error=${failure.javaClass.simpleName}")
                deliver { onError(null) }
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        if (closed) return
        worker.post {
            if (closed) return@post
            wantsPlayback = playing
            updatePlayback()
        }
    }

    private fun updatePlayback() {
        val current = player ?: return
        val playing = !closed && wantsPlayback && rendererOutput != null
        current.playWhenReady = playing
        if (playing && current.playbackState == Player.STATE_IDLE) current.prepare()
        worker.removeCallbacks(loopCheck)
        if (playing && !loopWholeItem) worker.postDelayed(loopCheck, 250L)
    }

    fun close() {
        if (closed) return
        closed = true
        val released = CompletableDeferred<Unit>()
        lastRelease = released
        worker.post {
            try {
                worker.removeCallbacks(loopCheck)
                player?.let { oldPlayer ->
                    try {
                        oldPlayer.release()
                    } finally {
                        PlayerLifecycle.registry.released(oldPlayer, "detail-preview-disposed")
                    }
                }
            } catch (failure: Exception) {
                PageLog.add("detailPreview player-release error=${failure.javaClass.simpleName}")
            } finally {
                player = null
                rendererOutput = null
                released.complete(Unit)
            }
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed || viewOutput?.texture === texture) return
        val next = Output(texture, Surface(texture))
        viewOutput = next
        onSurfaceChanged(true)
        worker.post {
            if (closed) return@post
            rendererOutput = next
            player?.setVideoSurface(next.surface)
            updatePlayback()
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val retiring = viewOutput?.takeIf { it.texture === texture } ?: return true
        viewOutput = null
        if (!closed) onSurfaceChanged(false)
        worker.post {
            try {
                if (rendererOutput === retiring) {
                    rendererOutput = null
                    player?.clearVideoSurface()
                    updatePlayback()
                }
            } finally {
                retiring.surface.release()
                retiring.texture.release()
            }
        }
        return false // Keep the texture alive until the player has detached from it.
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun deliver(action: () -> Unit) {
        main.post { if (!closed) action() }
    }
}
