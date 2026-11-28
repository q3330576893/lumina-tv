package com.lumina.tv.ui.components

import android.widget.Toast
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import org.json.JSONObject
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private val DIAG_OUTCOMES = listOf("黑屏", "卡缓冲", "没声音", "花屏", "报错退出", "环境日志")

@Composable
fun DiagExportDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    export: suspend (String) -> File,
) {
    if (!visible) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf(DIAG_OUTCOMES.first()) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resultPath by remember { mutableStateOf<String?>(null) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var missingDiagnosticFacts by remember { mutableStateOf(0) }
    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = exportedFile
        if (uri != null && source != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("无法打开目标文件")
                    output.use { destination ->
                        source.inputStream().use { input -> input.copyTo(destination) }
                    }
                }
            }.onSuccess {
                Toast.makeText(context, "诊断包已保存到所选位置", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (resultPath == null) "导出播放诊断包" else "诊断包已保存") },
        text = {
            Column {
                if (resultPath == null) {
                    Text("现象：$outcome", modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DIAG_OUTCOMES.forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { outcome = value; expanded = false })
                        }
                    }
                    TextButton(onClick = { expanded = true }) { Text("选择现象") }
                    Text(if (busy) "正在打包…" else "文件只保存在本机，不会上传。")
                } else {
                    Text(resultPath.orEmpty())
                    if (missingDiagnosticFacts > 0) {
                        Text("本次诊断包不完整：缺 $missingDiagnosticFacts 项")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("可以保存到所选位置，或通过已安装应用分享。")
                    TextButton(onClick = {
                        exportedFile?.let { file ->
                            runCatching { saveZipLauncher.launch(file.name) }
                                .onFailure { Toast.makeText(context, "无法打开保存位置：${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("保存到其他位置…") }
                    TextButton(onClick = {
                        exportedFile?.let { file ->
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, "LuminaTV诊断包", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "分享诊断包"))
                            }.onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("分享") }
                    TextButton(onClick = {
                        resultPath?.let { path ->
                            runCatching { clipboard.setText(AnnotatedString(path)) }
                            Toast.makeText(context, "已保存：$path", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("复制路径") }
                }
            }
        },
        confirmButton = {
            if (resultPath == null) Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        val file = export(outcome)
                        val missing = withContext(Dispatchers.IO) {
                            ZipFile(file).use { zip ->
                                val entry = zip.getEntry("diagnostics-gap.json")
                                if (entry == null) 0 else {
                                    val json = JSONObject(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
                                    json.optJSONArray("missing")?.length() ?: 0
                                }
                            }
                        }
                        file to missing
                    }
                        .onSuccess { (file, missing) ->
                            exportedFile = file
                            missingDiagnosticFacts = missing
                            resultPath = file.absolutePath
                        }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
                    busy = false
                }
            }) { Text(if (busy) "正在导出" else "导出") }
            else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (resultPath == null) ({ TextButton(onClick = onDismiss) { Text("取消") } }) else null,
    )
}
