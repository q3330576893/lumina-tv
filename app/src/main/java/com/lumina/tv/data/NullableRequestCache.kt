package com.lumina.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caches successful values indefinitely and misses for a bounded retry interval. */
internal class NullableRequestCache<T : Any>(
    private val missTtlMs: Long,
    private val maxEntries: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val values = LinkedHashMap<String, T>()
    private val misses = LinkedHashMap<String, Long>()
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    suspend fun getOrLoad(key: String, load: suspend () -> T?): T? {
        while (true) {
            currentCoroutineContext().ensureActive()
            var cached = false
            var value: T? = null
            var owner = false
            var gate: CompletableDeferred<Unit>? = null
            synchronized(this) {
                if (values.containsKey(key)) {
                    cached = true
                    value = values[key]
                } else {
                    val expiresAt = misses[key]
                    if (expiresAt != null && expiresAt > nowMs()) {
                        cached = true
                    } else {
                        if (expiresAt != null) misses.remove(key)
                        gate = inFlight[key]
                        if (gate == null) {
                            val created = CompletableDeferred<Unit>()
                            inFlight[key] = created
                            gate = created
                            owner = true
                        }
                    }
                }
            }
            if (cached) return value
            val pending = checkNotNull(gate)
            if (!owner) {
                pending.await()
                continue
            }
            try {
                val result = load()
                currentCoroutineContext().ensureActive()
                synchronized(this) {
                    if (result == null) {
                        misses[key] = nowMs() + missTtlMs
                    } else {
                        values[key] = result
                        misses.remove(key)
                    }
                    if (values.size > maxEntries) values.clear()
                    if (misses.size > maxEntries) misses.clear()
                }
                return result
            } finally {
                synchronized(this) {
                    if (inFlight[key] === pending) inFlight.remove(key)
                }
                pending.complete(Unit)
            }
        }
    }
}
