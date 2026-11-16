package com.lumina.tv

import com.lumina.tv.data.LanguageNormalizationSamples
import com.lumina.tv.data.RecommendationScoreSamples
import com.lumina.tv.data.RegionBucketEdgeSamples
import com.lumina.tv.data.S1SecurityChecks
import com.lumina.tv.data.S5WideImageChecks
import com.lumina.tv.data.runDvProfileParsingAssertions
import com.lumina.tv.data.main as regionBucketSamples
import com.lumina.tv.player.S2LanguageChecks
import com.lumina.tv.player.main as playbackErrorSamples

/** Single entry point for the repository's check()-based sample assertions. */
object SamplesMain {
    @JvmStatic
    fun main(args: Array<String>) {
        runDvProfileParsingAssertions()
        playbackErrorSamples()
        regionBucketSamples()
        LanguageNormalizationSamples.main(emptyArray())
        RegionBucketEdgeSamples.main(emptyArray())
        RecommendationScoreSamples.main(emptyArray())
        S1SecurityChecks.main(emptyArray())
        S2LanguageChecks.main(emptyArray())
        S5WideImageChecks.main(emptyArray())
        println("All sample assertions passed (9 suites).")
    }
}
