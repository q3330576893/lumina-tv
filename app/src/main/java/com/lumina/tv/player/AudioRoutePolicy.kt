package com.lumina.tv.player

import com.lumina.tv.data.MediaStream

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

