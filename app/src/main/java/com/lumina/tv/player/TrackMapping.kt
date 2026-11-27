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

enum class TrackMemoryType { Audio, Subtitle }

enum class TrackInvalidReason {
    NO_CONTEXT, SOURCE_CHANGED, TYPE_CHANGED, VERSION_CHANGED, UNATTESTED_VERSION,
    INVENTORY_CHANGED, INDEX_CHANGED, EXTERNAL_UNATTESTED, SCHEMA_LEGACY,
    LEGACY_UNVERIFIABLE, MALFORMED_RECORD, UNKNOWN_SCHEMA,
}

/** Constructed only after a producer's provenance and byte coverage have been reviewed.
 * The current MediaSource opaque hashes do not provide that attestation. */
data class TrackVersionEvidence internal constructor(val kind: String, val value: String,
    val coverage: String = "SOURCE_BYTES") {
    internal fun isValid() = kind in setOf("ETag", "DateModified", "DateLastSaved") &&
        value.isNotBlank() && coverage == "SOURCE_BYTES"
}

data class TrackIdentityV2 internal constructor(
    val schemaVersion: Int = 2,
    val type: TrackMemoryType,
    val sourceId: String,
    val validator: TrackVersionEvidence,
    val sourceVersion: String,
    val inventoryDigest: String,
)

sealed interface TrackMemoryIdentity {
    data class Available internal constructor(val identity: TrackIdentityV2) : TrackMemoryIdentity
    data class Unavailable(val reason: TrackInvalidReason) : TrackMemoryIdentity
}

/** Fail closed: neither contentVersion nor an opaque contentValidator attests its provenance.
 * A future positive producer requires a separate review; there is no hash-shaped escape hatch. */
fun trackMemoryIdentity(source: MediaSource?, type: String): TrackMemoryIdentity {
    val typed = TrackMemoryType.entries.singleOrNull { it.name == type }
        ?: return TrackMemoryIdentity.Unavailable(TrackInvalidReason.TYPE_CHANGED)
    return attestedTrackMemoryIdentity(source, typed, evidence = null)
}

/** Internal seam for an attested producer and behavioral fixtures, never inferred from metadata. */
internal fun attestedTrackMemoryIdentity(source: MediaSource?, type: TrackMemoryType,
    evidence: TrackVersionEvidence?): TrackMemoryIdentity {
    fun unavailable(reason: TrackInvalidReason) = TrackMemoryIdentity.Unavailable(reason)
    if (source == null) return unavailable(TrackInvalidReason.NO_CONTEXT)
    if (source.id.isBlank()) return unavailable(TrackInvalidReason.SOURCE_CHANGED)
    if (evidence?.isValid() != true) return unavailable(TrackInvalidReason.UNATTESTED_VERSION)
    val inventory = source.streams.filter { it.type == type.name }.sortedBy { it.index }
    if (inventory.isEmpty() || inventory.any { it.index < 0 } || inventory.distinctBy { it.index }.size != inventory.size)
        return unavailable(TrackInvalidReason.INVENTORY_CHANGED)
    return TrackMemoryIdentity.Available(TrackIdentityV2(type = type, sourceId = source.id,
        validator = evidence, sourceVersion = source.contentVersion,
        inventoryDigest = trackInventoryDigest(type, inventory)))
}

/** Every scalar has a type tag; strings have UTF-8 byte lengths and null has its own tag.
 * Include only real MediaStream fields. Titles never imply SDH, role or forced flags. */
internal fun trackInventoryDigest(type: TrackMemoryType, inventory: List<MediaStream>): String {
    val bytes = java.io.ByteArrayOutputStream()
    val out = java.io.DataOutputStream(bytes)
    fun field(value: Any?) {
        when (value) {
            null -> out.writeByte(0)
            is String -> { out.writeByte(1); val utf8 = value.toByteArray(Charsets.UTF_8); out.writeInt(utf8.size); out.write(utf8) }
            is Int -> { out.writeByte(2); out.writeInt(value) }
            is Long -> { out.writeByte(3); out.writeLong(value) }
            is Boolean -> { out.writeByte(4); out.writeBoolean(value) }
            is Double -> { out.writeByte(5); out.writeLong(java.lang.Double.doubleToLongBits(value)) }
            else -> error("Unsupported track identity scalar")
        }
    }
    field("lumina.track-memory"); field(2); field(type.name)
    out.writeByte(6); out.writeInt(inventory.size)
    inventory.sortedBy { it.index }.forEach { s ->
        field(s.index); field(s.type); field(s.codec); field(s.language); field(s.title)
        field(s.isDefault); field(s.isExternal); field(s.displayTitle); field(s.profile)
        field(s.level); field(s.width); field(s.height); field(s.bitRate); field(s.channels)
        field(s.sampleRate); field(s.videoRangeType); field(s.dvProfile); field(s.bitDepth)
        field(s.colorSpace); field(s.colorTransfer); field(s.colorPrimaries); field(s.frameRate)
    }
    out.flush()
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Unavailable is never equal evidence, even when the two reasons happen to be the same. */
fun sameTrackMemoryIdentity(a: TrackMemoryIdentity?, b: TrackMemoryIdentity?): Boolean =
    a is TrackMemoryIdentity.Available && b is TrackMemoryIdentity.Available && a.identity == b.identity

internal fun trackIdentityMismatch(saved: TrackIdentityV2, current: TrackMemoryIdentity): TrackInvalidReason? {
    if (current is TrackMemoryIdentity.Unavailable) return current.reason
    val now = (current as TrackMemoryIdentity.Available).identity
    return when {
        saved.schemaVersion != 2 || now.schemaVersion != 2 -> TrackInvalidReason.SCHEMA_LEGACY
        saved.type != now.type -> TrackInvalidReason.TYPE_CHANGED
        saved.sourceId != now.sourceId -> TrackInvalidReason.SOURCE_CHANGED
        saved.validator != now.validator || saved.sourceVersion != now.sourceVersion -> TrackInvalidReason.VERSION_CHANGED
        saved.inventoryDigest != now.inventoryDigest -> TrackInvalidReason.INVENTORY_CHANGED
        else -> null
    }
}

/** A user gesture belongs to one player instance and one load. With no attestation it can
 * affect only the actual mapping of this exact source object in this load. */
data class ManualTrackSelection(
    val itemId: String,
    val sourceId: String,
    val type: TrackMemoryType,
    val index: Int,
    val playerInstance: String,
    val loadGeneration: Int,
    val identity: TrackMemoryIdentity,
    private val sourceSnapshot: MediaSource,
) {
    fun matchesCurrent(item: String, source: MediaSource?, trackType: TrackMemoryType,
        instance: String, load: Int, currentIdentity: TrackMemoryIdentity): Boolean =
        itemId == item && source != null && sourceId == source.id && type == trackType &&
            playerInstance == instance && loadGeneration == load && validIndex(source) &&
            (sameTrackMemoryIdentity(identity, currentIdentity) ||
                identity is TrackMemoryIdentity.Unavailable && currentIdentity is TrackMemoryIdentity.Unavailable &&
                sourceSnapshot === source)

    fun indexForProbe(item: String, source: MediaSource, trackType: TrackMemoryType,
        currentIdentity: TrackMemoryIdentity): Int? = index.takeIf {
        itemId == item && sourceId == source.id && type == trackType && validIndex(source) &&
            source.streams.single { it.type == type.name && it.index == index }.isExternal.not() &&
            sameTrackMemoryIdentity(identity, currentIdentity)
    }

    fun forLoad(item: String, source: MediaSource, instance: String, previousLoad: Int,
        nextLoad: Int, currentIdentity: TrackMemoryIdentity): ManualTrackSelection? =
        takeIf { playerInstance == instance && loadGeneration == previousLoad &&
            indexForProbe(item, source, type, currentIdentity) != null }
            ?.copy(loadGeneration = nextLoad, sourceSnapshot = source, identity = currentIdentity)

    private fun validIndex(source: MediaSource) = index >= 0 && sourceId.isNotBlank() &&
        source.streams.count { it.type == type.name && it.index == index } == 1
}
