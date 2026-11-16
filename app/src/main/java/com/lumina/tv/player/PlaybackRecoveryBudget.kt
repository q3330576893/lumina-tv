package com.lumina.tv.player

/** Shared by audio recovery and the watchdog for one playback session. */
internal class PlaybackRecoveryBudget {
    var attempts: Int = 0
        private set
    private var lastAttemptAt: Long? = null
    val exhausted: Boolean get() = attempts >= 3

    fun cooldownRemainingMs(now: Long): Long =
        lastAttemptAt?.let { (60_000L - (now - it)).coerceAtLeast(0L) } ?: 0L

    fun tryAcquire(now: Long): Boolean {
        if (exhausted || cooldownRemainingMs(now) > 0L) return false
        attempts++
        lastAttemptAt = now
        return true
    }
}
