package com.lumina.tv.diag

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.lumina.tv.AppRuntime
import com.lumina.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagPayload(
    val metaJson: String,
    val playbackInfoJson: String,
    val streamsJson: String,
    val tracksJson: String,
    val errorsJson: String,
    val mpvLog: String,
    val appLog: String,
    val uiText: String,
)

object DiagExporter {
    private const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024

    fun environmentPayload(context: Context, outcome: String): DiagPayload {
        val prefs = AppRuntime.prefs
        val meta = baseMeta(context, outcome).put("engine", JSONObject()
            .put("prefs", prefs?.playerEngine ?: "unknown")
            .put("active", "unknown")
            .put("mpvAvailable", "not probed")
            .put("kernels", JSONArray().put("media3").put("mpv")))
        return DiagPayload(
            metaJson = meta.toString(2),
            playbackInfoJson = JSONObject().put("reason", "No playback in progress; playback-info unavailable").toString(2),
            streamsJson = JSONObject().put("reason", "No playback in progress").toString(2),
            tracksJson = JSONObject().put("reason", "No playback in progress").toString(2),
            errorsJson = JSONObject().put("reason", prefs?.lastPlaybackAudioFailure?.ifBlank { null } ?: "No playback error recorded").toString(2),
            mpvLog = runCatching { File(context.filesDir, "mpv-session.log").takeIf(File::exists)?.readText(Charsets.UTF_8) }
                .getOrNull()?.ifBlank { "No mpv log messages captured" } ?: "mpv log unavailable: no session log file",
            appLog = appLogTail(),
            uiText = "screen=${AppRuntime.screenLabel}\nplaying=${AppRuntime.nowPlaying}\nactiveEngine=unknown\nbuffering=unknown\ndegraded=unknown",
        )
    }

    fun baseMeta(context: Context, outcome: String): JSONObject {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        manager?.defaultDisplay?.getRealMetrics(metrics)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("app", JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode)
                .put("packageName", context.packageName))
            .put("build", JSONObject()
                .put("brand", Build.BRAND).put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("device", Build.DEVICE).put("hardware", Build.HARDWARE)
                .put("sdkInt", Build.VERSION.SDK_INT).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS))
                .put("screen", "${metrics.widthPixels}x${metrics.heightPixels}").put("density", metrics.density))
            .put("outcome", outcome)
    }

    fun appLogTail(): String = runCatching {
        val process: Process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800", "--pid=${android.os.Process.myPid()}"))
        val lines: List<String> = process.inputStream.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
        process.waitFor()
        lines.takeLast(800).joinToString("\n")
    }.getOrElse { "app.log unavailable: ${it.message ?: it::class.java.simpleName}" }

    fun export(context: Context, payload: DiagPayload, kind: String = "playback"): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "diag").apply { mkdirs() }
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "lumina-diag-$kind-$time.zip")
        val files = linkedMapOf(
            "meta.json" to payload.metaJson,
            "playback-info.json" to payload.playbackInfoJson,
            "streams.json" to payload.streamsJson,
            "tracks.json" to payload.tracksJson,
            "errors.json" to payload.errorsJson,
            "mpv.log" to payload.mpvLog,
            "app.log" to payload.appLog,
            "ui.txt" to payload.uiText,
        )
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            files.forEach { (name, original) ->
                val limit = when (name) {
                    "app.log" -> 300 * 1024
                    "mpv.log" -> 512 * 1024
                    else -> 120 * 1024
                }
                val sanitized = sanitize(original).take(limit)
                zip.putNextEntry(ZipEntry(name))
                zip.write(sanitized.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        if (out.length() > MAX_ARCHIVE_BYTES) throw IllegalStateException("诊断包超过 2MB")
        return out
    }

    fun sanitize(text: String): String {
        val keyValue = Regex("(?i)(\\\"?(?:api_key|x-emby-token|authorization|password|passwd|pw|token)\\\"?\\s*[:=]\\s*\\\")[^\\\"]*(\\\")")
        val query = Regex("(?i)(api_key|x-emby-token|authorization|password|passwd|pw|token)=([^&\\s]+)")
        val header = Regex("(?im)(\\b(?:api_key|x-emby-token|authorization|password|passwd|pw|token)\\b\\s*:\\s*)[^\\r\\n]+")
        return text.replace(keyValue, "$1***$2").replace(query, "$1=***").replace(header, "$1***")
    }
}
