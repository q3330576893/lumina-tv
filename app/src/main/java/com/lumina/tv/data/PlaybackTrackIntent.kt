package com.lumina.tv.data

import com.lumina.tv.player.normalizeLanguage
import com.lumina.tv.player.trackMemoryIdentity
import org.json.JSONObject

enum class SubtitleIntentMode { AUTO, OFF, TRACK }

data class PlaybackTrackIntent(
    val sourceId: String,
    val audioIdentity: String,
    val subtitleIdentity: String,
    val audioIndex: Int?,
    val subtitleMode: SubtitleIntentMode,
    val subtitleIndex: Int?,
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
    audioLanguage: String?, subtitleLanguage: String?, explicitAudio: Int? = null): PlaybackTrackIntent {
    val audio = source.audioStreams
    val subtitles = source.subtitleStreams
    val audioIndex = explicitAudio?.takeIf { index -> audio.any { it.index == index } }
        ?: rememberedAudio?.takeIf { index -> audio.any { it.index == index } }
        ?: normalizeLanguage(audioLanguage)?.let { language ->
            val matches = audio.filter { normalizeLanguage(it.language) == language }
            (matches.firstOrNull { it.isDefault } ?: matches.firstOrNull())?.index
        } ?: source.defaultAudioStreamIndex?.takeIf { index -> audio.any { it.index == index } }
        ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
    val rememberedIndex = rememberedSubtitle?.takeIf { it.startsWith("track:emby:") }
        ?.removePrefix("track:emby:")?.toIntOrNull()?.takeIf { index -> subtitles.any { it.index == index } }
    val validRemembered = rememberedSubtitle?.takeIf { it == "off" || it == "auto" || rememberedIndex != null }
    val language = normalizeLanguage(subtitleLanguage)
    val languageMatch = subtitles.filter { language != null && normalizeLanguage(it.language) == language }
        .let { matches -> matches.firstOrNull { it.isDefault } ?: matches.firstOrNull() }
    val mode = when {
        validRemembered == "off" -> SubtitleIntentMode.OFF
        validRemembered == "auto" -> SubtitleIntentMode.AUTO
        rememberedIndex != null -> SubtitleIntentMode.TRACK
        subtitleLanguage == "off" -> SubtitleIntentMode.OFF
        languageMatch != null -> SubtitleIntentMode.TRACK
        else -> SubtitleIntentMode.AUTO
    }
    val subtitleIndex = when (mode) {
        SubtitleIntentMode.OFF -> null
        SubtitleIntentMode.TRACK -> rememberedIndex ?: languageMatch?.index
        SubtitleIntentMode.AUTO -> source.defaultSubtitleStreamIndex?.takeIf { index -> index == -1 || subtitles.any { it.index == index } }
            ?: subtitles.firstOrNull { it.isDefault }?.index
    }
    return PlaybackTrackIntent(source.id, trackMemoryIdentity(source, "Audio"), trackMemoryIdentity(source, "Subtitle"),
        audioIndex, mode, subtitleIndex)
}

data class PreparedPlaybackTracks(val source: MediaSource, val intent: PlaybackTrackIntent,
    val availableSources: List<MediaSource>)

// PlaybackRequestKey/PlaybackProtocol remain unchanged; C03 adds the resolved track identity here.
internal data class TrackAwarePlaybackKey(val request: PlaybackRequestKey, val tracks: PlaybackTrackIntent)
