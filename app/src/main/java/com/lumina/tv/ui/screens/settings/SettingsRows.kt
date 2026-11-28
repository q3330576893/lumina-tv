package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumina.tv.ui.theme.*
import com.lumina.tv.ui.components.focusGutter

// Visual controls below are deliberately non-interactive children of one row focus target.
data class SettingChoiceOption(val key: String, val label: String, val color: Color? = null)
fun Modifier.glassBorder(shape: Shape): Modifier = luminaBoundary(shape)

@Composable
fun ToggleRow(
    focusKey: String, title: String, description: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, focusRequester: FocusRequester, leftRequester: FocusRequester,
    onFocused: (String) -> Unit, modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null, downRequester: FocusRequester? = null, showDivider: Boolean = true,
    enabled: Boolean = true, disabledReason: String? = null, nextCondition: String? = null,
) {
    SettingsRowFrame(focusKey, title, description, focusRequester, leftRequester, upRequester, downRequester,
        onFocused, { onCheckedChange(!checked) }, modifier, checked = checked, enabled = enabled,
        disabledReason = disabledReason, nextCondition = nextCondition,
        currentValue = if (checked) "已开启" else "已关闭") {
        Text(if (checked) "✓ 开启" else "○ 关闭", color = TextMain, style = TypeScale.body)
    }
}

@Composable
fun ChoiceRow(
    focusKey: String, title: String, description: String, options: List<SettingChoiceOption>, selectedKey: String,
    onSelected: (String) -> Unit, focusRequester: FocusRequester, leftRequester: FocusRequester,
    onFocused: (String) -> Unit, modifier: Modifier = Modifier,
    upRequester: FocusRequester? = null, downRequester: FocusRequester? = null, showDivider: Boolean = true,
    enabled: Boolean = true, disabledReason: String? = null, nextCondition: String? = null,
) {
    var open by remember(focusKey) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.key == selectedKey }?.label ?: "未设置"
    val details = SettingDeclarations.forRow(focusKey)?.summary.orEmpty()
    SettingsRowFrame(focusKey, title, description, focusRequester, leftRequester, upRequester, downRequester,
        onFocused, { open = true }, modifier, enabled = enabled && options.isNotEmpty(),
        disabledReason = disabledReason ?: if (options.isEmpty()) "暂无可用选项" else null,
        nextCondition = nextCondition ?: if (options.isEmpty()) "加载可用选项后重试" else null,
        currentValue = "$selectedLabel，${options.size} 个候选", actionLabel = "打开选择弹层") {
        if (!choiceUsesDialog(focusKey, options.size) && options.size <= 3 && options.all { it.label.length <= 4 }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    Text((if (option.key == selectedKey) "✓ " else "") + option.label,
                        color = if (option.key == selectedKey) TextMain else TextSub, style = TypeScale.caption)
                }
            }
        } else Text("$selectedLabel ›", color = TextMain, style = TypeScale.body, maxLines = 2)
    }
    if (open) SettingsDialog(focusKey, title, { open = false }) { close ->
        Text(description, color = TextSub, style = TypeScale.body)
        if (details.isNotEmpty()) Text(details, color = TextSub, style = TypeScale.caption)
        options.forEachIndexed { index, option ->
            val requester = remember(option.key) { FocusRequester() }
            if (option.key == selectedKey || (options.none { it.key == selectedKey } && index == 0)) {
                LaunchedEffect(Unit) { withFrameNanos { }; requester.requestFocus() }
            }
            SettingsOption("$focusKey:option:${option.key}", option.label, option.key == selectedKey, requester) {
                onSelected(option.key); close()
            }
        }
        SettingsDialogButton("$focusKey:close", "取消", onClick = close)
    }
}

@Composable
fun BaseThemeRow(focusKey: String, title: String, description: String, options: List<BasePreset>, selectedKey: String,
    onSelected: (String) -> Unit, focusRequester: FocusRequester, leftRequester: FocusRequester,
    onFocused: (String) -> Unit, modifier: Modifier = Modifier, upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null) = ChoiceRow(focusKey, title, description,
    options.map { SettingChoiceOption(it.key, it.label, it.bgTop) }, selectedKey, onSelected,
    focusRequester, leftRequester, onFocused, modifier, upRequester, downRequester)

@Composable
fun NavigationRow(focusKey: String, title: String, description: String, currentValue: String, onClick: () -> Unit,
    focusRequester: FocusRequester, leftRequester: FocusRequester, onFocused: (String) -> Unit,
    modifier: Modifier = Modifier, upRequester: FocusRequester? = null, downRequester: FocusRequester? = null,
    showDivider: Boolean = true) {
    SettingsRowFrame(focusKey, title, description, focusRequester, leftRequester, upRequester, downRequester,
        onFocused, onClick, modifier, currentValue = currentValue, actionLabel = "打开弹层") {
        Text("$currentValue ›", color = TextMain, style = TypeScale.body, maxLines = 2)
    }
}

@Composable
fun ActionRow(focusKey: String, title: String, description: String, actionLabel: String, onClick: () -> Unit,
    focusRequester: FocusRequester, leftRequester: FocusRequester, onFocused: (String) -> Unit,
    modifier: Modifier = Modifier, danger: Boolean = false, upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null, showDivider: Boolean = true, enabled: Boolean = true,
    disabledReason: String? = null, nextCondition: String? = null) {
    SettingsRowFrame(focusKey, title, description, focusRequester, leftRequester, upRequester, downRequester,
        onFocused, onClick, modifier, currentValue = actionLabel, actionLabel = actionLabel,
        enabled = enabled, disabledReason = disabledReason, nextCondition = nextCondition) {
        Text(actionLabel, color = if (danger) Color(0xFFFF8A9A) else TextMain, style = TypeScale.body)
    }
}

@Composable
private fun SettingsRowFrame(
    focusKey: String, title: String, description: String, focusRequester: FocusRequester,
    leftRequester: FocusRequester, upRequester: FocusRequester?, downRequester: FocusRequester?,
    onFocused: (String) -> Unit, onClick: () -> Unit, modifier: Modifier,
    currentValue: String, actionLabel: String = "切换", checked: Boolean? = null,
    enabled: Boolean = true, disabledReason: String? = null, nextCondition: String? = null,
    control: @Composable () -> Unit,
) {
    require(enabled || (!disabledReason.isNullOrBlank() && !nextCondition.isNullOrBlank()))
    var focused by remember(focusKey) { mutableStateOf(false) }
    var detailsOpen by remember(focusKey) { mutableStateOf(false) }
    val declaration = SettingDeclarations.forRow(focusKey)
    val disabledText = if (enabled) "" else "禁用：$disabledReason；下一步：$nextCondition"
    val detailText = listOf(description, declaration?.summary.orEmpty(), disabledText).filter { it.isNotBlank() }.joinToString("\n\n")
    val inDialog = LocalSettingsFocusContext.current.expansionState.startsWith("dialog:")
    val shape = RoundedCornerShape(TvTokens.ContentCorner)
    val activation = if (checked != null) Modifier.toggleable(value = checked, enabled = enabled,
        role = Role.Switch, onValueChange = { onClick() })
        else Modifier.clickable(enabled = enabled, onClickLabel = actionLabel, onClick = onClick)
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val gutter = focusGutter(maxWidth, TvTokenValues.FOCUS_SCALE)
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp).heightIn(min = 100.dp)
        .settingsFocusTarget(focusKey, focusRequester, onFocused)
        .focusProperties { if (!inDialog) { left = leftRequester; upRequester?.let { up = it }; downRequester?.let { down = it } } }
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) { detailsOpen = true; true } else false
        }
        .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
        .luminaSurface(SurfaceLevel.Content, shape, focused = focused)
        .semantics(mergeDescendants = true) {
            contentDescription = "$title，$description"
            stateDescription = listOf(currentValue, declaration?.summary.orEmpty(), disabledText).filter { it.isNotBlank() }.joinToString("，")
            customActions = listOf(CustomAccessibilityAction("查看完整说明与生效范围") { detailsOpen = true; true })
        }.then(activation).then(if (!enabled) Modifier.focusable() else Modifier)
        .padding(horizontal = SettingsTokens.RowPaddingH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextMain, fontSize = SettingsTokens.RowTitleSize, fontWeight = FontWeight.Medium)
            // Keep two readable lines; complete descriptions and URLs are available by Menu or TalkBack action.
            Text(if (description.length > 50) "详细说明请按菜单键查看；返回后仍保留当前设置。" else description,
                color = TextSub, fontSize = SettingsTokens.RowDescSize, minLines = 2, maxLines = 2)
            if (declaration != null) Text("${declaration.scope.label} · ${if (declaration.requiresNewLoad) "新 load 生效" else if (declaration.currentPlaybackImmediate) "当前播放即时生效" else declaration.timing} · 菜单键查看默认值",
                color = TextSub, style = TypeScale.caption)
            if (!enabled) Text(disabledText, color = TextSub, style = TypeScale.caption)
        }
        Box(Modifier.width(168.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) { control() }
    }
    }
    if (detailsOpen) SettingsDialog(focusKey, title, { detailsOpen = false }) { close ->
        Text(detailText, color = TextMain, style = TypeScale.body)
        SettingsDialogButton("$focusKey:details-close", "关闭", initialFocus = true, onClick = close)
    }
}

@Composable
internal fun SettingsOption(key: String, label: String, selected: Boolean, requester: FocusRequester,
    onClick: () -> Unit) {
    var focused by remember(key) { mutableStateOf(false) }
    Text((if (selected) "✓ " else "○ ") + label, color = TextMain, style = TypeScale.body,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).settingsFocusTarget(key, requester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
            .luminaSurface(SurfaceLevel.Overlay, RoundedCornerShape(TvTokens.ContentCorner), focused, selected)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(12.dp))
}

@Composable
internal fun SettingsDialogButton(key: String, label: String, initialFocus: Boolean = false, onClick: () -> Unit) {
    val requester = remember(key) { FocusRequester() }
    if (initialFocus) LaunchedEffect(key) { withFrameNanos { }; requester.requestFocus() }
    var focused by remember(key) { mutableStateOf(false) }
    Text(label, color = TextMain, style = TypeScale.body,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).settingsFocusTarget(key, requester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
            .luminaSurface(SurfaceLevel.Overlay, RoundedCornerShape(TvTokens.ContentCorner), focused = focused)
            .clickable(onClick = onClick).padding(12.dp))
}
