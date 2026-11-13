package com.lumina.tv.data

enum class DynamicRangePref { UNSET, DOLBY_VISION, HDR, SDR }
enum class NumericPref { UNSET, HIGH, LOW }

data class SourceStrategy(
    val dynamicRange: DynamicRangePref = DynamicRangePref.UNSET,
    val releaseGroups: List<String> = emptyList(),
    val bitrate: NumericPref = NumericPref.UNSET,
    val frameRate: NumericPref = NumericPref.UNSET,
) {
    val isDefault: Boolean
        get() = dynamicRange == DynamicRangePref.UNSET && releaseGroups.isEmpty() &&
            bitrate == NumericPref.UNSET && frameRate == NumericPref.UNSET
}

data class SourceOption(
    val id: String,
    val name: String,
    val index: Int,
    val container: String?,
    val videoCodec: String?,
    val videoHeight: Int?,
    val bitrate: Int?,
    val frameRate: Float?,
    val isDolbyVision: Boolean,
    val hdrType: String?,
)

fun MediaSource.toSourceOption(index: Int): SourceOption {
    val video = streams.firstOrNull { it.type == "Video" }
    val range = video?.videoRangeType
    return SourceOption(
        id = id, name = name, index = index, container = container, videoCodec = video?.codec,
        videoHeight = video?.height, bitrate = bitrate?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
        frameRate = video?.frameRate?.toFloat(),
        isDolbyVision = range.orEmpty().contains("DOVI", true) || range.orEmpty().contains("DOLBY", true) ||
            range.equals("DV", true) || video?.codec.orEmpty().contains("dovi", true),
        hdrType = range,
    )
}

fun chooseSource(options: List<SourceOption>, strategy: SourceStrategy, current: SourceOption? = null): SourceOption {
    if (options.isEmpty()) return current ?: throw IllegalArgumentException("No source options")
    return options.minWith(compareBy<SourceOption> { option ->
        when (strategy.dynamicRange) {
            DynamicRangePref.UNSET -> 0
            DynamicRangePref.DOLBY_VISION -> when { option.isDolbyVision -> 0; hasHdr(option) -> 1; else -> 2 }
            DynamicRangePref.HDR -> if (hasHdr(option)) 0 else 1
            DynamicRangePref.SDR -> if (!option.isDolbyVision && !hasHdr(option)) 0 else 1
        }
    }.thenBy { option ->
        strategy.releaseGroups.indexOfFirst { group -> group.isNotBlank() && option.name.contains(group, ignoreCase = true) }
            .let { if (it < 0) Int.MAX_VALUE else it }
    }.thenBy { option -> if (strategy.bitrate != NumericPref.UNSET && option.bitrate == null) 1 else 0 }
        .thenBy { option -> when (strategy.bitrate) { NumericPref.HIGH -> -(option.bitrate ?: 0); NumericPref.LOW -> option.bitrate ?: 0; NumericPref.UNSET -> 0 } }
        .thenBy { option -> if (strategy.frameRate != NumericPref.UNSET && option.frameRate == null) 1 else 0 }
        .thenBy { option -> when (strategy.frameRate) { NumericPref.HIGH -> -(option.frameRate ?: 0f); NumericPref.LOW -> option.frameRate ?: 0f; NumericPref.UNSET -> 0f } }
        .thenBy { it.index })
}

fun chooseSimilar(options: List<SourceOption>, current: SourceOption): SourceOption {
    options.firstOrNull { it.id == current.id }?.let { return it }
    if (options.isEmpty()) return current
    fun tokens(name: String) = name.lowercase().split(Regex("[^a-z0-9]+")).filter(String::isNotBlank).toSet()
    val currentTokens = tokens(current.name)
    val ranked = options.map { option ->
        val overlap = currentTokens.intersect(tokens(option.name)).size
        val sameContainer = option.container.equals(current.container, true)
        val sameCodec = option.videoCodec.equals(current.videoCodec, true)
        val heightDiff = kotlin.math.abs((option.videoHeight ?: 0) - (current.videoHeight ?: 0))
        Triple(option, overlap, Triple(sameContainer, sameCodec, heightDiff))
    }
    val intersecting = ranked.filter { it.second > 0 }
    if (intersecting.isNotEmpty()) return intersecting.sortedWith(
        compareByDescending<Triple<SourceOption, Int, Triple<Boolean, Boolean, Int>>> { it.second }
            .thenByDescending { it.third.first }.thenByDescending { it.third.second }
            .thenBy { it.third.third }.thenBy { kotlin.math.abs(it.first.index - current.index) }
            .thenBy { it.first.index }
    ).first().first

    val fallback = ranked.filter { entry ->
        val height = current.videoHeight ?: return@filter false
        val candidateHeight = entry.first.videoHeight ?: return@filter false
        entry.first.container.equals(current.container, true) && kotlin.math.abs(candidateHeight - height) <= height * 0.1f
    }
    return fallback.minWithOrNull(compareBy<Triple<SourceOption, Int, Triple<Boolean, Boolean, Int>>> {
        kotlin.math.abs((it.first.videoHeight ?: 0) - (current.videoHeight ?: 0))
    }.thenBy { kotlin.math.abs(it.first.index - current.index) }.thenBy { it.first.index })?.first
        ?: options.minWith(compareBy<SourceOption> { kotlin.math.abs(it.index - current.index) }.thenBy { it.index })
}

private fun hasHdr(option: SourceOption): Boolean = !option.hdrType.isNullOrBlank() &&
    !option.hdrType.equals("SDR", true) && !option.hdrType.equals("UNKNOWN", true)
