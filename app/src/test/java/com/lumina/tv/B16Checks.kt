package com.lumina.tv

import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.EmbyException
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.PlaybackInfoCapture
import com.lumina.tv.data.PlaybackMode
import com.lumina.tv.data.PreparedPlaybackTracks
import com.lumina.tv.data.RequestTimeoutException
import com.lumina.tv.data.TimelineOrigin
import com.lumina.tv.data.resolvePlaybackTrackIntent
import com.lumina.tv.data.withRequestBudget
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.player.MpvAvailabilityProbe
import com.lumina.tv.player.MpvAvailabilitySnapshot
import com.lumina.tv.player.PlaybackLoadDetails
import com.lumina.tv.player.PlaybackLoadKey
import com.lumina.tv.ui.screens.SEARCH_REQUEST_TIMEOUT_SECONDS
import com.lumina.tv.ui.screens.finishPlaybackLoadFailure
import com.lumina.tv.ui.screens.runSearchPageRequest
import com.lumina.tv.ui.screens.searchErrorPresentation
import com.lumina.tv.ui.screens.searchFailureMessage
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

object B16Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val failures = mutableListOf<String>()
        suspend fun verify(label: String, checkScenario: suspend () -> Unit) {
            try {
                checkScenario()
                println("B16 PASS $label")
            } catch (failure: IllegalStateException) {
                println("B16 FAIL $label: ${failure.message}")
                failures += label
            }
        }
        withTimeout(45_000L) {
            // Exercise the real 20s and 8s budgets in parallel.
            val playback = async { verify("W1 PlaybackInfo deadline") { deadline() } }
            val first = async { verify("W1 search first page deadline") { deadline(0) } }
            val append = async { verify("W1 search append deadline") { deadline(40) } }
            val cancellations = async {
                for (startIndex in listOf(null, 0, 40)) {
                    verify("W1 Back during Retry-After (startIndex=$startIndex)") { cancelled(startIndex) }
                    verify("W1 caller deadline during Retry-After (startIndex=$startIndex)") { callerTimeout(startIndex) }
                }
            }
            verify("W1 nested timeout and successful null") { budgetOwnership() }
            verify("W17 four exports and stale load keys") { diagnosticOwnership() }
            playback.await()
            first.await()
            append.await()
            cancellations.await()
        }
        check(failures.isEmpty()) { failures.joinToString() }
        println("B16 PASS: request deadline ownership and load-bound diagnostics")
    }

    private suspend fun deadline(startIndex: Int? = null) {
        Fixture { new429() }.use { fixture ->
            val page = RequestPage(startIndex)
            page.load(fixture)
            fixture.assertCallClosed(startIndex)
            val failure = page.failure
            check(failure is RequestTimeoutException && failure.budgetMs == if (startIndex == null) 20_000L else 8_000L)
            check(page.message?.contains("超时") == true && page.message?.contains("重试") == true && !page.loading)
            if (startIndex == null) check(!page.targetPresent)
            if (startIndex == 40) {
                check(page.searchResults.single().id == "kept-result")
                check(page.searchPresentation.primary == null && page.searchPresentation.append == page.message)
            } else if (startIndex == 0) {
                check(page.searchResults.isEmpty() && page.searchPresentation.primary == page.message)
            }
        }
    }

    private suspend fun cancelled(startIndex: Int?) = coroutineScope {
        Fixture { new429() }.use { fixture ->
            val page = RequestPage(startIndex)
            val request = async { page.load(fixture) }
            fixture.assertCallClosed(startIndex)
            check(request.isActive)
            request.cancelAndJoin()
            check(page.message == null && page.failure == null && request.isCancelled)
        }
    }

    private suspend fun callerTimeout(startIndex: Int?) {
        Fixture { new429() }.use { fixture ->
            val page = RequestPage(startIndex)
            var propagated: CancellationException? = null
            val outcome = runCatching {
                withTimeout(2_000L) {
                    try {
                        page.load(fixture)
                    } catch (failure: CancellationException) {
                        propagated = failure
                        throw failure
                    }
                }
            }.exceptionOrNull()
            fixture.assertCallClosed(startIndex)
            check(outcome is TimeoutCancellationException && outcome === propagated && page.message == null && page.failure == null)
        }
    }

    private suspend fun budgetOwnership() {
        check(withRequestBudget<String?> { null } == null)
        val inner = runCatching {
            withRequestBudget(1_000) { withTimeout(20) { delay(30_000) } }
        }.exceptionOrNull()
        check(inner is TimeoutCancellationException)
        val inherited = runCatching {
            withRequestBudget(40) { withRequestBudget(1_000) { delay(30_000) } }
        }.exceptionOrNull()
        check(inherited is RequestTimeoutException && inherited.budgetMs == 40L)

        var cleanup = false
        check(!finishPlaybackLoadFailure(EmbyException("retired request"), { false },
            { cleanup = true }, { cleanup = true }, { cleanup = true }))
        check(!cleanup)
    }

    private suspend fun diagnosticOwnership() {
        Fixture { session ->
            when (session.uri) {
                "/Items/first/PlaybackInfo" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", "{}")
                "/Items/replacement/PlaybackInfo" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "application/json", "{}")
                else -> {
                    val itemId = session.uri.substringAfter("/Items/").substringBefore('/')
                    // Valid PlaybackInfo with no route lets the JVM exercise capture before route validation.
                    val sourceId = if (itemId == "server-replaced-source") "other-source" else SOURCE.id
                    NanoHTTPD.newFixedLengthResponse("""{"MediaSources":[{"Id":"$sourceId","Container":"mkv","SupportsDirectPlay":false,"SupportsDirectStream":false}],"PlaySessionId":"response-$itemId"}""")
                }
            }
        }.use { fixture ->
            val probe = MpvAvailabilityProbe()
            fun availability() = probe.snapshot(true, listOf("arm64-v8a"), process64Bit = true)

            val first = newLoad(1, "first")
            check(first.snapshot(first.key).playbackJson().getString("urlType") == "pending")
            val firstFailure = runCatching { fixture.client.preparePlaybackTracks("first", SOURCE.id, null) }.exceptionOrNull()
            first.recordFailure(checkNotNull(firstFailure as? EmbyException))
            export(first, availability(), "media3").assertNegotiationFailure(401)
            println("B16 PASS W17 first load failure")

            val old = newLoad(2, "old")
            fixture.captureResponse(old)
            old.acceptTarget(targetWithCapturedResponse(old))
            old.rangeProbeDiagnostics = JSONObject().put("status", 206).put("marker", "old-range")
            old.lastPlaybackErrorJson = JSONObject().put("errorCodeName", "old-error").toString()
            val replacement = newLoad(3, "replacement") // Same source id is insufficient; item and generation also matter.
            val replaceFailure = runCatching {
                fixture.negotiate(replacement.key.itemId, replacement.playbackInfoCapture)
            }.exceptionOrNull()
            replacement.recordFailure(checkNotNull(replaceFailure as? EmbyException))
            val replaced = export(replacement, availability(), "media3")
            replaced.assertNegotiationFailure(503)
            check("old-" !in replaced.playback.toString() + replaced.info.toString() + replaced.errors.toString())
            val wrongKeys = listOf(
                old.key.copy(generation = 3),
                old.key.copy(itemId = "replacement"),
                old.key.copy(sourceId = "other-source"),
            )
            for (wrongKey in wrongKeys) {
                val stale = old.snapshot(wrongKey)
                check(stale.urlType == "stale" && stale.playMethod == "stale")
                check(stale.negotiation.getString("state") == "stale" && stale.rangeProbe.getString("state") == "stale")
                check(stale.errorsJson().getString("state") == "stale" && stale.errorsJson().isNull("media3"))
                check(JSONObject(stale.playbackInfoJson).getString("state") == "stale")
            }
            // A retired request can finish on IO after a new load begins; captures remain separate.
            val late = newLoad(2, "late-old")
            fixture.captureResponse(late)
            check(replacement.playbackInfoCapture.latest == null)
            println("B16 PASS W17 replacement load failure and stale keys")

            val redirected = newLoad(4, "server-replaced-source")
            fixture.captureResponse(redirected)
            val mismatchedResponse = JSONObject(redirected.snapshot(redirected.key).playbackInfoJson)
            check(mismatchedResponse.getString("state") == "stale" && !mismatchedResponse.has("MediaSources"))

            val init = newLoad(5, "init")
            fixture.captureResponse(init)
            init.acceptTarget(targetWithCapturedResponse(init))
            probe.recordInitialization("fixture-abi-init-failure")
            init.mpvInitializationError = "fixture-abi-init-failure"
            val initialized = export(init, availability(), "mpv") // No live controller after release.
            initialized.assertOwnedResponse("init")
            check(initialized.engine.getString("mpvAvailable") == "unavailable:fixture-abi-init-failure")
            val native = initialized.engine.getJSONObject("mpvAvailability")
            check(native.getBoolean("installed") && native.getBoolean("process64Bit"))
            check(native.getJSONArray("supportedAbis").getString(0) == "arm64-v8a" && native.getString("initialization") == "failed")
            check(initialized.errors.getBoolean("mpvErrorPresent") && initialized.errors.getString("mpv") == "fixture-abi-init-failure")
            println("B16 PASS W17 mpv initialization failure after release")

            probe.recordInitialization()
            val media3 = newLoad(6, "media3")
            fixture.captureResponse(media3)
            media3.acceptTarget(targetWithCapturedResponse(media3))
            val switched = export(media3, availability(), "media3")
            switched.assertOwnedResponse("media3")
            check(switched.engine.getString("active") == "media3" && switched.engine.getString("mpvAvailable") == "available")
            check(switched.engine.getJSONObject("mpvAvailability").getString("initialization") == "ready")
            check(switched.errors.getString("state") == "unavailable" && !switched.errors.getBoolean("mpvErrorPresent"))
            check(switched.errors.isNull("loadFailure") && switched.errors.isNull("media3"))
            println("B16 PASS W17 successful mpv initialization followed by Media3")
        }
    }

    private fun newLoad(generation: Int, itemId: String) = PlaybackLoadDetails(PlaybackLoadKey(generation, itemId, SOURCE.id))

    private fun targetWithCapturedResponse(load: PlaybackLoadDetails) = EmbyClient.PlaybackTarget(
        url = "http://127.0.0.1/video.mp4",
        mode = PlaybackMode.DirectPlay,
        source = SOURCE,
        startPositionMs = 0,
        playSessionId = null,
        audioStreamIndex = null,
        streamStartOffsetMs = 0,
        timelineOrigin = TimelineOrigin.CONTENT,
        playbackInfo = checkNotNull(load.playbackInfoCapture.latest),
    )

    private data class Export(val playback: JSONObject, val info: JSONObject, val errors: JSONObject, val engine: JSONObject) {
        fun assertNegotiationFailure(status: Int) {
            check(info.getString("state") == "unavailable" && !info.has("MediaSources"))
            check(playback.getString("urlType") == "unavailable" &&
                playback.getJSONObject("rangeProbe").getString("state") == "unavailable")
            val negotiation = playback.getJSONObject("negotiation")
            check(negotiation.getString("exceptionType") == "EmbyException" && negotiation.getInt("httpStatus") == status)
            check(errors.getJSONObject("loadFailure").getInt("httpStatus") == status && errors.isNull("media3"))
        }

        fun assertOwnedResponse(itemId: String) {
            check(info.getString("state") == "ready" && info.getString("PlaySessionId") == "response-$itemId")
            check(info.getJSONArray("MediaSources").getJSONObject(0).getString("Id") == SOURCE.id)
        }
    }

    private fun export(load: PlaybackLoadDetails, availability: MpvAvailabilitySnapshot, engine: String): Export {
        val snapshot = load.snapshot(load.key)
        val meta = JSONObject().put("playback", snapshot.playbackJson())
            .put("engine", availability.addTo(JSONObject().put("active", engine)))
        val zip = Files.createTempFile("lumina-b16-diag-", ".zip").toFile()
        try {
            DiagExporter.writeArchive(zip, mapOf(
                "meta.json" to meta.toString(),
                "playback-info.json" to snapshot.playbackInfoJson,
                "errors.json" to snapshot.errorsJson().toString(),
            ))
            ZipFile(zip).use { archive ->
                fun read(entry: String) = archive.getInputStream(archive.getEntry(entry)).bufferedReader(Charsets.UTF_8)
                    .use { JSONObject(it.readText()) }
                val info = read("playback-info.json")
                val errors = read("errors.json")
                for (owner in listOf(info.getJSONObject("load"), errors.getJSONObject("load"))) {
                    check(owner.getInt("generation") == load.key.generation &&
                        owner.getString("itemId") == load.key.itemId && owner.getString("sourceId") == load.key.sourceId)
                }
                val exportedMeta = read("meta.json")
                return Export(exportedMeta.getJSONObject("playback"), info, errors, exportedMeta.getJSONObject("engine"))
            }
        } finally {
            Files.deleteIfExists(zip.toPath())
        }
    }

    private class RequestPage(private val startIndex: Int?) {
        var loading = true
        var failure: Exception? = null
        var targetPresent = true
        private var playerError: String? = null
        private var searchError: String? = null
        private var appendError: String? = null
        var searchResults = if (startIndex == 40) listOf(MediaItem("kept-result", "Kept", "Movie")) else emptyList()
        val searchPresentation get() = searchErrorPresentation(searchResults.size, searchError, appendError)
        val message get() = playerError ?: searchPresentation.primary ?: searchPresentation.append

        suspend fun load(fixture: Fixture) {
            if (startIndex == null) {
                try {
                    fixture.negotiate("rate-limited")
                } catch (e: Exception) {
                    finishPlaybackLoadFailure(e, { true }, { loading = it }, { targetPresent = false }) {
                        failure = e
                        playerError = it
                    }
                }
            } else {
                runSearchPageRequest(onFailure = { e ->
                    failure = e
                    if (startIndex == 0) searchError = searchFailureMessage(e) else appendError = searchFailureMessage(e)
                }, onFinished = { loading = false }) {
                    val page = fixture.client.getItems(searchTerm = "B16", startIndex = startIndex,
                        requestTimeoutSeconds = SEARCH_REQUEST_TIMEOUT_SECONDS)
                    searchResults = if (startIndex == 0) page else searchResults + page
                }
            }
        }
    }

    private class Fixture(respond: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response) : AutoCloseable {
        private val requests = AtomicInteger()
        private val closed = CompletableDeferred<Call>()
        private val idle = CompletableDeferred<Unit>()
        private val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (session.method == Method.POST) session.parseBody(mutableMapOf())
                return respond(session).apply { addHeader("Connection", "close") }
            }
        }.apply { start(2_000, true) }
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "b16")
        private val http: OkHttpClient

        init {
            val field = EmbyClient::class.java.getDeclaredField("http").apply { isAccessible = true }
            http = (field.get(client) as OkHttpClient).newBuilder()
                .eventListener(object : EventListener() {
                    override fun callEnd(call: Call) { closed.complete(call) }
                }).build()
            field.set(client, http)
            http.dispatcher.idleCallback = Runnable { idle.complete(Unit) }
        }

        suspend fun assertCallClosed(startIndex: Int?) {
            val request = withTimeout(3_000L) { idle.await(); closed.await().request() }
            if (startIndex == null) check(request.method == "POST" && request.url.encodedPath == "/Items/rate-limited/PlaybackInfo")
            else check(request.url.encodedPath == "/Users/b16/Items" && request.url.queryParameter("StartIndex") == startIndex.toString())
            check(requests.get() == 1 && http.dispatcher.runningCallsCount() == 0 &&
                http.dispatcher.queuedCallsCount() == 0)
        }

        suspend fun negotiate(id: String, capture: PlaybackInfoCapture? = null) {
            val prepared = PreparedPlaybackTracks(SOURCE, resolvePlaybackTrackIntent(SOURCE, null, "auto", null, null), listOf(SOURCE))
            client.resolvePlayback(MediaItem(id, id, "Movie"), preparedTracks = prepared, playbackInfoCapture = capture)
        }

        suspend fun captureResponse(load: PlaybackLoadDetails) {
            val failure = runCatching { negotiate(load.key.itemId, load.playbackInfoCapture) }.exceptionOrNull()
            check(failure is EmbyException)
            val info = checkNotNull(load.playbackInfoCapture.latest)
            check(info.itemId == load.key.itemId)
        }

        override fun close() {
            client.close()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.stop()
        }
    }

    private fun new429(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "application/json", "{}")
            .apply { addHeader("Retry-After", "30") }

    private val SOURCE = MediaSource("same-source", "B16", "mkv", true, true, emptyList())
}
