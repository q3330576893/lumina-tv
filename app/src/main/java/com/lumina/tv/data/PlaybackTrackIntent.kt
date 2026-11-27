package com.lumina.tv.data

import com.lumina.tv.player.normalizeLanguage
import com.lumina.tv.player.trackMemoryIdentity
import com.lumina.tv.player.TrackMemoryIdentity
import org.json.JSONObject

enum class SubtitleIntentMode { AUTO, OFF, TRACK }

data class PlaybackTrackIntent(
    val sourceId: String,
    val audioIdentity: TrackMemoryIdentity,
    val subtitleIdentity: TrackMemoryIdentity,
    val audioIndex: Int?,
    val subtitleMode: SubtitleIntentMode,
    val subtitleIndex: Int?,
    // A preparation nonce prevents reusing prewarm results without content attestation.
    // It is intentionally ephemeral and is never a content identity or persisted preference.
    val preparationNonce: String? = if (audioIdentity is TrackMemoryIdentity.Available &&
        subtitleIdentity is TrackMemoryIdentity.Available) null else java.util.UUID.randomUUID().toString(),
) {
    val subtitlePreference: String get() = when (subtitleMode) {
        SubtitleIntentMode.OFF -> "off"
        SubtitleIntentMode.AUTO -> "auto"
        SubtitleIntentMode.TRACK -> "track:emby:$subtitleIndex"
    }
    // Emby uses -1 to explicitly disable subtitles. AUTO without a default remains absent.
    val requestedSubtitleIndex: Int? get() = if (subtitleMode == SubtitleIntentMode.OFF) -1 else subtitleIndex

    fun applyToPlaybackInfo(body: JSONObject, disableSubtitles: Boolean = false) {
        body.put("MediaSourceId", sourceId)
        body.remove("AudioStreamIndex")
        body.remove("SubtitleStreamIndex")
        audioIndex?.let { body.put("AudioStreamIndex", it) }
        (if (disableSubtitles) -1 else requestedSubtitleIndex)?.let { body.put("SubtitleStreamIndex", it) }
    }
}

fun resolvePlaybackTrackIntent(source: MediaSource, rememberedAudio: Int?, rememberedSubtitle: String?,
    audioLanguage: String?, subtitleLanguage: String?, explicitAudio: Int? = null, explicitSubtitle: Int? = null): PlaybackTrackIntent {
    val audio = source.audioStreams
    val subtitles = source.subtitleStreams
    val audioIndex = explicitAudio?.takeIf { index -> audio.count { it.index == index } == 1 }
        ?: rememberedAudio?.takeIf { index -> audio.count { it.index == index } == 1 }
        ?: normalizeLanguage(audioLanguage)?.let { language ->
            val matches = audio.filter { normalizeLanguage(it.language) == language }
            (matches.firstOrNull { it.isDefault } ?: matches.firstOrNull())?.index
        } ?: source.defaultAudioStreamIndex?.takeIf { index -> audio.count { it.index == index } == 1 }
        ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
    val selectedSubtitle = explicitSubtitle?.takeIf { index -> index >= 0 && subtitles.count { it.index == index } == 1 }
    val rememberedIndex = rememberedSubtitle?.takeIf { it.startsWith("track:emby:") }
        ?.removePrefix("track:emby:")?.toIntOrNull()?.takeIf { index -> subtitles.count { it.index == index } == 1 }
    val validRemembered = rememberedSubtitle?.takeIf { it == "off" || it == "auto" || rememberedIndex != null }
    val language = normalizeLanguage(subtitleLanguage)
    val languageMatch = subtitles.filter { language != null && normalizeLanguage(it.language) == language }
        .let { matches -> matches.firstOrNull { it.isDefault } ?: matches.firstOrNull() }
    val mode = when {
        selectedSubtitle != null -> SubtitleIntentMode.TRACK
        validRemembered == "off" -> SubtitleIntentMode.OFF
        validRemembered == "auto" -> SubtitleIntentMode.AUTO
        rememberedIndex != null -> SubtitleIntentMode.TRACK
        subtitleLanguage == "off" -> SubtitleIntentMode.OFF
        languageMatch != null -> SubtitleIntentMode.TRACK
        else -> SubtitleIntentMode.AUTO
    }
    val subtitleIndex = when (mode) {
        SubtitleIntentMode.OFF -> null
        SubtitleIntentMode.TRACK -> selectedSubtitle ?: rememberedIndex ?: languageMatch?.index
        SubtitleIntentMode.AUTO -> source.defaultSubtitleStreamIndex?.takeIf { index -> index == -1 || subtitles.count { it.index == index } == 1 }
            ?: subtitles.firstOrNull { it.isDefault }?.index
    }
    return PlaybackTrackIntent(source.id, trackMemoryIdentity(source, "Audio"), trackMemoryIdentity(source, "Subtitle"),
        audioIndex, mode, subtitleIndex)
}

data class PreparedPlaybackTracks(val source: MediaSource, val intent: PlaybackTrackIntent,
    val availableSources: List<MediaSource>)

// PlaybackRequestKey/PlaybackProtocol remain unchanged; C03 adds the resolved track identity here.
internal data class TrackAwarePlaybackKey(val request: PlaybackRequestKey, val tracks: PlaybackTrackIntent)

/** Retain the original inventory for player mapping, but never borrow a different response's
 * validator to attest that inventory. A missing response validator is not the old validator. */
internal fun trackSourceAfterPlaybackInfo(probed: MediaSource, response: MediaSource): MediaSource = response.copy(
    streams = probed.streams,
    defaultAudioStreamIndex = probed.defaultAudioStreamIndex,
    defaultSubtitleStreamIndex = probed.defaultSubtitleStreamIndex,
    contentValidator = response.contentValidator.takeIf { response.id == probed.id &&
        response.contentValidator == probed.contentValidator && response.contentVersion == probed.contentVersion },
)
