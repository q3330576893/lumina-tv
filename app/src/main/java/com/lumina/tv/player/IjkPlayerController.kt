package com.lumina.tv.player

import android.content.Context
import android.os.Build
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

    // IJK 的 .so 随 abiFilters 打进 APK，但个别设备 / ABI 仍可能缺少对应 so；
    // 构造与初始化全部包进 runCatching，失败不抛出、不崩溃，改通过 onState 上报，
    // 让 PlayerScreen 走已有的 mpv → IJK → Media3 降级链。
    private val init = runCatching {
        IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L)
            setOnPreparedListener { mp ->
                surface?.let(mp::setSurface)
                if (startPositionMs > 0) mp.seekTo(startPositionMs)
                mp.start()
                emit(ready = true, buffering = false, playing = true)
            }
            setOnCompletionListener { emit(ready = true, buffering = false, playing = false, ended = true) }
            setOnInfoListener { _, what, _ ->
                when (what) {
                    IMediaPlayer.MEDIA_INFO_BUFFERING_START -> emit(buffering = true)
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> emit(buffering = false)
                }
                true
            }
            setOnErrorListener { _, what, extra ->
                emit(buffering = false, error = "IJK 播放错误 ($what/$extra)")
                true
            }
        }
    }
    private val player: IjkMediaPlayer? = init.getOrNull()

    /**
     * 非空表示内核不可用，调用方应在拿到实例后立刻降级到 Media3。
     * 这里不在构造函数里回调 onState：构造发生在 Compose 组合期，直接写状态会触发
     * "state written during composition"，改为由调用方读取后显式处理。
     */
    val unavailableReason: String? =
        if (!Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }) {
            "当前 ABI ${Build.SUPPORTED_ABIS.toList()} 未打包 IJK so"
        } else {
            init.exceptionOrNull()?.let { failure ->
                failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName
            } ?: if (player == null) "IJK 原生库初始化失败" else null
        }

    private var surface: Surface? = null
    private var startPositionMs = 0L
    private var isReleased = false
    private var state = State()

    fun attachSurface(value: Surface) {
        if (isReleased || !value.isValid || player == null) return
        surface = value
        player.setSurface(value)
    }

    fun detachSurface() {
        if (!isReleased && player != null) {
            surface = null
            player.setSurface(null)
        }
    }

    fun load(url: String, startMs: Long, headers: Map<String, String> = emptyMap()) {
        if (isReleased || player == null) return
        startPositionMs = startMs.coerceAtLeast(0)
        try {
            // attachSurface 已在 SurfaceView 回调中完成；这里再同步一次，保证 prepareAsync 前 surface 已就绪。
            surface?.let(player::setSurface)
            // 透传 Referer / User-Agent 等 headers
            player.setDataSource(url, headers)
            emit(buffering = true)
            player.prepareAsync()
        } catch (failure: Exception) {
            emit(buffering = false, error = failure.message ?: "IJK 无法加载媒体")
        }
    }

    fun play() {
        if (!isReleased && player != null) {
            player.start()
            emit(playing = true)
        }
    }

    fun pause() {
        if (!isReleased && player != null) {
            player.pause()
            emit(playing = false)
        }
    }

    fun seekTo(ms: Long) {
        if (!isReleased && player != null) player.seekTo(ms.coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) {
        if (!isReleased && player != null) player.setSpeed(speed)
    }

    fun positionMs(): Long {
        val p = player ?: return 0
        if (isReleased) return 0
        return runCatching { p.currentPosition }.getOrDefault(0L)
    }

    fun durationMs(): Long {
        val p = player ?: return 0
        if (isReleased) return 0
        return runCatching { p.duration }.getOrDefault(0L)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        runCatching { player?.setSurface(null) }
        runCatching { player?.reset() }
        runCatching { player?.release() }
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
