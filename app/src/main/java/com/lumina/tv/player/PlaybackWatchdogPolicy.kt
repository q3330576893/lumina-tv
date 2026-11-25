package com.lumina.tv.player

/** Snapshot of the already-sampled signals used by the watchdog's pure decision policy. */
internal data class PlaybackWatchdogInput(
    val positionMs: Long,
    val durationMs: Long,
    val positionAgeMs: Long,
    val bufferedAgeMs: Long?,
    val stateAgeMs: Long,
    val bytesUnchangedForMs: Long,
    val loadingAgeMs: Long,
    val buffering: Boolean,
    val ready: Boolean,
    val loading: Boolean,
    val downloadInProgress: Boolean,
    val mpvLoad: Boolean,
    val playWhenReady: Boolean,
    val seeking: Boolean,
    val startupComplete: Boolean,
    val suppressed: Boolean,
)

internal fun playbackWatchdogReason(input: PlaybackWatchdogInput): WatchdogReason? {
    if (!input.playWhenReady || input.seeking || !input.startupComplete || input.suppressed || input.loadingAgeMs < 8_000L) {
        return null
    }
    // Near EOF the engine may wait for finalization. Do not let the 4s generic stall branches preempt 60s.
    if (input.durationMs > 0L && input.durationMs - input.positionMs in 0L..10_000L) {
        return if (input.positionAgeMs >= 60_000L && !input.downloadInProgress) WatchdogReason.NO_END else null
    }
    val bufferedAge = input.bufferedAgeMs
    return when {
        input.buffering && !input.loading && !input.downloadInProgress && input.stateAgeMs >= 4_000L &&
            input.bytesUnchangedForMs >= 4_000L && (!input.mpvLoad || bufferedAge != null && bufferedAge >= 4_000L) ->
            WatchdogReason.BUFFERING_NO_LOAD
        input.buffering && input.loading && !input.downloadInProgress && bufferedAge != null && bufferedAge >= 4_000L ->
            WatchdogReason.BUFFERING_NO_PROGRESS
        input.ready && input.positionAgeMs >= 4_000L && !input.downloadInProgress -> WatchdogReason.PLAYING_NO_PROGRESS
        else -> null
    }
}
