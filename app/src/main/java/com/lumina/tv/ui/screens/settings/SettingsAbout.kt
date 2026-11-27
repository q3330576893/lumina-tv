package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.TypeScale

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.BuildConfig
import com.lumina.tv.CrashHandler
import com.lumina.tv.data.UpdateCheckHttp
import com.lumina.tv.data.readCancellable
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.ui.screens.exportDiagnosticBundle
import com.lumina.tv.ui.screens.environmentDiagnosticPayload
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private data class AboutLicenseNotice(val library: String, val license: String, val source: String)

// License declarations and source evidence are audited in THIRD-PARTY-NOTICES.md.
// A wrapper's license does not describe all native code bundled in its AAR.
private val aboutLicenseNotices = listOf(
    AboutLicenseNotice("Lumina TV", "GPL-3.0（仓库 LICENSE）；发布合规仍待复核", "https://github.com/q3330576893/lumina-tv/blob/main/LICENSE"),
    AboutLicenseNotice("AndroidX / Compose / TV / Media3 1.7.1", "Apache-2.0（组件 POM 声明）；native path 来源另行核对", "https://github.com/androidx/media/blob/1.7.1/LICENSE"),
    AboutLicenseNotice("Haze 1.5.0", "Apache-2.0（POM 声明）", "https://github.com/chrisbanes/haze"),
    AboutLicenseNotice("OkHttp 4.12.0", "Apache-2.0（POM 声明）", "https://github.com/square/okhttp/tree/parent-4.12.0"),
    AboutLicenseNotice("Coil 2.7.0", "Apache-2.0（POM 声明）", "https://github.com/coil-kt/coil/tree/2.7.0"),
    AboutLicenseNotice("NanoHTTPD 2.3.1", "BSD-3-Clause（上游 LICENSE.md）", "https://github.com/NanoHttpd/nanohttpd/blob/nanohttpd-project-2.3.1/LICENSE.md"),
    AboutLicenseNotice("ZXing Core 3.5.3", "Apache-2.0（上游 LICENSE）", "https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE"),
    AboutLicenseNotice("ZXing Android Embedded 4.3.0", "Apache-2.0（POM 声明）", "https://github.com/journeyapps/zxing-android-embedded"),
    AboutLicenseNotice("TinyPinyin 2.0.3", "Apache-2.0（上游 LICENSE）", "https://github.com/promeG/TinyPinyin/blob/c4c65b9c0c82cc8e60ef081e91f3cc66b7829dcc/LICENSE"),
    AboutLicenseNotice("Anime4K shaders", "MIT；Copyright (c) 2019-2021 bloc97（随附 shader 头部）", "https://github.com/bloc97/Anime4K"),
    AboutLicenseNotice("JellyCine libmpv 1.0.4 / libplayer", "MIT（wrapper / POM 声明）；所含 native 完整许可待核实", "https://github.com/sureshfizzy/libmpv-android/tree/920dd825f245d3b86fb964c7115948bf5b502b4b"),
    AboutLicenseNotice("mpv v0.41.0（产物自报）", "上游默认 GPL-2.0-or-later；产物含 GPL 功能，完整许可组合待核实", "https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright"),
    AboutLicenseNotice("FFmpeg n9.0.1（libmpv 内产物自报）", "GPL-3.0-or-later（产物自报）；对应源码与静态依赖许可待核实", "https://ffmpeg.org/legal.html"),
    AboutLicenseNotice("Jellyfin Media3 FFmpeg decoder 1.6.1+2", "GPL-3.0（POM 声明）；libffmpegJNI 产物与源码对应关系待核实", "https://github.com/jellyfin/jellyfin-androidx-media/tree/414d3fca8321b4875a5272f852a2abebe24b1bfe"),
    AboutLicenseNotice("LLVM libc++_shared", "Apache-2.0 WITH LLVM-exception（上游）；确切 NDK / revision 待核实", "https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT"),
)

@Composable
fun SettingsAbout(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf("尚未检查") }
    var updateSuccess by remember { mutableStateOf(false) }
    var releaseUrl by remember { mutableStateOf<String?>(null) }
    var showDiagExport by remember { mutableStateOf(false) }
    var licensesOpen by remember { mutableStateOf(false) }

    val rowKeys = remember { listOf("about:checkUpdate", "about:releasePage", "about:exportDiagnostics", "about:copyCrashReport", "about:licenses") }
    val requesters = remember(firstFocusRequester) {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LazyColumn(state = settingsListState(SettingsCategory.About), modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
        item(key = "about:group-label") {
            Text("关于与诊断", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "about:group") {
            Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape)) {
                Column(Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight).padding(horizontal = SettingsTokens.RowPaddingH, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                    Text("当前版本", color = TextMain, fontSize = SettingsTokens.RowTitleSize)
                    Text(BuildConfig.VERSION_NAME, color = TextSub, fontSize = SettingsTokens.RowDescSize)
                }
                ActionRow("about:checkUpdate", "检查更新", "查询 GitHub 最新发布", if (checkingUpdate) "检查中…" else "检查", {
                    if (!checkingUpdate) scope.launch {
                        checkingUpdate = true
                        updateResult = "正在检查…"
                        updateSuccess = false
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val request = Request.Builder()
                                    .url("https://api.github.com/repos/q3330576893/lumina-tv/releases/latest")
                                    .header("Accept", "application/vnd.github+json")
                                    .header("User-Agent", "LuminaTV/${BuildConfig.VERSION_NAME}")
                                    .build()
                                UpdateCheckHttp.client.readCancellable(request) { response ->
                                    if (!response.isSuccessful) error("HTTP ${response.code}")
                                    val json = JSONObject(response.body?.string().orEmpty())
                                    val tag = json.optString("tag_name")
                                    val url = json.optString("html_url")
                                    if (tag.isBlank() || url.isBlank()) error("发布信息不完整")
                                    val latest = tag.removePrefix("v").substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
                                    val current = BuildConfig.VERSION_NAME.split('.').mapNotNull(String::toIntOrNull)
                                    val newer = (0 until maxOf(latest.size, current.size)).firstNotNullOfOrNull { index ->
                                        val delta = (latest.getOrElse(index) { 0 }).compareTo(current.getOrElse(index) { 0 })
                                        delta.takeIf { it != 0 }
                                    }?.let { it > 0 } ?: false
                                    url to if (newer) "发现新版本 $tag" else "当前已是最新版本"
                                }
                            }
                        }
                        result.onSuccess { (url, message) ->
                            releaseUrl = url
                            updateResult = message
                            updateSuccess = true
                        }.onFailure {
                            updateResult = "检查失败：${it.message ?: "网络不可用"}"
                            updateSuccess = false
                        }
                        checkingUpdate = false
                    }
                }, requesters.getValue(rowKeys[0]), railRequester, onFocused,
                    downRequester = requesters.getValue(rowKeys[1]))
                Text(
                    updateResult,
                    color = when {
                        checkingUpdate -> TextSub
                        updateSuccess -> Color(0xFF8AD9AE)
                        updateResult.startsWith("检查失败") -> Color(0xFFFF8A9A)
                        else -> TextSub
                    },
                    fontSize = TypeScale.sp11,
                    modifier = Modifier.padding(start = SettingsTokens.RowPaddingH, bottom = 8.dp),
                )
                ActionRow("about:releasePage", "打开发布页", "打开 GitHub Releases", "打开", {
                    val url = releaseUrl
                    if (url == null) {
                        Toast.makeText(context, "请先检查更新", Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "打开失败：${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }, requesters.getValue(rowKeys[1]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[0]), downRequester = requesters.getValue(rowKeys[2]))
                ActionRow("about:exportDiagnostics", "导出诊断包", "选择现象并保存或分享", if (showDiagExport) "进行中" else "导出", {
                    showDiagExport = true
                }, requesters.getValue(rowKeys[2]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[1]), downRequester = requesters.getValue(rowKeys[3]))
                ActionRow("about:copyCrashReport", "复制崩溃报告", "复制脱敏后的最近报告", "复制", {
                    scope.launch {
                        try {
                            val report = withContext(Dispatchers.IO) { CrashHandler.diagnosticReport(context) }
                            if (report.isBlank()) error("暂无崩溃报告")
                            clipboard.setText(AnnotatedString(report))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Toast.makeText(context, failure.message ?: "复制失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, requesters.getValue(rowKeys[3]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[2]), downRequester = requesters.getValue(rowKeys[4]))
                NavigationRow("about:licenses", "开源许可", "组件许可、来源与待核实项", "查看", {
                    licensesOpen = true
                }, requesters.getValue(rowKeys[4]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[3]), downRequester = requesters.getValue(rowKeys[4]), showDivider = false)
            }
        }
    }

    if (licensesOpen) AlertDialog(
        onDismissRequest = { licensesOpen = false },
        title = { Text("开源许可") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("技术来源核对，不构成法律合规结论。native 来源证据尚未齐备，发布门禁为 BLOCKED。完整清单见仓库 THIRD-PARTY-NOTICES.md 与 docs/native-sources.md。")
                }
                items(aboutLicenseNotices.size, key = { aboutLicenseNotices[it].library }) { index ->
                    val notice = aboutLicenseNotices[index]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notice.library)
                        Text(notice.license, fontSize = TypeScale.sp11)
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.source))) }
                                .onFailure { Toast.makeText(context, "无法打开来源链接", Toast.LENGTH_SHORT).show() }
                        }) { Text("来源与许可（浏览器）") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { licensesOpen = false }) { Text("关闭") } },
    )
    DiagExportDialog(visible = showDiagExport, onDismiss = { showDiagExport = false }) { outcome ->
        withContext(Dispatchers.IO) {
            exportDiagnosticBundle(context, environmentDiagnosticPayload(context, outcome), "environment")
        }
    }
}
