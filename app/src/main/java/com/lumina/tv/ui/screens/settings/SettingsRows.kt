package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.*
import com.lumina.tv.ui.theme.TypeScale

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocal
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
import com.lumina.tv.ui.theme.BasePreset
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.theme.TvMotion as Motion

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

fun Modifier.glassBorder(shape: Shape): Modifier = luminaBoundary(shape)

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
fun BaseThemeRow(
    focusKey: String,
    title: String,
    description: String,
    options: List<BasePreset>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
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
        showDivider = true,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                selectNext()
                true
            } else false
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEach { option ->
                val selected = option.key == selectedKey
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clickable { onSelected(option.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 40.dp)
                            .graphicsLayer { scaleX = 1f; scaleY = 1f }
                            .luminaStatus(option.bgTop, RoundedCornerShape(6.dp))
                            .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(6.dp)) else Modifier),
                    )
                    Text(
                        text = option.label,
                        color = TextSub,
                        fontSize = TypeScale.sp11,
                        maxLines = 1,
                    )
                }
            }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                fontSize = TypeScale.sp12,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 32.dp),
            )
        }
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
        Text(currentValue, color = TextSub, fontSize = TypeScale.sp12, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text("›", color = TextMain, fontSize = TypeScale.sp20)
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
                .heightIn(min = 32.dp)
                .widthIn(min = 72.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .luminaSurface(SurfaceLevel.Content, RoundedCornerShape(15.dp), tone = if (danger) SurfaceTone.Danger else SurfaceTone.Accent)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = actionLabel,
                color = if (danger) Danger else TextMain,
                fontSize = TypeScale.sp13,
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
            .luminaSurface(SurfaceLevel.Content, shape = shape)
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
            .heightIn(min = SettingsTokens.RowHeight)
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
            .luminaSurface(SurfaceLevel.Content, shape, focused = focused)

            .clickable(interactionSource = interactionSource, indication = null) {
                onClick()
            },
    ) {
        if (focused) {
            Box(
                Modifier
                    .width(SettingsTokens.FocusBarW)
                    .height(SettingsTokens.FocusBarH)
                    .align(Alignment.CenterStart)
                    .luminaStatus(Accent, shape = RoundedCornerShape(2.dp)),
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
                    maxLines = TvTokenValues.ROW_DESCRIPTION_LINES,
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
                    .luminaDivider(),
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
            .luminaStatus(if (checked) Accent else TvTokens.Boundary, RoundedCornerShape(10.dp)),
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(16.dp)
                .luminaStatus(if (checked) SelectedChoiceText else TextMain, CircleShape),
        )
    }
}

@Composable
private fun SegmentedChoiceControl(options: List<SettingChoiceOption>, selectedKey: String) {
    val pressScale = LocalSettingsPressScale.current
    Row(
        Modifier
            .height(28.dp)
            .luminaSurface(SurfaceLevel.Content, shape = RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            Box(
                Modifier
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .luminaStatus(if (selected) Accent else surfaceColor(SurfaceLevel.Content), RoundedCornerShape(14.dp))
                    .padding(horizontal = 11.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (selected) SelectedChoiceText else TextSub,
                    fontSize = TypeScale.sp11,
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
                        .luminaStatus(option.color ?: surfaceColor(SurfaceLevel.Content), CircleShape)
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
            fontSize = TypeScale.sp11,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp),
            maxLines = 1,
        )
    }
}
