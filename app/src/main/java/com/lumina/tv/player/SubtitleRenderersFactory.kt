package com.lumina.tv.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.DefaultRenderersFactory

@UnstableApi
class SubtitleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    var subtitleOffsetMs: Long = 0L

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
        if (renderer.trackType == C.TRACK_TYPE_TEXT) OffsetTextRenderer(renderer) { subtitleOffsetMs } else renderer
    }.toTypedArray()
}

@UnstableApi
private class OffsetTextRenderer(
    private val delegate: Renderer,
    private val offsetMs: () -> Long,
) : Renderer by delegate {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(positionUs - offsetMs() * 1000L, elapsedRealtimeUs)
    }

    override fun release() = delegate.release()
}
