package com.lumina.tv

import com.lumina.tv.data.B2MemoryPrefs
import com.lumina.tv.data.MdblistClient
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.Prefs
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.data.resolveTmdbMetadataItem
import com.lumina.tv.data.tmdbMediaPath
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.DetailRecommendationCache
import com.lumina.tv.data.RelatedCacheKey
import com.lumina.tv.data.identityScope
import com.lumina.tv.data.bucketOf
import com.lumina.tv.ui.screens.loadDetailRecommendations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** B6 protocol/behavior checks plus explicit source guards for Compose-only wiring. */
object B6Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        pickerWiringAndMemory()
        tmdbIdentity()
        mdblistProtocol()
        independentRatingsWiring()
        withTimeout(20_000L) { recommendationFailureCache() }
        previewOffGate()
        println("B6 PASS: Q-023 picker render/action/source memory; Q-024 Episode parent identity; Q-027 ratings array; Q-028 independent rating effect; Q-030 failure/partial/empty recommendation cache; Q-031 early off gate/clear state/zero resolver requests")
    }

    private fun source(path: String): String {
        val root = if (File("src/main").isDirectory) File(".") else File("app")
        return root.resolve("src/main/java/com/lumina/tv/$path").readText()
    }

    private fun pickerWiringAndMemory() {
        val detail = source("ui/screens/DetailScreen.kt")
        check(detail.contains("if (showSettingsPicker) {"))
        val picker = detail.substringAfter("if (showSettingsPicker) {").substringBefore("// 单集版本选择")
        listOf("PickerDialog(", "title = pickerTitle", "options = pickerOptions", "currentKey = pickerCurrentKey",
            "pickerAction(key)", "onClose = { showSettingsPicker = false }").forEach { check(picker.contains(it)) { "Picker wiring missing: $it" } }
        check(picker.substringAfter("pickerAction(key)").contains("showSettingsPicker = false"))
        check(detail.contains("rememberAudioTrack(currentItem.id, it, activeSource)"))
        check(detail.contains("\"-1\" -> \"off\"; \"auto\" -> \"auto\"; else -> \"track:emby:\$key\""))
        check(detail.contains("}, activeSource)"))

        val memory = B2MemoryPrefs(mapOf("secrets_migrated_v1" to true,
            "base_url" to "https://b6.invalid/emby", "user_id" to "b6"))
        val prefs = Prefs(memory.sp)
        val tracks = listOf(
            MediaStream(1, "Audio", "aac", "eng", null, true, false, null),
            MediaStream(2, "Audio", "aac", "chi", null, false, false, null),
            MediaStream(3, "Subtitle", "subrip", "eng", null, false, false, null),
        )
        val a = MediaSource("a", "A", "mkv", true, true, tracks)
        val b = a.copy(id = "b")
        prefs.rememberAudioTrack("item", 2, a)
        check(prefs.audioTrackFor("item", a) == 2 && prefs.audioTrackFor("item", b) == null)
        check(memory.values.keys.any { it.startsWith("audio_identity_") })
        prefs.rememberSubtitleTrack("item", "track:emby:3", a)
        check(prefs.subtitlePreferenceFor("item", a) == "track:emby:3")
        check(prefs.subtitlePreferenceFor("item", b) == null)
        check(memory.values.keys.any { it.startsWith("subtitle_identity_") })
        prefs.rememberSubtitleTrack("item", "off", a)
        check(prefs.subtitlePreferenceFor("item", a) == "off")
        prefs.rememberSubtitleTrack("item", "auto", a)
        check(prefs.subtitlePreferenceFor("item", a) == "auto")
        check(Prefs(memory.sp).subtitlePreferenceFor("item", a) == "auto")
        check(Prefs(memory.sp).audioTrackFor("item", a) == 2)
    }

    private suspend fun tmdbIdentity() {
        val movie = MediaItem("movie", "Movie", type = "Movie", year = 2001)
        val series = MediaItem("series", "Show", type = "Series", year = 1999,
            providerIds = mapOf("imdb" to "tt-parent", "tmdb" to "100", "tvdb" to "200"))
        val episode = MediaItem("episode", "Finale", type = "Episode", seriesId = "series", seriesName = "Show", year = 2026,
            providerIds = mapOf("imdb" to "tt-episode", "tmdb" to "900", "tvdb" to "901"))
        var loads = 0
        check(resolveTmdbMetadataItem(movie) { error("Movie must not load a parent") } === movie)
        check(resolveTmdbMetadataItem(series) { error("Series must not load a parent") } === series)
        val resolved = resolveTmdbMetadataItem(episode) { id -> loads++; check(id == "series"); series }
        check(resolved === series && resolved.year == 1999 && resolved.providerIds["tmdb"] == "100" && loads == 1)
        check(tmdbMediaPath(resolved.type) == "tv" && tmdbMediaPath(movie.type) == "movie")
        for (type in listOf("Episode", "Season", "MusicVideo", "", "unknown")) {
            check(tmdbMediaPath(type) == null)
            check(TmdbClient.lookup(type, "same name", 2026) == null)
            check(TmdbClient.lookupHero(type, "same name", 2026, null) == null)
            check(TmdbClient.externalIds(type, "900") == null)
            check(TmdbClient.externalLinks(type, "same name", 2026, "tt-episode", "900", "901") == null)
            check(MdblistClient.ratings(type, "tt-episode", "900") == null)
        }
        check(resolveTmdbMetadataItem(episode.copy(seriesId = null)) { error("No identity must skip") } == null)
        check(resolveTmdbMetadataItem(movie.copy(type = "Season")) { error("Unsupported type must skip") } == null)
        check(resolveTmdbMetadataItem(episode) { null } == null)
        check(resolveTmdbMetadataItem(episode) { throw IOException("offline") } == null)
        check(resolveTmdbMetadataItem(episode) { series.copy(id = "wrong") } == null)
        check(resolveTmdbMetadataItem(episode) { series.copy(type = "Movie") } == null)
        check(runCatching { resolveTmdbMetadataItem(episode) { throw CancellationException("left page") } }
            .exceptionOrNull() is CancellationException)
        val detail = source("ui/screens/DetailScreen.kt")
        val player = source("ui/screens/PlayerScreen.kt")
        check(detail.contains("resolveTmdbMetadataItem(current)") && player.contains("resolveTmdbMetadataItem(item)"))
        check(detail.contains("val providerIds = metadataItem?.providerIds.orEmpty()"))
        check(detail.contains("metadataItem?.type?.let(::tmdbMediaPath)"))
        check(detail.contains("val current = metadataItem ?: return@LaunchedEffect"))
        check(!player.contains("TmdbClient.lookup(item.type"))
    }

    private fun mdblistProtocol() {
        val fixture = requireNotNull(javaClass.getResource("/b6/mdblist-media-info.json")).readText()
        val ratings = requireNotNull(MdblistClient.parseRatings(JSONObject(fixture)))
        check(ratings == MdblistClient.Ratings(8.1, 97, 90, 87, 78, 76, 8.0, 86)) { ratings }
        val fallback = requireNotNull(MdblistClient.parseRatings(JSONObject("""{"ratings":[
            {"source":"IMDB","value":null,"score":"81"},
            {"source":"tomato","value":"95"},
            {"source":"popcorn","value":"NaN","score":88},
            {"source":"metacritic","value":null},
            {"source":"trakt","value":"Infinity","score":77},
            {"source":"tmdb","score":74},
            {"source":"letterboxd","score":80},
            {"source":"unknown","value":999},null,42]}""")))
        check(fallback == MdblistClient.Ratings(8.1, 95, 88, null, 77, 74, 8.0, null))
        for (json in listOf("{}", """{"ratings":{ "imdb":{"rating":8.1}},"score":80}""",
            """{"ratings":[]}""", """{"ratings":[{"source":"imdb","value":-1,"score":101}]}""",
            """{"ratings":[{"source":"unknown","value":8}]}""", """{"ratings":null,"score":80}""")) {
            check(MdblistClient.parseRatings(JSONObject(json)) == null) { "Unusable response cached as success: $json" }
        }
        check(MdblistClient.parseRatings(JSONObject("""{"ratings":[],"score":"86"}"""))?.score == 86)
        check(MdblistClient.parseRatings(JSONObject("""{"ratings":[{"source":"imdb","value":0}]}"""))?.imdb == 0.0)
        for (alias in listOf("tomatoesaudience", "audience", "rtaudience")) {
            check(MdblistClient.parseRatings(JSONObject("""{"ratings":[{"source":"$alias","value":90}]}"""))?.popcorn == 90)
        }
    }

    private fun independentRatingsWiring() {
        val detail = source("ui/screens/DetailScreen.kt")
        val mainLoad = detail.substringAfter("LaunchedEffect(id, accountScope, detailRetry) {")
            .substringBefore("LaunchedEffect(id, accountScope, item?.type")
        check(mainLoad.contains("val cached = LibraryCache.detail(id)") && mainLoad.contains("val seasonJob = launch"))
        check(mainLoad.contains("val sourceJob = launch") && mainLoad.contains("joinAll(seasonJob, sourceJob)"))
        check(!mainLoad.contains("TmdbClient.") && !mainLoad.contains("resolveTmdbMetadataItem"))
        check(mainLoad.contains("cacheRevision") && mainLoad.contains("empty-season-directory"))
        check(mainLoad.contains("cached.item?.type == \"Series\"") && mainLoad.contains("seasonRefreshJob?.join()"))
        check(detail.contains("LaunchedEffect(id, client, metadataItem, item?.rating, item?.communityRating)"))
        val rating = detail.substringAfter("LaunchedEffect(id, client, metadataItem, item?.rating, item?.communityRating)")
            .substringBefore("LaunchedEffect(id, client, metadataItem) {")
        check(rating.contains("current.rating == null && current.communityRating == null"))
        check(rating.contains("catchingRequest { TmdbClient.lookup(metadata.type, metadata.name, metadata.year) }"))
        check(!rating.contains("appScope") && !rating.contains("scope.launch"))
    }

    private suspend fun recommendationFailureCache() = coroutineScope {
        val similarStatus = AtomicInteger(500)
        val genreStatus = AtomicInteger(500)
        val ioFailure = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<String>()
        val delayed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deadlineRelease = CountDownLatch(1)
        val server = Loopback { target ->
            requests += target
            if (target.contains("io-failure") && ioFailure.get()) return@Loopback 0 to ""
            val similar = target.contains("/Similar")
            if (target.contains("cancelled")) {
                delayed.countDown()
                release.await(8, TimeUnit.SECONDS)
            }
            if (target.contains("deadline")) deadlineRelease.await(8, TimeUnit.SECONDS)
            val status = if (similar) similarStatus.get() else genreStatus.get()
            val count = when {
                target.contains("empty") -> 0
                target.contains("page-failure") && target.contains("StartIndex=0") -> 60
                else -> 1
            }
            val code = if (target.contains("page-failure") && target.contains("StartIndex=60")) 500 else status
            val items = (0 until count).joinToString(",") { i ->
                """{"Id":"${if (similar) "similar" else "genre"}-$i","Name":"candidate","Type":"Movie","Genres":["Drama"]}"""
            }
            code to """{"Items":[$items]}"""
        }
        val client = EmbyClient(server.base, userId = "b6")
        fun item(id: String, genre: String? = "Drama") = MediaItem(id, id, type = "Movie", genres = listOfNotNull(genre))
        suspend fun load(current: MediaItem) = loadDetailRecommendations(client, current.id, current)
        try {
            // The client must expose a fast HTTP failure before the aggregator can classify it.
            check(runCatching { client.getSimilarItems("direct-failure") }.isFailure)
            val both = item("both-failed")
            check(load(both).isEmpty())
            similarStatus.set(200); genreStatus.set(200)
            val beforeRecovery = requests.size
            check(load(both).isNotEmpty() && requests.size > beforeRecovery)
            val cached = requests.size
            check(load(both).isNotEmpty() && requests.size == cached)

            for ((id, similarCode, genreCode) in listOf(
                Triple("similar-failed", 500, 200), Triple("genre-failed", 200, 500),
            )) {
                similarStatus.set(similarCode); genreStatus.set(genreCode)
                val current = item(id)
                check(load(current).isNotEmpty()) // Preserve the successful source's partial data.
                val before = requests.size
                similarStatus.set(200); genreStatus.set(200)
                check(load(current).isNotEmpty() && requests.size > before)
            }
            similarStatus.set(500)
            val onlySimilar = item("only-similar", null)
            check(load(onlySimilar).isEmpty())
            val beforeSimilarRecovery = requests.size
            similarStatus.set(200)
            check(load(onlySimilar).isNotEmpty() && requests.size > beforeSimilarRecovery)

            val io = item("io-failure", null)
            check(load(io).isEmpty())
            val beforeIoRecovery = requests.size
            ioFailure.set(false)
            check(load(io).isNotEmpty() && requests.size > beforeIoRecovery)

            // Successful empty results retain the existing negative-cache behavior.
            val empty = item("empty", "empty")
            check(load(empty).isEmpty())
            val beforeEmptyHit = requests.size
            check(load(empty).isEmpty() && requests.size == beforeEmptyHit)

            // A later genre page failing cannot publish the earlier pages as a complete result.
            val paged = item("page-failure", "page-failure")
            check(load(paged).size == 12)
            val beforePageRetry = requests.size
            check(load(paged).size == 12 && requests.size > beforePageRetry)

            // A caller leaving the page must still get cancellation, never a cached empty list.
            val cancelled = item("cancelled", null)
            val pending = async { load(cancelled) }
            while (delayed.count != 0L) yield()
            pending.cancel()
            check(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            release.countDown()
            val scope = requireNotNull(identityScope(server.base, "b6"))
            val cancelledKey = RelatedCacheKey(scope, cancelled.id, "Movie", bucketOf(cancelled).first.toString(), "")
            check(DetailRecommendationCache.get(cancelledKey, "unused") == null)
            val beforeCancelRetry = requests.size
            check(load(cancelled).isNotEmpty() && requests.size > beforeCancelRetry)

            val deadline = item("deadline", null)
            check(load(deadline).isEmpty()) // The Similar endpoint's own 4s deadline is a failed source.
            deadlineRelease.countDown()
            val beforeDeadlineRetry = requests.size
            check(load(deadline).isNotEmpty() && requests.size > beforeDeadlineRetry)

            // Cache TTL/revision policy itself is unchanged for authoritative empty responses.
            val emptyKey = RelatedCacheKey(scope, "ttl", "Movie", "", "")
            val revision = DetailRecommendationCache.revision(scope)
            DetailRecommendationCache.put(emptyKey, emptyList(), "day", revision)
            val now = System.currentTimeMillis()
            check(DetailRecommendationCache.get(emptyKey, "other-day", now + 29 * 60_000L) == emptyList<MediaItem>())
            check(DetailRecommendationCache.get(emptyKey, "day", now + 31 * 60_000L) == null)
        } finally {
            release.countDown()
            deadlineRelease.countDown()
            client.close()
            server.close()
        }
    }

    private suspend fun previewOffGate() {
        val detail = source("ui/screens/DetailScreen.kt")
        val effect = detail.substringAfter("val backgroundMode = AppRuntime.prefs?.detailBackgroundVideo")
            .substringBefore("LaunchedEffect(item?.id)")
        check(effect.substringBefore("// Gate").contains("detailPreviewRetry, backgroundMode)"))
        val offIndex = effect.indexOf("if (backgroundMode == \"off\")")
        check(offIndex >= 0)
        val gate = effect.substring(offIndex).substringBefore("if (!previewMetadataReady)")
        for (clear in listOf("detailPreviewUrl = null", "detailPreviewHeaders = emptyMap()", "detailPreviewKey = \"\"",
            "detailPreviewIsTrailer = false", "detailPreviewStartMs = null", "detailVideoPlaying = false",
            "detailPreviewFailure = null", "detailPreviewFailures = emptyList()", "detailPreviewFromStage = \"hls\"",
            "detailPreviewStage = null", "detailPreviewRequestKey = \"\"", "detailPreviewFailureKey = \"\"",
            "detailPreviewHlsKey = \"\"", "detailPreviewRetry = 0", "previewResults.clear()",
            "previewHeaderResults.clear()", "previewStartTimes.clear()", "previewStages.clear()", "return@LaunchedEffect")) {
            check(gate.contains(clear)) { "Off gate must clear: $clear" }
        }
        val gateEnd = effect.indexOf("return@LaunchedEffect", offIndex)
        for (request in listOf("previewResults[requestKey]", "c.getEpisodes(", "c.getLocalTrailers(", "c.getMediaSources(", "c.resolveDetailPreviewUrl(")) {
            check(effect.indexOf(request) > gateEnd) { "Preview work before off gate: $request" }
        }
        check(!effect.take(effect.indexOf("if (!previewMetadataReady)")).contains("c.get"))
        check(detail.contains("if (previewActive && backgroundMode != \"off\") DetailVideoBackground("))
        val requests = AtomicInteger()
        val server = Loopback { requests.incrementAndGet(); 500 to "{}" }
        val client = EmbyClient(server.base, userId = "b6")
        try {
            for (type in listOf("Movie", "Series", "Episode")) {
                val resolution = client.resolveDetailPreviewUrl("off-$type", "source", "off")
                check(resolution.url == null && requests.get() == 0)
            }
        } finally { client.close(); server.close() }
    }

    private class Loopback(private val response: (String) -> Pair<Int, String>) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}/emby"
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "b6-http").apply { isDaemon = true } }
        private val acceptor = thread(isDaemon = true, name = "b6-accept") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    sockets += socket
                    workers.submit {
                        try {
                            socket.use {
                                val input = it.getInputStream().bufferedReader()
                                val target = input.readLine()?.split(' ')?.getOrNull(1) ?: return@submit
                                while (!input.readLine().isNullOrEmpty()) { /* GET headers */ }
                                val (code, body) = response(target)
                                if (code == 0) return@submit // Deliberately close without HTTP headers.
                                val bytes = body.toByteArray()
                                it.getOutputStream().write(("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                            }
                        } catch (_: IOException) { /* Expected for caller cancellation. */ }
                        finally { sockets.remove(socket) }
                    }
                }
            } catch (closed: SocketException) { check(server.isClosed) }
        }
        override fun close() {
            server.close()
            acceptor.join(1_000L)
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            check(workers.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
