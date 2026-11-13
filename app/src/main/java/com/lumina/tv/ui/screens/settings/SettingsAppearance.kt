package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.theme.BASE_PRESETS
import com.lumina.tv.ui.theme.GLASS_PRESETS
import com.lumina.tv.ui.theme.THEME_PRESETS
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime

private data class AppearanceRowSpec(val key: String)

@Composable
fun SettingsAppearance(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    var baseThemeKey by remember { mutableStateOf(prefs?.baseTheme ?: "ink") }
    var themeKey by remember { mutableStateOf(prefs?.themeColor ?: "blue") }
    var glassKey by remember { mutableStateOf(prefs?.glassStyle ?: "frosted") }
    var detailBackgroundVideo by remember {
        mutableStateOf(
            prefs?.detailBackgroundVideo?.takeIf { it in setOf("off", "720p", "auto") } ?: "auto",
        )
    }
    var showPosterRating by remember { mutableStateOf(AppRuntime.prefs?.showPosterRating ?: true) }
    var showPosterWatchState by remember { mutableStateOf(AppRuntime.prefs?.showPosterWatchState ?: true) }

    val rowSpecs = remember {
        listOf(
            AppearanceRowSpec("appearance:baseTheme"),
            AppearanceRowSpec("appearance:themeColor"),
            AppearanceRowSpec("appearance:glassStyle"),
            AppearanceRowSpec("appearance:detailBackgroundVideo"),
            AppearanceRowSpec("appearance:showPosterRating"),
            AppearanceRowSpec("appearance:showPosterWatchState"),
        )
    }
    val requesters = remember {
        rowSpecs.associate { spec ->
            spec.key to if (spec.key == "appearance:baseTheme") firstFocusRequester else FocusRequester()
        }
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LazyColumn(state = settingsListState(SettingsCategory.Appearance), modifier = modifier) {
        item(key = "appearance:group-label") {
            Text(
                text = "主题与展示",
                color = TextSub,
                fontSize = SettingsTokens.GroupLabelSize,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        item(key = "appearance:group") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(ThemeRuntime.frostTint, groupShape)
                    .glassBorder(groupShape),
            ) {
                Column {
                    rowSpecs.forEachIndexed { index, spec ->
                        val requester = requireNotNull(requesters[spec.key])
                        val upRequester = if (index == 0) requester else null
                        val downRequester = if (index == rowSpecs.lastIndex) requester else null
                        when (spec.key) {
                            "appearance:baseTheme" -> BaseThemeRow(
                                focusKey = spec.key,
                                title = "底色",
                                description = "选择页面底色",
                                options = BASE_PRESETS,
                                selectedKey = baseThemeKey,
                                onSelected = { key ->
                                    baseThemeKey = key
                                    prefs?.baseTheme = key
                                    ThemeRuntime.applyBase(key)
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                downRequester = requesters.getValue("appearance:themeColor"),
                                onFocused = onFocused,
                            )

                            "appearance:themeColor" -> ChoiceRow(
                                focusKey = spec.key,
                                title = "主题色",
                                description = "应用到强调色与背景光晕",
                                options = THEME_PRESETS.map {
                                    SettingChoiceOption(it.key, it.label, it.accent)
                                },
                                selectedKey = themeKey,
                                onSelected = { key ->
                                    themeKey = key
                                    ThemeRuntime.applyTheme(key)
                                    prefs?.themeColor = key
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                upRequester = upRequester,
                                onFocused = onFocused,
                            )

                            "appearance:glassStyle" -> ChoiceRow(
                                focusKey = spec.key,
                                title = "玻璃质感",
                                description = if (glassKey == "off") {
                                    "已关闭玻璃效果，低配设备更流畅省电"
                                } else {
                                    "磨砂更沉浸，清透更轻盈，关闭更省电"
                                },
                                options = GLASS_PRESETS.map {
                                    SettingChoiceOption(
                                        key = it.key,
                                        label = if (it.key == "off") "关" else it.label,
                                    )
                                },
                                selectedKey = glassKey,
                                onSelected = { key ->
                                    glassKey = key
                                    ThemeRuntime.applyGlass(key)
                                    prefs?.glassStyle = key
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                onFocused = onFocused,
                            )

                            "appearance:detailBackgroundVideo" -> ChoiceRow(
                                focusKey = spec.key,
                                title = "详情页背景视频",
                                description = "控制详情页动态背景的清晰度策略",
                                options = listOf(
                                    SettingChoiceOption("off", "关"),
                                    SettingChoiceOption("720p", "720p"),
                                    SettingChoiceOption("auto", "自动"),
                                ),
                                selectedKey = detailBackgroundVideo,
                                onSelected = { key ->
                                    detailBackgroundVideo = key
                                    prefs?.detailBackgroundVideo = key
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                onFocused = onFocused,
                            )

                            "appearance:showPosterRating" -> ToggleRow(
                                focusKey = spec.key,
                                title = "海报评分",
                                description = "在海报右上角显示 ★ 评分",
                                checked = showPosterRating,
                                onCheckedChange = {
                                    showPosterRating = it
                                    AppRuntime.prefs?.showPosterRating = it
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                onFocused = onFocused,
                            )

                            "appearance:showPosterWatchState" -> ToggleRow(
                                focusKey = spec.key,
                                title = "观看状态",
                                description = "在海报左上角标记已看",
                                checked = showPosterWatchState,
                                onCheckedChange = {
                                    showPosterWatchState = it
                                    AppRuntime.prefs?.showPosterWatchState = it
                                },
                                focusRequester = requester,
                                leftRequester = railRequester,
                                downRequester = downRequester,
                                showDivider = false,
                                onFocused = onFocused,
                            )

                        }
                    }
                }
            }
        }
        item(key = "appearance:bottom-space") {
            Spacer(Modifier.height(SettingsTokens.GroupGap))
        }
    }
}
