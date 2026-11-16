package com.lumina.tv.data

enum class RegionBucket { CN_MAINLAND, HK, TW, KR, JP, WEST, OTHER, UNKNOWN }
enum class Confidence { HIGH, LOW, UNKNOWN }

/** Recommendation bonus for a candidate that shares a reliable region with the current item. */
fun sameRegionBonus(
    current: Pair<RegionBucket, Confidence>,
    candidate: Pair<RegionBucket, Confidence>,
): Double {
    val (currentBucket, currentConfidence) = current
    val (candidateBucket, candidateConfidence) = candidate
    if (currentBucket != candidateBucket) return 0.0
    if (currentBucket == RegionBucket.OTHER || currentBucket == RegionBucket.UNKNOWN) return 0.0
    if (currentConfidence != Confidence.HIGH || candidateConfidence != Confidence.HIGH) return 0.0
    return 3.0
}

fun bucketOf(item: MediaItem): Pair<RegionBucket, Confidence> {
    val locations = item.productionLocations.map { it.trim().lowercase() }.filter(String::isNotBlank)
    fun matches(location: String, phrase: String): Boolean = when (phrase) {
        "usa", "uk" -> Regex("(^|[^a-z])${Regex.escape(phrase)}([^a-z]|$)").containsMatchIn(location)
        else -> phrase in location
    }
    if (locations.isNotEmpty()) {
        val bucket = when {
            locations.any { matches(it, "hong kong") || "香港" in it } -> RegionBucket.HK
            locations.any { matches(it, "taiwan") || "台湾" in it } -> RegionBucket.TW
            locations.any { matches(it, "china") || "中国" in it } -> RegionBucket.CN_MAINLAND
            locations.any { matches(it, "south korea") || matches(it, "korea") || "韩国" in it } -> RegionBucket.KR
            locations.any { matches(it, "japan") || "日本" in it } -> RegionBucket.JP
            locations.any {
                listOf("united states", "usa", "united kingdom", "uk", "france", "germany", "canada", "australia")
                    .any { country -> matches(it, country) }
            } -> RegionBucket.WEST
            else -> RegionBucket.OTHER
        }
        return bucket to Confidence.HIGH
    }
    return when (item.originalLanguage?.trim()?.lowercase()) {
        "ko" -> RegionBucket.KR to Confidence.HIGH
        "ja" -> RegionBucket.JP to Confidence.HIGH
        "en" -> RegionBucket.WEST to Confidence.LOW
        "zh", "zh-cn", "zh-tw", "zh-hk" -> RegionBucket.UNKNOWN to Confidence.UNKNOWN
        else -> RegionBucket.UNKNOWN to Confidence.UNKNOWN
    }
}

fun regionLabel(item: MediaItem): String = when (bucketOf(item).first) {
    RegionBucket.CN_MAINLAND -> "中国大陆"
    RegionBucket.HK -> "香港"
    RegionBucket.TW -> "台湾"
    RegionBucket.KR -> "韩国"
    RegionBucket.JP -> "日本"
    RegionBucket.WEST -> "欧美"
    RegionBucket.OTHER -> "其他地区"
    RegionBucket.UNKNOWN -> if (item.originalLanguage?.startsWith("zh", ignoreCase = true) == true) "中文地区未确定" else "地区未确定"
}
