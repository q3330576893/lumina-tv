package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.lumina.tv.ui.components.FrostPanel
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import dev.chrisbanes.haze.HazeState

enum class SettingsCategory(val key: String, val label: String) {
    Account("account", "账号与服务器"),
    Appearance("appearance", "外观"),
    Home("home", "首页"),
    Playback("playback", "播放"),
    Subtitles("subtitles", "字幕"),
    Compatibility("compat", "播放器与兼容性"),
    About("about", "关于与诊断"),
}

@Composable
fun SideRail(
    selectedCategory: SettingsCategory,
    railWidth: Dp,
    hazeState: HazeState,
    selectedRequester: FocusRequester,
    contentRequester: FocusRequester,
    onCategorySelected: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val railShape = RoundedCornerShape(SettingsTokens.GroupCorner)
    FrostPanel(
        modifier = modifier.width(railWidth).fillMaxHeight(),
        hazeState = hazeState,
        corner = SettingsTokens.GroupCorner,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .glassBorder(railShape)
                .focusGroup()
                .padding(horizontal = 10.dp, vertical = 16.dp),
        ) {
            Text(
                text = "设置",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsCategory.entries.forEach { category ->
                    val enabled = category == SettingsCategory.Appearance
                    SideRailItem(
                        category = category,
                        selected = category == selectedCategory,
                        enabled = enabled,
                        requester = if (category == SettingsCategory.Appearance) selectedRequester else null,
                        contentRequester = contentRequester,
                        trapVerticalFocus = enabled,
                        onClick = { onCategorySelected(category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SideRailItem(
    category: SettingsCategory,
    selected: Boolean,
    enabled: Boolean,
    requester: FocusRequester?,
    contentRequester: FocusRequester,
    trapVerticalFocus: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(category.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val itemModifier = Modifier
        .height(44.dp)
        .fillMaxWidth()
        .alpha(if (enabled) 1f else 0.4f)
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .then(
            if (requester != null) {
                Modifier.focusProperties {
                    right = contentRequester
                    if (trapVerticalFocus) {
                        up = requester
                        down = requester
                    }
                }
            } else {
                Modifier
            },
        )
        .onFocusChanged { focused = it.isFocused }
        .background(
            if (selected || focused) ThemeRuntime.frostStrongTint else Color.Transparent,
            shape,
        )
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)

    Box(itemModifier) {
        if (selected || focused) {
            Box(
                Modifier
                    .width(SettingsTokens.FocusBarW)
                    .height(SettingsTokens.FocusBarH)
                    .align(Alignment.CenterStart)
                    .background(Accent, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        if (selected) Accent else TextSub.copy(alpha = 0.75f),
                        RoundedCornerShape(4.dp),
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = category.label,
                color = if (selected || focused) TextMain else TextSub,
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
