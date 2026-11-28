package com.lumina.tv.ui.screens.settings

enum class SettingsCategory(val key: String, val label: String) {
    Account("account", "账号与服务器"),
    Appearance("appearance", "外观与玻璃"),
    Home("home", "首页"),
    Playback("playback", "播放"),
    Subtitles("subtitles", "字幕与音轨"),
    Compatibility("compat", "播放器与兼容性"),
    About("about", "关于与诊断"),
}
