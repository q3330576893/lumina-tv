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
    phaseTimes: JSONObject,
    droppedFrames: Long,
    renderedFrames: Long,
    bufferingCount: Int,
    bufferingDurationMs: Long,
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
    if (activeEngine == "mpv") listOf("decoderInitMs", "compatMappingMs", "firstFrameMs", "stableOutputMs").forEach { key ->
        if (phases.isNull(key)) phases.put(key, "N/A")
    }
    val meta = DiagExporter.baseMeta(context, outcome)
        .put("engine", JSONObject()
            .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown")
            .put("active", activeEngine)
            .put("mpvAvailable", mpvBackend?.unavailableReason == null && mpvBackend != null)
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
        .put("playback", JSONObject().put("url", currentPlaybackUrl ?: JSONObject.NULL)
            .put("urlType", currentPlaybackUrl?.let {
                when { it.contains(".m3u8", true) -> "hls"; else -> "static" }
            } ?: "none").put("negotiation", negotiationDiagnostics)
            .put("phasesMs", phases)
            .put("frameStability", JSONObject().put("droppedFrames", droppedFrames)
                .put("framesWithProcessingOffset", renderedFrames).put("bufferingCount", bufferingCount)
                .put("bufferingDurationMs", bufferingDurationMs)
                .put("sourceFps", videoStream?.frameRate ?: JSONObject.NULL)
                .put("firstFrameLatencyMs", phases.opt("firstFrameMs"))))
        .put("audioDecode", JSONObject()
            .put("audioDecodeMode", audioDecodeMode)
            .put("audioDecodePath", when {
                audioDecodeMode == "passthrough" -> "passthrough"
                activeEngine == "mpv" -> "local-soft-mpv"
                else -> "direct"
            })
            .put("audioCodec", playbackSource?.audioStreams?.firstOrNull { it.isDefault }?.codec ?: playbackSource?.audioStreams?.firstOrNull()?.codec ?: JSONObject.NULL)
            .put("channels", playbackSource?.audioStreams?.firstOrNull { it.isDefault }?.channels ?: playbackSource?.audioStreams?.firstOrNull()?.channels ?: JSONObject.NULL)
            .put("transcoded", playbackSource?.let { !it.supportsDirectPlay || degraded } ?: false))
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
    return DiagPayload(
        metaJson = meta.toString(2),
        playbackInfoJson = playbackInfoJson ?: JSONObject().put("reason", "PlaybackInfo was not captured").toString(2),
        streamsJson = JSONObject().put("MediaStreams", streams).toString(2),
        tracksJson = JSONObject().put("groups", tracks).toString(2),
        errorsJson = errors.toString(2),
        mpvLog = mpvBackend?.diagnosticLog()?.ifBlank { null }
            ?: runCatching { File(context.filesDir, "mpv-session.log").takeIf(File::exists)?.readText(Charsets.UTF_8) }
                .getOrNull()?.ifBlank { "No mpv log messages captured" }
            ?: "mpv log unavailable: no session log file",
        appLog = DiagExporter.appLogTail(),
        uiText = buildString {
            appendLine("screen=${AppRuntime.screenLabel}")
            appendLine("media=${item.name} / ${item.seriesName ?: "no series"}")
            appendLine("source=${playbackSource?.name ?: currentSourceId ?: "unknown"}")
            appendLine("playMethod=$currentPlayMethod urlType=${currentPlaybackUrl?.let { if (it.contains(".m3u8", true)) "hls" else "static" } ?: "none"}")
            appendLine("showControls=$showControls buffering=$buffering degraded=$degraded")
            appendLine("positionMs=$position durationMs=$duration activeEngine=$activeEngine videoRange=${AppRuntime.currentVideoRange}")
            appendLine("decoder=${activeVideoDecoder ?: "unknown"} decoderPath=${dvDecoderPath ?: "unknown"}")
            appendLine("phase=$failurePhase phaseTimes=${phaseTimes}")
            appendLine("framesDropped=$droppedFrames framesRendered=$renderedFrames bufferingCount=$bufferingCount bufferingDurationMs=$bufferingDurationMs")
            appendLine("error=${error ?: "none"}")
            appendLine("status=${playbackStatusNotice ?: "none"}")
            appendLine("mpvError=${mpvState.error ?: "none"}")
        },
    )
}
