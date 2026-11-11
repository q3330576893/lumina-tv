package com.lumina.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.app.ActivityManager
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.PageLog
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {
    private fun root(context: Context) = context.getExternalFilesDir(null) ?: context.filesDir
    fun crashFile(context: Context) = File(root(context), "crash-latest.txt")
    fun crashFiles(context: Context) = (1..5).map { File(root(context), "crash-$it.txt") }
    private fun statsFile(context: Context) = File(root(context), "crash-stats.json")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrash(app, thread, throwable) } catch (_: Throwable) { }
            try { previous?.uncaughtException(thread, throwable) } catch (_: Throwable) { }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val frames = generateSequence(t as Throwable?) { it.cause }.toList()
        val fingerprint = MessageDigest.getInstance("SHA-1").digest(
            frames.firstOrNull()?.stackTrace.orEmpty().take(3).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }.toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val stats = runCatching { JSONObject(statsFile(context).takeIf(File::exists)?.readText().orEmpty()) }.getOrDefault(JSONObject())
        val entry = stats.optJSONObject(fingerprint) ?: JSONObject()
        entry.put("count", entry.optInt("count", 0) + 1).put("lastAt", now)
        stats.put(fingerprint, entry)
        statsFile(context).writeText(stats.toString(2), Charsets.UTF_8)
        if (entry.optInt("count") > 1) {
            context.getSharedPreferences("lumina_crash", Context.MODE_PRIVATE).edit().putBoolean("notice_pending", true).putString("fingerprint", fingerprint).commit()
            return
        }
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val appLog = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800", "--pid=${android.os.Process.myPid()}"))
                .inputStream.bufferedReader().useLines { it.toList().takeLast(100).joinToString("\n") }
        }.getOrDefault("app.log unavailable")
        val report = buildString {
            appendLine("[基础]")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} / versionCode=${if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else info?.versionCode}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("线程: ${thread.name} (${thread.id})")
            appendLine("\n[上下文]")
            appendLine("页面: ${AppRuntime.screenLabel}")
            appendLine("播放条目: ${AppRuntime.nowPlaying} / id=${AppRuntime.playingItemId}")
            appendLine("播放引擎: ${AppRuntime.activePlayerEngine}")
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val foreground = am?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            appendLine("前台: $foreground")
            appendLine("\n[内存]")
            appendLine("java heap used=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} max=${Runtime.getRuntime().maxMemory()}")
            appendLine("native heap=${Debug.getNativeHeapAllocatedSize()}")
            appendLine("\n[最近页面]\n${PageLog.tail().ifBlank { "(无)" }}")
            appendLine("\n[最近网络]\n${NetLog.tail()}")
            appendLine("\n[最近日志]\n$appLog")
            appendLine("\n[堆栈]")
            frames.forEachIndexed { i, cause ->
                appendLine("Cause #${i + 1}: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(30).forEach { appendLine("  at $it") }
            }
            appendLine("\n[指纹]\n$fingerprint")
        }
        val safe = DiagExporter.sanitize(report)
        crashFiles(context).asReversed().forEachIndexed { index, file ->
            val source = crashFiles(context).asReversed().getOrNull(index + 1)
            if (source?.exists() == true) runCatching { source.copyTo(file, overwrite = true) }
        }
        File(root(context), "crash-1.txt").writeText(safe, Charsets.UTF_8)
        crashFile(context).writeText(safe, Charsets.UTF_8)
        context.getSharedPreferences("lumina_crash", Context.MODE_PRIVATE).edit().putBoolean("notice_pending", true).putString("fingerprint", fingerprint).commit()
    }

    fun takeStartupNotice(context: Context): String? {
        val prefs = context.getSharedPreferences("lumina_crash", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notice_pending", false)) return null
        prefs.edit().putBoolean("notice_pending", false).commit()
        return "上次崩溃已记录（${prefs.getString("fingerprint", "未知")?.take(8)}）· 设置 → 诊断 → 复制崩溃报告"
    }

    fun diagnosticReport(context: Context): String = crashFile(context).takeIf(File::exists)?.readText().orEmpty()
    fun lastCrashSummary(context: Context): String? = crashFile(context).takeIf(File::exists)?.readLines()?.firstOrNull { it.startsWith("时间:") }
    fun clearCrash(context: Context) { runCatching { crashFiles(context).forEach { it.delete() }; crashFile(context).delete() } }
    fun shareReport(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "LuminaTV crash report"); putExtra(Intent.EXTRA_TEXT, diagnosticReport(context).take(9000)) }
        context.startActivity(Intent.createChooser(intent, "分享诊断包"))
    }
}
