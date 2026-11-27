package com.lumina.tv.player

import com.lumina.tv.data.MediaStream
import kotlin.math.roundToInt

/** An unknown actual selection never falls back to the server's default-track marker. */
internal fun selectedAudioForRouteChange(
    streams: List<MediaStream>,
    engine: String,
    media3SelectedIndex: Int?,
    mpvSelectedEmbyIndex: Int?,
): MediaStream? {
    val selectedIndex = if (engine == "mpv") mpvSelectedEmbyIndex else media3SelectedIndex
    return selectedIndex?.let { index -> streams.singleOrNull { it.index == index } }
}

internal fun audioRouteNeedsRecovery(selectionKnown: Boolean, support: AudioSupport): Boolean =
    selectionKnown && support == AudioSupport.UNSUPPORTED

internal fun mpvOutputVolumePercent(requestedPercent: Int, ducked: Boolean): Int =
    (requestedPercent.coerceIn(0, 100) * if (ducked) 0.2 else 1.0).roundToInt().coerceIn(0, 100)

