package com.lumina.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounds successful values and misses separately; cache hits refresh recency, not the miss TTL. */
internal class NullableRequestCache<T : Any>(
    private val missTtlMs: Long,
    private val maxEntries: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(missTtlMs >= 0L) { "missTtlMs must not be negative" }
    }

    private val values = LinkedHashMap<String, T>(16, 0.75f, true)
    private val misses = LinkedHashMap<String, Long>(16, 0.75f, true)
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
                    val now = nowMs()
                    misses.entries.removeAll { it.value <= now }
                    if (result == null) {
                        misses[key] = if (now > Long.MAX_VALUE - missTtlMs) Long.MAX_VALUE else now + missTtlMs
                    } else {
                        values[key] = result
                        misses.remove(key)
                    }
                    trimToCapacity(values)
                    trimToCapacity(misses)
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

    private fun <V> trimToCapacity(entries: LinkedHashMap<String, V>) {
        val oldest = entries.entries.iterator()
        while (entries.size > maxEntries && oldest.hasNext()) {
            oldest.next()
            oldest.remove()
        }
    }
}
