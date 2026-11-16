package com.lumina.tv.player

/** The token is captured at ingress and checked again after dispatch/delay. */
internal class LoadEventGate<T : Any> {
    @Volatile var current: T? = null
        private set
    @Volatile var closed: Boolean = false
        private set

    fun activate(token: T) {
        check(!closed)
        current = token
    }

    fun accepts(token: T): Boolean = !closed && current === token
    fun retire(token: T) { if (current === token) current = null }
    fun close() { closed = true; current = null }
}
