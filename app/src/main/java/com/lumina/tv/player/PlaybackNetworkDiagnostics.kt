package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import com.lumina.tv.data.ORIGIN_AUTH_RESPONSE_HEADER
import com.lumina.tv.data.isSameOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class PlaybackErrorClassification(val category: PlaybackErrorCategory, val code: Int, val action: String)

fun classifyPlaybackError(error: PlaybackException, embyServerUrl: String? = null): PlaybackErrorClassification {
    val causes = playbackErrorCauses(error).toList()
    val responses = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
    val statusCodes = responses.map { it.responseCode }
    val authResponses = responses.filter { it.responseCode == 401 || it.responseCode == 403 }
    val embyAuthFailure = authResponses.any { response ->
        when (response.headerFields.entries.firstOrNull { it.key.equals(ORIGIN_AUTH_RESPONSE_HEADER, true) }?.value?.singleOrNull()) {
            "emby" -> true
            "external" -> false
            // Preserve unscoped callers; playback supplies an explicit server, including an empty
            // value when unavailable. With that context, unknown origin is never session evidence.
            else -> embyServerUrl == null || isSameOrigin(embyServerUrl, response.dataSpec?.uri?.toString())
        }
    }
    val category = when {
        isCertificateFailure(error) -> PlaybackErrorCategory.CERT_INVALID
        embyAuthFailure -> PlaybackErrorCategory.AUTH_FAILED
        authResponses.isNotEmpty() -> PlaybackErrorCategory.HTTP_PERMISSION
        statusCodes.any { it == 404 || it == 410 } -> PlaybackErrorCategory.SOURCE_GONE
        statusCodes.any { it == 408 || it == 429 || it in 500..599 } -> PlaybackErrorCategory.NETWORK_TRANSIENT
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> PlaybackErrorCategory.AUDIO_OUTPUT
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> PlaybackErrorCategory.RESOURCE_RECLAIMED
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || statusCodes.any { it == 404 || it == 410 } -> PlaybackErrorCategory.SOURCE_GONE
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> PlaybackErrorCategory.HTTP_CONTENT_INVALID
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any { it is SSLException } -> PlaybackErrorCategory.NETWORK_TRANSIENT
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackErrorCategory.OTHER
        error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackErrorCategory.HTTP_PERMISSION
        error.errorCode in setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ) -> PlaybackErrorCategory.PARSING
        error.errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ) -> PlaybackErrorCategory.DECODING
        else -> PlaybackErrorCategory.OTHER
    }
    val action = if (category == PlaybackErrorCategory.HTTP_PERMISSION && authResponses.isNotEmpty())
        "refresh_link_or_change_source" else playbackErrorAction(category)
    return PlaybackErrorClassification(category, error.errorCode, action)
}

internal fun playbackErrorCauses(error: Throwable): Sequence<Throwable> = sequence {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

internal const val MAX_PLAYBACK_NETWORK_RETRY_DELAY_MS = 60_000L

/** Null declines automatic retry when the server asks us to wait beyond the bounded recovery window. */
internal fun playbackNetworkRetryDelayMs(error: PlaybackException, retryIndex: Int, nowMs: Long = System.currentTimeMillis()): Long? {
    if (retryIndex !in 0..1) return null
    val headers = playbackErrorCauses(error)
        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.headerFields.orEmpty()
    val serverDelay = playbackRetryAfterMs(headers, nowMs)
    if (serverDelay != null && serverDelay > MAX_PLAYBACK_NETWORK_RETRY_DELAY_MS) return null
    return maxOf(if (retryIndex == 0) 1_000L else 3_000L, serverDelay ?: 0L)
}

internal fun playbackRetryAfterMs(headers: Map<String, List<String>>, nowMs: Long): Long? {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()?.trim()
    val raw = header("Retry-After")?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.all { it in '0'..'9' }) {
        // Saturate rather than overflow a very large delay into an immediate retry.
        return (raw.toLongOrNull() ?: Long.MAX_VALUE)
            .coerceAtMost(MAX_PLAYBACK_NETWORK_RETRY_DELAY_MS / 1_000L + 1L) * 1_000L
    }
    fun date(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
    val retryAt = date(raw) ?: return null
    val reference = header("Date")?.let(::date) ?: nowMs
    return (retryAt - reference).coerceAtLeast(0L)
}

object PlaybackRangeProbe {
    internal fun httpClient(playbackClient: OkHttpClient): OkHttpClient =
        playbackClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    suspend fun probe(url: String, headers: Map<String, String>, playbackClient: OkHttpClient): JSONObject = suspendCancellableCoroutine { continuation ->
        val started = System.nanoTime() / 1_000_000L
        val request = runCatching {
            Request.Builder().url(url).header("Range", "bytes=0-1023").header("User-Agent", "LuminaTV/1.5 (Android)").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
        }.getOrElse {
            continuation.resume(JSONObject().put("status", JSONObject.NULL).put("error", it.javaClass.simpleName))
            return@suspendCancellableCoroutine
        }
        val call = httpClient(playbackClient).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(JSONObject()
                    .put("status", JSONObject.NULL)
                    .put("rangeSupported", JSONObject.NULL)
                    .put("size", JSONObject.NULL)
                    .put("redirects", 0)
                    .put("elapsedMs", System.nanoTime() / 1_000_000L - started)
                    .put("error", e.javaClass.simpleName))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val redirects = generateSequence(it.priorResponse) { previous -> previous.priorResponse }.count()
                    continuation.resume(JSONObject()
                        .put("status", it.code)
                        .put("rangeSupported", it.code == 206 || it.header("Accept-Ranges")?.contains("bytes", true) == true)
                        .put("contentType", it.header("Content-Type") ?: JSONObject.NULL)
                        .put("finalUrlHost", it.request.url.host)
                        .put("size", it.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: it.header("Content-Length")?.toLongOrNull() ?: JSONObject.NULL)
                        .put("redirects", redirects)
                        .put("elapsedMs", System.nanoTime() / 1_000_000L - started))
                }
            }
        })
    }
}
