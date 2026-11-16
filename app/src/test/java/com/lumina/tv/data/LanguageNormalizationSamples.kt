package com.lumina.tv.data

import com.lumina.tv.player.normalizeLanguage

/** Pure Kotlin assertions for the language aliases used by S2. */
object LanguageNormalizationSamples {
    @JvmStatic
    fun main(args: Array<String>) {
        check(normalizeLanguage("chi") == "zh")
        check(normalizeLanguage("zho") == "zh")
        check(normalizeLanguage("zh-Hant") == "zh")
        check(normalizeLanguage("Chinese") == "zh")
        check(normalizeLanguage("中文") == "zh")
        check(normalizeLanguage("jpn") == "ja")
        check(normalizeLanguage("Japanese") == "ja")
        check(normalizeLanguage("kor") == "ko")
        check(normalizeLanguage("English") == "en")
        check(normalizeLanguage(null) == null)
        check(normalizeLanguage("") == null)
        check(normalizeLanguage("xyz") == "xyz")
        println("Language normalization samples passed.")
    }
}
