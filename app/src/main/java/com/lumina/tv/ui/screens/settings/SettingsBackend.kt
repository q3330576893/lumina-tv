package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.*
import com.lumina.tv.ui.theme.TypeScale

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.rememberPreferenceState
import com.lumina.tv.AppRuntime
import com.lumina.tv.BootReceiver
import com.lumina.tv.data.DynamicRangePref
import com.lumina.tv.data.NumericPref
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsBackend(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    val context = LocalContext.current
    val bootCapability = remember(context) { BootReceiver.capability(context) }
    var playerEngine by rememberPreferenceState(prefs, "engine", { prefs?.playerEngine ?: "auto" }, { prefs?.playerEngine = it })
    var decoderPreference by rememberPreferenceState(prefs, "decoder_preference", { prefs?.decoderPreference ?: "auto" }, { prefs?.decoderPreference = it })
    var matchDisplayFrameRate by rememberPreferenceState(prefs, "match_display_frame_rate", { prefs?.matchDisplayFrameRate ?: false }, { prefs?.matchDisplayFrameRate = it })
    var declareDtsDirectPlay by rememberPreferenceState(prefs, "declare_dts_direct_play", { prefs?.declareDtsDirectPlay ?: false }, { prefs?.declareDtsDirectPlay = it })
    var bootLaunch by rememberPreferenceState(prefs, "boot_launch", { prefs?.bootLaunch ?: false }, { prefs?.bootLaunch = it })
    var sourceDynamicRange by rememberPreferenceState(prefs, "source_dynamic_range", { prefs?.sourceDynamicRange ?: DynamicRangePref.UNSET }, { prefs?.sourceDynamicRange = it })
    var sourceBitrate by rememberPreferenceState(prefs, "source_bitrate", { prefs?.sourceBitratePreference ?: NumericPref.UNSET }, { prefs?.sourceBitratePreference = it })
    var sourceReleaseGroups by rememberPreferenceState(prefs, "source_release_groups", { prefs?.sourceReleaseGroups.orEmpty() }, { prefs?.sourceReleaseGroups = it })
    var sourcePreferencesExpanded by rememberSaveable { mutableStateOf(false) }
    var releaseGroupsDialog by remember { mutableStateOf(false) }

    val rowKeys = buildList {
        add("compat:playerEngine")
        add("compat:decoderPreference")
        add("compat:matchDisplayFrameRate")
        add("compat:declareDtsDirectPlay")
        add("compat:bootLaunch")
        add("compat:sourcePreferences")
        add("compat:sourceDynamicRange")
        add("compat:sourceBitrate")
        add("compat:sourceReleaseGroups")
    }
    val requesters = remember(firstFocusRequester) {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val focusContext = LocalSettingsFocusContext.current
    val expandedContext = focusContext.copy(expansionState = if (sourcePreferencesExpanded) "expanded" else "collapsed")
    BackHandler(enabled = sourcePreferencesExpanded && !releaseGroupsDialog) {
        sourcePreferencesExpanded = false
        focusContext.restore(focusContext.key("compat:sourcePreferences"))
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LazyColumn(state = settingsListState(SettingsCategory.Compatibility), modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
        item(key = "compat:group-label") {
            Text("播放器与兼容性", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "compat:group") {
            Column(Modifier.fillMaxWidth().luminaSurface(SurfaceLevel.Content, shape = groupShape).glassBorder(groupShape)) {
                val engineOptions = listOf(
                    SettingChoiceOption("auto", "自动"),
                    SettingChoiceOption("media3", "Media3"),
                    SettingChoiceOption("mpv", "mpv"),
                )
                ChoiceRow("compat:playerEngine", "播放内核", "自动优先 Media3", engineOptions, playerEngine, { key ->
                    playerEngine = key
                }, requesters.getValue("compat:playerEngine"), railRequester, onFocused,
                    downRequester = requesters.getValue("compat:decoderPreference"))
                ChoiceRow("compat:decoderPreference", "解码器优先级", "选择硬件或软件解码优先级", listOf(
                    SettingChoiceOption("auto", "自动"), SettingChoiceOption("hardware", "硬解优先"), SettingChoiceOption("software", "软解优先"),
                ), decoderPreference, { key -> decoderPreference = key }, requesters.getValue("compat:decoderPreference"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:playerEngine"), downRequester = requesters.getValue("compat:matchDisplayFrameRate"))
                ToggleRow("compat:matchDisplayFrameRate", "匹配显示帧率", "默认关闭；仅在 API 30 及以上尝试无缝切换", matchDisplayFrameRate, {
                    matchDisplayFrameRate = it
                }, requesters.getValue("compat:matchDisplayFrameRate"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:decoderPreference"), downRequester = requesters.getValue("compat:declareDtsDirectPlay"),
                    enabled = android.os.Build.VERSION.SDK_INT >= 30,
                    disabledReason = "系统版本不支持无缝帧率切换", nextCondition = "需要 Android 11 / API 30 及以上")
                ToggleRow("compat:declareDtsDirectPlay", "声明 DTS/TrueHD 直放（实验）", "开启后服务器可能直接给原轨；若本机解不了会退回 mpv，可能出现黑画面/无声，建议先导出诊断对比", declareDtsDirectPlay, {
                    declareDtsDirectPlay = it
                }, requesters.getValue("compat:declareDtsDirectPlay"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:matchDisplayFrameRate"), downRequester = requesters.getValue("compat:bootLaunch"))
                ToggleRow("compat:bootLaunch", "开机自启", bootCapability.description, bootLaunch, {
                    bootLaunch = it
                }, requesters.getValue("compat:bootLaunch"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:declareDtsDirectPlay"), downRequester = requesters.getValue("compat:sourcePreferences"))

                CompositionLocalProvider(LocalSettingsFocusContext provides expandedContext) {
                NavigationRow("compat:sourcePreferences", "高级选项", "片源动态范围、码率与发布组偏好",
                    if (sourcePreferencesExpanded) "已展开 ▾" else "已收起 ▸", {
                        sourcePreferencesExpanded = !sourcePreferencesExpanded
                        val next = focusContext.copy(expansionState = if (sourcePreferencesExpanded) "expanded" else "collapsed")
                        focusContext.record(next.key("compat:sourcePreferences"))
                        focusContext.restore(next.key("compat:sourcePreferences"))
                    }, requesters.getValue("compat:sourcePreferences"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:bootLaunch"))
                }
                if (sourcePreferencesExpanded) CompositionLocalProvider(LocalSettingsFocusContext provides expandedContext) {
                    Column {
                        ChoiceRow("compat:sourceDynamicRange", "动态范围", "仅影响新媒体的片源排序", listOf(
                            SettingChoiceOption("UNSET", "不设置"), SettingChoiceOption("DOLBY_VISION", "DV 优先"),
                            SettingChoiceOption("HDR", "HDR 优先"), SettingChoiceOption("SDR", "SDR 优先")),
                            sourceDynamicRange.name, { sourceDynamicRange = DynamicRangePref.valueOf(it) },
                            requesters.getValue("compat:sourceDynamicRange"), railRequester, onFocused,
                            upRequester = requesters.getValue("compat:sourcePreferences"))
                        ChoiceRow("compat:sourceBitrate", "码率偏好", "按码率排列片源，不改变流媒体码率上限", listOf(
                            SettingChoiceOption("UNSET", "不设置"), SettingChoiceOption("HIGH", "高优先"), SettingChoiceOption("LOW", "低优先")),
                            sourceBitrate.name, { sourceBitrate = NumericPref.valueOf(it) },
                            requesters.getValue("compat:sourceBitrate"), railRequester, onFocused)
                        NavigationRow("compat:sourceReleaseGroups", "发布组关键词", "打开弹层查看和编辑完整列表；逗号分隔",
                            if (sourceReleaseGroups.isBlank()) "未设置" else "已设置 ${sourceReleaseGroups.split(',').count { it.isNotBlank() }} 项", { releaseGroupsDialog = true },
                            requesters.getValue("compat:sourceReleaseGroups"), railRequester, onFocused)
                    }
                }
            }
        }
    }

    if (releaseGroupsDialog) CompositionLocalProvider(LocalSettingsFocusContext provides expandedContext) {
        var draft by remember { mutableStateOf(sourceReleaseGroups) }
        SettingsDialog("compat:sourceReleaseGroups", "发布组关键词", { releaseGroupsDialog = false }) { close ->
            Text(SettingDeclarations.forRow("compat:sourceReleaseGroups")!!.summary, color = TextSub)
            val field = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; field.requestFocus() }
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("发布组关键词（逗号分隔）") },
                modifier = Modifier.fillMaxWidth().settingsEditorFocus("compat:releaseGroups:editor", field))
            SettingsDialogButton("compat:releaseGroups:save", "保存") {
                sourceReleaseGroups = draft; close()
            }
            SettingsDialogButton("compat:releaseGroups:cancel", "取消", onClick = close)
        }
    }
}
