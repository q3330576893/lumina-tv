package com.lumina.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.ui.AspectRatioFrameLayout
import com.lumina.tv.data.NextEpisodeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal const val BOOST_SPEED = 3f

internal data class PlaybackSpeedState(val userSpeed: Float, val boostActive: Boolean = false) {
    val effectiveSpeed: Float get() = if (boostActive) BOOST_SPEED else userSpeed
    fun select(value: Float) = PlaybackSpeedState(value)
    fun boost(active: Boolean) = copy(boostActive = active)
    fun forNewLoad() = copy(boostActive = false)
}

internal fun creditsWindowStartMs(durationMs: Long, markerMs: Long?, skipSeconds: Int): Long? {
    if (durationMs <= 0L || skipSeconds <= 0) return null
    return markerMs?.takeIf { it > 0L && it < durationMs }
        ?: (durationMs - skipSeconds * 1000L).coerceAtLeast(0L)
}

internal fun shouldCancelCreditsForSeek(fromMs: Long, targetMs: Long, windowStartMs: Long?, chapter: Boolean): Boolean =
    windowStartMs != null && targetMs < windowStartMs &&
        (chapter || (targetMs < fromMs && targetMs <= windowStartMs - 1_000L))

/** Owns both next-episode lookup and ticking, so cancellation also covers a lookup in flight. */
internal class CreditsCountdown<T>(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val awaitRetry: suspend (Long) -> Unit = { delay(it) },
    private val awaitTick: suspend () -> Unit = { delay(1_000L) },
) {
    var remainingSeconds by mutableIntStateOf(0)
        private set
    var nextItem by mutableStateOf<T?>(null)
        private set
    var triggered = false
        private set
    var lookupResult by mutableStateOf<NextEpisodeResult<T>?>(null)
        private set
    private var job: Job? = null
    private var revision = 0L
    val active: Boolean get() = job?.isActive == true

    private fun stopInternal(reason: String, allowRestart: Boolean) {
        job = null
        remainingSeconds = 0
        nextItem = null
        lookupResult = null
        triggered = !allowRestart
        log("creditsCountdown cancel reason=$reason restart=$allowRestart")
    }

    fun cancel(reason: String, allowRestart: Boolean = true) {
        revision++
        job?.cancel()
        stopInternal(reason, allowRestart)
    }

    /** T03 authority is captured once; a new permit, even in the same generation, revokes it. */
    fun startForLoad(
        seconds: Int, next: suspend () -> NextEpisodeResult<T>,
        loadCoordinator: PlaybackLoadCoordinator, generation: Int, permit: PlaybackLoadPermit,
        canContinue: () -> Boolean, onComplete: (T) -> Unit,
    ) = start(seconds, next, canContinue = {
        loadCoordinator.accepts(generation) && loadCoordinator.accepts(permit) && canContinue()
    }, onComplete = onComplete)

    fun start(seconds: Int, next: suspend () -> NextEpisodeResult<T>, canContinue: () -> Boolean, onComplete: (T) -> Unit) {
        if (triggered) return
        triggered = true
        val ticket = ++revision
        job = scope.launch {
            suspend fun stillCurrent(): Boolean {
                currentCoroutineContext().ensureActive()
                if (ticket != revision) return false
                if (canContinue()) return true
                stopInternal("playback-paused-or-replaced", allowRestart = true)
                return false
            }
            var attempts = 0
            var item: T
            while (true) {
                if (!stillCurrent()) return@launch
                val result = next()
                if (!stillCurrent()) return@launch
                attempts++
                lookupResult = result
                when (result) {
                    is NextEpisodeResult.Found -> { item = result.item; break }
                    NextEpisodeResult.NoNext -> { log("creditsCountdown result=NoNext"); return@launch }
                    is NextEpisodeResult.NonRetryableFailure -> { log("creditsCountdown result=NonRetryableFailure"); return@launch }
                    is NextEpisodeResult.RetryableFailure -> {
                        log("creditsCountdown result=RetryableFailure attempt=$attempts")
                        if (attempts >= 3) return@launch
                        awaitRetry(attempts * 1_000L)
                    }
                }
            }
            log("creditsCountdown result=Found attempts=$attempts")
            nextItem = item
            remainingSeconds = seconds.coerceAtLeast(1)
            while (remainingSeconds > 0) {
                awaitTick()
                if (!stillCurrent()) return@launch
                remainingSeconds--
            }
            onComplete(item)
        }
    }
}

internal data class MpvAspectProperties(val keepAspect: Boolean, val videoZoom: Double, val panscan: Double)

internal fun mpvAspectProperties(mode: Int): MpvAspectProperties = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> MpvAspectProperties(true, 0.0, 1.0)
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> MpvAspectProperties(false, 0.0, 0.0)
    else -> MpvAspectProperties(true, 0.0, 0.0)
}

/** Each property is independent: an unsupported property cannot prevent the others or playback. */
internal fun applyMpvAspectMode(mode: Int, setBoolean: (String, Boolean) -> Unit, setDouble: (String, Double) -> Unit) {
    val properties = mpvAspectProperties(mode)
    runCatching { setBoolean("keepaspect", properties.keepAspect) }
    runCatching { setDouble("video-zoom", properties.videoZoom) }
    runCatching { setDouble("panscan", properties.panscan) }
}
