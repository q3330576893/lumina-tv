package com.lumina.tv

import com.lumina.tv.data.LanguageNormalizationSamples
import com.lumina.tv.data.RecommendationScoreSamples
import com.lumina.tv.data.RegionBucketEdgeSamples
import com.lumina.tv.data.S1SecurityChecks
import com.lumina.tv.data.S5WideImageChecks
import com.lumina.tv.data.runDvProfileParsingAssertions
import com.lumina.tv.data.main as regionBucketSamples
import com.lumina.tv.player.S2LanguageChecks
import com.lumina.tv.player.A1ReliabilityChecks
import com.lumina.tv.player.main as playbackErrorSamples

/** Single entry point for the repository's check()-based sample assertions. */
object SamplesMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val suites = listOf<Pair<String, () -> Unit>>(
            "DV profile parsing" to { runDvProfileParsingAssertions() },
            "Playback error classification" to { playbackErrorSamples() },
            "Region buckets" to { regionBucketSamples() },
            "Language normalization" to { LanguageNormalizationSamples.main(emptyArray()) },
            "Region bucket edges" to { RegionBucketEdgeSamples.main(emptyArray()) },
            "Recommendation scores" to { RecommendationScoreSamples.main(emptyArray()) },
            "S1 security" to { S1SecurityChecks.main(emptyArray()) },
            "S2 language" to { S2LanguageChecks.main(emptyArray()) },
            "S5 wide images" to { S5WideImageChecks.main(emptyArray()) },
            "V6 M00 diagnostics" to { M00Checks.main(emptyArray()) },
            "V6 M01 login and notice lifecycle" to { M01Checks.main(emptyArray()) },
            "V6 M04B poster title font scaling" to { M04BChecks.main(emptyArray()) },
            "A1 reliability" to { A1ReliabilityChecks.main(emptyArray()) },
        )
        check(suites.size == 13) { "Expected all sample suites plus A1, M00, M01, and M04B; refusing an incomplete gate." }
        suites.forEachIndexed { index, (name, run) ->
            println("[${index + 1}/${suites.size}] START $name")
            run()
            println("[${index + 1}/${suites.size}] PASS $name")
        }
        println("All sample assertions passed (${suites.size} suites).")
    }
}
