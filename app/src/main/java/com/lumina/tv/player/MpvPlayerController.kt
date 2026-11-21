package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import android.util.Log
import android.view.Surface
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.nativePlaybackHeaders
import com.lumina.tv.diag.PageLog
import org.jellycine.mpv.MPVLib
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray

internal fun shouldSyncMpvAudioTrack(
    ready: Boolean,
    becameReady: Boolean,
    trackListChanged: Boolean,
    hasPendingManualAudio: Boolean,
): Boolean = ready && hasPendingManualAudio && (becameReady || trackListChanged)

internal data class MpvHttpHeadersResult(val mode: String, val accepted: Int, val total: Int) {
    val diagnostic: String get() = "mpv headers mode=$mode accepted=$accepted/$total"
    val complete: Boolean get() = accepted == total
    val notice: String? get() = if (complete) null else
        "mpv 缺少 ${total - accepted}/$total 个请求头，需要 Referer 或鉴权头的片源可能无法播放"
}

/** A physical load has a fresh mpv instance, hence an initially empty header list.
 * -append accepts one literal element (including commas and backslashes). The
 * pinned binding cannot read a structured string-list, so do not fall back to a
 * void string setter + lossy print_str_list readback and claim it was verified. */
internal fun applyMpvHttpHeaders(
    headers: Map<String, String>,
    setOptionString: (String, String) -> Int,
): MpvHttpHeadersResult {
    if (headers.isEmpty()) return MpvHttpHeadersResult("none", 0, 0)
    val accepted = headers.count { (name, value) ->
        mpvHeaderApiAccepted { setOptionString("http-header-fields-append", "$name: $value") }
    }
    return MpvHttpHeadersResult("append", accepted, headers.size)
}

private inline fun mpvHeaderApiAccepted(call: () -> Int): Boolean = try {
    call() >= 0
} catch (_: Exception) {
    false
} catch (_: LinkageError) {
    false
}

/** Small Android Surface-backed mpv bridge. Hardware decode is attempted first, then retried in software. */
class MpvPlayerController(
    context: Context,
    private val generation: Int,
    private val tlsCaFile: File,
    subtitleHttpClient: OkHttpClient,
    private val onSoftwareRetry: (MpvPlayerController, LoadIdentity, () -> Boolean, () -> Unit) -> Unit,
    private val desiredPlaying: (() -> Boolean?)? = null,
    private val onPhysicalLoad: (MpvPlayerController, LoadIdentity, Boolean) -> Unit = { _, _, _ -> },
    private val onState: (MpvPlayerController, LoadIdentity, State) -> Unit,
) {
    data class LoadIdentity(val generation: Int, val physicalId: Long, val software: Boolean)
    enum class FailureKind { INITIALIZATION, SURFACE, HTTP, DECODING, FIRST_FRAME_TIMEOUT, LOAD, CERT_INVALID }
    sealed interface InitializationResult {
        data object Ready : InitializationResult
        data class Failed(val reason: String, val identity: LoadIdentity? = null) : InitializationResult
    }

    // Each observer closes over this object, never a mutable generation field.
    private inner class NativeLoad(val identity: LoadIdentity, val engine: MPVLib) {
        val subtitleIo = LoadScopedIo(identity, { isActive(this) }, { task -> handler.post(task) })
        val subtitleFiles = mutableSetOf<File>() // Only accessed on subtitleIo's serial executor.
        val subtitleAliases = ConcurrentHashMap<String, Int>()
        var subtitlePending = 0
        @Volatile var started = false
        var failureKind: FailureKind? = null
        val endFileEvidence = MpvEndFileTracker()
        var endKind: MpvEndKind? = null
        var httpHeadersStatus: String? = null
        var httpHeadersNotice: String? = null
        var retryPending = false
        var terminal = false
        var ended = false
        var audioReconfigurationPending = false
        var stableSamplePending = false
        var sampledAid: Int? = null
        var telemetryPending = false
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {
                if (property != "demuxer-cache-time" && property != "cache-speed") return
                enqueue(this@NativeLoad) {
                    if (property == "demuxer-cache-time") bufferedPositionMs = null
                    else cacheSpeedBytesPerSecond = 0L
                    emitTelemetry(this@NativeLoad)
                }
            }
            override fun eventProperty(property: String, value: String) = enqueue(this@NativeLoad) { handleProperty(this@NativeLoad, property, value) }
            override fun eventProperty(property: String, value: Long) = enqueue(this@NativeLoad) { handleProperty(this@NativeLoad, property, value) }
            override fun eventProperty(property: String, value: Double) {
                // Preserve native-thread samples even while the main handler is backlogged.
                endFileEvidence.property(property, value)
                enqueue(this@NativeLoad) { handleProperty(this@NativeLoad, property, value) }
            }
            override fun eventProperty(property: String, value: Boolean) = enqueue(this@NativeLoad) { handleProperty(this@NativeLoad, property, value) }
            override fun event(eventId: Int) {
                val end = if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) endFileEvidence.snapshot() else null
                enqueue(this@NativeLoad) { handleEvent(this@NativeLoad, eventId, end) }
            }
        }
        val logObserver = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                val at = timestamp()
                endFileEvidence.log(level, text)
                enqueue(this@NativeLoad, requireStarted = false) {
                    appendMpvLog("$at load=${identity.physicalId} $prefix/$level $text")
                    if (!isMpvErrorLogLevel(level)) return@enqueue
                    if (isMpvCertificateFailure(text)) {
                        failureKind = FailureKind.CERT_INVALID
                        emit(this@NativeLoad, buffering = false, error = CERTIFICATE_ERROR_MESSAGE, failureKind = FailureKind.CERT_INVALID)
                        return@enqueue
                    }
                    // Preserve a useful cause when END_FILE cannot expose its reason in this API.
                    mpvEndFailureKind(level, text)?.let { failureKind = it }
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val loadGate = LoadEventGate<NativeLoad>()
    private val activeLoad: NativeLoad? get() = loadGate.current
    private val mpv: MPVLib? get() = activeLoad?.engine

    private fun isActive(load: NativeLoad): Boolean = !released && loadGate.accepts(load)
    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun enqueue(load: NativeLoad, requireStarted: Boolean = true, block: () -> Unit) {
        if (!isActive(load) || (requireStarted && !load.started)) return
        postForLoad(load, 0L, block)
    }

    private fun postForLoad(load: NativeLoad, delayMs: Long, block: () -> Unit) {
        if (!isActive(load)) return
        handler.postAtTime({ if (isActive(load) && !load.terminal) block() }, load, SystemClock.uptimeMillis() + delayMs)
    }

    private data class MpvTrack(
        val id: Int,
        val ffIndex: Int?,
        val language: String?,
        val codec: String?,
        val title: String?,
        val channels: Int?,
        val external: Boolean,
        val externalSourceIndex: Int?,
    )

    data class State(
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val buffering: Boolean = false,
        val ready: Boolean = false,
        val playing: Boolean = false,
        val ended: Boolean = false,
        val error: String? = null,
        val audioSilentCodec: String? = null,
        val cacheSpeedBytesPerSecond: Long = 0L,
        val subtitleNotice: String? = null,
        val failureKind: FailureKind? = null,
        val subtitleFallbackToAuto: Boolean = false,
        val bufferedPositionMs: Long? = null,
        val trackListChanged: Boolean = false,
        val httpHeadersStatus: String? = null,
        val httpHeadersNotice: String? = null,
        val endKind: MpvEndKind? = null,
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
    private val audioDiagnostics = MpvAudioDiagnostics(context)
    private val audioFocus = MpvAudioFocusController(context)
    private var softwareRetryCount = 0
    var unavailableReason: String? = null
        private set
    private var positionMs = 0L
    private var positionAvailable = false
    private var durationMs = 0L
    private var ready = false
    private var playing = false
    private var softwareRetryUsed = false
    @Volatile private var released = false
    private var pendingUrl: String? = null
    private var pendingPositionMs: Long? = null
    private var pauseIntent = false
    private var audioFocusHold = false
    private var duckedVolume: Double? = null
    private var attachedSurface: Surface? = null
    private var lastSurfaceWidth = -1
    private var lastSurfaceHeight = -1
    private var fileLoaded = false
    private var buffering = false
    private var audioStreams: List<MediaStream> = emptyList()
    private var preferredAudioIndex: Int? = null
    private var subtitleStreams: List<MediaStream> = emptyList()
    private var preferredSubtitleIndex: Int? = null
    private var subtitleDisabled = false
    private var subtitleMappingNotice: String? = null
    private var subtitleFallbackToAuto = false
    private val subtitleDownload = ExternalSubtitleDownload(subtitleHttpClient)
    private var lastRetirement: CompletableDeferred<Unit>? = null
    private var audioSilentCodec: String? = null
    private var cacheSpeedBytesPerSecond = 0L
    private var bufferedPositionMs: Long? = null

    // Constructor is side-effect free. The caller installs ownership before initialization.
    fun initialize(): InitializationResult {
        checkMainThread()
        if (released) return InitializationResult.Failed("控制器已释放")
        if (activeLoad != null) return InitializationResult.Ready
        PlayerLifecycle.registry.created(this, "mpv", "playback:$generation")
        clearSessionLog()
        return createNativeLoad(software = false)
    }

    private fun createNativeLoad(software: Boolean): InitializationResult {
        checkMainThread()
        val identity = LoadIdentity(generation, physicalIds.incrementAndGet(), software)
        var created: NativeLoad? = null
        return try {
            val engine = MPVLib.create(appContext) ?: error("libmpv 初始化失败或当前设备 ABI 不受支持")
            val load = NativeLoad(identity, engine)
            created = load
            loadGate.activate(load)
            audioDiagnostics.beginLoad(load.identity)
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
                "hwdec" to if (software) "no" else "mediacodec",
                "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
                "cache" to "yes",
                "keep-open" to "no",
                "sub-scale" to "1.0",
            ).forEach { (key, value) -> engine.setOptionString(key, value) }
            MpvTlsPolicy.options(tlsCaFile).forEach { (key, value) ->
                check(engine.setOptionString(key, value) >= 0) { "无法启用 TLS 证书验证" }
            }
            engine.init()
            engine.addObserver(load.observer)
            engine.addLogObserver(load.logObserver)
            engine.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            engine.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            // The demuxer's last buffered timestamp is independent of the playback head.
            engine.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            engine.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            engine.observeProperty("cache-speed", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            engine.observeProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
            engine.observeProperty("audio-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
            engine.observeProperty("audio-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE)
            engine.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE)
            engine.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            listOf("hwdec-current", "current-vo", "video-params/pixelformat")
                .forEach { engine.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_STRING) }
            listOf("video-params/w", "video-params/h")
                .forEach { engine.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_INT64) }
            engine.observeProperty("vo-drop-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            appendMpvLog("${timestamp()} load=${load.identity} initialized")
            MpvAvailabilityProbe.process.recordInitialization()
            InitializationResult.Ready
        } catch (failure: Throwable) {
            unavailableReason = failure.message ?: "libmpv 初始化失败"
            MpvAvailabilityProbe.process.recordInitialization(unavailableReason)
            created?.let { destroyNativeLoad(it) }
            Log.e(TAG, "mpv initialization failed: $unavailableReason", failure)
            InitializationResult.Failed(checkNotNull(unavailableReason), identity)
        }
    }

    private fun destroyNativeLoad(load: NativeLoad): CompletableDeferred<Unit> {
        checkMainThread()
        load.endFileEvidence.stop()
        restoreDuckedVolume(load)
        loadGate.retire(load)
        handler.removeCallbacksAndMessages(load)
        audioFocus.pauseByUser()
        audioFocusHold = false
        val retirement = load.subtitleIo.retire {
            runCatching { load.engine.removeObserver(load.observer) }
            runCatching { load.engine.removeLogObserver(load.logObserver) }
            runCatching { load.engine.detachSurface() }
            // This serial executor owns both subtitle JNI and destruction. Never wait on the UI.
            runCatching { load.engine.destroy() }
            load.subtitleFiles.forEach(File::delete)
        }
        lastRetirement = retirement
        MpvRetirementFence.track(retirement)
        return retirement
    }

    private val subtitleOptions = linkedMapOf<String, String>()
    private var subtitleDelayMs = 0L
    private var playbackSpeed = 1f
    private var aspectMode = 0

    fun setAspectMode(mode: Int) {
        aspectMode = mode
        if (!released) activeLoad?.let { applyAspectMode(it) }
    }

    private fun applyAspectMode(load: NativeLoad) {
        applyMpvAspectMode(aspectMode,
            setBoolean = { name, value -> load.engine.setPropertyBoolean(name, value) },
            setDouble = { name, value -> load.engine.setPropertyDouble(name, value) })
    }
    private var requestHeaders: Map<String, String> = emptyMap()
    private var subtitleUrls: List<String> = emptyList()

    fun attachSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        checkMainThread()
        if (!refreshPauseIntent()) return
        if (!released && surface.isValid) {
            val resolvedWidth = width.takeIf { it > 0 } ?: fallbackSurfaceWidth
            val resolvedHeight = height.takeIf { it > 0 } ?: fallbackSurfaceHeight
            Log.i(TAG, "${timestamp()} MPVLib.attachSurface (init already completed)")
            if (attachedSurface !== surface) {
                runCatching { mpv?.attachSurface(surface) }.onFailure { failure ->
                    activeLoad?.let { emit(it, error = "播放画面绑定失败：${failure.message}", failureKind = FailureKind.SURFACE) }
                    return
                }
            }
            if (attachedSurface !== surface || resolvedWidth != lastSurfaceWidth || resolvedHeight != lastSurfaceHeight) {
                Log.i(TAG, "${timestamp()} attached surface ${resolvedWidth}x$resolvedHeight, vo=gpu")
                runCatching { mpv?.setPropertyString("android-surface-size", "${resolvedWidth}x${resolvedHeight}") }
                lastSurfaceWidth = resolvedWidth
                lastSurfaceHeight = resolvedHeight
                if (fileLoaded && pendingUrl != null) {
                    // Re-seek after late attachment to make the Android VO render the already-loaded frame.
                    mpv?.setPropertyBoolean("pause", shouldPauseMpv(pauseIntent, audioFocusHold))
                    mpv?.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
                    Log.i(TAG, "${timestamp()} re-render requested after late surface attachment")
                }
            }
            attachedSurface = surface
        }
    }

    fun detachSurface() {
        checkMainThread()
        if (!released) {
            Log.i(TAG, "${timestamp()} MPVLib.detachSurface")
            runCatching { mpv?.detachSurface() }
            if (attachedSurface != null) Log.d(TAG, "surface detached")
            attachedSurface = null
        }
    }

    fun setSubtitleFontSize(sizePx: Int) {
        subtitleOptions["sub-font-size"] = sizePx.coerceIn(16, 120).toString()
        if (!released) runCatching { mpv?.setOptionString("sub-font-size", subtitleOptions.getValue("sub-font-size")) }
    }

    fun setSubtitleStyle(color: Int, outline: Boolean, position: Float, background: Boolean) {
        if (released) return
        val colorHex = "#%08X".format(color)
        val options = listOf(
            "sub-color" to colorHex,
            "sub-border-size" to if (outline) "2.0" else "0.0",
            "sub-pos" to ((1f - position.coerceIn(0f, 0.25f)) * 100f).toInt().toString(),
            "sub-back-color" to if (background) "#80000000" else "#00000000",
        )
        options.forEach { (key, value) ->
            subtitleOptions[key] = value
            runCatching { mpv?.setOptionString(key, value) }
                .onFailure { appendMpvLog("${timestamp()} mpv subtitle style unsupported: $key (${it.message})") }
        }
    }

    fun setSubtitleOffset(offsetMs: Long) {
        subtitleDelayMs = offsetMs
        if (!released) runCatching { mpv?.setPropertyDouble("sub-delay", offsetMs / 1000.0) }
    }

    fun setAudioStreams(streams: List<MediaStream>) {
        mappings.remove("audio")
        audioStreams = streams.filter { it.type.equals("Audio", true) }
    }

    fun setSubtitleStreams(streams: List<MediaStream>) {
        mappings.remove("sub")
        subtitleStreams = streams.filter { it.type.equals("Subtitle", true) }
    }

    fun setAudioTrack(index: Int) {
        if (released) return
        preferredAudioIndex = index.takeIf { it >= 0 }
        audioSilentCodec = null
        if (fileLoaded && preferredAudioIndex != null) applyPreferredAudioTrack()
    }

    fun setSubtitleTrack(embyStreamIndex: Int) {
        if (released) return
        preferredSubtitleIndex = embyStreamIndex
        subtitleDisabled = false
        subtitleMappingNotice = null
        subtitleFallbackToAuto = false
        if (fileLoaded) {
            loadPendingSubtitles()
            applyPreferredSubtitleTrack()
        }
    }

    fun disableSubtitle() {
        if (released) return
        subtitleMappingNotice = null
        subtitleFallbackToAuto = false
        preferredSubtitleIndex = null
        subtitleDisabled = true
        runCatching { mpv?.setPropertyString("sid", "no") }
            .onFailure { appendMpvLog("${timestamp()} mpv subtitle disable failed: ${it.message}") }
    }

    fun enableSubtitleAuto() {
        if (released) return
        subtitleMappingNotice = null
        subtitleFallbackToAuto = false
        preferredSubtitleIndex = null
        subtitleDisabled = false
        if (fileLoaded) loadPendingSubtitles()
        runCatching { mpv?.setPropertyString("sid", "auto") }
            .onFailure { appendMpvLog("${timestamp()} mpv subtitle auto failed: ${it.message}") }
    }

    private fun loadPendingSubtitles() {
        if (subtitleDisabled) return
        val load = activeLoad?.takeIf(::isActive) ?: return
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        load.subtitlePending += subtitles.size
        subtitles.forEach { url ->
            val embyIndex = externalSubtitleIndices[url]
            val headers = requestHeaders
            load.subtitleIo.submit({
                val file = File.createTempFile("mpv-sub-${load.identity.physicalId}-", ".vtt", appContext.cacheDir)
                load.subtitleFiles += file
                subtitleDownload.fetch(url, headers, file, load.subtitleIo)
                load.subtitleIo.checkCurrent()
                if (embyIndex != null) {
                    load.subtitleAliases[file.absolutePath] = embyIndex
                    load.subtitleAliases[file.toURI().toString()] = embyIndex
                }
                // A bounded, validated local VTT is the ONLY input to this synchronous JNI call.
                load.engine.command(arrayOf("sub-add", file.absolutePath, "auto"))
            }) { result ->
                load.subtitlePending--
                mappings.remove("sub")
                result.exceptionOrNull()?.let { failure ->
                    subtitleMappingNotice = if (isCertificateFailure(failure)) CERTIFICATE_ERROR_MESSAGE
                        else "外挂字幕加载失败或超时，视频继续播放；可在字幕菜单换轨或关闭字幕"
                    appendMpvLog("${timestamp()} subtitle load failed: ${failure.javaClass.simpleName}")
                }
                if (subtitleDisabled) disableSubtitle() else applyPreferredSubtitleTrack()
                emit(load)
            }
        }
    }

    fun currentSubtitleEmbyIndex(): Int? {
        val sid = runCatching { mpv?.getPropertyString("sid")?.toIntOrNull() }.getOrNull() ?: return null
        return findEmbyIndex("sub", sid, subtitleStreams)
    }

    fun isSubtitleDisabled(): Boolean = runCatching {
        mpv?.getPropertyString("sid")?.equals("no", true) == true
    }.getOrDefault(false)

    /** One external load per controller; software retry creates its own native load below. */
    fun load(url: String, startPositionMs: Long?, subtitleUrls: List<String> = emptyList(), headers: Map<String, String> = emptyMap(), paused: Boolean = false) {
        checkMainThread()
        val load = activeLoad?.takeIf(::isActive) ?: return
        check(pendingUrl == null) { "A new external load requires a new controller" }
        pendingUrl = url
        pendingPositionMs = startPositionMs?.coerceAtLeast(0L)
        pauseIntent = paused
        this.subtitleUrls = subtitleUrls.toList()
        requestHeaders = nativePlaybackHeaders(headers)
        startPhysicalLoad(load)
    }

    private fun startPhysicalLoad(load: NativeLoad) {
        if (!isActive(load)) return
        if (!refreshPauseIntent()) return
        if (pauseIntent) {
            audioFocus.pauseByUser()
            restoreDuckedVolume()
        } else {
            audioFocusHold = !audioFocus.acquire(audioFocusTarget(load))
        }
        if (!refreshPauseIntent()) return
        val pauseForLoad = shouldPauseMpv(pauseIntent, audioFocusHold)
        audioSilentCodec = null
        subtitleMappingNotice = null
        subtitleFallbackToAuto = false
        ready = false
        fileLoaded = false
        positionMs = 0L
        positionAvailable = false
        durationMs = 0L
        cacheSpeedBytesPerSecond = 0L
        bufferedPositionMs = null
        observedProperties.clear()
        mappings.clear()
        applyAspectMode(load)
        pendingSubtitles = subtitleUrls
        runCatching {
            val headerResult = applyMpvHttpHeaders(requestHeaders, load.engine::setOptionString)
            load.httpHeadersStatus = headerResult.diagnostic
            load.httpHeadersNotice = headerResult.notice
            if (headerResult.total > 0) {
                PageLog.add("${headerResult.diagnostic} load=${load.identity.physicalId}")
            }
            subtitleOptions.forEach { (key, value) -> load.engine.setOptionString(key, value) }
            load.engine.setPropertyDouble("sub-delay", subtitleDelayMs / 1000.0)
            load.engine.setPropertyDouble("speed", playbackSpeed.toDouble())
            // Apply pause before loadfile as well, so a paused load cannot briefly start audio.
            load.engine.setPropertyBoolean("pause", pauseForLoad)
            onPhysicalLoad(this, load.identity, pauseForLoad)
            load.started = true
            load.engine.command(loadCommand(checkNotNull(pendingUrl), pendingPositionMs))
            playing = !pauseForLoad
            buffering = true
            load.engine.setPropertyBoolean("pause", pauseForLoad)
        }.onFailure {
            emit(load, buffering = false, error = "mpv 加载失败：${it.message ?: "未知错误"}", failureKind = FailureKind.LOAD)
            return
        }
        scheduleLoadTimeout(load)
        scheduleHeartbeat(load)
        emit(load, buffering = true)
    }

    private fun scheduleLoadTimeout(load: NativeLoad) {
        postForLoad(load, SOFTWARE_RETRY_TIMEOUT_MS) { onLoadTimeout(load) }
    }

    private fun scheduleHeartbeat(load: NativeLoad) {
        postForLoad(load, SESSION_HEARTBEAT_MS) {
            persistSessionState()
            if (ready && playing && !buffering) checkAudioOutput(load, "stable-playing", assessSilence = true)
            scheduleHeartbeat(load)
        }
    }

    private var pendingSubtitles: List<String> = emptyList()

    private fun loadCommand(url: String, startMs: Long?): Array<String> =
        if (startMs == null) arrayOf("loadfile", url, "replace")
        else arrayOf("loadfile", url, "replace", "-1", "start=${startMs / 1000.0}")

    fun play() {
        checkMainThread()
        pauseIntent = false
        val load = activeLoad?.takeIf(::isActive) ?: return
        audioFocusHold = !audioFocus.play(audioFocusTarget(load))
        if (audioFocusHold) {
            playing = false
            mpv?.setPropertyBoolean("pause", true)
            emit(load)
            return
        }
        playing = true
        mpv?.setPropertyBoolean("pause", shouldPauseMpv(pauseIntent, audioFocusHold))
        emit(load)
    }

    fun pause() {
        checkMainThread()
        pauseIntent = true
        audioFocus.pauseByUser()
        audioFocusHold = false
        restoreDuckedVolume()
        val load = activeLoad?.takeIf(::isActive) ?: return
        playing = false
        mpv?.setPropertyBoolean("pause", true)
        emit(load)
    }

    private fun refreshPauseIntent(): Boolean {
        val provider = desiredPlaying ?: return true
        pauseIntent = !(provider() ?: return false)
        return true
    }

    private fun audioFocusTarget(load: NativeLoad) = MpvAudioFocusController.PlaybackTarget(
        isCurrent = { isActive(load) },
        isPlaying = { playing && !pauseIntent && !audioFocusHold },
        userWantsPlayback = { desiredPlaying?.invoke() ?: !pauseIntent },
        pause = {
            if (isActive(load)) {
                audioFocusHold = true
                playing = false
                load.engine.setPropertyBoolean("pause", true)
                emit(load)
            }
        },
        resume = {
            if (isActive(load) && (desiredPlaying?.invoke() ?: !pauseIntent)) {
                audioFocusHold = false
                playing = true
                load.engine.setPropertyBoolean("pause", false)
                emit(load)
            }
        },
        duck = { duck ->
            if (!isActive(load)) return@PlaybackTarget false
            if (duck) {
                if (duckedVolume == null) duckedVolume = runCatching { load.engine.getPropertyDouble("volume") }
                    .getOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                val current = duckedVolume ?: return@PlaybackTarget false
                runCatching { load.engine.setPropertyDouble("volume", current * 0.2) }.isSuccess
            } else {
                restoreDuckedVolume(load)
                true
            }
        },
    )

    private fun restoreDuckedVolume(load: NativeLoad? = activeLoad) {
        val volume = duckedVolume ?: return
        duckedVolume = null
        load?.takeIf(::isActive)?.let { owner -> runCatching { owner.engine.setPropertyDouble("volume", volume) } }
    }

    fun seekTo(ms: Long) {
        checkMainThread()
        val load = activeLoad?.takeIf(::isActive) ?: return
        load.ended = false
        bufferedPositionMs = null
        val requestedMs = ms.coerceAtLeast(0L)
        mpv?.command(arrayOf("seek", (requestedMs / 1000.0).toString(), "absolute+exact"))
        emit(load)
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        if (!released) mpv?.setPropertyDouble("speed", speed.toDouble())
    }

    fun positionMs(): Long = positionMs
    /** Only time-pos observations count; neither load --start nor seek commands update this value. */
    fun observedPositionMs(): Long? = positionMs.takeIf { positionAvailable }
    fun durationMs(): Long = durationMs

    fun containerFps(): Float? = runCatching {
        mpv?.getPropertyString("container-fps")?.toFloatOrNull()?.takeIf { it > 0f }
    }.getOrNull()
    fun diagnosticLog(): String {
        val previous = runCatching { previousCrashFile.takeIf(File::exists)?.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        val current = synchronized(logLock) { diagnosticLines.toList() }.joinToString("\n")
        return listOf(previous, current).filter(String::isNotBlank).joinToString("\n\n")
    }
    fun softwareRetryCount(): Int = softwareRetryCount
    fun diagnosticLoadIdentity(): LoadIdentity? = activeLoad?.takeIf(::isActive)?.identity

    /** Fresh readback only: never expose cached properties from a retired physical load as actual output. */
    fun diagnosticReadback(identity: LoadIdentity?): Map<String, String> {
        checkMainThread()
        val load = activeLoad?.takeIf { isActive(it) && it.identity == identity } ?: return emptyMap()
        return listOf("audio-decoder", "video-decoder", "hwdec-current", "current-ao",
            "audio-out-params/format", "audio-out-params/channels", "audio-out-params/samplerate")
            .mapNotNull { key -> runCatching { load.engine.getPropertyString(key) }.getOrNull()?.let { key to it } }.toMap()
    }

    fun diagnosticProperties(): Map<String, String> {
        checkMainThread()
        val properties = observedProperties.toMutableMap()
        val engine = activeLoad?.takeIf(::isActive)?.engine ?: return properties
        listOf("aid", "audio-params", "audio-out-params", "current-ao", "audio-device", "track-list",
            "audio-codec-name", "audio-decoder", "af", "af-metadata")
            .forEach { key ->
                // Preserve missing-property semantics used by A1's absent-output check.
                runCatching { engine.getPropertyString(key) }.getOrNull()?.let { properties[key] = it }
            }
        listOf("time-pos", "audio-pts", "audio-bitrate", "packet-audio-bitrate", "audio-buffer",
            "volume", "ao-volume", "audio-delay", "speed").forEach { key ->
            properties[key] = runCatching { engine.getPropertyDouble(key) }.getOrNull()?.toString() ?: "unavailable"
        }
        listOf("mute", "ao-mute", "pause", "paused-for-cache").forEach { key ->
            properties[key] = runCatching { engine.getPropertyBoolean(key) }.getOrNull()?.toString() ?: "unavailable"
        }
        return properties
    }

    private fun applyPreferredAudioTrack() {
        val embyIndex = preferredAudioIndex ?: return
        val id = findMpvId("audio", embyIndex, audioStreams)
        if (id == null) {
            appendMpvLog("${timestamp()} mpv audio map failed: embyIndex=$embyIndex mapping=fallback-unavailable")
            return
        }
        appendMpvLog("${timestamp()} mpv audio map: embyIndex=$embyIndex -> aid=$id")
        if (mpv?.getPropertyString("aid") != id.toString()) {
            mpv?.setPropertyInt("aid", id)
        }
        activeLoad?.let { load ->
            if (load.sampledAid != id) {
                load.sampledAid = id
                postForLoad(load, 200L) { checkAudioOutput(load, "track-selected") }
            }
        }
    }

    private fun applyPreferredSubtitleTrack() {
        val load = activeLoad?.takeIf(::isActive) ?: return
        if (subtitleDisabled) return
        val embyIndex = preferredSubtitleIndex ?: return
        val id = findMpvId("sub", embyIndex, subtitleStreams)
        if (id == null) {
            // FILE_LOADED/track-list can precede asynchronous external track arrival.
            if (load.subtitlePending > 0 || pendingSubtitles.isNotEmpty()) return
            appendMpvLog("${timestamp()} mpv subtitle map failed: embyIndex=$embyIndex mapping=fallback-unavailable")
            subtitleMappingNotice = missingSubtitleTrackNotice(subtitleMappingNotice)
            subtitleFallbackToAuto = true
            preferredSubtitleIndex = null
            subtitleDisabled = false
            runCatching { mpv?.setPropertyString("sid", "auto") }
            emit(load)
            return
        }
        appendMpvLog("${timestamp()} mpv subtitle map: embyIndex=$embyIndex -> sid=$id")
        mpv?.setPropertyInt("sid", id)
    }

    private var trackContext = TrackMappingContext()
    private var subtitleTrackContext = TrackMappingContext()
    private var externalSubtitleIndices = emptyMap<String, Int>()
    private val mappings = mutableMapOf<String, Pair<List<MpvTrack>, TrackMapping<Int>>>()

    fun setTrackMappingContext(context: TrackMappingContext, subtitleIndex: Int? = null) {
        trackContext = context.copy(orderEvidence = null) // mpv uses ff-index, never src-id or ordinal.
        subtitleTrackContext = trackContext.copy(negotiatedIndex = subtitleIndex)
        mappings.clear()
    }

    fun setExternalSubtitleIdentities(indices: Map<String, Int>) {
        externalSubtitleIndices = indices.toMap()
        mappings.remove("sub")
    }

    private fun trackMapping(type: String, streams: List<MediaStream>): TrackMapping<Int> {
        val tracks = readMpvTracks(type)
        mappings[type]?.takeIf { it.first == tracks }?.let { return it.second }
        val context = if (type == "audio") trackContext else subtitleTrackContext
        val table = buildTrackMapping(streams, tracks.map {
            PlayerTrack(it.id, it.ffIndex, it.language, it.codec, it.title, it.channels,
                it.externalSourceIndex, it.external)
        }, context)
        mappings[type] = tracks to table
        PageLog.add("mpv $type mapping ${table.summary()}")
        return table
    }

    private fun findMpvId(type: String, embyIndex: Int, streams: List<MediaStream>): Int? =
        trackMapping(type, streams).playerFor(embyIndex)

    private fun logTrackMapping(type: String, streams: List<MediaStream>, mpvType: String) {
        trackMapping(mpvType, streams)
    }

    private fun findEmbyIndex(type: String, id: Int, streams: List<MediaStream>): Int? =
        trackMapping(type, streams).embyFor(id)

    fun hasAudioMapping(index: Int): Boolean = trackMapping("audio", audioStreams).playerFor(index) != null

    fun currentPlayerAudioId(): Int? = runCatching { mpv?.getPropertyString("aid")?.toIntOrNull() }.getOrNull()

    fun currentAudioEmbyIndex(): Int? = currentPlayerAudioId()?.let { trackMapping("audio", audioStreams).embyFor(it) }

    fun uncertainAudioTracks(): List<Pair<Int, String>> {
        val table = trackMapping("audio", audioStreams)
        return readMpvTracks("audio").filter { table.embyFor(it.id) == null }
            .map { it.id to listOfNotNull(it.title, it.language, it.codec).joinToString(" · ").ifBlank { "音轨 ${it.id}" } }
    }

    internal data class MenuTracks(
        val audioIndex: Int?,
        val playerAudioId: Int?,
        val uncertainAudio: List<Pair<Int, String>>,
        val subtitleIndex: Int?,
        val subtitleDisabled: Boolean,
    )

    internal fun menuTracks(): MenuTracks {
        val audioId = currentPlayerAudioId()
        return MenuTracks(
            audioId?.let { trackMapping("audio", audioStreams).embyFor(it) },
            audioId,
            uncertainAudioTracks(),
            currentSubtitleEmbyIndex(),
            isSubtitleDisabled(),
        )
    }

    fun selectPlayerAudioTrack(id: Int) {
        if (released || readMpvTracks("audio").none { it.id == id }) return
        preferredAudioIndex = null
        mpv?.setPropertyInt("aid", id)
    }

    private fun readMpvTracks(type: String): List<MpvTrack> = runCatching {
        val raw = mpv?.getPropertyString("track-list") ?: return@runCatching emptyList()
        val tracks = JSONArray(raw)
        (0 until tracks.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            if (!track.optString("type").equals(type, true)) return@mapNotNull null
            val id = track.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val external = track.optBoolean("external", false)
            MpvTrack(
                id = id,
                ffIndex = track.optInt("ff-index", -1).takeIf { it >= 0 && !external },
                language = track.optString("lang").takeIf(String::isNotBlank),
                codec = track.optString("codec").takeIf(String::isNotBlank),
                title = track.optString("title").takeIf(String::isNotBlank),
                channels = track.optInt("demux-channel-count", -1).takeIf { it > 0 },
                external = external,
                externalSourceIndex = if (external) externalSubtitleIndices[track.optString("external-filename")]
                    ?: activeLoad?.subtitleAliases?.get(track.optString("external-filename")) else null,
            )
        }
    }.getOrDefault(emptyList())

    private fun checkAudioOutput(load: NativeLoad, point: String, assessSilence: Boolean = false) {
        if (!isActive(load)) return
        val properties = diagnosticProperties()
        properties["aid"]?.let { observedProperties["aid"] = it }
        audioDiagnostics.sample(load.identity, point, positionMs, properties)
        PageLog.add("mpvAudio sample=$point load=${load.identity.physicalId} pos=$positionMs aid=${properties["aid"]} ao=${properties["current-ao"]} volume=${properties["volume"]} mute=${properties["mute"]}")
        if (!assessSilence || !ready) return
        val params = properties["audio-params"].orEmpty()
        val ao = properties["current-ao"].orEmpty()
        // Retain A1's missing-output check. System AudioTrackImpl '[zero]' is not an AO name.
        val abnormalAo = ao.isBlank() || ao.lowercase() in setOf("null", "unknown", "no")
        if (params.isBlank() || params == "null" || params == "{}" || abnormalAo) {
            val codec = audioStreams.firstOrNull { it.index == preferredAudioIndex }?.codec
                ?: audioStreams.firstOrNull { it.isDefault }?.codec
                ?: audioStreams.firstOrNull()?.codec ?: "unknown"
            audioSilentCodec = codec
            emit(load)
        }
    }

    private fun clearSessionLog() {
        synchronized(logLock) {
            diagnosticLines.clear()
        }
        sessionFiles.execute {
            recordPreviousAbnormalSession()
            runCatching { sessionLogFile.writeText("", Charsets.UTF_8) }
        }
    }

    private fun appendMpvLog(line: String) {
        synchronized(logLock) {
            if (diagnosticLines.size >= MAX_DIAGNOSTIC_LINES) diagnosticLines.removeFirst()
            diagnosticLines.addLast(line)
        }
        sessionFiles.execute {
            runCatching {
                sessionLogFile.appendText("$line\n", Charsets.UTF_8)
                if (sessionLogFile.length() > MAX_SESSION_LOG_BYTES) {
                    val bytes = sessionLogFile.readBytes()
                    val start = bytes.copyOfRange(bytes.size - MAX_SESSION_LOG_BYTES / 2, bytes.size).indexOf('\n'.code.toByte())
                    sessionLogFile.writeBytes(bytes.copyOfRange(bytes.size - MAX_SESSION_LOG_BYTES / 2 + start + 1, bytes.size))
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
        runCatching { sessionStateFile.writeText(JSONObject().put("active", false).toString(), Charsets.UTF_8) }
    }

    private fun persistSessionState() {
        val identity = activeLoad?.identity ?: return
        val properties = observedProperties.toMap()
        val lastLog = synchronized(logLock) { diagnosticLines.lastOrNull() ?: "none" }
        val at = timestamp()
        sessionFiles.execute {
            val runtime = Runtime.getRuntime()
            val state = JSONObject()
            .put("active", true)
            .put("at", at)
            .put("pid", android.os.Process.myPid())
            .put("generation", identity.generation)
            .put("physicalLoadId", identity.physicalId)
            .put("hwdec", properties["hwdec-current"] ?: "pending")
            .put("vo", properties["current-vo"] ?: "pending")
            .put("video", JSONObject()
                .put("pixelformat", properties["video-params/pixelformat"] ?: JSONObject.NULL)
                .put("w", properties["video-params/w"] ?: JSONObject.NULL)
                .put("h", properties["video-params/h"] ?: JSONObject.NULL))
            .put("audio", JSONObject(properties.filterKeys {
                it in SESSION_AUDIO_PROPERTIES
            }))
            .put("memory", JSONObject()
                .put("usedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("maxBytes", runtime.maxMemory()))
            .put("lastLog", lastLog)
            runCatching { sessionStateFile.writeText(state.toString(), Charsets.UTF_8) }
        }
    }

    fun release(reason: String = "unspecified") {
        checkMainThread()
        if (released) return
        activeLoad?.endFileEvidence?.stop()
        restoreDuckedVolume()
        audioFocus.release()
        released = true
        handler.removeCallbacksAndMessages(null)
        audioDiagnostics.close()
        val retirement = activeLoad?.let { destroyNativeLoad(it) } ?: lastRetirement
        val cleanup = {
            tlsCaFile.delete()
            PlayerLifecycle.registry.released(this, reason)
        }
        if (retirement == null) cleanup() else retirement.invokeOnCompletion { cleanup() }
        loadGate.close()
        attachedSurface = null
        sessionFiles.execute {
            runCatching { sessionStateFile.writeText(JSONObject().put("active", false).put("endedAt", timestamp()).put("reason", reason).toString(), Charsets.UTF_8) }
        }
    }

    private fun onLoadTimeout(load: NativeLoad) {
        if (!isActive(load) || ready || load.terminal || !mpvEndAllowsRecovery(load.endKind)) return
        if (load.failureKind == FailureKind.CERT_INVALID) {
            emit(load, buffering = false, error = CERTIFICATE_ERROR_MESSAGE, failureKind = FailureKind.CERT_INVALID)
            return
        }
        if (load.failureKind == FailureKind.HTTP) {
            emit(load, buffering = false, error = "mpv HTTP 请求失败", failureKind = FailureKind.HTTP)
            return
        }
        if (load.retryPending) return
        if (!softwareRetryUsed) {
            load.retryPending = true
            val stillNeeded = { isActive(load) && !ready && !load.terminal && mpvEndAllowsRecovery(load.endKind) }
            onSoftwareRetry(this, load.identity, stillNeeded) {
                if (stillNeeded()) retryInSoftware(load)
            }
        } else {
            val kind = load.failureKind ?: FailureKind.FIRST_FRAME_TIMEOUT
            val message = if (kind == FailureKind.DECODING) "mpv 硬解和软解均失败" else "mpv 未能出画（硬解和软解均无首帧）"
            emit(load, buffering = false, error = message, failureKind = kind)
        }
    }

    private fun retryInSoftware(load: NativeLoad) {
        checkMainThread()
        if (!isActive(load)) return
        softwareRetryUsed = true
        softwareRetryCount++
        val retirement = destroyNativeLoad(load)
        fileLoaded = false
        ready = false
        retirement.invokeOnCompletion {
            handler.post {
                if (released || activeLoad != null) return@post
                // Surface callbacks can run during retirement. Rebind the latest surface, not a
                // stale pre-retirement snapshot or a surface cached while activeLoad was null.
                val surface = attachedSurface
                val width = lastSurfaceWidth
                val height = lastSurfaceHeight
                attachedSurface = null
                val result = createNativeLoad(software = true)
                if (result is InitializationResult.Failed) {
                    if (!released && activeLoad == null) {
                        onState(this, checkNotNull(result.identity), State(error = result.reason, failureKind = FailureKind.INITIALIZATION))
                    }
                    return@post
                }
                val retry = checkNotNull(activeLoad)
                if (surface?.isValid != true) {
                    emit(retry, buffering = false, error = "播放画面未就绪（Surface 未创建）", failureKind = FailureKind.SURFACE)
                    return@post
                }
                attachSurface(surface, width, height)
                appendMpvLog("${timestamp()} load=${retry.identity} hwdec fallback -> no")
                startPhysicalLoad(retry)
            }
        }
    }

    private fun handleProperty(load: NativeLoad, property: String, value: String) {
        if (property in MPV_DIAGNOSTIC_PROPERTIES) {
            synchronized(observedProperties) { observedProperties[property] = value }
            appendMpvLog("${timestamp()} property $property=$value")
            persistSessionState()
        }
        if (property in setOf("audio-params", "audio-out-params", "current-ao") && !load.audioReconfigurationPending) {
            load.audioReconfigurationPending = true
            postForLoad(load, 200L) {
                load.audioReconfigurationPending = false
                checkAudioOutput(load, "audio-reconfigured")
            }
        }
        when (property) {
            "track-list" -> {
                logTrackMapping("audio", audioStreams, "audio")
                logTrackMapping("subtitle", subtitleStreams, "sub")
                if (fileLoaded) {
                    if (subtitleDisabled) disableSubtitle() else applyPreferredSubtitleTrack()
                    if (preferredAudioIndex != null) applyPreferredAudioTrack()
                }
                emit(load, trackListChanged = true)
            }
            "end-file" -> Log.w(TAG, "${timestamp()} END_FILE reason: $value")
            "video-out-params" -> {
                if (!fileLoaded || value.isBlank() || value == "{}" || value == "null") return
                ready = true
                buffering = false
                scheduleStableAudioSample(load)
                Log.i(TAG, "${timestamp()} received video-out-params")
                emit(load, buffering = false, ready = true)
            }
        }
    }
    private fun handleProperty(load: NativeLoad, property: String, value: Long) {
        if (property == "cache-speed") {
            cacheSpeedBytesPerSecond = value.coerceAtLeast(0L)
            emitTelemetry(load)
        }
        if (property == "video-params/w" || property == "video-params/h") {
            synchronized(observedProperties) { observedProperties[property] = value.toString() }
            appendMpvLog("${timestamp()} property $property=$value")
            persistSessionState()
        }
    }
    private fun handleProperty(load: NativeLoad, property: String, value: Double) {
        when (property) {
            "time-pos" -> if (value.isFinite() && value >= 0.0) {
                positionMs = (value * 1000.0).toLong()
                positionAvailable = true
            }
            "duration" -> durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "demuxer-cache-time" -> bufferedPositionMs =
                value.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1000.0).toLong() }
        }
        emitTelemetry(load)
    }

    private fun handleProperty(load: NativeLoad, property: String, value: Boolean) {
        when (property) {
            "paused-for-cache" -> {
                buffering = value
                emit(load, buffering = value)
            }
            "pause" -> {
                playing = !value
                if (!value) buffering = false
                emit(load)
            }
        }
    }

    private fun handleEvent(load: NativeLoad, eventId: Int, end: MpvEndFileEvidence? = null) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (!refreshPauseIntent()) return
                val pauseForLoad = shouldPauseMpv(pauseIntent, audioFocusHold)
                load.engine.setPropertyBoolean("pause", pauseForLoad)
                fileLoaded = true
                applyAspectMode(load)
                buffering = false
                Log.i(TAG, "${timestamp()} received FILE_LOADED")
                checkAudioOutput(load, "file-loaded")
                applyPreferredAudioTrack()
                persistSessionState()
                loadPendingSubtitles()
                logTrackMapping("audio", audioStreams, "audio")
                logTrackMapping("subtitle", subtitleStreams, "sub")
                if (subtitleDisabled) disableSubtitle()
                else if (preferredSubtitleIndex != null) applyPreferredSubtitleTrack()
                emit(load, buffering = false)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val firstReady = !ready
                ready = true
                if (firstReady) Log.i(TAG, "${timestamp()} received PLAYBACK_RESTART (first frame ready)")
                buffering = false
                scheduleStableAudioSample(load)
                emit(load, buffering = false, ready = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (load.endKind != null) return
                val captured = checkNotNull(end)
                val latest = load.endFileEvidence.snapshot()
                // Read only on the owning main thread, before any retirement. Most
                // properties are already unavailable here, so retain callback samples.
                val evidence = captured.copy(
                    positionMs = captured.positionMs ?: mpvSecondsToMs(runCatching { load.engine.getPropertyDouble("time-pos") }.getOrNull()),
                    durationMs = captured.durationMs ?: mpvSecondsToMs(runCatching { load.engine.getPropertyDouble("duration") }.getOrNull()),
                    stopped = captured.stopped || latest.stopped,
                    failureKind = captured.failureKind ?: latest.failureKind ?: load.failureKind,
                    errorLogSeen = captured.errorLogSeen || latest.errorLogSeen,
                )
                val kind = classifyMpvEndFile(evidence)
                load.endKind = kind
                playing = false
                buffering = false
                fileLoaded = false
                audioSilentCodec = null
                appendMpvLog("${timestamp()} END_FILE kind=$kind pos=${evidence.positionMs} duration=${evidence.durationMs} errorLog=${evidence.errorLogSeen}")
                when (kind) {
                    MpvEndKind.ERROR -> {
                        load.failureKind = checkNotNull(evidence.failureKind)
                        if (!ready) onLoadTimeout(load)
                        else emit(load, error = "mpv 播放中断", failureKind = load.failureKind)
                    }
                    MpvEndKind.EOF -> {
                        positionMs = checkNotNull(evidence.positionMs)
                        positionAvailable = true
                        durationMs = checkNotNull(evidence.durationMs)
                        load.ended = true
                        emit(load, ended = true)
                        load.terminal = true
                    }
                    MpvEndKind.STOP, MpvEndKind.UNKNOWN -> {
                        // Neutral termination: no failure callback, no software retry or timer.
                        emit(load)
                        load.terminal = true
                    }
                }
            }
        }
    }

    private fun scheduleStableAudioSample(load: NativeLoad) {
        if (load.stableSamplePending) return
        load.stableSamplePending = true
        postForLoad(load, AUDIO_OUTPUT_CHECK_DELAY_MS) {
            load.stableSamplePending = false
            if (playing && !buffering) checkAudioOutput(load, "stable-playing", assessSilence = true)
        }
    }

    private fun emit(
        load: NativeLoad,
        buffering: Boolean = this.buffering,
        ready: Boolean = this.ready,
        ended: Boolean = load.ended,
        error: String? = null,
        failureKind: FailureKind? = null,
        trackListChanged: Boolean = false,
    ) {
        checkMainThread()
        if (!isActive(load) || load.terminal) return
        if (error != null) load.terminal = true
        onState(this, load.identity, State(positionMs, durationMs, buffering, ready, playing, ended, error, audioSilentCodec,
            cacheSpeedBytesPerSecond, subtitleMappingNotice, failureKind, subtitleFallbackToAuto, bufferedPositionMs,
            trackListChanged, load.httpHeadersStatus, load.httpHeadersNotice, load.endKind))
    }

    private fun emitTelemetry(load: NativeLoad) {
        if (load.telemetryPending) return
        load.telemetryPending = true
        postForLoad(load, 100L) {
            load.telemetryPending = false
            emit(load)
        }
    }

    private companion object {
        val physicalIds = AtomicLong()
        val sessionFiles = Executors.newSingleThreadExecutor { task -> Thread(task, "mpv-session-files").apply { isDaemon = true } }
        val SESSION_AUDIO_PROPERTIES = setOf("aid", "audio-params", "audio-out-params", "current-ao", "audio-device")
        const val TAG = "MpvPlayerController"
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
