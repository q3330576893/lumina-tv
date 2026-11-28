package com.lumina.tv.ui.screens.settings

/** The five fields are length-prefixed, so URLs, IPv6 and delimiters cannot collide. */
internal data class SettingsFocusKey(
    val category: String,
    val rowKey: String,
    val itemIdentity: String,
    val expansionState: String,
    val accountScope: String,
) {
    val encoded: String get() = listOf(category, rowKey, itemIdentity, expansionState, accountScope)
        .joinToString("") { "${it.length}:$it" }
}

internal data class SettingsFocusTicket(val category: String, val accountScope: String, val epoch: Long)

/** Every suspension in a focus restore must recheck this ticket. */
internal class SettingsFocusSession(private var category: String, private val accountScope: String) {
    private var epoch = 0L
    fun select(next: String): SettingsFocusTicket { category = next; epoch++; return ticket() }
    fun userMoved() { epoch++ }
    fun ticket() = SettingsFocusTicket(category, accountScope, epoch)
    fun isCurrent(ticket: SettingsFocusTicket): Boolean = ticket == ticket()
}

internal enum class SettingScope(val label: String) { Global("全局"), Account("账号"), Media("媒体（当前账号）") }

internal data class SettingDeclaration(
    val defaultValue: String,
    val scope: SettingScope,
    val currentPlaybackImmediate: Boolean,
    val requiresNewLoad: Boolean,
    val timing: String,
) {
    val summary: String get() = "默认：$defaultValue · 保存：${scope.label} · 当前播放：${if (currentPlaybackImmediate) "立即生效" else "不立即生效"} · ${if (requiresNewLoad) "需要新 load" else "无需新 load"} · $timing"
}

/** Describes the existing Prefs keys; this registry never migrates or rewrites their scope. */
internal object SettingDeclarations {
    private fun global(default: String, immediate: Boolean = false, load: Boolean = true, timing: String = "下一次加载媒体时使用") =
        SettingDeclaration(default, SettingScope.Global, immediate, load, timing)

    val entries = mapOf(
        "appearance:baseTheme" to global("OLED 黑", load = false, timing = "界面即时更新"),
        "appearance:themeColor" to global("蓝色", load = false, timing = "界面即时更新"),
        "appearance:glassStyle" to global("FULL（设备能力可降档）", load = false, timing = "界面即时更新"),
        "appearance:detailBackgroundVideo" to global("自动", load = false, timing = "下次进入详情页使用"),
        "appearance:showPosterRating" to global("开", load = false, timing = "海报重新显示时使用"),
        "appearance:showPosterWatchState" to global("开", load = false, timing = "海报重新显示时使用"),
        "playback:defaultSpeed" to global("1.0x"),
        "playback:autoNextEpisode" to global("关", true, false, "下一次连播判断时使用"),
        "playback:globalIntroSeconds" to global("0 秒", timing = "媒体单独设置优先"),
        "playback:endCreditsSkipSeconds" to global("30 秒", true, false, "下一次片尾判断时使用"),
        "playback:maxStreamingBitrate" to global("120 Mbps"),
        "playback:playbackCacheBytes" to global("512 MB", load = false, timing = "即时维护磁盘缓存；当前流不重新加载"),
        "playback:forceTranscode" to global("关"),
        "playback:anime4kMode" to global("关"),
        "subtitles:preferredAudioLanguage" to global("跟随片源", timing = "新媒体使用；按媒体手动选择优先"),
        "subtitles:preferredSubtitleLanguage" to global("跟随片源", timing = "新媒体使用；按媒体手动选择优先"),
        "subtitles:subtitleScale" to global("标准（1.0）", true, false, "当前字幕显示同步更新"),
        "subtitles:subtitleColor" to global("白色", true, false, "当前字幕显示同步更新"),
        "subtitles:subtitleOutline" to global("开", true, false, "当前字幕显示同步更新"),
        "subtitles:subtitlePosition" to global("标准（8%）", true, false, "当前字幕显示同步更新"),
        "subtitles:subtitleBg" to global("关", true, false, "当前字幕显示同步更新"),
        "compat:playerEngine" to global("自动"),
        "compat:decoderPreference" to global("自动"),
        "compat:matchDisplayFrameRate" to global("关"),
        "compat:declareDtsDirectPlay" to global("关"),
        "compat:bootLaunch" to global("关", load = false, timing = "下次设备启动时尝试"),
        "compat:sourceDynamicRange" to global("不设置"),
        "compat:sourceBitrate" to global("不设置"),
        "compat:sourceReleaseGroups" to global("空（不按发布组排序）"),
    )

    fun forRow(key: String): SettingDeclaration? = entries[key] ?: when {
        key.startsWith("home:view:") || key == "home:show-all" || key == "home:hide-all" ->
            SettingDeclaration("全部显示", SettingScope.Account, false, false, "返回首页后生效")
        key.startsWith("account:edit:") || key == "account:current-edit" ->
            SettingDeclaration("服务器返回的名称", SettingScope.Global, false, false, "设备已保存服务器列表；名称即时更新")
        else -> null // Commands (logout, export, clear cache) are not saved preferences.
    }
}

internal fun choiceUsesDialog(rowKey: String, count: Int): Boolean = count >= 4 ||
    listOf("Language", "Bitrate", "ReleaseGroups", "server", "Engine").any { rowKey.contains(it, ignoreCase = true) }
