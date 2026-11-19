package com.lumina.tv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Track fallback must retain a certificate cause instead of replacing it with a generic notice. */
internal fun missingSubtitleTrackNotice(previous: String?): String =
    previous?.takeIf { it.contains("CERT_INVALID") } ?: "记住的字幕轨不存在，已恢复自动选择"

/** The JNI command only sees bounded local VTT files, never an HTTP URL/unfinished response. */
internal class ExternalSubtitleDownload(client: OkHttpClient, timeoutMs: Long = 8_000L) {
    private val http = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    fun <I> fetch(url: String, headers: Map<String, String>, file: File, work: LoadScopedIo<I>): File {
        val call = http.newCall(Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build())
        val unregister = work.cancelWith { call.cancel() }
        try {
            work.checkCurrent()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Subtitle HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty subtitle body")
                if (body.contentLength() > MAX_BYTES) throw IOException("Subtitle exceeds $MAX_BYTES bytes")
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        work.checkCurrent()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_BYTES) throw IOException("Subtitle exceeds $MAX_BYTES bytes")
                        output.write(buffer, 0, size)
                    }
                } }
            }
            // PlayerScreen requests VTT. Refuse a playlist/HTML masquerading as a subtitle, which
            // could otherwise make even a local sub-add perform another unbounded network open.
            val prefix = file.inputStream().use { input -> ByteArray(10).let { bytes ->
                val count = input.read(bytes); if (count < 0) "" else String(bytes, 0, count, Charsets.UTF_8)
            } }.removePrefix("\uFEFF")
            if (!Regex("^WEBVTT(?:[ \\t\\r\\n]|$)").containsMatchIn(prefix)) throw IOException("Expected WebVTT subtitle")
            work.checkCurrent()
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        } finally { unregister() }
    }

    companion object { const val MAX_BYTES = 4L * 1024L * 1024L }
}
