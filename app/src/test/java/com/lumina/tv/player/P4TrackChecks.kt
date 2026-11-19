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
        check(ordered.playerFor(1) == "g1:0" && ordered.playerFor(2) == "g2:0") {
            "Expected verified Matroska track order, actual=${ordered.summary()}"
        }
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
        codecAliasesAndCues()
        audioProfiles()
        println("P4 B04 PASS: DTS bijection, codec aliases/cues, ambiguity, languages, reduced output, mpv conflicts, paired audio profiles")
    }

    private fun codecAliasesAndCues() {
        check(media3TrackCodec("application/x-media3-cues", "application/x-subrip") == "application/x-subrip")
        check(media3TrackCodec("application/x-media3-cues", "text/x-ssa") == "text/x-ssa")
        check(media3TrackCodec("application/x-media3-cues", "application/pgs") == "application/pgs")
        check(media3TrackCodec("application/x-media3-cues", null) == "application/x-media3-cues")
        check(codecFamily("SubRip") == codecFamily("application/x-subrip"))
        check(codecFamily("ass") == codecFamily("text/x-ssa"))
        check(codecFamily("PGS") == codecFamily("application/pgs"))
        check(codecFamily("truehd") == codecFamily("audio/true-hd"))
        check(codecFamily("DTS") == codecFamily("audio/vnd.dts.hd"))
        check(codecFamily("DTS") == codecFamily("audio/vnd.dts"))
        check(codecFamily("S_TEXT/UTF8") == codecFamily("application/x-subrip"))
        check(codecFamily("unknown-audio-format") != codecFamily("aac"))

        // The local JVM harness uses Android stubs, so Format.Builder requires a device/Robolectric.
        // Exercise the exact adapter inputs here; verify a real Format on an Android TV device.
        val emby = listOf(
            MediaStream(1, "Subtitle", "subrip", "eng", "Main", true, false, null),
            MediaStream(2, "Subtitle", "ass", "jpn", "Signs", false, false, null),
            MediaStream(3, "Subtitle", "pgs", "chi", "SDH", false, false, null),
        )
        val media3 = listOf(
            PlayerTrack("subrip", language = "eng", codec = media3TrackCodec("application/x-media3-cues", "application/x-subrip"), title = "Main"),
            PlayerTrack("ssa", language = "jpn", codec = "text/x-ssa", title = "Signs"),
            PlayerTrack("pgs", language = "zh", codec = "application/pgs", title = "SDH"),
        )
        val aliased = buildTrackMapping(emby, media3, TrackMappingContext())
        check(aliased.playerFor(1) == "subrip" && aliased.playerFor(2) == "ssa" && aliased.playerFor(3) == "pgs")
        val ambiguousSubs = listOf(
            emby[0].copy(index = 1, title = null),
            emby[0].copy(index = 2, isDefault = false, title = null),
        )
        val ambiguousTracks = listOf(
            media3[0].copy(key = "a", title = null),
            media3[0].copy(key = "b", title = null),
        )
        check(buildTrackMapping(ambiguousSubs, ambiguousTracks, TrackMappingContext())
            .entries.all { it.confidence == TrackConfidence.UNCERTAIN })
    }

    private fun audioProfiles() {
        val stereo = AudioCapabilities(
            mapOf(14 to listOf(AudioProfileInfo(2, setOf(12), setOf(192_000))))
        )
        val surround = AudioCapabilities(
            mapOf(14 to listOf(AudioProfileInfo(6, setOf(252), setOf(48_000))))
        )
        val paired = AudioCapabilities(mapOf(14 to listOf(
            AudioProfileInfo(2, setOf(12), setOf(192_000)),
            AudioProfileInfo(6, setOf(252), setOf(48_000)),
        )))
        check(AudioCodecMatrix.canPassthrough(14, 2, 192_000, stereo))
        check(!AudioCodecMatrix.canPassthrough(14, 6, 192_000, stereo))
        check(AudioCodecMatrix.canPassthrough(14, 6, 48_000, surround))
        check(AudioCodecMatrix.canPassthrough(14, 2, 192_000, paired))
        check(AudioCodecMatrix.canPassthrough(14, 6, 48_000, paired))
        check(!AudioCodecMatrix.canPassthrough(14, 6, 192_000, paired))
    }
}
