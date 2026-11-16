package com.lumina.tv.player

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import com.lumina.tv.data.MediaStream

data class AudioTrackCandidate(val group: TrackGroup, val index: Int, val format: Format)

fun bestAudioTrack(
    stream: MediaStream,
    candidates: List<AudioTrackCandidate>,
    preferredLanguage: String?,
): AudioTrackCandidate? {
    candidates.firstOrNull { it.format.id?.toIntOrNull() == stream.index }?.let { return it }

    val streamLanguage = normalizeLanguage(stream.language)
    val preferred = normalizeLanguage(preferredLanguage)
    val embysCommentary = listOfNotNull(stream.title, stream.displayTitle)
        .any { it.contains("commentary", true) || it.contains("评论", true) || it.contains("解说", true) }
    return candidates.map { candidate ->
        val format = candidate.format
        val codec = format.sampleMimeType?.substringAfter('/') ?: format.codecs.orEmpty()
        val label = format.label.orEmpty()
        val candidateCommentary = label.contains("commentary", true) || label.contains("评论") || label.contains("解说")
        var score = 0
        val candidateLanguage = normalizeLanguage(format.language)
        if (streamLanguage != null && candidateLanguage == streamLanguage) score += 8
        if (preferred != null && candidateLanguage == preferred) score += 10
        if (!stream.codec.isNullOrBlank() && codec.contains(stream.codec, true)) score += 8
        if (stream.channels != null && stream.channels == format.channelCount) {
            score += 6
            if (stream.channels == 6 || stream.channels == 8) score += 2
        }
        if (listOfNotNull(stream.title, stream.displayTitle).any { it.isNotBlank() && label.contains(it, true) }) score += 3
        if (label.contains("default", true) || label.contains("主")) score += 1
        if (embysCommentary != candidateCommentary) score -= 12
        candidate to score
    }.maxWithOrNull(
        compareBy<Pair<AudioTrackCandidate, Int>> { it.second }
            .thenBy { -it.first.index }
            .thenBy { if (stream.channels != null && it.first.format.channelCount == stream.channels) 1 else 0 },
    )?.takeIf { it.second > 0 }?.first
}
