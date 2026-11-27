package com.lumina.tv

import com.lumina.tv.player.CreditsCountdown
import com.lumina.tv.player.PlayerHostLifecycleEvent
import com.lumina.tv.player.PlayerLifecycleRegistry
import com.lumina.tv.player.SubtitleOffsetState
import com.lumina.tv.player.applyMpvHttpHeaders
import com.lumina.tv.player.B17HeaderBinding
import com.lumina.tv.player.detailPreviewShouldPlay
import com.lumina.tv.player.reducePlayerHostLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        // One base-option set (mpv_set_option_string has no -append suffix) + one readback.
        check(binding.calls.map { it.first } == listOf("option:http-header-fields", "read:options/http-header-fields"))
        check(binding.elements == headers.map { (name, value) -> "$name: $value" })
        check(clean.mode == "string-verified" && clean.accepted == 1 && clean.complete && clean.notice == null)

        // Rejection must not fail loadfile; the probe reports it for diagnostics and the UI.
        val attempted = mutableListOf<Pair<String, String>>()
        val failure = applyMpvHttpHeaders(headers, { name, value -> attempted += name to value; -1 })
        check(failure.mode == "string-failed" && failure.accepted == 0 && !failure.complete)
        check(failure.notice?.contains("4/4") == true) { "rejected headers must remain visible: $failure" }
        check(failure.failedNames == headers.keys.toList())
        check(attempted.map { it.first } == listOf("http-header-fields"))
        val empty = applyMpvHttpHeaders(emptyMap(), { _, _ -> error("must not probe empty headers") })
        check(empty.mode == "none" && empty.complete)
    }

    private suspend fun subtitleOffsetPublication() {
        val writers = mutableListOf<Thread>()
        val recordWriter: (Thread) -> Unit = { writers += it }
        verifySubtitleOffsetPublication(recordWriter)
        check(writers.none { it.isAlive })

        val injectedCheckFailure = IllegalStateException("injected subtitle publication assertion failure")
        val checkFailure = runCatching {
            verifySubtitleOffsetPublication(recordWriter, afterPositive = { throw injectedCheckFailure })
        }.exceptionOrNull()
        check(checkFailure === injectedCheckFailure) { "Publication assertion failure was lost: $checkFailure" }
        check(injectedCheckFailure.suppressed.isEmpty()) { "Publication cleanup failed" }
        check(writers.none { it.isAlive }) { "Assertion failure retained a subtitle writer" }

        val injectedWriterFailure = IllegalStateException("injected subtitle writer failure")
        val writerFailure = runCatching {
            verifySubtitleOffsetPublication(recordWriter, injectedWriterFailure = injectedWriterFailure)
        }.exceptionOrNull()
        check(writerFailure === injectedWriterFailure) { "Writer failure was lost: $writerFailure" }
        check(writers.none { it.isAlive }) { "Writer failure retained a subtitle writer" }

        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val cancellationSeen = CompletableDeferred<CancellationException>()
            val job = launch {
                try {
                    verifySubtitleOffsetPublication(recordWriter, afterPositive = {
                        entered.complete(Unit)
                        awaitCancellation()
                    })
                } catch (cancelled: CancellationException) {
                    cancellationSeen.complete(cancelled)
                    throw cancelled
                }
            }
            withTimeout(5_000L) { entered.await() }
            job.cancelAndJoin()
            check(job.isCancelled && cancellationSeen.isCompleted)
            check(cancellationSeen.await().suppressed.isEmpty()) { "Cancellation cleanup failed" }
            check(writers.none { it.isAlive }) { "Cancellation retained a subtitle writer" }
        }
        check(writers.size == 4 && writers.none { it.isAlive })
        check(writers.none { it.isDaemon }) { "Writer cleanup must not depend on daemon threads" }
    }

    private suspend fun verifySubtitleOffsetPublication(
        onWriter: (Thread) -> Unit,
        injectedWriterFailure: Throwable? = null,
        afterPositive: suspend () -> Unit = {},
    ) {
        val state = SubtitleOffsetState()
        val positiveWritten = CompletableDeferred<Unit>()
        val negativeWritten = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>(null)
        val writer = Thread({
            try {
                state.offsetMs = 250L
                positiveWritten.complete(Unit)
                check(negativeWritten.await(5L, TimeUnit.SECONDS)) { "Subtitle writer release timed out" }
                injectedWriterFailure?.let { throw it }
                state.offsetMs = -500L
            } catch (failure: Throwable) {
                writerError.set(failure)
                positiveWritten.completeExceptionally(failure)
            }
        }, "b5-subtitle-offset-writer").apply { isDaemon = false }
        onWriter(writer)
        var primaryFailure: Throwable? = null
        try {
            writer.start()
            withTimeout(5_000L) { positiveWritten.await() }
            check(state.offsetMs == 250L)
            afterPositive()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // No suspension here: cancellation cannot skip releasing or joining the JVM thread.
            negativeWritten.countDown()
            val joinFailure = runCatching { joinSubtitleWriter(writer) }.exceptionOrNull()
            val asynchronousFailure = writerError.get()
            val failure = primaryFailure ?: joinFailure ?: asynchronousFailure
            if (failure != null) {
                if (joinFailure != null && joinFailure !== failure) failure.addSuppressed(joinFailure)
                if (asynchronousFailure != null && asynchronousFailure !== failure) failure.addSuppressed(asynchronousFailure)
                if (primaryFailure == null) throw failure
            }
        }
        check(state.offsetMs == -500L)
    }

    private fun joinSubtitleWriter(writer: Thread) {
        var interrupted = false
        fun awaitUntil(deadline: Long) {
            while (writer.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return
                try {
                    TimeUnit.NANOSECONDS.timedJoin(writer, remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                    writer.interrupt()
                }
            }
        }
        try {
            awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5L))
            if (writer.isAlive) {
                writer.interrupt()
                awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5L))
            }
            check(!writer.isAlive) { "Subtitle writer did not terminate after release and interrupt" }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (interrupted) throw InterruptedException("Interrupted while joining subtitle writer")
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

    private fun weakPlayerRegistry() {
        val logs = mutableListOf<String>()
        val registry = PlayerLifecycleRegistry { line -> logs += line }
        check(registry.liveVideoDecoders == 0)
        val first = EqualPlayer()
        val second = EqualPlayer()
        check(first !== second && first == second && first.hashCode() == second.hashCode())
        val firstId = registry.created(first, "media3", "weak-check")
        val secondId = registry.created(second, "media3", "weak-check-equal")
        check(firstId != secondId) { "Equal players must have distinct registry identities" }
        check(registry.created(first, "media3", "ignored-repeat") == firstId)
        registry.decoderInitialized(first, "hevc")
        registry.decoderInitialized(second, "hevc")
        check(registry.liveVideoDecoders == 2)
        registry.decoderReleased(first, "hevc")
        check(registry.liveVideoDecoders == 1)
        registry.decoderInitialized(first, "hevc")
        check(registry.liveVideoDecoders == 2)

        val playersField = PlayerLifecycleRegistry::class.java.getDeclaredField("players").apply { isAccessible = true }
        val entries = playersField.get(registry) as? Map<*, *> ?: error("Registry players must be a map")
        check(entries.size == 2)
        check(entries.keys.all { it is WeakReference<*> }) { "Registry keys must be actual weak references" }
        val keys = entries.keys.map { it as WeakReference<*> }
        val firstKey = keys.single { it.get() === first }
        val secondKey = keys.single { it.get() === second }
        check(firstKey !== secondKey && firstKey != secondKey)
        check(firstKey.hashCode() == System.identityHashCode(first))
        check(secondKey.hashCode() == System.identityHashCode(second))
        val players = listOf(first, second)
        assertNoStrongPlayerReferences(registry, players)
        val retainedPlayer = runCatching { assertNoStrongPlayerReferences(listOf(first), players) }.exceptionOrNull()
        check(retainedPlayer is IllegalStateException) { "Strong-reference inspection must reject a retained player" }

        // Exercise the real registered key and ReferenceQueue without depending on JVM GC timing.
        firstKey.clear()
        check(firstKey.get() == null)
        check(registry.liveVideoDecoders == 2) { "A cleared key must be removed through its queue notification" }
        check(firstKey.enqueue()) { "First weak key was not registered for queue cleanup" }
        check(registry.liveVideoDecoders == 1)
        check(entries.size == 1 && entries.keys.single() === secondKey)
        check(registry.created(second, "media3", "ignored-repeat") == secondId)
        registry.released(first, "cleared-key")
        check(registry.liveVideoDecoders == 1) { "Releasing the stale identity affected an equal player" }
        registry.decoderReleased(second, "hevc")
        check(registry.liveVideoDecoders == 0)
        registry.decoderInitialized(second, "av1")
        check(registry.liveVideoDecoders == 1)
        registry.released(second, "explicit-release")
        check(registry.liveVideoDecoders == 0 && entries.isEmpty())
        secondKey.clear()
        check(secondKey.enqueue())
        check(registry.liveVideoDecoders == 0 && entries.isEmpty())
        check(logs.first().startsWith("playerLifecycle event=create engine=media3 id=1 owner=weak-check players=1"))
    }

    private class EqualPlayer {
        override fun equals(other: Any?): Boolean = other is EqualPlayer
        override fun hashCode(): Int = 1
    }

    private fun assertNoStrongPlayerReferences(root: Any, players: List<Any>) {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?) {
            if (value == null) return
            check(players.none { it === value }) { "Registry has a strong path to a player" }
            if (!visited.add(value)) return
            when (value) {
                is String, is Number, is Boolean, is Char, is Enum<*>, is Class<*> -> return
                is Map<*, *> -> {
                    value.forEach { (key, entry) -> visit(key); visit(entry) }
                    return
                }
                is Iterable<*> -> {
                    value.forEach { visit(it) }
                    return
                }
                is Array<*> -> {
                    value.forEach { visit(it) }
                    return
                }
                // ReferenceQueue contains reference notifications, not strong referent ownership.
                is ReferenceQueue<*> -> return
            }
            var type: Class<*> = value.javaClass
            // Inspect custom weak-key fields, but never follow WeakReference's weak referent.
            while (type != Any::class.java && type != WeakReference::class.java) {
                check(!type.name.startsWith("java.")) { "Unhandled registry reference container: ${type.name}" }
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    visit(field.get(value))
                }
                type = type.superclass ?: break
            }
        }
        visit(root)
    }

    private suspend fun creditsCountdownSelfStop() = coroutineScope {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val logs = mutableListOf<String>()
        val countdown = CreditsCountdown<String>(this, logs::add) { ticks.receive() }
        var canContinue = true
        countdown.start(2, next = { com.lumina.tv.data.NextEpisodeResult.Found("next") }, canContinue = { canContinue }, onComplete = { error("unexpected completion") })
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
