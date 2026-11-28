package com.lumina.tv.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun shouldRetryMpvInSoftware(autoSoftwareRetry: Boolean, softwareRetryUsed: Boolean): Boolean =
    autoSoftwareRetry && !softwareRetryUsed

/** Native ownership and temporary-file hygiene have different failure consequences. */
internal fun retireNativeResources(destroy: () -> Unit, cleanupFiles: () -> Unit, onFileCleanupFailure: (Throwable) -> Unit) {
    try {
        destroy()
    } finally {
        runCatching(cleanupFiles).exceptionOrNull()?.let(onFileCleanupFailure)
    }
}

/** Called on the owning main coroutine. No suspension is allowed between grant, install and commit. */
internal suspend fun renewSoftwareRetryPermit(
    retirement: LoadRetirement,
    fence: LoadRetirementFence,
    coordinator: PlaybackLoadCoordinator,
    generation: Int,
    isCurrent: () -> Boolean,
    install: (PlaybackLoadPermit) -> Unit,
    start: (Boolean) -> Unit,
): Boolean {
    when (val outcome = retirement.await()) {
        is RetirementOutcome.Failed -> throw RetirementBlockedException(outcome.cause)
        RetirementOutcome.Succeeded -> Unit
        RetirementOutcome.Pending -> return false
    }
    currentCoroutineContext().ensureActive()
    if (!coordinator.accepts(generation) || !isCurrent()) return false
    val retirementPermit = fence.awaitPermit()
    currentCoroutineContext().ensureActive()
    if (!coordinator.accepts(generation) || !isCurrent()) return false
    val permit = coordinator.grantPermit(generation, retirementPermit) ?: return false
    install(permit)
    return coordinator.commit(permit, start)
}
