package com.lumina.tv.data

import org.json.JSONObject

object P4NegotiationChecks {
    @JvmStatic fun main(args: Array<String>) {
        fun stream(index: Int, type: String, language: String, default: Boolean = false) =
            MediaStream(index, type, "aac", language, null, default, false, null)
        val source = MediaSource("v1", "source", "mkv", true, true,
            listOf(stream(1, "Audio", "eng", true), stream(2, "Audio", "zh-Hant"),
                stream(3, "Subtitle", "eng", true), stream(4, "Subtitle", "chi")),
            defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = 3)
        fun intent(audio: Int? = null, subtitle: String? = null, language: String = "中文") =
            resolvePlaybackTrackIntent(source, audio, subtitle, language, language)
        check(intent().audioIndex == 2 && intent().subtitleIndex == 4)
        listOf("chi", "zho", "zh-Hant", "Chinese", "中文").forEach { check(intent(language = it).audioIndex == 2) }
        check(intent(1, "track:emby:3").let { it.audioIndex == 1 && it.subtitleIndex == 3 })
        check(intent(99, "track:1:0").let { it.audioIndex == 2 && it.subtitleIndex == 4 })
        val off = intent(subtitle = "off")
        val auto = intent(subtitle = "auto")
        check(off.subtitleMode == SubtitleIntentMode.OFF && off.requestedSubtitleIndex == -1)
        check(auto.subtitleMode == SubtitleIntentMode.AUTO && auto.requestedSubtitleIndex == 3)
        val request = JSONObject().put("AudioStreamIndex", 99).put("SubtitleStreamIndex", 99)
        off.applyToPlaybackInfo(request)
        check(request.getString("MediaSourceId") == "v1" && request.getInt("AudioStreamIndex") == 2)
        check(request.getInt("SubtitleStreamIndex") == -1)
        auto.applyToPlaybackInfo(request)
        check(request.getInt("SubtitleStreamIndex") == 3)
        val serverOff = resolvePlaybackTrackIntent(source.copy(defaultSubtitleStreamIndex = -1), null, "auto", null, "zh")
        check(serverOff.requestedSubtitleIndex == -1 && serverOff.subtitleMode == SubtitleIntentMode.AUTO)
        val noStreams = resolvePlaybackTrackIntent(source.copy(streams = emptyList()), null, null, null, null)
        noStreams.applyToPlaybackInfo(request)
        check(!request.has("AudioStreamIndex") && !request.has("SubtitleStreamIndex"))
        val requestKey = PlaybackRequestKey("item", false, "v1", 2, false, 120_000_000,
            SourceStrategy(), PlaybackStartIntent.Resume, 0, false, false, false)
        check(TrackAwarePlaybackKey(requestKey, off) != TrackAwarePlaybackKey(requestKey, auto))
        check(TrackAwarePlaybackKey(requestKey, off) != TrackAwarePlaybackKey(requestKey, off.copy(sourceId = "v2")))
        check(TrackAwarePlaybackKey(requestKey, off) != TrackAwarePlaybackKey(requestKey, off.copy(audioIdentity = com.lumina.tv.player.TrackMemoryIdentity.Unavailable(com.lumina.tv.player.TrackInvalidReason.VERSION_CHANGED))))
        println("P4 C03 PASS: language negotiation, manual priority, OFF/AUTO, request indices and cache identity")
    }
}
