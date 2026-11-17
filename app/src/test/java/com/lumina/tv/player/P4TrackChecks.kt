package com.lumina.tv.player

import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.MediaStream

object P4TrackChecks {
    @JvmStatic fun main(args: Array<String>) {
        fun stream(index: Int, language: String = "eng", codec: String = "dts") =
            MediaStream(index, "Audio", codec, language, null, index == 1, false, null, channels = 6)
        val streams = listOf(stream(1), stream(2))
        val tracks = listOf(PlayerTrack("g1:0", language = "en", codec = "audio/vnd.dts.hd", channels = 6),
            PlayerTrack("g2:0", language = "eng", codec = "audio/vnd.dts", channels = 6))
        val original = TrackMappingContext(originalFile = true,
            orderEvidence = media3OrderEvidence(true, "mkv"))
        val ordered = buildTrackMapping(streams, tracks, original)
        check(ordered.playerFor(1) == "g1:0" && ordered.playerFor(2) == "g2:0")
        check(ordered.embyFor("g2:0") == 2)
        check(ordered.entries.all { it.basis == TrackMatchBasis.VERIFIED_CONTAINER_ORDER })
        val ambiguous = buildTrackMapping(streams, tracks, TrackMappingContext(originalFile = true))
        check(ambiguous.entries.all { it.confidence == TrackConfidence.UNCERTAIN })
        check(ambiguous.playerFor(2) == null && ambiguous.embyFor("g1:0") == null)
        check(media3OrderEvidence(true, "mp4") == null && media3OrderEvidence(false, "mkv") == null)
        check(progressiveQueueIdentity("0:2") == ("0" to 2))
        check(progressiveQueueIdentity("0:0:3") == ("0:0" to 3))
        check(progressiveQueueIdentity("hls:2") == null)
        check(externalSubtitleSourceIndex("1:lumina-external-subtitle:4") == 4)
        check(externalSubtitleSourceIndex("4") == null)
        val external = stream(4).copy(type = "Subtitle", isExternal = true)
        val externalMap = buildTrackMapping(listOf(external), listOf(PlayerTrack("external", externalSourceIndex = 4, external = true)),
            TrackMappingContext(singleOutput = true))
        check(externalMap.embyFor("external") == 4)
        val languages = listOf(stream(1), stream(2, "chi"))
        val reversed = listOf(tracks[1].copy(language = "zh-Hant"), tracks[0])
        val byLanguage = buildTrackMapping(languages, reversed, original)
        check(byLanguage.playerFor(2) == "g2:0" && byLanguage.embyFor("g1:0") == 1)
        check(byLanguage.entries.all { it.basis == TrackMatchBasis.UNIQUE_METADATA })
        val reduced = buildTrackMapping(streams, tracks.take(1), original)
        check(reduced.playerFor(1) == null && reduced.playerFor(2) == null)
        val transcoded = buildTrackMapping(streams, listOf(tracks[0].copy(ffIndex = 1)),
            TrackMappingContext(negotiatedIndex = 2, singleOutput = true))
        check(transcoded.playerFor(1) == null && transcoded.embyFor("g1:0") == 2)
        check(transcoded.entries.single { it.playerKey != null }.basis == TrackMatchBasis.NEGOTIATED_OUTPUT)
        check(buildTrackMapping(streams, tracks, TrackMappingContext(negotiatedIndex = 2, singleOutput = true))
            .entries.all { it.confidence == TrackConfidence.UNCERTAIN })
        val mpv = buildTrackMapping(streams, listOf(tracks[1].copy(ffIndex = 2), tracks[0].copy(ffIndex = 1)),
            TrackMappingContext(originalFile = true))
        check(mpv.playerFor(2) == "g2:0" && mpv.embyFor("g2:0") == 2)
        check(mpv.entries.all { it.basis == TrackMatchBasis.FFMPEG_INDEX })
        val duplicate = buildTrackMapping(streams, tracks.map { it.copy(ffIndex = 1) }, original)
        check(duplicate.entries.all { it.confidence == TrackConfidence.UNCERTAIN })
        val source = MediaSource("v1", "version", "mkv", true, true, streams)
        check(trackMemoryIdentity(source, "Audio") != trackMemoryIdentity(source.copy(id = "v2"), "Audio"))
        check(trackMemoryIdentity(source, "Audio") != trackMemoryIdentity(source.copy(streams = streams.take(1)), "Audio"))
        println("P4 B04 PASS: DTS bijection, ambiguity, languages, reduced output, mpv conflicts, version identity")
    }
}
