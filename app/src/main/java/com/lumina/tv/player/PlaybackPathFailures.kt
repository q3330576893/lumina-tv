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

internal fun mpvWasExplicitlyRequested(preference: String?, pinned: String?): Boolean =
    (pinned ?: preference) == "mpv"

/** mpv enters the list only after a user choice. */
internal fun selectPlaybackEngine(
    preference: String?,
    pinned: String?,
    path: PlaybackPath,
    failures: PlaybackPathFailures,
    mpvAvailable: Boolean,
): String? {
    val mpvRequested = mpvWasExplicitlyRequested(preference, pinned)
    if (mpvRequested && mpvAvailable && !failures.excludes(path.copy(engine = "mpv"))) return "mpv"
    return "media3".takeUnless { failures.excludes(path.copy(engine = it)) }
}
