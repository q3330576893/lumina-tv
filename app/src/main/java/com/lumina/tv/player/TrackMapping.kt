package com.lumina.tv.player

import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.MediaStream
import java.security.MessageDigest
import java.util.Locale

enum class TrackMatchBasis { EXTERNAL_SUBTITLE, FFMPEG_INDEX, VERIFIED_CONTAINER_ORDER, UNIQUE_METADATA, NEGOTIATED_OUTPUT, UNRESOLVED }
enum class TrackConfidence { HIGH, MEDIUM, UNCERTAIN }

data class TrackMatch<K>(
    val embyIndex: Int?,
    val playerKey: K?,
    val basis: TrackMatchBasis,
    val confidence: TrackConfidence,
    val detail: String,
)

data class TrackMapping<K>(val entries: List<TrackMatch<K>>) {
    fun playerFor(embyIndex: Int): K? = entries.singleOrNull {
        it.embyIndex == embyIndex && it.confidence != TrackConfidence.UNCERTAIN
    }?.playerKey
    fun embyFor(playerKey: K): Int? = entries.singleOrNull {
        it.playerKey == playerKey && it.confidence != TrackConfidence.UNCERTAIN
    }?.embyIndex
    fun summary(): String = entries.joinToString { "${it.embyIndex ?: "?"}->${it.playerKey ?: "?"}(${it.basis}/${it.confidence}:${it.detail})" }
}

data class PlayerTrack<K>(val key: K, val ffIndex: Int? = null, val language: String? = null,
    val codec: String? = null, val title: String? = null, val channels: Int? = null,
    val externalSourceIndex: Int? = null, val external: Boolean = false)

data class TrackMappingContext(val originalFile: Boolean = false, val orderEvidence: String? = null,
    val negotiatedIndex: Int? = null, val singleOutput: Boolean = false)

/** Media3 1.7.1 MatroskaExtractor initializes output at each TrackEntry end; ffprobe
 * creates AVStreams in that same declaration order. ProgressiveMediaPeriod appends queues
 * in output creation order. Full cardinality/metadata checks are still required below.
 * MP4 is deliberately excluded: fragmented extraction can enumerate tracks by track ID.
 * The Media3 adapter also verifies container MIME, one-format groups and queue order. */
fun media3OrderEvidence(originalFile: Boolean, container: String?): String? = if (!originalFile) null else
    when (container?.lowercase(Locale.ROOT)) {
        "mkv", "matroska", "webm" -> "original-matroska-TrackEntry-order"
        else -> null
    }

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

private fun language(raw: String?) = normalizeLanguage(raw)?.takeUnless { it == "und" }
private fun <K> compatible(stream: MediaStream, track: PlayerTrack<K>): Boolean {
    val a = language(stream.language)
    val b = language(track.language)
    val c = codecFamily(stream.codec)
    val d = codecFamily(track.codec)
    return (a == null || b == null || a == b) && (c == null || d == null || c == d) &&
        (stream.channels == null || track.channels == null || stream.channels == track.channels)
}

private fun <K> score(stream: MediaStream, track: PlayerTrack<K>): Int {
    if (!compatible(stream, track)) return -1
    return (if (language(stream.language) != null && language(stream.language) == language(track.language)) 8 else 0) +
        (if (stream.channels != null && stream.channels == track.channels) 4 else 0) +
        (if (codecFamily(stream.codec) != null && codecFamily(stream.codec) == codecFamily(track.codec)) 4 else 0) +
        (if (!stream.title.isNullOrBlank() && stream.title.equals(track.title, true)) 8 else 0)
}

/** One bijection for the complete track set; ambiguous ties never consume a track. */
fun <K> buildTrackMapping(streams: List<MediaStream>, tracks: List<PlayerTrack<K>>,
    context: TrackMappingContext): TrackMapping<K> {
    val result = mutableListOf<TrackMatch<K>>()
    val remainingStreams = streams.toMutableList()
    val remainingTracks = tracks.toMutableList()
    fun bind(stream: MediaStream, track: PlayerTrack<K>, basis: TrackMatchBasis, detail: String) {
        result += TrackMatch(stream.index, track.key, basis,
            if (basis == TrackMatchBasis.UNIQUE_METADATA) TrackConfidence.MEDIUM else TrackConfidence.HIGH, detail)
        remainingStreams.remove(stream)
        remainingTracks.remove(track)
    }
    val uniqueIdentities = streams.map { it.index }.distinct().size == streams.size &&
        tracks.map { it.key }.distinct().size == tracks.size
    if (uniqueIdentities) streams.filter { it.isExternal }.forEach { stream ->
        val explicit = remainingTracks.singleOrNull { it.externalSourceIndex == stream.index }
        if (explicit != null) bind(stream, explicit, TrackMatchBasis.EXTERNAL_SUBTITLE, "app-attached-source-index")
    }
    if (uniqueIdentities && context.singleOutput) {
        // Output ff-index/TrackNumber refer to the new container, not the original file.
        val selected = remainingStreams.singleOrNull { it.index == context.negotiatedIndex && !it.isExternal }
        val output = remainingTracks.filterNot { it.external }
        if (selected != null && output.size == 1) bind(selected, output.single(),
            TrackMatchBasis.NEGOTIATED_OUTPUT, "selected-source-only")
    } else if (uniqueIdentities) {
        if (context.originalFile) remainingStreams.toList().filterNot { it.isExternal }.forEach { stream ->
            val matches = remainingTracks.filter { !it.external && it.ffIndex == stream.index }
            if (matches.size == 1 && compatible(stream, matches.single())) {
                bind(stream, matches.single(), TrackMatchBasis.FFMPEG_INDEX, "original-ff-index")
            }
        }
        val ordered = streams.filterNot { it.isExternal }.sortedBy { it.index }
        val internalTracks = tracks.filterNot { it.external }
        val canUseOrder = context.originalFile && context.orderEvidence != null && ordered.size == internalTracks.size &&
            ordered.zip(internalTracks).all { (stream, track) -> compatible(stream, track) &&
                (track.ffIndex == null || track.ffIndex == stream.index) &&
                result.none { (it.embyIndex == stream.index) != (it.playerKey == track.key) } }
        if (canUseOrder) ordered.zip(internalTracks).forEach { (stream, track) ->
            if (stream in remainingStreams) bind(stream, track, TrackMatchBasis.VERIFIED_CONTAINER_ORDER, context.orderEvidence!!)
        }
        // Mutual unique best match: equal attributes stay uncertain, with no first-entry tie-break.
        var changed: Boolean
        fun evidenceScore(stream: MediaStream, track: PlayerTrack<K>): Int {
            if (stream.isExternal != track.external) return -1
            // A contradictory/duplicated ff-index must not be repaired by a metadata guess.
            if (context.originalFile && track.ffIndex != null &&
                (track.ffIndex != stream.index || tracks.count { it.ffIndex == track.ffIndex } != 1)) return -1
            return score(stream, track)
        }
        do {
            changed = false
            for (stream in remainingStreams.toList()) {
                val scores = remainingTracks.map { it to evidenceScore(stream, it) }
                val best = scores.maxOfOrNull { it.second } ?: continue
                val track = scores.filter { it.second == best && best > 0 }.singleOrNull()?.first ?: continue
                val reverse = remainingStreams.map { it to evidenceScore(it, track) }
                val reverseBest = reverse.maxOf { it.second }
                if (reverse.filter { it.second == reverseBest }.singleOrNull()?.first == stream) {
                    bind(stream, track, TrackMatchBasis.UNIQUE_METADATA, "mutual-unique")
                    changed = true
                }
            }
        } while (changed)
    }
    val reason = when {
        !uniqueIdentities -> "duplicate-identity"
        context.singleOutput -> "output-not-associated"
        streams.size != tracks.size -> "count-changed-or-missing"
        else -> "ambiguous-or-insufficient-evidence"
    }
    remainingStreams.forEach { result += TrackMatch(it.index, null, TrackMatchBasis.UNRESOLVED, TrackConfidence.UNCERTAIN, reason) }
    remainingTracks.forEach { result += TrackMatch(null, it.key, TrackMatchBasis.UNRESOLVED, TrackConfidence.UNCERTAIN, reason) }
    return TrackMapping(result)
}

/** Bind an Index to one version AND its inventory. Legacy unbound indices are not trusted. */
fun trackMemoryIdentity(source: MediaSource, type: String): String {
    val inventory = source.streams.filter { it.type.equals(type, true) }.sortedBy { it.index }
        .map { listOf(it.index, it.codec, it.profile, it.language, it.channels, it.title, it.isExternal).toString() }
    return MessageDigest.getInstance("SHA-256").digest((source.id + inventory).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
