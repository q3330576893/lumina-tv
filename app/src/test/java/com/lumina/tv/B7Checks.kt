package com.lumina.tv

import com.lumina.tv.data.NullableRequestCache
import com.lumina.tv.data.readCancellable
import com.lumina.tv.data.withMetadataContext
import com.lumina.tv.player.PlaybackDelivery
import com.lumina.tv.player.PlaybackPath
import com.lumina.tv.player.PlaybackPathFailures
import com.lumina.tv.player.displayHdrDiagnostic
import com.lumina.tv.player.selectPlaybackEngine
import com.lumina.tv.player.supportsContentHdr
import com.lumina.tv.ui.screens.HeroActionFocus
import com.lumina.tv.ui.screens.HeroDirectionKey
import com.lumina.tv.ui.screens.shouldHeroConsumeCarouselDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** B7 regressions for third-party request lifecycle, HDR preference, Home focus and search wiring. */
object B7Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        heroNegativeTtlAndInFlight()
        metadataCancellationAndDeadline()
        heroFocusPolicy()
        hdrPolicyAndDiagnostics()
        searchWiring()
        println("B7 PASS: Q-025 negative TTL/single-flight; Q-026 cancel/deadline for TMDB and MDBList transport; Q-032 Hero action focus; E7-A2 HDR capability routing/diagnostics; E7-B5 derived search count keeps empty-page continuation")
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
            check(accepted.await(2, TimeUnit.SECONDS))
            cancel.cancel(CancellationException("page left"))
            check(runCatching { cancel.await() }.exceptionOrNull() is CancellationException)
            check(closed.await(2, TimeUnit.SECONDS)) { "page cancellation did not close the socket" }
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
                withMetadataContext(400L) {
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

    private fun heroFocusPolicy() {
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.RIGHT, 3))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.LEFT, 3))
        check(shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.LEFT, 3))
        check(shouldHeroConsumeCarouselDirection(HeroActionFocus.DETAIL, HeroDirectionKey.RIGHT, 3))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.PLAY, HeroDirectionKey.LEFT, 1))
        check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.NONE, HeroDirectionKey.RIGHT, 3))
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
        check(displayHdrDiagnostic(supported) == listOf("HDR10", "HLG"))
        check(displayHdrDiagnostic(null) == null)

        val path = PlaybackPath("hdr", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        val failures = PlaybackPathFailures()
        check(selectPlaybackEngine("media3", null, false, false, path, failures, true) == "mpv")
        failures.record(com.lumina.tv.player.PlaybackPathFailure(path.copy(engine = "mpv"),
            com.lumina.tv.player.PlaybackFailureReason.INITIALIZATION, 1, "injected"))
        check(selectPlaybackEngine("media3", null, false, false, path, failures, true) == "media3")
        check(selectPlaybackEngine("media3", null, false, false, path, failures) == "media3")
        check(hdr10Plus != hdr10)
    }

    private fun searchWiring() {
        val root = if (java.io.File("src/main").isDirectory) java.io.File(".") else java.io.File("app")
        val search = root.resolve("src/main/java/com/lumina/tv/ui/screens/SearchScreen.kt").readText()
        val tmdb = root.resolve("src/main/java/com/lumina/tv/data/TmdbClient.kt").readText()
        val mdblist = root.resolve("src/main/java/com/lumina/tv/data/MdblistClient.kt").readText()
        check(!search.contains("SideEffect { visibleResultCount"))
        check(search.contains("LaunchedEffect(keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow, visibleResults.size)"))
        check(search.contains("snapshotFlow { Triple(visibleResults.size, hasMore, loading || loadingMore) }"))
        check(search.contains("shouldAutoLoadEmptySearchPage(visibleCount, more, isLoading, false, autoLoadedEmptyPages)"))
        check(search.contains("autoLoadedEmptyPages++"))
        check(search.contains("pageStart < 300"))
        check(!tmdb.substringAfter("private suspend fun getJson").contains(".execute()"))
        check(!mdblist.substringAfter("private suspend fun getJson").contains(".execute()"))
        check(tmdb.contains("http.readCancellable(req)"))
        check(mdblist.contains("http.readCancellable(request)"))
        check(tmdb.contains("withMetadataContext(METADATA_CALL_TIMEOUT_MS)"))
        check(mdblist.contains("withMetadataContext(METADATA_CALL_TIMEOUT_MS)"))
        val heroLookup = tmdb.substringAfter("suspend fun lookupHero").substringBefore("private suspend fun request")
        check(!heroLookup.contains("noHit.remove"))
    }

    private class DripServer(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        private val worker = thread(isDaemon = true) {
            runCatching { server.accept().use(handler) }
        }
        override fun close() {
            runCatching { server.close() }
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
