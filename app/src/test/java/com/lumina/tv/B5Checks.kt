package com.lumina.tv

import com.lumina.tv.player.CreditsCountdown
import com.lumina.tv.player.PlayerHostLifecycleEvent
import com.lumina.tv.player.PlayerLifecycleRegistry
import com.lumina.tv.player.SubtitleOffsetState
import com.lumina.tv.player.applyMpvHttpHeaders
import com.lumina.tv.player.detailPreviewShouldPlay
import com.lumina.tv.player.reducePlayerHostLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.lang.ref.WeakReference

/** Offline regressions for the B5 player lifecycle and renderer details. */
object B5Checks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        mpvHeaderOptions()
        subtitleOffsetPublication()
        hostLifecycleTransitions()
        weakPlayerRegistry()
        creditsCountdownSelfStop()
        println("B5 PASS: Q-020 per-item mpv headers; Q-021 volatile subtitle offset; Q-048 host-resumed preview; E7-A1 pause without reload; E7-B2 weak identity registry; E7-B4 countdown internal stop")
    }

    private fun mpvHeaderOptions() {
        val headers = linkedMapOf(
            "Referer" to "https://example.invalid/path?a=1,b=2",
            "Cookie" to "token=first\\second",
            "X-Note" to "字幕, 中文",
            "X-Empty" to "",
        )
        val options = mutableListOf<Pair<String, String>>()
        val clean = applyMpvHttpHeaders(headers) { name, value -> options += name to value; 0 }
        check(options == listOf(
            "http-header-fields-append" to "Referer: https://example.invalid/path?a=1,b=2",
            "http-header-fields-append" to "Cookie: token=first\\second",
            "http-header-fields-append" to "X-Note: 字幕, 中文",
            "http-header-fields-append" to "X-Empty: ",
        )) { "mpv option sequence did not preserve header values: $options" }
        check(clean == null) { "accepted headers must not produce a warning: $clean" }

        // A build that rejects the list must not fail the load; the caller logs the warning instead.
        val failedOptions = mutableListOf<String>()
        val failure = applyMpvHttpHeaders(headers) { name, _ ->
            failedOptions += name
            -1
        }
        check(failure != null && failure.contains("4/4")) { "rejected headers must degrade, not throw: $failure" }
        check(failedOptions.all { it == "http-header-fields-append" }) { "unexpected option: $failedOptions" }
        check(failedOptions.size == headers.size) { "every header must be attempted: $failedOptions" }
        check(applyMpvHttpHeaders(emptyMap()) { _, _ -> -1 } == null) { "no headers means nothing to report" }
    }

    private suspend fun subtitleOffsetPublication() {
        val state = SubtitleOffsetState()
        val positiveWritten = CompletableDeferred<Unit>()
        val negativeWritten = CompletableDeferred<Unit>()
        val writer = Thread {
            state.offsetMs = 250L
            positiveWritten.complete(Unit)
            runBlocking { negativeWritten.await() }
            state.offsetMs = -500L
        }
        writer.start()
        positiveWritten.await()
        check(state.offsetMs == 250L)
        negativeWritten.complete(Unit)
        writer.join()
        check(state.offsetMs == -500L)
    }

    private fun hostLifecycleTransitions() {
        val paused = reducePlayerHostLifecycle(
            resumedByBackground = false,
            event = PlayerHostLifecycleEvent.ON_STOP,
            playbackRequested = true,
        )
        check(paused.resumedByBackground && paused.pausePlayback && !paused.restoreControls)
        val unchanged = reducePlayerHostLifecycle(
            resumedByBackground = false,
            event = PlayerHostLifecycleEvent.ON_STOP,
            playbackRequested = false,
        )
        check(!unchanged.resumedByBackground && !unchanged.pausePlayback)
        val returned = reducePlayerHostLifecycle(
            resumedByBackground = paused.resumedByBackground,
            event = PlayerHostLifecycleEvent.ON_START,
            playbackRequested = false,
        )
        check(!returned.resumedByBackground && !returned.pausePlayback && returned.restoreControls)
        check(detailPreviewShouldPlay(requested = true, hostResumed = true))
        check(!detailPreviewShouldPlay(requested = true, hostResumed = false))
        check(!detailPreviewShouldPlay(requested = false, hostResumed = true))
    }

    private suspend fun weakPlayerRegistry() {
        val logs = mutableListOf<String>()
        val registry = PlayerLifecycleRegistry(logs::add)
        check(registry.liveVideoDecoders == 0)
        val player = registerEphemeralPlayer(registry)
        repeat(40) {
            if (player.get() == null) return@repeat
            System.gc()
            allocatePressure()
            Thread.sleep(5)
        }
        check(player.get() == null) { "player registry retained a player without an external owner" }
        check(registry.liveVideoDecoders == 0)
        check(logs.first().startsWith("playerLifecycle event=create engine=media3 id=1 owner=weak-check players=1"))
    }

    private fun registerEphemeralPlayer(registry: PlayerLifecycleRegistry): WeakReference<Any> {
        val player = Any()
        registry.created(player, "media3", "weak-check")
        registry.decoderInitialized(player, "hevc")
        check(registry.liveVideoDecoders == 1)
        return WeakReference(player)
    }

    private fun allocatePressure() { ByteArray(64 * 1024) }

    private suspend fun creditsCountdownSelfStop() = coroutineScope {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val logs = mutableListOf<String>()
        val countdown = CreditsCountdown<String>(this, logs::add) { ticks.receive() }
        var canContinue = true
        countdown.start(2, next = { "next" }, canContinue = { canContinue }, onComplete = { error("unexpected completion") })
        yield()
        check(countdown.active && countdown.remainingSeconds == 2)
        canContinue = false
        ticks.send(Unit)
        yield()
        check(!countdown.active && countdown.remainingSeconds == 0 && countdown.nextItem == null)
        check(!countdown.triggered && logs.any { it == "creditsCountdown cancel reason=playback-paused-or-replaced restart=true" })
        ticks.close()
    }
}
