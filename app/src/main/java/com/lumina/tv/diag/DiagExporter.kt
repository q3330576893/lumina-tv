package com.lumina.tv.diag

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.lumina.tv.AppRuntime
import com.lumina.tv.BuildConfig
import com.lumina.tv.player.MpvAvailabilityProbe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        val engine = MpvAvailabilityProbe.process.snapshot(context).addTo(JSONObject()
            .put("prefs", prefs?.playerEngine ?: "unknown")
            .put("active", "unknown")
            .put("kernels", JSONArray().put("media3").put("mpv")))
        val meta = baseMeta(context, outcome).put("engine", engine)
        return DiagPayload(
            metaJson = meta.toString(2),
            playbackInfoJson = JSONObject().put("reason", "No playback in progress; playback-info unavailable").toString(2),
            streamsJson = JSONObject().put("reason", "No playback in progress").toString(2),
            tracksJson = JSONObject().put("reason", "No playback in progress").toString(2),
            errorsJson = JSONObject().put("reason", prefs?.lastPlaybackAudioFailure?.ifBlank { null } ?: "No playback error recorded").toString(2),
            mpvLog = runCatching { File(context.filesDir, "mpv-session.log").takeIf(File::exists)?.let { readPrefix(it, 512 * 1024) } }
                .getOrNull()?.ifBlank { "No mpv log messages captured" } ?: "mpv log unavailable: no session log file",
            appLog = "",
            uiText = "screen=${AppRuntime.screenLabel}\nplaying=${AppRuntime.nowPlaying}\nactiveEngine=unknown\nbuffering=unknown\ndegraded=unknown\n视频动态范围: ${AppRuntime.currentVideoRange} / 引擎: ${AppRuntime.activePlayerEngine}",
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

    fun appLogTail(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "app.log unavailable: logcat capture requires a background thread"
        }
        val timeoutMs = 1500L
        val timeoutMessage = "app.log unavailable: logcat timed out after ${timeoutMs}ms"
        val capture = FutureTask {
            val process = ProcessBuilder("logcat", "-d", "-v", "epoch", "-t", "800", "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true)
                .start()
            val output = FutureTask {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.toList().takeLast(800).joinToString("\n")
                }
            }
            try {
                Thread(output, "lumina-logcat-reader").apply { isDaemon = true }.start()
                if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) output.get() else timeoutMessage
            } finally {
                output.cancel(true)
                process.destroyForcibly()
            }
        }
        return try {
            Thread(capture, "lumina-logcat-capture").apply { isDaemon = true }.start()
            capture.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            timeoutMessage
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "app.log unavailable: logcat capture interrupted"
        } catch (failure: Exception) {
            val cause = failure.cause ?: failure
            "app.log unavailable: ${cause.message ?: cause::class.java.simpleName}"
        } finally {
            capture.cancel(true)
        }
    }

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
            "app.log" to (AppLog.snapshot() + "\n[logcat]\n" + appLogTail()),
            "mpv-audio.jsonl" to File(context.filesDir, "mpv-audio.jsonl").takeIf(File::exists)?.let { readPrefix(it, 384 * 1024) }.orEmpty(),
            "mpv-audio-system.log" to File(context.filesDir, "mpv-audio-system.log").takeIf(File::exists)?.let { readPrefix(it, 384 * 1024) }.orEmpty(),
            "ui.txt" to (payload.uiText + "\n视频动态范围: ${AppRuntime.currentVideoRange} / 引擎: ${AppRuntime.activePlayerEngine}"),
            "net.json" to NetLog.tail(),
            "pages.txt" to PageLog.tail(),
            "crash-stats.json" to File(root, "crash-stats.json").takeIf(File::exists)?.let { readPrefix(it) }.orEmpty(),
            "lastSession.json" to File(root, "last-session.json").takeIf(File::exists)?.let { readPrefix(it) }.orEmpty(),
            "exitReason.json" to File(root, "exit-reason.json").takeIf(File::exists)?.let { readPrefix(it) }.orEmpty(),
            "anr-latest.txt" to File(root, "anr-latest.txt").takeIf(File::exists)?.let { readPrefix(it) }.orEmpty(),
            "native-latest.txt" to File(root, "native-latest.txt").takeIf(File::exists)?.let { readPrefix(it) }.orEmpty(),
        )
        File(context.filesDir, "app-prev.log").takeIf(File::exists)?.let {
            files["app-prev.log"] = readPrefix(it)
        }
        (1..5).forEach { n -> File(root, "crash-$n.txt").takeIf(File::exists)?.let { files["crash-$n.txt"] = readPrefix(it) } }
        File(root, "crash-latest.txt").takeIf(File::exists)?.let { files["crash-latest.txt"] = readPrefix(it) }
        return writeArchive(out, files)
    }

    private fun readPrefix(file: File, limit: Int = 120 * 1024): String {
        val fileLength = file.length()
        val readLimit = if (file.extension.equals("json", ignoreCase = true) && fileLength <= limit.toLong() * 4L) {
            fileLength.toInt()
        } else {
            fileLength.coerceAtMost(limit.toLong()).toInt()
        }
        val buffer = ByteArray(readLimit)
        var bytesRead = 0
        try {
            file.inputStream().use { input ->
                while (bytesRead < readLimit) {
                    val count = input.read(buffer, bytesRead, readLimit - bytesRead)
                    if (count < 0) break
                    bytesRead += count
                }
            }
        } finally {
            Log.i("DiagExporter", "diagExport size=$bytesRead file=${file.name}")
        }
        return String(buffer, 0, bytesRead, Charsets.UTF_8)
    }

    internal fun writeArchive(out: File, files: Map<String, String>): File {
        var completed = false
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                files.forEach { (name, original) ->
                    val limit = when (name) {
                        "app.log", "app-prev.log" -> 120 * 1024
                        "mpv.log" -> 512 * 1024
                        "mpv-audio.jsonl", "mpv-audio-system.log" -> 384 * 1024
                        else -> 120 * 1024
                    }
                    val isJson = name.endsWith(".json", ignoreCase = true)
                    val sanitized = if (isJson) LogRedactor.redactJson(original) else sanitize(original)
                    val bounded = if (isJson) {
                        boundedDiagnosticJson(sanitized, limit)
                    } else {
                        sanitized.take(limit)
                    }
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bounded.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            if (out.length() > MAX_ARCHIVE_BYTES) throw IllegalStateException("诊断包超过 2MB")
            completed = true
            return out
        } finally {
            if (!completed) out.delete()
        }
    }

    fun sanitize(text: String): String {
        val sanitized = LogRedactor.redact(text)
        return if (sanitized.startsWith("[基础]") || sanitized.startsWith("[鍩虹]")) {
            sanitized.replace(Regex("(?m)^视频动态范围:.*(?:\\r?\\n)?"), "") +
                "\n视频动态范围: ${AppRuntime.currentVideoRange} / 引擎: ${AppRuntime.activePlayerEngine}\n"
        } else sanitized
    }
}

/** One redaction policy for both log ingestion and diagnostic export. No Android dependencies. */
object LogRedactor {
    private const val SECRET_KEYS =
        "api_key|apikey|x-emby-token|x-emby-authorization|x-mediabrowser-token|access_token|authorization|password|passwd|pw|token|cookie|set-cookie|pass|pwd|secret|jsessionid"
    private const val BASE_URL_KEYS = "baseUrl|base_url"
    private val encodedKeys = SECRET_KEYS.replace("-", "(?:-|%2d)").replace("_", "(?:_|%5f)")
    private const val QUOTED_VALUE = """"(?:\\.|[^"\\])*+"|'(?:\\.|[^'\\])*+'"""
    private val keyValue = Regex(
        """(?i)((?<![\w-])["']?(?:$SECRET_KEYS)["']?\s*[:=]\s*)($QUOTED_VALUE|(?:(?:Bearer|Basic)[ \t]+)?[^,}\s&"'<>]+)"""
    )
    private val query = Regex("""(?i)(?<![\w-])((?:$SECRET_KEYS)=)([^&\s#"'<>\\]+)""")
    private val encodedQuery = Regex(
        """(?i)(?<![\w-])((?:$encodedKeys)%3d)(%22[^\r\n]*?%22|%27[^\r\n]*?%27|[^&\s#"'<>\\]+?(?=%26|%23|%22|%27|%0[ad]|[&\s#"'<>\\]|$))"""
    )
    // Unquoted headers can contain schemes, spaces and commas. Quoted JSON keys use keyValue.
    private val header = Regex(
        """(?im)(?<![\w"'%-])((?:$SECRET_KEYS)\b[ \t]*:[ \t]*)[^\r\n]+"""
    )
    private val quotedHeader = Regex(
        """(?i)(["'])((?:$SECRET_KEYS)\b[ \t]*:[ \t]*)(?:\\[^\r\n]|(?!\1)[^\r\n])*+(\1)?"""
    )
    private val baseUrl = Regex(
        """(?i)((?<![\w-])["']?(?:$BASE_URL_KEYS)["']?\s*[:=]\s*)($QUOTED_VALUE|[^,}\s]+)"""
    )
    private val baseUrlField = Regex("""(?i)^(?:$BASE_URL_KEYS)$""")
    private val urlUserInfo = Regex("""(?i)(https?://)[^\s/?#<>"']*@""")
    private val secretField = Regex("""(?i)^(?:$SECRET_KEYS)$""")

    /** Redact JSON values structurally so punctuation in a string cannot damage the document. */
    fun redactJson(text: String): String = try {
        val tokener = org.json.JSONTokener(text)
        val value = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "Trailing data after JSON value" }
        redactJsonValue(value).toString()
    } catch (_: Exception) {
        redact(text)
    }

    private fun redactJsonValue(value: Any?): Any = when (value) {
        is JSONObject -> JSONObject().apply {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val field = value.opt(key)
                put(key, when {
                    secretField.matches(key) -> "***"
                    baseUrlField.matches(key) -> when (field) {
                        is String -> baseUrlHost(field)
                        null, JSONObject.NULL -> JSONObject.NULL
                        else -> "***"
                    }
                    else -> redactJsonValue(field)
                })
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(redactJsonValue(value.opt(index)))
        }
        is String -> redact(value)
        null -> JSONObject.NULL
        else -> value
    }

    fun redact(s: String): String {
        // Also applies to nested baseUrl fields in meta.json and last-session.json.
        val hostsOnly = s.replace(baseUrl) { match ->
            val value = match.groupValues[2]
            if (value == "null") return@replace match.value
            val quote = value.first().takeIf { it == '"' || it == '\'' }
            val raw = if (quote != null) value.substring(1, value.length - 1) else value
            val host = baseUrlHost(raw)
            match.groupValues[1] + if (quote != null) "$quote$host$quote" else host
        }
        return hostsOnly
            .replace(urlUserInfo, "$1***@")
            .replace(quotedHeader, "$1$2***$3")
            .replace(header, "$1***")
            .replace(keyValue) { it.groupValues[1] + mask(it.groupValues[2]) }
            .replace(query, "$1***")
            .replace(encodedQuery) { it.groupValues[1] + mask(it.groupValues[2]) }
    }

    private fun baseUrlHost(raw: String): String = runCatching {
        val url = raw.replace("\\/", "/")
        URI(if ("://" in url || url.startsWith("//")) url else "//$url").host
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "***"

    private fun mask(value: String): String = when {
        value.startsWith('"') -> "\"***\""
        value.startsWith('\'') -> "'***'"
        value.startsWith("%22", ignoreCase = true) -> "%22***%22"
        value.startsWith("%27", ignoreCase = true) -> "%27***%27"
        else -> "***"
    }
}
