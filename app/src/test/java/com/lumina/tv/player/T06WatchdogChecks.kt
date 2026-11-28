package com.lumina.tv.player

import com.lumina.tv.data.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/** B: real sampler/stateful watchdog and actual Progress/Stop HTTP content coordinates. */
internal object T06WatchdogChecks {
    @JvmStatic fun main(args: Array<String>) { run() }

    fun run() = runBlocking {
        nativeEndThresholds()
        unknownAndOldLoads()
        reportCoordinates()
        productionWiring()
        println("V6-T06-B PASS: native offset=0/>0, exact 60s EOF/4s stall; unknown position/duration and stale/mixed physical samples; real Progress/Stop content coordinates across generations")
    }

    private fun PlaybackWatchdog.tick(
        now: Long, sample: WatchdogTimelineSample?, expected: WatchdogLoadIdentity = checkNotNull(sample).load,
        playing: Boolean = true, seeking: Boolean = false,
    ) = onTick(sample, expected, bufferedMs = null, state = 3, playWhenReady = playing,
        isSeeking = seeking, bytesLoaded = 0, isLoading = false, nowMs = now)

    private fun calibrated(offset: Long): TimelineCalibrationWindow = TimelineCalibrationWindow(offset, 1_200_000L).apply {
        firstFrame(0)
        check(sample(0, 1_200_000L - offset, 0))
        check(calibration.offsetMs == offset)
    }

    private fun nativeEndThresholds() {
        for (offset in listOf(0L, 600_000L)) {
            val timeline = calibrated(offset)
            val nativeDuration = 1_200_000L - offset
            val nativePosition = nativeDuration - 5_000L
            val content = timeline.position(nativePosition)
            check(content.contentPositionMs == 1_195_000L && content.reportPositionMs == 1_195_000L)
            val identity = WatchdogLoadIdentity(1, 100)
            val sample = checkNotNull(sampleWatchdogTimeline(1, { identity }, { nativePosition }, { nativeDuration }))
            val watchdog = PlaybackWatchdog()
            check(watchdog.tick(0, sample) == null)
            check(watchdog.tick(8_000, sample) == null)
            check(watchdog.tick(59_999, sample) == null)
            check(watchdog.tick(60_000, sample) == WatchdogReason.NO_END)
            // A 4-second stall away from EOF still uses the existing 8-second loading grace.
            val stall = PlaybackWatchdog()
            val away = sample.copy(positionMs = 10_000L)
            check(stall.tick(0, away) == null && stall.tick(7_999, away) == null)
            check(stall.tick(8_000, away) == WatchdogReason.PLAYING_NO_PROGRESS)
            val paused = PlaybackWatchdog()
            check(paused.tick(0, sample) == null)
            check(paused.tick(60_000, sample, playing = false) == null)
            check(paused.tick(120_000, sample, seeking = true) == null)
        }
    }

    private fun unknownAndOldLoads() {
        val old = WatchdogLoadIdentity(1, 100)
        val current = WatchdogLoadIdentity(2, 200)
        var reads = 0
        check(sampleWatchdogTimeline(2, { old }, { reads++; 0 }, { reads++; 120_000 }) == null)
        check(reads == 0) { "Retired generation was sampled" }
        var identity = old
        check(sampleWatchdogTimeline(1, { identity }, { identity = old.copy(physicalId = 101); 115_000 }, { 120_000 }) == null) {
            "Mixed physical sample was accepted"
        }
        check(sampleWatchdogTimeline(2, { null }, { error("No load") }, { error("No load") }) == null)
        for (position in listOf(null, -1L)) {
            val sample = checkNotNull(sampleWatchdogTimeline(2, { current }, { position }, { 120_000 }))
            check(sample.positionMs == null)
            val watchdog = PlaybackWatchdog()
            check(watchdog.tick(0, sample) == null && watchdog.tick(60_000, sample) == null)
            // A newly available position starts its own age, including a real observed zero.
            val zero = sample.copy(positionMs = 0)
            check(watchdog.tick(60_001, zero) == null && watchdog.tick(64_000, zero) == null)
            check(watchdog.tick(64_001, zero) == WatchdogReason.PLAYING_NO_PROGRESS)
        }
        for (duration in listOf(null, 0L, -1L, androidx.media3.common.C.TIME_UNSET)) {
            val sample = checkNotNull(sampleWatchdogTimeline(2, { current }, { 115_000 }, { duration }))
            check(sample.durationMs == null)
            val watchdog = PlaybackWatchdog()
            check(watchdog.tick(0, sample) == null)
            check(watchdog.tick(60_000, sample) == WatchdogReason.PLAYING_NO_PROGRESS) { "Unknown duration became NO_END" }
        }
        // No duration can leak across native software retries within the same generation.
        val watchdog = PlaybackWatchdog()
        val nearEnd = WatchdogTimelineSample(old, 115_000, 120_000)
        check(watchdog.tick(0, nearEnd) == null)
        val replacement = nearEnd.copy(load = old.copy(physicalId = 101), durationMs = null)
        check(watchdog.tick(60_000, replacement) == null)
        check(watchdog.tick(68_000, replacement) == WatchdogReason.PLAYING_NO_PROGRESS)
        val stale = PlaybackWatchdog()
        check(stale.tick(0, nearEnd) == null)
        check(stale.tick(10_000, nearEnd, expected = current) == null)
        check(stale.tick(10_001, null, expected = current) == null)
        val newSample = WatchdogTimelineSample(current, 115_000, 120_000)
        check(stale.tick(10_002, newSample) == null)
        check(stale.tick(70_001, newSample) == null)
        check(stale.tick(70_002, newSample) == WatchdogReason.NO_END)
        // A sample from an earlier physical load must not rebase/suppress a current run.
        val reverse = PlaybackWatchdog()
        check(reverse.tick(0, newSample) == null)
        check(reverse.tick(59_999, nearEnd, expected = current) == null)
        check(reverse.tick(60_000, newSample) == WatchdogReason.NO_END)
        val losesPosition = PlaybackWatchdog()
        check(losesPosition.tick(0, newSample) == null)
        check(losesPosition.tick(59_999, newSample.copy(positionMs = null)) == null)
        check(losesPosition.tick(60_000, newSample) == null)
        check(losesPosition.tick(119_999, newSample) == null)
        check(losesPosition.tick(120_000, newSample) == WatchdogReason.NO_END)
        // Missing current duration must not reuse the previous tick's known duration either.
        val losesDuration = PlaybackWatchdog()
        check(losesDuration.tick(0, nearEnd) == null)
        check(losesDuration.tick(60_000, nearEnd.copy(durationMs = null)) == WatchdogReason.PLAYING_NO_PROGRESS)
        val gap = PlaybackWatchdog()
        check(gap.tick(0, nearEnd) == null)
        check(gap.tick(59_999, null, expected = old) == null)
        check(gap.tick(60_000, nearEnd) == null)
        check(gap.tick(119_999, nearEnd) == null)
        check(gap.tick(120_000, nearEnd) == WatchdogReason.NO_END)
    }

    private suspend fun reportCoordinates() = withTimeout(10_000L) {
        val requests = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                requests.trySend(session.uri to JSONObject(checkNotNull(files["postData"])))
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "t06")
        try {
            for (offset in listOf(0L, 600_000L)) {
                val calibration = calibrated(offset)
                val coordinator = PlaybackLoadCoordinator()
                repeat(2) { load ->
                    val generation = coordinator.beginLoad("source")
                    val contentPosition = 700_000L + load * 1_000L
                    val sample = calibration.position(contentPosition - offset)
                    val session = client.reportStart("episode", sample.reportPositionMs, "source", "DirectPlay", "emby-session")
                    check(requests.receive().first == "/Sessions/Playing")
                    check(client.reportProgress(session, sample.reportPositionMs, false, sample.timelineUnverified, sample.playerPositionMs))
                    val progress = requests.receive()
                    check(progress.first == "/Sessions/Playing/Progress")
                    coordinator.beginLoad("new-source")
                    check(!coordinator.accepts(generation))
                    check(client.reportStop(session, sample.reportPositionMs, playerPositionMs = sample.playerPositionMs))
                    val stop = requests.receive()
                    check(stop.first == "/Sessions/Playing/Stopped")
                    for (body in listOf(progress.second, stop.second)) {
                        check(body.getLong("PositionTicks") == contentPosition * 10_000L)
                        check(body.getString("ItemId") == "episode" && body.getString("PlaySessionId") == "emby-session")
                        check(body.keys().asSequence().none { it.contains("generation", true) || it.contains("physical", true) })
                    }
                }
            }
        } finally { client.close(); server.stop(); requests.close() }
    }

    private fun productionWiring() {
        val source = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        val loop = source.substringAfter("val watchdogEngine = activeEngine").substringBefore("val creditsSkipSeconds =")
        check("val nativeTimeline = sampleWatchdogTimeline(" in loop)
        check("position = { observedPlayerPositionMs() }" in loop)
        // Metadata/retirement may intentionally leave no Media3 instance; preserve unknown duration.
        check("duration = { if (watchdogEngine == \"mpv\") watchdogBackend?.durationMs() else exo?.duration }" in loop) {
            "Watchdog duration is disconnected from current native sampling"
        }
        check("timeline = nativeTimeline" in loop && "expectedLoad = WatchdogLoadIdentity(sessionGeneration, watchdogMpvLoad?.physicalId)" in loop)
        check("!loadCoordinator.accepts(playbackLoadPermit)" in loop && "isCurrentMedia3()" in loop)
        check("watchdogBackend?.diagnosticLoadIdentity()?.let { WatchdogLoadIdentity(it.generation, it.physicalId) }" in loop)
        check("positionMs = playerPosition ?: 0L" !in loop && "durationMs = duration" !in loop)
        val stop = source.substringAfter("fun reportStopInAppScope()").substringBefore("fun confirmSeek(")
        check("val sample = timelinePosition(playerMs)" in stop && "stopClient.reportStop(session, sample.reportPositionMs" in stop)
        val progress = source.substringAfter("val reportSession = progressSession").substringBefore("LaunchedEffect(controlsVisible")
        check("val sample = timelinePosition(lastPlayerPositionMs)" in progress && "client?.reportProgress(reportSession, sample.reportPositionMs" in progress)
    }
}
