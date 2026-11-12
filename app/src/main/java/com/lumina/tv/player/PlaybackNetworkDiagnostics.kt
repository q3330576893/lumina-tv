package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class PlaybackErrorCategory { NETWORK_TRANSIENT, HTTP_PERMISSION, PARSING, DECODING, OTHER }

data class PlaybackErrorClassification(val category: PlaybackErrorCategory, val code: Int)

fun classifyPlaybackError(error: PlaybackException): PlaybackErrorClassification {
    val category = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackErrorCategory.NETWORK_TRANSIENT
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> if (generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode }
            .any { it == 401 || it == 403 }) PlaybackErrorCategory.HTTP_PERMISSION else PlaybackErrorCategory.OTHER
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackErrorCategory.HTTP_PERMISSION
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackErrorCategory.PARSING
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackErrorCategory.DECODING
        else -> PlaybackErrorCategory.OTHER
    }
    return PlaybackErrorClassification(category, error.errorCode)
}

object PlaybackRangeProbe {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun probe(url: String, headers: Map<String, String>): JSONObject = suspendCancellableCoroutine { continuation ->
        val started = android.os.SystemClock.elapsedRealtime()
        val request = runCatching {
            Request.Builder().url(url).header("Range", "bytes=0-1023").header("User-Agent", "LuminaTV/1.5 (Android)").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
        }.getOrElse {
            continuation.resume(JSONObject().put("status", JSONObject.NULL).put("error", it.javaClass.simpleName))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(JSONObject()
                    .put("status", JSONObject.NULL)
                    .put("rangeSupported", JSONObject.NULL)
                    .put("size", JSONObject.NULL)
                    .put("redirects", 0)
                    .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
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
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started))
                }
            }
        })
    }
}
