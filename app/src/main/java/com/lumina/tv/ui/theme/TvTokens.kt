package com.lumina.tv.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight

object TvTokens {
    val FocusBorder = TvTokenValues.FOCUS_BORDER_DP.dp
    val ContentCorner = TvTokenValues.CONTENT_RADIUS_DP.dp
    val GroupCorner = TvTokenValues.GROUP_RADIUS_DP.dp
    val ModalCorner = TvTokenValues.MODAL_RADIUS_DP.dp
    val RowHeight = 58.dp
    val RowPadding = 18.dp
    val Boundary = androidx.compose.ui.graphics.Color(0xFF79879C)
    fun safeInset(width: Dp): Dp = TvTokenValues.safeInset(width.value).dp
}

/** Adapts Material text fields/buttons to the same immediate 3dp focus boundary. */
fun Modifier.tvFocusBoundary(shape: Shape = ShapeScale.card): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.luminaBoundary(shape, focused = true) else Modifier)
}

/** Same padding placement as Modifier.padding, calculated from the parent's available width. */
fun Modifier.tvPagePadding(vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val available = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else 1200.dp
    val horizontalPx = TvTokens.safeInset(available).roundToPx()
    val verticalPx = vertical.roundToPx()
    val child = measurable.measure(constraints.offset(-2 * horizontalPx, -2 * verticalPx))
    layout(constraints.constrainWidth(child.width + 2 * horizontalPx),
        constraints.constrainHeight(child.height + 2 * verticalPx)) {
        child.placeRelative(horizontalPx, verticalPx)
    }
}

object TvMotion {
    val PRESS: Int get() = ThemeRuntime.motion.duration(TvTokenValues.PRESS_MS)
    val FOCUS: Int get() = ThemeRuntime.motion.duration(TvTokenValues.FOCUS_MS)
    val CATEGORY: Int get() = ThemeRuntime.motion.duration(TvTokenValues.PAGE_MS)
    val SHIFT_CATEGORY: Float get() = if (ThemeRuntime.motion.reduced) 0f else 0.08f
    val SCALE_PRESSED: Float get() = if (ThemeRuntime.motion.reduced) 1f else 0.97f
    val enterEasing = FastOutSlowInEasing
    val exitEasing = LinearOutSlowInEasing
}
