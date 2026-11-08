package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.jellycine.mpv.MPVLib
import java.util.ArrayDeque

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
    private val logLock = Any()
    private val diagnosticLines = ArrayDeque<String>()
    private var softwareRetryCount = 0
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            synchronized(logLock) {
                if (diagnosticLines.size >= MAX_DIAGNOSTIC_LINES) diagnosticLines.removeFirst()
                diagnosticLines.addLast("${timestamp()} $prefix/$level $text")
            }
        }
    }
    private var mpv: MPVLib? = null
    var unavailableReason: String? = null
        private set
    private var positionMs = 0L
    private var durationMs = 0L
    private var ready = false
    private var playing = false
    private var softwareRetryUsed = false
    private var released = false
    private var pendingUrl: String? = null
    private var pendingPositionMs = 0L
    private var attachedSurface: Surface? = null
    private var lastSurfaceWidth = -1
    private var lastSurfaceHeight = -1
    private var fileLoaded = false
    private var buffering = false
    private val loadTimeout = Runnable { onLoadTimeout() }

    init {
        val created = runCatching { MPVLib.create(context.applicationContext) }
        if (created.isFailure || created.getOrNull() == null) {
            unavailableReason = created.exceptionOrNull()?.message ?: "libmpv 初始化失败或当前设备 ABI 不受支持"
            Log.e(TAG, "mpv initialization failed: $unavailableReason", created.exceptionOrNull())
            emit(error = "该设备不支持 mpv 内核：$unavailableReason")
        } else {
            mpv = created.getOrNull()
            val engine = checkNotNull(mpv)
            runCatching {
                listOf(
                    "config" to "no",
                    "load-scripts" to "no",
                    "load-auto-profiles" to "no",
                    "terminal" to "no",
                    "msg-level" to "all=info",
                    "vo" to "gpu",
                    "gpu-context" to "android",
                    "force-window" to "yes",
                    "hwdec" to "auto-safe",
                    "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
                    "cache" to "yes",
                    "keep-open" to "no",
                    "tls-verify" to "no",
                    "sub-scale" to "1.0",
                ).forEach { (key, value) -> engine.setOptionString(key, value) }
                engine.init()
                engine.addObserver(this)
                engine.addLogObserver(logObserver)
                engine.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                engine.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                engine.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                engine.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                engine.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            }.onFailure { failure ->
                unavailableReason = failure.message ?: "libmpv 初始化失败"
                Log.e(TAG, "mpv initialization failed: $unavailableReason", failure)
                runCatching { engine.destroy() }
                mpv = null
                emit(error = "该设备不支持 mpv 内核：$unavailableReason")
            }.onSuccess { Log.i(TAG, "mpv initialization succeeded") }
        }
    }

    fun attachSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        if (!released && surface.isValid) {
            mpv?.attachSurface(surface)
            if (attachedSurface !== surface || (width > 0 && height > 0 &&
                    (width != lastSurfaceWidth || height != lastSurfaceHeight))) {
                Log.i(TAG, "${timestamp()} attached surface ${width}x$height, vo=gpu")
                if (width > 0 && height > 0) {
                    lastSurfaceWidth = width
                    lastSurfaceHeight = height
                }
                if (fileLoaded && pendingUrl != null) {
                    // Re-seek after late attachment to make the Android VO render the already-loaded frame.
                    mpv?.setPropertyBoolean("pause", false)
                    mpv?.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
                    Log.i(TAG, "${timestamp()} re-render requested after late surface attachment")
                }
            }
            attachedSurface = surface
        }
    }

    fun detachSurface() {
        if (!released) {
            mpv?.detachSurface()
            if (attachedSurface != null) Log.d(TAG, "surface detached")
            attachedSurface = null
        }
    }

    fun load(url: String, startPositionMs: Long, subtitleUrls: List<String> = emptyList(), headers: Map<String, String> = emptyMap()) {
        if (released || mpv == null) return
        pendingUrl = url
        pendingPositionMs = startPositionMs.coerceAtLeast(0L)
        ready = false
        fileLoaded = false
        positionMs = pendingPositionMs
        softwareRetryUsed = false
        softwareRetryCount = 0
        mpv?.setOptionString("hwdec", "auto-safe")
        val headerFields = headers.entries.mapNotNull { (key, value) ->
            if (',' in key || ',' in value) {
                Log.w(TAG, "Skipping HTTP header containing comma: $key")
                null
            } else {
                "$key: $value"
            }
        }
        if (headerFields.isNotEmpty()) {
            runCatching { mpv?.setOptionString("http-header-fields", headerFields.joinToString(",")) }
        }
        runCatching { mpv?.command(loadCommand(url, pendingPositionMs)) }.onFailure {
            emit(error = "mpv 加载失败：${it.message ?: "未知错误"}")
            return
        }
        playing = true
        mpv?.setPropertyBoolean("pause", false)
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, SOFTWARE_RETRY_TIMEOUT_MS)
        pendingSubtitles = subtitleUrls
        buffering = true
        emit(buffering = true)
    }

    private var pendingSubtitles: List<String> = emptyList()

    private fun loadCommand(url: String, startMs: Long): Array<String> = arrayOf(
        "loadfile", url, "replace", "-1", "start=${startMs / 1000.0}",
    )

    fun play() {
        if (released || mpv == null) return
        playing = true
        mpv?.setPropertyBoolean("pause", false)
        emit()
    }

    fun pause() {
        if (released || mpv == null) return
        playing = false
        mpv?.setPropertyBoolean("pause", true)
        emit()
    }

    fun seekTo(ms: Long) {
        if (released || mpv == null) return
        positionMs = ms.coerceAtLeast(0L)
        mpv?.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
        emit()
    }

    fun setSpeed(speed: Float) {
        if (!released) mpv?.setPropertyDouble("speed", speed.toDouble())
    }

    fun positionMs(): Long = positionMs
    fun durationMs(): Long = durationMs
    fun diagnosticLog(): String = synchronized(logLock) { diagnosticLines.joinToString("\n") }
    fun softwareRetryCount(): Int = softwareRetryCount

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(loadTimeout)
        mpv?.let { engine ->
            runCatching { engine.removeObserver(this) }
            runCatching { engine.removeLogObserver(logObserver) }
            runCatching { engine.detachSurface() }
            runCatching { engine.destroy() }
        }
    }

    private fun onLoadTimeout() {
        if (released || ready || mpv == null) return
        val url = pendingUrl ?: return
        handler.removeCallbacks(loadTimeout)
        if (!softwareRetryUsed) {
            softwareRetryUsed = true
            softwareRetryCount++
            mpv?.setOptionString("hwdec", "no")
            mpv?.command(loadCommand(url, pendingPositionMs))
            mpv?.setPropertyBoolean("pause", false)
            Log.w(TAG, "mpv startup retrying with software decode")
            handler.postDelayed(loadTimeout, SOFTWARE_RETRY_TIMEOUT_MS)
            emit(buffering = true)
        } else {
            Log.e(TAG, "${timestamp()} mpv did not produce first frame within ${LOAD_TIMEOUT_MS}ms")
            emit(buffering = false, error = "mpv 未能出画（12s 无首帧）")
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: String) {
        if (property == "end-file") Log.w(TAG, "${timestamp()} END_FILE reason: $value")
    }
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
            "paused-for-cache" -> {
                buffering = value
                emit(buffering = value)
            }
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
                fileLoaded = true
                buffering = false
                Log.i(TAG, "${timestamp()} received FILE_LOADED")
                pendingSubtitles.forEach { mpv?.command(arrayOf("sub-add", it, "auto")) }
                pendingSubtitles = emptyList()
                emit(buffering = false)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val firstReady = !ready
                ready = true
                handler.removeCallbacks(loadTimeout)
                if (firstReady) Log.i(TAG, "${timestamp()} received PLAYBACK_RESTART (first frame ready)")
                emit(buffering = false, ready = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                Log.w(TAG, "${timestamp()} END_FILE received (reason unavailable from observer API)")
                if (!ready) onLoadTimeout()
            }
        }
    }

    private fun emit(
        buffering: Boolean = this.buffering,
        ready: Boolean = this.ready,
        ended: Boolean = false,
        error: String? = null,
    ) {
        onState(State(positionMs, durationMs, buffering, ready, playing, ended, error))
    }

    private companion object {
        const val TAG = "MpvPlayerController"
        const val LOAD_TIMEOUT_MS = 12_000L
        const val SOFTWARE_RETRY_TIMEOUT_MS = 6_000L
        const val MAX_DIAGNOSTIC_LINES = 2_000
    }

    private fun timestamp(): Long = System.currentTimeMillis()
}
