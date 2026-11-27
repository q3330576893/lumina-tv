package com.lumina.tv.player

import org.json.JSONObject

internal enum class AudioOutputConfidence { OBSERVED, SUSPICIOUS, UNKNOWN }
internal enum class MpvAudioOutputHealth { CONFIGURED, CONFIRMED_NO_OUTPUT, SUSPICIOUS, UNKNOWN }

internal fun mpvAudioOutputHealth(audioParams: String?, currentAo: String?): MpvAudioOutputHealth {
    val hasParams = !audioParams.isNullOrBlank() && audioParams !in setOf("null", "{}", "unavailable")
    val ao = currentAo?.trim().orEmpty()
    val hasAo = ao.isNotEmpty() && ao.lowercase() !in setOf("null", "unknown", "unavailable")
    return when {
        hasParams && ao.equals("no", ignoreCase = true) -> MpvAudioOutputHealth.CONFIRMED_NO_OUTPUT
        hasParams && hasAo -> MpvAudioOutputHealth.CONFIGURED
        hasParams || hasAo -> MpvAudioOutputHealth.SUSPICIOUS
        else -> MpvAudioOutputHealth.UNKNOWN
    }
}

/** Runtime output evidence belongs to one exact logical playback/route identity. */
internal data class AudioOutputBinding(
    val scopeId: String,
    val itemId: String,
    val sourceId: String,
    val trackId: String,
    val engine: String,
    val routeRevision: Long,
    val generation: Int,
) {
    fun json(): JSONObject = JSONObject()
        .put("scope", scopeId)
        .put("item", itemId)
        .put("source", sourceId)
        .put("track", trackId)
        .put("engine", engine)
        .put("routeRevision", routeRevision)
        .put("generation", generation)
}

internal data class AudioOutputSnapshot(
    val binding: AudioOutputBinding,
    val sampledAtElapsedRealtimeMs: Long?,
    val confidence: AudioOutputConfidence,
    val values: Map<String, String> = emptyMap(),
) {
    fun json(): JSONObject = JSONObject()
        .put("binding", binding.json())
        .put("sampledAtElapsedRealtimeMs", sampledAtElapsedRealtimeMs ?: JSONObject.NULL)
        .put("confidence", confidence.name)
        .put("values", JSONObject(values))
}

/** Drops observations as soon as any item, source, track, engine, generation, or route changes. */
internal class AudioOutputSnapshotLedger {
    private var routeRevision = 0L
    private var binding: AudioOutputBinding? = null
    private var sample: AudioOutputSnapshot? = null

    fun bind(
        scopeId: String,
        itemId: String,
        sourceId: String?,
        trackId: String?,
        engine: String,
        generation: Int,
    ): AudioOutputBinding {
        val next = AudioOutputBinding(
            scopeId = scopeId,
            itemId = itemId,
            sourceId = sourceId?.takeIf(String::isNotBlank) ?: "unknown",
            trackId = trackId?.takeIf(String::isNotBlank) ?: "unknown",
            engine = engine,
            routeRevision = routeRevision,
            generation = generation,
        )
        if (binding != next) sample = null
        binding = next
        return next
    }

    fun bindTrack(trackId: String?) {
        val current = binding ?: return
        bind(current.scopeId, current.itemId, current.sourceId, trackId, current.engine, current.generation)
    }

    fun routeChanged(): AudioOutputBinding? {
        routeRevision++
        val current = binding ?: return null
        val next = current.copy(routeRevision = routeRevision)
        binding = next
        sample = null
        return next
    }

    fun record(snapshot: AudioOutputSnapshot): Boolean {
        if (snapshot.binding != binding || snapshot.sampledAtElapsedRealtimeMs == null) return false
        sample = snapshot
        return true
    }

    fun invalidateSample() {
        sample = null
    }

    fun current(): AudioOutputSnapshot? {
        val current = binding ?: return null
        return sample?.takeIf { it.binding == current }
            ?: AudioOutputSnapshot(current, null, AudioOutputConfidence.UNKNOWN)
    }
}

internal enum class Media3AudioPath { PCM, ENCODED_PASSTHROUGH, UNKNOWN }

internal fun media3DirectOutputDisabled(audioMode: String, sampleMimeType: String?): Boolean =
    audioMode == "soft" && sampleMimeType?.startsWith("audio/") == true && sampleMimeType != "audio/raw"

internal fun media3AudioPath(encoding: Int): Media3AudioPath = when {
    encoding in AudioCodecMatrix.PASSTHROUGH_ENCODINGS -> Media3AudioPath.ENCODED_PASSTHROUGH
    encoding in setOf(
        android.media.AudioFormat.ENCODING_PCM_8BIT,
        android.media.AudioFormat.ENCODING_PCM_16BIT,
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
        android.media.AudioFormat.ENCODING_PCM_32BIT,
        android.media.AudioFormat.ENCODING_PCM_FLOAT,
    ) -> Media3AudioPath.PCM
    else -> Media3AudioPath.UNKNOWN
}

internal fun mpvAudioSpdifCodecs(mode: String, caps: AudioCaps): String {
    if (mode == "soft") return ""
    val supported = caps.profiles.filterValues { it.isNotEmpty() }.keys
    return buildList {
        if (5 in supported) add("ac3")
        if (6 in supported || 18 in supported) add("eac3")
        if (7 in supported) add("dts")
        if (8 in supported) add("dts-hd")
        if (14 in supported) add("truehd")
    }.joinToString(",")
}
