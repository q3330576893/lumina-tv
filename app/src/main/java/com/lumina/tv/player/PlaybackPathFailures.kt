package com.lumina.tv.player

/** A failed DTS direct path must not exclude AAC, transcoding, or another source. */
internal data class PlaybackPath(
    val sourceId: String,
    val engine: String,
    val delivery: PlaybackDelivery,
    val audioTrack: String?,
    val audioMode: String,
    val requestVariant: Int = 0,
)

internal enum class PlaybackDelivery { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }
internal enum class PlaybackFailureReason {
    INITIALIZATION, SURFACE, HTTP, DECODING, AUDIO_OUTPUT, PARSING, FIRST_FRAME_TIMEOUT, WATCHDOG, LOAD,
}

internal data class PlaybackPathFailure(
    val path: PlaybackPath,
    val reason: PlaybackFailureReason,
    val generation: Int,
    val detail: String,
)

internal class PlaybackPathFailures {
    private val failures = linkedSetOf<PlaybackPathFailure>()
    fun record(failure: PlaybackPathFailure) { failures += failure }
    fun excludes(path: PlaybackPath): Boolean = failures.any { it.path == path }
    fun snapshot(): List<PlaybackPathFailure> = failures.toList()
    fun clearForManualRetry() = failures.clear()
}

/** Every preference is an ordering hint; none can bypass a failed playback path. */
internal fun selectPlaybackEngine(
    preference: String?,
    pinned: String?,
    isDvP7: Boolean,
    isDolbyVision: Boolean,
    path: PlaybackPath,
    failures: PlaybackPathFailures,
): String? {
    val preferred = pinned ?: when {
        isDvP7 -> "mpv"
        isDolbyVision -> "media3"
        path.audioMode == "soft" -> "mpv"
        preference == "mpv" -> "mpv"
        else -> "media3"
    }
    return listOf(preferred, "media3", "mpv").distinct()
        .firstOrNull { it in setOf("media3", "mpv") && !failures.excludes(path.copy(engine = it)) }
}
