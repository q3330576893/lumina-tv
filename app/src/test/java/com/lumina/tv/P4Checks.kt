package com.lumina.tv

import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.PlayableItemRequestGate
import com.lumina.tv.data.PlayableItemResult
import com.lumina.tv.data.resolvePlayableItem
import kotlinx.coroutines.runBlocking

object P4Checks {
    @JvmStatic fun main(args: Array<String>) {
        com.lumina.tv.player.P4TrackChecks.main(args)
        com.lumina.tv.data.P4NegotiationChecks.main(args)
        com.lumina.tv.data.T10TrackMemoryChecks.main(args)
        com.lumina.tv.data.P4PlayableItemChecks.main(args)
        missingMedia()
        cancelledSourceRequest()
    }

    private fun missingMedia() = runBlocking {
        for (type in listOf("Movie", "Episode", "Series")) {
            val result = resolvePlayableItem(
                MediaItem("missing-$type", "Missing", type, isMissing = true, sourceCount = 2),
                loadEpisodes = { error("Missing media must be rejected before episode lookup") },
                probeSource = { error("Missing media must be rejected before source lookup") },
            )
            check(result == PlayableItemResult.Unavailable)
        }
        println("P4 BATCH-6 PASS: missing movies, episodes and series rejected before lookup")
    }

    private fun cancelledSourceRequest() {
        val gate = PlayableItemRequestGate().apply { updateContext("detail:episode") }
        val cancelled = checkNotNull(gate.begin())
        gate.cancel() // Picking auto or closing a still-loading menu releases this generation.
        val retry = checkNotNull(gate.begin())
        check(!gate.accepts(cancelled))
        gate.finish(cancelled) // The cancelled job's finally must not unlock the retry.
        check(gate.accepts(retry) && gate.begin() == null)
        gate.finish(retry)
        check(gate.begin() != null)
    }
}
