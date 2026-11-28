package com.lumina.tv.player

internal enum class FirstFrameStage { WaitingSurface, RebindSurface, RecreateOnce, AlternatePath, Terminal }
internal enum class FirstFrameAction { REBIND, RECREATE, ALTERNATE, TERMINAL }
internal data class FrameObservation(val generation: Int, val epoch: Long, val sinceMs: Long)

/** Main-thread state. All deadlines use elapsedRealtime; polling frequency cannot extend them. */
internal class FirstFrameRecovery {
    var stage = FirstFrameStage.WaitingSurface
        private set
    var observation = FrameObservation(-1, 0, 0)
        private set
    private var startedAt: Long? = null
    private var eligibleAt: Long? = null
    private var surfaceAt: Long? = null
    private var stageAt = 0L
    private var rendered = false
    private var decoderFailed = false
    val ownsRecovery: Boolean get() = startedAt != null && !rendered

    fun beginLoad(generation: Int, now: Long) {
        if (rendered) startedAt = null
        observation = FrameObservation(generation, observation.epoch + 1, now)
        eligibleAt = null
        surfaceAt = null
        rendered = false
        decoderFailed = false
    }

    fun beforeSeek(now: Long) {
        val wasRendered = rendered
        beginLoad(observation.generation, now)
        // A normal seek must not manufacture a startup failure after output was already observed.
        rendered = wasRendered
    }

    fun frame(token: FrameObservation, eventAt: Long): Boolean {
        if (token != observation || eventAt < observation.sinceMs || stage == FirstFrameStage.Terminal) return false
        rendered = true
        decoderFailed = false
        return true
    }

    fun videoFailure(generation: Int) {
        if (generation == observation.generation && !rendered) decoderFailed = true
    }

    fun audioOnlyReady(generation: Int) {
        if (generation != observation.generation) return
        // Audio output closes observation without claiming a rendered video frame.
        rendered = true
        decoderFailed = false
        startedAt = null
    }

    fun terminate(): FirstFrameAction {
        stage = FirstFrameStage.Terminal
        return FirstFrameAction.TERMINAL
    }

    fun tick(now: Long, generation: Int, ready: Boolean, selectedVideo: Boolean,
             surfaceValid: Boolean, playing: Boolean, budget: PlaybackRecoveryBudget): FirstFrameAction? {
        if (generation != observation.generation || rendered || stage == FirstFrameStage.Terminal) return null
        // Positive video renderer failure is sufficient even if track selection never finished.
        if (!selectedVideo && !decoderFailed) return null
        if (!playing) { eligibleAt = null; surfaceAt = null; return null }
        if (startedAt?.let { now - it >= SESSION_LIMIT_MS } == true) return terminate()
        if (!ready && !decoderFailed) return null
        if (startedAt == null) startedAt = now
        if (eligibleAt == null) eligibleAt = now
        if (!surfaceValid) {
            surfaceAt = null
            return if (now - eligibleAt!! >= SURFACE_LIMIT_MS) terminate() else null
        }
        if (surfaceAt == null) surfaceAt = now
        // A late Surface gets its own full frame grace period, without spending a retry.
        if (now - surfaceAt!! < FRAME_GRACE_MS && !decoderFailed) return null
        return when (stage) {
            FirstFrameStage.WaitingSurface -> {
                stage = FirstFrameStage.RebindSurface
                stageAt = now
                FirstFrameAction.REBIND
            }
            FirstFrameStage.RebindSurface -> {
                if (now - stageAt < REBIND_GRACE_MS) null
                else acquire(now, budget, PlaybackRecoveryKind.FIRST_FRAME_RECREATE,
                    FirstFrameStage.RecreateOnce, FirstFrameAction.RECREATE)
            }
            FirstFrameStage.RecreateOnce -> acquire(now, budget, PlaybackRecoveryKind.FIRST_FRAME_ALTERNATE,
                FirstFrameStage.AlternatePath, FirstFrameAction.ALTERNATE)
            FirstFrameStage.AlternatePath -> terminate()
            FirstFrameStage.Terminal -> null
        }
    }

    private fun acquire(now: Long, budget: PlaybackRecoveryBudget, kind: PlaybackRecoveryKind,
                        next: FirstFrameStage, action: FirstFrameAction): FirstFrameAction? {
        if (!budget.available(kind)) return terminate()
        if (!budget.tryAcquire(now, kind)) return null
        stage = next
        stageAt = now
        eligibleAt = null
        surfaceAt = null
        return action
    }

    companion object {
        const val FRAME_GRACE_MS = 8_000L
        const val REBIND_GRACE_MS = 2_000L
        const val SURFACE_LIMIT_MS = 15_000L
        const val SESSION_LIMIT_MS = 150_000L
    }
}
