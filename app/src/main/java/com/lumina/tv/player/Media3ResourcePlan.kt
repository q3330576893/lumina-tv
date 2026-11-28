package com.lumina.tv.player

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.lumina.tv.data.MediaSource
import com.lumina.tv.ui.screens.M3oBufferPlan
import com.lumina.tv.ui.screens.m3oBufferPlan

/** Constructed only after metadata resolution and old decoder retirement. Values are unchanged. */
internal data class Media3ResourcePlan(val sourceId: String, val videoBitrateBps: Long, val totalMemMb: Int, val buffer: M3oBufferPlan) {
    companion object {
        fun resolve(source: MediaSource, totalMemMb: Int, fastStart: Boolean, retirement: RetirementPermit): Media3ResourcePlan {
            check(retirement.isValid()) { "Metadata cannot select resources while an old decoder is retiring" }
            val bitrate = source.streams.firstOrNull { it.type.equals("Video", true) }?.bitRate?.takeIf { it > 0 }
                ?: source.bitrate?.takeIf { it > 0 } ?: 0L
            return Media3ResourcePlan(source.id, bitrate, totalMemMb, m3oBufferPlan(totalMemMb, bitrate > 25_000_000L, fastStart))
        }
    }
}

/** Pass this exact factory to BOTH ExoPlayer.Builder and createMediaSource. */
internal class Media3SourceWiring(dataSourceFactory: DataSource.Factory) {
    val extractors = configuredPlaybackExtractors()
    val factory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
}

internal fun configuredPlaybackExtractors() = DefaultExtractorsFactory()
    .setConstantBitrateSeekingEnabled(true)
    .setConstantBitrateSeekingAlwaysEnabled(true)
