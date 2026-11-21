package com.lumina.tv

import com.lumina.tv.player.CreditsCountdown
import com.lumina.tv.player.PlayerHostLifecycleEvent
import com.lumina.tv.player.PlayerLifecycleRegistry
import com.lumina.tv.player.SubtitleOffsetState
import com.lumina.tv.player.applyMpvHttpHeaders
import com.lumina.tv.player.B17HeaderBinding
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
        val binding = B17HeaderBinding()
        val clean = binding.apply(headers)
        check(binding.calls == headers.map { (name, value) -> "option:http-header-fields-append" to "$name: $value" })
        check(binding.elements == headers.map { (name, value) -> "$name: $value" })
        check(clean.mode == "append" && clean.accepted == headers.size && clean.complete && clean.notice == null)

        // parse+print can echo a corrupted list. This must never be a success test.
        val raw = headers.entries.joinToString(",") { (name, value) -> "$name: $value" }
        val lossy = B17HeaderBinding()
        lossy.setProperty("http-header-fields", raw)
        check(lossy.getProperty("http-header-fields") == raw)
        check(lossy.elements.size != headers.size)

        // Rejection must not fail loadfile; the probe reports it for diagnostics and the UI.
        val failedOptions = mutableListOf<String>()
        val failure = applyMpvHttpHeaders(headers) { name, _ -> failedOptions += "option:$name"; -1 }
        check(failure.mode == "append" && failure.accepted == 0 && !failure.complete)
        check(failure.notice?.contains("4/4") == true) { "rejected headers must remain visible: $failure" }
        check(failedOptions == List(headers.size) { "option:http-header-fields-append" })
        val empty = applyMpvHttpHeaders(emptyMap()) { _, _ -> error("must not probe empty headers") }
        check(empty.mode == "none" && empty.complete)
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
