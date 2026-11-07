package com.lumina.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获与诊断导出。
 * - 崩溃时把完整堆栈写入 App 外部目录，用户可通过系统分享发回给开发者
 * - 设置页可随时导出"诊断包"（设备信息 + 崩溃记录 + 运行状态，不含密码/token）
 */
object CrashHandler {

    private const val CRASH_FILE = "crash-latest.txt"

    fun crashFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, CRASH_FILE)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val text = buildString {
            appendLine("===== 琉璃TV 崩溃报告 =====")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            appendLine("线程: ${thread.name}")
            appendLine(deviceBlock())
            appendLine("----- 堆栈 -----")
            appendLine(android.util.Log.getStackTraceString(t))
            appendLine("----- 运行状态 -----")
            appendLine("页面: ${AppRuntime.screenLabel}")
            appendLine("播放: ${AppRuntime.nowPlaying}")
        }
        runCatching {
            val target = crashFile(context)
            val pending = File(target.parentFile, "$CRASH_FILE.pending")
            pending.outputStream().bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
            if (!pending.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
                pending.delete()
            }
            context.getSharedPreferences("lumina_crash", Context.MODE_PRIVATE)
                .edit().putBoolean("notice_pending", true).commit()
        }
    }

    fun takeStartupNotice(context: Context): String? {
        val preferences = context.getSharedPreferences("lumina_crash", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("notice_pending", false)) return null
        preferences.edit().putBoolean("notice_pending", false).commit()
        return lastCrashSummary(context)
    }

    fun deviceBlock(): String = buildString {
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("版本: 琉璃TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("内核: ${System.getProperty("os.version") ?: "-"}")
        appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString("/")}")
    }

    /** 生成完整诊断包文本。 */
    fun diagnosticReport(context: Context): String = buildString {
        appendLine("===== 琉璃TV 诊断包 =====")
        appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
        appendLine()
        appendLine(deviceBlock())
        appendLine()
        appendLine("----- 运行状态 -----")
        appendLine("页面: ${AppRuntime.screenLabel}")
        appendLine("正在播放: ${AppRuntime.nowPlaying.ifEmpty { "无" }}")
        appendLine("服务器: ${AppRuntime.session?.serverName ?: "未登录"}")
        appendLine("配对服务: ${if (AppRuntime.pairing != null) "运行中 ${AppRuntime.pairingUrl}" else (AppRuntime.pairingError ?: "未启动")}")
        appendLine()
        val cf = crashFile(context)
        if (cf.exists()) {
            appendLine("----- 最近崩溃 -----")
            appendLine(runCatching { cf.readText() }.getOrDefault("(读取失败)"))
        } else {
            appendLine("----- 最近崩溃 -----")
            appendLine("(无崩溃记录)")
        }
    }

    fun lastCrashSummary(context: Context): String? {
        val f = crashFile(context)
        if (!f.exists()) return null
        return runCatching {
            val lines = f.readLines()
            val time = lines.firstOrNull { it.startsWith("时间:") } ?: ""
            val first = lines.indexOfFirst { it == "----- 堆栈 -----" }
            val cause = if (first >= 0 && lines.size > first + 2) {
                lines.drop(first + 1).firstOrNull { it.contains("Exception") || it.contains("Error") } ?: ""
            } else ""
            listOf(time, cause).filter { it.isNotBlank() }.joinToString("  ")
        }.getOrNull()
    }

    fun clearCrash(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /** 调起系统分享面板发送诊断包。 */
    fun shareReport(context: Context) {
        val report = diagnosticReport(context)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "琉璃TV 诊断包")
            putExtra(Intent.EXTRA_TEXT, report.take(9000))
        }
        context.startActivity(Intent.createChooser(intent, "分享诊断包"))
    }
}
