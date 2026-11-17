package com.lumina.tv.data

import com.lumina.tv.data.TimelineCalibration.Confidence
import kotlin.math.abs

/** An offset is evidence from this physical playback, never an inference from its route/container. */
data class TimelineCalibration(
    val offsetMs: Long?,
    val confidence: Confidence,
    val evidence: String,
) {
    enum class Confidence { MEASURED, CONFLICT, UNMEASURED }

    fun playerToContentPositionMs(playerMs: Long): Long? = offsetMs?.let { (playerMs + it).coerceAtLeast(0L) }
    fun contentToPlayerPositionMs(contentMs: Long): Long? = offsetMs?.let { (contentMs - it).coerceAtLeast(0L) }
}

/** Duration rules have priority within their family; an opposing position measurement vetoes them. */
internal fun measureTimeline(
    playerPos: Long,
    startPos: Long,
    playerDur: Long?,
    contentDur: Long?,
): TimelineCalibration {
    val tolerance = maxOf(2_000.0, (contentDur ?: 0L) * 0.02)
    val durationOffset = if (playerDur != null && playerDur > 0L && contentDur != null && contentDur > 0L) {
        when {
            abs(playerDur.toDouble() - contentDur) <= tolerance -> 0L
            startPos > 0L && contentDur >= startPos &&
                abs(playerDur.toDouble() - (contentDur - startPos)) <= tolerance -> startPos
            else -> null
        }
    } else null
    val positionOffset = when {
        abs(playerPos.toDouble() - startPos) <= 2_000.0 -> 0L
        playerPos in 0L..5_000L && startPos >= 10_000L -> startPos
        else -> null
    }
    val evidence = "posMs:$playerPos->startMs:$startPos,durMs:$playerDur/$contentDur," +
        "durationOffset:$durationOffset,positionOffset:$positionOffset,toleranceMs:$tolerance"
    return when {
        durationOffset != null && positionOffset != null && durationOffset != positionOffset ->
            TimelineCalibration(null, Confidence.CONFLICT, "$evidence,conflicting-measurements")
        durationOffset != null -> TimelineCalibration(durationOffset, Confidence.MEASURED, "$evidence,duration")
        positionOffset != null -> TimelineCalibration(positionOffset, Confidence.MEASURED, "$evidence,position")
        else -> TimelineCalibration(null, Confidence.UNMEASURED, "$evidence,no-matching-rule")
    }
}

/** The first available sample after the first frame locks the result, including failures. */
internal class TimelineCalibrationWindow(private val startPos: Long, private val contentDur: Long?) {
    var calibration = TimelineCalibration(null, Confidence.UNMEASURED, "awaiting-first-frame-and-position")
        private set
    var locked = false
        private set
    private var firstFrameAt: Long? = null
    private var resumeReached = false
    private var restartDecided = false

    fun firstFrame(now: Long): Boolean {
        if (firstFrameAt != null) return false
        firstFrameAt = now
        return true
    }

    fun sample(playerPos: Long?, playerDur: Long?, now: Long): Boolean {
        if (locked) return false
        val first = firstFrameAt ?: return false
        if (now - first > SAMPLE_WINDOW_MS || now - first == SAMPLE_WINDOW_MS && playerPos == null) {
            calibration = TimelineCalibration(null, Confidence.UNMEASURED, "no-position-within-8000ms-after-first-frame")
        } else {
            if (playerPos == null || playerPos < 0L) return false
            calibration = measureTimeline(playerPos, startPos, playerDur, contentDur)
        }
        locked = true
        return true
    }

    /** A later user seek cannot become evidence for the initial timestamp origin. */
    fun beforeSeek() {
        if (!locked) {
            calibration = TimelineCalibration(null, Confidence.UNMEASURED, "seek-before-calibration-sample")
            locked = true
        }
    }

    fun position(playerMs: Long?): TimelinePosition {
        val player = playerMs?.coerceAtLeast(0L)
        return TimelinePosition(player, player?.let(calibration::playerToContentPositionMs))
    }

    /** Called only for STRM resume. Observe reaching the requested content point, then decide once. */
    fun shouldRestartStrmFromZero(playerMs: Long?, now: Long): Boolean {
        val first = firstFrameAt ?: return false
        if (startPos <= 0L || restartDecided) return false
        val observed = position(playerMs).reportPositionMs
        if (playerMs != null && observed >= (startPos - 2_000L).coerceAtLeast(0L)) resumeReached = true
        if (now - first < SAMPLE_WINDOW_MS) return false
        restartDecided = true
        return !resumeReached
    }

    companion object { const val SAMPLE_WINDOW_MS = 8_000L }
}

/** The same observed snapshot feeds UI, Progress and Stop. No requested/optimistic position is an input. */
internal data class TimelinePosition(val playerPositionMs: Long?, val contentPositionMs: Long?) {
    val reportPositionMs: Long get() = contentPositionMs ?: playerPositionMs ?: 0L
    val timelineUnverified: Boolean get() = contentPositionMs == null
}
