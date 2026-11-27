package com.lumina.tv.player

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.WeakHashMap

internal sealed interface RetirementOutcome {
    data object Pending : RetirementOutcome
    data object Succeeded : RetirementOutcome
    data class Failed(val cause: Throwable) : RetirementOutcome
}

/** Completion is evidence that cleanup finished, not merely that its work item ran. */
internal class LoadRetirement {
    private val completion = CompletableDeferred<RetirementOutcome>()
    private val state = AtomicReference<RetirementOutcome>(RetirementOutcome.Pending)

    val outcome: RetirementOutcome get() = state.get()
    val isCompleted: Boolean get() = outcome !is RetirementOutcome.Pending

    fun succeed(): Boolean = complete(RetirementOutcome.Succeeded)
    fun fail(cause: Throwable): Boolean = complete(RetirementOutcome.Failed(cause))

    private fun complete(outcome: RetirementOutcome): Boolean {
        if (!state.compareAndSet(RetirementOutcome.Pending, outcome)) return false
        completion.complete(outcome)
        return true
    }

    suspend fun await(): RetirementOutcome = completion.await()

    fun invokeOnCompletion(action: (RetirementOutcome) -> Unit) {
        completion.invokeOnCompletion { action(outcome) }
    }
}

internal class RetirementBlockedException(cause: Throwable) :
    IllegalStateException("Playback resource cleanup failed; new loads remain blocked", cause)

internal class RetirementPermit internal constructor(
    private val fence: LoadRetirementFence,
    internal val revision: Long,
) {
    fun isValid(): Boolean = fence.accepts(this)
}

/** Serial work plus deferred destruction: retirement never races a synchronous JNI command. */
internal class LoadScopedIo<I>(
    val identity: I,
    private val isCurrent: (I) -> Boolean,
    private val post: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val cancellations = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "mpv-subtitle-io").apply { isDaemon = true } }
    private val retired = LoadRetirement()

    fun checkCurrent() { if (closed.get() || !isCurrent(identity)) throw CancellationException("Retired subtitle load") }

    fun cancelWith(action: () -> Unit): () -> Unit {
        cancellations += action
        if (closed.get()) action()
        return { cancellations -= action }
    }

    fun <T> submit(work: () -> T, complete: (Result<T>) -> Unit): Boolean = synchronized(lock) {
        if (closed.get()) return@synchronized false
        executor.execute {
            val result = runCatching { checkCurrent(); work() }
            if (!closed.get() && isCurrent(identity)) post {
                if (!closed.get() && isCurrent(identity)) complete(result)
            }
        }
        true
    }

    /** Cleanup runs on the same serial executor, AFTER any command already inside native code. */
    fun retire(cleanup: () -> Unit): LoadRetirement = synchronized(lock) {
        if (closed.compareAndSet(false, true)) {
            val cancellationFailures = cancellations.toList().mapNotNull { runCatching(it).exceptionOrNull() }
            try {
                executor.execute {
                    var failure: Throwable? = cancellationFailures.firstOrNull()
                    cancellationFailures.drop(1).forEach { failure?.addSuppressed(it) }
                    try {
                        cleanup()
                    } catch (cleanupFailure: Throwable) {
                        if (failure == null) failure = cleanupFailure else failure?.addSuppressed(cleanupFailure)
                    } finally {
                        if (failure == null) retired.succeed() else retired.fail(failure!!)
                    }
                }
            } catch (enqueueFailure: Throwable) {
                cancellationFailures.forEach(enqueueFailure::addSuppressed)
                retired.fail(enqueueFailure)
            }
            executor.shutdown()
        }
        retired
    }
}

/** New decoders need a current permit after all prior cleanup has succeeded. */
internal open class LoadRetirementFence {
    private val pending = mutableSetOf<LoadRetirement>()
    private var revision = 0L

    @Synchronized fun track(retirement: LoadRetirement) {
        pending += retirement
        revision++
        retirement.invokeOnCompletion { outcome ->
            if (outcome is RetirementOutcome.Succeeded) synchronized(this) { pending.remove(retirement) }
        }
    }

    /** Compatibility bridge for older lifecycle fixtures; production uses LoadRetirement. */
    @Synchronized fun track(retirement: CompletableDeferred<Unit>) {
        val wrapped = LoadRetirement()
        track(wrapped)
        retirement.invokeOnCompletion { cause ->
            if (cause == null) wrapped.succeed() else wrapped.fail(cause)
        }
    }

    suspend fun awaitPermit(): RetirementPermit {
        while (true) {
            val snapshot = synchronized(this) {
                val failure = pending.firstNotNullOfOrNull { it.outcome as? RetirementOutcome.Failed }
                if (failure != null) throw RetirementBlockedException(failure.cause)
                val unresolved = pending.filter { it.outcome is RetirementOutcome.Pending }
                if (unresolved.isEmpty()) return RetirementPermit(this, revision)
                unresolved
            }
            snapshot.forEach { it.await() }
        }
    }

    suspend fun await() { awaitPermit() }

    @Synchronized internal fun accepts(permit: RetirementPermit): Boolean =
        permit.revision == revision && pending.none { it.outcome !is RetirementOutcome.Succeeded }
}

internal object MpvRetirementFence : LoadRetirementFence()

/** Tracks only Media3 video-decoder callbacks needed to prove an old load has drained. */
internal object Media3DecoderRetirements {
    private data class Entry(
        val decoders: MutableList<String> = mutableListOf(),
        val retirements: MutableMap<LoadRetirement, Boolean> = mutableMapOf(),
    )

    private val players = WeakHashMap<Any, Entry>()

    @Synchronized fun decoderInitialized(player: Any, name: String) {
        players.getOrPut(player, ::Entry).decoders += name
    }

    @Synchronized fun decoderReleased(player: Any, name: String) {
        val entry = players[player] ?: return
        entry.decoders.remove(name)
        completeDrained(entry)
    }

    @Synchronized fun begin(player: Any): LoadRetirement = LoadRetirement().also { retirement ->
        players.getOrPut(player, ::Entry).retirements[retirement] = false
    }

    @Synchronized fun finish(player: Any, retirement: LoadRetirement) {
        val entry = players[player]
        if (entry == null) {
            retirement.succeed()
            return
        }
        if (retirement !in entry.retirements) return
        entry.retirements[retirement] = true
        completeDrained(entry)
    }

    @Synchronized fun fail(player: Any, retirement: LoadRetirement, failure: Throwable) {
        players[player]?.retirements?.remove(retirement)
        retirement.fail(failure)
    }

    @Synchronized fun released(player: Any) {
        val entry = players.remove(player) ?: return
        entry.retirements.keys.forEach(LoadRetirement::succeed)
    }

    private fun completeDrained(entry: Entry) {
        if (entry.decoders.isNotEmpty()) return
        entry.retirements.filterValues { it }.keys.toList().forEach { retirement ->
            retirement.succeed()
            entry.retirements.remove(retirement)
        }
    }
}
