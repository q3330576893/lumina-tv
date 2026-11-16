package com.lumina.tv.data

fun main() {
    check(bucketOf(MediaItem("1", "K", originalLanguage = "ko")) == RegionBucket.KR to Confidence.HIGH)
    check(bucketOf(MediaItem("2", "J", originalLanguage = "ja")) == RegionBucket.JP to Confidence.HIGH)
    check(bucketOf(MediaItem("3", "E", originalLanguage = "en")) == RegionBucket.WEST to Confidence.LOW)
    check(bucketOf(MediaItem("4", "Chinese", originalLanguage = "zh")) == RegionBucket.UNKNOWN to Confidence.UNKNOWN)
    check(regionLabel(MediaItem("4", "Chinese", originalLanguage = "zh")) == "中文地区未确定")
    check(bucketOf(MediaItem("5", "Mainland", productionLocations = listOf("China"))) == RegionBucket.CN_MAINLAND to Confidence.HIGH)
    check(bucketOf(MediaItem("6", "Hong Kong", productionLocations = listOf("China", "Hong Kong"))) == RegionBucket.HK to Confidence.HIGH)
    check(bucketOf(MediaItem("7", "Taiwan", productionLocations = listOf("Taiwan"))) == RegionBucket.TW to Confidence.HIGH)
    check(bucketOf(MediaItem("8", "Other", productionLocations = listOf("Iceland"))) == RegionBucket.OTHER to Confidence.HIGH)
    check(bucketOf(MediaItem("10", "Ukraine", productionLocations = listOf("Ukraine"))) == RegionBucket.OTHER to Confidence.HIGH)
    check(bucketOf(MediaItem("9", "Unknown")) == RegionBucket.UNKNOWN to Confidence.UNKNOWN)
}
