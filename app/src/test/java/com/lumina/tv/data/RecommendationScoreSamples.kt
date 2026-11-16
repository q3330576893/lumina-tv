package com.lumina.tv.data

/** Pure assertions for the region-only part of detail recommendation scoring. */
object RecommendationScoreSamples {
    @JvmStatic
    fun main(args: Array<String>) {
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.HIGH, RegionBucket.HK to Confidence.HIGH) == 3.0,
        )
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.HIGH, RegionBucket.HK to Confidence.LOW) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.LOW, RegionBucket.HK to Confidence.HIGH) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.WEST to Confidence.LOW, RegionBucket.WEST to Confidence.LOW) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.HIGH, RegionBucket.TW to Confidence.HIGH) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.OTHER to Confidence.HIGH, RegionBucket.OTHER to Confidence.HIGH) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.UNKNOWN to Confidence.UNKNOWN, RegionBucket.UNKNOWN to Confidence.UNKNOWN) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.UNKNOWN to Confidence.HIGH, RegionBucket.UNKNOWN to Confidence.HIGH) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.UNKNOWN, RegionBucket.HK to Confidence.HIGH) == 0.0,
        )
        check(
            sameRegionBonus(RegionBucket.HK to Confidence.HIGH, RegionBucket.HK to Confidence.UNKNOWN) == 0.0,
        )
        println("Recommendation score samples passed.")
    }
}
