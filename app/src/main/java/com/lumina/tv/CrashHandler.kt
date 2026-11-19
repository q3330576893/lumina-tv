package com.lumina.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.PageLog
import com.lumina.tv.diag.CrashStore
import com.lumina.tv.diag.ExitTraceKind
import com.lumina.tv.diag.ExitTraceReader
import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.ProcessCrashHandler
import com.lumina.tv.diag.AppLog
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
    private val installation = ProcessCrashHandler()

    fun install(context: Context) {
        val app = context.applicationContext
        installation.install(
            write = { thread, throwable -> writeCrash(app, thread, throwable) },
            collectPreviousExit = {
                runCatching { recordPreviousExit(app) }
                    .onFailure { PageLog.add("previous exit collection failed=${it.javaClass.simpleName}") }
            },
        )
    }

    private fun recordPreviousExit(context: Context) {
        val exit = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 8).firstOrNull()
            } else null
        }.getOrNull()
        val payload = if (exit != null) JSONObject()
            .put("reason", when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_KILLED"
                else -> "OTHER_${exit.reason}"
            }).put("description", LogRedactor.redact(exit.description ?: ""))
            .put("timestamp", exit.timestamp).put("importance", exit.importance)
        else JSONObject().put("reason", "legacy-crash-file")
            .put("description", crashFile(context).takeIf(File::exists)?.readLines()?.firstOrNull().orEmpty())
            .put("timestamp", JSONObject.NULL).put("importance", JSONObject.NULL)
        val kind = when (exit?.reason) {
            ApplicationExitInfo.REASON_ANR -> ExitTraceKind.ANR
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitTraceKind.NATIVE
            else -> null
        }
        if (kind != null && exit != null) {
            val trace = ExitTraceReader.read(kind, Build.VERSION.SDK_INT) { exit.traceInputStream }
            payload.put("trace", JSONObject().put("source", "ApplicationExitInfo.getTraceInputStream")
                .put("format", kind.format).put("status", trace.status)
                .put("capturedBytes", trace.bytes.size).put("truncated", trace.truncated))
            var evidence = trace.report()
            if (kind == ExitTraceKind.NATIVE) {
                // A raw tombstone can contain secrets: retain privately, never add it to the shared ZIP.
                val stored = runCatching {
                    if (trace.status == "available") File(context.filesDir, trace.nativeFileName).writeBytes(trace.bytes)
                    listOf("native-trace-latest.pb", "native-trace-latest.pb.partial")
                        .filter { trace.status != "available" || it != trace.nativeFileName }
                        .forEach { File(context.filesDir, it).delete() }
                }.fold(
                    onSuccess = { if (trace.status == "available") "stored" else "not_available" },
                    onFailure = { "write_failed:${it.javaClass.simpleName}" },
                )
                payload.getJSONObject("trace").put("binaryPersistence", stored)
                    .put("binaryFile", if (stored == "stored") trace.nativeFileName else JSONObject.NULL)
                evidence += "\nbinaryPersistence=$stored"
            }
            val name = if (kind == ExitTraceKind.ANR) "anr-latest.txt" else "native-latest.txt"
            runCatching { File(root(context), name).writeText(evidence, Charsets.UTF_8) }
            PageLog.add("previousExit ${kind.name} ${trace.summary}")
            writeCrashType(context, kind.name, evidence, exit.timestamp)
        }
        runCatching { File(root(context), "exit-reason.json").writeText(payload.toString(2), Charsets.UTF_8) }
    }

    private fun writeCrashType(context: Context, type: String, evidence: String, at: Long) {
        runCatching {
            CrashStore(root(context)).recordExit(type, evidence, at)
        }.onFailure { PageLog.add("crash stats write failed=${it.javaClass.simpleName}") }
    }

    @Synchronized private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val frames = generateSequence(t as Throwable?) { it.cause }.toList()
        val fingerprint = MessageDigest.getInstance("SHA-1").digest(
            frames.firstOrNull()?.stackTrace.orEmpty().take(3).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }.toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
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
            appendLine("\n[最近日志]\n${AppLog.snapshot()}\n[logcat]\n$appLog")
            appendLine("\n[堆栈]")
            frames.forEachIndexed { i, cause ->
                appendLine("Cause #${i + 1}: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(30).forEach { appendLine("  at $it") }
            }
            appendLine("\n[指纹]\n$fingerprint")
        }
        val safe = DiagExporter.sanitize(report)
        CrashStore(root(context)).recordJava(fingerprint, safe, now)
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
