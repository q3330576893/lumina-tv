package com.lumina.tv.player

import com.lumina.tv.data.MediaStream
import java.util.Locale

/** Pure Kotlin/JVM check() assertions; no JUnit or Android runtime. */
object S2LanguageChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        val aliases = mapOf(
            "zh" to listOf("zh", "chi", "zho", "zh-CN", "zh-Hans", "zh-Hant", "Chinese", "中文", "国语", "普通话", "zh_Hant_TW"),
            "en" to listOf("en", "eng", "English", "英语", "英文", "en-US"),
            "ja" to listOf("ja", "jpn", "Japanese", "日语", "日文", "ja-JP"),
            "ko" to listOf("ko", "kor", "Korean", "韩语", "韩文", "ko-KR"),
        )
        aliases.forEach { (expected, variants) ->
            variants.forEach { raw -> check(normalizeLanguage(raw) == expected) { "$raw -> $expected" } }
        }
        check(normalizeLanguage(null) == null)
        check(normalizeLanguage(" \t") == null)
        check(normalizeLanguage(" CHI ") == "zh")
        check(normalizeLanguage("FR-ca") == "fr-ca")
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            check(normalizeLanguage("CHINESE") == "zh")
            check(normalizeLanguage("ENGLISH") == "en")
        } finally {
            Locale.setDefault(originalLocale)
        }

        fun track(index: Int, language: String, type: String = "Subtitle", default: Boolean = false) =
            MediaStream(index, type, null, language, null, default, false, null)
        val subtitles = listOf(track(2, "eng", default = true), track(4, "zh-Hant"), track(6, "jpn"))
        check(resolveSubtitlePreference(null, "zh", subtitles) == "track:emby:4")
        check(resolveSubtitlePreference(null, "en", subtitles) == "track:emby:2")
        check(resolveSubtitlePreference(null, "ja", subtitles) == "track:emby:6")
        check(resolveSubtitlePreference(null, "ko", subtitles) == "auto")
        check(resolveSubtitlePreference(null, "", subtitles) == "auto")
        check(resolveSubtitlePreference(null, null, subtitles) == "auto")
        check(resolveSubtitlePreference(null, "zh", emptyList()) == "auto")
        check(resolveSubtitlePreference(null, "off", subtitles) == "off")
        check(resolveSubtitlePreference("off", "zh", subtitles) == "off")
        check(resolveSubtitlePreference("auto", "zh", subtitles) == "auto")
        check(resolveSubtitlePreference("auto", "off", subtitles) == "auto")
        check(resolveSubtitlePreference("track:emby:2", "zh", subtitles) == "track:emby:2")
        check(resolveSubtitlePreference("track:emby:4", "off", subtitles) == "track:emby:4")
        // A missing remembered track must be handled as auto by the player, never by the global language.
        check(resolveSubtitlePreference("track:emby:99", "zh", subtitles) == "track:emby:99")

        val audio = listOf(track(1, "eng", "Audio", true), track(3, "chi", "Audio"), track(5, "kor", "Audio"))
        check(preferredAudioStream(null, "zh", audio)?.index == 3)
        check(preferredAudioStream(null, "ko", audio)?.index == 5)
        check(preferredAudioStream(null, "ja", audio) == null)
        check(preferredAudioStream(null, "", audio) == null)
        check(preferredAudioStream(1, "zh", audio)?.index == 1)
        check(preferredAudioStream(99, "zh", audio) == null)
        println("S2 checks passed: language aliases, locale independence, subtitle priority/fallback, audio preference/memory.")
    }
}
