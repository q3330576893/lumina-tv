package com.lumina.tv.player

import com.lumina.tv.diag.PageLog

/** Media3 owns audio focus; this class only provides a common diagnostic event sink. */
object AudioFocusGuard {
    fun record(event: String) = PageLog.add("audioFocus event=$event owner=media3")
}
