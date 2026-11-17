package com.lumina.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lumina.tv.data.MediaItem

/** Keep allocation, selection and its diagnostic log inside the input-keyed calculation. */
@Composable
internal fun rememberHomeHeroItems(
    latest: List<MediaItem>,
    movies: List<MediaItem>,
    series: List<MediaItem>,
    favorites: List<MediaItem>,
    unavailableHeroIds: Set<String>,
    pick: (List<MediaItem>, Set<String>, Int) -> List<MediaItem>,
): List<MediaItem> {
    val heroPool = remember(latest, movies, series, favorites) {
        (latest + movies + series + favorites).distinctBy { it.id }
    }
    val latestHeroIds = remember(latest) { latest.map { it.id }.toHashSet() }
    return remember(heroPool, latestHeroIds, unavailableHeroIds) {
        pick(heroPool.filterNot { it.id in unavailableHeroIds }, latestHeroIds, 6)
    }
}
