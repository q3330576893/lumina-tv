package com.lumina.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lumina.tv.data.MediaItem

internal enum class HeroActionFocus { NONE, ARTWORK, PLAY, DETAIL }
internal enum class HeroDirectionKey { LEFT, RIGHT }

internal fun shouldHeroConsumeCarouselDirection(
    focusedAction: HeroActionFocus,
    direction: HeroDirectionKey,
    itemCount: Int,
): Boolean = itemCount > 1 && focusedAction != HeroActionFocus.NONE && when (focusedAction to direction) {
    HeroActionFocus.PLAY to HeroDirectionKey.RIGHT,
    HeroActionFocus.DETAIL to HeroDirectionKey.LEFT -> false
    else -> true
}

internal fun homeNeedsHeroReset(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    keyboard: Boolean,
    heroHasFocus: Boolean,
    resetPending: Boolean,
): Boolean = resetPending || firstVisibleIndex != 0 || firstVisibleOffset != 0 || (keyboard && !heroHasFocus)

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
