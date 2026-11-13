package com.lumina.tv.ui.screens.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.components.FrostPanel
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val hazeState = remember { HazeState() }
    val railRequester = remember { FocusRequester() }
    val contentFirstRequester = remember { FocusRequester() }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.Appearance) }
    var focusedRowKey by remember { mutableStateOf("appearance:themeColor") }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { railRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF121A26), Color(0xFF0B1018)),
                    ),
                ),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val railWidth = if (maxWidth < 1100.dp) 192.dp else SettingsTokens.RailWidth
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = SettingsTokens.PagePaddingH,
                        vertical = SettingsTokens.PagePaddingV,
                    ),
            ) {
                SettingsCategoryRail(
                    selectedCategory = selectedCategory,
                    railWidth = railWidth,
                    hazeState = hazeState,
                    selectedRequester = railRequester,
                    contentRequester = contentFirstRequester,
                    onCategorySelected = { selectedCategory = it },
                )
                Spacer(Modifier.width(SettingsTokens.RailContentGap))
                Column(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = SettingsTokens.ContentMaxWidth)
                        .weight(1f)
                        .focusGroup(),
                ) {
                    Text(
                        text = selectedCategory.label,
                        color = TextMain,
                        fontSize = SettingsTokens.TitleSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (selectedCategory == SettingsCategory.Appearance) {
                            "主题色与玻璃质感 · 即时生效"
                        } else {
                            "设置分类"
                        },
                        color = TextSub,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Crossfade(
                        targetState = selectedCategory,
                        animationSpec = tween(SettingsTokens.MotionCategoryMs),
                        label = "settingsCategory",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { category ->
                        when (category) {
                            SettingsCategory.Appearance -> SettingsAppearance(
                                firstFocusRequester = contentFirstRequester,
                                railRequester = railRequester,
                                onFocused = { focusedRowKey = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            SettingsCategory.Playback -> SettingsPlayback(
                                firstFocusRequester = contentFirstRequester,
                                railRequester = railRequester,
                                onFocused = { focusedRowKey = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            SettingsCategory.Subtitles -> SettingsSubtitles(
                                firstFocusRequester = contentFirstRequester,
                                railRequester = railRequester,
                                onFocused = { focusedRowKey = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            SettingsCategory.Compatibility -> SettingsBackend(
                                firstFocusRequester = contentFirstRequester,
                                railRequester = railRequester,
                                onFocused = { focusedRowKey = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            SettingsCategory.About -> SettingsAbout(
                                firstFocusRequester = contentFirstRequester,
                                railRequester = railRequester,
                                onFocused = { focusedRowKey = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            else -> Box(Modifier.fillMaxSize().padding(top = 36.dp)) {
                                Text("该分类正在迁移", color = TextSub, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRail(
    selectedCategory: SettingsCategory,
    railWidth: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    selectedRequester: FocusRequester,
    contentRequester: FocusRequester,
    onCategorySelected: (SettingsCategory) -> Unit,
) {
    val shape = RoundedCornerShape(SettingsTokens.GroupCorner)
    FrostPanel(Modifier.width(railWidth).fillMaxHeight(), hazeState, SettingsTokens.GroupCorner) {
        Column(Modifier.fillMaxSize().glassBorder(shape).focusGroup().padding(horizontal = 10.dp, vertical = 16.dp)) {
            Text("设置", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.height(10.dp))
            SettingsCategory.entries.forEach { category ->
                val enabled = category in setOf(SettingsCategory.Appearance, SettingsCategory.Playback, SettingsCategory.Subtitles, SettingsCategory.Compatibility, SettingsCategory.About)
                val selected = category == selectedCategory
                var focused by remember(category.key) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .then(if (category == selectedCategory) Modifier.focusRequester(selectedRequester) else Modifier)
                        .focusProperties { if (category == selectedCategory) right = contentRequester }
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (selected || focused) ThemeRuntime.frostStrongTint else Color.Transparent, RoundedCornerShape(12.dp))
                        .then(if (enabled) Modifier.clickable { onCategorySelected(category) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected || focused) Box(Modifier.width(SettingsTokens.FocusBarW).height(SettingsTokens.FocusBarH).background(Accent, RoundedCornerShape(2.dp)))
                    Box(Modifier.padding(start = 14.dp).size(14.dp).background(if (selected) Accent else TextSub.copy(alpha = .75f), RoundedCornerShape(4.dp)))
                    Text(category.label, Modifier.padding(start = 10.dp), color = if (selected || focused) TextMain else TextSub, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}
