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
    private val engineFailures = linkedSetOf<Triple<String, String, PlaybackFailureReason>>()
    private val engineWideFailureReasons = setOf(
        PlaybackFailureReason.LOAD,
        PlaybackFailureReason.HTTP,
        PlaybackFailureReason.INITIALIZATION,
        PlaybackFailureReason.DECODING,
    )
    fun record(failure: PlaybackPathFailure) { failures += failure }
    fun recordEngineFailure(failure: PlaybackPathFailure) {
        require(failure.reason in engineWideFailureReasons)
        failures += failure
        engineFailures += Triple(failure.path.sourceId, failure.path.engine, failure.reason)
    }
    fun excludes(path: PlaybackPath): Boolean = failures.any { failure ->
        failure.path == path
    } || engineFailures.any { (sourceId, engine, _) -> sourceId == path.sourceId && engine == path.engine }
    fun snapshot(): List<PlaybackPathFailure> = failures.toList()
    fun clearForManualRetry() { failures.clear(); engineFailures.clear() }
}

internal fun mpvWasExplicitlyRequested(preference: String?, pinned: String?): Boolean =
    (pinned ?: preference) == "mpv"

/** A Media3 audio-output failure may trigger one automatic mpv recovery attempt. */
internal fun shouldAutomaticallyTryMpvAudioFallback(
    failure: PlaybackFailureReason,
    path: PlaybackPath,
    failures: PlaybackPathFailures,
    mpvAvailable: Boolean,
    mpvExplicitlyRequested: Boolean,
): Boolean = failure == PlaybackFailureReason.AUDIO_OUTPUT && path.engine == "media3" &&
    mpvAvailable && mpvExplicitlyRequested &&
    !failures.excludes(path.copy(engine = "mpv"))

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
