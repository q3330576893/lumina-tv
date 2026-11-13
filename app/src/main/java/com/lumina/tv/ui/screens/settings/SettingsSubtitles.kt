package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime

@Composable
fun SettingsSubtitles(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    var subtitleScale by remember { mutableStateOf(prefs?.subtitleScale ?: 1.0f) }
    var subtitleColor by remember { mutableStateOf(prefs?.subtitleColor ?: android.graphics.Color.WHITE) }
    var subtitleOutline by remember { mutableStateOf(prefs?.subtitleOutline ?: true) }
    var subtitlePosition by remember { mutableStateOf(prefs?.subtitlePosition ?: 0.08f) }
    var subtitleBg by remember { mutableStateOf(prefs?.subtitleBg ?: false) }

    val rowKeys = remember { listOf("subtitles:subtitleScale", "subtitles:subtitleColor", "subtitles:subtitleOutline", "subtitles:subtitlePosition", "subtitles:subtitleBg") }
    val requesters = remember {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val scaleOptions = remember {
        listOf(0.85f to "小", 1.0f to "标准", 1.25f to "大", 1.5f to "特大").map { SettingChoiceOption(it.first.toString(), it.second) }
    }
    val colorOptions = remember {
        listOf(
            SettingChoiceOption(android.graphics.Color.WHITE.toString(), "白色", Color.White),
            SettingChoiceOption(android.graphics.Color.YELLOW.toString(), "黄色", Color.Yellow),
            SettingChoiceOption(android.graphics.Color.CYAN.toString(), "青色", Color.Cyan),
            SettingChoiceOption(android.graphics.Color.GREEN.toString(), "绿色", Color.Green),
        )
    }
    val positionOptions = remember {
        listOf(0.04f to "低", 0.08f to "标准", 0.15f to "高").map { SettingChoiceOption(it.first.toString(), it.second) }
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
        item(key = "subtitles:preview-label") {
            Text("字幕", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "subtitles:preview") {
            SubtitlePreviewCard(subtitleScale, Color(subtitleColor), subtitleOutline, subtitlePosition, subtitleBg)
        }
        item(key = "subtitles:group") {
            Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape)) {
                ChoiceRow("subtitles:subtitleScale", "字号", "调整字幕显示大小", scaleOptions, subtitleScale.toString(), { key ->
                    subtitleScale = key.toFloat(); prefs?.subtitleScale = subtitleScale
                }, requesters.getValue(rowKeys[0]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[1]))
                ChoiceRow("subtitles:subtitleColor", "文字颜色", "选择字幕文字颜色", colorOptions, subtitleColor.toString(), { key ->
                    subtitleColor = key.toInt(); prefs?.subtitleColor = subtitleColor
                }, requesters.getValue(rowKeys[1]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[2]))
                ToggleRow("subtitles:subtitleOutline", "字幕描边", "高对比背景下保持文字清晰", subtitleOutline, {
                    subtitleOutline = it; prefs?.subtitleOutline = it
                }, requesters.getValue(rowKeys[2]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[1]), downRequester = requesters.getValue(rowKeys[3]))
                ChoiceRow("subtitles:subtitlePosition", "垂直位置", "调整字幕在画面中的高度", positionOptions, subtitlePosition.toString(), { key ->
                    subtitlePosition = key.toFloat(); prefs?.subtitlePosition = subtitlePosition
                }, requesters.getValue(rowKeys[3]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[2]), downRequester = requesters.getValue(rowKeys[4]))
                ToggleRow("subtitles:subtitleBg", "字幕背景底色", "亮背景下更易读，默认关闭", subtitleBg, {
                    subtitleBg = it; prefs?.subtitleBg = it
                }, requesters.getValue(rowKeys[4]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[3]), downRequester = requesters.getValue(rowKeys[4]), showDivider = false)
            }
        }
    }
}
