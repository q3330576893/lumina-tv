package com.lumina.tv.ui.screens

import android.content.Context
import android.os.Looper
import com.lumina.tv.diag.LatestDiagnosticWrite
import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.PageLog
import com.lumina.tv.diag.boundedDiagnosticJson
import com.lumina.tv.diag.readDiagnosticTail
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import androidx.media3.exoplayer.ExoPlayer
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.diag.LogcatCapture
import com.lumina.tv.diag.AppLog
import com.lumina.tv.diag.NetLog
import com.lumina.tv.player.MpvAvailabilityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import com.lumina.tv.player.AudioCapabilities
import com.lumina.tv.player.AudioCodecMatrix
import com.lumina.tv.player.AudioSupport
import com.lumina.tv.player.AudioOutputSnapshot
import com.lumina.tv.player.MpvAvailabilitySnapshot
import com.lumina.tv.player.MpvPlayerController
import com.lumina.tv.player.nativeVersionDiagnosticProperties
import com.lumina.tv.player.PlaybackDiagnostics
import com.lumina.tv.player.PlaybackFailureInput
import com.lumina.tv.player.PlaybackHttpTrace
import com.lumina.tv.player.PlaybackLoadDetailSnapshot
import com.lumina.tv.player.diagnosticsGap
import com.lumina.tv.player.playbackFailureJson
import com.lumina.tv.player.PlayerLifecycle
import com.lumina.tv.player.displayHdrDiagnostic
import com.lumina.tv.player.readDisplayHdrTypes
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun buildPlayerDiagPayload(
    context: Context,
    outcome: String,
    activeEngine: String,
    mpvAvailability: MpvAvailabilitySnapshot,
    mpvBackend: MpvPlayerController?,
    item: MediaItem,
    playbackSource: MediaSource?,
    load: PlaybackLoadDetailSnapshot,
    audioDecodeMode: String,
    audioSupport: String,
    audioCaps: AudioCapabilities,
    degraded: Boolean,
    engineSwitchTrace: List<String>,
    exo: ExoPlayer?,
    failurePhase: String,
    mpvState: MpvPlayerController.State,
    showControls: Boolean,
    buffering: Boolean,
    error: String?,
    position: Long,
    activeVideoDecoder: String?,
    dvDecoderPath: String?,
    playbackStatusNotice: String?,
    phaseTimes: JSONObject,
    droppedFrames: Long,
    renderedFrames: Long,
    bufferingCount: Int,
    bufferingDurationMs: Long,
    duration: Long,
    playbackDiagnostics: PlaybackDiagnostics,
    httpTrace: JSONObject,
    selectedAudioStreamIndex: Int?,
    audioOutputSnapshot: AudioOutputSnapshot?,
): DiagPayload {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Player diagnostics require main" }
    if (exo != null) check(exo.applicationLooper == Looper.getMainLooper()) { "Player applicationLooper must be main" }
    val videoStream = playbackSource?.streams?.firstOrNull { it.type == "Video" }
    val isDolbyVision = videoStream?.let {
        val range = it.videoRangeType.orEmpty().uppercase().filter(Char::isLetterOrDigit)
        val profile = it.profile.orEmpty().uppercase().filter(Char::isLetterOrDigit)
        val display = (it.displayTitle.orEmpty() + " " + it.title.orEmpty()).uppercase().filter(Char::isLetterOrDigit)
        range.startsWith("DOVI") || range == "DOLBYVISION" || range == "DOVIINVALID" ||
            (it.dvProfile ?: 0) > 0 || profile.contains("DVHE") || profile.contains("DVH1") ||
            profile.contains("DVPROFILE") || profile.contains("DOLBYVISION") ||
            display.contains("DOVI") || display.contains("DOLBYVISION")
    } ?: false
    val phases = JSONObject(phaseTimes.toString())
    val loadIdentity = (if (load.current) playbackDiagnostics.identityJson() else JSONObject().put("state", "stale"))
        .put("generation", load.key.generation).put("itemId", load.key.itemId)
        .put("sourceId", load.key.sourceId ?: JSONObject.NULL)
    val timeline = phases.optJSONObject("timeline") ?: JSONObject()
    val progress = phases.optJSONObject("progress") ?: JSONObject().put("timelineUnverified", true)
    val readback = if (activeEngine == "mpv") mpvBackend?.diagnosticReadback(playbackDiagnostics.identity).orEmpty() else emptyMap()
    val actualDecoder = if (load.current) playbackDiagnostics.actualDecoder(activeEngine, readback) else JSONObject.NULL
    val actualAudioIndex = if (activeEngine == "mpv") mpvBackend?.currentAudioEmbyIndex() else selectedAudioStreamIndex
    val actualOutputSnapshot = audioOutputSnapshot?.takeIf { snapshot ->
        val binding = snapshot.binding
        load.current && binding.scopeId == playbackDiagnostics.sessionId && binding.itemId == item.id &&
            binding.sourceId == (load.key.sourceId ?: "unknown") && binding.engine == activeEngine &&
            binding.generation == load.key.generation &&
            binding.trackId == (actualAudioIndex?.toString() ?: "unknown")
    }

    val actualOutput = if (!load.current) JSONObject.NULL else actualOutputSnapshot?.json()
        ?: JSONObject().put("value", "unknown").put("confidence", "UNKNOWN")
    val displayHdrTypes = readDisplayHdrTypes(context)
    val audioStream = playbackSource?.audioStreams?.firstOrNull { it.index == actualAudioIndex }
    val audioCapabilitySummary = AudioCodecMatrix.currentAudioSupport(audioCaps)
    if (activeEngine == "mpv") listOf("decoderInitMs", "compatMappingMs", "firstFrameMs", "stableOutputMs").forEach { key ->
        if (phases.isNull(key)) phases.put(key, "N/A")
    }
    val nativeVersions = JSONObject(mpvBackend?.diagnosticNativeVersions()
        ?: nativeVersionDiagnosticProperties(false) { null })
    val audioProbe = audioCaps.probe?.json() ?: JSONObject()
        .put("api", JSONObject.NULL).put("input", JSONObject.NULL).put("rawResult", JSONObject.NULL)
        .put("state", "not_collected")
    val meta = DiagExporter.baseMeta(context, outcome)
        .put("load", loadIdentity)
        .put("display", JSONObject().put("hdr", displayHdrDiagnostic(displayHdrTypes)?.let(::JSONArray) ?: JSONObject.NULL))
        .put("engine", mpvAvailability.addTo(JSONObject()
            .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown")
            .put("active", activeEngine)
            .put("native", nativeVersions)
            .put("anime4k", JSONObject()
                .put("mode", AppRuntime.prefs?.anime4kMode ?: "off")
                .put("shaders", if (activeEngine == "mpv") mpvBackend?.anime4kShaderCount() ?: 0 else 0)
                .put("fallback", false))
            .put("audioSupport", JSONObject()
                .put("maxChannels", audioCapabilitySummary.maxChannels)
                .put("passthrough", JSONArray(audioCapabilitySummary.codecs
                    .filterValues { it == AudioSupport.PASSTHROUGH }.keys.sorted()))
                .put("probe", audioProbe))
            .put("mpvProperties", JSONObject(mpvBackend?.diagnosticProperties().orEmpty()))
            .put("liveVideoDecoders", PlayerLifecycle.registry.liveVideoDecoders)
            .put("videoDecoderCountScope", "media3_callbacks;mpv_lifecycle_only")
            .put("kernels", JSONArray().put("media3").put("mpv"))))
        .put("media", JSONObject()
            .put("id", item.id)
            .put("name", item.name)
            .put("seriesName", item.seriesName ?: JSONObject.NULL)
            .put("container", playbackSource?.container ?: JSONObject.NULL)
            .put("size", playbackSource?.size ?: 0L)
            .put("isStrm", playbackSource?.container?.contains("strm", true) == true)
            .put("video", JSONObject()
                .put("dolbyVision", isDolbyVision)
                .put("profile", videoStream?.profile ?: JSONObject.NULL)
                .put("videoRangeType", videoStream?.videoRangeType ?: JSONObject.NULL)
                .put("dvProfile", videoStream?.dvProfile ?: JSONObject.NULL)
                .put("displayTitle", videoStream?.displayTitle ?: JSONObject.NULL)
                .put("decoderName", activeVideoDecoder ?: JSONObject.NULL)
                .put("decoderPath", dvDecoderPath ?: JSONObject.NULL)))
        .put("playback", load.playbackJson()
            .put("phasesMs", phases)
            .put("timeline", timeline)
            .put("progress", progress)
            .put("frameStability", JSONObject().put("droppedFrames", droppedFrames)
                .put("framesWithProcessingOffset", renderedFrames).put("bufferingCount", bufferingCount)
                .put("bufferingDurationMs", bufferingDurationMs)
                .put("sourceFps", videoStream?.frameRate ?: JSONObject.NULL)
                .put("firstFrameLatencyMs", phases.opt("firstFrameMs"))))
        .put("audioDecode", JSONObject()
            .put("load", loadIdentity)
            .put("sourceFormat", JSONObject()
                .put("container", playbackSource?.container ?: "unknown")
                .put("selectedAudioStreamIndex", actualAudioIndex ?: "unknown")
                .put("codec", audioStream?.codec ?: "unknown")
                .put("channels", audioStream?.channels ?: "unknown")
                .put("audioStreams", JSONArray().apply {
                    playbackSource?.audioStreams.orEmpty().forEach { stream ->
                        put(JSONObject().put("index", stream.index).put("codec", stream.codec ?: "unknown")
                            .put("channels", stream.channels ?: "unknown").put("sampleRate", stream.sampleRate ?: "unknown"))
                    }
                }))
            .put("selectionPolicy", JSONObject()
                .put("requestedAudioMode", audioDecodeMode)
                .put("audioSupportAssessment", audioSupport)
                .put("declareDtsDirectPlay", AppRuntime.prefs?.declareDtsDirectPlay ?: false)
                .put("selectedEngine", activeEngine).put("negotiatedPlayMethod", load.playMethod))
            .put("actualDecoder", actualDecoder)
            .put("actualAudioOutput", actualOutput))
        .put("engineSwitchTrace", JSONArray(engineSwitchTrace))
    val streams = JSONArray().apply {
        playbackSource?.streams.orEmpty().forEach { stream ->
            put(JSONObject()
                .put("Index", stream.index).put("Type", stream.type).put("Codec", stream.codec ?: JSONObject.NULL)
                .put("Profile", stream.profile ?: JSONObject.NULL).put("Level", stream.level ?: JSONObject.NULL)
                .put("DvProfile", stream.dvProfile ?: JSONObject.NULL).put("VideoRangeType", stream.videoRangeType ?: JSONObject.NULL)
                .put("Width", stream.width ?: JSONObject.NULL).put("Height", stream.height ?: JSONObject.NULL)
                .put("BitRate", stream.bitRate ?: JSONObject.NULL).put("Channels", stream.channels ?: JSONObject.NULL)
                .put("SampleRate", stream.sampleRate ?: JSONObject.NULL).put("IsDefault", stream.isDefault)
                .put("DisplayTitle", stream.displayTitle ?: JSONObject.NULL).put("Language", stream.language ?: JSONObject.NULL))
        }
    }
    val tracks = JSONArray().apply {
        if (load.current) exo?.currentTracks?.groups.orEmpty().forEach { group ->
            val formats = JSONArray()
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                formats.put(JSONObject()
                    .put("sampleMimeType", f.sampleMimeType ?: JSONObject.NULL)
                    .put("id", f.id ?: JSONObject.NULL).put("label", f.label ?: JSONObject.NULL)
                    .put("language", f.language ?: JSONObject.NULL)
                    .put("codecs", f.codecs ?: JSONObject.NULL).put("bitrate", f.bitrate)
                    .put("width", f.width).put("height", f.height).put("sampleRate", f.sampleRate)
                    .put("channelCount", f.channelCount).put("selected", group.isTrackSelected(i))
                    .put("supported", group.isTrackSupported(i)))
            }
            put(JSONObject().put("type", group.type).put("length", group.length).put("formats", formats))
        }
    }
    val mpvError = mpvState.error ?: load.mpvInitializationError
    val errors = load.errorsJson(mpvError, error)
        .put("engine", activeEngine)
        .put("failurePhase", failurePhase)
        .put("decoderName", activeVideoDecoder ?: JSONObject.NULL)
        .put("dolbyVision", isDolbyVision)
        .put("dvProfile", videoStream?.dvProfile ?: JSONObject.NULL)
        .put("videoRangeType", videoStream?.videoRangeType ?: JSONObject.NULL)
        .put("profile", videoStream?.profile ?: JSONObject.NULL)
        .put("displayTitle", videoStream?.displayTitle ?: JSONObject.NULL)
        .put("externalLinks", runCatching { JSONObject(TmdbClient.externalLinkDiagnosticSnapshot()) }.getOrNull() ?: JSONObject.NULL)
        .put("decoderPath", dvDecoderPath ?: JSONObject.NULL)
        .put("hasPlaybackError", error != null)
        .put("hasPlaybackNotice", playbackStatusNotice != null)
        .put("finalReason", error ?: playbackStatusNotice ?: JSONObject.NULL)
        .put("mpvHttpHeaders", mpvState.httpHeadersStatus ?: JSONObject.NULL)
        .put("mpvHttpHeadersNotice", mpvState.httpHeadersNotice ?: JSONObject.NULL)
        .put("mpvSoftwareRetryCount", mpvBackend?.softwareRetryCount() ?: 0)
        .put("metrics", if (load.current) playbackDiagnostics.snapshot(android.os.SystemClock.elapsedRealtime())
            .put("droppedFrames", droppedFrames + (mpvBackend?.diagnosticProperties()?.get("vo-drop-frame-count")?.toLongOrNull() ?: 0L))
            .put("droppedFramesScope", "screen-session") else JSONObject().put("state", "stale"))
    val metrics = playbackDiagnostics.snapshot(android.os.SystemClock.elapsedRealtime())
    val metricEvents = metrics.optJSONArray("events") ?: JSONArray()
    val loadStartedAt = (0 until metricEvents.length()).asSequence()
        .map { metricEvents.optJSONObject(it) }
        .firstOrNull { it?.optString("event") == "load_started" &&
            it.optJSONObject("loadIdentity")?.optInt("generation") == load.key.generation }
        ?.optLong("elapsedRealtimeMs")?.takeIf { it > 0L }
    val failureAt = load.failureElapsedRealtimeMs
    val failureTimeline = JSONObject()
        .put("loadStartedMs", if (loadStartedAt != null) 0L else JSONObject.NULL)
        .put("loadStartedElapsedRealtimeMs", loadStartedAt ?: JSONObject.NULL)
        .put("failureElapsedMs", if (loadStartedAt != null && failureAt != null) (failureAt - loadStartedAt).coerceAtLeast(0L) else JSONObject.NULL)
        .put("failureElapsedRealtimeMs", failureAt ?: JSONObject.NULL)
        .put("stageDurationsMs", JSONObject(phases.toString()))
        .put("events", metricEvents)
        .put("firstFailureTrace", metrics.optJSONArray("firstFailureTrace") ?: JSONArray())
        .put("pathExclusions", phases.optJSONArray("pathExclusions") ?: JSONArray())
        .put("mediaPeriodId", load.mediaPeriodId ?: JSONObject.NULL)
    val decoderMime = load.media3Failure?.let(::media3RendererMime) ?: exo?.videoFormat?.sampleMimeType
    val decoderFallbacks = decoderFallbackHistory(load.media3Failure)
    val decoderHardwareAccelerated = activeVideoDecoder?.let(::decoderHardwareAcceleration)
    val playbackFailure = playbackFailureJson(PlaybackFailureInput(
        failure = load.failureThrowable,
        playbackException = load.media3Failure,
        http = JSONObject(httpTrace.toString()),
        sessionId = playbackDiagnostics.sessionId,
        generation = load.key.generation,
        loadIdentity = loadIdentity,
        engine = activeEngine,
        decoderName = activeVideoDecoder,
        decoderMime = decoderMime,
        hardwareAccelerated = decoderHardwareAccelerated,
        initResult = when {
            activeVideoDecoder != null -> "initialized"
            load.media3Failure?.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "init_failed"
            load.failureThrowable != null -> "not_initialized_or_not_captured"
            else -> null
        },
        fallbackHistory = decoderFallbacks,
        audioProbe = audioProbe,
        audioSelection = JSONObject().put("requestedMode", audioDecodeMode)
            .put("engine", activeEngine).put("support", audioSupport)
            .put("selectedOutput", actualOutput),
        phaseTimeline = failureTimeline,
        nativeVersions = nativeVersions,
        abi = meta.getJSONObject("build").getJSONArray("supportedAbis"),
        hdr = displayHdrTypes?.let(::JSONArray),
        engineSelectionReason = engineSwitchTrace.lastOrNull() ?: "no engine switch recorded; preference=${AppRuntime.prefs?.playerEngine ?: "unknown"}",
        source = if (load.current) "current" else "lastSession",
    ))
    val diagnosticsGap = diagnosticsGap(playbackFailure)
    playbackFailure.put("rendererFailure", phases.optJSONObject("rendererFailure") ?: JSONObject()
        .put("route", "UNKNOWN").put("rendererType", JSONObject.NULL).put("sampleMime", JSONObject.NULL)
        .put("decoderName", JSONObject.NULL).put("trackIdentity", JSONObject.NULL).put("source", JSONObject.NULL))
    return DiagPayload(
        metaJson = meta.toString(2),
        playbackInfoJson = load.playbackInfoJson,
        streamsJson = JSONObject().put("MediaStreams", streams).toString(2),
        tracksJson = JSONObject().put("groups", tracks).toString(2),
        errorsJson = errors.toString(2),
        mpvLog = mpvBackend?.diagnosticLog().orEmpty(),
        appLog = "",
        playbackFailureJson = playbackFailure.toString(2),
        diagnosticsGapJson = diagnosticsGap.toString(2),
        uiText = buildString {
            appendLine("screen=${AppRuntime.screenLabel}")
            appendLine("media=${item.name} / ${item.seriesName ?: "no series"}")
            appendLine("source=${playbackSource?.name ?: load.key.sourceId ?: "unknown"}")
            appendLine("playMethod=${load.playMethod} urlType=${load.urlType}")
            appendLine("showControls=$showControls buffering=$buffering degraded=$degraded")
            appendLine("positionMs=$position durationMs=$duration activeEngine=$activeEngine videoRange=${AppRuntime.currentVideoRange}")
            appendLine("timeline: origin=${timeline.optString("origin", "UNKNOWN")} offset=${timeline.opt("offsetMs") ?: "null"} confidence=${timeline.optString("confidence", "UNMEASURED")} evidence=${timeline.optString("evidence", "no-sample")}")
            appendLine("timeline=${if (progress.optBoolean("timelineUnverified", true)) "unmeasured" else "measured"} locked=${timeline.optBoolean("locked")}")
            appendLine("progress: contentPos=${progress.opt("contentPos") ?: "null"} playerPos=${progress.opt("playerPos") ?: "null"} unverified=${progress.optBoolean("timelineUnverified", true)}")
            appendLine("decoder=${activeVideoDecoder ?: "unknown"} decoderPath=${dvDecoderPath ?: "unknown"}")
            appendLine("phase=$failurePhase phaseTimes=${phaseTimes}")
            appendLine("framesDropped=$droppedFrames framesRendered=$renderedFrames bufferingCount=$bufferingCount bufferingDurationMs=$bufferingDurationMs")
            appendLine("error=${error ?: "none"}")
            appendLine("status=${playbackStatusNotice ?: "none"}")
            appendLine("mpvError=${mpvError ?: "none"}")
            appendLine("mpvHttpHeaders=${mpvState.httpHeadersStatus ?: "not-applied"}")
            appendLine("mpvUnavailableReason=${mpvAvailability.unavailableReason ?: "none"}")
        },
    )
}


/** Called only on IO after the applicationLooper has produced the immutable payload. */
internal fun completePlayerDiagnosticLogs(context: Context, payload: DiagPayload): DiagPayload {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Diagnostic file IO requires background" }
    val previous = readDiagnosticTail(File(context.filesDir, "mpv-previous-abnormal.txt"), 32 * 1024)
    val current = readDiagnosticTail(File(context.filesDir, "mpv-session.log"), 128 * 1024)
    return payload.copy(mpvLog = DiagExporter.sanitize(listOf(previous, current, payload.mpvLog)
        .filter(String::isNotBlank).joinToString("\n\n").ifBlank { "No mpv log messages captured" }))
}

/** Independent of a disposed composition. Snapshots are immutable strings and writes are serialized. */
internal object ExitSessionDiagnostics {
    private val sessions = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lumina-exit-diagnostics").apply { isDaemon = true }
    }
    private data class Snapshot(val directory: () -> File, val json: String)
    private val writes = LatestDiagnosticWrite<Snapshot>({ worker.execute(it) }) { snapshot ->
        val root = snapshot.directory().apply { mkdirs() }
        val safe = boundedDiagnosticJson(LogRedactor.redactJson(snapshot.json), 120 * 1024)
        val temporary = Files.createTempFile(root.toPath(), "last-session-", ".tmp")
        try {
            Files.write(temporary, safe.toByteArray(Charsets.UTF_8))
            try { Files.move(temporary, File(root, "last-session.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, File(root, "last-session.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            PageLog.add("exitDiagnostic writeFailed=${error.javaClass.simpleName}")
            throw error
        } finally { Files.deleteIfExists(temporary) }
    }

    fun newSession(): Long = sessions.incrementAndGet()
    fun enqueue(context: Context, session: Long, frozenJson: String) {
        val app = context.applicationContext
        if (!writes.submit(session, Snapshot({ app.getExternalFilesDir(null) ?: app.filesDir }, frozenJson))) {
            PageLog.add("exitDiagnostic staleSession=$session ignored")
        }
    }
}


/** Environment capture has no player access and leaves log IO to the common bounded export path. */
internal fun environmentDiagnosticPayload(context: Context, outcome: String): DiagPayload {
    val engine = MpvAvailabilityProbe.process.snapshot(context).addTo(JSONObject()
        .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown").put("active", "unknown")
        .put("kernels", JSONArray().put("media3").put("mpv")))
    val unavailable = JSONObject().put("reason", "No playback in progress").toString()
    return DiagPayload(
        metaJson = DiagExporter.baseMeta(context, outcome).put("engine", engine).toString(),
        playbackInfoJson = unavailable, streamsJson = unavailable, tracksJson = unavailable,
        errorsJson = JSONObject().put("reason", AppRuntime.prefs?.lastPlaybackAudioFailure?.ifBlank { null }
            ?: "No playback error recorded").toString(),
        mpvLog = "", appLog = "",
        uiText = "screen=${AppRuntime.screenLabel}\nplaying=${AppRuntime.nowPlaying}\nactiveEngine=unknown\nbuffering=unknown\ndegraded=unknown",
    )
}

/** Both UI entry points use this IO boundary; the ZIP formatter itself remains shared. */
internal suspend fun exportDiagnosticBundle(context: Context, payload: DiagPayload, kind: String): File =
    runInterruptible(Dispatchers.IO) {
        val logs = completePlayerDiagnosticLogs(context, payload)
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(root, "diag").apply { mkdirs() }
        val out = Files.createTempFile(directory.toPath(), "lumina-diag-$kind-", ".zip").toFile()
        fun tail(directory: File, name: String, limit: Int = 120 * 1024): String =
            readDiagnosticTail(File(directory, name), limit)
        fun json(name: String): String {
            val file = File(root, name)
            if (!file.isFile) return "{}"
            // JSON is a structured record, not a line log. Read a bounded prefix, then validate it.
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(120 * 1024 + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                buffer.copyOf(size)
            }
            return boundedDiagnosticJson(LogRedactor.redactJson(bytes.toString(Charsets.UTF_8)), 120 * 1024)
        }
        try {
            val capture = LogcatCapture.read(120 * 1024, 1500L) { resources ->
                resources.process(ProcessBuilder("logcat", "-d", "-v", "epoch", "-t", "800", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true).start()).inputStream
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Diagnostic export cancelled")
            val appLog = AppLog.snapshot() + "\n[logcat] status=${capture.status} truncated=${capture.truncated}\n" +
                LogRedactor.redact(capture.bytes.toString(Charsets.UTF_8))
            val files = linkedMapOf(
                "meta.json" to DiagExporter.withBuildIdentity(context, logs.metaJson), "playback-info.json" to logs.playbackInfoJson,
                "streams.json" to logs.streamsJson, "tracks.json" to logs.tracksJson, "errors.json" to logs.errorsJson,
                "playback-failure.json" to logs.playbackFailureJson, "diagnostics-gap.json" to logs.diagnosticsGapJson,
                "mpv.log" to logs.mpvLog, "app.log" to appLog,
                "mpv-audio.jsonl" to tail(context.filesDir, "mpv-audio.jsonl", 384 * 1024),
                "mpv-audio-system.log" to tail(context.filesDir, "mpv-audio-system.log", 384 * 1024),
                "ui.txt" to logs.uiText,
                "net.json" to NetLog.tail(), "pages.txt" to PageLog.tail(),
                "crash-stats.json" to json("crash-stats.json"), "lastSession.json" to json("last-session.json"),
                "exitReason.json" to json("exit-reason.json"),
                "anr-latest.txt" to tail(root, "anr-latest.txt"), "native-latest.txt" to tail(root, "native-latest.txt"),
            )
            File(context.filesDir, "app-prev.log").takeIf(File::isFile)?.let { files["app-prev.log"] = readDiagnosticTail(it) }
            (1..5).forEach { n -> File(root, "crash-$n.txt").takeIf(File::isFile)?.let { files["crash-$n.txt"] = readDiagnosticTail(it) } }
            File(root, "crash-latest.txt").takeIf(File::isFile)?.let { files["crash-latest.txt"] = readDiagnosticTail(it) }
            // Explicit allowlist above: private raw native protobufs never enter a diagnostic bundle.
            DiagExporter.writeArchive(out, files)
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }

private fun media3RendererMime(error: androidx.media3.common.PlaybackException): String? =
    (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType

private fun decoderFallbackHistory(error: Throwable?): JSONArray {
    val output = JSONArray()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    val queue = ArrayDeque<Any>()
    error?.let(queue::addLast)
    while (queue.isNotEmpty() && output.length() < 16) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        val fallback = reflectedValue(current, "fallbackDecoderInitializationException")
        if (current.javaClass.simpleName.contains("DecoderInitializationException", true)) {
            val codec = reflectedValue(current, "codecInfo")
            val codecName = codec?.let { reflectedValue(it, "name")?.toString() }
            output.put(JSONObject()
                .put("name", codecName ?: JSONObject.NULL)
                .put("mime", reflectedValue(current, "mimeType") ?: JSONObject.NULL)
                .put("hardwareAccelerated", codecName?.let(::decoderHardwareAcceleration) ?: JSONObject.NULL)
            .put("reason", (current as? Throwable)?.message?.let { LogRedactor.redact(it) } ?: JSONObject.NULL))
        }
        (current as? Throwable)?.cause?.let(queue::addLast)
        fallback?.let(queue::addLast)
    }
    return output
}

private fun reflectedValue(instance: Any, property: String): Any? = runCatching {
    val getter = instance.javaClass.methods.firstOrNull {
        it.name == "get${property.replaceFirstChar(Char::uppercase)}" || it.name == "is${property.replaceFirstChar(Char::uppercase)}"
    } ?: return@runCatching null
    getter.invoke(instance)
}.getOrNull()

private fun decoderHardwareAcceleration(name: String): Boolean? {
    if (android.os.Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.name == name }?.isHardwareAccelerated
    }.getOrNull()
}
