package com.lumina.tv.player

import android.content.Context
import android.view.Surface
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

class IjkPlayerController(
    context: Context,
    private val onState: (State) -> Unit,
) {
    data class State(
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val buffering: Boolean = false,
        val ready: Boolean = false,
        val playing: Boolean = false,
        val ended: Boolean = false,
        val error: String? = null,
    )

    private val player = IjkMediaPlayer()
    private var surface: Surface? = null
    private var startPositionMs = 0L
    private var isReleased = false
    private var state = State()

    init {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L)
        player.setOnPreparedListener { mp ->
            surface?.let(mp::setSurface)
            if (startPositionMs > 0) mp.seekTo(startPositionMs)
            mp.start()
            emit(ready = true, buffering = false, playing = true)
        }
        player.setOnCompletionListener { emit(ready = true, buffering = false, playing = false, ended = true) }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> emit(buffering = true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> emit(buffering = false)
            }
            true
        }
        player.setOnErrorListener { _, what, extra ->
            emit(buffering = false, error = "IJK 播放错误 ($what/$extra)")
            true
        }
    }

    fun attachSurface(value: Surface) {
        if (isReleased || !value.isValid) return
        surface = value
        player.setSurface(value)
    }

    fun detachSurface() {
        if (!isReleased) {
            surface = null
            player.setSurface(null)
        }
    }

    fun load(url: String, startMs: Long, headers: Map<String, String> = emptyMap()) {
        if (isReleased) return
        startPositionMs = startMs.coerceAtLeast(0)
        try {
            surface?.let(player::setSurface)
            player.setDataSource(url, headers)
            emit(buffering = true)
            player.prepareAsync()
        } catch (failure: Exception) {
            emit(buffering = false, error = failure.message ?: "IJK 无法加载媒体")
        }
    }

    fun play() { if (!isReleased) { player.start(); emit(playing = true) } }
    fun pause() { if (!isReleased) { player.pause(); emit(playing = false) } }
    fun seekTo(ms: Long) { if (!isReleased) player.seekTo(ms.coerceAtLeast(0)) }
    fun setSpeed(speed: Float) { if (!isReleased) player.setSpeed(speed) }
    fun positionMs(): Long = if (isReleased) 0 else runCatching { player.currentPosition }.getOrDefault(0L)
    fun durationMs(): Long = if (isReleased) 0 else runCatching { player.duration }.getOrDefault(0L)

    fun release() {
        if (isReleased) return
        isReleased = true
        runCatching { player.setSurface(null) }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun emit(
        buffering: Boolean? = null,
        ready: Boolean? = null,
        playing: Boolean? = null,
        ended: Boolean? = null,
        error: String? = null,
    ) {
        state = state.copy(
            positionMs = positionMs(),
            durationMs = durationMs(),
            buffering = buffering ?: state.buffering,
            ready = ready ?: state.ready,
            playing = playing ?: state.playing,
            ended = ended ?: state.ended,
            error = error,
        )
        onState(state)
    }
}
