package com.lumina.tv.ui.screens

import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.theme.TvTokenValues
import java.util.Locale

internal object HomeRailTokens {
    const val ASPECT = 16f / 9f
    const val GAP_DP = 12f
    const val CORNER_DP = 12f
    const val VISIBLE_CARDS = 6.5f
    const val IMAGE_WIDTH = 480
    const val PROGRESS_DP = 4f
}

internal data class HomeRailGeometry(val width: Float, val height: Float, val gap: Float, val inset: Float)

internal fun homeRailGeometry(viewportWidth: Float): HomeRailGeometry {
    val inset = TvTokenValues.safeInset(viewportWidth)
    // Six complete gaps, six complete cards and half of the seventh card.
    val width = ((viewportWidth - 2 * inset - 6 * HomeRailTokens.GAP_DP) / HomeRailTokens.VISIBLE_CARDS).coerceAtLeast(1f)
    return HomeRailGeometry(width, width / HomeRailTokens.ASPECT, HomeRailTokens.GAP_DP, inset)
}

internal enum class HeroImageSource { BACKDROP, POSTER, STATIC }
internal data class HomeImage(val source: HeroImageSource, val url: String?)

/** Tagged Emby artwork only. The final static candidate also handles every network failure. */
internal fun homeImageCandidates(client: EmbyClient?, item: MediaItem, maxWidth: Int = 1280): List<HomeImage> = buildList {
    if (client != null) {
        val tags = item.backdropTags.ifEmpty { item.backdropTag?.let { mapOf(0 to it) }.orEmpty() }
        tags.toSortedMap().forEach { (index, tag) ->
            if (tag.isNotBlank()) client.backdropUrl(item.id, index, tag, maxWidth)?.let { add(HomeImage(HeroImageSource.BACKDROP, it)) }
        }
        if (tags.isEmpty() && !item.parentBackdropItemId.isNullOrBlank()) {
            item.parentBackdropTags.toSortedMap().forEach { (index, tag) ->
                if (tag.isNotBlank()) client.backdropUrl(item.parentBackdropItemId, index, tag, maxWidth)?.let {
                    add(HomeImage(HeroImageSource.BACKDROP, it))
                }
            }
        }
        if (!item.imageTag.isNullOrBlank()) client.imageUrl(item, maxHeight = maxWidth, maxWidth = maxWidth)?.let {
            add(HomeImage(HeroImageSource.POSTER, it))
        }
    }
    add(HomeImage(HeroImageSource.STATIC, null))
}

internal fun heroUsesTextTitle(logoUrl: String?, logoLoaded: Boolean, artworkFailed: Boolean = false): Boolean =
    logoUrl.isNullOrBlank() || !logoLoaded || artworkFailed

internal fun readableMediaTitle(item: MediaItem): String =
    item.name.takeIf(String::isNotBlank) ?: item.seriesName?.takeIf(String::isNotBlank) ?: "未命名影片"

internal fun homeHeroMetadata(item: MediaItem): String = listOfNotNull(
    item.genres.filter(String::isNotBlank).take(2).joinToString(" / ").takeIf(String::isNotBlank),
    item.year?.takeIf { it > 0 }?.let { start ->
        item.endYear?.takeIf { it > start }?.let { "$start-$it" } ?: start.toString()
    },
    (item.rating ?: item.communityRating)?.takeIf { it.isFinite() && it > 0 }?.let { "★%.1f".format(Locale.ROOT, it) },
).joinToString(" · ")

/** No guessed percentage when duration is unknown, even if UserData supplies a percentage. */
internal fun browseProgress(item: MediaItem): Float? {
    val duration = item.runtimeTicks?.takeIf { it > 0 } ?: return null
    if (item.positionTicks <= 0L) return null
    return (item.positionTicks.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
}

internal fun collectionPosterItems(children: List<MediaItem>): List<MediaItem> {
    val posters = children.filter { !it.imageTag.isNullOrBlank() }.distinctBy { it.id }.take(4)
    return if (posters.size < 3) posters.take(1) else posters
}

internal const val SEARCH_RESULT_BUDGET = 300
internal fun searchPageSize(loaded: Int): Int = (SEARCH_RESULT_BUDGET - loaded).coerceIn(0, 40)
internal fun searchBudgetNotice(loaded: Int, truncated: Boolean): String? =
    if (truncated) "已加载前 $loaded 条 / 可能还有更多" else null
internal fun mayFocusSearchResult(requested: Boolean, imeVisible: Boolean, inputFocused: Boolean): Boolean =
    requested && !imeVisible && !inputFocused

internal enum class DetailContentState { LOADING, EMPTY, NOT_FOUND, ERROR, READY }
internal fun detailContentState(loading: Boolean, notFound: Boolean, failed: Boolean, item: MediaItem?): DetailContentState = when {
    loading -> DetailContentState.LOADING
    notFound -> DetailContentState.NOT_FOUND
    failed -> DetailContentState.ERROR
    item == null || item.id.isBlank() -> DetailContentState.EMPTY
    else -> DetailContentState.READY
}
