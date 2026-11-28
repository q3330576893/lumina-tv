package com.lumina.tv.data

import com.lumina.tv.player.CreditsCountdown
import com.lumina.tv.player.LoadRetirementFence
import com.lumina.tv.player.PlaybackLoadCoordinator
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A: actual Emby transport -> production countdown -> T03 authority, with deterministic waits. */
internal object T06NextEpisodeChecks {
    @JvmStatic fun main(args: Array<String>) = runBlocking { run() }

    suspend fun run() = withTimeout(30_000L) {
        httpResultsAndRetries()
        lateResultsAndRetryRevocation()
        productionWiring()
        println("V6-T06-A PASS: HTTP seasons/episodes/probes; Found/NoNext/retryable/terminal; missing sources; recovery/backoff/exhaustion; pause/background/cancel/generation/permit and late-result isolation")
    }

    private fun episode(id: String, season: String = "s1", index: Int = 1, missing: Boolean = false) = JSONObject()
        .put("Id", id).put("Name", id).put("Type", "Episode").put("SeriesId", "series").put("SeasonId", season)
        .put("ParentIndexNumber", if (season == "s1") 1 else 2).put("IndexNumber", index).put("IsMissing", missing)
        .put("MediaSources", JSONArray().put(JSONObject().put("Id", "metadata-hint")))

    private class Fixture : NanoHTTPD("127.0.0.1", 0) {
        class Gate(val started: CompletableDeferred<Unit> = CompletableDeferred(), val release: CountDownLatch = CountDownLatch(1))
        @Volatile var gate: Gate? = null
        @Volatile var failurePath = ""
        @Volatile var status = 200
        @Volatile var failuresLeft = Int.MAX_VALUE
        @Volatile var failureAfter = 0
        @Volatile var malformed = false
        @Volatile var noSources = emptySet<String>()
        @Volatile var codecless = emptySet<String>()
        @Volatile var noProfileSources = emptySet<String>()
        @Volatile var episodes = mapOf("s1" to listOf(episode("e1"), episode("e3", index = 3), episode("e2", index = 2)))
        @Volatile var seasons = listOf("s1")
        val calls = CopyOnWriteArrayList<String>()
        val failures = AtomicInteger()

        override fun serve(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            if (session.method == Method.POST) session.parseBody(files)
            val hasProfile = files["postData"]?.let { JSONObject(it).has("DeviceProfile") } == true
            val path = session.uri
            calls += path
            if (path == "/Items/e2/PlaybackInfo") gate?.let {
                it.started.complete(Unit)
                check(it.release.await(5, TimeUnit.SECONDS)) { "Delayed HTTP fixture timed out" }
            }
            val attempt = if (path == failurePath) failures.getAndIncrement() else -1
            if (attempt >= failureAfter && attempt - failureAfter < failuresLeft) {
                if (malformed) return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
                return newFixedLengthResponse(Response.Status.lookup(status), "application/json", "{}")
            }
            val body = when {
                path.endsWith("/Episodes") -> JSONObject().put("Items", JSONArray(
                    episodes[session.parameters["SeasonId"]?.firstOrNull()].orEmpty().ifEmpty {
                        if (session.parameters["SeasonId"] == null) episodes.values.flatten() else emptyList()
                    }
                ))
                path.endsWith("/Seasons") -> JSONObject().put("Items", JSONArray(seasons.map { season ->
                    JSONObject().put("Id", season).put("Name", season).put("Type", "Season")
                        .put("IndexNumber", season.removePrefix("s").toInt())
                }))
                path.endsWith("/PlaybackInfo") -> {
                    val id = path.split('/')[2]
                    JSONObject().put("MediaSources", if (id in noSources || hasProfile && id in noProfileSources) JSONArray() else JSONArray().put(
                        JSONObject().put("Id", "source-$id").put("Container", "mkv").put("MediaStreams",
                            if (id in codecless && !hasProfile) JSONArray() else JSONArray().put(
                            JSONObject().put("Index", 0).put("Type", "Video").put("Codec", "h264")
                        ))
                    ))
                }
                else -> error("Unexpected next-episode request: $path")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        }

        fun reset() {
            failurePath = ""; status = 200; failuresLeft = Int.MAX_VALUE; failureAfter = 0
            malformed = false; codecless = emptySet(); noProfileSources = emptySet(); failures.set(0); calls.clear()
        }
    }

    private suspend fun httpResultsAndRetries() = coroutineScope {
        val server = Fixture().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "t06")
        val current = MediaItem("e1", "Episode 1", "Episode", seriesId = "series", seasonId = "s1", indexNumber = 1)
        try {
            check((client.getNextEpisode(current) as NextEpisodeResult.Found).item.id == "e2")
            check("/Items/e2/PlaybackInfo" in server.calls) { "SourceCount bypassed the production probe" }
            for (path in listOf("/Shows/series/Episodes", "/Shows/series/Seasons", "/Items/e2/PlaybackInfo")) {
                server.episodes = mapOf("s1" to if (path.endsWith("/Seasons")) listOf(episode("e1"))
                    else listOf(episode("e1"), episode("e2", index = 2)))
                for (status in listOf(408, 429, 500, 503, 400, 401, 403, 404, 410)) {
                    server.reset(); server.failurePath = path; server.status = status
                    val result = client.getNextEpisode(current)
                    if (status in listOf(408, 429, 500, 503)) check(result is NextEpisodeResult.RetryableFailure) { "$path $status: $result" }
                    else check(result is NextEpisodeResult.NonRetryableFailure) { "$path $status: $result" }
                }
                server.reset(); server.failurePath = path; server.malformed = true
                check(client.getNextEpisode(current) is NextEpisodeResult.NonRetryableFailure)
            }
            server.episodes = mapOf("s1" to listOf(episode("e1"), episode("e2", index = 2), episode("e3", index = 3)))
            for (status in listOf(503, 401)) {
                server.reset(); server.codecless = setOf("e2"); server.failurePath = "/Items/e2/PlaybackInfo"
                server.failureAfter = 1; server.status = status
                val result = client.getNextEpisode(current)
                check(if (status == 503) result is NextEpisodeResult.RetryableFailure else result is NextEpisodeResult.NonRetryableFailure) {
                    "Failed profile probe must not reuse an unverified inventory: $result"
                }
                check(server.calls.count { it == server.failurePath } == 2)
                server.failures.set(0)
                check(client.getMediaSourcesResult("e2").getOrThrow().isNotEmpty()) { "T01 default inventory fallback changed" }
            }
            server.reset(); server.codecless = setOf("e2"); server.noProfileSources = setOf("e2")
            check((client.getNextEpisode(current) as NextEpisodeResult.Found).item.id == "e3") {
                "An empty playable profile probe must skip the unplayable episode"
            }
            check(client.getMediaSourcesResult("e2").getOrThrow().isNotEmpty()) { "T01 empty enrichment fallback changed" }
            server.reset()
            server.episodes = mapOf("s1" to listOf(episode("e1"), episode("missing", index = 2, missing = true), episode("e3", index = 3)))
            check((client.getNextEpisode(current) as NextEpisodeResult.Found).item.id == "e3")
            check("/Items/missing/PlaybackInfo" !in server.calls)
            server.episodes = mapOf("s1" to listOf(episode("e1"), episode("e2", index = 2), episode("e3", index = 3)))
            server.noSources = setOf("e2")
            check((client.getNextEpisode(current) as NextEpisodeResult.Found).item.id == "e3")
            server.noSources = setOf("e2", "e3")
            check(client.getNextEpisode(current) == NextEpisodeResult.NoNext)
            server.seasons = listOf("s3", "s1", "s2")
            server.episodes = server.episodes + mapOf("s2" to listOf(episode("s2e2", "s2", 2), episode("s2e1", "s2", 1)))
            check((client.getNextEpisode(current) as NextEpisodeResult.Found).item.id == "s2e1")
            check((client.getNextEpisode(current.copy(seasonId = null)) as NextEpisodeResult.Found).item.id == "s2e1")
            server.seasons = listOf("s1"); server.episodes = mapOf("s1" to listOf(episode("e1")))
            check(client.getNextEpisode(current) == NextEpisodeResult.NoNext)
            check(client.getNextEpisode(current.copy(id = "absent")) is NextEpisodeResult.NonRetryableFailure)
            server.episodes = mapOf("s1" to listOf(episode("e1"), episode("e2", index = 2)))
            server.noSources = emptySet()
            for ((failureCount, status) in listOf(2 to 503, Int.MAX_VALUE to 503, Int.MAX_VALUE to 401, 0 to 200)) {
                server.reset(); server.failurePath = "/Items/e2/PlaybackInfo"; server.status = status; server.failuresLeft = failureCount
                val delays = mutableListOf<Long>()
                val completed = mutableListOf<MediaItem>()
                var attempts = 0
                val coordinator = PlaybackLoadCoordinator()
                val generation = coordinator.beginLoad("source")
                val permit = checkNotNull(coordinator.grantPermit(generation, LoadRetirementFence().awaitPermit()))
                val done = CompletableDeferred<Unit>()
                val countdown = CreditsCountdown<MediaItem>(this, {}, awaitRetry = { delays += it }, awaitTick = {})
                countdown.startForLoad(1, next = {
                    attempts++
                    client.getNextEpisode(current).also {
                        if (it !is NextEpisodeResult.RetryableFailure || attempts == 3) done.complete(Unit)
                    }
                }, coordinator, generation, permit, canContinue = { true }, onComplete = completed::add)
                done.await()
                // Await the actual countdown job, including its post-HTTP authority checks.
                while (countdown.active) yield()
                if (failureCount == 2 || failureCount == 0) {
                    check(completed.single().id == "e2" && countdown.lookupResult is NextEpisodeResult.Found)
                } else {
                    check(completed.isEmpty() && countdown.nextItem == null && countdown.triggered)
                    check(if (status == 401) countdown.lookupResult is NextEpisodeResult.NonRetryableFailure
                        else countdown.lookupResult is NextEpisodeResult.RetryableFailure)
                    countdown.start(1, { error("Exhausted/terminal lookup restarted") }, { true }, {})
                }
                check(attempts == when { status == 401 -> 1; failureCount == 0 -> 1; else -> 3 })
                check(delays == if (attempts == 3) listOf(1_000L, 2_000L) else emptyList<Long>())
            }
            server.reset(); server.episodes = mapOf("s1" to listOf(episode("e1")))
            val terminal = CreditsCountdown<MediaItem>(this, {}, awaitTick = {})
            terminal.start(1, { client.getNextEpisode(current) }, { true }, { error("Last episode advanced") })
            while (terminal.active) yield()
            check(terminal.lookupResult == NextEpisodeResult.NoNext && terminal.triggered)
            check(nextEpisodeFailure(IOException()) is NextEpisodeResult.RetryableFailure)
            check(nextEpisodeFailure(RequestTimeoutException(20)) is NextEpisodeResult.RetryableFailure)
            check(nextEpisodeFailure(javax.net.ssl.SSLHandshakeException("cert")) is NextEpisodeResult.NonRetryableFailure)
            try { nextEpisodeFailure(CancellationException("caller")); error("Cancellation swallowed") } catch (_: CancellationException) { }
            // The real HTTP result arrives after T03 has replaced its load or permit.
            for (replacePermit in listOf(false, true)) {
                server.reset(); server.episodes = mapOf("s1" to listOf(episode("e1"), episode("e2", index = 2)))
                val gate = Fixture.Gate().also { server.gate = it }
                val coordinator = PlaybackLoadCoordinator()
                val generation = coordinator.beginLoad("A")
                val fence = LoadRetirementFence()
                val permit = checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
                val stale = CreditsCountdown<MediaItem>(this, {}, awaitTick = {})
                try {
                    stale.startForLoad(1, { client.getNextEpisode(current) }, coordinator, generation, permit,
                        { true }, { error("Late HTTP result advanced a retired load") })
                    gate.started.await()
                    if (replacePermit) checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
                    else coordinator.beginLoad("B")
                    gate.release.countDown()
                    while (stale.active) yield()
                    check(stale.nextItem == null && stale.lookupResult == null)
                } finally { gate.release.countDown(); server.gate = null }
            }
        } finally { client.close(); server.stop() }
    }

    private suspend fun lateResultsAndRetryRevocation() = coroutineScope {
        for (boundary in listOf("pause", "background", "user-cancel", "generation", "permit", "retirement")) {
            for (duringRetry in listOf(false, true)) {
                val coordinator = PlaybackLoadCoordinator()
                val generation = coordinator.beginLoad("A")
                val fence = LoadRetirementFence()
                val permit = checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val ticks = Channel<Unit>(Channel.UNLIMITED)
                var canContinue = true
                var lookups = 0
                val completed = mutableListOf<String>()
                val countdown = CreditsCountdown<String>(this, {}, awaitRetry = {
                    reached.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }, awaitTick = { ticks.receive() })
                countdown.startForLoad(1, next = {
                    lookups++
                    if (duringRetry) NextEpisodeResult.RetryableFailure(IOException()) else {
                        reached.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        NextEpisodeResult.Found("late")
                    }
                }, coordinator, generation, permit, canContinue = { canContinue }, onComplete = completed::add)
                reached.await()
                when (boundary) {
                    "pause", "background" -> canContinue = false
                    "user-cancel" -> countdown.cancel(boundary, allowRestart = false)
                    "generation" -> coordinator.beginLoad("B")
                    "permit" -> checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
                    "retirement" -> fence.track(CompletableDeferred<Unit>())
                }
                release.complete(Unit)
                repeat(10) { yield() }
                check(completed.isEmpty() && countdown.nextItem == null && countdown.remainingSeconds == 0 && !countdown.active) {
                    "$boundary duringRetry=$duringRetry revived the old lookup"
                }
                check(lookups == 1) { "$boundary launched a stale retry" }
                // A new run cannot be erased by the cancelled run's late result.
                countdown.cancel("new-load")
                countdown.start(1, { NextEpisodeResult.Found("new") }, { true }, completed::add)
                yield(); ticks.send(Unit)
                repeat(5) { yield() }
                check(completed == listOf("new"))
                ticks.close()
            }
        }
        // Recheck authority after countdown ticks as well as after lookups/backoffs.
        val coordinator = PlaybackLoadCoordinator()
        val generation = coordinator.beginLoad("A")
        val permit = checkNotNull(coordinator.grantPermit(generation, LoadRetirementFence().awaitPermit()))
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val countdown = CreditsCountdown<String>(this, {}, awaitTick = { ticks.receive() })
        countdown.startForLoad(1, { NextEpisodeResult.Found("old") }, coordinator, generation, permit, { true }, { error("Stale completion") })
        yield(); coordinator.beginLoad("B"); ticks.send(Unit); yield()
        check(countdown.nextItem == null && !countdown.active)
        ticks.close()
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val overlapTicks = Channel<Unit>(Channel.UNLIMITED)
        val completed = mutableListOf<String>()
        val overlapping = CreditsCountdown<String>(this, {}, awaitTick = { overlapTicks.receive() })
        overlapping.start(1, {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { oldRelease.await() }
            NextEpisodeResult.Found("old")
        }, { true }, completed::add)
        oldStarted.await(); overlapping.cancel("pause")
        overlapping.start(1, { NextEpisodeResult.Found("new") }, { true }, completed::add)
        yield(); oldRelease.complete(Unit); repeat(5) { yield() }
        check(overlapping.nextItem == "new" && overlapping.remainingSeconds == 1 && overlapping.active)
        overlapTicks.send(Unit); repeat(5) { yield() }
        check(completed == listOf("new"))
        overlapTicks.close()
    }

    private fun productionWiring() {
        val source = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        val start = source.substringAfter("fun startAutoNext(").substringBefore("fun updateStalled(")
        check("creditsCountdown.startForLoad(" in start && "next = { client?.getNextEpisode(item)" in start)
        check("loadCoordinator = loadCoordinator, generation = generation, permit = permit" in start)
        check("isCurrentLoad(generation) && !reloadPending && error == null && autoNextForeground && !playbackPaused()" in start)
        check("playbackLoadPermit = loadPermit" in source && "playbackLoadPermit = null" in source)
        check("autoNextForeground = false" in source && "Lifecycle.Event.ON_RESUME) {" in source && "prewarm.resume()" in source)
        check("cancelCreditsCountdown(\"pause\")" in source && "allowRestart = false" in source)
        check("loadCoordinator.accepts(prewarmPermit)" in source && "runPrewarmLookup(prewarm, token" in source)
        val prewarm = File("src/main/java/com/lumina/tv/player/NextEpisodePrewarm.kt").readText()
        check("is NextEpisodeResult.Found" in prewarm && "state.finish(token)" in prewarm)
    }
}
