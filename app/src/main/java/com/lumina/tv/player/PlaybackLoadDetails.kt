package com.lumina.tv.player

import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.EmbyException
import com.lumina.tv.data.PlaybackInfoCapture
import androidx.media3.common.PlaybackException
import org.json.JSONObject

internal data class PlaybackLoadKey(val generation: Int, val itemId: String, val sourceId: String?) {
    fun json(): JSONObject = JSONObject()
        .put("generation", generation).put("itemId", itemId).put("sourceId", sourceId ?: JSONObject.NULL)
}

/** Request and error details belong to one logical load, even if the physical backend is released. */
internal class PlaybackLoadDetails(key: PlaybackLoadKey) {
    var key = key
        private set
    val playbackInfoCapture = PlaybackInfoCapture()
    var currentPlaybackUrl: String? = null
        private set
    var currentPlayMethod = "pending"
        private set
    var negotiationDiagnostics = state("pending")
        private set
    var rangeProbeDiagnostics = state("pending")
    var lastPlaybackErrorJson: String? = null
    var mpvInitializationError: String? = null
    private var loadFailure: JSONObject? = null
    var failureThrowable: Throwable? = null
        private set
    var media3Failure: PlaybackException? = null
        private set
    var mediaPeriodId: String? = null
        private set
    var failureElapsedRealtimeMs: Long? = null
        private set

    fun bindSource(sourceId: String) {
        key = key.copy(sourceId = sourceId)
    }

    fun acceptTarget(target: EmbyClient.PlaybackTarget) {
        bindSource(target.source.id)
        currentPlaybackUrl = target.url
        currentPlayMethod = target.playMethod
        negotiationDiagnostics = target.negotiationDiagnostics.put("state", "ready")
        playbackInfoCapture.latest = target.playbackInfo
    }

    fun recordFailure(failure: Exception) {
        failureThrowable = failure
        failureElapsedRealtimeMs = System.nanoTime() / 1_000_000L
        val httpStatus = generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<EmbyException>().firstNotNullOfOrNull { it.httpStatus }
        val details = state("failed").put("exceptionType", failure.javaClass.simpleName)
            .put("httpStatus", httpStatus ?: JSONObject.NULL)
        loadFailure = details
        if (currentPlayMethod == "pending") {
            currentPlayMethod = "unavailable"
            negotiationDiagnostics = details
        }
        if (rangeProbeDiagnostics.optString("state") == "pending") rangeProbeDiagnostics = state("unavailable")
    }

    fun recordMedia3Failure(failure: PlaybackException, failedPeriodId: String?) {
        failureThrowable = failure
        media3Failure = failure
        mediaPeriodId = failedPeriodId
        failureElapsedRealtimeMs = System.nanoTime() / 1_000_000L
    }

    fun playbackInfoJson(expected: PlaybackLoadKey): String? =
        if (key == expected) {
            playbackInfoCapture.latest?.takeIf { it.belongsTo(expected.itemId, expected.sourceId) }?.json
        } else null

    fun snapshot(expected: PlaybackLoadKey): PlaybackLoadDetailSnapshot {
        val current = key == expected
        val info = playbackInfoCapture.latest
        val infoCurrent = current && info?.belongsTo(expected.itemId, expected.sourceId) == true
        val infoState = when {
            !current || (info != null && !infoCurrent) -> "stale"
            infoCurrent -> "ready"
            currentPlayMethod == "pending" -> "pending"
            else -> "unavailable"
        }
        val playbackInfoJson = (if (infoCurrent) JSONObject(checkNotNull(info).json) else JSONObject())
            .put("state", infoState).put("load", expected.json()).toString(2)
        val urlType = when {
            !current -> "stale"
            currentPlaybackUrl?.contains(".m3u8", true) == true -> "hls"
            currentPlaybackUrl != null -> "static"
            currentPlayMethod == "pending" -> "pending"
            else -> "unavailable"
        }
        return PlaybackLoadDetailSnapshot(
            key = expected,
            current = current,
            urlType = urlType,
            playMethod = if (current) currentPlayMethod else "stale",
            playbackInfoJson = playbackInfoJson,
            negotiation = if (current) negotiationDiagnostics else state("stale"),
            rangeProbe = if (current) rangeProbeDiagnostics else state("stale"),
            media3Error = if (current) lastPlaybackErrorJson?.let { JSONObject(it) } else null,
            loadFailure = if (current) loadFailure else null,
            mpvInitializationError = if (current) mpvInitializationError else null,
            failureThrowable = if (current) failureThrowable else null,
            media3Failure = if (current) media3Failure else null,
            mediaPeriodId = if (current) mediaPeriodId else null,
            failureElapsedRealtimeMs = if (current) failureElapsedRealtimeMs else null,
        )
    }

    private fun state(value: String) = JSONObject().put("state", value)
}

internal data class PlaybackLoadDetailSnapshot(
    val key: PlaybackLoadKey,
    val current: Boolean,
    val urlType: String,
    val playMethod: String,
    val playbackInfoJson: String,
    val negotiation: JSONObject,
    val rangeProbe: JSONObject,
    val media3Error: JSONObject?,
    val loadFailure: JSONObject?,
    val mpvInitializationError: String?,
    val failureThrowable: Throwable?,
    val media3Failure: PlaybackException?,
    val mediaPeriodId: String?,
    val failureElapsedRealtimeMs: Long?,
) {
    fun playbackJson(): JSONObject = JSONObject().put("urlType", urlType)
        .put("negotiation", negotiation).put("rangeProbe", rangeProbe)

    fun errorsJson(mpvError: String? = mpvInitializationError, pageError: String? = null): JSONObject =
        JSONObject().put("load", key.json())
        .put("state", when {
            !current -> "stale"
            media3Error != null || loadFailure != null || mpvError != null || pageError != null -> "ready"
            playMethod == "pending" -> "pending"
            else -> "unavailable"
        })
        .put("media3", media3Error ?: JSONObject.NULL)
        .put("loadFailure", loadFailure ?: JSONObject.NULL)
        .put("lastPlaybackErrorCodeName", media3Error?.optString("errorCodeName") ?: JSONObject.NULL)
        .put("mpv", mpvError ?: "No mpv error recorded")
        .put("mpvErrorPresent", mpvError != null)
}
