package com.lumina.tv.data

enum class ExternalMediaType(val embyType: String) {
    MOVIE("Movie"),
    SERIES("Series"),
}

/** A catalog item from a public source which may not exist in the user's Emby library. */
data class ExternalMediaItem(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val posterUrl: String?,
    val type: ExternalMediaType,
) {
    val cacheKey: String get() = "${type.name}:$tmdbId"
}
