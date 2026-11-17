package com.lumina.tv.data

sealed interface PlayableItemResult {
    data class Ready(val item: MediaItem) : PlayableItemResult
    data class ReplayAvailable(val firstEpisode: MediaItem, val allWatched: Boolean) : PlayableItemResult
    data object Unavailable : PlayableItemResult
}

/** Fetch progress at click time; never use a detail page's selected-season snapshot. */
suspend fun resolvePlayableItem(
    item: MediaItem,
    loadEpisodes: suspend (String) -> List<MediaItem>,
    isPlayable: suspend (MediaItem) -> Boolean,
): PlayableItemResult {
    if (item.type == "Movie" || item.type == "Episode") return PlayableItemResult.Ready(item)
    if (item.type != "Series") return PlayableItemResult.Unavailable
    val episodes = loadEpisodes(item.id).filter { it.type == "Episode" && !it.isMissing }
        .distinctBy { it.id }.sortedWith(compareBy<MediaItem> { it.parentIndexNumber ?: Int.MAX_VALUE }
            .thenBy { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
    val checked = mutableMapOf<String, Boolean>()
    suspend fun playable(episode: MediaItem): Boolean = checked[episode.id]
        ?: isPlayable(episode).also { checked[episode.id] = it }
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
}
