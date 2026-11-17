package com.lumina.tv

import com.lumina.tv.data.*
import com.lumina.tv.player.MpvPlayerController.LoadIdentity
import com.lumina.tv.player.PlaybackDiagnostics
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual HTTP pagination plus delayed-response/mutation/diagnostic ownership regressions. */
object P6Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        httpPages()
        pagingOwnership()
        diagnosticOwnership()
        println("P6 PASS: 137 favorites, response-local totals/offsets, mutation rebuild, retained failures, stale loads, focus anchors, actual readback and session-bound metrics")
    }

    private fun items(count: Int) = (0 until count).map { MediaItem("id$it", "Item $it", "Movie", isFavorite = true) }

    private suspend fun httpPages() = coroutineScope {
        val serverItems = items(137)
        val queries = CopyOnWriteArrayList<Map<String, String>>()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "p6-http") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val target = input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val query = target.substringAfter('?').split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    queries.add(query)
                    val favorite = query["Filters"] == "IsFavorite"
                    val rows = if (favorite) serverItems.drop(query.getValue("StartIndex").toInt()).take(query.getValue("Limit").toInt()) else emptyList()
                    val body = JSONObject().put("TotalRecordCount", if (favorite) 137 else 9999)
                        .put("Items", JSONArray(rows.map { JSONObject().put("Id", it.id).put("Name", it.name).put("Type", it.type) }))
                        .toString().toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(body)
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) failure.set(error)
            }
        }
        try {
            val client = EmbyClient("http://127.0.0.1:${server.localPort}", userId = "test")
            val preview = client.getFavorites(24)
            check(preview.items.size == 24 && preview.total == 137 && preview.nextOffset == 24)
            val collected = mutableListOf<MediaItem>()
            var offset: Int? = 0
            while (offset != null) {
                val start = checkNotNull(offset)
                val page = async { client.getFavorites(50, start) }
                val unrelated = async { client.getItems(limit = 1) }
                val result = page.await()
                unrelated.await()
                check(result.total == 137 && client.lastTotalRecordCount == 9999)
                collected += result.items
                offset = result.nextOffset
            }
            check(collected.map { it.id } == serverItems.map { it.id })
            val favoritesQueries = queries.filter { it["Filters"] == "IsFavorite" }
            check(favoritesQueries.map { it["StartIndex"] } == listOf("0", "0", "50", "100"))
            check(favoritesQueries.all { it["EnableTotalRecordCount"] == "true" && it["SortBy"] == FAVORITES_SORT })
            check(favoritesNextOffset(50, 3, 137) == 53) // Short server pages still have a next page.
            check(favoritesNextOffset(100, 37, 137) == null)
            check(favoritesNextOffset(0, 3, null) == 3 && favoritesNextOffset(3, 0, null) == null)
            println("D02 HTTP PASS: preview 24, full 50+50+37, concurrent unrelated total 9999")
        } finally {
            server.close()
            worker.join(5000)
            failure.get()?.let { throw it }
        }
    }

    private suspend fun pagingOwnership() = coroutineScope {
        FavoritesCache.clear()
        var revision = 0L
        var data = items(137)
        var failAt: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val offsets = mutableListOf<Int>()
        val key = favoritesQueryKey("https://server/emby", "user")
        check(key != favoritesQueryKey("https://server/emby", "other"))
        check(key != "favorites" && key != "tab:Favorites")
        val pager = FavoritesPager(key, { revision }) { limit, start ->
            offsets += start
            if (start == failAt) error("offline")
            val snapshot = data
            gate?.let { started.complete(Unit); it.await() }
            val rows = snapshot.drop(start).take(limit)
            FavoritesPage(rows, snapshot.size, favoritesNextOffset(start, rows.size, snapshot.size))
        }
        pager.refresh(data.take(24))
        check(pager.state.items.size == 50 && !pager.state.refreshRequired)
        failAt = 50
        pager.append()
        check(pager.state.items.size == 50 && pager.state.nextOffset == 50 && pager.state.error != null)
        failAt = null
        pager.append()
        check(pager.state.items.size == 100 && pager.state.items.distinctBy { it.id }.size == 100)
        val anchor = FavoritesAnchor("id72", 72, 17, "id75", 75, 100)
        pager.saveAnchor(anchor)
        val beforeFailure = pager.state.items
        failAt = 50 // A refresh that fails after fetching its first page must retain the old prefix.
        pager.refresh(emptyList())
        check(pager.state.items == beforeFailure && pager.state.refreshRequired && pager.state.error != null)
        failAt = null
        pager.refresh(emptyList())
        gate = CompletableDeferred()
        val oldAppend = launch { pager.append() }
        started.await()
        FavoritesCache.invalidate(key, "id75", favorite = false, played = null)
        data = data.filterNot { it.id == "id75" }
        revision++
        val release = checkNotNull(gate)
        gate = null
        val oldIdentity = pager.state.identity
        pager.refresh(emptyList())
        release.complete(Unit)
        oldAppend.join()
        check(pager.state.identity != oldIdentity && pager.state.total == 136)
        check(pager.state.items == data.take(100) && pager.state.nextOffset == 100)
        check(anchor.indexIn(pager.state.items) == 72 && anchor.focusIn(pager.state.items) == "id76")
        pager.append()
        check(pager.state.items == data && pager.state.nextOffset == null)
        pager.cancel()
        val restored = FavoritesPager(key, { revision }) { limit, start ->
            offsets += start
            val rows = data.drop(start).take(limit)
            FavoritesPage(rows, data.size, favoritesNextOffset(start, rows.size, data.size))
        }
        val beforeReturn = offsets.size
        restored.refresh(emptyList())
        check(offsets.drop(beforeReturn) == listOf(0, 50, 100))
        check(restored.state.items == data && restored.anchor.offset == 17)
        // Cancellation, even if the transport ignores it, must never repopulate a disposed list.
        val late = CompletableDeferred<FavoritesPage>()
        val disposed = FavoritesPager("disposed", { revision }) { _, _ -> late.await() }
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { disposed.refresh(data.take(24)) }
        disposed.cancel()
        late.complete(FavoritesPage(data.take(50), data.size, 50))
        pending.join()
        check(disposed.state.identity == null && disposed.state.items.size == 24)
        println("D02 STATE PASS: retained append/refresh failures, late append after delete, rebuilt offsets, removed focus neighbour, return and dispose")
    }

    private fun diagnosticOwnership() {
        val logs = mutableListOf<String>()
        val diag = PlaybackDiagnostics(logs::add)
        val first = diag.begin(1, 100)
        diag.decoder(first, false, "c2.vendor.hevc.decoder")
        diag.decoder(first, true, "c2.android.aac.decoder")
        diag.audioTrack(first, 2, 48000, 12, false)
        diag.firstFrame(first, 250, "Media3.onRenderedFirstFrame")
        diag.firstFrame(first, 300, "duplicate")
        diag.stalled(first, true, 310, false) // Background fill / startup is not rebuffering.
        check(diag.snapshot(320).getInt("rebufferCount") == 0)
        diag.stalled(first, true, 400, true)
        diag.stalled(first, false, 480, true)
        check(diag.snapshot(490).getLong("rebufferDurationMs") == 80L)
        check(diag.actualDecoder("media3", emptyMap()).getJSONObject("audio").getString("value") == "c2.android.aac.decoder")
        check(diag.actualAudioOutput("media3", emptyMap()).getInt("encoding") == 2)
        val next = diag.begin(2, 500)
        diag.firstFrame(first, 510, "stale")
        diag.stalled(first, true, 520, true)
        diag.retry(first, 530)
        diag.audioTrack(first, 5, 192000, 63, true)
        diag.decoder(first, true, "stale-decoder")
        check(diag.snapshot(540).isNull("firstFrameMs") && diag.snapshot(540).getInt("rebufferCount") == 0)
        check(diag.snapshot(540).getInt("retryCountInSession") == 1)
        check(diag.actualAudioOutput("media3", emptyMap()).getString("value") == "unknown")
        check(diag.actualDecoder("media3", emptyMap()).getJSONObject("audio").getString("value") == "unknown")
        val mpv = LoadIdentity(next.generation, 42, false)
        diag.activate(mpv, 550)
        diag.firstFrame(mpv, 600, "mpv.ready")
        diag.retry(mpv, 620)
        val software = mpv.copy(physicalId = 43, software = true)
        diag.activate(software, 650)
        diag.firstFrame(mpv, 660, "late-native-frame")
        check(diag.snapshot(680).isNull("firstFrameMs"))
        check(diag.actualDecoder("mpv", emptyMap()).getJSONObject("audio").getString("value") == "unknown")
        val readback = mapOf("audio-decoder" to "aac", "current-ao" to "audiotrack", "audio-out-params/format" to "s16")
        check(diag.actualAudioOutput("mpv", readback).getJSONObject("format").getString("value") == "s16")
        val secondSession = PlaybackDiagnostics()
        secondSession.begin(1, 700)
        check(secondSession.sessionId != diag.sessionId && secondSession.snapshot(710).getInt("retryCountInSession") == 0)
        val events = diag.snapshot(720).getJSONArray("events")
        repeat(events.length()) { index ->
            val event = events.getJSONObject(index)
            check(event.getString("sessionId") == diag.sessionId)
            check(event.getJSONObject("loadIdentity").has("generation"))
            check(event.getJSONObject("loadIdentity").has("physicalId"))
        }
        check(logs.none { it.contains("://") || it.contains("token", true) })
        println("D15 PASS: readback/unknown, no strategy inference, late events rejected, native software retry, independent sessions, numeric identity-only event logs")
    }
}
