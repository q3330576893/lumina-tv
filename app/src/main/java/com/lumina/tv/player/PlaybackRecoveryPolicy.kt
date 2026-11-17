package com.lumina.tv.player

import org.json.JSONObject

internal enum class ContainerRecovery { MPV, TRANSCODE, TERMINAL }

/** Only for non-HLS container failures; HLS keeps its direct-stream recovery. */
internal fun containerRecovery(
    mpvPathAvailable: Boolean,
    serverTranscodingAvailable: Boolean,
    transcodeTried: Boolean,
): ContainerRecovery = when {
    // ExoPlayer 的解复用器覆盖面远窄于 ffmpeg；容器解析失败正是换内核收益最大的场景。
    // 因此先试未失败的 mpv 路径，再考虑服务器转码，即使服务器支持转码也一样。
    mpvPathAvailable -> ContainerRecovery.MPV
    serverTranscodingAvailable && !transcodeTried -> ContainerRecovery.TRANSCODE
    else -> ContainerRecovery.TERMINAL
}

internal fun playbackSourceJson(playbackInfoJson: String?, sourceId: String?): JSONObject? = runCatching {
    val sources = JSONObject(playbackInfoJson.orEmpty()).optJSONArray("MediaSources")
    (0 until (sources?.length() ?: 0)).mapNotNull { sources?.optJSONObject(it) }
        .firstOrNull { it.optString("Id") == sourceId }
}.getOrNull()

internal fun serverTranscodingAvailable(sourceJson: JSONObject?): Boolean = sourceJson != null &&
    sourceJson.optBoolean("SupportsTranscoding", false) && sourceJson.optString("TranscodingUrl").isNotBlank()

internal fun mpvAvailabilityDiagnostic(probed: Boolean, unavailableReason: String?): String = when {
    !probed -> "not_probed"
    unavailableReason != null -> "unavailable:$unavailableReason"
    else -> "available"
}

/** No Stop, diagnostics claiming a Stop, or requested-start fallback without a real sample. */
internal inline fun withStopPositionSample(
    observedPositionMs: Long?,
    lastPlayerPositionMs: Long?,
    onMissing: () -> Unit,
    report: (Long) -> Unit,
) {
    val positionMs = observedPositionMs ?: lastPlayerPositionMs
    if (positionMs == null) {
        onMissing()
        return
    }
    report(positionMs)
}
