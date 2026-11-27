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
    private var permitSequence = 0L
    private var activePermit: PlaybackLoadPermit? = null

    fun beginLoad(source: String?): Int {
        generation++
        activePermit = null
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

    fun grantPermit(load: Int, retirement: RetirementPermit): PlaybackLoadPermit? {
        if (!accepts(load) || !retirement.isValid()) return null
        return PlaybackLoadPermit(load, ++permitSequence, retirement).also { activePermit = it }
    }

    fun accepts(permit: PlaybackLoadPermit?): Boolean = permit != null &&
        permit.generation == generation && activePermit === permit && permit.retirement.isValid()

    /** Recheck generation and retirement authority at the commit point after all suspension. */
    fun commit(permit: PlaybackLoadPermit, apply: (playing: Boolean) -> Unit): Boolean {
        if (!accepts(permit)) return false
        val playing = playingFor(permit.generation) ?: return false
        apply(playing)
        return true
    }
}

internal class PlaybackLoadPermit internal constructor(
    internal val generation: Int,
    internal val sequence: Long,
    internal val retirement: RetirementPermit,
)

internal data class Http400RequestVariant(val stage: Int) {
    val removeSubtitleParams: Boolean get() = stage == 1
    val preferDirectStreamUrl: Boolean get() = stage == 2
}
