package com.lumina.tv.player

import com.lumina.tv.data.playbackCacheKey

/** B12 guards the extracted pure policies while the old playback entry points remain adapters. */
object B12Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        codecFamilies()
        errorActions()
        watchdogInputs()
        cacheKeyBoundary()
        println("B12 PASS: extracted playback policies preserve codec, recovery, watchdog and cache-key decisions")
    }

    private fun codecFamilies() {
        check(codecFamily("truehd") == codecFamily("audio/true-hd"))
        check(codecFamily("application/x-subrip") == codecFamily("SubRip"))
        check(codecFamily("text/x-ssa") == codecFamily("ass"))
        check(codecFamily("application/pgs") == codecFamily("PGS"))
        check(codecFamily("unknown-format") == "unknown-format")
        check(codecFamily(null) == null)
    }

    private fun errorActions() {
        val actions = mapOf(
            PlaybackErrorCategory.CERT_INVALID to "check_certificate",
            PlaybackErrorCategory.AUTH_FAILED to "reauthenticate",
            PlaybackErrorCategory.SOURCE_GONE to "change_source",
            PlaybackErrorCategory.AUDIO_OUTPUT to "switch_mpv_or_reduce_pcm",
            PlaybackErrorCategory.NETWORK_TRANSIENT to "retry",
            PlaybackErrorCategory.RESOURCE_RECLAIMED to "retry",
            PlaybackErrorCategory.HTTP_PERMISSION to "check_permissions",
            PlaybackErrorCategory.HTTP_CONTENT_INVALID to "retry_or_change_source",
            PlaybackErrorCategory.PARSING to "change_source_or_transcode",
            PlaybackErrorCategory.DECODING to "switch_decoder",
            PlaybackErrorCategory.OTHER to "inspect_diagnostics",
        )
        actions.forEach { (category, action) ->
            check(playbackErrorAction(category) == action)
            check(playbackErrorRoute(category) != PlaybackErrorRoute.MEDIA_RECOVERY || category in setOf(
                PlaybackErrorCategory.HTTP_CONTENT_INVALID,
                PlaybackErrorCategory.PARSING,
                PlaybackErrorCategory.DECODING,
                PlaybackErrorCategory.OTHER,
            ))
        }
        check(shouldRetryNetworkPlayback(PlaybackErrorCategory.NETWORK_TRANSIENT, 1))
        check(!shouldRetryNetworkPlayback(PlaybackErrorCategory.NETWORK_TRANSIENT, 2))
        check(!shouldRetryNetworkPlayback(PlaybackErrorCategory.AUTH_FAILED, 0))
    }

    private fun watchdogInputs() {
        fun input(
            download: Boolean = false,
            bufferedAge: Long? = 5_000L,
            buffering: Boolean = true,
            loading: Boolean = true,
            mpv: Boolean = true,
        ) = PlaybackWatchdogInput(
            positionMs = 12_000L,
            durationMs = 120_000L,
            positionAgeMs = 10_000L,
            bufferedAgeMs = bufferedAge,
            stateAgeMs = 5_000L,
            bytesUnchangedForMs = 5_000L,
            loadingAgeMs = 10_000L,
            buffering = buffering,
            ready = !buffering,
            loading = loading,
            downloadInProgress = download,
            mpvLoad = mpv,
            playWhenReady = true,
            seeking = false,
            startupComplete = true,
            suppressed = false,
        )

        check(playbackWatchdogReason(input(download = true)) == null)
        check(playbackWatchdogReason(input(bufferedAge = null)) == null)
        check(playbackWatchdogReason(input()) == WatchdogReason.BUFFERING_NO_PROGRESS)
        check(playbackWatchdogReason(input(loading = false)) == WatchdogReason.BUFFERING_NO_LOAD)
        check(playbackWatchdogReason(input().copy(seeking = true)) == null)
        check(playbackWatchdogReason(input().copy(startupComplete = false)) == null)
        check(playbackWatchdogReason(input().copy(suppressed = true)) == null)
        check(playbackWatchdogReason(input(buffering = false, loading = false, mpv = false)
            .copy(positionAgeMs = 4_000L)) == WatchdogReason.PLAYING_NO_PROGRESS)
    }

    private fun cacheKeyBoundary() {
        fun key(validator: String, mode: String = "DirectPlay", route: String = "https://server/media") =
            playbackCacheKey("server", "user", "item", "source", validator, 100L, mode,
                route, 1, "AUTO", null, 0L)

        val stable = key("etag-v1")
        check(stable != null)
        check(key("etag-v2") != stable)
        check(key("etag-v1", mode = "DirectStream") != stable)
        check(key("etag-v1", route = "https://server/media?route=B") != stable)
        check(playbackCacheKey("server", "user", "item", "source", null, 100L, "DirectPlay",
            "https://server/media", 1, "AUTO", null, 0L) == null)
    }
}
