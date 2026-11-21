package com.lumina.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread authority for load ownership, user intent and the active source's request variant. */
internal class PlaybackLoadCoordinator(initialSourceId: String? = null) {
    var generation by mutableIntStateOf(0)
    var desiredPlaying by mutableStateOf(true)
    var badHttpFallbackStage by mutableIntStateOf(0)
        private set
    var sourceId: String? = initialSourceId
        private set
    val requestVariant: Http400RequestVariant get() = Http400RequestVariant(badHttpFallbackStage)

    fun beginLoad(source: String?): Int {
        generation++
        selectSource(source)
        return generation
    }

    fun accepts(load: Int): Boolean = generation == load

    /** Bind an initially unknown source, or isolate a server-selected replacement. */
    fun resolveSource(load: Int, source: String?): Boolean {
        if (!accepts(load)) return false
        selectSource(source)
        return true
    }

    private fun selectSource(source: String?) {
        if (sourceId != source) {
            sourceId = source
            badHttpFallbackStage = 0
        }
    }

    fun advanceHttp400(load: Int): Boolean {
        if (!accepts(load) || badHttpFallbackStage >= 2) return false
        badHttpFallbackStage++
        return true
    }

    fun playingFor(load: Int): Boolean? = desiredPlaying.takeIf { accepts(load) }

    /** Read at the commit point, never before a suspension such as negotiation/Surface waiting. */
    fun commit(load: Int, apply: (playing: Boolean) -> Unit): Boolean {
        val playing = playingFor(load) ?: return false
        apply(playing)
        return true
    }
}

internal data class Http400RequestVariant(val stage: Int) {
    val removeSubtitleParams: Boolean get() = stage == 1
    val preferDirectStreamUrl: Boolean get() = stage == 2
}
