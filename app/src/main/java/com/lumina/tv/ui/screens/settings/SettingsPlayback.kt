package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.*
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.lumina.tv.ui.rememberPreferenceState
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.PLAYBACK_CACHE_OPTIONS
import com.lumina.tv.data.PlaybackCache
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun SettingsPlayback(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speed by rememberPreferenceState(prefs, "default_speed", { prefs?.defaultSpeed ?: 1.0f }, { prefs?.defaultSpeed = it })
    var autoNext by rememberPreferenceState(prefs, "auto_next", { prefs?.autoNextEpisode ?: false }, { prefs?.autoNextEpisode = it })
    var introSeconds by rememberPreferenceState(prefs, "global_intro_seconds", { prefs?.globalIntroSeconds ?: 0 }, { prefs?.globalIntroSeconds = it })
    var endCreditsSeconds by rememberPreferenceState(prefs, "end_credits_skip_seconds", { AppRuntime.prefs?.endCreditsSkipSeconds ?: 30 }, { prefs?.endCreditsSkipSeconds = it })
    var bitrate by rememberPreferenceState(prefs, "max_streaming_bitrate", { prefs?.maxStreamingBitrate ?: 120_000_000L }, { prefs?.maxStreamingBitrate = it })
    var cacheBytes by rememberPreferenceState(prefs, "playback_cache_bytes", { prefs?.playbackCacheBytes ?: 512L * 1024L * 1024L }, { prefs?.playbackCacheBytes = it })
    var cacheUsed by remember { mutableStateOf<Long?>(null) }
    var cacheMaintenanceBusy by remember { mutableStateOf(false) }
    var cacheMaintenanceError by remember { mutableStateOf<String?>(null) }
    val cacheMaintenanceMutex = remember { Mutex() }
    var forceTranscode by rememberPreferenceState(prefs, "force_transcode", { prefs?.forceTranscode ?: false }, { prefs?.forceTranscode = it })
    var anime4kMode by rememberPreferenceState(prefs, "anime4k_mode", { prefs?.anime4kMode ?: "off" }, { prefs?.anime4kMode = it })

    suspend fun maintainCache(operation: suspend () -> Unit) {
        cacheMaintenanceMutex.withLock {
            cacheMaintenanceBusy = true
            cacheMaintenanceError = null
            try {
                val (succeeded, usage) = withContext(Dispatchers.IO) {
                    val succeeded = try {
                        operation()
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    succeeded to runCatching { PlaybackCache.spaceBytes(context) }
                }
                cacheUsed = usage.getOrNull()
                cacheMaintenanceError = when {
                    !succeeded -> "缓存维护失败，请重试"
                    usage.isFailure -> "无法读取缓存占用，请重试"
                    else -> null
                }
            } finally {
                cacheMaintenanceBusy = false
            }
        }
    }

    LaunchedEffect(context) {
        maintainCache { PlaybackCache.autoTrim(context).getOrThrow() }
    }

    val rowKeys = remember {
        listOf(
            "playback:defaultSpeed", "playback:autoNextEpisode", "playback:globalIntroSeconds",
            "playback:endCreditsSkipSeconds", "playback:maxStreamingBitrate", "playback:playbackCacheBytes",
            "playback:clearCache", "playback:forceTranscode",
            "playback:anime4kMode",
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
    val languageOptions = remember {
        listOf("" to "跟随片源", "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韩文")
            .map { SettingChoiceOption(it.first, it.second) }
    }
    val anime4kOptions = remember {
        listOf("off" to "关闭", "efficiency" to "效率", "quality" to "质量")
            .map { SettingChoiceOption(it.first, it.second) }
    }
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
            Column(Modifier.fillMaxWidth().luminaSurface(SurfaceLevel.Content, shape = groupShape).glassBorder(groupShape)) {
                ChoiceRow("playback:defaultSpeed", "默认倍速", "选择新视频的默认播放速度", speedOptions, speed.toString(), { key ->
                    speed = key.toFloat()
                }, requesters.getValue(rowKeys[0]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[1]))
                ToggleRow("playback:autoNextEpisode", "自动连播", "剧集播完自动播放下一集", autoNext, {
                    autoNext = it
                }, requesters.getValue(rowKeys[1]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[2]))
                ChoiceRow("playback:globalIntroSeconds", "跳过片头", "全局默认；单剧设置优先", introOptions, introSeconds.toString(), { key ->
                    introSeconds = key.toInt()
                }, requesters.getValue(rowKeys[2]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[1]), downRequester = requesters.getValue(rowKeys[3]))
                ChoiceRow("playback:endCreditsSkipSeconds", "片尾跳过", "自动跳过片尾的时长", endOptions, endCreditsSeconds.toString(), { key ->
                    endCreditsSeconds = key.toInt()
                }, requesters.getValue(rowKeys[3]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[2]), downRequester = requesters.getValue(rowKeys[4]))
                ChoiceRow("playback:maxStreamingBitrate", "播放码率上限", "限制流媒体最高码率", bitrateOptions, bitrate.toString(), { key ->
                    bitrate = key.toLong()
                }, requesters.getValue(rowKeys[4]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[3]), downRequester = requesters.getValue(rowKeys[5]))
                ChoiceRow("playback:playbackCacheBytes", "缓存上限", "每天自动清理一次，超过上限会提前淘汰", cacheOptions, cacheBytes.toString(), { key ->
                    val selectedBytes = key.toLong()
                    cacheBytes = selectedBytes
                    scope.launch {
                        maintainCache {
                            PlaybackCache.setLimit(selectedBytes)
                            PlaybackCache.autoTrim(context, force = true).getOrThrow()
                        }
                    }
                }, requesters.getValue(rowKeys[5]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[4]), downRequester = requesters.getValue(rowKeys[6]))
                val usageText = cacheUsed?.let { "已用 ${it / (1024L * 1024L)} MB" } ?: "占用未知"
                val limitText = if (cacheBytes > 0L) "上限 ${cacheBytes / (1024L * 1024L)} MB" else "缓存已关闭"
                val cacheDescription = "$usageText / $limitText · 每天自动清理" +
                    cacheMaintenanceError?.let { " · $it" }.orEmpty()
                ActionRow("playback:clearCache", "播放缓存", cacheDescription, if (cacheMaintenanceBusy) "处理中" else "清空", {
                    if (!cacheMaintenanceBusy) scope.launch {
                        maintainCache { PlaybackCache.clear(context).getOrThrow() }
                    }
                }, requesters.getValue(rowKeys[6]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[5]), downRequester = requesters.getValue(rowKeys[7]),
                    enabled = !cacheMaintenanceBusy, disabledReason = "缓存维护进行中", nextCondition = "等待维护完成后重试")
                ToggleRow("playback:forceTranscode", "强制转码", "电视盒子解码异常或画面卡顿时打开", forceTranscode, {
                    forceTranscode = it
                }, requesters.getValue(rowKeys[7]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[6]), downRequester = requesters.getValue(rowKeys[8]))
                ChoiceRow("playback:anime4kMode", "Anime4K 超分", "仅 mpv 内核 / SDR / 1080p 及以下生效", anime4kOptions, anime4kMode, { key ->
                    anime4kMode = key
                }, requesters.getValue(rowKeys[8]), railRequester, { onFocused(it) }, upRequester = requesters.getValue(rowKeys[7]), downRequester = requesters.getValue(rowKeys[8]), showDivider = false)
            }
        }
        item { Spacer(Modifier.height(1.dp)) }
    }
}

/** Shared preference row used by the 字幕与音轨 category. */
@Composable
internal fun AudioLanguagePreferenceRow(focusRequester: FocusRequester, railRequester: FocusRequester,
    onFocused: (String) -> Unit, upRequester: FocusRequester) {
    val prefs = AppRuntime.prefs
    var preferredAudioLanguage by rememberPreferenceState(prefs, "preferred_audio_language",
        { prefs?.preferredAudioLanguage.orEmpty() }, { prefs?.preferredAudioLanguage = it })
    val options = listOf("" to "跟随片源", "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韩文")
        .map { SettingChoiceOption(it.first, it.second) }
    ChoiceRow("subtitles:preferredAudioLanguage", "首选音轨语言", "新影片默认语言；按媒体手动选择优先",
        options, preferredAudioLanguage, { preferredAudioLanguage = it }, focusRequester, railRequester, onFocused,
        upRequester = upRequester, showDivider = false)
}
