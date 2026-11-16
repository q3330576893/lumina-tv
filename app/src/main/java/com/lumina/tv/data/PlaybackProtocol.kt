package com.lumina.tv.data

enum class PlaybackMode { DirectPlay, DirectStream, Transcode }

/** CONTENT is only used for the explicit static original-file route. */
enum class TimelineOrigin { CONTENT, UNKNOWN }

internal fun normalizeContainer(container: String?): String? = when (val value = container?.trim()?.lowercase()) {
    null, "" -> null
    "matroska", "mkv" -> "mkv"
    "mpegts", "mpeg-ts", "m2ts", "mts", "ts" -> "ts"
    "mpeg4", "mpeg-4", "mp4" -> "mp4"
    "quicktime", "mov" -> "mov"
    else -> value
}

/** Includes every input that may be embedded in a negotiated URL. Consumed once per playback. */
internal data class PlaybackRequestKey(
    val itemId: String,
    val forceTranscode: Boolean,
    val sourceId: String?,
    val audioStreamIndex: Int?,
    val forceDirect: Boolean,
    val maxStreamingBitrate: Long,
    val strategy: SourceStrategy,
    val startIntent: PlaybackStartIntent,
    val requestedPositionMs: Long,
    val manualAudioSelection: Boolean,
    val removeSubtitleParams: Boolean,
    val preferDirectStreamUrl: Boolean,
)
