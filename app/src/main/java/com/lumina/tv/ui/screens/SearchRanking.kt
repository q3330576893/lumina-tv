package com.lumina.tv.ui.screens

import com.lumina.tv.data.MediaItem

internal fun rankSearchItems(
    items: List<MediaItem>,
    query: String,
    pinyinRank: (name: String, needle: String) -> Int,
): List<MediaItem> {
    val needle = query.filterNot(Char::isWhitespace)
    val ranked = items.mapNotNull { item ->
        val name = item.name.trim()
        val rank = when {
            name.equals(query, true) -> 0
            name.startsWith(query, true) -> 1
            name.contains(query, true) -> 2
            else -> pinyinRank(name, needle).coerceIn(0, 3)
        }
        item.takeIf { rank < 3 }?.let { it to rank }
    }
    return ranked.sortedWith(
        compareBy<Pair<MediaItem, Int>> { it.second }
            .thenByDescending { it.first.rating ?: it.first.communityRating ?: 0.0 }
            .thenByDescending { it.first.year ?: 0 },
    ).map { it.first }
}
