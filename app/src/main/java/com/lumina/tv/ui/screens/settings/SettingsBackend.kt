package com.lumina.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
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
    var playerEngine by remember { mutableStateOf(prefs?.playerEngine ?: "auto") }
    var decoderPreference by remember { mutableStateOf(prefs?.decoderPreference ?: "auto") }
    var matchDisplayFrameRate by remember { mutableStateOf(prefs?.matchDisplayFrameRate ?: false) }
    var declareDtsDirectPlay by remember { mutableStateOf(prefs?.declareDtsDirectPlay ?: false) }
    var bootLaunch by remember { mutableStateOf(prefs?.bootLaunch ?: false) }
    var mpvBroken by remember { mutableStateOf(prefs?.mpvBroken ?: false) }
    var sourceDynamicRange by remember { mutableStateOf(prefs?.sourceDynamicRange ?: DynamicRangePref.UNSET) }
    var sourceBitrate by remember { mutableStateOf(prefs?.sourceBitratePreference ?: NumericPref.UNSET) }
    var sourceReleaseGroups by remember { mutableStateOf(prefs?.sourceReleaseGroups.orEmpty()) }
    var sourcePreferencesExpanded by rememberSaveable { mutableStateOf(false) }
    var releaseGroupsDialog by remember { mutableStateOf(false) }

    val rowKeys = buildList {
        add("compat:playerEngine")
        if (mpvBroken) add("compat:resetMpv")
        add("compat:decoderPreference")
        add("compat:matchDisplayFrameRate")
        add("compat:declareDtsDirectPlay")
        add("compat:bootLaunch")
        add("compat:sourcePreferences")
    }
    val requesters = remember(mpvBroken, firstFocusRequester) {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
        item(key = "compat:group-label") {
            Text("播放器与兼容性", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "compat:group") {
            Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape)) {
                val engineOptions = listOf(
                    SettingChoiceOption("auto", "自动"),
                    SettingChoiceOption("media3", "Media3"),
                    SettingChoiceOption("mpv", if (mpvBroken) "mpv（已禁用）" else "mpv"),
                )
                ChoiceRow("compat:playerEngine", "播放内核", "自动：Media3 → mpv", engineOptions, playerEngine, { key ->
                    if (key != "mpv" || !mpvBroken) {
                        playerEngine = key
                        prefs?.playerEngine = key
                    }
                }, requesters.getValue("compat:playerEngine"), railRequester, onFocused,
                    downRequester = requesters.getValue(if (mpvBroken) "compat:resetMpv" else "compat:decoderPreference"))
                if (mpvBroken) {
                    ActionRow("compat:resetMpv", "重置 mpv 状态", "清除 mpv 不可用标记", "重置", {
                        requesters.getValue("compat:playerEngine").requestFocus()
                        prefs?.mpvBroken = false
                        prefs?.mpvUnavailableReason = ""
                        mpvBroken = false
                    }, requesters.getValue("compat:resetMpv"), railRequester, onFocused,
                        upRequester = requesters.getValue("compat:playerEngine"), downRequester = requesters.getValue("compat:decoderPreference"))
                }
                ChoiceRow("compat:decoderPreference", "解码器优先级", "选择硬件或软件解码优先级", listOf(
                    SettingChoiceOption("auto", "自动"), SettingChoiceOption("hardware", "硬解优先"), SettingChoiceOption("software", "软解优先"),
                ), decoderPreference, { key -> decoderPreference = key; prefs?.decoderPreference = key }, requesters.getValue("compat:decoderPreference"), railRequester, onFocused,
                    upRequester = requesters.getValue(if (mpvBroken) "compat:resetMpv" else "compat:playerEngine"), downRequester = requesters.getValue("compat:matchDisplayFrameRate"))
                ToggleRow("compat:matchDisplayFrameRate", "匹配显示帧率", "默认关闭；仅在 API 30 及以上尝试无缝切换", matchDisplayFrameRate, {
                    matchDisplayFrameRate = it; prefs?.matchDisplayFrameRate = it
                }, requesters.getValue("compat:matchDisplayFrameRate"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:decoderPreference"), downRequester = requesters.getValue("compat:declareDtsDirectPlay"))
                ToggleRow("compat:declareDtsDirectPlay", "声明 DTS/TrueHD 直放（实验）", "开启后服务器可能直接给原轨；若本机解不了会退回 mpv，可能出现黑画面/无声，建议先导出诊断对比", declareDtsDirectPlay, {
                    declareDtsDirectPlay = it; prefs?.declareDtsDirectPlay = it
                }, requesters.getValue("compat:declareDtsDirectPlay"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:matchDisplayFrameRate"), downRequester = requesters.getValue("compat:bootLaunch"))
                ToggleRow("compat:bootLaunch", "开机自启", "电视盒子开机后自动打开琉璃", bootLaunch, {
                    bootLaunch = it; prefs?.bootLaunch = it
                }, requesters.getValue("compat:bootLaunch"), railRequester, onFocused,
                    upRequester = requesters.getValue("compat:declareDtsDirectPlay"), downRequester = requesters.getValue("compat:sourcePreferences"))

                var disclosureFocused by remember { mutableStateOf(false) }
                val disclosureRequester = requesters.getValue("compat:sourcePreferences")
                val disclosureShape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.fillMaxWidth().height(44.dp)
                        .focusRequester(disclosureRequester)
                        .focusProperties {
                            left = railRequester
                            up = requesters.getValue("compat:bootLaunch")
                        }
                        .onFocusChanged { disclosureFocused = it.isFocused; if (it.isFocused) onFocused("compat:sourcePreferences") }
                        .background(if (disclosureFocused) ThemeRuntime.frostStrongTint else Color.Transparent, disclosureShape)
                        .clickable {
                            if (sourcePreferencesExpanded) disclosureRequester.requestFocus()
                            sourcePreferencesExpanded = !sourcePreferencesExpanded
                        },
                ) {
                    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("高级选项", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(12.dp))
                        val summary = buildList {
                            if (sourceDynamicRange != DynamicRangePref.UNSET) add(when (sourceDynamicRange) {
                                DynamicRangePref.DOLBY_VISION -> "DV优先"
                                DynamicRangePref.HDR -> "HDR优先"
                                DynamicRangePref.SDR -> "SDR优先"
                                else -> ""
                            })
                            if (sourceBitrate != NumericPref.UNSET) add(if (sourceBitrate == NumericPref.HIGH) "码率高优先" else "码率低优先")
                        }.joinToString(" · ").ifBlank { "片源偏好：默认（取第一个源）" }
                        Text(summary, Modifier.weight(1f), color = TextSub, fontSize = 10.sp, maxLines = 1)
                        androidx.compose.material3.Icon(if (sourcePreferencesExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = if (disclosureFocused) Accent else TextSub)
                    }
                }
                AnimatedVisibility(
                    visible = sourcePreferencesExpanded,
                    modifier = Modifier.focusProperties { canFocus = sourcePreferencesExpanded },
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("动态范围", color = TextSub, fontSize = 10.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                DynamicRangePref.UNSET to "不设置", DynamicRangePref.DOLBY_VISION to "DV优先",
                                DynamicRangePref.HDR to "HDR优先", DynamicRangePref.SDR to "SDR优先",
                            ).forEach { (value, label) ->
                                val active = sourceDynamicRange == value
                                FocusSurface(Modifier.height(32.dp).widthIn(min = 72.dp), corner = 16.dp, onClick = {
                                    sourceDynamicRange = value; prefs?.sourceDynamicRange = value
                                }) { focused ->
                                    Box(Modifier.fillMaxWidth().height(32.dp).background(if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("码率", color = TextSub, fontSize = 10.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(NumericPref.UNSET to "不设置", NumericPref.HIGH to "高优先", NumericPref.LOW to "低优先").forEach { (value, label) ->
                                val active = sourceBitrate == value
                                FocusSurface(Modifier.height(32.dp).widthIn(min = 72.dp), corner = 16.dp, onClick = {
                                    sourceBitrate = value; prefs?.sourceBitratePreference = value
                                }) { focused ->
                                    Box(Modifier.fillMaxWidth().height(32.dp).background(if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FocusSurface(Modifier.fillMaxWidth().height(40.dp), corner = 10.dp, onClick = { releaseGroupsDialog = true }) { focused ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("发布组关键词", color = TextMain, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text(sourceReleaseGroups.ifBlank { "SPARKS,FGT" }, color = if (focused) TextMain else TextSub, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    if (releaseGroupsDialog) {
        AlertDialog(
            onDismissRequest = { releaseGroupsDialog = false },
            title = { Text("发布组关键词") },
            text = {
                OutlinedTextField(
                    value = sourceReleaseGroups,
                    onValueChange = { sourceReleaseGroups = it; prefs?.sourceReleaseGroups = it },
                    label = { Text("发布组关键词") },
                    placeholder = { Text("SPARKS,FGT") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = { releaseGroupsDialog = false }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { releaseGroupsDialog = false }) { Text("关闭") } },
        )
    }
}
