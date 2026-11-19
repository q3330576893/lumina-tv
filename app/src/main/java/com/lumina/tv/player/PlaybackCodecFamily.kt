package com.lumina.tv.player

import java.util.Locale

/** Normalizes server and Media3 codec labels without guessing unknown formats. */
internal fun codecFamily(raw: String?): String? {
    val value = raw?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
        ?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
    val key = value.replace("_", "-")
    return when (key) {
        "ac3", "ac-3", "a52" -> "ac3"
        "eac3", "e-ac-3", "ec3", "ec-3" -> "eac3"
        "truehd", "true-hd", "mlp" -> "truehd"
        "dts", "dca", "dts.hd", "dts-hd", "dts-ma", "dts-hra", "dts-express", "dts-uhd", "dts-hd ma", "dts-hd hra" -> "dts"
        "aac", "mp4a", "mp4a-latm" -> "aac"
        "mpeg", "mp3", "mpeg-l1", "mpeg-l2" -> "mp3"
        "x-subrip", "subrip", "srt", "utf8" -> "subrip"
        "x-ssa", "ssa", "x-ass", "ass", "s-text/ssa", "s-text/ass" -> "ssa"
        "pgs", "hdmv-pgs-subtitle", "s-hdmv/pgs" -> "pgs"
        "vobsub", "dvd-subtitle", "s-vobsub" -> "vobsub"
        "dvbsubs", "dvbsub", "dvb-subtitle" -> "dvbsub"
        "ttml+xml", "ttml", "stpp" -> "ttml"
        "vtt", "webvtt", "mp4vtt", "wvtt" -> "vtt"
        "x-quicktime-tx3g", "tx3g" -> "tx3g"
        "flac" -> "flac"
        "opus" -> "opus"
        "vorbis" -> "vorbis"
        else -> when {
            key == "vnd.dts" || key.startsWith("vnd.dts.") || key.startsWith("dts-") -> "dts"
            else -> key
        }
    }
}
