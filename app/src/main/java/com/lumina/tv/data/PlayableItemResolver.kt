package com.lumina.tv.data

import kotlinx.coroutines.CancellationException

enum class EpisodeSourceAvailability {
    HAS_SOURCE,
    NO_SOURCE,
    PROBE_FAILED,
}

internal suspend fun <T> probeMediaSources(load: suspend () -> List<T>): Result<List<T>> = try {
    Result.success(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal fun <T> episodeSourceAvailability(result: Result<List<T>>): EpisodeSourceAvailability =
    result.fold(
        onSuccess = { if (it.isEmpty()) EpisodeSourceAvailability.NO_SOURCE else EpisodeSourceAvailability.HAS_SOURCE },
        onFailure = { EpisodeSourceAvailability.PROBE_FAILED },
    )

internal fun <T> recoveredSourceInventory(initial: List<T>, retried: Result<List<T>>): Result<List<T>> =
    retried.fold(
        onSuccess = { Result.success(it.ifEmpty { initial }) },
        onFailure = { failure -> if (initial.isEmpty()) Result.failure(failure) else Result.success(initial) },
    )

internal suspend fun probeEpisodeSource(
    episode: MediaItem,
    probe: suspend (MediaItem) -> EpisodeSourceAvailability,
): EpisodeSourceAvailability = if (episode.sourceCount > 0) EpisodeSourceAvailability.HAS_SOURCE else probe(episode)

internal suspend fun selectNextPlayableEpisode(
    candidates: List<MediaItem>,
    probeSource: suspend (MediaItem) -> EpisodeSourceAvailability,
): MediaItem? {
    for (episode in candidates.asSequence()
        .filter { it.type == "Episode" && !it.isMissing }
        .distinctBy { it.id }) {
        if (probeEpisodeSource(episode, probeSource) != EpisodeSourceAvailability.NO_SOURCE) return episode
    }
    return null
}

sealed interface PlayableItemResult {
    data class Ready(val item: MediaItem) : PlayableItemResult
    data class ReplayAvailable(val firstEpisode: MediaItem, val allWatched: Boolean) : PlayableItemResult
    data object Unavailable : PlayableItemResult
    data object RetryableFailure : PlayableItemResult
}

/** Incomplete disk rows are display data until the existing item endpoint revalidates them. */
internal suspend fun resolveCachedPlayableItem(
    item: MediaItem,
    loadItem: suspend (String) -> MediaItem?,
    isCurrent: () -> Boolean,
    resolve: suspend (MediaItem) -> PlayableItemResult,
): PlayableItemResult {
    if (item.isMissing) return PlayableItemResult.Unavailable
    if (!isCurrent()) return PlayableItemResult.RetryableFailure
    val verified = if (CacheMediaItemCodec.requiresRefresh(item)) {
        val fresh = try { loadItem(item.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!isCurrent() || fresh == null || fresh.id != item.id) return PlayableItemResult.RetryableFailure
        if (fresh.isMissing) return PlayableItemResult.Unavailable
        if (CacheMediaItemCodec.requiresRefresh(fresh) || fresh.type.isBlank()) return PlayableItemResult.RetryableFailure
        fresh
    } else item
    val result = resolve(verified)
    return if (isCurrent()) result else PlayableItemResult.RetryableFailure
}

/** Fetch progress at click time; never use a detail page's selected-season snapshot. */
suspend fun resolvePlayableItem(
    item: MediaItem,
    loadEpisodes: suspend (String) -> List<MediaItem>,
    probeSource: suspend (MediaItem) -> EpisodeSourceAvailability,
): PlayableItemResult {
    if (item.isMissing) return PlayableItemResult.Unavailable
    if (CacheMediaItemCodec.requiresRefresh(item)) return PlayableItemResult.RetryableFailure
    if (item.type == "Movie" || item.type == "Episode") return PlayableItemResult.Ready(item)
    if (item.type != "Series") return PlayableItemResult.Unavailable
    val episodes = loadEpisodes(item.id).filter { it.type == "Episode" && !it.isMissing }
        .distinctBy { it.id }.sortedWith(compareBy<MediaItem> { it.parentIndexNumber ?: Int.MAX_VALUE }
            .thenBy { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
    val checked = mutableMapOf<String, EpisodeSourceAvailability>()
    suspend fun playable(episode: MediaItem): Boolean = (checked[episode.id]
        ?: probeEpisodeSource(episode, probeSource).also { checked[episode.id] = it }) != EpisodeSourceAvailability.NO_SOURCE
    for (episode in episodes.filter { !it.played && it.positionTicks > 0L }) {
        if (playable(episode)) return PlayableItemResult.Ready(episode)
    }
    for (episode in episodes.filter { !it.played }) {
        if (playable(episode)) return PlayableItemResult.Ready(episode)
    }
    for (episode in episodes) {
        if (playable(episode)) return PlayableItemResult.ReplayAvailable(episode, episodes.all { it.played })
    }
    return PlayableItemResult.Unavailable
}

/** A click belongs to one visible item/account context. Returning to the same ID is a new context. */
class PlayableItemRequestGate {
    private var context: Any? = null
    private var generation = 0L
    private var active: Long? = null

    fun updateContext(value: Any?) {
        if (context != value) {
            context = value
            generation++
            active = null
        }
    }

    fun begin(): Long? {
        if (context == null || active != null) return null
        return (++generation).also { active = it }
    }

    fun accepts(ticket: Long): Boolean = active == ticket
    fun finish(ticket: Long) { if (accepts(ticket)) active = null }
    fun cancel() { generation++; active = null }
}
