package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.lumina.tv.data.MediaStream
import com.lumina.tv.diag.PageLog
import org.jellycine.mpv.MPVLib
import java.io.File
import java.util.ArrayDeque
import org.json.JSONObject
import org.json.JSONArray

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
        val audioSilentCodec: String? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val fallbackSurfaceWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    private val fallbackSurfaceHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
    private val logLock = Any()
    private val sessionLogFile = File(context.applicationContext.filesDir, "mpv-session.log")
    private val sessionStateFile = File(context.applicationContext.filesDir, "mpv-session-state.json")
    private val previousCrashFile = File(context.applicationContext.filesDir, "mpv-previous-abnormal.txt")
    private val diagnosticLines = ArrayDeque<String>()
    private val observedProperties = linkedMapOf<String, String>()
    private val heartbeat = object : Runnable {
        override fun run() {
            if (released || pendingUrl == null) return
            persistSessionState()
            handler.postDelayed(this, SESSION_HEARTBEAT_MS)
        }
    }
    private var softwareRetryCount = 0
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            appendMpvLog("${timestamp()} $prefix/$level $text")
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
    private var audioStreams: List<MediaStream> = emptyList()
    private var preferredAudioIndex: Int? = null
    private var audioSilentCodec: String? = null
    private val audioOutputCheck = Runnable { checkAudioOutput() }

    init {
        Log.i(TAG, "${timestamp()} MPVLib.create starting")
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
                    "ytdl" to "no",
                    "load-auto-profiles" to "no",
                    "terminal" to "no",
                    "msg-level" to "all=info",
                    "vo" to "gpu",
                    "gpu-context" to "android",
                    "tone-mapping" to "bt.2446a",
                    "hdr-compute-peak" to "yes",
                    "target-peak" to "auto",
                    "hwdec" to "mediacodec",
                    "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
                    "cache" to "yes",
                    "keep-open" to "no",
                    "tls-verify" to "no",
                    "sub-scale" to "1.0",
                ).forEach { (key, value) -> engine.setOptionString(key, value) }
                Log.i(TAG, "${timestamp()} MPVLib.init starting (create -> init; Surface attached later)")
                engine.init()
                engine.addObserver(this)
                engine.addLogObserver(logObserver)
                engine.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                engine.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                engine.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                engine.observeProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
                engine.observeProperty("audio-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
                engine.observeProperty("audio-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
                engine.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE)
                engine.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                engine.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                listOf("hwdec-current", "current-vo", "video-params/pixelformat")
                    .forEach { engine.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_STRING) }
                listOf("video-params/w", "video-params/h")
                    .forEach { engine.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_INT64) }
                engine.observeProperty("vo-drop-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                recordPreviousAbnormalSession()
            }.onFailure { failure ->
                unavailableReason = failure.message ?: "libmpv 初始化失败"
                Log.e(TAG, "mpv initialization failed: $unavailableReason", failure)
                runCatching { engine.destroy() }
                mpv = null
                emit(error = "该设备不支持 mpv 内核：$unavailableReason")
            }.onSuccess { Log.i(TAG, "${timestamp()} MPVLib.init succeeded") }
        }
    }

    fun attachSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        if (!released && surface.isValid) {
            val resolvedWidth = width.takeIf { it > 0 } ?: fallbackSurfaceWidth
            val resolvedHeight = height.takeIf { it > 0 } ?: fallbackSurfaceHeight
            Log.i(TAG, "${timestamp()} MPVLib.attachSurface (init already completed)")
            mpv?.attachSurface(surface)
            if (attachedSurface !== surface || resolvedWidth != lastSurfaceWidth || resolvedHeight != lastSurfaceHeight) {
                Log.i(TAG, "${timestamp()} attached surface ${resolvedWidth}x$resolvedHeight, vo=gpu")
                runCatching { mpv?.setPropertyString("android-surface-size", "${resolvedWidth}x${resolvedHeight}") }
                lastSurfaceWidth = resolvedWidth
                lastSurfaceHeight = resolvedHeight
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
            Log.i(TAG, "${timestamp()} MPVLib.detachSurface")
            mpv?.detachSurface()
            if (attachedSurface != null) Log.d(TAG, "surface detached")
            attachedSurface = null
        }
    }

    fun setSubtitleFontSize(sizePx: Int) {
        if (!released) runCatching { mpv?.setOptionString("sub-font-size", sizePx.coerceIn(16, 120).toString()) }
    }

    fun setSubtitleOffset(offsetMs: Long) {
        if (!released) runCatching { mpv?.setPropertyDouble("sub-delay", offsetMs / 1000.0) }
    }

    fun setAudioStreams(streams: List<MediaStream>) {
        audioStreams = streams.filter { it.type.equals("Audio", true) }
    }

    fun setAudioTrack(index: Int) {
        if (released) return
        preferredAudioIndex = index.takeIf { it >= 0 }
        if (fileLoaded && preferredAudioIndex != null) applyPreferredAudioTrack()
    }

    fun load(url: String, startPositionMs: Long, subtitleUrls: List<String> = emptyList(), headers: Map<String, String> = emptyMap()) {
        if (released || mpv == null) return
        clearSessionLog()
        audioSilentCodec = null
        handler.removeCallbacks(audioOutputCheck)
        pendingUrl = url
        pendingPositionMs = startPositionMs.coerceAtLeast(0L)
        ready = false
        fileLoaded = false
        positionMs = pendingPositionMs
        softwareRetryUsed = false
        softwareRetryCount = 0
        mpv?.setOptionString("hwdec", "mediacodec")
        appendMpvLog("${timestamp()} mpv hwdec reset -> mediacodec reason=new-load")
        persistSessionState()
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, SESSION_HEARTBEAT_MS)
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
            appendMpvLog("${timestamp()} mpv request headers configured names=${headers.keys.joinToString(",")}")
        } else {
            runCatching { mpv?.setOptionString("http-header-fields", "") }
            appendMpvLog("${timestamp()} mpv request headers configured names=none")
        }
        Log.i(TAG, "${timestamp()} MPVLib.load (create -> init -> attachSurface -> load)")
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

    fun containerFps(): Float? = runCatching {
        mpv?.getPropertyString("container-fps")?.toFloatOrNull()?.takeIf { it > 0f }
    }.getOrNull()
    fun diagnosticLog(): String = synchronized(logLock) {
        val previous = runCatching { previousCrashFile.takeIf(File::exists)?.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        val current = runCatching { sessionLogFile.takeIf(File::exists)?.readText(Charsets.UTF_8) }
            .getOrNull()?.ifBlank { diagnosticLines.joinToString("\n") }
            ?: ""
        listOf(previous, current).filter(String::isNotBlank).joinToString("\n\n")
    }
    fun softwareRetryCount(): Int = softwareRetryCount
    fun diagnosticProperties(): Map<String, String> {
        val properties = synchronized(observedProperties) { observedProperties.toMutableMap() }
        listOf("aid", "audio-params", "audio-out-params", "current-ao", "audio-device", "track-list")
            .forEach { key ->
                runCatching { mpv?.getPropertyString(key) }.getOrNull()?.let { properties[key] = it }
            }
        return properties
    }

    private fun applyPreferredAudioTrack() {
        val embyIndex = preferredAudioIndex ?: return
        val ordinal = audioStreams.indexOfFirst { it.index == embyIndex }.takeIf { it >= 0 }?.plus(1)
            ?: return
        val audioIds = readMpvAudioTrackIds()
        val aid = audioIds.getOrNull(ordinal - 1) ?: ordinal
        appendMpvLog("${timestamp()} mpv audio map: embyIndex=$embyIndex -> audioOrdinal=$ordinal -> aid=$aid")
        mpv?.setPropertyInt("aid", aid)
    }

    private fun readMpvAudioTrackIds(): List<Int> = runCatching<List<Int>> {
        val raw = mpv?.getPropertyString("track-list") ?: return@runCatching emptyList()
        val tracks = JSONArray(raw)
        (0 until tracks.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            if (!track.optString("type").equals("audio", true)) return@mapNotNull null
            track.optInt("id", -1).takeIf { it > 0 }
        }
    }.getOrDefault(emptyList())

    private fun checkAudioOutput() {
        if (released || !ready) return
        val properties = diagnosticProperties()
        val params = properties["audio-params"].orEmpty()
        val ao = properties["current-ao"].orEmpty()
        val abnormalAo = ao.isBlank() || ao.equals("null", true) || ao.equals("unknown", true) || ao.equals("no", true)
        if (params.isBlank() || params.equals("null", true) || params.equals("{}") || abnormalAo) {
            val codec = audioStreams.firstOrNull { it.index == preferredAudioIndex }?.codec
                ?: audioStreams.firstOrNull { it.isDefault }?.codec
                ?: audioStreams.firstOrNull()?.codec ?: "unknown"
            val aid = properties["aid"] ?: preferredAudioIndex?.toString() ?: "auto"
            val message = "mpv audio silent: aid=$aid ao=${ao.ifBlank { "empty" }} codec=$codec"
            appendMpvLog("${timestamp()} $message")
            PageLog.add(message)
            audioSilentCodec = codec
            emit()
        }
    }

    private fun clearSessionLog() {
        synchronized(logLock) {
            diagnosticLines.clear()
            runCatching { sessionLogFile.writeText("", Charsets.UTF_8) }
        }
    }

    private fun appendMpvLog(line: String) {
        synchronized(logLock) {
            if (diagnosticLines.size >= MAX_DIAGNOSTIC_LINES) diagnosticLines.removeFirst()
            diagnosticLines.addLast(line)
            runCatching {
                sessionLogFile.appendText("$line\n", Charsets.UTF_8)
                if (sessionLogFile.length() > MAX_SESSION_LOG_BYTES) {
                    val bytes = sessionLogFile.readBytes()
                    sessionLogFile.writeBytes(bytes.copyOfRange(bytes.size - MAX_SESSION_LOG_BYTES, bytes.size))
                }
            }.onFailure { Log.w(TAG, "Failed to persist mpv session log", it) }
        }
    }

    private fun recordPreviousAbnormalSession() {
        val prior = runCatching { JSONObject(sessionStateFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (!prior.optBoolean("active", false)) return
        val oldTail = runCatching { sessionLogFile.readText(Charsets.UTF_8).takeLast(6000) }.getOrDefault("")
        val summary = buildString {
            appendLine("上次 mpv 会话异常结束")
            appendLine("state=${prior}")
            appendLine("last mpv log:")
            append(oldTail)
        }
        runCatching { previousCrashFile.writeText(summary, Charsets.UTF_8) }
        appendMpvLog("${timestamp()} previous mpv session ended abnormally; hwdec=${prior.optString("hwdec")} vo=${prior.optString("vo")}")
        runCatching { sessionStateFile.writeText(JSONObject().put("active", false).toString(), Charsets.UTF_8) }
    }

    private fun persistSessionState() {
        val runtime = Runtime.getRuntime()
        val state = JSONObject()
            .put("active", true)
            .put("at", timestamp())
            .put("hwdec", synchronized(observedProperties) { observedProperties["hwdec-current"] } ?: "pending")
            .put("vo", synchronized(observedProperties) { observedProperties["current-vo"] } ?: "pending")
            .put("video", JSONObject()
                .put("pixelformat", synchronized(observedProperties) { observedProperties["video-params/pixelformat"] } ?: JSONObject.NULL)
                .put("w", synchronized(observedProperties) { observedProperties["video-params/w"] } ?: JSONObject.NULL)
                .put("h", synchronized(observedProperties) { observedProperties["video-params/h"] } ?: JSONObject.NULL))
            .put("audio", JSONObject(diagnosticProperties().filterKeys {
                it in setOf("aid", "audio-params", "audio-out-params", "current-ao", "audio-device")
            }))
            .put("memory", JSONObject()
                .put("usedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("maxBytes", runtime.maxMemory()))
            .put("lastLog", synchronized(logLock) { diagnosticLines.lastOrNull() ?: "none" })
        runCatching { sessionStateFile.writeText(state.toString(), Charsets.UTF_8) }
    }

    fun release(reason: String = "unspecified") {
        if (released) return
        released = true
        handler.removeCallbacks(loadTimeout)
        handler.removeCallbacks(heartbeat)
        runCatching { sessionStateFile.writeText(JSONObject().put("active", false).put("endedAt", timestamp()).toString(), Charsets.UTF_8) }
        mpv?.let { engine ->
            Log.i(TAG, "${timestamp()} MPVLib.destroy (release reason=$reason fileLoaded=$fileLoaded)")
            runCatching { engine.removeObserver(this) }
            runCatching { engine.removeLogObserver(logObserver) }
            if (attachedSurface != null) runCatching { engine.detachSurface() }
            attachedSurface = null
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
            appendMpvLog("${timestamp()} mpv hwdec fallback -> no reason=first-frame-timeout")
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
        if (property in MPV_DIAGNOSTIC_PROPERTIES) {
            synchronized(observedProperties) { observedProperties[property] = value }
            appendMpvLog("${timestamp()} property $property=$value")
            persistSessionState()
        }
        when (property) {
            "end-file" -> Log.w(TAG, "${timestamp()} END_FILE reason: $value")
            "video-out-params" -> {
                ready = true
                handler.removeCallbacks(loadTimeout)
                Log.i(TAG, "${timestamp()} received video-out-params")
                emit(buffering = false, ready = true)
            }
        }
    }
    override fun eventProperty(property: String, value: Long) {
        if (property == "video-params/w" || property == "video-params/h") {
            synchronized(observedProperties) { observedProperties[property] = value.toString() }
            appendMpvLog("${timestamp()} property $property=$value")
            persistSessionState()
        }
    }
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
                if (!value) buffering = false
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
                appendMpvLog("${timestamp()} file-loaded properties=${JSONObject(diagnosticProperties())}")
                applyPreferredAudioTrack()
                persistSessionState()
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, SESSION_HEARTBEAT_MS)
                pendingSubtitles.forEach { mpv?.command(arrayOf("sub-add", it, "auto")) }
                pendingSubtitles = emptyList()
                emit(buffering = false)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val firstReady = !ready
                ready = true
                handler.removeCallbacks(loadTimeout)
                if (firstReady) Log.i(TAG, "${timestamp()} received PLAYBACK_RESTART (first frame ready)")
                handler.removeCallbacks(audioOutputCheck)
                handler.postDelayed(audioOutputCheck, AUDIO_OUTPUT_CHECK_DELAY_MS)
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
        onState(State(positionMs, durationMs, buffering, ready, playing, ended, error, audioSilentCodec))
    }

    private companion object {
        const val TAG = "MpvPlayerController"
        const val LOAD_TIMEOUT_MS = 12_000L
        const val SOFTWARE_RETRY_TIMEOUT_MS = 6_000L
        const val MAX_DIAGNOSTIC_LINES = 2_000
        const val MAX_SESSION_LOG_BYTES = 512 * 1024
        const val SESSION_HEARTBEAT_MS = 10_000L
        const val AUDIO_OUTPUT_CHECK_DELAY_MS = 5_000L
        val MPV_DIAGNOSTIC_PROPERTIES = setOf(
            "hwdec-current", "current-vo", "video-params/pixelformat", "audio-params",
            "audio-out-params", "current-ao", "audio-device", "track-list",
        )
    }

    private fun timestamp(): Long = System.currentTimeMillis()
}
