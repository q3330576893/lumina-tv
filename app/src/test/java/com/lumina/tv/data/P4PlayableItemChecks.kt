package com.lumina.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

object P4PlayableItemChecks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val series = MediaItem("series", "Series", "Series")
        fun episode(id: String, season: Int, index: Int, played: Boolean = false, position: Long = 0) =
            MediaItem(id, id, "Episode", parentIndexNumber = season, indexNumber = index,
                played = played, positionTicks = position, sourceCount = 1)
        val first = episode("s1e1", 1, 1)
        val second = episode("s1e2", 1, 2)
        val nextSeason = episode("s2e1", 2, 1)
        suspend fun resolve(episodes: List<MediaItem>, unavailable: Set<String> = emptySet()) =
            resolvePlayableItem(series, { episodes }, { it.id !in unavailable })
        val movie = MediaItem("movie", "Movie", "Movie")
        check(resolvePlayableItem(movie, { error("Movie must not query episodes") }, { error("No lookup") }) == PlayableItemResult.Ready(movie))
        check(resolvePlayableItem(first, { error("Episode must not query episodes") }, { error("No lookup") }) == PlayableItemResult.Ready(first))
        check(resolve(listOf(nextSeason, second, first)) == PlayableItemResult.Ready(first))
        val resume = nextSeason.copy(positionTicks = 50)
        check(resolve(listOf(second, resume, first)) == PlayableItemResult.Ready(resume))
        check(resolve(listOf(resume.copy(played = true), first)) == PlayableItemResult.Ready(first))
        check(resolve(listOf(first, second), setOf(first.id)) == PlayableItemResult.Ready(second))
        check(resolve(listOf(first.copy(isMissing = true), second)) == PlayableItemResult.Ready(second))
        check(resolve(emptyList()) == PlayableItemResult.Unavailable)
        check(resolve(listOf(first), setOf(first.id)) == PlayableItemResult.Unavailable)
        val completed = resolve(listOf(nextSeason.copy(played = true), first.copy(played = true)))
        check(completed is PlayableItemResult.ReplayAvailable && completed.firstEpisode.id == first.id && completed.allWatched)
        val gate = PlayableItemRequestGate()
        gate.updateContext("account1:heroA")
        val old = checkNotNull(gate.begin())
        check(gate.begin() == null) // Double click while a lookup is pending.
        val delayed = CompletableDeferred<List<MediaItem>>()
        var navigations = 0
        val oldLookup = async {
            resolvePlayableItem(series, { delayed.await() }, { true })
            if (gate.accepts(old)) navigations++
        }
        yield()
        gate.updateContext("account1:heroB")
        gate.updateContext("account1:heroA") // A -> B -> A cannot resurrect an old lookup.
        val newer = checkNotNull(gate.begin())
        delayed.complete(listOf(first))
        oldLookup.await()
        check(navigations == 0 && gate.accepts(newer))
        gate.finish(old)
        check(gate.accepts(newer))
        gate.updateContext("account2:heroA")
        check(!gate.accepts(newer))
        gate.updateContext(null)
        check(gate.begin() == null)
        println("P4 D05 PASS: fresh cross-season order, resume, replay prompt, unavailable, duplicate/stale/account guard")
    }
}
