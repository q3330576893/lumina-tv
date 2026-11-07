package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.jellycine.mpv.MPVLib

/** Small Android Surface-backed mpv bridge. Hardware decode is attempted first, then retried in software. */
class MpvPlayerController(
    context: Context,
    private val onState: (State) -> Unit,
) : MPVLib.EventObserver {
    data class State(
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val buffering: Boolean = false,
        val ready: Boolean = false,
        val playing: Boolean = false,
        val ended: Boolean = false,
        val error: String? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val mpv = requireNotNull(MPVLib.create(context.applicationContext)) { "libmpv 初始化失败" }
    private var positionMs = 0L
    private var durationMs = 0L
    private var ready = false
    private var playing = false
    private var softwareRetryUsed = false
    private var released = false
    private var pendingUrl: String? = null
    private var pendingPositionMs = 0L
    private val loadTimeout = Runnable { onLoadTimeout() }

    init {
        listOf(
            "config" to "no",
            "load-scripts" to "no",
            "load-auto-profiles" to "no",
            "terminal" to "no",
            "msg-level" to "all=no",
            "vo" to "gpu",
            "gpu-context" to "android",
            "hwdec" to "auto-safe",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
            "cache" to "yes",
            "keep-open" to "no",
            "tls-verify" to "no",
            "sub-scale" to "1.0",
        ).forEach { (key, value) -> mpv.setOptionString(key, value) }
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    fun attachSurface(surface: Surface) {
        if (!released && surface.isValid) mpv.attachSurface(surface)
    }

    fun detachSurface() {
        if (!released) mpv.detachSurface()
    }

    fun load(url: String, startPositionMs: Long, subtitleUrls: List<String> = emptyList()) {
        if (released) return
        pendingUrl = url
        pendingPositionMs = startPositionMs.coerceAtLeast(0L)
        ready = false
        positionMs = pendingPositionMs
        softwareRetryUsed = false
        mpv.setOptionString("hwdec", "auto-safe")
        mpv.command(loadCommand(url, pendingPositionMs))
        playing = true
        mpv.setPropertyBoolean("pause", false)
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        pendingSubtitles = subtitleUrls
        emit(buffering = true)
    }

    private var pendingSubtitles: List<String> = emptyList()

    private fun loadCommand(url: String, startMs: Long): Array<String> = arrayOf(
        "loadfile", url, "replace", "-1", "start=${startMs / 1000.0}",
    )

    fun play() {
        if (released) return
        playing = true
        mpv.setPropertyBoolean("pause", false)
        emit()
    }

    fun pause() {
        if (released) return
        playing = false
        mpv.setPropertyBoolean("pause", true)
        emit()
    }

    fun seekTo(ms: Long) {
        if (released) return
        positionMs = ms.coerceAtLeast(0L)
        mpv.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
        emit()
    }

    fun setSpeed(speed: Float) {
        if (!released) mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun positionMs(): Long = positionMs
    fun durationMs(): Long = durationMs

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(loadTimeout)
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    private fun onLoadTimeout() {
        if (released || ready) return
        val url = pendingUrl ?: return
        if (!softwareRetryUsed) {
            softwareRetryUsed = true
            mpv.setOptionString("hwdec", "no")
            mpv.command(loadCommand(url, pendingPositionMs))
            mpv.setPropertyBoolean("pause", false)
            handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
            emit(buffering = true)
        } else {
            emit(error = "mpv 硬件/软件解码均未能启动")
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> positionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "duration" -> durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
        }
        emit()
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "paused-for-cache" -> emit(buffering = value)
            "eof-reached" -> if (value) {
                playing = false
                emit(ended = true)
            }
            "pause" -> {
                playing = !value
                emit()
            }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                pendingSubtitles.forEach { mpv.command(arrayOf("sub-add", it, "auto")) }
                pendingSubtitles = emptyList()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                ready = true
                handler.removeCallbacks(loadTimeout)
                emit(buffering = false, ready = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (!ready && !softwareRetryUsed) onLoadTimeout()
            }
        }
    }

    private fun emit(
        buffering: Boolean = false,
        ready: Boolean = this.ready,
        ended: Boolean = false,
        error: String? = null,
    ) {
        onState(State(positionMs, durationMs, buffering, ready, playing, ended, error))
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 12_000L
    }
}
