package com.lumina.tv.player

internal enum class PlaybackRecoveryKind { GENERAL, FIRST_FRAME_RECREATE, FIRST_FRAME_ALTERNATE }

/** Shared by all automatic recovery for one item/session, never reset by a physical load. */
internal class PlaybackRecoveryBudget {
    var attempts: Int = 0
        private set
    private var lastAttemptAt: Long? = null
    private val used = mutableSetOf<PlaybackRecoveryKind>()
    val exhausted: Boolean get() = attempts >= 3

    fun cooldownRemainingMs(now: Long): Long =
        lastAttemptAt?.let { (60_000L - (now - it)).coerceAtLeast(0L) } ?: 0L

    fun available(kind: PlaybackRecoveryKind): Boolean = !exhausted &&
        (kind == PlaybackRecoveryKind.GENERAL || kind !in used)

    fun tryAcquire(now: Long, kind: PlaybackRecoveryKind = PlaybackRecoveryKind.GENERAL): Boolean {
        if (!available(kind) || cooldownRemainingMs(now) > 0L) return false
        attempts++
        used += kind
        lastAttemptAt = now
        return true
    }
}
