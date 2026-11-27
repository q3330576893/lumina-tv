package com.lumina.tv.data

/** A single image candidate for wide surfaces such as the home hero and player buffer screen. */
data class WideImageCandidate(val url: String, val source: String)

/**
 * Builds the one canonical fallback chain for wide images. URL order is deliberate: local
 * Emby images are tried before the optional remote TMDB image, with the poster as the final
 * visual fallback when no wide image exists.
 */
fun EmbyClient.wideImageCandidates(
    item: MediaItem,
    maxWidth: Int,
    quality: Int = 80,
    tmdbUrl: String? = null,
): List<WideImageCandidate> {
    val candidates = buildList {
        val ownTags = item.backdropTags.toMutableMap().apply {
            // Preserve old in-memory/disk items that only carried the legacy field.
            if (item.backdropTag != null && 0 !in this) this[0] = item.backdropTag
        }
        for ((index, tag) in ownTags.toSortedMap()) {
            if (index >= 0 && tag.isNotBlank()) {
                backdropUrl(item, index, maxWidth, quality)?.let { add(WideImageCandidate(it, "emby")) }
            }
        }

        // Backdrop/0 is valid even when the server omitted image tags from its metadata response.
        add(WideImageCandidate(backdropUrlUntagged(item.id, 0, maxWidth, quality), "emby"))

        item.parentBackdropItemId?.takeIf(String::isNotBlank)?.let { parentId ->
            for ((index, tag) in item.parentBackdropTags.toSortedMap()) {
                if (index >= 0 && tag.isNotBlank()) {
                    backdropUrl(parentId, index, tag, maxWidth, quality)?.let { add(WideImageCandidate(it, "parent")) }
                }
            }
            add(WideImageCandidate(backdropUrlUntagged(parentId, 0, maxWidth, quality), "parent"))
        }

        tmdbUrl?.takeIf(String::isNotBlank)?.let { add(WideImageCandidate(it, "tmdb")) }
        imageUrl(item, maxWidth)?.let { add(WideImageCandidate(it, "poster")) }
    }
    return candidates.distinctBy { it.url }
}
