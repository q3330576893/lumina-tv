package com.lumina.tv.ui.screens

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.player.MpvPlayerController
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
): DiagPayload {
    val meta = DiagExporter.baseMeta(context, outcome)
        .put("engine", JSONObject()
            .put("prefs", AppRuntime.prefs?.playerEngine ?: "unknown")
            .put("active", activeEngine)
            .put("mpvAvailable", mpvBackend?.unavailableReason == null && mpvBackend != null)
            .put("kernels", JSONArray().put("media3").put("mpv")))
        .put("media", JSONObject()
            .put("id", item.id).put("name", item.name).put("seriesName", item.seriesName ?: JSONObject.NULL)
            .put("container", playbackSource?.container ?: JSONObject.NULL)
            .put("size", playbackSource?.size ?: 0L)
            .put("isStrm", playbackSource?.container?.contains("strm", true) == true))
        .put("playback", JSONObject().put("url", currentPlaybackUrl ?: JSONObject.NULL))
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
                    .put("id", f.id ?: JSONObject.NULL).put("label", f.label ?: JSONObject.NULL)
                    .put("language", f.language ?: JSONObject.NULL).put("sampleMimeType", f.sampleMimeType ?: JSONObject.NULL)
                    .put("codecs", f.codecs ?: JSONObject.NULL).put("bitrate", f.bitrate)
                    .put("width", f.width).put("height", f.height).put("sampleRate", f.sampleRate)
                    .put("channelCount", f.channelCount).put("selected", group.isTrackSelected(i))
                    .put("supported", group.isTrackSupported(i)))
            }
            put(JSONObject().put("type", group.type).put("length", group.length).put("formats", formats))
        }
    }
    val errors = JSONObject()
        .put("failurePhase", failurePhase)
        .put("media3", lastPlaybackErrorJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        .put("mpv", mpvState.error ?: "No mpv error recorded")
        .put("mpvSoftwareRetryCount", mpvBackend?.softwareRetryCount() ?: 0)
        .put("audioDecodeFailure", AppRuntime.prefs?.lastPlaybackAudioFailure ?: "")
    return DiagPayload(
        metaJson = meta.toString(2),
        playbackInfoJson = playbackInfoJson ?: JSONObject().put("reason", "PlaybackInfo was not captured").toString(2),
        streamsJson = JSONObject().put("MediaStreams", streams).toString(2),
        tracksJson = JSONObject().put("groups", tracks).toString(2),
        errorsJson = errors.toString(2),
        mpvLog = mpvBackend?.diagnosticLog()?.ifBlank { "No mpv log messages captured" } ?: "mpv log unavailable: mpv backend was not active",
        appLog = DiagExporter.appLogTail(),
        uiText = "showControls=$showControls\nbuffering=$buffering\nerror=${error ?: "none"}\npositionMs=$position\nactiveEngine=$activeEngine\ndegraded=$degraded",
    )
}
