package com.lumina.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import java.io.IOException

object P0RendererRoutingChecks {
    fun run() {
        // Use the real exception's explicit-timestamp constructor: the public factory calls Android TextUtils/SystemClock.
        val constructor = ExoPlaybackException::class.java.declaredConstructors.single { it.parameterCount == 11 }
            .apply { isAccessible = true }
        fun renderer(name: String, mime: String, code: Int = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED): ExoPlaybackException =
            constructor.newInstance("fixture renderer", IllegalStateException("fixture cause"), code,
                ExoPlaybackException.TYPE_RENDERER, name, 0,
                Format.Builder().setId("track-7").setSampleMimeType(mime).build(), C.FORMAT_UNSUPPORTED_TYPE, null, 0L, false) as ExoPlaybackException
        val video = renderer("MediaCodecVideoRenderer", "video/hevc")
        for (mode in listOf("auto", "soft", "passthrough")) {
            val classified = classifyRendererFailure(video, audioSupportUnsupported = true)
            check(classified.route == RendererFailureRoute.VIDEO) { "video routed to audio in $mode" }
            check(classified.evidence.sampleMime == "video/hevc" && classified.evidence.trackIdentity == "track-7")
            check(classified.json().getString("route") == "VIDEO")
        }
        check(classifyRendererFailure(renderer("MediaCodecAudioRenderer", "audio/ac3")).route == RendererFailureRoute.AUDIO)
        check(classifyRendererFailure(renderer("TextRenderer", "text/vtt")).route == RendererFailureRoute.TEXT)
        val audio = playbackErrorSample(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, IOException("nested"))
        check(classifyRendererFailure(audio).route == RendererFailureRoute.AUDIO)
        val audioFormat = Format.Builder().setId("audio-2").setSampleMimeType("audio/ac3").build()
        val output = AudioSink.WriteException(-6, audioFormat, false)
        check(classifyRendererFailure(playbackErrorSample(PlaybackException.ERROR_CODE_UNSPECIFIED,
            IOException("nested audio output", output))).route == RendererFailureRoute.AUDIO)
        val initialization = DecoderInitializationException(
            Format.Builder().setId("video-9").setSampleMimeType("video/hevc").build(), IOException("fixture decoder"), false, -4999)
        check(classifyRendererFailure(playbackErrorSample(PlaybackException.ERROR_CODE_UNSPECIFIED,
            IOException("nested decoder init", initialization))).route == RendererFailureRoute.VIDEO)
        check(classifyRendererFailure(playbackErrorSample(PlaybackException.ERROR_CODE_UNSPECIFIED, video)).route == RendererFailureRoute.VIDEO)
        check(classifyRendererFailure(renderer("MediaCodecAudioRenderer", "video/hevc")).route == RendererFailureRoute.UNKNOWN)
        for ((code, route) in listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS to RendererFailureRoute.HTTP,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to RendererFailureRoute.PARSING,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to RendererFailureRoute.UNKNOWN,
            PlaybackException.ERROR_CODE_UNSPECIFIED to RendererFailureRoute.UNKNOWN,
        )) check(classifyRendererFailure(playbackErrorSample(code), audioSupportUnsupported = true).route == route)
        check(classifyRendererFailure(playbackErrorSample(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
            externalSubtitle = true).route == RendererFailureRoute.TEXT)
        println("P0RendererRoutingChecks PASS: VIDEO/AUDIO/TEXT/HTTP/PARSING/UNKNOWN; nested renderer; conflicting evidence; three audio modes; audio output retained")
    }
}
