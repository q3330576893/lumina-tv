package com.lumina.tv.player

import com.lumina.tv.data.NextEpisodeResult
import kotlinx.coroutines.*
import java.io.IOException

object P1PrewarmChecks {
    fun run() = runBlocking {
        val coordinator = PlaybackLoadCoordinator("A")
        val fence = LoadRetirementFence()
        var generation = coordinator.beginLoad("A")
        var permit = checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
        var now = 0L
        val state = NextEpisodePrewarm()
        fun begin() = checkNotNull(state.begin(now, generation, permit, coordinator::accepts))
        var lookups = 0
        suspend fun run(token: PrewarmState.Lookup, result: NextEpisodeResult<String>) = runPrewarmLookup(state, token, { now },
            { coordinator.accepts(token.permit) }, { lookups++; result }, { it }, { true })
        repeat(4) { index ->
            val token = begin()
            check(token.attempt == index + 1)
            run(token, NextEpisodeResult.RetryableFailure(IOException("temporary fixture failure")))
            val failed = state.state as PrewarmState.RetryableFailure
            check(state.begin(now, generation, permit, coordinator::accepts) == null)
            now = failed.nextRetryAt
        }
        check(state.begin(now + 60_000, generation, permit, coordinator::accepts) == null && lookups == 4)
        state.resume()
        run(begin(), NextEpisodeResult.Found("episode-2"))
        check(state.state == PrewarmState.Found("episode-2"))
        check(state.begin(now, generation, permit, coordinator::accepts) == null)

        for (boundary in listOf("pause", "background", "source", "generation", "permit", "cancel", "warm-cancel")) {
            state.resume()
            val token = begin()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var warms = 0
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                runPrewarmLookup(state, token, { now }, { coordinator.accepts(token.permit) },
                    lookup = {
                        if (boundary != "warm-cancel") { entered.complete(Unit); release.await() }
                        NextEpisodeResult.Found("late")
                    }, id = { it }, warm = {
                        warms++
                        if (boundary == "warm-cancel") { entered.complete(Unit); release.await() }
                        true
                    })
            }
            entered.await()
            when (boundary) {
                "source", "generation" -> generation = coordinator.beginLoad(if (boundary == "source") "B" else "A")
                "permit" -> fence.track(LoadRetirement().also { it.succeed() })
            }
            state.invalidate()
            job.cancelAndJoin()
            release.complete(Unit)
            check(state.state == PrewarmState.Cancelled && !job.isActive)
            check(warms == if (boundary == "warm-cancel") 1 else 0)
            permit = checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
            val next = begin()
            // A finally or completion belonging to the old job cannot erase the new Lookup.
            state.finish(token); state.found(token, "stale"); state.noNext(token)
            check(state.owns(next))
            run(next, NextEpisodeResult.Found("fresh"))
            check(state.state == PrewarmState.Found("fresh"))
        }
        state.resume()
        val invalid = begin()
        runPrewarmLookup(state, invalid, { now }, { false }, { error("must not query") }, { it: String -> it }, { true })
        check(state.state == PrewarmState.Cancelled)
        var cacheWrites = 0
        commitPrewarmResult({ true }) { cacheWrites++ }
        check(runCatching { commitPrewarmResult({ false }) { cacheWrites++ } }.exceptionOrNull() is CancellationException)
        val lateIo = CompletableDeferred<Unit>()
        val startedIo = CompletableDeferred<Unit>()
        val lateJob = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { startedIo.complete(Unit); lateIo.await() }
            commitPrewarmResult({ true }) { cacheWrites++ }
        }
        startedIo.await()
        lateJob.cancel()
        lateIo.complete(Unit)
        lateJob.join()
        check(cacheWrites == 1) { "Stale/cancelled warmup populated the target cache" }
        val client = java.io.File("src/main/java/com/lumina/tv/data/EmbyClient.kt").readText()
            .substringAfter("suspend fun prewarmPlayback(").substringBefore("suspend fun getTrickplayFrame")
        check("cacheResult = false" in client && "commitPrewarmResult(isCurrent)" in client)
        state.resume()
        val neverDispatched = begin()
        val cancelledScope = CoroutineScope(Job().also { it.cancel() })
        val unstarted = cancelledScope.launch { error("A cancelled job must never dispatch") }
        state.bind(neverDispatched, unstarted)
        unstarted.join()
        check(state.state == PrewarmState.Cancelled)
        state.resume()
        run(begin(), NextEpisodeResult.NoNext)
        val queriesAtEnd = lookups
        repeat(3) { state.resume(); state.invalidate(); check(state.begin(now, generation, permit, coordinator::accepts) == null) }
        check(state.state == PrewarmState.NoNext && lookups == queriesAtEnd)
        println("P1PrewarmChecks PASS: initial+3 backoffs; resume; pause/background/source/generation/permit cancellation; lookup/warm cleanup; stale writes; NoNext tombstone")
    }
}
