package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.PLAYBACK_CACHE_OPTIONS
import com.lumina.tv.data.PlaybackCache
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

@Composable
fun SettingsPlayback(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    val context = LocalContext.current
    var speed by remember { mutableStateOf(prefs?.defaultSpeed ?: 1.0f) }
    var autoNext by remember { mutableStateOf(prefs?.autoNextEpisode ?: false) }
    var introSeconds by remember { mutableStateOf(prefs?.globalIntroSeconds ?: 0) }
    var endCreditsSeconds by remember { mutableStateOf(AppRuntime.prefs?.endCreditsSkipSeconds ?: 30) }
    var bitrate by remember { mutableStateOf(prefs?.maxStreamingBitrate ?: 120_000_000L) }
    var cacheBytes by remember { mutableStateOf(prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L) }
    var cacheUsed by remember { mutableStateOf(PlaybackCache.spaceBytes(context)) }
    var forceTranscode by remember { mutableStateOf(prefs?.forceTranscode ?: false) }

    val rowKeys = remember {
        listOf(
            "playback:defaultSpeed", "playback:autoNextEpisode", "playback:globalIntroSeconds",
            "playback:endCreditsSkipSeconds", "playback:maxStreamingBitrate", "playback:playbackCacheBytes",
            "playback:clearCache", "playback:forceTranscode",
        )
    }
    val requesters = remember {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val speedOptions = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).map { value ->
        SettingChoiceOption(value.toString(), if (value == 1.0f) "1.0x" else "${value}x")
    } }
    val introOptions = remember { listOf(0 to "关", 60 to "60秒", 90 to "90秒", 120 to "120秒").map { SettingChoiceOption(it.first.toString(), it.second) } }
    val endOptions = remember { listOf(0 to "关闭", 30 to "30 秒", 60 to "60 秒").map { SettingChoiceOption(it.first.toString(), it.second) } }
    val bitrateOptions = remember { listOf(20_000_000L to "20 Mbps", 40_000_000L to "40 Mbps", 80_000_000L to "80 Mbps", 120_000_000L to "120 Mbps").map { SettingChoiceOption(it.first.toString(), it.second) } }
    val cacheOptions = remember {
        PLAYBACK_CACHE_OPTIONS.sorted().map { value ->
            val label = when (value) {
                0L -> "关闭"
                256L * 1024L * 1024L -> "256M"
                512L * 1024L * 1024L -> "512M"
                1024L * 1024L * 1024L -> "1G"
                else -> "2G"
            }
            SettingChoiceOption(value.toString(), label)
        }
    }

    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)
    LazyColumn(state = settingsListState(SettingsCategory.Playback), modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
        item(key = "playback:group-label") {
            Text("播放", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "playback:group") {
            Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape)) {
                ChoiceRow("playback:defaultSpeed", "默认倍速", "选择新视频的默认播放速度", speedOptions, speed.toString(), { key ->
                    speed = key.toFloat(); prefs?.defaultSpeed = speed
                }, requesters.getValue(rowKeys[0]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[1]))
                ToggleRow("playback:autoNextEpisode", "自动连播", "剧集播完自动播放下一集", autoNext, {
                    autoNext = it; prefs?.autoNextEpisode = it
                }, requesters.getValue(rowKeys[1]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[2]))
                ChoiceRow("playback:globalIntroSeconds", "跳过片头", "全局默认；单剧设置优先", introOptions, introSeconds.toString(), { key ->
                    introSeconds = key.toInt(); prefs?.globalIntroSeconds = introSeconds
                }, requesters.getValue(rowKeys[2]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[1]), downRequester = requesters.getValue(rowKeys[3]))
                ChoiceRow("playback:endCreditsSkipSeconds", "片尾跳过", "自动跳过片尾的时长", endOptions, endCreditsSeconds.toString(), { key ->
                    endCreditsSeconds = key.toInt(); AppRuntime.prefs?.endCreditsSkipSeconds = endCreditsSeconds
                }, requesters.getValue(rowKeys[3]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[2]), downRequester = requesters.getValue(rowKeys[4]))
                ChoiceRow("playback:maxStreamingBitrate", "播放码率上限", "限制流媒体最高码率", bitrateOptions, bitrate.toString(), { key ->
                    bitrate = key.toLong(); prefs?.maxStreamingBitrate = bitrate
                }, requesters.getValue(rowKeys[4]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[3]), downRequester = requesters.getValue(rowKeys[5]))
                ChoiceRow("playback:playbackCacheBytes", "播放缓存", "限制本地播放缓存空间", cacheOptions, cacheBytes.toString(), { key ->
                    cacheBytes = key.toLong(); prefs?.playbackCacheBytes = cacheBytes
                    PlaybackCache.setLimit(cacheBytes); cacheUsed = PlaybackCache.spaceBytes(context)
                }, requesters.getValue(rowKeys[5]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[4]), downRequester = requesters.getValue(rowKeys[6]))
                ActionRow("playback:clearCache", "清空播放缓存", "已用 ${cacheUsed / (1024L * 1024L)} MB", "清空", {
                    PlaybackCache.clear(context); cacheUsed = PlaybackCache.spaceBytes(context)
                }, requesters.getValue(rowKeys[6]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[5]), downRequester = requesters.getValue(rowKeys[7]))
                ToggleRow("playback:forceTranscode", "强制转码", "电视盒子解码异常或画面卡顿时打开", forceTranscode, {
                    forceTranscode = it; prefs?.forceTranscode = it
                }, requesters.getValue(rowKeys[7]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[6]), downRequester = requesters.getValue(rowKeys[7]), showDivider = false)
            }
        }
        item { Spacer(Modifier.height(1.dp)) }
    }
}
