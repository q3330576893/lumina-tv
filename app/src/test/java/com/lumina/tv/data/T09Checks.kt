package com.lumina.tv.data

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import com.lumina.tv.AppRuntime
import com.lumina.tv.diag.PageLog
import com.lumina.tv.player.AudioCapabilities
import com.lumina.tv.player.sourceDeliveryHint
import com.lumina.tv.player.sourceHint
import com.lumina.tv.ui.screens.HeroExposureHistory
import com.lumina.tv.ui.screens.HeroExposureTracker
import com.lumina.tv.ui.screens.HeroImageLayer
import com.lumina.tv.ui.screens.SeasonDirectoryStatus
import com.lumina.tv.ui.screens.detailRequestIsCurrent
import com.lumina.tv.ui.screens.heroDisplayLayer
import com.lumina.tv.ui.screens.heroHourContext
import com.lumina.tv.ui.screens.heroViewportVisible
import com.lumina.tv.ui.screens.loadDetailParts
import com.lumina.tv.ui.screens.pickHeroItems
import com.lumina.tv.ui.screens.rememberScopedHomeHeroItems
import com.lumina.tv.ui.screens.settings.libraryItemCountDescription
import com.lumina.tv.ui.screens.settings.loadLibraryCounts
import com.lumina.tv.ui.screens.splashShimmerOffsetPx
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Offline production policies, real HTTP and Compose. Native rendering remains a device check. */
object T09Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking { run() }

    suspend fun run() {
        detailColdWarmRequests()
        detailJoinAndOwners()
        logoLanguageOrder()
        parentImageFallbacks()
        sourceDeliveryAndRasters()
        countScopeAndFailures()
        densityConversions()
        weightedSelection()
        exposureAccounting()
        hourlyAccountComposition()
        sourceWiring()
        println("V6-T09 PASS: request-owned cold/warm joins; account/client/revision fences; zh/en/any logos; indexed parent fallback; capability/selection hints; cropped rasters; unknown scoped counts; dp/px; seeded weighted hourly selection and drawn account-local exposure")
    }

    private fun source(id: String) = MediaSource(id, id, "mkv", true, true, emptyList())
    private fun item(id: String, type: String = "Series") = MediaItem(id, id, type)

    private suspend fun detailColdWarmRequests() = coroutineScope {
        val calls = CopyOnWriteArrayList<String>()
        val server = Http { request ->
            calls += request.path
            val user = request.query["UserId"] ?: request.path.substringAfter("/Users/").substringBefore('/')
            when {
                request.path.endsWith("/Seasons") -> 200 to JSONObject().put("Items", JSONArray().put(
                    JSONObject().put("Id", "season-$user").put("Name", "Season $user").put("Type", "Season"))).toString()
                request.path.endsWith("/PlaybackInfo") -> 200 to JSONObject().put("MediaSources", JSONArray().put(
                    JSONObject().put("Id", "source-$user").put("MediaStreams", JSONArray().put(
                        JSONObject().put("Type", "Video").put("Width", 1920).put("Height", 800))))).toString()
                else -> 200 to JSONObject().put("Id", "shared").put("Name", "Account $user").put("Type", "Series").toString()
            }
        }
        val a = EmbyClient(server.base, userId = "a")
        val b = EmbyClient(server.base, userId = "b")
        val previous = AppRuntime.client
        try {
            LibraryCache.clear()
            AppRuntime.client = a
            val revision = LibraryCache.userDataRevision
            val main = a.getItemForDetail("shared")
            val cold = loadDetailParts(main, { a.getSeasons(main.id) }, { a.getMediaSources(main.id) })
            check(cold.directory.seasons.single().id == "season-a")
            check(cold.sources.single().id == "source-a")
            LibraryCache.putDetail(main.id, checkNotNull(cold.cacheData(main)), revision)
            check(calls.size == 3 && calls.count { it.endsWith("/PlaybackInfo") } == 1)
            val cached = checkNotNull(LibraryCache.detail(main.id))
            check(cached.seasons.single().id == "season-a" && calls.size == 3)
            // Warm rendering is request-free, then one independent refresh of each part.
            val fresh = async { a.getItem(main.id) }
            val warm = loadDetailParts(checkNotNull(cached.item), { a.getSeasons(main.id) }, { a.getMediaSources(main.id) }, cached.sources)
            LibraryCache.putDetail(main.id, checkNotNull(warm.cacheData(checkNotNull(fresh.await()))), revision)
            check(calls.size == 6 && calls.count { it.endsWith("/Seasons") } == 2)
            check(calls.none { it.endsWith("/Episodes") })
            AppRuntime.client = b
            LibraryCache.clear()
            check(LibraryCache.detail(main.id) == null && !detailRequestIsCurrent(a, revision))
            val other = b.getItemForDetail("shared")
            val otherParts = loadDetailParts(other, { b.getSeasons(other.id) }, { b.getMediaSources(other.id) })
            LibraryCache.putDetail(other.id, checkNotNull(otherParts.cacheData(other)))
            check(calls.size == 9 && LibraryCache.detail("shared")?.seasons?.single()?.id == "season-b")
            check(LibraryCache.detail("shared")?.sources?.single()?.id == "source-b")
        } finally {
            AppRuntime.client = previous
            LibraryCache.clear()
            closeT07Client(a); closeT07Client(b); server.close()
        }
        println("T09 detail HTTP PASS: cold 3; warm render 0 + refresh 3; no duplicate Episodes; same ID belongs to the new account")
    }

    private suspend fun detailJoinAndOwners() = coroutineScope {
        val previous = AppRuntime.client
        val a = EmbyClient("https://example.invalid/Emby", userId = "a")
        val b = EmbyClient("https://example.invalid/Emby", userId = "b")
        val replacementA = EmbyClient("https://example.invalid/Emby", userId = "a")
        try {
            LibraryCache.clear()
            AppRuntime.client = a
            val revision = LibraryCache.userDataRevision
            val seasonStarted = CompletableDeferred<Unit>()
            val sourceStarted = CompletableDeferred<Unit>()
            val seasonRelease = CompletableDeferred<Unit>()
            val sourceRelease = CompletableDeferred<Unit>()
            val seasonPublished = CompletableDeferred<Unit>()
            val ownedSeason = item("request-season", "Season")
            var uiSeasons = listOf(item("old-session-season", "Season"))
            val pending = async {
                loadDetailParts(item("shared"),
                    { seasonStarted.complete(Unit); seasonRelease.await(); listOf(ownedSeason) },
                    { sourceStarted.complete(Unit); sourceRelease.await(); listOf(source("owned-source")) },
                    onSeasons = { uiSeasons = it.seasons; seasonPublished.complete(Unit) })
            }
            withTimeout(2_000) { seasonStarted.await(); sourceStarted.await() }
            check(!pending.isCompleted) // Both requests have started before either is released.
            seasonRelease.complete(Unit)
            withTimeout(2_000) { seasonPublished.await() }
            check(uiSeasons == listOf(ownedSeason))
            uiSeasons = listOf(item("another-selection-season", "Season"))
            sourceRelease.complete(Unit)
            val result = pending.await()
            check(result.cacheData(item("shared"))?.seasons == listOf(ownedSeason) && uiSeasons != listOf(ownedSeason))
            AppRuntime.client = b
            check(!detailRequestIsCurrent(a, revision))
            AppRuntime.client = replacementA // Same credentials still retire the original request owner.
            check(!detailRequestIsCurrent(a, revision))
            AppRuntime.client = a
            LibraryCache.clear()
            check(!detailRequestIsCurrent(a, revision))
            val current = LibraryCache.userDataRevision
            LibraryCache.putDetail("shared", checkNotNull(result.cacheData(item("shared"))), revision)
            check(LibraryCache.detail("shared") == null)
            check(detailRequestIsCurrent(a, current))

            for (seasons in listOf<suspend () -> List<MediaItem>>({ emptyList() }, { error("offline") })) {
                val failed = loadDetailParts(item("failed"), seasons, { emptyList() })
                check(failed.directory.status == SeasonDirectoryStatus.FAILURE && failed.cacheData(item("failed")) == null)
            }
            var movieSeasonRequests = 0
            val movie = loadDetailParts(item("movie", "Movie"), { movieSeasonRequests++; emptyList() }, { emptyList() })
            check(movieSeasonRequests == 0 && movie.cacheData(item("movie", "Movie")) != null)
            val entered = CompletableDeferred<Unit>()
            var completed = false
            val cancelled = launch {
                loadDetailParts(item("cancel"), { entered.complete(Unit); awaitCancellation() }, { awaitCancellation() })
                completed = true
            }
            entered.await(); cancelled.cancelAndJoin()
            check(!completed && cancelled.isCancelled)

            // A late completed join must not overwrite the replacement account or revision.
            for (switchAccount in listOf(true, false)) {
                AppRuntime.client = a
                val oldRevision = LibraryCache.userDataRevision
                val enteredLate = CompletableDeferred<Unit>()
                val releaseLate = CompletableDeferred<Unit>()
                val late = async {
                    val parts = loadDetailParts(item("late"), { enteredLate.complete(Unit); releaseLate.await(); listOf(ownedSeason) }, { emptyList() })
                    if (detailRequestIsCurrent(a, oldRevision)) LibraryCache.putDetail("late", checkNotNull(parts.cacheData(item("late"))), oldRevision)
                }
                enteredLate.await()
                if (switchAccount) AppRuntime.client = b
                LibraryCache.clear()
                LibraryCache.putDetail("late", LibraryCache.DetailData(item("replacement"), emptyList(), emptyList(), emptyList(), System.currentTimeMillis()))
                releaseLate.complete(Unit); late.await()
                check(LibraryCache.detail("late")?.item?.id == "replacement")
            }
        } finally {
            AppRuntime.client = previous; LibraryCache.clear()
            closeT07Client(a); closeT07Client(b); closeT07Client(replacementA)
        }
        println("T09 join PASS: parallel start, immutable joined seasons despite UI changes, failed/empty retry, cancellation, revision and same-account replacement/ABA fences")
    }

    private fun logoLanguageOrder() {
        fun logo(language: String?, path: String?) = JSONObject().put("iso_639_1", language ?: JSONObject.NULL)
            .put("file_path", path ?: JSONObject.NULL)
        val all = listOf(logo("ja", "/ja.png"), logo("en", "/en.png"), logo("zh", "/zh.png"),
            logo("zh", ""), logo("zh", null), JSONObject(), JSONObject.NULL)
        repeat(80) { seed -> check(preferredTmdbLogoPath(JSONArray(all.shuffled(java.util.Random(seed.toLong())))) == "/zh.png") }
        repeat(40) { seed ->
            val noChinese = listOf(logo(null, "/none.png"), logo("ja", "/ja.png"), logo("en", "/en.png"))
            check(preferredTmdbLogoPath(JSONArray(noChinese.shuffled(java.util.Random(seed.toLong())))) == "/en.png")
        }
        for (alias in listOf("cn", "ZH", "zh-CN", "zh_Hans")) {
            check(preferredTmdbLogoPath(JSONArray().put(logo("en", "/en")).put(logo(alias, "/zh"))) == "/zh")
        }
        check(preferredTmdbLogoPath(JSONArray().put(logo(null, "/fallback"))) == "/fallback")
        check(preferredTmdbLogoPath(JSONArray().put(logo("ja", "/japanese-only"))) == "/japanese-only")
        check(preferredTmdbLogoPath(JSONArray().put(logo("zh", null))) == null)
        println("T09 logo PASS: shuffled zh > en > arbitrary, aliases, null/blank paths")
    }

    private fun parentImageFallbacks() {
        val requests = CopyOnWriteArrayList<String>()
        val failAll = AtomicBoolean(false)
        val server = Http { req ->
            requests += req.path
            if (!failAll.get() && req.path.endsWith("/parent/Images/Backdrop/2")) 200 to "image" else 404 to "{}"
        }
        val client = EmbyClient(server.base, userId = "u")
        try {
            val media = item("own").copy(imageTag = "poster", parentBackdropItemId = "parent",
                parentBackdropTags = linkedMapOf(7 to "parent-7", -2 to "bad", 0 to "parent-0", 2 to "parent-2", 1 to ""))
            val candidates = client.wideImageCandidates(media, 1280, quality = 73)
            val parents = candidates.filter { it.source == "parent" }
            check(parents.map { URI(it.url).path.substringAfterLast('/') } == listOf("0", "2", "7", "0"))
            check(URI(parents.last().url).query.split('&').none { it.startsWith("tag=") })
            check(parents.all { "maxWidth=1280" in it.url && "quality=73" in it.url })
            val winner = candidates.firstOrNull { candidate ->
                client.playbackHttpClient.newCall(Request.Builder().url(candidate.url).build()).execute().use { it.isSuccessful }
            }
            check(winner == parents[1] && requests.size == 3) // Own /0 and parent /0 fail before parent /2 succeeds.
            failAll.set(true)
            val allFailed = parents.all { candidate ->
                client.playbackHttpClient.newCall(Request.Builder().url(candidate.url).build()).execute().use { !it.isSuccessful }
            }
            check(allFailed && requests.last().endsWith("/parent/Images/Backdrop/0"))
            val bareParent = client.wideImageCandidates(media.copy(parentBackdropTags = emptyMap()), 1280)
            check(bareParent.count { it.source == "parent" } == 1)
            val noZeroTag = client.wideImageCandidates(media.copy(parentBackdropTags = mapOf(3 to "only-3")), 1280)
            check(noZeroTag.filter { it.source == "parent" }.map { URI(it.url).path.substringAfterLast('/') } == listOf("3", "0"))
            check(client.backdropUrl("parent", "tag") == client.backdropUrl("parent", 0, "tag"))
            check(runCatching { client.backdropUrl("parent", -1, "tag") }.isFailure)
        } finally { closeT07Client(client); server.close() }
        println("T09 images PASS: unsorted legal indices, invalid/blank tags, HTTP /0 failures -> /2, untagged /0 retained and all-fail path")
    }

    private fun sourceDeliveryAndRasters() {
        val directStream = source("ds").copy(supportsDirectPlay = false)
        check(sourceDeliveryHint(directStream) == "可 DirectStream")
        check(sourceHint(directStream, "media3", AudioCapabilities(emptyMap())).label == "可 DirectStream")
        for (mode in PlaybackMode.entries) check(sourceDeliveryHint(directStream, mode)?.startsWith("已选") == true)
        check(sourceDeliveryHint(directStream, PlaybackMode.Transcode) == "已选转码")
        check(sourceDeliveryHint(source("direct")) == null)
        check(sourceDeliveryHint(directStream.copy(supportsDirectStream = false)) == "无直放/DirectStream 能力")
        val cases = listOf(Triple(1920, 800, "1920×800"), Triple(3840, 1600, "4K · 3840×1600"),
            Triple(3840, 2160, "4K"), Triple(1920, 1080, "1080P"), Triple(1280, 720, "720P"),
            Triple(1080, 1920, "1080×1920"), Triple(1280, 536, "1280×536"),
            Triple(4096, 1716, "4K · 4096×1716"), Triple(7680, 4320, "7680×4320"))
        val client = EmbyClient("https://example.invalid")
        val parse = EmbyClient::class.java.getDeclaredMethod("parseItem", JSONObject::class.java).apply { isAccessible = true }
        try {
            for ((width, height, expected) in cases) {
                val video = JSONObject().put("Type", "Video").put("Width", width).put("Height", height)
                val json = JSONObject().put("Id", "raster").put("MediaStreams", JSONArray().put(video))
                check(client.parseMediaSource(json).resolution == expected)
                check((parse.invoke(client, json) as MediaItem).resolution == expected)
            }
            check(mediaResolutionLabel(null, null) == null && mediaResolutionLabel(0, -1) == null)
            check(mediaResolutionLabel(null, 800) == "800p" && mediaResolutionLabel(1920, null) == "1920px 宽")
        } finally { closeT07Client(client) }
        println("T09 display PASS: DS capability versus selected route; item/source cropped/portrait/exact/partial rasters; no invented 1080 lines")
    }

    private suspend fun countScopeAndFailures() = coroutineScope {
        val calls = CopyOnWriteArrayList<RequestInfo>()
        val mode = AtomicReference("success")
        val release = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val server = Http { req ->
            calls += req
            if (mode.get() == "hold") { entered.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
            when (mode.get()) {
                "failure" -> 401 to "{}"
                "missing" -> 200 to "{\"Items\":[]}"
                else -> 200 to JSONObject().put("Items", JSONArray()).put("TotalRecordCount",
                    if (req.query["ParentId"] == "empty") 0 else if ("/Users/b/" in req.path) 7 else 123).toString()
            }
        }
        val a = EmbyClient(server.base, userId = "a")
        val b = EmbyClient(server.base, userId = "b")
        val anonymous = EmbyClient(server.base)
        val views = listOf(LibraryView("same-id", "TV", "tvshows"), LibraryView("empty", "Movies", "movies"))
        try {
            val success = loadLibraryCounts(a, views)
            check(success == mapOf("same-id" to 123, "empty" to 0))
            check(calls.all { it.path == "/Emby/Users/a/Items" && it.query["Recursive"] == "true" && it.query["Limit"] == "1" })
            check(calls.single { it.query["ParentId"] == "same-id" }.query["IncludeItemTypes"] == "Series")
            check(calls.single { it.query["ParentId"] == "empty" }.query["IncludeItemTypes"] == "Movie")
            check(loadLibraryCounts(b, views.take(1))["same-id"] == 7)
            mode.set("failure")
            val failed = loadLibraryCounts(a, views)
            check(failed.keys == success.keys && failed.values.all { it == null })
            check(libraryItemCountDescription(failed["same-id"], true) == "条目数未知")
            check(libraryItemCountDescription(null, false) == "正在获取条目数")
            check(libraryItemCountDescription(0, true) == "0 个条目")
            mode.set("missing")
            check(loadLibraryCounts(a, views).values.all { it == null })
            val before = calls.size
            check(loadLibraryCounts(anonymous, views).values.all { it == null } && calls.size == before)
            check(loadLibraryCounts(a, emptyList()).isEmpty() && calls.size == before)
            mode.set("hold")
            val cancelled = async { loadLibraryCounts(a, views.take(1)) }
            withTimeout(2_000) { entered.await() }
            cancelled.cancelAndJoin()
            check(cancelled.isCancelled)
            check(calls.none { it.path.endsWith("/Items/Counts") || it.path == "/Emby/Items" })
        } finally { release.countDown(); closeT07Client(a); closeT07Client(b); closeT07Client(anonymous); server.close() }
        println("T09 counts PASS: user/basePath/parent/type scope; real zero vs failed/missing unknown; new account same ID; cancellation; no server-wide fallback")
    }

    private fun densityConversions() {
        for (density in listOf(0.75f, 1f, 1.5f, 2f, 3f)) for (fontScale in listOf(0.85f, 1f, 2f)) {
            for (dp in listOf(-60f, 0f, 12.25f, 160f)) {
                check(splashShimmerOffsetPx(dp, Density(density, fontScale)) == (dp * density).roundToInt())
            }
            check(heroViewportVisible(100f * density, 80f * density, 100f * density, 40f * density))
            check(!heroViewportVisible(100f * density, 80f * density, 100f * density, 39f * density))
        }
        check(!heroViewportVisible(0f, 80f, 10f, 10f) && !heroViewportVisible(100f, 80f, 0f, 80f))
        println("T09 density PASS: 60 dp->px cases, negative/fractional offsets, font scale independence and visible-area density invariance")
    }

    private fun weightedSelection() {
        val pool = (0..59).map { i -> item("id-${i.toString().padStart(2, '0')}", "Movie").copy(
            genres = listOf(if (i % 2 == 0) "Drama" else "Comedy"), communityRating = (i % 10).toDouble()) }
        val recent = pool.take(5).mapTo(mutableSetOf()) { it.id }
        val shown = pool.takeLast(5).mapTo(mutableSetOf()) { it.id }
        val expected = pickHeroItems(pool, recent, seed = 41234, shownIds = shown)
        repeat(60) { i -> check(pickHeroItems(pool.shuffled(java.util.Random(i.toLong())), recent, seed = 41234, shownIds = shown) == expected) }
        check(pickHeroItems(pool + pool, recent, 100, seed = 1).size == 40)
        check(pickHeroItems(pool, recent, 0, seed = 1).isEmpty() && pickHeroItems(emptyList(), recent, seed = 1).isEmpty())
        check(expected.map { it.id }.distinct().size == expected.size)
        val low = item("low", "Movie")
        val high = item("high", "Movie").copy(backdropTag = "wide", logoTag = "logo", communityRating = 9.0)
        var favored = 0
        var penalized = 0
        repeat(512) { i ->
            val seed = i.toLong() * 0x9E3779B9L
            if (pickHeroItems(listOf(low, high), emptySet(), 1, seed).single().id == "high") favored++
            if (pickHeroItems(listOf(low, high), emptySet(), 1, seed, setOf("high")).single().id == "high") penalized++
        }
        check(favored > 420 && favored < 512 && penalized < favored - 45) { "Weights are ineffective: favored=$favored penalized=$penalized" }
        check(heroHourContext(3_599_999) == 0L && heroHourContext(3_600_000) == 1L && heroHourContext(7_199_999) == 1L)
        println("T09 weighted PASS: 60 shuffled pools, dedup/top40/no replacement, seed replay, weighting $favored/512 vs exposure penalty $penalized/512, hour boundary")
    }

    private fun exposureAccounting() {
        val history = HeroExposureHistory(maxAccounts = 2, maxItems = 2)
        val tracker = HeroExposureTracker()
        val a = checkNotNull(identityScope("https://host/Emby", "a"))
        val b = checkNotNull(identityScope("https://host/Emby", "b"))
        val bottom = HeroImageLayer("shared", "same-url")
        val top = HeroImageLayer("new", "same-url")
        var events = 0
        val record = { account: String, id: String -> events++; history.record(account, id) }
        pickHeroItems(listOf(item("shared")), emptySet(), seed = 1)
        check(history.snapshot(a).isEmpty())
        tracker.onDraw(a, bottom, false, true, true, true, record)
        tracker.onDraw(a, bottom, true, false, true, true, record)
        tracker.onDraw(a, bottom, true, true, false, true, record)
        tracker.onDraw(a, bottom, true, true, true, false, record)
        tracker.onDraw(null, bottom, true, true, true, true, record)
        check(events == 0 && history.snapshot(a).isEmpty())
        tracker.onDraw(a, heroDisplayLayer(bottom, top, false, true), true, true, true, true, record)
        check(events == 1 && history.snapshot(a) == setOf("shared"))
        repeat(5) { tracker.onDraw(a, bottom, true, true, true, true, record) }
        check(events == 1)
        tracker.onDraw(a, heroDisplayLayer(bottom, top, true, true), true, true, true, true, record)
        check(events == 2 && history.snapshot(a) == setOf("shared", "new"))
        check(history.snapshot(b).isEmpty())
        tracker.onDraw(b, bottom, true, true, true, true, record)
        check(history.snapshot(b) == setOf("shared") && history.snapshot(a) == setOf("shared", "new"))
        history.record(a, "third")
        check(history.snapshot(a) == setOf("new", "third") && history.snapshot(b) == setOf("shared"))
        history.record("third-account", "one")
        check(history.snapshot(b).isEmpty() && history.snapshot(a) == setOf("new", "third"))
        println("T09 exposure PASS: selection is read-only; foreground/viewport/loaded/drawn gates, transition owning IDs, repeat draws, same ID in different accounts and bounded LRU")
    }

    private suspend fun hourlyAccountComposition() = coroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val pool = (1..24).map { item("hour-$it", "Movie") }
        val history = HeroExposureHistory()
        var account by mutableStateOf("account-a")
        var hour by mutableStateOf(10L)
        var unrelated by mutableIntStateOf(0)
        var selected = emptyList<MediaItem>()
        suspend fun flush() { Snapshot.sendApplyNotifications(); yield(); recomposer.awaitIdle() }
        try {
            composition.setContent {
                unrelated
                selected = rememberScopedHomeHeroItems(account, hour, pool, emptyList(), emptyList(), emptyList(), emptySet(), history)
            }
            val original = selected
            val logs = PageLog.tail()
            repeat(8) { unrelated++; flush(); check(selected === original && PageLog.tail() == logs) }
            check(history.snapshot(account).isEmpty())
            history.record(account, original.first().id)
            unrelated++; flush()
            check(selected === original) // An exposure cannot cause a selection/recomposition feedback loop.
            hour = 11; flush()
            check(selected !== original)
            check(selected == pickHeroItems(pool, pool.mapTo(mutableSetOf()) { it.id },
                seed = hour xor (account.hashCode().toLong() shl 32), shownIds = history.snapshot(account)))
            account = "account-b"; flush()
            check(history.snapshot(account).isEmpty())
            check(selected == pickHeroItems(pool, pool.mapTo(mutableSetOf()) { it.id },
                seed = hour xor (account.hashCode().toLong() shl 32), shownIds = emptySet()))
            check(history.snapshot("account-a") == setOf(original.first().id))
        } finally { composition.dispose(); recomposer.close(); runner.join() }
        println("T09 Compose PASS: same-hour unrelated recompositions are stable; hour/account invalidate selection; exposure does not reshuffle; account A history never reaches B")
    }

    private fun sourceWiring() {
        val root = if (File("src/main").isDirectory) File(".") else File("app")
        fun read(path: String) = root.resolve("src/main/java/com/lumina/tv/$path").readText().replace("\r\n", "\n")
        val detail = read("ui/screens/DetailScreen.kt")
        val main = detail.substringAfter("LaunchedEffect(id, client, accountScope, detailRevision, detailRetry) {")
            .substringBefore("LaunchedEffect(id, accountScope, item?.type")
        check(main.contains("parts.cacheData(it0)") && main.contains("parts.cacheData(refreshedItem)"))
        check(!main.contains("LibraryCache.DetailData(it0, seasons") && !main.contains("seasons = seasonDirectory.seasons"))
        check(main.split("detailRequestIsCurrent(c, cacheRevision)").size >= 6)
        check(detail.contains("mediaResolutionLabel(stream.width, stream.height)"))
        val settings = read("ui/screens/settings/SettingsHome.kt")
        check(settings.contains("remember(client, accountScope, revision, views)"))
        check(settings.contains("AppRuntime.client !== c || revision != LibraryCache.userDataRevision"))
        check(!settings.contains("runCatching { AppRuntime.client") && !settings.contains("if (count != null) itemCounts"))
        val home = read("ui/screens/HomeScreen.kt")
        check(home.contains("LaunchedEffect(views, client, reloadToken, userDataRevision)"))
        check(home.contains("var views by remember(homeIdentity)"))
        check(home.contains("rememberScopedHomeHeroItems(homeIdentity, heroHour"))
        check(home.contains("heroHour = heroHourContext(now)") && home.contains("Math.floorMod(now, 3_600_000L)"))
        check(home.contains(".onGloballyPositioned") && home.contains("boundsInWindow()"))
        check(home.contains("drawContent()\n                exposureTracker.onDraw("))
        check(home.contains("loadedHeroImages = loadedHeroImages + image") && home.contains("account == homeIdentity"))
        check(!home.contains("recentlyShownHeroIds"))
        check(read("ui/screens/SplashScreen.kt").contains("splashShimmerOffsetPx(shimmerX, this)"))
        val logoRequest = read("data/TmdbClient.kt").substringAfter("private suspend fun fetchLogo(")
            .substringBefore("private suspend fun getJson(")
        check(logoRequest.contains("preferredTmdbLogoPath(logos)"))
        check(!logoRequest.contains("include_image_language") && !logoRequest.contains("&language="))
        println("T09 wiring PASS: production joins publish request-owned parts, current ownership gates, scoped count replacement, hourly account keys and post-draw exposure")
    }

    private data class RequestInfo(val path: String, val query: Map<String, String>)
    private class Http(private val reply: (RequestInfo) -> Pair<Int, String>) : AutoCloseable {
        private val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.POST) {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                }
                val (status, text) = reply(RequestInfo(session.uri, session.parameters.mapValues { it.value.first() }))
                val code = when (status) { 200 -> Response.Status.OK; 401 -> Response.Status.UNAUTHORIZED; else -> Response.Status.NOT_FOUND }
                return newFixedLengthResponse(code, "application/json", text)
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val base = "http://127.0.0.1:${server.listeningPort}/Emby"
        override fun close() { server.stop() }
    }
}
