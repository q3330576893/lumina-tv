package com.lumina.tv.data

class PlaybackInfoSnapshot(val itemId: String, val sourceId: String, val json: String) {
    internal fun belongsTo(itemId: String, sourceId: String?): Boolean =
        this.itemId == itemId && this.sourceId == sourceId
}

/** One capture per load. The IO request can publish its response even when route selection fails. */
class PlaybackInfoCapture {
    @Volatile var latest: PlaybackInfoSnapshot? = null
        internal set
}
