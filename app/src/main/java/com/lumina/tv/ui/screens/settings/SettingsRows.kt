package com.lumina.tv.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocal
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.motion.Motion
import com.lumina.tv.ui.sound.KeySound

internal val LocalSettingsFocusKeys = staticCompositionLocalOf<MutableMap<String, FocusRequester>?> { null }
internal val LocalSettingsListStates = staticCompositionLocalOf<Map<SettingsCategory, LazyListState>?> { null }
private val LocalSettingsPressScale = staticCompositionLocalOf { 1f }

@Composable
internal fun settingsListState(category: SettingsCategory): LazyListState =
    requireNotNull(LocalSettingsListStates.current?.get(category))

private val DividerColor = Color(0x14FFFFFF)
private val SelectedChoiceText = Color(0xFF0B1018)
private val Danger = Color(0xFFFF8A9A)

data class SettingChoiceOption(
    val key: String,
    val label: String,
    val color: Color? = null,
)

fun Modifier.glassBorder(shape: Shape): Modifier = border(
    BorderStroke(
        1.dp,
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.78f),
                0.38f to Color.White.copy(alpha = 0.10f),
                0.62f to Color.White.copy(alpha = 0.06f),
                1f to Color.White.copy(alpha = 0.42f),
            ),
        ),
    ),
    shape,
)

@Composable
fun ToggleRow(
    focusKey: String,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    showDivider: Boolean = true,
) {
    SettingsRowFrame(
        focusKey = focusKey,
        title = title,
        description = description,
        focusRequester = focusRequester,
        leftRequester = leftRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        onFocused = onFocused,
        onClick = { onCheckedChange(!checked) },
        showDivider = showDivider,
        modifier = modifier,
    ) {
        ToggleControl(checked)
    }
}

@Composable
fun ChoiceRow(
    focusKey: String,
    title: String,
    description: String,
    options: List<SettingChoiceOption>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    showDivider: Boolean = true,
) {
    val selectNext = {
        if (options.isNotEmpty()) {
            val currentIndex = options.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
            onSelected(options[(currentIndex + 1) % options.size].key)
        }
    }
    SettingsRowFrame(
        focusKey = focusKey,
        title = title,
        description = description,
        focusRequester = focusRequester,
        leftRequester = leftRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        onFocused = onFocused,
        onClick = selectNext,
        showDivider = showDivider,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                selectNext()
                true
            } else {
                false
            }
        },
    ) {
        if (options.any { it.color != null }) {
            ColorChoiceControl(options, selectedKey)
        } else {
            SegmentedChoiceControl(options, selectedKey)
        }
    }
}

@Composable
fun ValueRow(
    focusKey: String,
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onActivate: () -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    showDivider: Boolean = true,
) {
    SettingsRowFrame(
        focusKey = focusKey,
        title = title,
        description = description,
        focusRequester = focusRequester,
        leftRequester = leftRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        onFocused = onFocused,
        onClick = onActivate,
        showDivider = showDivider,
        modifier = modifier,
    ) {
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .coerceIn(0f, 1f)
        Canvas(Modifier.width(160.dp).height(20.dp)) {
            val centerY = size.height / 2f
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                color = Accent,
                start = Offset(0f, centerY),
                end = Offset(size.width * fraction, centerY),
                strokeWidth = 3.dp.toPx(),
            )
            drawCircle(
                color = Accent,
                radius = 7.dp.toPx(),
                center = Offset(size.width * fraction, centerY),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = valueLabel,
            color = TextMain,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
fun NavigationRow(
    focusKey: String,
    title: String,
    description: String,
    currentValue: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    showDivider: Boolean = true,
) {
    SettingsRowFrame(
        focusKey = focusKey,
        title = title,
        description = description,
        focusRequester = focusRequester,
        leftRequester = leftRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        onFocused = onFocused,
        onClick = onClick,
        showDivider = showDivider,
        modifier = modifier,
    ) {
        Text(currentValue, color = TextSub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text("›", color = TextMain, fontSize = 20.sp)
    }
}

@Composable
fun ActionRow(
    focusKey: String,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    showDivider: Boolean = true,
) {
    SettingsRowFrame(
        focusKey = focusKey,
        title = title,
        description = description,
        focusRequester = focusRequester,
        leftRequester = leftRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        onFocused = onFocused,
        onClick = onClick,
        showDivider = showDivider,
        modifier = modifier,
        titleColor = if (danger) Danger else TextMain,
    ) {
        val pressScale = LocalSettingsPressScale.current
        Box(
            Modifier
                .height(30.dp)
                .widthIn(min = 72.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .background(
                    (if (danger) Danger else Accent).copy(alpha = 0.18f),
                    RoundedCornerShape(15.dp),
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = actionLabel,
                color = if (danger) Danger else TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun PreviewCard(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(ThemeRuntime.frostTint, shape)
            .glassBorder(shape),
    )
}

@Composable
private fun SettingsRowFrame(
    focusKey: String,
    title: String,
    description: String,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    showDivider: Boolean,
    modifier: Modifier,
    titleColor: Color = TextMain,
    control: @Composable BoxScope.() -> Unit,
) {
    var focused by remember(focusKey) { mutableStateOf(false) }
    val view = LocalView.current
    val focusKeys = LocalSettingsFocusKeys.current
    val interactionSource = remember(focusKey) { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) Motion.SCALE_PRESSED else 1f,
        animationSpec = tween(Motion.PRESS),
        label = "settingsControlPress",
    )
    DisposableEffect(focusKey, focusRequester, focusKeys) {
        focusKeys?.set(focusKey, focusRequester)
        onDispose {
            if (focusKeys?.get(focusKey) === focusRequester) focusKeys.remove(focusKey)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(SettingsTokens.RowHeight)
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftRequester
                if (upRequester != null) up = upRequester
                if (downRequester != null) down = downRequester
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused(focusKey)
                }
            }
            .background(if (focused) ThemeRuntime.frostStrongTint else Color.Transparent, shape)
            .then(if (focused) Modifier.glassBorder(shape) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null) {
                if (AppRuntime.prefs?.keySound == "all") KeySound.playConfirm(view)
                onClick()
            },
    ) {
        if (focused) {
            Box(
                Modifier
                    .width(SettingsTokens.FocusBarW)
                    .height(SettingsTokens.FocusBarH)
                    .align(Alignment.CenterStart)
                    .background(Accent, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = SettingsTokens.RowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = SettingsTokens.RowTitleSize,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = description,
                    color = TextSub,
                    fontSize = SettingsTokens.RowDescSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            CompositionLocalProvider(LocalSettingsPressScale provides pressScale) {
                Box(contentAlignment = Alignment.CenterEnd, content = control)
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = SettingsTokens.RowPaddingH)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(DividerColor),
            )
        }
    }
}

@Composable
private fun ToggleControl(checked: Boolean) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 2.dp,
        animationSpec = tween(Motion.FOCUS),
        label = "settingsToggleThumb",
    )
    Box(
        Modifier
            .size(width = 34.dp, height = 20.dp)
            .background(
                if (checked) Accent else Color.White.copy(alpha = 0.18f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(16.dp)
                .background(if (checked) SelectedChoiceText else TextMain, CircleShape),
        )
    }
}

@Composable
private fun SegmentedChoiceControl(options: List<SettingChoiceOption>, selectedKey: String) {
    val pressScale = LocalSettingsPressScale.current
    Row(
        Modifier
            .height(28.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            Box(
                Modifier
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 11.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (selected) SelectedChoiceText else TextSub,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColorChoiceControl(options: List<SettingChoiceOption>, selectedKey: String) {
    val selectedLabel = options.firstOrNull { it.key == selectedKey }?.label.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            Box(
                Modifier.size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (selected) 18.dp else 14.dp)
                        .background(option.color ?: Color.Transparent, CircleShape)
                        .then(
                            if (selected) Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier,
                        ),
                )
            }
            Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = selectedLabel,
            color = TextMain,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp),
            maxLines = 1,
        )
    }
}
