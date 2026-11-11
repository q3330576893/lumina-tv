package com.lumina.tv.data

enum class DiscoveryLayout {
    POSTER_RANKED,
    LANDSCAPE_HOT,
}

data class DiscoverySectionConfig(
    val id: String,
    val title: String,
    val sourceId: String,
    val endpoint: String,
    val sortBy: String,
    val pageSize: Int = 20,
    val mediaType: String = "movie",
    val layout: DiscoveryLayout,
)

data class ExternalCard(
    val tmdbId: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
)

object DiscoverySections {
    val home = listOf(
        DiscoverySectionConfig(
            id = "tmdb-top-rated-movies",
            title = "高分电影",
            sourceId = "tmdb",
            endpoint = "/movie/top_rated",
            sortBy = "vote_average.desc",
            layout = DiscoveryLayout.POSTER_RANKED,
        ),
        DiscoverySectionConfig(
            id = "tmdb-now-playing-movies",
            title = "正在热播",
            sourceId = "tmdb",
            endpoint = "/movie/now_playing",
            sortBy = "popularity.desc",
            layout = DiscoveryLayout.LANDSCAPE_HOT,
        ),
    )
}
