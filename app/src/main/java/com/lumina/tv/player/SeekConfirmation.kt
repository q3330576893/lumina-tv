package com.lumina.tv.player

import kotlin.math.max

internal enum class SeekConfirmation { PENDING, CONFIRMED, ROLLED_BACK }

/** Coordinates are player-relative even when the caller displays content-relative time. */
internal data class PendingSeekConfirmation(
    val displayTargetMs: Long,
    val playerTargetMs: Long,
    val fromPlayerMs: Long?,
    val requestedAtMs: Long,
    val playbackSpeed: Float = 1f,
    val paused: Boolean = false,
) {
    fun evaluate(
        observedPlayerMs: Long?,
        now: Long,
        playbackSpeed: Float = this.playbackSpeed,
        paused: Boolean = this.paused,
    ): SeekConfirmation {
        val elapsedMs = (now - requestedAtMs).coerceAtLeast(0L)
        if (elapsedMs > TIMEOUT_MS) return SeekConfirmation.ROLLED_BACK
        fun validSpeed(speed: Float): Double = speed.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
        // A speed/pause change may occur between samples: conservatively cover either playing speed.
        val drift = elapsedMs * max(if (this.paused) 0.0 else validSpeed(this.playbackSpeed),
            if (paused) 0.0 else validSpeed(playbackSpeed))
        if (observedPlayerMs != null) {
            val observed = observedPlayerMs.toDouble()
            val nearTarget = observed >= playerTargetMs.toDouble() - TARGET_TOLERANCE_MS &&
                observed <= playerTargetMs.toDouble() + drift + TARGET_TOLERANCE_MS
            val onOriginalTrajectory = fromPlayerMs?.let { from ->
                observed >= from.toDouble() - NATURAL_DRIFT_TOLERANCE_MS &&
                    observed <= from.toDouble() + drift + NATURAL_DRIFT_TOLERANCE_MS
            } ?: false
            if (nearTarget && !onOriginalTrajectory) return SeekConfirmation.CONFIRMED
        }
        return if (elapsedMs >= TIMEOUT_MS) SeekConfirmation.ROLLED_BACK else SeekConfirmation.PENDING
    }

    companion object {
        const val TIMEOUT_MS = 1_500L
        const val TARGET_TOLERANCE_MS = 1_000L
        private const val NATURAL_DRIFT_TOLERANCE_MS = 250L
    }
}
