package com.lumina.tv

import com.lumina.tv.data.EpisodeSourceAvailability
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.PlayableItemResult
import com.lumina.tv.data.episodeSourceAvailability
import com.lumina.tv.data.probeEpisodeSource
import com.lumina.tv.data.probeMediaSources
import com.lumina.tv.data.resolvePlayableItem
import com.lumina.tv.ui.screens.accountFocusTargets
import com.lumina.tv.ui.screens.accountRequesterKeys
import com.lumina.tv.ui.screens.beginSeasonEpisodeLoad
import com.lumina.tv.ui.screens.cachedSeasonEpisodeState
import com.lumina.tv.ui.screens.completeSeasonEpisodeLoad
import com.lumina.tv.ui.screens.libraryAppendProgress
import com.lumina.tv.ui.screens.shouldAppendSearchPage
import com.lumina.tv.ui.screens.shouldAutoLoadEmptySearchPage
import com.lumina.tv.ui.screens.shouldRetryLibraryAppend
import com.lumina.tv.ui.screens.SEARCH_EMPTY_AUTO_PAGE_LIMIT
import com.lumina.tv.ui.screens.LatestItemsLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object E5Checks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        seasonEpisodeLoads()
        libraryAppend()
        searchPaging()
        accountFocus()
        seriesSourceProbe()
        latestItemsRequests()
        println("E5 PASS: season failure/empty/cache and old-row clearing, retryable library append, filtered search continuation capped at 3 pages, registered acyclic account focus, source probe tri-state and cancellation")
    }

    private fun seasonEpisodeLoads() {
        val oldSeason = listOf("old-episode")
        val switching = beginSeasonEpisodeLoad<String>()
        check(switching.loading && switching.episodes.isEmpty() && oldSeason.isNotEmpty())

        var cacheWrites = 0
        val failure = completeSeasonEpisodeLoad<String>(Result.failure(IllegalStateException("offline"))) { cacheWrites++ }
        check(failure.error != null && failure.episodes.isEmpty() && cacheWrites == 0)

        val emptySeason = completeSeasonEpisodeLoad(Result.success(emptyList<String>())) {
            cacheWrites++
            check(it.isEmpty())
        }
        check(emptySeason.error == null && emptySeason.episodes.isEmpty() && cacheWrites == 1)

        val cached = cachedSeasonEpisodeState(listOf("cached"))
        check(cached?.episodes == listOf("cached") && cacheWrites == 1)
        check(cachedSeasonEpisodeState(emptyList<String>())?.episodes?.isEmpty() == true)
    }

    private fun libraryAppend() {
        val failed = libraryAppendProgress(50, hasMore = true, pageSize = null)
        check(failed.failed && failed.hasMore && failed.nextIndex == 50)
        check(!shouldRetryLibraryAppend(failed.failed, requested = 0, handled = 0))
        check(shouldRetryLibraryAppend(failed.failed, requested = 1, handled = 0))
        check(!shouldRetryLibraryAppend(failed.failed, requested = 1, handled = 1))
        val retry = libraryAppendProgress(failed.nextIndex, failed.hasMore, pageSize = 50)
        check(!retry.failed && retry.nextIndex == 100 && retry.hasMore)
        val finalPage = libraryAppendProgress(100, hasMore = true, pageSize = 12)
        check(!finalPage.failed && finalPage.nextIndex == 112 && !finalPage.hasMore)
        val emptyPage = libraryAppendProgress(100, hasMore = true, pageSize = 0)
        check(!emptyPage.hasMore && emptyPage.nextIndex == 100)
    }

    private fun searchPaging() {
        check(shouldAppendSearchPage(true, 40, true, false, false, false))
        check(!shouldAppendSearchPage(false, 40, true, false, false, false))
        check(shouldAutoLoadEmptySearchPage(0, true, false, false, 0))
        check(shouldAutoLoadEmptySearchPage(0, true, false, false, SEARCH_EMPTY_AUTO_PAGE_LIMIT - 1))
        check(!shouldAutoLoadEmptySearchPage(0, true, false, false, SEARCH_EMPTY_AUTO_PAGE_LIMIT))
        check(!shouldAutoLoadEmptySearchPage(0, true, false, true, 0))
        check(!shouldAutoLoadEmptySearchPage(1, true, false, false, 0))
    }

    private suspend fun latestItemsRequests() = coroutineScope {
        val loader = LatestItemsLoader()
        check(loader.state.loading && loader.state.error == null)
        loader.load { error("offline") }
        check(!loader.state.loading && loader.state.error != null && loader.state.items.isEmpty())
        loader.load { emptyList() }
        check(!loader.state.loading && loader.state.error == null && loader.state.items.isEmpty())

        val fresh = MediaItem("fresh", "Fresh item", "Movie")
        for (oldFails in listOf(false, true)) {
            val delayed = CompletableDeferred<List<MediaItem>>()
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load {
                    val rows = delayed.await()
                    if (oldFails) error("late failure")
                    rows
                }
            }
            check(loader.state.loading && loader.state.error == null)
            loader.load { listOf(fresh) }
            delayed.complete(listOf(fresh.copy(id = "old")))
            old.join()
            check(loader.state.items == listOf(fresh) && loader.state.error == null && !loader.state.loading)
        }

        val disposedResponse = CompletableDeferred<List<MediaItem>>()
        val disposed = launch(start = CoroutineStart.UNDISPATCHED) { loader.load { disposedResponse.await() } }
        val beforeDispose = loader.state
        loader.cancel()
        disposedResponse.complete(listOf(fresh.copy(id = "disposed")))
        disposed.join()
        check(loader.state == beforeDispose)

        var cancelled = false
        try {
            loader.load { throw CancellationException("leave search") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        check(cancelled)
        println("E5 BATCH-6 PASS: latest failure versus empty, retry, stale success/failure and disposal guards")
    }

    private fun accountFocus() {
        for (urls in listOf(emptyList(), listOf("one"), listOf("one", "two"))) {
            val keys = accountRequesterKeys(urls).toSet()
            val graph = accountFocusTargets(urls)
            check(graph.keys == keys)
            check(graph.values.all { targets ->
                (targets.up == null || targets.up in keys) && (targets.down == null || targets.down in keys)
            })
            graph.forEach { (key, targets) ->
                check(targets.up == null || targets.up in keys)
                check(targets.down == null || targets.down in keys)
                check(targets.up != key && targets.down != key)
            }
        }
        check(accountFocusTargets(emptyList()).getValue("account:save-current").up == "account:current-edit")
        check(accountFocusTargets(listOf("one")).getValue("account:switch:one").up == null)
    }

    private suspend fun seriesSourceProbe() {
        check(episodeSourceAvailability(Result.success(emptyList<String>())) == EpisodeSourceAvailability.NO_SOURCE)
        check(episodeSourceAvailability(Result.success(listOf("source"))) == EpisodeSourceAvailability.HAS_SOURCE)
        val failed = probeMediaSources<List<String>> { error("server error") }
        check(episodeSourceAvailability(failed) == EpisodeSourceAvailability.PROBE_FAILED)

        val episode = MediaItem("episode", "Episode", "Episode", indexNumber = 1)
        var probes = 0
        val unknown = probeEpisodeSource(episode) {
            probes++
            EpisodeSourceAvailability.PROBE_FAILED
        }
        check(unknown == EpisodeSourceAvailability.PROBE_FAILED)
        val known = probeEpisodeSource(episode.copy(sourceCount = 1)) {
            probes++
            EpisodeSourceAvailability.NO_SOURCE
        }
        check(known == EpisodeSourceAvailability.HAS_SOURCE && probes == 1)

        val series = MediaItem("series", "Series", "Series")
        val next = episode.copy(id = "next", indexNumber = 2)
        val resolved = resolvePlayableItem(series, { listOf(episode, next).map { it.copy(sourceCount = 0) } }) {
            if (it.id == episode.id) EpisodeSourceAvailability.PROBE_FAILED else EpisodeSourceAvailability.HAS_SOURCE
        }
        check(resolved is PlayableItemResult.Ready && resolved.item.id == episode.id)
        val skipsConfirmedEmpty = resolvePlayableItem(series, { listOf(episode, next).map { it.copy(sourceCount = 0) } }) {
            if (it.id == episode.id) EpisodeSourceAvailability.NO_SOURCE else EpisodeSourceAvailability.HAS_SOURCE
        }
        check(skipsConfirmedEmpty is PlayableItemResult.Ready && skipsConfirmedEmpty.item.id == next.id)

        var cancellationPropagated = false
        try {
            probeMediaSources<List<String>> { throw CancellationException("cancel") }
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        check(cancellationPropagated)
    }
}
