package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioCapabilities as Media3AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Wires the observed platform formats into the actual Media3 sink and preserves subtitle timing. */
@UnstableApi
internal class AudioOutputRenderersFactory(
    context: Context,
    private val audioMode: () -> String,
) : DefaultRenderersFactory(context) {
    @Volatile var subtitleOffsetMs: Long = 0L

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(Media3AudioCapabilities.getCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return object : AudioSink by sink {
            override fun getFormatSupport(format: Format): Int =
                if (avoidDirectAudio(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else sink.getFormatSupport(format)

            override fun supportsFormat(format: Format): Boolean =
                getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

            private fun avoidDirectAudio(format: Format): Boolean =
                media3DirectOutputDisabled(audioMode(), format.sampleMimeType)
        }
    }

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = super.createRenderers(
        eventHandler,
        videoRendererEventListener,
        audioRendererEventListener,
        textRendererOutput,
        metadataRendererOutput,
    ).map { renderer ->
        if (renderer.trackType == C.TRACK_TYPE_TEXT) OffsetAudioSubtitleRenderer(renderer) { subtitleOffsetMs }
        else renderer
    }.toTypedArray()
}

@UnstableApi
private class OffsetAudioSubtitleRenderer(
    private val delegate: Renderer,
    private val offsetMs: () -> Long,
) : Renderer by delegate {
    private var frameTick = 0

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offset = offsetMs()
        if (offset == 0L) {
            delegate.render(positionUs, elapsedRealtimeUs)
            frameTick = 0
            return
        }
        if (frameTick == 0) delegate.render(positionUs - offset * 1000L, elapsedRealtimeUs)
        frameTick = (frameTick + 1) % 3
    }

    override fun release() = delegate.release()
}
