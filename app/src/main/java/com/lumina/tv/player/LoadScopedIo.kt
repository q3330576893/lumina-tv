package com.lumina.tv.player

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val retired = CompletableDeferred<Unit>()

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
    fun retire(cleanup: () -> Unit): CompletableDeferred<Unit> = synchronized(lock) {
        if (closed.compareAndSet(false, true)) {
            cancellations.toList().forEach { runCatching(it) }
            executor.execute {
                try { cleanup() } finally { retired.complete(Unit) }
            }
            executor.shutdown()
        }
        retired
    }
}

/** New decoders wait by suspension while retired mpv instances drain; the UI never waits on JNI. */
internal object MpvRetirementFence {
    private val pending = mutableSetOf<CompletableDeferred<Unit>>()
    @Synchronized fun track(retirement: CompletableDeferred<Unit>) { pending += retirement }
    suspend fun await() {
        while (true) {
            val snapshot = synchronized(this) { pending.filterNot { it.isCompleted }.also { pending.retainAll(it.toSet()) } }
            if (snapshot.isEmpty()) return
            snapshot.forEach { it.await() }
        }
    }
}
