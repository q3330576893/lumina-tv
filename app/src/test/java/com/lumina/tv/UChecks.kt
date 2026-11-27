package com.lumina.tv

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import com.lumina.tv.player.PlaybackDelivery
import com.lumina.tv.player.PlaybackFailureReason
import com.lumina.tv.player.PlaybackPath
import com.lumina.tv.player.PlaybackPathFailure
import com.lumina.tv.player.PlaybackPathFailures
import com.lumina.tv.player.selectPlaybackEngine
import com.lumina.tv.player.mpvWasExplicitlyRequested
import com.lumina.tv.pair.PAIRING_TOKEN_TTL_MS
import com.lumina.tv.pair.PairingEndpoint
import com.lumina.tv.pair.refreshPairingAdvertisement
import com.lumina.tv.ui.screens.HeroActionFocus
import com.lumina.tv.ui.screens.HeroDirectionKey
import com.lumina.tv.ui.screens.EpisodePickerPages
import com.lumina.tv.ui.screens.homeNeedsHeroReset
import com.lumina.tv.ui.screens.pickerNeighbor
import com.lumina.tv.ui.screens.shouldHeroConsumeCarouselDirection
import kotlinx.coroutines.runBlocking
import com.lumina.tv.data.PlayableItemRequestGate
import com.lumina.tv.ui.screens.resolveWithSlowNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File

object UChecks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        heroAndBack()
        detailPreviewLifecycle()
        controlsAndEpisodePickers()
        media3Default()
        pairingQr()
        resumeRemoval()
        resolvingNotice()
    }

    private fun heroAndBack() {
        check(!homeNeedsHeroReset(0, 0, keyboard = true, heroHasFocus = true, resetPending = false))
        check(homeNeedsHeroReset(0, 0, keyboard = true, heroHasFocus = false, resetPending = false))
        check(homeNeedsHeroReset(0, 1, keyboard = true, heroHasFocus = true, resetPending = false))
        check(homeNeedsHeroReset(1, 0, keyboard = false, heroHasFocus = false, resetPending = false))
        check(homeNeedsHeroReset(0, 0, keyboard = true, heroHasFocus = true, resetPending = true))
        check(!homeNeedsHeroReset(0, 0, keyboard = false, heroHasFocus = false, resetPending = false))
        for (direction in HeroDirectionKey.entries) {
            check(shouldHeroConsumeCarouselDirection(HeroActionFocus.ARTWORK, direction, 2))
            check(!shouldHeroConsumeCarouselDirection(HeroActionFocus.ARTWORK, direction, 1))
        }

        val home = source("ui/screens/HomeScreen.kt")
        check("maxHeight * 0.86f" in home && "maxHeight - 172.dp" in home)
        check("heroHeight = heroHeight" in home && "item(key = \"home-hero\")" in home)
        check("heroArtworkFocus.requestFocus()" in home && ".focusRequester(artworkFocus)" in home)
        check("recListState.scroll {" !in home && "recListState.scrollToItem(0)" in home)
        val backdrop = home.substringAfter("private fun HeroBackdropImage(").substringBefore("@Composable")
        check("ContentScale.Crop" in backdrop)
        val fit = ContentScale.Fit.computeScaleFactor(Size(2560f, 1080f), Size(1920f, 1080f))
        check(fit.scaleX == 0.75f && fit.scaleY == 0.75f)
        println("B14 PASS U2/U3/U12: viewport geometry, artwork focus, carousel keys and return-to-top routing")
    }

    private fun detailPreviewLifecycle() {
        val detail = source("ui/screens/DetailScreen.kt")
        val resolution = detail.substringAfter("val backgroundMode =").substringBefore("LaunchedEffect(item?.id)")
        check("LaunchedEffect(previewActive," in resolution && "if (!previewActive)" in resolution)
        check(resolution.indexOf("if (!previewActive)") in 0 until resolution.indexOf("c.getEpisodes"))
        check("firstPreviewEpisode?.let { Triple(it.id, it.contentVersion, it.runtimeTicks) }" in detail)
        val background = source("ui/components/DetailVideoBackground.kt")
        check("key(url, mediaKey, bypassCache, startAtMs, loopWholeItem)" in background)
        check("onDispose { controller.close() }" in background && ".setLooper(looper)" in background)
        check("if (firstFrame && previewShouldPlay) 1f else 0f" in background)
        check("readCancellable(builder.build())" in background)
        val preview = source("ui/components/DetailPreviewPlayer.kt")
        val release = preview.substringAfter("fun close()").substringBefore("override fun onSurfaceTextureAvailable")
        check(release.indexOf("worker.post") in 0 until release.indexOf("oldPlayer.release()"))
        check("output !== rendered.surface" in preview && "viewOutput === rendered" in preview)
        check("if (!closed) action()" in preview)
        val player = source("ui/screens/PlayerScreen.kt")
        check(player.indexOf("DetailPreviewPlayer.awaitRelease()") in 0 until player.lastIndexOf("loadSource(forceTranscode = reloadForceTranscode)"))
        println("B14 PASS U5/U6: off-main preview teardown, cancellable probing and current-surface first frame")
    }

    private suspend fun controlsAndEpisodePickers() {
        val pages = EpisodePickerPages<Int>()
        val shared = mutableMapOf<String, List<Int>>()
        var requests = 0
        check(pages.cached("a") { shared["a"] } == null)
        check(pages.fetch("a", { requests++; listOf(1, 2, 3) }, { shared["a"] = it }).episodes == listOf(1, 2, 3))
        repeat(15) { check(pages.cached("a") { error("Loaded season was fetched again") }?.size == 3) }
        check(pages.fetch("b", { requests++; error("offline") }, { error("Cached a failure") }).error != null)
        check(pages.cached("b") { null } == null)
        check(pages.fetch("b", { requests++; emptyList() }, { shared["b"] = it }).episodes.isEmpty())
        check(pages.cached("b") { error("Loaded empty season was fetched again") }.orEmpty().isEmpty())
        check(EpisodePickerPages<Int>().cached("a") { shared["a"] } == listOf(1, 2, 3))
        check(requests == 3)
        check(pickerNeighbor(0, -1, 1) == null && pickerNeighbor(0, 1, 1) == null)
        check(pickerNeighbor(10, 1, 12) == 11 && pickerNeighbor(11, 1, 12) == null)

        val player = source("ui/screens/PlayerScreen.kt")
        val speed = player.substringAfter("val panelWidth =").substringBefore("if (seasons.isNotEmpty())")
        check("else if (quickContentHasFocus) dismissSpeedPanel()" in speed && ".focusGroup()" in speed)
        check("left = playFocus" in speed && "episodeFocus else menuFocus" in speed)
        check("closeQuickPanel(if (was == QuickPanel.SPEED) speedFocus else episodeFocus)" in player)
        check("LaunchedEffect(quickPanel, selectedSeasonId, item.seriesId, episodePages, episodeRetry)" in player)
        val seasons = player.substringAfter("rowItemsIndexed(seasons").substringBefore("if (episodesLoadFailed) {")
        check(".clickable {" in seasons && ".focusable()" !in seasons)
        val detail = source("ui/screens/DetailScreen.kt")
        check("item?.id, selectedSeason?.id, episodeReloadTick, episodePages)" in detail)
        check(".heightIn(max = 46.dp * 8 + 40.dp).verticalScroll(" in detail)
        check("down = pickerNeighbor(index, 1, entries.size)?.let(entryFocuses::get) ?: FocusRequester.Cancel" in detail)
        println("B14 PASS U7/U8/U9: speed focus exit, loaded and retryable season pages, scrollable bounded version focus")
    }

    private fun media3Default() {
        val path = PlaybackPath("big-file", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "soft")
        val failures = PlaybackPathFailures()
        check(!mpvWasExplicitlyRequested("auto", null))
        check(!mpvWasExplicitlyRequested("media3", null))
        check(mpvWasExplicitlyRequested("auto", "mpv"))
        check(mpvWasExplicitlyRequested("mpv", null))
        check(!mpvWasExplicitlyRequested("mpv", "media3"))
        check(selectPlaybackEngine("auto", null, path, failures, mpvAvailable = true) == "media3")
        check(selectPlaybackEngine("auto", "mpv", path, failures, mpvAvailable = true) == "mpv")
        check(selectPlaybackEngine("auto", "mpv", path, failures, mpvAvailable = false) == "media3")
        failures.record(PlaybackPathFailure(path, PlaybackFailureReason.DECODING, 1, "injected"))
        check(selectPlaybackEngine("auto", null, path, failures, mpvAvailable = true) == null)
        check(selectPlaybackEngine("auto", "mpv", path, failures, mpvAvailable = false) == null)
        check(selectPlaybackEngine("auto", "mpv", path, failures, mpvAvailable = true) == "mpv")
        val mpv = source("player/MpvPlayerController.kt")
        check("postForLoad(load, 100L)" in mpv)
        check("diagnosticLogBuffer.add(line)" in mpv.substringAfter("private fun appendMpvLog").substringBefore("private fun recordPreviousAbnormalSession"))
        val player = source("ui/screens/PlayerScreen.kt")
        check("remember(menuMpv, mpvMenuTrackRevision, subtitlePreference)" in player)
        check("preferMpvForUnsupportedHdr" !in player && "\"soft\" -> enginePinned" !in player)
        check("mpvWasExplicitlyRequested(AppRuntime.prefs?.playerEngine, enginePinned)" in player)
        check("audioDecodeNotice = if (selectedEngine == \"mpv\")" in player && "先尝试 Media3" in player)
        check("已用 mpv 软解" !in player && "正在尝试软解内核" !in player)
        println("B14 PASS U10: Media3 default, explicit engine selection plus targeted audio-output recovery, path exclusions and paced mpv diagnostics")
    }

    private fun pairingQr() {
        val box = PairingEndpoint("192.168.1.10", 8971, 1)
        var sequence = 0
        val first = checkNotNull(refreshPairingAdvertisement(null, box, 0L) { "t${++sequence}" })
        check(first.accepts("t1", PAIRING_TOKEN_TTL_MS - 1L) && !first.accepts("t1", PAIRING_TOKEN_TTL_MS))
        check(!first.accepts("", 1L) && !first.accepts("wrong", 1L))
        check(refreshPairingAdvertisement(first, box, PAIRING_TOKEN_TTL_MS - 1) { error("Rotated a valid QR") } === first)
        val second = checkNotNull(refreshPairingAdvertisement(first, box, PAIRING_TOKEN_TTL_MS) { "t${++sequence}" })
        check(!second.accepts("t1", PAIRING_TOKEN_TTL_MS) && second.accepts("t2", PAIRING_TOKEN_TTL_MS))
        for (endpoint in listOf(box.copy(ip = "192.168.1.11"), box.copy(port = 8972), box.copy(listenerGeneration = 2L))) {
            val rebound = checkNotNull(refreshPairingAdvertisement(second, endpoint, PAIRING_TOKEN_TTL_MS + 1L) { "t${++sequence}" })
            check(!rebound.accepts("t2", PAIRING_TOKEN_TTL_MS + 1L))
            check(rebound.url == "http://${endpoint.ip}:${endpoint.port}/?token=t$sequence")
        }
        check(refreshPairingAdvertisement(second, null, PAIRING_TOKEN_TTL_MS) { error("No listener") } == null)
        check(refreshPairingAdvertisement(second, box, PAIRING_TOKEN_TTL_MS, force = true) { "new" }?.url?.endsWith("token=new") == true)
        val login = source("ui/screens/LoginScreen.kt")
        check("repeatOnLifecycle(Lifecycle.State.RESUMED)" in login && "AppRuntime.refreshPairingUrl(context)" in login)
        check("复制地址" in login && "手动输入" in login)
        println("B14 PASS U1: pairing token expiry, IP/port/listener refresh, manual fallback and authorized URL")
    }

    private fun resumeRemoval() {
        val home = source("ui/screens/HomeScreen.kt")
        val dialog = home.substringAfter("pendingResumeRemoval?.let { item ->").substringBefore("private fun NavBar(")
        check("ResumeRemovalPill(" in dialog && "focusRequester = removeFocus" in dialog)
        check("hideResumeItem(item)" in dialog && "dismissButton" in dialog)
        println("B14 PASS U4: glass action is wired to the existing confirmation and removal")
    }

    private suspend fun resolvingNotice() = coroutineScope {
        val threshold = CompletableDeferred<Unit>()
        val notice = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        var transportCancelled = false
        val slow = async {
            resolveWithSlowNotice(waitForSlow = { threshold.await() }, onSlow = { notice.complete(Unit) }) {
                try { response.await() } finally { transportCancelled = true }
            }
        }
        yield()
        check(!notice.isCompleted)
        threshold.complete(Unit)
        withTimeout(1_000L) { notice.await() }
        slow.cancelAndJoin()
        check(transportCancelled && slow.isCancelled)
        val gate = PlayableItemRequestGate().apply { updateContext("same page") }
        val first = checkNotNull(gate.begin())
        gate.cancel()
        check(!gate.accepts(first))
        val retried = checkNotNull(gate.begin())
        check(gate.accepts(retried) && !gate.accepts(first))
        var notified = false
        val immediate = resolveWithSlowNotice(waitForSlow = { awaitCancellation() }, onSlow = { notified = true }) { "ready" }
        check(immediate == "ready" && !notified)
        val action = source("ui/screens/PlayableItemAction.kt")
        check("lookupJob?.cancel()" in action && "片源响应较慢，仍在尝试" in action && "取消查找" in action)
        println("B14 PASS U11: delayed lookup explanation, real coroutine cancellation, stale ticket rejection and retry")
    }

    private fun source(path: String): String =
        File("src/main/java/com/lumina/tv", path).readText()
}
