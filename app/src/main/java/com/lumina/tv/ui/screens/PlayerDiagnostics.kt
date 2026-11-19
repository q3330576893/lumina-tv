package com.lumina.tv.ui.screens

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.player.MpvPlayerController
import com.lumina.tv.player.PlaybackDiagnostics
import com.lumina.tv.player.mpvAvailabilityDiagnostic
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
    mpvBackend: MpvPlayerController?,
    item: MediaItem,
    playbackSource: MediaSource?,
    currentPlaybackUrl: String?,
    audioDecodeMode: String,
    audioSupport: String,
    degraded: Boolean,
    engineSwitchTrace: List<String>,
    exo: ExoPlayer,
    failurePhase: String,
    lastPlaybackErrorJson: String?,
    mpvState: MpvPlayerController.State,
    playbackInfoJson: String?,
    showControls: Boolean,
    buffering: Boolean,
    error: String?,
    position: Long,
    activeVideoDecoder: String?,
    dvDecoderPath: String?,
    playbackStatusNotice: String?,
    negotiationDiagnostics: JSONObject,
    rangeProbeDiagnostics: JSONObject,
    phaseTimes: JSONObject,
    droppedFrames: Long,
    renderedFrames: Long,
    bufferingCount: Int,
    bufferingDurationMs: Long,
    currentSourceId: String?,
    currentPlayMethod: String,
    duration: Long,
    playbackDiagnostics: PlaybackDiagnostics,
    selectedAudioStreamIndex: Int?,
): DiagPayload {
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
    val timeline = phases.optJSONObject("timeline") ?: JSONObject()
    val progress = phases.optJSONObject("progress") ?: JSONObject().put("timelineUnverified", true)
    val readback = if (activeEngine == "mpv") mpvBackend?.diagnosticReadback(playbackDiagnostics.identity).orEmpty() else emptyMap()
    val actualDecoder = playbackDiagnostics.actualDecoder(activeEngine, readback)
    val actualOutput = playbackDiagnostics.actualAudioOutput(activeEngine, readback)
    val displayHdrTypes = readDisplayHdrTypes(context)
    val actualAudioIndex = if (activeEngine == "mpv") mpvBackend?.currentAudioEmbyIndex() else selectedAudioStreamIndex
    val audioStream = playbackSource?.audioStreams?.firstOrNull { it.index == actualAudioIndex }
    if (activeEngine == "mpv") listOf("decoderInitMs", "compatMappingMs", "firstFrameMs", "stableOutputMs").forEach { key ->
        if (phases.isNull(key)) phases.put(key, "N/A")
    }
    val meta = DiagExporter.baseMeta(context, outcome)
        .put("load", playbackDiagnostics.identityJson())
        .put("display", JSONObject().put("hdr", displayHdrDiagnostic(displayHdrTypes)?.let(::JSONArray) ?: JSONObject.NULL))
        .put("engine", JSONObject()
            .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown")
            .put("active", activeEngine)
            .put("mpvAvailable", mpvAvailabilityDiagnostic(mpvBackend != null, mpvBackend?.unavailableReason))
            .put("mpvUnavailableReason", mpvBackend?.unavailableReason ?: JSONObject.NULL)
            .put("mpvProperties", JSONObject(mpvBackend?.diagnosticProperties().orEmpty()))
            .put("liveVideoDecoders", PlayerLifecycle.registry.liveVideoDecoders)
            .put("videoDecoderCountScope", "media3_callbacks;mpv_lifecycle_only")
            .put("kernels", JSONArray().put("media3").put("mpv")))
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
        .put("playback", JSONObject()
            .put("urlType", currentPlaybackUrl?.let {
                when { it.contains(".m3u8", true) -> "hls"; else -> "static" }
            } ?: "none").put("negotiation", negotiationDiagnostics)
            .put("rangeProbe", rangeProbeDiagnostics)
            .put("phasesMs", phases)
            .put("timeline", timeline)
            .put("progress", progress)
            .put("frameStability", JSONObject().put("droppedFrames", droppedFrames)
                .put("framesWithProcessingOffset", renderedFrames).put("bufferingCount", bufferingCount)
                .put("bufferingDurationMs", bufferingDurationMs)
                .put("sourceFps", videoStream?.frameRate ?: JSONObject.NULL)
                .put("firstFrameLatencyMs", phases.opt("firstFrameMs"))))
        .put("audioDecode", JSONObject()
            .put("load", playbackDiagnostics.identityJson())
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
                .put("selectedEngine", activeEngine).put("negotiatedPlayMethod", currentPlayMethod))
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
        exo.currentTracks.groups.forEach { group ->
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
    val errors = JSONObject()
        .put("engine", activeEngine)
        .put("failurePhase", failurePhase)
        .put("media3", lastPlaybackErrorJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        .put("decoderName", activeVideoDecoder ?: JSONObject.NULL)
        .put("dolbyVision", isDolbyVision)
        .put("dvProfile", videoStream?.dvProfile ?: JSONObject.NULL)
        .put("videoRangeType", videoStream?.videoRangeType ?: JSONObject.NULL)
        .put("profile", videoStream?.profile ?: JSONObject.NULL)
        .put("displayTitle", videoStream?.displayTitle ?: JSONObject.NULL)
        .put("externalLinks", runCatching { JSONObject(TmdbClient.externalLinkDiagnosticSnapshot()) }.getOrNull() ?: JSONObject.NULL)
        .put("decoderPath", dvDecoderPath ?: JSONObject.NULL)
        .put("lastPlaybackErrorCodeName", lastPlaybackErrorJson?.let {
            runCatching { JSONObject(it).optString("errorCodeName") }.getOrNull()
        } ?: JSONObject.NULL)
        .put("hasPlaybackError", error != null)
        .put("hasPlaybackNotice", playbackStatusNotice != null)
        .put("finalReason", error ?: playbackStatusNotice ?: JSONObject.NULL)
        .put("mpv", mpvState.error ?: "No mpv error recorded")
        .put("mpvErrorPresent", mpvState.error != null)
        .put("mpvSoftwareRetryCount", mpvBackend?.softwareRetryCount() ?: 0)
        .put("audioDecodeFailure", AppRuntime.prefs?.lastPlaybackAudioFailure ?: "")
        .put("metrics", playbackDiagnostics.snapshot(android.os.SystemClock.elapsedRealtime())
            .put("droppedFrames", droppedFrames + (mpvBackend?.diagnosticProperties()?.get("vo-drop-frame-count")?.toLongOrNull() ?: 0L))
            .put("droppedFramesScope", "screen-session"))
    return DiagPayload(
        metaJson = meta.toString(2),
        playbackInfoJson = playbackInfoJson ?: JSONObject().put("reason", "PlaybackInfo was not captured").toString(2),
        streamsJson = JSONObject().put("MediaStreams", streams).toString(2),
        tracksJson = JSONObject().put("groups", tracks).toString(2),
        errorsJson = errors.toString(2),
        mpvLog = mpvBackend?.diagnosticLog()?.ifBlank { null }
            ?: runCatching {
                val previous = File(context.filesDir, "mpv-previous-abnormal.txt").takeIf(File::exists)?.readText(Charsets.UTF_8).orEmpty()
                val current = File(context.filesDir, "mpv-session.log").takeIf(File::exists)?.readText(Charsets.UTF_8).orEmpty()
                listOf(previous, current).filter(String::isNotBlank).joinToString("\n\n")
            }.getOrNull()?.ifBlank { "No mpv log messages captured" }
            ?: "mpv log unavailable: no session log file",
        appLog = "",
        uiText = buildString {
            appendLine("screen=${AppRuntime.screenLabel}")
            appendLine("media=${item.name} / ${item.seriesName ?: "no series"}")
            appendLine("source=${playbackSource?.name ?: currentSourceId ?: "unknown"}")
            appendLine("playMethod=$currentPlayMethod urlType=${currentPlaybackUrl?.let { if (it.contains(".m3u8", true)) "hls" else "static" } ?: "none"}")
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
            appendLine("mpvError=${mpvState.error ?: "none"}")
            appendLine("mpvUnavailableReason=${mpvBackend?.unavailableReason ?: "none"}")
        },
    )
}
