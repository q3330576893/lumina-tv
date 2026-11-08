package com.lumina.tv.ui.components

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

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
                    Spacer(Modifier.height(8.dp))
                    Text("用 U 盘或局域网把这个文件发给开发者。")
                }
            }
        },
        confirmButton = {
            if (resultPath == null) Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { export(outcome) }
                        .onSuccess { resultPath = it.absolutePath }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
                    busy = false
                }
            }) { Text(if (busy) "正在导出" else "导出") }
            else TextButton(onClick = {
                resultPath?.let { path ->
                    runCatching { clipboard.setText(AnnotatedString(path)) }
                    Toast.makeText(context, "已保存：$path", Toast.LENGTH_LONG).show()
                }
            }) { Text("复制路径") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (resultPath == null) "取消" else "关闭") } },
    )
}
