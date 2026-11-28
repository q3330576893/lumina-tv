package com.lumina.tv.player

import org.json.JSONObject

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

internal fun playbackPathJson(path: PlaybackPath): JSONObject = JSONObject()
    .put("sourceId", path.sourceId).put("engine", path.engine).put("delivery", path.delivery.name)
    .put("audioTrack", path.audioTrack ?: JSONObject.NULL).put("audioMode", path.audioMode)
    .put("requestVariant", path.requestVariant)

/** The action must enter the regular physical reload pipeline, never just dismiss the stall. */
internal fun retryAvailablePath(path: PlaybackPath, failures: PlaybackPathFailures, reload: () -> Unit): Boolean {
    if (failures.excludes(path)) return false
    reload()
    return true
}
internal enum class PlaybackFailureReason {
    INITIALIZATION, SURFACE, HTTP, DECODING, AUDIO_OUTPUT, PARSING, FIRST_FRAME_TIMEOUT, WATCHDOG, LOAD, UNKNOWN,
}

internal data class PlaybackPathFailure(
    val path: PlaybackPath,
    val reason: PlaybackFailureReason,
    val generation: Int,
    val detail: String,
    val attemptId: String = generation.toString(),
    val timeMs: Long = System.nanoTime() / 1_000_000L,
    val evidence: String = detail,
    val unrecoverable: Boolean = false,
)

internal typealias FailureObservation = PlaybackPathFailure

internal data class PathAvailability(
    val path: PlaybackPath,
    val excluded: Boolean,
    val failures: Int,
    val reason: PlaybackFailureReason? = null,
    val excludedAtMs: Long? = null,
)

internal data class PathExclusion(val observation: FailureObservation, val remainingAvailablePaths: Int, val manualAttempt: Int)

internal class PlaybackPathFailures {
    private val failures = mutableListOf<FailureObservation>()
    private val current = mutableListOf<FailureObservation>()
    private val knownPaths = linkedSetOf<PlaybackPath>()
    private val excluded = linkedMapOf<PlaybackPath, FailureObservation>()
    private val exclusions = mutableListOf<PathExclusion>()
    private var manualAttempt = 0

    fun registerPaths(paths: Collection<PlaybackPath>) { knownPaths += paths }

    /** Observing a stall does not spend its one permitted same-path retry. */
    fun record(failure: FailureObservation) {
        knownPaths += failure.path
        if (current.any { it.path == failure.path && it.reason == failure.reason && it.attemptId == failure.attemptId }) return
        failures += failure
        current += failure
        val count = current.count { it.path == failure.path && it.reason == failure.reason }
        val limit = when (failure.reason) {
            PlaybackFailureReason.WATCHDOG, PlaybackFailureReason.FIRST_FRAME_TIMEOUT, PlaybackFailureReason.UNKNOWN -> 2
            // These callers have already exhausted protocol/audio recovery or supplied a hard error.
            else -> 1
        }
        val fatalEvidence = failure.unrecoverable && failure.reason in setOf(
            PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.DECODING)
        if ((fatalEvidence || count >= limit) && failure.path !in excluded) {
            excluded[failure.path] = failure
            exclusions += PathExclusion(failure, knownPaths.count { it !in excluded }, manualAttempt)
        }
    }

    fun recordEngineFailure(failure: PlaybackPathFailure) {
        // Decoder evidence belongs to the actual track/delivery path; never poison another path.
        record(failure)
    }
    fun availability(path: PlaybackPath): PathAvailability = PathAvailability(path, path in excluded,
        current.count { it.path == path }, excluded[path]?.reason, excluded[path]?.timeMs)
    fun excludes(path: PlaybackPath): Boolean = availability(path).excluded
    fun snapshot(): List<PlaybackPathFailure> = failures.toList()
    fun exclusionHistory(): List<PathExclusion> = exclusions.toList()
    fun clearForManualRetry() { current.clear(); excluded.clear(); manualAttempt++ }
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
    audioUndecodable: Boolean = false,
): Boolean = failure == PlaybackFailureReason.AUDIO_OUTPUT && path.engine == "media3" &&
    mpvAvailable && (mpvExplicitlyRequested || audioUndecodable) &&
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
    failures.registerPaths(listOfNotNull(path.copy(engine = "media3"),
        path.copy(engine = "mpv").takeIf { mpvRequested && mpvAvailable }))
    if (mpvRequested && mpvAvailable && !failures.excludes(path.copy(engine = "mpv"))) return "mpv"
    return "media3".takeUnless { failures.excludes(path.copy(engine = it)) }
}
