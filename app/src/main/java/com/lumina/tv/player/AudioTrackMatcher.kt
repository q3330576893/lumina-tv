package com.lumina.tv.player

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import com.lumina.tv.data.MediaStream

data class AudioTrackCandidate(val group: TrackGroup, val index: Int, val format: Format, val supported: Boolean)
data class Media3TrackKey(val group: TrackGroup, val index: Int) {
    override fun toString() = "g${group.id.takeIf { progressiveQueueIdentity(it) != null } ?: group.hashCode()}:$index"
}

const val EXTERNAL_SUBTITLE_ID_PREFIX = "lumina-external-subtitle:"

/** MergingMediaPeriod adds a numeric child-period prefix to both group IDs and format IDs. */
internal fun progressiveQueueIdentity(groupId: String): Pair<String, Int>? {
    if (!groupId.matches(Regex("[0-9]+(?::[0-9]+)*"))) return null
    return groupId.substringAfterLast(':').toIntOrNull()?.let { groupId.substringBeforeLast(':', "") to it }
}

internal fun externalSubtitleSourceIndex(formatId: String?): Int? = formatId?.let {
    Regex("(?:[0-9]+:)*${EXTERNAL_SUBTITLE_ID_PREFIX}([0-9]+)").matchEntire(it)
        ?.groupValues?.get(1)?.toIntOrNull()
}

/** Input includes unsupported tracks. Capability belongs to selection, never identity. */
fun media3TrackMapping(streams: List<MediaStream>, candidates: List<AudioTrackCandidate>,
    context: TrackMappingContext): TrackMapping<Media3TrackKey> {
    // Tracks groups can be regrouped by renderer (including unsupported groups). Progressive
    // group IDs identify queue creation order, not Emby indices or container TrackNumbers.
    val queues = candidates.mapNotNull { progressiveQueueIdentity(it.group.id) }
    val verifiedOrder = context.orderEvidence != null && candidates.isNotEmpty() &&
        candidates.all { it.group.length == 1 && it.format.containerMimeType in
            setOf("video/x-matroska", "audio/x-matroska", "video/webm", "audio/webm") } &&
        queues.size == candidates.size && queues.map { it.first }.distinct().size == 1 &&
        queues.map { it.second }.distinct().size == candidates.size
    val ordered = if (verifiedOrder) candidates.sortedBy { progressiveQueueIdentity(it.group.id)!!.second } else candidates
    return buildTrackMapping(streams, ordered.map {
        val externalIndex = externalSubtitleSourceIndex(it.format.id)
        PlayerTrack(Media3TrackKey(it.group, it.index), language = it.format.language,
            codec = it.format.sampleMimeType ?: it.format.codecs, title = it.format.label,
            channels = it.format.channelCount.takeIf { count -> count > 0 },
            externalSourceIndex = externalIndex, external = externalIndex != null)
    }, context.copy(orderEvidence = context.orderEvidence.takeIf { verifiedOrder }))
}

fun TrackMapping<Media3TrackKey>.candidateFor(embyIndex: Int,
    candidates: List<AudioTrackCandidate>): AudioTrackCandidate? = playerFor(embyIndex)?.let { key ->
    candidates.singleOrNull { it.group == key.group && it.index == key.index }
}
