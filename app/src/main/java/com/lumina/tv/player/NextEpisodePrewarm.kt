package com.lumina.tv.player

import com.lumina.tv.data.NextEpisodeResult
import com.lumina.tv.data.nextEpisodeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job

internal sealed interface PrewarmState {
    data object Idle : PrewarmState
    class Lookup(val attempt: Int, val generation: Int, val permit: PlaybackLoadPermit) : PrewarmState
    data class Found(val id: String) : PrewarmState
    data object NoNext : PrewarmState
    data class RetryableFailure(val nextRetryAt: Long, val attempt: Int) : PrewarmState
    data object Cancelled : PrewarmState
}

/** Commit on the caller's context after IO, with no suspension between authority check and write. */
internal suspend fun commitPrewarmResult(isCurrent: () -> Boolean, commit: () -> Unit) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("Prewarm ownership expired")
    commit()
}

/** Cancellation propagates; even early return or a stale completion relinquishes Lookup. */
internal suspend fun <T> runPrewarmLookup(
    state: NextEpisodePrewarm,
    token: PrewarmState.Lookup,
    now: () -> Long,
    valid: () -> Boolean,
    lookup: suspend () -> NextEpisodeResult<T>,
    id: (T) -> String,
    warm: suspend (T) -> Boolean,
) {
    fun current() = state.owns(token) && valid()
    try {
        if (!current()) return
        val result = lookup()
        currentCoroutineContext().ensureActive()
        if (!current()) return
        when (result) {
            is NextEpisodeResult.Found -> {
                val warmed = warm(result.item)
                currentCoroutineContext().ensureActive()
                if (current()) {
                    if (warmed) state.found(token, id(result.item)) else state.failed(token, now())
                }
            }
            NextEpisodeResult.NoNext -> state.noNext(token)
            is NextEpisodeResult.RetryableFailure -> state.failed(token, now())
            is NextEpisodeResult.NonRetryableFailure -> state.failed(token, now(), retryable = false)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (current()) state.failed(token, now(), nextEpisodeFailure(failure) is NextEpisodeResult.RetryableFailure)
    } finally {
        state.finish(token)
    }
}

/** One lookup AND warmup job owns a token. Only NoNext survives content-preserving invalidation. */
internal class NextEpisodePrewarm {
    @Volatile
    var state: PrewarmState = PrewarmState.Idle
        private set
    private var attempts = 0
    private var nextRetryAt = 0L
    private var generation = -1
    private var permit: PlaybackLoadPermit? = null

    @Synchronized fun begin(now: Long, generation: Int, permit: PlaybackLoadPermit?, accepts: (PlaybackLoadPermit?) -> Boolean): PrewarmState.Lookup? {
        if (permit == null || !accepts(permit) || state == PrewarmState.NoNext) return null
        if (this.generation != generation || this.permit !== permit) invalidate()
        this.generation = generation
        this.permit = permit
        if (state is PrewarmState.Lookup || state is PrewarmState.Found || attempts >= MAX_ATTEMPTS || now < nextRetryAt) return null
        return PrewarmState.Lookup(attempts + 1, generation, permit).also { state = it }
    }

    @Synchronized fun owns(token: PrewarmState.Lookup): Boolean = state === token
    @Synchronized fun found(token: PrewarmState.Lookup, id: String) { if (owns(token)) state = PrewarmState.Found(id) }
    @Synchronized fun noNext(token: PrewarmState.Lookup) { if (owns(token)) state = PrewarmState.NoNext }
    @Synchronized fun failed(token: PrewarmState.Lookup, now: Long, retryable: Boolean = true) {
        if (!owns(token)) return
        attempts = if (retryable) token.attempt else MAX_ATTEMPTS
        nextRetryAt = now + BACKOFF_MS[(token.attempt - 1).coerceIn(BACKOFF_MS.indices)]
        state = PrewarmState.RetryableFailure(nextRetryAt, attempts)
    }
    @Synchronized fun finish(token: PrewarmState.Lookup) { if (owns(token)) state = PrewarmState.Cancelled }

    fun bind(token: PrewarmState.Lookup, job: Job) {
        // A coroutine cancelled before its first dispatch never enters its try/finally.
        job.invokeOnCompletion { finish(token) }
    }

    @Synchronized fun invalidate() {
        if (state != PrewarmState.NoNext) state = PrewarmState.Cancelled
    }

    /** Explicit foreground/play intent can start another bounded batch after its backoff. */
    @Synchronized fun resume() {
        invalidate()
        attempts = 0
    }

    companion object {
        // Initial lookup plus at most three automatic retries; no tick can reopen an exhausted batch.
        const val MAX_ATTEMPTS = 4
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
    }
}
