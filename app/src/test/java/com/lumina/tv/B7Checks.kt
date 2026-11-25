package com.lumina.tv

import com.lumina.tv.data.NullableRequestCache
import com.lumina.tv.data.MdblistClient
import com.lumina.tv.data.RequestDeadline
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.data.readCancellable
import com.lumina.tv.data.withThirdPartyMetadataContext
import com.lumina.tv.data.withRequestBudget
import com.lumina.tv.player.PlaybackDelivery
import com.lumina.tv.player.PlaybackPath
import com.lumina.tv.player.PlaybackPathFailures
import com.lumina.tv.player.displayHdrDiagnostic
import com.lumina.tv.player.selectPlaybackEngine
import com.lumina.tv.player.supportsContentHdr
import com.lumina.tv.ui.screens.HeroActionFocus
import com.lumina.tv.ui.screens.HeroDirectionKey
import com.lumina.tv.ui.screens.shouldHeroConsumeCarouselDirection
import com.lumina.tv.ui.screens.EmptySearchPageEffect
import com.lumina.tv.ui.screens.SEARCH_EMPTY_AUTO_PAGE_LIMIT
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** B7 regressions for third-party request lifecycle, HDR preference, Home focus and search wiring. */
object B7Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        heroNegativeTtlAndInFlight()
        heroCacheCapacity()
        metadataCancellationAndDeadline()
        metadataClientsAndNestedBudgets()
        if (args.contentEquals(arrayOf("network"))) {
            println("B7 NETWORK PASS: Q-025 TTL/single-flight/cancelled owners and waiters; R4-16 bounded LRU/miss retention; Q-026 real TMDB/MDBList JSON readers, body cancellation and inherited whole-chain deadlines")
            return@runBlocking
        }
        heroFocusPolicy()
        hdrPolicyAndDiagnostics()
        searchContinuation()
        sourceWiring()
        println("B7 PASS: Q-025 negative TTL/single-flight; R4-16 bounded LRU/miss retention; Q-026 cancel/deadline for TMDB and MDBList transport; Q-032 Hero action focus; E7-A2 HDR capability routing/diagnostics; E7-B5 direct result count and real Compose empty-page continuation capped at 3")
    }

    private suspend fun heroNegativeTtlAndInFlight() = coroutineScope {
        var now = 1_000L
        val cache = NullableRequestCache<String>(missTtlMs = 60_000L, maxEntries = 20) { now }
        var loads = 0
        check(cache.getOrLoad("empty") { loads++; null } == null)
        check(cache.getOrLoad("empty") { loads++; "unexpected" } == null && loads == 1)
        now += 60_001L
        check(cache.getOrLoad("empty") { loads++; null } == null && loads == 2)

        val shared = NullableRequestCache<String>(missTtlMs = 60_000L, maxEntries = 20) { now }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val concurrentLoads = AtomicInteger()
        val first = async {
            shared.getOrLoad("same") {
                concurrentLoads.incrementAndGet()
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val second = async { shared.getOrLoad("same") { concurrentLoads.incrementAndGet(); "duplicate" } }
        yield()
        release.complete("hit")
        check(first.await() == "hit" && second.await() == "hit")
        check(concurrentLoads.get() == 1)

        val distinctKeys = NullableRequestCache<String>(60_000L, 20) { now }
        var distinctLoads = 0
        for (key in listOf("Movie|title|2020", "Movie|title|0", "Movie|original|2020", "Series|title|2020")) {
            repeat(2) { check(distinctKeys.getOrLoad(key) { distinctLoads++; null } == null) }
        }
        check(distinctLoads == 4) // Each fallback has its own negative TTL.

        val cancelledOwnerStarted = CompletableDeferred<Unit>()
        val cancelledOwner = async {
            shared.getOrLoad("cancel-owner") { cancelledOwnerStarted.complete(Unit); awaitCancellation() }
        }
        cancelledOwnerStarted.await()
        val survivingWaiter = async { shared.getOrLoad("cancel-owner") { "retried" } }
        yield()
        cancelledOwner.cancelAndJoin()
        check(survivingWaiter.await() == "retried") // Cancellation never poisons a negative cache entry.

        val liveOwnerStarted = CompletableDeferred<Unit>()
        val liveOwnerRelease = CompletableDeferred<String>()
        val liveOwner = async {
            shared.getOrLoad("cancel-waiter") { liveOwnerStarted.complete(Unit); liveOwnerRelease.await() }
        }
        liveOwnerStarted.await()
        val cancelledWaiter = async { shared.getOrLoad("cancel-waiter") { error("duplicate loader") } }
        yield()
        cancelledWaiter.cancelAndJoin()
        liveOwnerRelease.complete("survived")
        check(liveOwner.await() == "survived")
        check(shared.getOrLoad("cancel-waiter") { error("success should be cached") } == "survived")
    }

    private suspend fun heroCacheCapacity() {
        for (capacity in listOf(0, -1, Int.MIN_VALUE)) {
            check(runCatching { NullableRequestCache<String>(1_000L, capacity) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { NullableRequestCache<String>(-1L, 2) }.exceptionOrNull() is IllegalArgumentException)

        var now = 1_000L
        val cache = NullableRequestCache<String>(1_000L, 2) { now }
        check(cache.getOrLoad("hot") { "hot" } == "hot")
        check(cache.getOrLoad("cold") { "cold" } == "cold")
        check(cache.getOrLoad("hot") { error("hot value lost") } == "hot")
        check(cache.getOrLoad("new") { "new" } == "new")
        check(cache.getOrLoad("hot") { error("overflow removed a hot value") } == "hot")
        check(cache.getOrLoad("new") { error("overflow cleared the inserted value") } == "new")
        check(cache.getOrLoad("cold") { "reloaded" } == "reloaded")

        check(cache.getOrLoad("miss-hot") { null } == null)
        check(cache.getOrLoad("miss-cold") { null } == null)
        check(cache.getOrLoad("miss-hot") { error("negative cache lost") } == null)
        check(cache.getOrLoad("miss-new") { null } == null)
        check(cache.getOrLoad("miss-hot") { error("overflow removed a hot miss") } == null)
        check(cache.getOrLoad("miss-new") { error("overflow cleared all misses") } == null)
        check(cache.getOrLoad("miss-cold") { "retried" } == "retried")
        repeat(10) { index -> cache.getOrLoad("value-$index") { "value" } }
        check(cache.getOrLoad("miss-new") { error("value churn removed an unexpired miss") } == null)
        now += 1_000L
        check(cache.getOrLoad("miss-hot") { "recovered" } == "recovered") // An evicted miss can recover independently.

        val expiry = NullableRequestCache<String>(1_000L, 2) { now }
        expiry.getOrLoad("expires-first") { null }
        now += 500L
        expiry.getOrLoad("still-valid") { null }
        now += 499L
        expiry.getOrLoad("expires-first") { error("miss should still be throttled") }
        now += 1L
        expiry.getOrLoad("new-miss") { null }
        check(expiry.getOrLoad("still-valid") { error("expired hot miss evicted a live miss") } == null)
        check(expiry.getOrLoad("expires-first") { "recovered" } == "recovered")

        val single = NullableRequestCache<String>(0L, 1) { now }
        var misses = 0
        repeat(2) { single.getOrLoad("no-throttle") { misses++; null } }
        check(misses == 2)
        single.getOrLoad("a") { "a" }
        single.getOrLoad("b") { "b" }
        check(single.getOrLoad("b") { error("capacity one cleared its only value") } == "b")
    }

    private suspend fun metadataCancellationAndDeadline() {
        val accepted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val server = DripServer { socket ->
            accepted.countDown()
            runCatching {
                BufferedInputStream(socket.getInputStream()).readRequest()
                val out = BufferedOutputStream(socket.getOutputStream())
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                out.flush()
                repeat(1000) {
                    out.write(" ".toByteArray())
                    out.flush()
                    Thread.sleep(20)
                }
            }
            closed.countDown()
        }
        val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        try {
            val url = "http://127.0.0.1:${server.port}/metadata"
            val cancel = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                client.readCancellable(Request.Builder().url(url).build()) { response -> response.body?.string() }
            }
            check(withContext(Dispatchers.IO) { accepted.await(2, TimeUnit.SECONDS) })
            cancel.cancel(CancellationException("page left"))
            check(runCatching { cancel.await() }.exceptionOrNull() is CancellationException)
            check(withContext(Dispatchers.IO) { closed.await(2, TimeUnit.SECONDS) }) { "page cancellation did not close the socket" }
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }

        val deadlineStarted = CountDownLatch(1)
        val deadlineServer = DripServer { socket ->
            runCatching {
                BufferedInputStream(socket.getInputStream()).readRequest()
                val out = BufferedOutputStream(socket.getOutputStream())
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                out.flush()
                deadlineStarted.countDown()
                repeat(1000) {
                    out.write("x".toByteArray())
                    out.flush()
                    Thread.sleep(20)
                }
            }
        }
        val deadlineClient = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        val began = System.nanoTime()
        try {
            val result = runCatching {
                withThirdPartyMetadataContext(400L) {
                    deadlineClient.readCancellable(
                        Request.Builder().url("http://127.0.0.1:${deadlineServer.port}/slow").build()
                    ) { it.body?.string() }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            check(deadlineStarted.await(1, TimeUnit.SECONDS))
            check(result.isFailure)
            check(elapsedMs in 300..2_000) { "metadata deadline elapsed=${elapsedMs}ms" }
        } finally {
            deadlineServer.close()
            deadlineClient.dispatcher.executorService.shutdownNow()
            deadlineClient.connectionPool.evictAll()
        }
    }

    private suspend fun metadataClientsAndNestedBudgets() = coroutineScope {
        try {
            for (metadataClient in listOf(TmdbClient, MdblistClient)) {
                val name = metadataClient.javaClass.simpleName
                val bodyStarted = CountDownLatch(1)
                val bodyClosed = CountDownLatch(1)
                val followedUp = AtomicInteger()
                DripServer { socket ->
                    try { drip(socket, bodyStarted) } finally { bodyClosed.countDown() }
                }.use { server ->
                    val request = async {
                        withThirdPartyMetadataContext(3_000L) {
                            metadataJson(metadataClient, "http://127.0.0.1:${server.port}/search")
                            followedUp.incrementAndGet() // A logo/fallback must not start after leaving the page.
                        }
                    }
                    check(withContext(Dispatchers.IO) { bodyStarted.await(2, TimeUnit.SECONDS) }) { "$name did not start reading the body" }
                    request.cancel(CancellationException("page left"))
                    check(runCatching { request.await() }.exceptionOrNull() is CancellationException)
                    check(withContext(Dispatchers.IO) { bodyClosed.await(2, TimeUnit.SECONDS) }) { "$name did not close its in-flight body" }
                    check(followedUp.get() == 0)
                }

                val deadlineStarted = CountDownLatch(1)
                DripServer { drip(it, deadlineStarted) }.use { server ->
                    val began = System.nanoTime()
                    val outcome = runCatching {
                        withRequestBudget(3_000L) {
                            withThirdPartyMetadataContext(400L) {
                                metadataJson(metadataClient, "http://127.0.0.1:${server.port}/slow")
                            }
                        }
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                    check(deadlineStarted.count == 0L)
                    check(outcome.isFailure || outcome.getOrNull() == null)
                    check(elapsed in 300..1_500) { "$name inherited the long outer budget: ${elapsed}ms" }
                }

                // The short cap also covers search -> logo / original-title fallback, without resetting.
                DripServer { socket ->
                    BufferedInputStream(socket.getInputStream()).readRequest()
                    Thread.sleep(200)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        flush()
                    }
                }.use { search ->
                    val logoStarted = CountDownLatch(1)
                    DripServer { drip(it, logoStarted) }.use { logo ->
                        val began = System.nanoTime()
                        var inheritedDeadline = 0L
                        val outcome = runCatching {
                            withThirdPartyMetadataContext(600L) {
                                inheritedDeadline = currentCoroutineContext()[RequestDeadline]!!.expiresAtNanos
                                check(metadataJson(metadataClient, "http://127.0.0.1:${search.port}/search") != null)
                                withThirdPartyMetadataContext(3_000L) {
                                    check(currentCoroutineContext()[RequestDeadline]!!.expiresAtNanos == inheritedDeadline)
                                    metadataJson(metadataClient, "http://127.0.0.1:${logo.port}/images")
                                }
                            }
                        }
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                        check(logoStarted.count == 0L)
                        check(outcome.isFailure || outcome.getOrNull() == null)
                        check(elapsed in 500..1_500) { "$name reset its chain deadline: ${elapsed}ms" }
                    }
                }

                DripServer { socket ->
                    BufferedInputStream(socket.getInputStream()).readRequest()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }.use { server ->
                    check(withThirdPartyMetadataContext(1_000L) {
                        metadataJson(metadataClient, "http://127.0.0.1:${server.port}/failure")
                    } == null) // Ordinary network failures keep their silent fallback.
                }
            }
            withRequestBudget(150L) {
                val parent = currentCoroutineContext()[RequestDeadline]!!.expiresAtNanos
                withThirdPartyMetadataContext(3_000L) {
                    check(currentCoroutineContext()[RequestDeadline]!!.expiresAtNanos == parent)
                }
            }
        } finally {
            for (metadataClient in listOf(TmdbClient, MdblistClient)) {
                val http = metadataClient.javaClass.getDeclaredField("http").apply { isAccessible = true }
                    .get(metadataClient) as OkHttpClient
                http.dispatcher.executorService.shutdownNow()
                http.connectionPool.evictAll()
            }
        }
    }

    /** Exercise each production JSON reader against loopback, without keys or transport test hooks. */
    private suspend fun metadataJson(client: Any, url: String): JSONObject? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = client.javaClass.getDeclaredMethod("getJson", String::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            try {
                method.invoke(client, url, continuation)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }

    private fun drip(socket: Socket, started: CountDownLatch) {
        BufferedInputStream(socket.getInputStream()).readRequest()
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n".toByteArray())
        out.flush()
        started.countDown()
        repeat(1000) {
            out.write(' '.code)
            out.flush()
            Thread.sleep(20)
        }
    }

    private fun heroFocusPolicy() {
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.RIGHT, 3))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.LEFT, 3))
        check(shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.LEFT, 3))
        check(shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.RIGHT, 3))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.LEFT, 1))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.NONE, HeroDirectionKey.RIGHT, 3))
        for (count in 0..6) {
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.RIGHT, count))
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.LEFT, count))
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.NONE, HeroDirectionKey.LEFT, count))
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.NONE, HeroDirectionKey.RIGHT, count))
        }
        for (count in 0..1) {
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.LEFT, count))
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.RIGHT, count))
        }
    }

    private fun hdrPolicyAndDiagnostics() {
        val hdr10 = android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
        val hdr10Plus = android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        val hlg = android.view.Display.HdrCapabilities.HDR_TYPE_HLG
        val supported = intArrayOf(hdr10, hlg)
        check(supportsContentHdr(supported, "HDR10") == true)
        check(supportsContentHdr(supported, "SMPTE ST 2084") == true)
        check(supportsContentHdr(supported, "HDR10+") == false)
        check(supportsContentHdr(supported, "HLG") == true)
        check(supportsContentHdr(supported, "Dolby Vision") == null)
        check(supportsContentHdr(null, "HDR10") == null)
        check(supportsContentHdr(intArrayOf(hdr10Plus), "HDR10+") == true)
        check(supportsContentHdr(intArrayOf(hdr10Plus), "HDR10Plus") == true)
        check(supportsContentHdr(intArrayOf(hdr10Plus), "HDR10") == false)
        check(supportsContentHdr(intArrayOf(hdr10), "HDR10Plus") == false)
        check(supportsContentHdr(supported, "ARIB STD-B67") == true)
        check(supportsContentHdr(supported, "DOVIWithHDR10") == null)
        check(supportsContentHdr(supported, "DolbyVisionWithHDR10") == null)
        check(supportsContentHdr(supported, "HDR") == null)
        check(supportsContentHdr(supported, "SDR") == null)
        check(supportsContentHdr(supported, null) == null)
        for (type in listOf("HDR10", "HDR10+", "HLG")) {
            check(supportsContentHdr(intArrayOf(), type) == false)
            check(supportsContentHdr(null, type) == null)
        }
        check(displayHdrDiagnostic(supported) == listOf("HDR10", "HLG"))
        check(displayHdrDiagnostic(null) == null)
        check(displayHdrDiagnostic(intArrayOf()) == emptyList<String>())
        check(displayHdrDiagnostic(intArrayOf(hdr10Plus, android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)) ==
            listOf("HDR10+", "DOLBY_VISION"))

        val path = PlaybackPath("hdr", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        val failures = PlaybackPathFailures()
        check(selectPlaybackEngine("media3", null, path, failures, mpvAvailable = true) == "media3")
        failures.record(com.lumina.tv.player.PlaybackPathFailure(path.copy(engine = "mpv"),
            com.lumina.tv.player.PlaybackFailureReason.INITIALIZATION, 1, "injected"))
        check(selectPlaybackEngine("media3", "mpv", path, failures, mpvAvailable = true) == "media3")
        check(selectPlaybackEngine("media3", null, path, failures, mpvAvailable = false) == "media3")
        check(hdr10Plus != hdr10)
        val clean = PlaybackPathFailures()
        check(selectPlaybackEngine("media3", "mpv", path, clean, mpvAvailable = true) == "mpv")
        check(selectPlaybackEngine("mpv", null, path.copy(audioMode = "soft"), clean, mpvAvailable = true) == "mpv")
        check(selectPlaybackEngine("mpv", "media3", path, clean, mpvAvailable = true) == "media3")
        check(selectPlaybackEngine("media3", null, path.copy(audioMode = "soft"), clean, mpvAvailable = true) == "media3")
        failures.record(com.lumina.tv.player.PlaybackPathFailure(path,
            com.lumina.tv.player.PlaybackFailureReason.DECODING, 2, "injected"))
        check(selectPlaybackEngine("media3", "mpv", path, failures, mpvAvailable = true) == null)
    }

    private suspend fun searchContinuation() = coroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val composition = Composition(applier, recomposer)
        var searchKey by mutableIntStateOf(0)
        var pageStart by mutableIntStateOf(40)
        var visibleCount by mutableIntStateOf(0)
        var hasMore by mutableStateOf(true)
        var loading by mutableStateOf(true)
        var loadingMore by mutableStateOf(false)
        var autoPages by mutableIntStateOf(0)
        var requests = 0
        var completed = 0
        var cancelled = 0
        var finishOnPage = 0
        var showResultOnPage = 0
        suspend fun pumpUntil(condition: () -> Boolean) = withTimeout(3_000L) {
            while (!condition()) {
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                delay(5)
            }
        }
        suspend fun settle() {
            repeat(15) {
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                delay(5)
            }
        }
        fun reset(finishPage: Int = 0, resultPage: Int = 0) {
            Snapshot.withMutableSnapshot {
                searchKey++
                pageStart = 40
                visibleCount = 0
                hasMore = true
                autoPages = 0
            }
            requests = 0
            completed = 0
            finishOnPage = finishPage
            showResultOnPage = resultPage
        }
        try {
            composition.setContent {
                EmptySearchPageEffect(
                    searchKey = listOf("query", searchKey),
                    loadedItemCount = pageStart,
                    visibleCount = visibleCount,
                    hasMore = { hasMore },
                    loading = { loading || loadingMore },
                    automaticallyLoadedPages = { autoPages },
                ) {
                    requests++
                    autoPages++
                    loadingMore = true
                    try {
                        delay(20)
                        completed++
                        pageStart += 40
                        if (autoPages == showResultOnPage) visibleCount = 1
                        if (autoPages == finishOnPage) hasMore = false
                    } catch (failure: CancellationException) {
                        cancelled++
                        throw failure
                    } finally {
                        loadingMore = false
                    }
                }
            }
            settle()
            check(requests == 0) // Initial search is still loading.
            loading = false
            pumpUntil { completed == 3 && !loadingMore }
            settle()
            check(SEARCH_EMPTY_AUTO_PAGE_LIMIT == 3 && requests == 3 && autoPages == 3 && pageStart == 160)
            check(visibleCount == 0 && hasMore && cancelled == 0)

            reset(resultPage = 2)
            pumpUntil { completed == 2 && !loadingMore }
            settle()
            check(requests == 2 && autoPages == 2 && visibleCount == 1 && cancelled == 0)

            reset(finishPage = 1)
            pumpUntil { completed == 1 && !loadingMore }
            settle()
            check(requests == 1 && autoPages == 1 && !hasMore && cancelled == 0)

            reset()
            visibleCount = 1
            settle()
            check(requests == 0 && autoPages == 0) // Visible results disable automatic continuation.
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }

    private fun sourceWiring() {
        val root = if (java.io.File("src/main").isDirectory) java.io.File(".") else java.io.File("app")
        val search = root.resolve("src/main/java/com/lumina/tv/ui/screens/SearchScreen.kt").readText()
        val tmdb = root.resolve("src/main/java/com/lumina/tv/data/TmdbClient.kt").readText()
        val mdblist = root.resolve("src/main/java/com/lumina/tv/data/MdblistClient.kt").readText()
        check(!search.contains("SideEffect { visibleResultCount"))
        check(!search.contains("visibleResultCount ="))
        check(search.contains("searchKey = listOf(keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow)"))
        check(search.contains("visibleCount = visibleResults.size") && search.contains("loadedItemCount = pageStart"))
        check(search.contains("LaunchedEffect(searchKey, loadedItemCount, visibleCount)"))
        check(search.contains("snapshotFlow { Triple(visibleCount, hasMore(), loading()) }"))
        check(search.contains("loading = { loading || loadingMore }"))
        check(search.contains("shouldAutoLoadEmptySearchPage(visibleCount, more, isLoading, false, autoLoadedEmptyPages)"))
        check(search.contains("autoLoadedEmptyPages++"))
        check(search.contains("pageStart < 300"))
        check(!tmdb.substringAfter("private suspend fun getJson").contains(".execute()"))
        check(!mdblist.substringAfter("private suspend fun getJson").contains(".execute()"))
        check(tmdb.contains("http.readCancellable(req)"))
        check(mdblist.contains("http.readCancellable(request)"))
        check(tmdb.contains("withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS)"))
        check(mdblist.contains("withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS)"))
        val heroLookup = tmdb.substringAfter("suspend fun lookupHero").substringBefore("private suspend fun request")
        check(!heroLookup.contains("noHit.remove"))
        check(heroLookup.contains("currentCoroutineContext().ensureActive()"))
        check(tmdb.contains("METADATA_CALL_TIMEOUT_MS = 8_000L"))
        check(mdblist.contains("METADATA_CALL_TIMEOUT_MS = 8_000L"))
        val budgets = root.resolve("src/main/java/com/lumina/tv/data/RequestBudget.kt").readText()
        val embyMetadata = budgets.substringAfter("internal suspend fun <T> withMetadataContext(")
            .substringBefore("/** Third-party metadata")
        check(embyMetadata.contains("timeoutMs: Long = 20_000L"))
        check(embyMetadata.contains("withRequestBudget(timeoutMs) { withContext(Dispatchers.IO, block) }"))
        check(!embyMetadata.contains("withTimeout("))
        check(!root.resolve("src/main/java/com/lumina/tv/data/EmbyClient.kt").readText().contains("withThirdPartyMetadataContext"))

        val home = root.resolve("src/main/java/com/lumina/tv/ui/screens/HomeScreen.kt").readText()
        val hero = home.substringAfter("private fun Hero(").substringBefore("private fun MediaRow(")
        check(hero.contains("media.id to catchingRequest {"))
        check(hero.contains("var focusedHeroAction by remember { mutableStateOf(HeroActionFocus.NONE) }"))
        check(hero.contains("if (focusedHeroAction == HeroActionFocus.NONE || event.type != KeyEventType.KeyDown)"))
        check(hero.contains("shouldHeroConsumeCarouselDirection(focusedHeroAction, direction, items.size)"))
        check(Regex("focusProperties\\s*\\{\\s*right\\s*=\\s*detailFocus\\b").containsMatchIn(hero))
        check(Regex("focusProperties\\s*\\{\\s*left\\s*=\\s*playFocus\\b").containsMatchIn(hero))
        check(hero.contains("focusRequester = playFocus") && hero.contains("focusRequester = detailFocus"))

        val player = root.resolve("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText().replace("\r\n", "\n")
        val diagnostics = root.resolve("src/main/java/com/lumina/tv/ui/screens/PlayerDiagnostics.kt").readText()
        val engines = root.resolve("src/main/java/com/lumina/tv/player/PlaybackPathFailures.kt").readText()
        check(player.contains("val isDolbyVision = rangeType.contains(\"DOVI\") || videoStreamForDv?.dvProfile != null"))
        check(player.contains("(videoStreamForDv?.dvProfile == 7 || rangeType.contains(\"WITHEL\") || rangeType.contains(\"WITH EL\"))"))
        check(player.contains("val contentHdr = rangeType.takeUnless { isDolbyVision }"))
        check(player.contains("contentHdr?.let { supportsContentHdr(displayHdrTypes, it) }"))
        check(player.contains(".put(\"dvProfile7\", isDvP7)"))
        check("isDolbyVision" !in engines && "isDvP7" !in engines && "preferMpvForUnsupportedHdr" !in engines)
        check("path.audioMode ==" !in engines && "mpvRequested && mpvAvailable" in engines)
        check(diagnostics.contains(".put(\"display\", JSONObject().put(\"hdr\", displayHdrDiagnostic(displayHdrTypes)"))
    }

    private class DripServer(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        private val connected = AtomicReference<Socket?>()
        val port get() = server.localPort
        private val worker = thread(isDaemon = true) {
            runCatching { server.accept().use { socket -> connected.set(socket); handler(socket) } }
        }
        override fun close() {
            runCatching { server.close() }
            runCatching { connected.get()?.close() }
            worker.interrupt()
            worker.join(1000)
        }
    }

    private fun BufferedInputStream.readRequest() {
        val line = StringBuilder()
        var previous = -1
        while (true) {
            val current = read()
            if (current < 0) return
            if (previous == '\r'.code && current == '\n'.code && line.isEmpty()) return
            if (previous == '\r'.code && current == '\n'.code) line.clear() else if (current != '\r'.code) line.append(current.toChar())
            previous = current
        }
    }
}
