package com.lumina.tv.ui.sound

import android.view.SoundEffectConstants
import android.view.View
import android.os.SystemClock

enum class KeySoundMode { Off, Dpad, All }

object KeySound {
    private var lastFocusMoveAt = 0L
    private var lastConfirmAt = 0L

    fun playFocusMove(view: View) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusMoveAt < 40L) return
        lastFocusMoveAt = now
        runCatching { view.playSoundEffect(SoundEffectConstants.NAVIGATION_UP) }
    }

    fun playConfirm(view: View) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfirmAt < 40L) return
        lastConfirmAt = now
        runCatching { view.playSoundEffect(SoundEffectConstants.CLICK) }
    }
}
