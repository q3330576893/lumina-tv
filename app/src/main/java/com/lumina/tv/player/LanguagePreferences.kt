package com.lumina.tv.player

import com.lumina.tv.data.MediaStream
import java.util.Locale

/** Normalize Emby/decoder language tags without depending on Android or the device locale. */
fun normalizeLanguage(raw: String?): String? {
    val language = raw?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() } ?: return null
    return when (language.substringBefore('-')) {
        "zh", "chi", "zho", "chinese", "中文", "国语", "普通话" -> "zh"
        "en", "eng", "english", "英语", "英文" -> "en"
        "ja", "jpn", "japanese", "日语", "日文" -> "ja"
        "ko", "kor", "korean", "韩语", "韩文" -> "ko"
        else -> language
    }
}

/** 按片记忆（包括手动 auto） > 全局语言/关闭 > Emby auto；自动选择不写入按片记忆。 */
fun resolveSubtitlePreference(
    remembered: String?,
    preferredLanguage: String?,
    streams: List<MediaStream>,
): String {
    if (remembered != null) return remembered
    if (preferredLanguage == "off") return "off"
    val language = normalizeLanguage(preferredLanguage)
    if (language != null) {
        streams.firstOrNull { normalizeLanguage(it.language) == language }
            ?.let { return "track:emby:${it.index}" }
    }
    return "auto"
}

fun preferredAudioStream(
    rememberedIndex: Int?,
    preferredLanguage: String?,
    streams: List<MediaStream>,
): MediaStream? {
    if (rememberedIndex != null) return streams.firstOrNull { it.index == rememberedIndex }
    val language = normalizeLanguage(preferredLanguage) ?: return null
    val matches = streams.filter { normalizeLanguage(it.language) == language }
    return matches.firstOrNull { it.isDefault } ?: matches.firstOrNull()
}
