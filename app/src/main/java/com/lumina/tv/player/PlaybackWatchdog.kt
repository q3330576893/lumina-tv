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
    private var physicalLoadId: Long? = null
    private var generationAt = 0L
    private var stateSince = 0L
    private var playbackState = 0
    private var loadingAt = 0L
    private var loading = false
    private var byteAt = 0L
    private var lastBytes = -1L
    private var lastPositionMs = -1L
    private var lastPositionAt = 0L
    private var lastBufferedMs: Long? = null
    private var lastBufferedAt: Long? = null
    private var suppressedUntil = 0L

    fun reset() {
        generation = -1
        physicalLoadId = null
        generationAt = 0L
        stateSince = 0L
        playbackState = 0
        loadingAt = 0L
        loading = false
        byteAt = 0L
        lastBytes = -1L
        lastPositionMs = -1L
        lastPositionAt = 0L
        lastBufferedMs = null
        lastBufferedAt = null
        suppressedUntil = 0L
    }

    fun onTick(
        positionMs: Long, bufferedMs: Long?, state: Int, playWhenReady: Boolean,
        isSeeking: Boolean, sessionGeneration: Int, durationMs: Long, bytesLoaded: Long, isLoading: Boolean,
        mpvLoadId: Long? = null, downloadInProgress: Boolean = false,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): WatchdogReason? {
        val now = nowMs
        onPlaybackStateChanged(state, now)
        if (generation != sessionGeneration || physicalLoadId != mpvLoadId) {
            generation = sessionGeneration
            physicalLoadId = mpvLoadId
            generationAt = now
            stateSince = now
            onLoadingChanged(isLoading, now)
            lastPositionMs = positionMs
            lastPositionAt = now
            updateBuffered(now, bufferedMs, rebase = true)
            lastBytes = bytesLoaded
            byteAt = now
            suppressedUntil = 0L
            return null
        }
        if (loading != isLoading) onLoadingChanged(isLoading, now)
        if (!playWhenReady || isSeeking || now - generationAt < 3_000L) {
            updatePositions(now, positionMs, bufferedMs, bytesLoaded)
            return null
        }
        if (bytesLoaded != lastBytes) { lastBytes = bytesLoaded; byteAt = now }
        if (positionMs != lastPositionMs) { lastPositionMs = positionMs; lastPositionAt = now }
        updateBuffered(now, bufferedMs)
        val bufferedAge = lastBufferedAt?.let { now - it }
        val reason = playbackWatchdogReason(PlaybackWatchdogInput(
            positionMs = positionMs,
            durationMs = durationMs,
            positionAgeMs = now - lastPositionAt,
            bufferedAgeMs = bufferedAge,
            stateAgeMs = now - stateSince,
            bytesUnchangedForMs = now - byteAt,
            loadingAgeMs = now - loadingAt,
            buffering = state == 2,
            ready = state == 3,
            loading = loading,
            downloadInProgress = downloadInProgress,
            mpvLoad = mpvLoadId != null,
            playWhenReady = playWhenReady,
            seeking = isSeeking,
            startupComplete = now - generationAt >= 3_000L,
            suppressed = now < suppressedUntil,
        )) ?: return null
        PageLog.add("watchdog hit=${reason.name} pos=$positionMs buffered=${bufferedMs ?: "unknown"} state=$state gen=$sessionGeneration")
        StabilityMetrics.watchdogHits++
        suppressedUntil = now + 30_000L
        return reason
    }

    fun onLoadingChanged(loading: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        this.loading = loading
        loadingAt = nowMs
    }

    fun onPlaybackStateChanged(state: Int, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (playbackState == state) return
        playbackState = state
        stateSince = nowMs
    }

    private fun updatePositions(now: Long, positionMs: Long, bufferedMs: Long?, bytesLoaded: Long) {
        lastPositionMs = positionMs; lastPositionAt = now
        updateBuffered(now, bufferedMs, rebase = true)
        if (bytesLoaded != lastBytes) { lastBytes = bytesLoaded; byteAt = now }
    }

    private fun updateBuffered(now: Long, bufferedMs: Long?, rebase: Boolean = false) {
        if (bufferedMs == null) {
            lastBufferedMs = null
            lastBufferedAt = null
        } else if (rebase || bufferedMs != lastBufferedMs) {
            lastBufferedMs = bufferedMs
            lastBufferedAt = now
        }
    }
}
