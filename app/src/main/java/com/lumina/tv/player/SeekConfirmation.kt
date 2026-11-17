package com.lumina.tv.player

import kotlin.math.abs

internal enum class SeekConfirmation { PENDING, CONFIRMED, ROLLED_BACK }

/** Coordinates are player-relative even when the caller displays content-relative time. */
internal data class PendingSeekConfirmation(
    val displayTargetMs: Long,
    val playerTargetMs: Long,
    val fromPlayerMs: Long?,
    val requestedAtMs: Long,
) {
    fun evaluate(observedPlayerMs: Long?, now: Long): SeekConfirmation {
        if (now - requestedAtMs > TIMEOUT_MS) return SeekConfirmation.ROLLED_BACK
        if (observedPlayerMs != null && abs(observedPlayerMs.toDouble() - playerTargetMs) <= TARGET_TOLERANCE_MS) {
            // Ordinary playback drift far from the target cannot acknowledge a seek.
            return SeekConfirmation.CONFIRMED
        }
        return if (now - requestedAtMs >= TIMEOUT_MS) SeekConfirmation.ROLLED_BACK else SeekConfirmation.PENDING
    }

    companion object {
        const val TIMEOUT_MS = 1_500L
        const val TARGET_TOLERANCE_MS = 1_000L
    }
}
