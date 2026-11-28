package com.lumina.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

enum class SurfaceTone { Neutral, Raised, Accent, Success, Danger }
enum class BackdropShade { Hero, Detail, Login, Darken, Modal, Bottom, PosterTitle }

fun surfaceColor(level: SurfaceLevel, focused: Boolean = false, selected: Boolean = false,
    tone: SurfaceTone = SurfaceTone.Neutral): Color {
    val base = when (level) {
        SurfaceLevel.Canvas -> Ink
        SurfaceLevel.Content -> Color(0xFF141B2B)
        SurfaceLevel.Floating -> Color(0xFF1B2438)
        SurfaceLevel.Overlay -> Color(0xFF111827)
    }
    val tint = when {
        tone == SurfaceTone.Danger -> Color(0xFFFF8A9A).copy(alpha = 0.10f)
        tone == SurfaceTone.Success -> Color(0xFF57E3C6).copy(alpha = 0.10f)
        selected || tone == SurfaceTone.Accent -> Accent.copy(alpha = 0.10f)
        focused || tone == SurfaceTone.Raised -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    return tint.compositeOver(base).copy(alpha = 1f)
}

/** Static material entry. It cannot acquire blur, including when used on a browsing page. */
fun Modifier.luminaSurface(
    level: SurfaceLevel = SurfaceLevel.Content,
    shape: Shape = RectangleShape,
    focused: Boolean = false,
    selected: Boolean = false,
    tone: SurfaceTone = SurfaceTone.Neutral,
    outline: Boolean = false,
): Modifier = background(surfaceColor(level, focused, selected, tone), shape)
    .then(if (focused || outline) Modifier.border(if (focused) TvTokens.FocusBorder else 1.dp,
        if (focused) Color.White else TvTokens.Boundary, shape) else Modifier)

fun Modifier.luminaBoundary(shape: Shape, focused: Boolean = false): Modifier =
    border(if (focused) TvTokens.FocusBorder else 1.dp,
        if (focused) Color.White else TvTokens.Boundary, shape)

/** Status colors and dividers are distinct from materials and never request glass effects. */
fun Modifier.luminaStatus(color: Color, shape: Shape = RectangleShape): Modifier =
    background(color.compositeOver(surfaceColor(SurfaceLevel.Content)), shape)

fun Modifier.luminaDivider(shape: Shape = RectangleShape): Modifier =
    background(TvTokens.Boundary, shape)

/** A single static image/video mask, including in OFF. No blur or animated highlight. */
fun Modifier.luminaScrim(shade: BackdropShade): Modifier {
    if (shade == BackdropShade.Modal) return background(Color.Black.copy(alpha = 0.70f))
    val colors = when (shade) {
        BackdropShade.Detail -> listOf(Color(0x66000000), Color(0x99000000), Color(0xF2000000))
        BackdropShade.Login -> listOf(Color(0xE6000000), Color(0xF2000000))
        BackdropShade.Darken -> listOf(Color(0x99000000), Color(0x99000000))
        BackdropShade.Hero -> listOf(Color(0x33000000), Color(0x55000000), Ink)
        BackdropShade.Bottom, BackdropShade.PosterTitle -> listOf(Color.Transparent, Color(0xE6000000))
        BackdropShade.Modal -> error("handled above")
    }
    return background(Brush.verticalGradient(colors))
}

private object FloatingBlurLeases {
    val budget = BlurBudget()
    var revision by mutableIntStateOf(0)
    fun request(owner: Any) { budget.request(owner); revision++ }
    fun release(owner: Any) { budget.release(owner); revision++ }
}

/** Four-layer container. Floating blur is opt-in, capability-gated, and limited to one lease.
 * The backing Box matches the measured content, so fallback never changes focus dimensions. */
@Composable
fun LuminaSurface(
    level: SurfaceLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TvTokens.ContentCorner),
    focused: Boolean = false,
    tone: SurfaceTone = SurfaceTone.Neutral,
    hazeState: HazeState? = null,
    allowBlur: Boolean = false,
    floatingEffect: Boolean = allowBlur,
    content: @Composable BoxScope.() -> Unit,
) {
    val tier = ThemeRuntime.effectTier
    val candidate = level == SurfaceLevel.Floating && floatingEffect && hazeState != null
    val eligible = candidate && allowBlur && tier == EffectTier.FULL
    val owner = remember { Any() }
    DisposableEffect(eligible) {
        if (eligible) FloatingBlurLeases.request(owner)
        onDispose { FloatingBlurLeases.release(owner) }
    }
    @Suppress("UNUSED_VARIABLE") val revision = FloatingBlurLeases.revision
    val policy = surfacePolicy(tier, level, candidate, eligible && FloatingBlurLeases.budget.granted(owner))
    val fill = surfaceColor(level, focused, tone = tone)
    Box(modifier.clip(shape)) {
        Box(Modifier.matchParentSize().then(
            if (policy.blur) Modifier.hazeEffect(state = requireNotNull(hazeState), style = HazeStyle(
                backgroundColor = fill,
                tint = HazeTint(fill.copy(alpha = policy.opacity)),
                blurRadius = 16.dp,
                noiseFactor = 0f,
            )) else Modifier.background(fill.copy(alpha = policy.opacity))
        ))
        content()
        if (focused || policy.highlight) Box(Modifier.matchParentSize().border(
            if (focused) TvTokens.FocusBorder else 1.dp,
            if (focused) Color.White else TvTokens.Boundary, shape))
    }
}
