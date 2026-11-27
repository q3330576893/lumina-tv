package com.lumina.tv

import com.lumina.tv.data.*
import fi.iki.elonen.NanoHTTPD
import com.lumina.tv.player.MpvPlayerController.LoadIdentity
import com.lumina.tv.player.PlaybackDiagnostics
import com.lumina.tv.ui.screens.DetailHlsFailureCache
import com.lumina.tv.ui.screens.HeroImageLayer
import com.lumina.tv.ui.screens.detailCastEntries
import com.lumina.tv.ui.screens.detailPersonKey
import com.lumina.tv.ui.screens.heroDisplayLayer
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
        favoritesHttpProgressChecks()
        favoritesProgressChecks()
        WatchHistoryClearChecks.run()
        diagnosticOwnership()
        detailPageRecords()
        sharedHeroArtwork()
        println("P6 PASS: V6-T05 A/B progress guards plus 137 favorites, response-local totals/offsets, mutation rebuild, retained failures, stale loads, focus anchors, actual readback and session-bound metrics")
    }

    private fun items(count: Int) = (0 until count).map { MediaItem("id$it", "Item $it", "Movie", isFavorite = true) }

    private fun detailPageRecords() {
        var now = 0L
        val failures = DetailHlsFailureCache { now }
        failures.add("broken")
        now = 10 * 60 * 1000L - 1
        check("broken" in failures)
        now++
        check("broken" !in failures)
        failures.add("broken") // Expiry permits a fresh attempt and failure window.
        check("broken" in failures)
        check("broken" !in DetailHlsFailureCache { now }) // A different page starts clean.
        failures.add("")
        check("" !in failures)

        val bounded = DetailHlsFailureCache { now }
        repeat(128) { bounded.add("hls:$it") }
        bounded.add("hls:0") // A repeated failure refreshes its eviction position.
        bounded.add("hls:128")
        check("hls:0" in bounded && "hls:1" !in bounded)
        check((2..128).all { "hls:$it" in bounded })

        val actor = MediaPerson("person", "Performer", role = "Lead", type = "Actor")
        val secondRole = actor.copy(role = "Narrator")
        val director = actor.copy(role = null, type = "Director")
        val anonymous = actor.copy(id = null, name = "Another performer")
        val cast = detailCastEntries(listOf(actor, actor, secondRole, director, anonymous, actor.copy(name = "")))
        check(cast == listOf(actor, secondRole, director, anonymous))
        check(cast.map(::detailPersonKey).distinct().size == cast.size)
        val delimiterInId = actor.copy(id = "a|b", type = "c")
        val delimiterInType = actor.copy(id = "a", type = "b|c")
        check(detailPersonKey(delimiterInId) != detailPersonKey(delimiterInType))
        check(detailPersonKey(actor) != detailPersonKey(actor.copy(id = null, name = actor.id!!)))
        println("P6 BATCH-6 PASS: page-local HLS TTL/capacity and distinct cast roles")
    }

    private fun sharedHeroArtwork() {
        val first = MediaItem("first", "First movie", "Movie")
        val second = MediaItem("second", "Second series", "Series")
        val items = listOf(first, second).associateBy { it.id }
        val bottom = HeroImageLayer(first.id, "https://artwork/shared.jpg")
        val top = HeroImageLayer(second.id, bottom.imageUrl)
        fun displayed(loaded: Boolean, pastMidpoint: Boolean) =
            heroDisplayLayer(bottom, top, loaded, pastMidpoint)?.let { items[it.itemId] }
        check(displayed(loaded = false, pastMidpoint = true) == first)
        check(displayed(loaded = true, pastMidpoint = false) == first)
        check(displayed(loaded = true, pastMidpoint = true) == second)
        check(heroDisplayLayer(top, null, false, true)?.itemId == second.id)
        check(heroDisplayLayer(null, top, true, true)?.itemId == second.id)
        println("P6 BATCH-6 PASS: shared artwork retains each layer's media identity through the wipe")
    }

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

    private suspend fun favoritesHttpProgressChecks() {
        FavoritesCache.clear()
        val data = items(101)
        val phase = AtomicReference("known")
        val offsets = CopyOnWriteArrayList<Int>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                check(session.uri == "/emby/Users/fixture/Items")
                val offset = session.parameters.getValue("StartIndex").single().toInt()
                offsets += offset
                val mode = phase.get()
                if (mode == "failure" || (mode == "fail-second" && offset > 0))
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "fixture failure")
                val rows = data.drop(if (mode == "same") 0 else offset).take(50)
                val body = JSONObject().put("Items", JSONArray(rows.map {
                    JSONObject().put("Id", it.id).put("Name", it.name).put("Type", it.type)
                }))
                if (mode != "unknown") body.put("TotalRecordCount", 101)
                return newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val client = EmbyClient("http://127.0.0.1:" + server.listeningPort + "/emby", userId = "fixture")
        try {
            withTimeout(15_000L) {
                for (mode in listOf("known", "unknown")) {
                    phase.set(mode)
                    offsets.clear()
                    val pager = FavoritesPager("t05-http-" + mode, { 0L }) { limit, start -> client.getFavorites(limit, start) }
                    pager.refresh(emptyList())
                    while (pager.state.canAutoAppend) {
                        check(offsets.size <= 4)
                        pager.append(automatic = true)
                    }
                    check(pager.state.items.map { it.id } == data.map { it.id } && pager.state.error == null)
                    check(pager.state.nextOffset == null && pager.state.total == if (mode == "known") 101 else null)
                    check(offsets.toList() == if (mode == "known") listOf(0, 50, 100) else listOf(0, 50, 100, 101))
                }
                phase.set("known")
                val pager = FavoritesPager("t05-http-unchanged", { 0L }) { limit, start -> client.getFavorites(limit, start) }
                pager.refresh(emptyList())
                val anchor = FavoritesAnchor("id21", 21, 19, "id23", 23, 50)
                pager.saveAnchor(anchor)
                val before = pager.state.items
                phase.set("same")
                pager.append(automatic = true)
                check(pager.state.items == before && pager.anchor == anchor && pager.state.nextOffset == 50)
                check(pager.state.error != null && !pager.state.canAutoAppend && !pager.state.appending)
                val stoppedAt = offsets.size
                repeat(5) { pager.append(automatic = true) }
                check(offsets.size == stoppedAt)
                phase.set("known")
                pager.append() // The existing retry button can resume the rejected offset.
                pager.append()
                check(pager.state.items.size == 101 && pager.state.error == null && pager.anchor == anchor)
                val all = pager.state.items
                for (mode in listOf("same", "failure", "fail-second")) {
                    phase.set(mode)
                    val start = offsets.size
                    pager.refresh(emptyList())
                    check(pager.state.items == all && pager.anchor == anchor && pager.state.refreshRequired)
                    check(pager.state.error != null && !pager.state.refreshing && !pager.state.canAutoAppend)
                    check(offsets.size - start == if (mode == "failure") 1 else 2)
                    repeat(3) { pager.append(automatic = true) }
                    check(offsets.size - start == if (mode == "failure") 1 else 2)
                }
                phase.set("known")
                pager.refresh(emptyList())
                check(pager.state.items == all && pager.state.error == null && pager.anchor == anchor)
                phase.set("failure")
                val failed = FavoritesPager("t05-http-all-failed", { 0L }) { limit, start -> client.getFavorites(limit, start) }
                failed.refresh(data.take(24))
                check(failed.state.items == data.take(24) && failed.state.error != null && failed.state.refreshRequired)
            }
        } finally { client.close(); server.stop() }
        println("V6-T05-B HTTP PASS: 101 known/unknown total, terminal empty probe, actual 2xx repeated append/refresh, full/partial HTTP failures, retained anchors and explicit retry")
    }

    private suspend fun favoritesProgressChecks() = coroutineScope {
        FavoritesCache.clear()
        val data = items(101)
        val anchor = FavoritesAnchor("id21", 21, 17, "id23", 23, 50)
        val invalid = listOf(
            FavoritesPage(data.take(50).reversed(), 101, 100), // Same IDs in a different order.
            FavoritesPage(data.take(25), 101, 75), // Not a repeated page, but no new IDs.
            FavoritesPage(data.drop(50).take(50), 101, 50),
            FavoritesPage(data.drop(50).take(50), 101, 0),
            FavoritesPage(emptyList(), 101, null),
            FavoritesPage(emptyList(), null, 51),
            FavoritesPage(data.drop(50).take(50), 101, null),
            FavoritesPage(listOf(MediaItem("", "Invalid", "Movie")), null, 51),
        )
        for ((index, bad) in invalid.withIndex()) {
            var calls = 0
            var answer: FavoritesPage? = null
            val pager = FavoritesPager("t05-append-" + index, { 0L }) { limit, offset ->
                calls++
                answer ?: data.drop(offset).take(limit).let { FavoritesPage(it, 101, favoritesNextOffset(offset, it.size, 101)) }
            }
            pager.refresh(emptyList())
            pager.saveAnchor(anchor)
            val before = pager.state.items
            answer = bad
            pager.append(automatic = true)
            check(calls == 2 && pager.state.items == before && pager.state.nextOffset == 50 && pager.anchor == anchor)
            check(pager.state.error != null && !pager.state.canAutoAppend && !pager.state.appending)
            repeat(3) { pager.append(automatic = true) }
            check(calls == 2)
            answer = null
            pager.append()
            check(calls == 3 && pager.state.items.size == 100 && pager.state.error == null && pager.anchor == anchor)
        }
        val badRefreshes = listOf(
            FavoritesPage(data.take(3), null, 0),
            FavoritesPage(data.take(3), null, -1),
            FavoritesPage(emptyList(), 101, null),
            FavoritesPage(emptyList(), null, 1),
            FavoritesPage(data.take(3), 101, null),
            FavoritesPage(listOf(MediaItem("", "Invalid", "Movie")), null, 1),
        )
        for ((index, bad) in badRefreshes.withIndex()) {
            var calls = 0
            val pager = FavoritesPager("t05-refresh-" + index, { 0L }) { _, _ -> calls++; bad }
            pager.saveAnchor(anchor)
            pager.refresh(data.take(24))
            check(calls == 1 && pager.state.items == data.take(24) && pager.anchor == anchor)
            check(pager.state.error != null && pager.state.refreshRequired && !pager.state.refreshing)
            repeat(3) { pager.append(automatic = true) }
            check(calls == 1)
        }
        var calls = 0
        val bounded = FavoritesPager("t05-append-budget", { 0L }, maxPages = 2) { limit, offset ->
            calls++
            val rows = data.drop(offset).take(limit)
            FavoritesPage(rows, 101, favoritesNextOffset(offset, rows.size, 101))
        }
        bounded.refresh(emptyList())
        bounded.saveAnchor(anchor)
        bounded.append(automatic = true)
        check(calls == 2 && bounded.state.items.size == 100 && bounded.state.nextOffset == 100)
        check(bounded.state.error != null && !bounded.state.canAutoAppend && bounded.anchor == anchor)
        repeat(5) { bounded.append(automatic = true) }
        check(calls == 2)
        bounded.append()
        check(calls == 3 && bounded.state.items.size == 101 && bounded.state.error == null)

        val key = "t05-refresh-budget"
        val fullAnchor = anchor.copy(loaded = 101)
        FavoritesCache.put(key, FavoritesPagingState(FavoritesQueryIdentity(key, 0, 0), data), fullAnchor)
        var tiny = true
        var refreshCalls = 0
        val rebuild = FavoritesPager(key, { 0L }, maxPages = 2) { limit, offset ->
            refreshCalls++
            val rows = data.take(75).drop(offset).take(if (tiny) 1 else limit)
            FavoritesPage(rows, if (tiny) null else 75, favoritesNextOffset(offset, rows.size, if (tiny) null else 75))
        }
        rebuild.refresh(emptyList())
        check(refreshCalls == 2 && rebuild.state.items == data && rebuild.anchor == fullAnchor)
        check(rebuild.state.error != null && rebuild.state.refreshRequired && !rebuild.state.refreshing)
        repeat(3) { rebuild.append(automatic = true) }
        check(refreshCalls == 2)
        tiny = false
        rebuild.refresh(emptyList())
        check(refreshCalls == 4 && rebuild.state.items == data.take(75) && rebuild.state.error == null)

        // The production cap applies across successful automatic append calls, not only inside refresh.
        var defaultCalls = 0
        val capped = FavoritesPager("t05-default-budget", { 0L }) { _, offset ->
            defaultCalls++
            FavoritesPage(listOf(MediaItem("cap" + offset, "Item", "Movie")), null, offset + 1)
        }
        capped.refresh(emptyList())
        while (capped.state.canAutoAppend) { check(defaultCalls <= FAVORITES_MAX_PAGES); capped.append(automatic = true) }
        check(defaultCalls == FAVORITES_MAX_PAGES && capped.state.items.size == FAVORITES_MAX_PAGES && capped.state.total == null)
        repeat(3) { capped.append(automatic = true) }
        check(defaultCalls == FAVORITES_MAX_PAGES)
        capped.append()
        check(defaultCalls == FAVORITES_MAX_PAGES + 1 && capped.state.error == null)

        var changed = false
        var totalCalls = 0
        val totals = FavoritesPager("t05-changing-total", { 0L }) { limit, offset ->
            totalCalls++
            val rows = data.drop(offset).take(limit)
            FavoritesPage(rows, if (changed) 102 else 101, favoritesNextOffset(offset, rows.size, if (changed) 102 else 101))
        }
        totals.refresh(emptyList())
        totals.saveAnchor(anchor)
        changed = true
        totals.append(automatic = true)
        check(totalCalls == 2 && totals.state.items == data.take(50) && totals.anchor == anchor)
        check(totals.state.refreshRequired && totals.state.error != null)
        repeat(3) { totals.append(automatic = true) }
        check(totalCalls == 2)
        changed = false
        totals.refresh(emptyList())
        check(totalCalls == 3 && totals.state.error == null)
        check(runCatching { favoritesNextOffset(Int.MAX_VALUE, 1, null) }.isFailure)
        favoritesCancellationChecks()
        val screen = java.io.File("src/main/java/com/lumina/tv/ui/screens/FavoritesScreen.kt").readText()
        check("s.canAutoAppend" in screen && "pager.append(automatic = true)" in screen)
        check("scope.launch { pager.append() }" in screen && "!pager.state.refreshRequired" in screen)
        check("state.total?.toString() ?: \"未知\"" in screen)
        println("V6-T05-B STATE PASS: new IDs, forward offsets/overflow, page fingerprints, zero progress, refresh/append budgets, automatic suppression, explicit retry, cancellation and screen retry/anchor wiring")
    }

    private suspend fun favoritesCancellationChecks() = coroutineScope {
        val data = items(101)
        val anchor = FavoritesAnchor("id21", 21, 17, "id23", 23, 50)
        var cancelled: CancellationException? = CancellationException("internal transport cancellation")
        var calls = 0
        val pager = FavoritesPager("t05-internal-cancel", { 0L }) { limit, offset ->
            calls++
            cancelled?.let { throw it }
            val rows = data.drop(offset).take(limit)
            FavoritesPage(rows, 101, favoritesNextOffset(offset, rows.size, 101))
        }
        pager.saveAnchor(anchor)
        try { pager.refresh(data.take(24)); error("Refresh cancellation swallowed") }
        catch (failure: CancellationException) { check(failure === cancelled) }
        check(pager.state.items == data.take(24) && pager.anchor == anchor && !pager.state.refreshing && pager.state.error != null)
        cancelled = null
        pager.refresh(emptyList())
        cancelled = CancellationException("internal append cancellation")
        try { pager.append(); error("Append cancellation swallowed") }
        catch (failure: CancellationException) { check(failure === cancelled) }
        check(pager.state.items.size == 50 && pager.anchor == anchor && !pager.state.appending && !pager.state.canAutoAppend)
        val count = calls
        repeat(3) { pager.append(automatic = true) }
        check(calls == count)
        cancelled = null
        pager.append()
        check(pager.state.items.size == 100 && pager.state.error == null)

        // A transport may finish after caller cancellation. ensureActive must still reject its page.
        for (append in listOf(false, true)) {
            var block = false
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = FavoritesPager("t05-user-cancel-" + append, { 0L }) { limit, offset ->
                if (block) withContext(NonCancellable) { started.complete(Unit); release.await() }
                val rows = data.drop(offset).take(limit)
                FavoritesPage(rows, 101, favoritesNextOffset(offset, rows.size, 101))
            }
            late.refresh(emptyList())
            late.saveAnchor(anchor)
            val before = late.state.items
            block = true
            val pending = launch { if (append) late.append() else late.refresh(emptyList()) }
            started.await()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            check(pending.isCancelled && late.state.items == before && late.anchor == anchor)
            check(!late.state.refreshing && !late.state.appending && !late.state.canAutoAppend && late.state.error != null)
        }
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
