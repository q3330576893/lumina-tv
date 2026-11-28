package com.lumina.tv

import com.lumina.tv.data.CacheMediaItemCodec
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.screens.*
import com.lumina.tv.ui.theme.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Offline production-policy and Emby protocol checks for the reference browsing layout. */
object M09Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        imageAndTitleFallbacks()
        railGeometryAndProgress()
        collectionFallbacks()
        embyProtocol()
        searchAndDetailStates()
        productionWiring()
        println("M09 PASS: Emby backdrop/poster/static priority, bounded clearlogo/text fallback, 16:9/12dp/6.5-card geometry, real progress, BoxSet protocol/collage fallback, search focus/budget and detail states")
    }

    private fun imageAndTitleFallbacks() {
        val client = EmbyClient("http://127.0.0.1:12345", userId = "fixture-user")
        val item = MediaItem("item", "Title", imageTag = "poster", backdropTag = "backdrop", logoTag = "logo")
        val candidates = homeImageCandidates(client, item)
        check(candidates.map { it.source } == listOf(HeroImageSource.BACKDROP, HeroImageSource.POSTER, HeroImageSource.STATIC))
        check(candidates[0].url!!.contains("/Images/Backdrop/0") && candidates[1].url!!.contains("/Images/Primary"))
        check(candidates.last().url == null)
        check(homeImageCandidates(client, item.copy(backdropTag = null)).map { it.source } == listOf(HeroImageSource.POSTER, HeroImageSource.STATIC))
        check(homeImageCandidates(client, item.copy(backdropTag = null, imageTag = null)).single().source == HeroImageSource.STATIC)
        check(homeImageCandidates(null, item).single().source == HeroImageSource.STATIC)
        check(homeImageCandidates(client, item.copy(backdropTag = "", imageTag = " ")).single().source == HeroImageSource.STATIC)
        val indexed = homeImageCandidates(client, item.copy(backdropTags = mapOf(2 to "b2", 1 to "b1")))
        check(indexed.take(2).map { it.url!!.substringBefore('?').substringAfterLast('/') } == listOf("1", "2"))
        val episode = item.copy(backdropTag = null, parentBackdropItemId = "series", parentBackdropTags = mapOf(1 to "parent"))
        check(homeImageCandidates(client, episode).first().url!!.contains("/Items/series/Images/Backdrop/1"))
        val thumbnail = homeImageCandidates(client, item, HomeRailTokens.IMAGE_WIDTH)
        check(thumbnail.dropLast(1).all { it.url!!.contains("maxWidth=480") })
        val logo = client.logoUrl(item, maxWidth = 640, maxHeight = 220)
        check(logo!!.contains("/Items/item/Images/Logo") && logo.contains("maxWidth=640") && logo.contains("maxHeight=220"))
        check(client.logoUrl(item.copy(logoTag = null), 640, 220) == null)
        check(client.logoUrl(item.copy(logoTag = " "), 640, 220) == null)
        check(heroUsesTextTitle(null, true))
        check(heroUsesTextTitle(logo, false)) // Both a pending request and an image failure preserve text.
        check(!heroUsesTextTitle(logo, true))
        check(heroUsesTextTitle(logo, true, artworkFailed = true))
        check(homeHeroMetadata(item).isEmpty())
        val metadata = item.copy(genres = listOf("剧情"), year = 2020, endYear = 2026, rating = 8.5)
        check(homeHeroMetadata(metadata) == "剧情 · 2020-2026 · ★8.5")
        check(homeHeroMetadata(metadata.copy(year = null, rating = Double.NaN)) == "剧情")
        check(readableMediaTitle(item.copy(name = "", seriesName = "Series")) == "Series")
        check(readableMediaTitle(item.copy(name = " ")).isNotBlank())
        val cached = checkNotNull(CacheMediaItemCodec.decode(CacheMediaItemCodec.encode(metadata)))
        check(cached.item == metadata && !cached.fields.requiresRefresh)
        client.close()
    }

    private fun railGeometryAndProgress() {
        for (viewport in listOf(640f, 960f, 1280f, 1920f)) {
            val rail = homeRailGeometry(viewport)
            check(abs(rail.width / rail.height - 16f / 9f) < 0.001f)
            check(rail.gap == 12f && HomeRailTokens.CORNER_DP == 12f && HomeRailTokens.PROGRESS_DP == 4f)
            val usable = viewport - 2 * rail.inset
            check(abs(rail.width * 6.5f + rail.gap * 6 - usable) < 0.01f)
            val seventhStart = 6 * (rail.width + rail.gap)
            check(abs((usable - seventhStart) / rail.width - 0.5f) < 0.001f)
            check(rail.gap > rail.width * (TvTokenValues.FOCUS_SCALE - 1f))
            for (fontScale in listOf(1f, 1.3f, 1.5f)) {
                // Image dimensions do not depend on font scaling. The label has its own measured row.
                check(rail.width >= 4 * 10 * fontScale + 12)
            }
        }
        val item = MediaItem("progress", "Progress", runtimeTicks = 100, positionTicks = 25)
        check(browseProgress(item) == 0.25f)
        check(browseProgress(item.copy(positionTicks = 0)) == null)
        check(browseProgress(item.copy(positionTicks = -1)) == null)
        check(browseProgress(item.copy(runtimeTicks = null, playedPercentage = 80.0)) == null)
        check(browseProgress(item.copy(runtimeTicks = 0)) == null)
        check(browseProgress(item.copy(runtimeTicks = -1)) == null)
        check(browseProgress(item.copy(positionTicks = Long.MAX_VALUE)) == 1f)
        for (tier in EffectTier.entries) for (reduced in listOf(false, true)) {
            check(motionPolicy(tier, reduced).duration(200) in 0..220)
            if (tier != EffectTier.FULL || reduced) check(motionPolicy(tier, reduced).duration(200) == 0)
        }
    }

    private fun collectionFallbacks() {
        val posters = (1..6).map { MediaItem("p$it", "Poster $it", imageTag = "tag$it") }
        check(collectionPosterItems(emptyList()).isEmpty())
        check(collectionPosterItems(posters.take(1)) == posters.take(1))
        check(collectionPosterItems(posters.take(2)) == posters.take(1))
        check(collectionPosterItems(posters.take(3)) == posters.take(3))
        check(collectionPosterItems(posters) == posters.take(4))
        check(collectionPosterItems(listOf(posters[0], posters[0], posters[1])) == posters.take(1))
        check(collectionPosterItems(posters.take(3).map { it.copy(imageTag = null) }).isEmpty())
    }

    private suspend fun embyProtocol() {
        val calls = CopyOnWriteArrayList<Map<String, String>>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val query = session.parameters.mapValues { it.value.first() }
                calls += query
                val rows = when {
                    query["ParentId"] == "empty" -> emptyList()
                    query["IncludeItemTypes"] == "BoxSet" -> listOf(JSONObject().put("Id", "collection").put("Name", "华语").put("Type", "BoxSet"))
                    else -> (1..(if (query["ParentId"] == "two") 2 else 4)).map { index ->
                        JSONObject().put("Id", "member$index").put("Name", "Member $index").put("Type", "Movie")
                            .put("ProductionYear", 2020).put("EndDate", "2026-01-01T00:00:00Z")
                            .put("ImageTags", JSONObject().put("Primary", "p$index").put("Logo", "logo$index"))
                            .put("BackdropImageTags", JSONArray().put("backdrop$index"))
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                    JSONObject().put("Items", JSONArray(rows)).put("TotalRecordCount", rows.size).toString())
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "fixture-user")
        try {
            check(loadBoxSets(client).single().id == "collection")
            check(calls.last()["IncludeItemTypes"] == "BoxSet")
            check(loadBoxSets(client, "empty").isEmpty())
            val posters = loadCollectionPosters(client, "collection")
            check(posters.size == 4 && posters.first().endYear == 2026 && posters.first().logoTag == "logo1")
            check(calls.last()["ParentId"] == "collection" && calls.last()["Limit"] == "4")
            check(loadCollectionPosters(client, "two").size == 1)
        } finally { client.close(); server.stop() }
    }

    private fun searchAndDetailStates() {
        for (requested in listOf(false, true)) for (ime in listOf(false, true)) for (input in listOf(false, true)) {
            check(mayFocusSearchResult(requested, ime, input) == (requested && !ime && !input))
        }
        var loaded = 0
        val pages = mutableListOf<Int>()
        while (searchPageSize(loaded) > 0) { val count = searchPageSize(loaded); pages += count; loaded += count }
        check(loaded == 300 && pages == List(7) { 40 } + 20)
        check(searchPageSize(320) == 0)
        check(searchBudgetNotice(300, true) == "已加载前 300 条 / 可能还有更多")
        check(searchBudgetNotice(40, false) == null)
        val item = MediaItem("ready", "Ready")
        check(detailContentState(true, false, false, null) == DetailContentState.LOADING)
        check(detailContentState(false, true, false, null) == DetailContentState.NOT_FOUND)
        check(detailContentState(false, false, true, null) == DetailContentState.ERROR)
        check(detailContentState(false, false, false, null) == DetailContentState.EMPTY)
        check(detailContentState(false, false, false, item.copy(id = "")) == DetailContentState.EMPTY)
        check(detailContentState(false, false, false, item) == DetailContentState.READY)
    }

    private fun productionWiring() {
        val root = File("src/main/java/com/lumina/tv")
        val home = File(root, "ui/screens/HomeScreen.kt").readText()
        val hero = home.substringAfter("private fun Hero(").substringBefore("private fun RowSection(")
        check(hero.countOccurrences("Crossfade(") == 1 && "duration(200)" in hero)
        check("delay(" !in hero && "lookupHero(" !in hero && "AnimatedContent(" !in hero && "Animatable(" !in hero)
        check("items = resume.ifEmpty" in home && "val selectedId = rememberSaveable" in hero)
        check("HeroPresentation(items, selectedId" in hero && "selectedId.value = it.id" in hero)
        check("fillMaxWidth(0.40f)" in hero && "0.92f" in hero && "0.55f" in hero)
        check("onError = { logoLoaded = false }" in hero && "heroUsesTextTitle(logo, logoLoaded, artworkFailed)" in hero)
        check("LazyRow(" in hero && "items(items, key = { it.id })" in hero)
        check("event.key == Key.Menu" in hero && "onPlay = { playAction.play(it) }" in hero)
        val library = File(root, "ui/screens/LibraryBrowse.kt").readText()
        check("if (collections.isNotEmpty()) item" in library && "collectionPosterItems(posters)" in library)
        check("logoUrl(" !in library)
        val cards = File(root, "ui/components/BrowseCards.kt").readText()
        check("aspectRatio(2f / 3f)" in cards && "15.sp" in cards && "12.sp" in cards)
        check("shadow" !in cards && "glow" !in cards && "TvTokens.FocusBorder" in cards)
        val search = File(root, "ui/screens/SearchScreen.kt").readText()
        check("mayFocusSearchResult(resultsFocusRequested, imeVisible, inputHasFocus)" in search)
        check("searchBudgetNotice(pageStart, budgetTruncated)" in search && "budgetTruncated -> EmptyView" in search)
        val detail = File(root, "ui/screens/DetailScreen.kt").readText()
        val primary = detail.substringAfter("Spacer(Modifier.height(18.dp))\n                        Row(")
            .substringBefore("androidx.compose.foundation.layout.FlowRow(")
        check("playAction.play(it0)" in primary && "showSourceMenu = true" !in primary)
        check("DetailUnavailable(contentState" in detail && "browseProgress(ep)?.let" in detail && "?: 0.45f" !in detail)
    }

    private fun String.countOccurrences(value: String) = windowed(value.length).count { it == value }
}
