package com.lumina.tv

import com.lumina.tv.data.TimelineCalibration.Confidence
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.PlaybackMode
import com.lumina.tv.data.TimelineCalibrationWindow
import com.lumina.tv.data.TimelineOrigin
import com.lumina.tv.data.measureTimeline
import com.lumina.tv.player.PendingSeekConfirmation
import com.lumina.tv.player.SeekConfirmation
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Production calibration/seek decisions and actual Emby HTTP bodies, without credentials or a device. */
object E2Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        calibrationRules()
        samplingWindow()
        observedPositionsAndResume()
        seekConfirmation()
        progressAndStopHttp()
        println("E2 PASS: duration/position calibration, conflicts, one sample/8s deadline, bidirectional conversion, moving unverified UI, STRM resume/fallback, 1.5s seek confirmation/rollback, real Stop and diagnostic-only metadata")
    }

    private fun calibrationRules() {
        fun measured(player: Long, start: Long, playerDur: Long?, contentDur: Long?, offset: Long) {
            val result = measureTimeline(player, start, playerDur, contentDur)
            check(result.confidence == Confidence.MEASURED && result.offsetMs == offset) { result }
            check(result.evidence.contains("posMs:$player") && result.evidence.contains("startMs:$start"))
        }
        // Full duration also works when the request starts at zero, even past the position window.
        measured(7_000L, 0L, 100_500L, 100_000L, 0L)
        measured(1_800_400L, 1_800_000L, 3_599_000L, 3_600_000L, 0L)
        measured(400L, 1_800_000L, 1_799_000L, 3_600_000L, 1_800_000L)
        // No usable duration, and duration present but neither duration rule matches.
        measured(121_500L, 120_000L, null, null, 0L)
        measured(120_400L, 120_000L, 10_000L, 600_000L, 0L)
        measured(400L, 120_000L, 0L, 600_000L, 120_000L)
        measured(400L, 120_000L, 10_000L, 600_000L, 120_000L)

        // Fixed 2 seconds for short media; 2% (20s here) for long media. Test both boundary sides.
        measured(7_000L, 0L, 102_000L, 100_000L, 0L)
        check(measureTimeline(7_000L, 0L, 102_001L, 100_000L).confidence == Confidence.UNMEASURED)
        measured(7_000L, 0L, 1_020_000L, 1_000_000L, 0L)
        check(measureTimeline(7_000L, 0L, 1_020_001L, 1_000_000L).confidence == Confidence.UNMEASURED)
        // Both duration rules fit a very small start; full duration wins per the prescribed order.
        measured(1_400L, 1_000L, 99_500L, 100_000L, 0L)
        measured(122_000L, 120_000L, null, null, 0L)
        check(measureTimeline(122_001L, 120_000L, null, null).confidence == Confidence.UNMEASURED)
        measured(5_000L, 10_000L, null, null, 10_000L)
        check(measureTimeline(5_001L, 10_000L, null, null).confidence == Confidence.UNMEASURED)
        check(measureTimeline(400L, 9_999L, null, null).confidence == Confidence.UNMEASURED)

        val conflicts = listOf(
            measureTimeline(400L, 120_000L, 600_000L, 600_000L),
            measureTimeline(120_400L, 120_000L, 480_000L, 600_000L),
        )
        conflicts.forEach { result ->
            check(result.confidence == Confidence.CONFLICT && result.offsetMs == null)
            check(result.playerToContentPositionMs(4_000L) == null)
            check(result.contentToPlayerPositionMs(124_000L) == null)
        }
        val unknown = measureTimeline(40_000L, 120_000L, null, 600_000L)
        check(unknown.confidence == Confidence.UNMEASURED && unknown.offsetMs == null)
        check(unknown.playerToContentPositionMs(41_000L) == null && unknown.contentToPlayerPositionMs(125_000L) == null)
    }

    private fun samplingWindow() {
        val window = TimelineCalibrationWindow(120_000L, 600_000L)
        check(!window.sample(400L, 480_000L, 100L) && !window.locked) // Before first frame.
        check(window.firstFrame(1_000L))
        check(!window.sample(null, 480_000L, 1_100L) && !window.locked)
        check(window.sample(400L, 480_000L, 1_200L))
        val locked = window.calibration
        check(locked.offsetMs == 120_000L && window.locked)
        check(!window.firstFrame(10_000L)) // Seek/surface callbacks cannot restart the deadline.
        check(!window.sample(120_000L, 600_000L, 10_001L) && window.calibration == locked)

        val atDeadline = TimelineCalibrationWindow(120_000L, null)
        atDeadline.firstFrame(0L)
        check(atDeadline.sample(400L, null, 8_000L) && atDeadline.calibration.offsetMs == 120_000L)
        val expired = TimelineCalibrationWindow(120_000L, null)
        expired.firstFrame(0L)
        check(expired.sample(400L, null, 8_001L))
        check(expired.locked && expired.calibration.confidence == Confidence.UNMEASURED)
        check(expired.calibration.offsetMs == null && expired.calibration.evidence.contains("8000ms"))
        val noPosition = TimelineCalibrationWindow(120_000L, null)
        noPosition.firstFrame(0L)
        check(noPosition.sample(null, null, 8_000L) && noPosition.locked)
        check(!noPosition.sample(400L, null, 8_100L) && noPosition.calibration.offsetMs == null)

        val failed = TimelineCalibrationWindow(120_000L, null)
        failed.firstFrame(0L)
        check(failed.sample(40_000L, null, 100L))
        check(!failed.sample(400L, null, 200L) && failed.calibration.confidence == Confidence.UNMEASURED)
        val sought = TimelineCalibrationWindow(120_000L, null)
        sought.beforeSeek()
        sought.firstFrame(100L)
        check(!sought.sample(120_000L, null, 200L) && sought.calibration.offsetMs == null)
        check(sought.calibration.evidence == "seek-before-calibration-sample")
    }

    private fun target(start: Long = 120_000L) = EmbyClient.PlaybackTarget(
        url = "https://example.invalid/video", mode = PlaybackMode.DirectStream,
        source = MediaSource("source", "source", "mkv", false, true, emptyList()),
        startPositionMs = start, playSessionId = "emby", audioStreamIndex = null,
        streamStartOffsetMs = null, timelineOrigin = TimelineOrigin.UNKNOWN, durationMs = 600_000L,
    )

    private fun observedPositionsAndResume() {
        val target = target()
        check(target.playerToContentPositionMs(400L) == null && target.contentToPlayerPositionMs(120_400L) == null)
        check(target.playerStartPositionMs == null) // Negotiated URLs receive no double seek.
        check(target.timeline.position(400L).reportPositionMs == 400L)
        check(target.timeline.position(1_400L).reportPositionMs == 1_400L) // UI must move before calibration.
        check(target.timeline.position(1_400L).timelineUnverified)
        target.timeline.firstFrame(0L)
        check(target.timeline.sample(400L, 480_000L, 100L))
        check(target.playerToContentPositionMs(4_000L) == 124_000L)
        check(target.contentToPlayerPositionMs(124_000L) == 4_000L)
        check(target.contentToPlayerPositionMs(10_000L) == 0L)
        check(target.timeline.position(4_000L).reportPositionMs == 124_000L)
        check(!target.timeline.position(4_000L).timelineUnverified)
        // Stop follows the final observed frame, not the original 120s request or a new seek target.
        check(target.timeline.position(60_000L).reportPositionMs == 180_000L)
        check(target.timeline.position(null).reportPositionMs == 0L)
        val fresh = target.copy()
        check(!fresh.timeline.locked && fresh.playerToContentPositionMs(4_000L) == null)
        target.resetTimeline()
        check(!target.timeline.locked && target.playerToContentPositionMs(4_000L) == null)

        val strm = target().copy(isStrmDirectLink = true)
        check(strm.playerStartPositionMs == 120_000L) // An attempt, still no verified conversion.
        check(strm.contentToPlayerPositionMs(120_000L) == null)
        strm.timeline.firstFrame(0L)
        strm.timeline.sample(120_400L, 600_000L, 100L)
        check(!strm.timeline.shouldRestartStrmFromZero(128_000L, 8_000L))
        val clipped = target().timeline
        clipped.firstFrame(0L)
        clipped.sample(400L, 480_000L, 100L)
        check(!clipped.shouldRestartStrmFromZero(8_000L, 8_000L)) // Content position already reached.
        val failedResume = target().timeline
        failedResume.firstFrame(0L)
        failedResume.sample(400L, 600_000L, 100L)
        check(failedResume.calibration.confidence == Confidence.CONFLICT)
        check(!failedResume.shouldRestartStrmFromZero(7_999L, 7_999L))
        check(failedResume.shouldRestartStrmFromZero(8_000L, 8_000L))
        check(!failedResume.shouldRestartStrmFromZero(9_000L, 9_000L)) // Decide/reopen only once.
    }

    private fun seekConfirmation() {
        val forward = PendingSeekConfirmation(120_000L, 120_000L, 10_000L, 100L)
        check(forward.evaluate(10_500L, 600L) == SeekConfirmation.PENDING)
        check(forward.evaluate(11_499L, 1_599L) == SeekConfirmation.PENDING)
        check(forward.evaluate(11_500L, 1_600L) == SeekConfirmation.ROLLED_BACK) // Drift is not a seek.
        check(forward.evaluate(119_500L, 1_000L) == SeekConfirmation.CONFIRMED)
        check(forward.evaluate(120_000L, 1_601L) == SeekConfirmation.ROLLED_BACK)
        check(forward.evaluate(null, 1_600L) == SeekConfirmation.ROLLED_BACK)
        check(forward.evaluate(9_000L, 1_600L) == SeekConfirmation.ROLLED_BACK)
        val backward = PendingSeekConfirmation(10_000L, 10_000L, 120_000L, 0L)
        check(backward.evaluate(120_500L, 1_500L) == SeekConfirmation.ROLLED_BACK)
        check(backward.evaluate(10_300L, 700L) == SeekConfirmation.CONFIRMED)
        // A chapter/content target is confirmed against player coordinates after conversion.
        val relative = PendingSeekConfirmation(124_000L, 4_000L, 1_000L, 0L)
        check(relative.evaluate(4_100L, 500L) == SeekConfirmation.CONFIRMED)
        check(relative.evaluate(124_000L, 1_500L) == SeekConfirmation.ROLLED_BACK)
        val initiallyUnavailable = PendingSeekConfirmation(40_000L, 40_000L, null, 0L)
        check(initiallyUnavailable.evaluate(40_100L, 500L) == SeekConfirmation.CONFIRMED)
        check(initiallyUnavailable.evaluate(null, 1_500L) == SeekConfirmation.ROLLED_BACK)
        val fast = PendingSeekConfirmation(15_000L, 15_000L, 10_000L, 0L, playbackSpeed = 3f)
        check(fast.evaluate(14_200L, 1_400L) == SeekConfirmation.PENDING) // Failed +5s at 3x enters old target tolerance.
        check(fast.evaluate(14_500L, 1_500L) == SeekConfirmation.ROLLED_BACK)
        check(fast.evaluate(15_300L, 100L) == SeekConfirmation.CONFIRMED) // A real jump differs from natural drift.
        check(fast.evaluate(18_000L, 1_000L) == SeekConfirmation.CONFIRMED) // Drift after a real seek is allowed.
        val paused = fast.copy(paused = true)
        check(paused.evaluate(10_000L, 1_500L) == SeekConfirmation.ROLLED_BACK)
        check(paused.evaluate(15_000L, 1_400L) == SeekConfirmation.CONFIRMED)
        check(paused.evaluate(14_200L, 1_400L, paused = false) == SeekConfirmation.PENDING)
        val accelerated = fast.copy(playbackSpeed = 1f)
        check(accelerated.evaluate(14_200L, 1_400L, playbackSpeed = 3f) == SeekConfirmation.PENDING)
        val small = PendingSeekConfirmation(11_000L, 11_000L, 10_000L, 0L)
        check(small.evaluate(11_000L, 1_000L) == SeekConfirmation.PENDING)
        check(small.evaluate(11_500L, 1_500L) == SeekConfirmation.ROLLED_BACK)
    }

    private suspend fun progressAndStopHttp() {
        val received = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val files = HashMap<String, String>()
                session.parseBody(files)
                received.trySend(session.uri to JSONObject(checkNotNull(files["postData"])))
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "test")
        suspend fun event(path: String, positionMs: Long) {
            val (actualPath, body) = withTimeout(5_000L) { received.receive() }
            check(actualPath == path && body.getLong("PositionTicks") == positionMs * 10_000L) { "$actualPath $body" }
            check(body.keys().asSequence().toSet() == setOf("ItemId", "PositionTicks", "IsPaused", "PlayMethod", "MediaSourceId", "PlaySessionId") +
                if (path.endsWith("Stopped")) emptySet() else setOf("CanSeek")) { "Unexpected Emby fields: $body" }
            check(!body.has("timelineUnverified") && !body.has("playerPos") && !body.has("contentPos"))
        }
        try {
            val unmeasured = target()
            val session = client.reportStart("item", unmeasured.startPositionMs, "source", "DirectStream", "emby")
            event("/Sessions/Playing", 120_000L)
            val progress = unmeasured.timeline.position(4_123L)
            check(progress.contentPositionMs == null && progress.timelineUnverified && progress.reportPositionMs == 4_123L)
            check(client.reportProgress(session, progress.reportPositionMs, false, progress.timelineUnverified, progress.playerPositionMs))
            check(client.lastProgressDiagnostics.getBoolean("timelineUnverified"))
            event("/Sessions/Playing/Progress", 4_123L)
            val stop = unmeasured.timeline.position(6_789L)
            check(client.reportStop(session, stop.reportPositionMs, true, stop.timelineUnverified, stop.playerPositionMs))
            check(client.lastProgressDiagnostics.getString("event") == "Stop")
            check(client.lastProgressDiagnostics.getLong("positionMs") == 6_789L)
            check(client.lastProgressDiagnostics.getLong("playerPos") == 6_789L)
            check(client.lastProgressDiagnostics.isNull("contentPos") && client.lastProgressDiagnostics.getBoolean("timelineUnverified"))
            event("/Sessions/Playing/Stopped", 6_789L)
            check(!client.reportProgress(session, 99_999L, false, true, 99_999L))
            check(client.lastProgressDiagnostics.getString("event") == "Stop")

            val measured = target()
            measured.timeline.firstFrame(0L)
            measured.timeline.sample(400L, 480_000L, 100L)
            val verifiedSession = client.reportStart("item", 120_000L, "source", "DirectStream", "emby")
            event("/Sessions/Playing", 120_000L)
            val verifiedProgress = measured.timeline.position(5_000L)
            check(client.reportProgress(verifiedSession, verifiedProgress.reportPositionMs, false,
                verifiedProgress.timelineUnverified, verifiedProgress.playerPositionMs))
            event("/Sessions/Playing/Progress", 125_000L)
            val verifiedStop = measured.timeline.position(7_000L)
            check(client.reportStop(verifiedSession, verifiedStop.reportPositionMs, true,
                verifiedStop.timelineUnverified, verifiedStop.playerPositionMs))
            check(!client.lastProgressDiagnostics.getBoolean("timelineUnverified"))
            check(client.lastProgressDiagnostics.getLong("contentPos") == 127_000L)
            event("/Sessions/Playing/Stopped", 127_000L)
        } finally {
            client.close()
            server.stop()
            received.close()
        }
    }
}
