package com.lumina.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.lumina.tv.ui.theme.SurfaceLevel
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.theme.luminaSurface

enum class GlassTone { Normal, Accent }

/** Compatibility entry: never paints translucent white, reflections, or simulated frost. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassSurface(
    shape: Shape,
    tone: GlassTone = GlassTone.Normal,
    enabled: Boolean = ThemeRuntime.glassEnabled,
): Modifier = luminaSurface(SurfaceLevel.Content, shape, selected = tone == GlassTone.Accent)
