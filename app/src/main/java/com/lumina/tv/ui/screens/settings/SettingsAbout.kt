package com.lumina.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.ui.components.DiagExportDialog
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
                Column(Modifier.fillMaxWidth().height(SettingsTokens.RowHeight).padding(horizontal = SettingsTokens.RowPaddingH, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
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
                                OkHttpClient().newCall(request).execute().use { response ->
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
                    fontSize = 11.sp,
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
                ActionRow("about:exportDiagnostics", "导出诊断包", "选择现象并保存到本机", if (showDiagExport) "进行中" else "导出", {
                    showDiagExport = true
                }, requesters.getValue(rowKeys[2]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[1]), downRequester = requesters.getValue(rowKeys[3]))
                ActionRow("about:copyCrashReport", "复制崩溃报告", "复制脱敏后的最近报告", "复制", {
                    runCatching {
                        val report = CrashHandler.crashFile(context).takeIf(java.io.File::exists)?.readText().orEmpty()
                        if (report.isBlank()) error("暂无崩溃报告")
                        clipboard.setText(AnnotatedString(DiagExporter.sanitize(report)))
                    }.onSuccess { Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.message ?: "复制失败", Toast.LENGTH_SHORT).show() }
                }, requesters.getValue(rowKeys[3]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[2]), downRequester = requesters.getValue(rowKeys[4]))
                NavigationRow("about:licenses", "开源许可", "Media3、mpv 与本机 FFmpeg", "查看", {
                    licensesOpen = true
                }, requesters.getValue(rowKeys[4]), railRequester, onFocused,
                    upRequester = requesters.getValue(rowKeys[3]), downRequester = requesters.getValue(rowKeys[4]), showDivider = false)
            }
        }
    }

    if (licensesOpen) AlertDialog(
        onDismissRequest = { licensesOpen = false },
        title = { Text("开源许可") },
        text = { Text("Media3\nmpv\n本机 FFmpeg") },
        confirmButton = { TextButton(onClick = { licensesOpen = false }) { Text("关闭") } },
    )
    DiagExportDialog(visible = showDiagExport, onDismiss = { showDiagExport = false }) { outcome ->
        withContext(Dispatchers.IO) {
            DiagExporter.export(context, DiagExporter.environmentPayload(context, outcome), "environment")
        }
    }
}
