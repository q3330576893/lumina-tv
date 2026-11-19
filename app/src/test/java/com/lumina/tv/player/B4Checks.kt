package com.lumina.tv.player

import androidx.media3.common.Player

object B4Checks {
    @JvmStatic fun main(args: Array<String>) {
        watchdog()
        audioProfilesAndTracks()
        errorRoutes()
        audioFocus()
    }

    private fun watchdog() {
        fun PlaybackWatchdog.tick(second: Long, bufferedMs: Long? = 1_000L,
            loading: Boolean = true, downloading: Boolean = false, playing: Boolean = true, seeking: Boolean = false,
            generation: Int = 1, physicalId: Long? = 1L) = onTick(
            positionMs = 1_000L, bufferedMs = bufferedMs, state = Player.STATE_BUFFERING,
            playWhenReady = playing, isSeeking = seeking, sessionGeneration = generation, durationMs = 120_000L,
            bytesLoaded = 0L, isLoading = loading, mpvLoadId = physicalId, downloadInProgress = downloading,
            nowMs = second * 1_000L,
        )

        val slow = PlaybackWatchdog()
        for (s in 0L..20L) check(slow.tick(s, downloading = true) == null)
        // Dropping from positive speed to zero starts the no-load grace period.
        for (s in 21L..28L) check(slow.tick(s, loading = false) == null)
        check(slow.tick(29, loading = false) == WatchdogReason.BUFFERING_NO_LOAD)
        check(slow.tick(30, loading = false) == null)

        val progress = PlaybackWatchdog()
        for (s in 0L..20L) check(progress.tick(s, bufferedMs = 1_000 + 250 * s) == null)
        val unknown = PlaybackWatchdog()
        for (s in 0L..20L) check(unknown.tick(s, bufferedMs = null, loading = false) == null)
        check(unknown.tick(21, physicalId = 2L) == null)
        check(unknown.tick(29, physicalId = 2L) == WatchdogReason.BUFFERING_NO_PROGRESS)
        check(unknown.tick(30, generation = 2) == null)
        val unavailableBuffer = PlaybackWatchdog()
        for (s in 0L..20L) check(unavailableBuffer.tick(s, bufferedMs = null) == null)
        check(unavailableBuffer.tick(21, bufferedMs = null, physicalId = 2L) == null)
        check(unavailableBuffer.tick(29, bufferedMs = null, physicalId = 2L) == null)

        val suppressed = PlaybackWatchdog()
        check(suppressed.tick(0) == null)
        check(suppressed.tick(8, playing = false) == null)
        check(suppressed.tick(12, seeking = true) == null)
        check(suppressed.tick(13) == null)
        check(suppressed.tick(16) == WatchdogReason.BUFFERING_NO_PROGRESS)
        val media3 = PlaybackWatchdog()
        check(media3.tick(0, physicalId = null) == null)
        check(media3.tick(8, physicalId = null) == WatchdogReason.BUFFERING_NO_PROGRESS)
        println("B4 PASS Q-013: cache edge, slow download, disconnect, missing sample, pause, seek and load isolation")
    }

    private fun audioProfilesAndTracks() {
        P4TrackChecks.main(emptyArray())
        val streams = listOf(
            com.lumina.tv.data.MediaStream(1, "Audio", "aac", "eng", "Default", true, false, null, channels = 2),
            com.lumina.tv.data.MediaStream(2, "Audio", "ac3", "jpn", "Surround", false, false, null, channels = 6),
        )
        check(selectedAudioForRouteChange(streams, "media3", 2, null) == streams[1])
        check(selectedAudioForRouteChange(streams, "mpv", null, 2) == streams[1])
        check(selectedAudioForRouteChange(streams, "media3", null, null) == null)
        check(!audioRouteNeedsRecovery(selectionKnown = false, support = AudioSupport.UNSUPPORTED))
        check(!audioRouteNeedsRecovery(selectionKnown = true, support = AudioSupport.PASSTHROUGH))
        check(!audioRouteNeedsRecovery(selectionKnown = true, support = AudioSupport.DECODE_ONLY))
        check(audioRouteNeedsRecovery(selectionKnown = true, support = AudioSupport.UNSUPPORTED))
        println("B4 PASS Q-016/Q-017/Q-018: paired profiles, aliases/cues, ambiguity rejection and selected non-default route policy")
    }

    private fun errorRoutes() {
        val cases = listOf(
            PlaybackErrorCategory.CERT_INVALID to PlaybackErrorRoute.CERTIFICATE,
            PlaybackErrorCategory.AUTH_FAILED to PlaybackErrorRoute.AUTHENTICATION,
            PlaybackErrorCategory.SOURCE_GONE to PlaybackErrorRoute.SOURCE_GONE,
            PlaybackErrorCategory.AUDIO_OUTPUT to PlaybackErrorRoute.AUDIO_OUTPUT,
            PlaybackErrorCategory.NETWORK_TRANSIENT to PlaybackErrorRoute.NETWORK_RETRY,
            PlaybackErrorCategory.RESOURCE_RECLAIMED to PlaybackErrorRoute.RESOURCE_RETRY,
            PlaybackErrorCategory.HTTP_PERMISSION to PlaybackErrorRoute.PERMISSION_DENIED,
            PlaybackErrorCategory.OTHER to PlaybackErrorRoute.MEDIA_RECOVERY,
        )
        cases.forEach { (category, route) -> check(playbackErrorRoute(category) == route) }
        for (status in listOf(400, 401, 403, 404, 410, 500)) {
            val sample = playbackErrorSample(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpResponseErrorSample(status),
            )
            val classification = classifyPlaybackError(sample)
            check(playbackErrorRoute(classification.category) == when (status) {
                400 -> PlaybackErrorRoute.MEDIA_RECOVERY
                401, 403 -> PlaybackErrorRoute.AUTHENTICATION
                404, 410 -> PlaybackErrorRoute.SOURCE_GONE
                else -> PlaybackErrorRoute.NETWORK_RETRY
            })
        }
        check(!shouldRetryNetworkPlayback(PlaybackErrorCategory.NETWORK_TRANSIENT, 2))
        check(shouldRetryNetworkPlayback(PlaybackErrorCategory.NETWORK_TRANSIENT, 1))
        check(!shouldRetryNetworkPlayback(PlaybackErrorCategory.HTTP_PERMISSION, 0))
        for (code in listOf(
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )) {
            check(playbackErrorRoute(classifyPlaybackError(playbackErrorSample(code)).category) == PlaybackErrorRoute.AUDIO_OUTPUT)
        }
        println("B4 PASS Q-015: direct/nested 401/403/404/410/500, certificate, audio output, permission and bounded retry routes")
    }

    private fun audioFocus() {
        val playing = reduceMpvFocus(MpvFocusPolicyState(), MpvFocusSignal.TRANSIENT_LOSS, true, true)
        check(playing.effect == MpvFocusEffect.PAUSE && playing.state.resumeOnGain)
        check(reduceMpvFocus(playing.state, MpvFocusSignal.GAIN, true, false).effect == MpvFocusEffect.RESUME)
        check(reduceMpvFocus(playing.state, MpvFocusSignal.GAIN, false, false).effect == MpvFocusEffect.NONE)
        check(reduceMpvFocus(MpvFocusPolicyState(resumeOnGain = true), MpvFocusSignal.LOSS, true, true)
            .let { it.effect == MpvFocusEffect.PAUSE && !it.state.resumeOnGain })
        val ducked = reduceMpvFocus(MpvFocusPolicyState(), MpvFocusSignal.DUCK, true, true)
        check(ducked.effect == MpvFocusEffect.DUCK && ducked.state.ducked)
        check(reduceMpvFocus(ducked.state, MpvFocusSignal.GAIN, true, true).effect == MpvFocusEffect.UNDUCK)
        check(reduceMpvFocus(ducked.state, MpvFocusSignal.GAIN, false, false).effect == MpvFocusEffect.UNDUCK)
        println("B4 PASS Q-019: transient resume, permanent loss, duck/unduck and manual-pause gain guard")
    }
}
