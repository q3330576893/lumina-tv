package com.lumina.tv.player

import com.lumina.tv.data.MediaStream

data class TextSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

fun MediaStream.secondarySubtitleUnsupportedReason(): String? {
    val format = listOfNotNull(codec, profile).joinToString(" ").lowercase()
    val description = listOfNotNull(title, displayTitle).joinToString(" ").lowercase()
    return when {
        listOf("pgs", "hdmv", "vobsub", "dvdsub", "bitmap", "dvbsub", "xsub")
            .any { format.contains(it) || description.contains(it) } -> "图形字幕不支持"
        listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "text", "ttml", "sami", "smi").any { format.contains(it) } -> null
        else -> "字幕格式未知，无法确认是文本"
    }
}

fun parseTextSubtitle(input: String): List<TextSubtitleCue> {
    val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val blocks = normalized.split(Regex("\n\\s*\n"))
    return blocks.mapNotNull { block ->
        val lines = block.lines().map { it.trim() }
        val timeIndex = lines.indexOfFirst { "-->" in it }
        if (timeIndex < 0) return@mapNotNull null
        val times = lines[timeIndex].split("-->", limit = 2)
        if (times.size != 2) return@mapNotNull null
        val start = parseSubtitleTime(times[0].trim().substringBefore(' ')) ?: return@mapNotNull null
        val end = parseSubtitleTime(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
        val text = lines.drop(timeIndex + 1).joinToString("\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").trim()
        if (text.isBlank() || end <= start) null else TextSubtitleCue(start, end, text)
    }.sortedBy(TextSubtitleCue::startMs)
}

fun findTextSubtitleCue(cues: List<TextSubtitleCue>, positionMs: Long): TextSubtitleCue? {
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            positionMs < cue.startMs -> high = mid - 1
            positionMs >= cue.endMs -> low = mid + 1
            else -> return cue
        }
    }
    return null
}

private fun parseSubtitleTime(value: String): Long? {
    val parts = value.replace(',', '.').split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()).coerceAtLeast(0L)
}
