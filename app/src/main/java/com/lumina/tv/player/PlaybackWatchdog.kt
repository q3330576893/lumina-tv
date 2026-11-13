package com.lumina.tv.player

import android.os.SystemClock
import com.lumina.tv.diag.PageLog

enum class WatchdogReason { BUFFERING_NO_LOAD, BUFFERING_NO_PROGRESS, PLAYING_NO_PROGRESS, NO_END }

object StabilityMetrics {
    @Volatile var watchdogHits: Long = 0
    @Volatile var retryCount: Long = 0
    @Volatile var firstFrameMs: Long? = null
    @Volatile var rebufferCount: Long = 0
    @Volatile var rebufferDurationMs: Long = 0

    fun reset() {
        watchdogHits = 0
        retryCount = 0
        firstFrameMs = null
        rebufferCount = 0
        rebufferDurationMs = 0
    }
}

class PlaybackWatchdog {
    private var generation = -1
    private var generationAt = 0L
    private var stateSince = 0L
    private var loadingAt = 0L
    private var loading = false
    private var byteAt = 0L
    private var lastBytes = -1L
    private var lastPositionMs = -1L
    private var lastPositionAt = 0L
    private var lastBufferedMs = -1L
    private var lastBufferedAt = 0L
    private var suppressedUntil = 0L

    fun reset() {
        generation = -1
        generationAt = 0L
        stateSince = 0L
        loadingAt = 0L
        loading = false
        byteAt = 0L
        lastBytes = -1L
        lastPositionMs = -1L
        lastPositionAt = 0L
        lastBufferedMs = -1L
        lastBufferedAt = 0L
        suppressedUntil = 0L
    }

    fun onTick(
        positionMs: Long, bufferedMs: Long, state: Int, playWhenReady: Boolean,
        isSeeking: Boolean, sessionGeneration: Int, durationMs: Long, bytesLoaded: Long, isLoading: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (generation != sessionGeneration) {
            generation = sessionGeneration
            generationAt = now
            stateSince = now
            lastPositionMs = positionMs
            lastPositionAt = now
            lastBufferedMs = bufferedMs
            lastBufferedAt = now
            lastBytes = bytesLoaded
            byteAt = now
            return
        }
        if (!playWhenReady || isSeeking || now - generationAt < 3_000L) {
            updatePositions(now, positionMs, bufferedMs, bytesLoaded)
            return
        }
        if (bytesLoaded != lastBytes) { lastBytes = bytesLoaded; byteAt = now }
        loading = isLoading
        if (positionMs != lastPositionMs) { lastPositionMs = positionMs; lastPositionAt = now }
        if (bufferedMs != lastBufferedMs) { lastBufferedMs = bufferedMs; lastBufferedAt = now }
        if (now < suppressedUntil || now - loadingAt < 8_000L) return
        val reason = when {
            durationMs > 0 && durationMs - positionMs in 0..10_000L && now - lastPositionAt >= 60_000L -> WatchdogReason.NO_END
            state == 2 && !loading && now - stateSince >= 4_000L && now - byteAt >= 4_000L -> WatchdogReason.BUFFERING_NO_LOAD
            state == 2 && loading && now - lastBufferedAt >= 4_000L -> WatchdogReason.BUFFERING_NO_PROGRESS
            state == 3 && now - lastPositionAt >= 4_000L -> WatchdogReason.PLAYING_NO_PROGRESS
            else -> null
        } ?: return
        PageLog.add("watchdog hit=${reason.name} pos=$positionMs buffered=$bufferedMs state=$state gen=$sessionGeneration")
        StabilityMetrics.watchdogHits++
        suppressedUntil = now + 30_000L
    }

    fun onLoadingChanged(loading: Boolean) {
        this.loading = loading
        loadingAt = SystemClock.elapsedRealtime()
        stateSince = loadingAt
    }

    private fun updatePositions(now: Long, positionMs: Long, bufferedMs: Long, bytesLoaded: Long) {
        lastPositionMs = positionMs; lastPositionAt = now
        lastBufferedMs = bufferedMs; lastBufferedAt = now
        if (bytesLoaded != lastBytes) { lastBytes = bytesLoaded; byteAt = now }
    }
}
