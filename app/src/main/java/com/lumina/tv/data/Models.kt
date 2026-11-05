package com.lumina.tv.data

data class MediaItem(
    val id: String,
    val name: String,
    val type: String = "",
    val imageTag: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val runtimeTicks: Long? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val seriesName: String? = null,
    val positionTicks: Long = 0L,
    val played: Boolean = false,
    val playedPercentage: Double = 0.0,
    val primaryImageAspectRatio: Double? = null,
    val backdropTag: String? = null,
    val communityRating: Double? = null,
    val genres: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val resolution: String? = null,
    val isHdr: Boolean = false,
    val isDolbyVision: Boolean = false,
    val audioFormat: String? = null,
    val people: List<MediaPerson> = emptyList(),
) {
    /** 规格胶囊徽标：4K / 1080P / HDR / DV / 5.1CH 等 */
    val tags: List<String>
        get() = buildList {
            resolution?.let { add(it) }
            if (isDolbyVision) add("DOLBY VISION")
            else if (isHdr) add("HDR10")
            audioFormat?.let { add(it) }
        }
    val subtitle: String
        get() = buildString {
            val y = year
            if (y != null) append(y)
            val r = rating ?: communityRating
            if (r != null && r > 0) {
                if (isNotEmpty()) append("  ·  ")
                append("%.1f".format(r))
            }
            if (indexNumber != null) {
                if (isNotEmpty()) append("  ·  ")
                if (parentIndexNumber != null) append("S%02dE%02d".format(parentIndexNumber, indexNumber))
                else append("第 %d 集".format(indexNumber))
            }
        }
}

data class MediaPerson(
    val id: String?,
    val name: String,
    val role: String? = null,
    val type: String? = null,
    val imageTag: String? = null,
)

data class MediaStream(
    val index: Int,
    val type: String,
    val codec: String?,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
    val displayTitle: String?,
) {
    val label: String
        get() = displayTitle ?: listOfNotNull(language?.uppercase(), title, codec?.uppercase()).joinToString(" ")
}

data class MediaSource(
    val id: String,
    val name: String,
    val container: String?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val streams: List<MediaStream>,
    val size: Long = 0L,
    val resolution: String? = null,
) {
    val audioStreams: List<MediaStream> get() = streams.filter { it.type == "Audio" }
    val subtitleStreams: List<MediaStream> get() = streams.filter { it.type == "Subtitle" }

    /** 选源列表展示标签：名称 · 分辨率 · 大小 */
    val displayLabel: String
        get() = listOfNotNull(
            name.ifBlank { null } ?: container?.uppercase(),
            resolution,
            formatSize(size),
        ).joinToString("  ·  ").ifBlank { "默认源" }

    private fun formatSize(bytes: Long): String? {
        if (bytes <= 0) return null
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) "%.1fGB".format(gb) else "${(bytes / 1_048_576).toInt()}MB"
    }
}

data class Session(
    val baseUrl: String,
    val token: String,
    val userId: String,
    val userName: String,
    val serverName: String = "Emby",
)

data class LibraryView(
    val id: String,
    val name: String,
    val type: String,
    val imageTag: String? = null,
)
