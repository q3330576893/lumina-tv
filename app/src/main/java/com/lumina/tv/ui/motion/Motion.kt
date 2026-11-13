package com.lumina.tv.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

object Motion {
    const val PRESS = 140
    const val FOCUS = 260
    const val CATEGORY = 260
    const val SHIFT_CATEGORY = 0.08f
    const val SCALE_PRESSED = 0.97f
    val enterEasing = FastOutSlowInEasing
    val exitEasing = LinearOutSlowInEasing
}
