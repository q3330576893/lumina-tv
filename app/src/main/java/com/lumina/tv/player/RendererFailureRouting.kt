package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import org.json.JSONObject

internal enum class RendererFailureRoute { AUDIO, VIDEO, TEXT, HTTP, TLS, AUTH, PARSING, UNKNOWN }

internal data class RendererFailureEvidence(
    val rendererType: String?,
    val sampleMime: String?,
    val decoderName: String?,
    val trackIdentity: String?,
    val source: String?,
    val audioTrackUnsupported: Boolean,
)

internal data class RendererFailure(val route: RendererFailureRoute, val evidence: RendererFailureEvidence) {
    fun json(): JSONObject = JSONObject().put("route", route.name)
        .put("rendererType", evidence.rendererType ?: JSONObject.NULL)
        .put("sampleMime", evidence.sampleMime ?: JSONObject.NULL)
        .put("decoderName", evidence.decoderName ?: JSONObject.NULL)
        .put("trackIdentity", evidence.trackIdentity ?: JSONObject.NULL)
        .put("source", evidence.source ?: JSONObject.NULL)
        .put("audioTrackUnsupported", evidence.audioTrackUnsupported)
}

/** Use positive renderer/track evidence, never the support status of an unrelated audio track. */
internal fun classifyRendererFailure(
    error: PlaybackException,
    source: String? = null,
    audioSupportUnsupported: Boolean = false,
    externalSubtitle: Boolean = false,
): RendererFailure {
    val causes = playbackErrorCauses(error).toList()
    val renderers = causes.filterIsInstance<ExoPlaybackException>().filter { it.type == ExoPlaybackException.TYPE_RENDERER }
    val decoderInit = causes.filterIsInstance<DecoderInitializationException>().firstOrNull()
    val decoder = causes.filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo
    val formats = renderers.mapNotNull { it.rendererFormat }
    val mimes = formats.mapNotNull { it.sampleMimeType } + listOfNotNull(decoderInit?.mimeType, decoder?.mimeType)
    val names = renderers.mapNotNull { it.rendererName }
    val families = linkedSetOf<RendererFailureRoute>()
    for (mime in mimes) when {
        mime.startsWith("audio/") -> families += RendererFailureRoute.AUDIO
        mime.startsWith("video/") -> families += RendererFailureRoute.VIDEO
        mime.startsWith("text/") || mime in setOf("application/ttml+xml", "application/x-subrip", "application/cea-608", "application/cea-708", "application/dvbsubs", "application/pgs") -> families += RendererFailureRoute.TEXT
    }
    for (name in names) when {
        name.contains("Audio", true) -> families += RendererFailureRoute.AUDIO
        name.contains("Video", true) -> families += RendererFailureRoute.VIDEO
        name.contains("Text", true) || name.contains("Subtitle", true) -> families += RendererFailureRoute.TEXT
    }
    val playbackErrors = causes.filterIsInstance<PlaybackException>()
    if (playbackErrors.any { it.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            it.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED } ||
        causes.any { it is AudioSink.InitializationException || it is AudioSink.WriteException ||
            it is AudioSink.ConfigurationException }) families += RendererFailureRoute.AUDIO
    if (causes.any { it is MediaCodecVideoDecoderException }) families += RendererFailureRoute.VIDEO
    if (externalSubtitle || formats.any { it.id?.startsWith(EXTERNAL_SUBTITLE_ID_PREFIX) == true }) families += RendererFailureRoute.TEXT
    val category = classifyPlaybackError(error).category
    val route = when {
        families.size > 1 -> RendererFailureRoute.UNKNOWN
        families.size == 1 -> families.single()
        category == PlaybackErrorCategory.CERT_INVALID -> RendererFailureRoute.TLS
        category == PlaybackErrorCategory.AUTH_FAILED -> RendererFailureRoute.AUTH
        category == PlaybackErrorCategory.PARSING -> RendererFailureRoute.PARSING
        category in setOf(PlaybackErrorCategory.NETWORK_TRANSIENT, PlaybackErrorCategory.HTTP_PERMISSION,
            PlaybackErrorCategory.HTTP_CONTENT_INVALID, PlaybackErrorCategory.SOURCE_GONE) ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> RendererFailureRoute.HTTP
        else -> RendererFailureRoute.UNKNOWN
    }
    return RendererFailure(route, RendererFailureEvidence(names.firstOrNull(), mimes.firstOrNull(),
        decoderInit?.codecInfo?.name ?: decoder?.name, formats.firstOrNull()?.id, source,
        audioSupportUnsupported && route == RendererFailureRoute.AUDIO && mimes.any { it.startsWith("audio/") }))
}
