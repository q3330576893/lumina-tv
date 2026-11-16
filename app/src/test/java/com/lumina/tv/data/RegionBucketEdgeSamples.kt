package com.lumina.tv.data

/** Boundary assertions for production-location priority and language-only region inference. */
object RegionBucketEdgeSamples {
    @JvmStatic
    fun main(args: Array<String>) {
        check(
            bucketOf(
                MediaItem("hk", "Hong Kong", productionLocations = listOf("China", "Hong Kong")),
            ) == RegionBucket.HK to Confidence.HIGH,
        )
        check(
            bucketOf(MediaItem("tw", "Taiwan", productionLocations = listOf("Taiwan"))) ==
                RegionBucket.TW to Confidence.HIGH,
        )
        check(
            bucketOf(MediaItem("is", "Iceland", productionLocations = listOf("Iceland"))) ==
                RegionBucket.OTHER to Confidence.HIGH,
        )

        val english = MediaItem("en", "English", originalLanguage = "en")
        check(bucketOf(english) == RegionBucket.WEST to Confidence.LOW)

        val chinese = MediaItem("zh", "Chinese", originalLanguage = "zh")
        check(bucketOf(chinese) == RegionBucket.UNKNOWN to Confidence.UNKNOWN)
        check(regionLabel(chinese) == "中文地区未确定")
        println("Region bucket edge samples passed.")
    }
}
