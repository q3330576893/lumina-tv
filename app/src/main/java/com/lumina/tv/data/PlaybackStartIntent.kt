package com.lumina.tv.data

sealed interface PlaybackStartIntent {
    data object FromStart : PlaybackStartIntent
    data object Resume : PlaybackStartIntent
    data class Restore(val position: Long) : PlaybackStartIntent {
        init { require(position >= 0L) }
    }

    fun positionMs(cachedPositionMs: Long, serverPositionMs: Long? = null): Long = when (this) {
        FromStart -> 0L
        Resume -> (serverPositionMs ?: cachedPositionMs).coerceAtLeast(0L)
        is Restore -> position
    }
}
