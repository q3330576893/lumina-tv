package com.lumina.tv

import com.lumina.tv.data.TimelinePosition
import com.lumina.tv.data.MediaItem
import com.lumina.tv.diag.PageLog
import com.lumina.tv.player.*
import com.lumina.tv.ui.screens.rememberHomeHeroItems
import com.lumina.tv.ui.screens.pickHeroItems
import com.lumina.tv.ui.screens.playbackContinuationPaused
import com.lumina.tv.ui.screens.media3DesiredPlayingAfterChange
import com.lumina.tv.ui.screens.media3AudioOnlyReady
import com.lumina.tv.ui.screens.isDefinitelyNonMediaResponse
import com.lumina.tv.ui.screens.media3AudioOffloadPreferences
import com.lumina.tv.ui.screens.m3oBufferPlan
import androidx.media3.common.Player
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import androidx.media3.ui.AspectRatioFrameLayout
import org.json.JSONObject

/** E4 uses the same recovery and control decisions as the app, without a device or credentials. */
object E4Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        containerFallbacks()
        mpvDiagnostics()
        stopSamples()
        heroRecomposition()
        decoderLifetimes()
        playbackSpeeds()
        creditsCountdowns()
        continuationPauseIntent()
        audioOnlyReady()
        directMimeAndOffload()
        bufferBounds()
        pathFailureScope()
        watchdogEndWait()
        audioModeEngineOrder()
        mpvAspectModes()
        println("E4 PASS: container fallback priority/exclusions, mpv tri-state, Stop sample gate, real Compose hero recomposition, decoder lifetimes, boost/selection/reload, countdown cancellation/resume, soft-mode engine exclusions and mpv aspect properties")
    }

    private fun containerFallbacks() {
        val path = PlaybackPath("blurayiso", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        check(containerMpvFallbackAllowed("auto", null, path, PlaybackPathFailures(), mpvAvailable = true))
        check(!containerMpvFallbackAllowed("media3", null, path, PlaybackPathFailures(), mpvAvailable = true))
        val excludedMpv = PlaybackPathFailures().apply {
            record(PlaybackPathFailure(path.copy(engine = "mpv"), PlaybackFailureReason.PARSING, 1, "mpv-failed"))
        }
        check(!containerMpvFallbackAllowed("auto", null, path, excludedMpv, mpvAvailable = true))
        for (mpvExcluded in listOf(false, true)) for (serverSupportsTranscoding in listOf(false, true)) {
            val failures = PlaybackPathFailures()
            failures.record(PlaybackPathFailure(path, PlaybackFailureReason.PARSING, 1, "container-unsupported"))
            if (mpvExcluded) failures.record(PlaybackPathFailure(path.copy(engine = "mpv"),
                PlaybackFailureReason.PARSING, 2, "mpv-failed"))
            val source = JSONObject().put("Id", "blurayiso")
                .put("SupportsTranscoding", serverSupportsTranscoding).put("TranscodingUrl", "/transcode.m3u8")
            val info = JSONObject().put("MediaSources", org.json.JSONArray().put(source)).toString()
            val canTranscode = serverTranscodingAvailable(playbackSourceJson(info, "blurayiso"))
            check(canTranscode == serverSupportsTranscoding)
            val canMpv = selectPlaybackEngine("media3", "mpv", path, failures, mpvAvailable = true) == "mpv"
            check(canMpv == !mpvExcluded)
            val result = containerRecovery(canMpv, canTranscode, transcodeTried = false)
            val expected = when {
                !mpvExcluded -> ContainerRecovery.MPV
                serverSupportsTranscoding -> ContainerRecovery.TRANSCODE
                else -> ContainerRecovery.TERMINAL
            }
            check(result == expected) { "mpvExcluded=$mpvExcluded transcode=$canTranscode: $result" }
            check(failures.excludes(path)) // Changing engine never clears the failed media3 DIRECT_PLAY path.
            check(containerRecovery(canMpv, canTranscode, transcodeTried = true) ==
                if (canMpv) ContainerRecovery.MPV else ContainerRecovery.TERMINAL)
        }
        check(!serverTranscodingAvailable(null))
        check(!serverTranscodingAvailable(JSONObject().put("SupportsTranscoding", true)))
        check(!serverTranscodingAvailable(JSONObject().put("SupportsTranscoding", true).put("TranscodingUrl", "")))
        check(!serverTranscodingAvailable(JSONObject().put("SupportsTranscoding", false).put("TranscodingUrl", "/exists")))
        check(playbackSourceJson("invalid", "source") == null)
        check(playbackSourceJson("{\"MediaSources\":[]}", "source") == null)
    }

    private fun mpvDiagnostics() {
        check(mpvAvailabilityDiagnostic(false, null) == "not_probed")
        check(mpvAvailabilityDiagnostic(true, null) == "available")
        check(mpvAvailabilityDiagnostic(true, "missing-library") == "unavailable:missing-library")
    }

    private fun stopSamples() {
        val reports = mutableListOf<TimelinePosition>()
        var skips = 0
        fun stop(observed: Long?, last: Long?) = withStopPositionSample(observed, last,
            onMissing = { skips++ }) { reports += TimelinePosition(it, null) }
        stop(null, null)
        check(reports.isEmpty() && skips == 1) // Neither reportStop nor its success diagnostics run.
        stop(4_123L, null)
        stop(null, 6_789L)
        stop(8_001L, 6_789L)
        stop(0L, null) // An observed zero is a real sample, unlike a requested/missing position.
        check(reports.map { it.reportPositionMs } == listOf(4_123L, 6_789L, 8_001L, 0L))
        check(reports.all { it.timelineUnverified && it.playerPositionMs != null } && skips == 1)
    }

    private suspend fun heroRecomposition() = coroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val composition = Composition(applier, recomposer)
        val items = (1..12).map { MediaItem("e4-hero-$it", "Hero $it", "Movie") }
        var unrelated by mutableStateOf(0)
        var latest by mutableStateOf(items.take(4))
        var unavailable by mutableStateOf(emptySet<String>())
        var computations = 0
        var compositions = 0
        var result = emptyList<MediaItem>()
        try {
            composition.setContent {
                unrelated // Simulate focus/scroll/control state changing independently of the inputs.
                compositions++
                result = rememberHomeHeroItems(latest, items, emptyList(), items.take(2), unavailable) { pool, ids, size ->
                    computations++
                    check(pool.map { it.id }.distinct().size == pool.size && size == 6)
                    check(pool.none { it.id in unavailable } && ids == latest.map { it.id }.toSet())
                    pickHeroItems(pool, ids, size)
                }
            }
            val original = result
            val originalLogs = PageLog.tail()
            repeat(12) {
                unrelated++
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                check(result === original && computations == 1)
            }
            check(compositions >= 13 && PageLog.tail() == originalLogs)
            // Recent membership can change while the deduplicated pool stays equal.
            latest = items.takeLast(4)
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()
            check(computations == 2)
            unavailable = setOf(result.first().id)
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()
            check(computations == 3 && result.none { it.id in unavailable })
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }

    private fun decoderLifetimes() {
        val logs = mutableListOf<String>()
        val lifecycle = PlayerLifecycleRegistry(logs::add)
        val preview = Any()
        val playback = Any()
        val mpv = Any()
        check(lifecycle.created(preview, "media3", "detail-preview") == 1L)
        check(lifecycle.created(preview, "media3", "detail-preview") == 1L)
        lifecycle.decoderInitialized(preview, "hevc")
        check(lifecycle.liveVideoDecoders == 1)
        check(lifecycle.created(playback, "media3", "playback") == 2L)
        lifecycle.decoderInitialized(playback, "hevc")
        check(lifecycle.liveVideoDecoders == 2 && logs.any { it.startsWith("decoder: overlap count=2") })
        lifecycle.decoderReleased(preview, "hevc")
        lifecycle.decoderReleased(preview, "hevc") // Late/duplicate release cannot underflow.
        lifecycle.released(preview, "navigation")
        check(lifecycle.liveVideoDecoders == 1)
        lifecycle.released(playback, "exit") // Teardown may suppress the final analytics callback.
        lifecycle.decoderReleased(playback, "hevc")
        check(lifecycle.liveVideoDecoders == 0)
        check(lifecycle.created(mpv, "mpv", "playback") == 3L)
        lifecycle.released(mpv, "exit")
        lifecycle.released(mpv, "duplicate")
        check(logs.count { it.startsWith("playerLifecycle event=create") } == 3)
        check(logs.count { it.startsWith("playerLifecycle event=release") } == 3)
        logs.clear()
        // The fixed navigation order retires the preview before preparing the main video.
        lifecycle.created(preview, "media3", "detail-preview")
        lifecycle.decoderInitialized(preview, "hevc")
        lifecycle.released(preview, "navigation")
        lifecycle.created(playback, "media3", "playback")
        lifecycle.decoderInitialized(playback, "hevc")
        lifecycle.released(playback, "exit")
        check(lifecycle.liveVideoDecoders == 0 && logs.none { it.startsWith("decoder: overlap") })
    }

    private fun playbackSpeeds() {
        for (selected in listOf(0.75f, 1f, 1.5f, 2f)) {
            val user = PlaybackSpeedState(selected)
            val boosted = user.boost(true)
            check(boosted.userSpeed == selected && boosted.effectiveSpeed == 3f)
            val released = boosted.boost(false)
            check(released.userSpeed == selected && released.effectiveSpeed == selected)
            val menuDuringBoost = boosted.select(1.25f)
            check(!menuDuringBoost.boostActive && menuDuringBoost.userSpeed == 1.25f && menuDuringBoost.effectiveSpeed == 1.25f)
            check(menuDuringBoost.boost(false).effectiveSpeed == 1.25f)
            val newLoad = boosted.forNewLoad()
            check(!newLoad.boostActive && newLoad.userSpeed == selected && newLoad.effectiveSpeed == selected)
            val persistedUserSpeed = menuDuringBoost.userSpeed
            check(PlaybackSpeedState(persistedUserSpeed).effectiveSpeed == 1.25f) // New episode/controller.
        }
    }

    private suspend fun creditsCountdowns() = coroutineScope {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val logs = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val countdown = CreditsCountdown<String>(this, logs::add) { ticks.receive() }
        var playing = true
        var lookups = 0
        fun start() = countdown.start(3, next = { lookups++; "next" }, canContinue = { playing }, onComplete = completed::add)
        suspend fun tick() { ticks.send(Unit); yield() }
        val window = creditsWindowStartMs(600_000L, 580_000L, 30)
        check(window == 580_000L && creditsWindowStartMs(600_000L, null, 30) == 570_000L)
        check(creditsWindowStartMs(600_000L, 700_000L, 30) == 570_000L)
        check(creditsWindowStartMs(600_000L, null, 0) == null)
        start() // Natural entry starts once.
        yield()
        check(countdown.remainingSeconds == 3 && countdown.active && lookups == 1)
        start()
        yield()
        check(lookups == 1)
        check(!shouldCancelCreditsForSeek(570_000L, 590_000L, window, chapter = false)) // Forward into credits.
        check(!shouldCancelCreditsForSeek(595_000L, 590_000L, window, chapter = false)) // Backward within credits.
        check(!shouldCancelCreditsForSeek(595_000L, 579_500L, window, chapter = false)) // Boundary jitter.
        tick()
        check(countdown.remainingSeconds == 2)
        playing = false
        countdown.cancel("pause")
        yield()
        check(countdown.remainingSeconds == 0 && countdown.nextItem == null && !countdown.active && completed.isEmpty())
        playing = true // Resume while still in the window starts a full fresh countdown.
        start()
        yield()
        check(countdown.remainingSeconds == 3 && lookups == 2)
        tick()
        check(countdown.remainingSeconds == 2)
        check(shouldCancelCreditsForSeek(590_000L, 540_000L, window, chapter = false))
        countdown.cancel("seek-before-credits")
        yield()
        check(countdown.remainingSeconds == 0 && !countdown.active && completed.isEmpty())
        start()
        yield()
        check(shouldCancelCreditsForSeek(590_000L, 579_500L, window, chapter = true))
        check(!shouldCancelCreditsForSeek(590_000L, 585_000L, window, chapter = true))
        countdown.cancel("chapter-before-credits")
        yield()
        check(completed.isEmpty())
        start()
        yield()
        countdown.cancel("remote-key", allowRestart = false)
        start()
        yield()
        check(countdown.remainingSeconds == 0 && !countdown.active)
        countdown.cancel("load")
        start()
        yield()
        tick(); tick(); tick()
        check(completed == listOf("next") && countdown.remainingSeconds == 0)
        check(logs.any { it.contains("reason=pause") } && logs.any { it.contains("reason=seek-before-credits") })

        // A cancelled lookup cannot later revive a countdown, even if the lookup ignores cancellation.
        val lookupStarted = CompletableDeferred<Unit>()
        val lookupResult = CompletableDeferred<String>()
        val slow = CreditsCountdown<String>(this, logs::add) { ticks.receive() }
        slow.start(3, next = {
            lookupStarted.complete(Unit)
            withContext(NonCancellable) { lookupResult.await() }
        }, canContinue = { true }, onComplete = { error("Cancelled lookup completed playback") })
        lookupStarted.await()
        slow.cancel("pause-during-lookup")
        lookupResult.complete("late-next")
        yield()
        check(!slow.active && slow.remainingSeconds == 0 && slow.nextItem == null)
        ticks.close()
    }

    private fun continuationPauseIntent() {
        check(!playbackContinuationPaused(true, enginePlaying = false, naturallyEnded = true))
        check(playbackContinuationPaused(false, enginePlaying = false, naturallyEnded = true))
        check(playbackContinuationPaused(true, enginePlaying = false, naturallyEnded = false))
        for (reason in listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG)) {
            check(!media3DesiredPlayingAfterChange(true, false, reason))
        }
        check(media3DesiredPlayingAfterChange(true, false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
        check(media3DesiredPlayingAfterChange(true, false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        check(!media3DesiredPlayingAfterChange(false, true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
    }

    private fun audioOnlyReady() {
        check(media3AudioOnlyReady(hasVideo = false, hasSelectedAudio = true))
        check(!media3AudioOnlyReady(hasVideo = true, hasSelectedAudio = true))
        check(!media3AudioOnlyReady(hasVideo = false, hasSelectedAudio = false)) // Empty/unknown tracks are not audio-only.
    }

    private fun directMimeAndOffload() {
        for (type in listOf("video/mp4", "audio/mpeg", "application/ogg", "application/vnd.apple.mpegurl",
            "application/x-mpegURL", "application/dash+xml", "application/octet-stream", "text/xml", "text/vtt",
            "text/unknown", "", "application/unknown")) {
            check(!isDefinitelyNonMediaResponse(type)) { type }
        }
        for (type in listOf("text/html; charset=utf-8", "image/jpeg", "application/json", "TEXT/PLAIN")) {
            check(isDefinitelyNonMediaResponse(type)) { type }
        }
        val enabled = androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        val disabled = androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        check(media3AudioOffloadPreferences("passthrough").audioOffloadMode == enabled)
        for (mode in listOf("auto", "soft")) check(media3AudioOffloadPreferences(mode).audioOffloadMode == disabled)
    }

    private fun bufferBounds() {
        for ((mem, backSec, ceilingMb) in listOf(Triple(2048, 10, 64), Triple(4096, 20, 160), Triple(8192, 30, 256))) {
            for (highBitrate in listOf(false, true)) for (fastStart in listOf(false, true)) {
                val plan = m3oBufferPlan(mem, highBitrate, fastStart)
                check(plan.backSec * 1000 == backSec * 1000 && plan.backSec * 1000 in 10_000..30_000)
                check(plan.targetBytes in 1..(ceilingMb * 1024 * 1024))
                check(plan.maxMs >= plan.minMs && plan.minMs >= plan.afterRebufferMs)
            }
        }
        check(m3oBufferPlan(0, false, true).targetBytes <= 160 * 1024 * 1024)
    }

    private fun pathFailureScope() {
        val path = PlaybackPath("source", "mpv", PlaybackDelivery.DIRECT_PLAY, "dts", "auto")
        for (reason in listOf(PlaybackFailureReason.HTTP, PlaybackFailureReason.LOAD)) {
            val failures = PlaybackPathFailures()
            failures.recordEngineFailure(PlaybackPathFailure(path, reason, 1, "one request failed"))
            check(failures.excludes(path))
            check(!failures.excludes(path.copy(delivery = PlaybackDelivery.TRANSCODE)))
            check(!failures.excludes(path.copy(delivery = PlaybackDelivery.DIRECT_STREAM)))
            check(!failures.excludes(path.copy(audioTrack = "aac")))
            check(!failures.excludes(path.copy(requestVariant = 1)))
            check(!failures.excludes(path.copy(sourceId = "other")))
        }
        val failures = PlaybackPathFailures()
        failures.recordEngineFailure(PlaybackPathFailure(path, PlaybackFailureReason.INITIALIZATION, 1, "native init failed"))
        check(failures.excludes(path.copy(delivery = PlaybackDelivery.TRANSCODE)))
        check(!failures.excludes(path.copy(engine = "media3")))
        failures.clearForManualRetry()
        check(!failures.excludes(path))
    }

    private fun watchdogEndWait() {
        val end = PlaybackWatchdogInput(
            positionMs = 115_000L, durationMs = 120_000L, positionAgeMs = 4_000L,
            bufferedAgeMs = 10_000L, stateAgeMs = 10_000L, bytesUnchangedForMs = 10_000L,
            loadingAgeMs = 10_000L, buffering = false, ready = true, loading = false,
            downloadInProgress = false, mpvLoad = false, playWhenReady = true,
            seeking = false, startupComplete = true, suppressed = false,
        )
        for (buffering in listOf(false, true)) for (loading in listOf(false, true)) {
            val input = end.copy(buffering = buffering, ready = !buffering, loading = loading)
            for (age in listOf(4_000L, 59_999L)) check(playbackWatchdogReason(input.copy(positionAgeMs = age)) == null)
            check(playbackWatchdogReason(input.copy(positionAgeMs = 60_000L)) == WatchdogReason.NO_END)
        }
        check(playbackWatchdogReason(end.copy(positionAgeMs = 60_000L, downloadInProgress = true)) == null)
        check(playbackWatchdogReason(end.copy(positionAgeMs = 60_000L, playWhenReady = false)) == null)
        check(playbackWatchdogReason(end.copy(positionAgeMs = 60_000L, seeking = true)) == null)
        check(playbackWatchdogReason(end.copy(positionMs = 110_000L)) == null) // Inclusive 10s window.
        check(playbackWatchdogReason(end.copy(positionMs = 109_999L)) == WatchdogReason.PLAYING_NO_PROGRESS)
        check(playbackWatchdogReason(end.copy(durationMs = 0L)) == WatchdogReason.PLAYING_NO_PROGRESS)
    }

    private fun audioModeEngineOrder() {
        val path = PlaybackPath("source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "soft")
        val failures = PlaybackPathFailures()
        fun select(preference: String? = "media3", pinned: String? = null, available: Boolean = true) =
            selectPlaybackEngine(preference, pinned, path, failures, mpvAvailable = available)
        check(select() == "media3")
        check(select(pinned = "media3") == "media3")
        check(select(pinned = "mpv", available = false) == "media3")
        check(select(pinned = "mpv") == "mpv")
        failures.record(PlaybackPathFailure(path.copy(engine = "mpv"), PlaybackFailureReason.INITIALIZATION, 1, "excluded"))
        check(select() == "media3" && select(preference = "mpv") == "media3")
        failures.record(PlaybackPathFailure(path, PlaybackFailureReason.DECODING, 2, "excluded"))
        check(select() == null)
        check(selectPlaybackEngine("mpv", null, path.copy(sourceId = "other"), failures, mpvAvailable = true) == "mpv")
        check(selectPlaybackEngine("media3", null, path.copy(sourceId = "other"), failures, mpvAvailable = true) == "media3")
    }

    private fun mpvAspectModes() {
        val expected = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to MpvAspectProperties(true, 0.0, 0.0),
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to MpvAspectProperties(true, 0.0, 1.0),
            AspectRatioFrameLayout.RESIZE_MODE_FILL to MpvAspectProperties(false, 0.0, 0.0),
        )
        for ((mode, properties) in expected) {
            check(mpvAspectProperties(mode) == properties)
            val applied = linkedMapOf<String, Any>()
            repeat(2) { // Every physical load can reapply the complete state.
                applied.clear()
                applyMpvAspectMode(mode, { key, value -> applied[key] = value }, { key, value -> applied[key] = value })
                check(applied == mapOf("keepaspect" to properties.keepAspect, "video-zoom" to properties.videoZoom, "panscan" to properties.panscan))
            }
            val attempted = mutableListOf<String>()
            applyMpvAspectMode(mode, { key, _ -> attempted += key; error("unsupported") }, { key, _ -> attempted += key; error("unsupported") })
            check(attempted == listOf("keepaspect", "video-zoom", "panscan")) // Each failure is isolated.
        }
    }
}
