package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import android.os.Bundle
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

// These fixtures use Media3's real exception types without calling Android SystemClock or Uri.
internal fun playbackErrorSample(code: Int, cause: Throwable? = null): PlaybackException =
    object : PlaybackException("sample", cause, code, Bundle.EMPTY, 0L) {}

internal fun httpResponseErrorSample(status: Int) =
    HttpDataSource.InvalidResponseCodeException(
        status,
        "sample",
        null,
        emptyMap(),
        DataSpec::class.java.cast(null),
        ByteArray(0),
    )

/** Call from a local test harness to run playback error classification assertions. */
fun runPlaybackErrorClassificationAssertions() {
    fun classify(code: Int, cause: Throwable? = null) = classifyPlaybackError(playbackErrorSample(code, cause))

    fun assertCategory(code: Int, expected: PlaybackErrorCategory, cause: Throwable? = null) {
        val actual = classify(code, cause).category
        check(actual == expected) { "code=$code expected=$expected actual=$actual" }
        println("PASS code=$code category=$actual")
    }

    assertCategory(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackErrorCategory.NETWORK_TRANSIENT)
    assertCategory(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackErrorCategory.NETWORK_TRANSIENT)
    check(classify(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE).let {
        it.category == PlaybackErrorCategory.HTTP_CONTENT_INVALID && it.action == "retry_or_change_source"
    })
    println("PASS invalid HTTP content type action")

    assertCategory(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackErrorCategory.OTHER)
    for ((status, expected) in mapOf(
        400 to (PlaybackErrorCategory.OTHER to "inspect_diagnostics"),
        401 to (PlaybackErrorCategory.AUTH_FAILED to "reauthenticate"),
        403 to (PlaybackErrorCategory.AUTH_FAILED to "reauthenticate"),
        404 to (PlaybackErrorCategory.SOURCE_GONE to "change_source"),
        410 to (PlaybackErrorCategory.SOURCE_GONE to "change_source"),
        500 to (PlaybackErrorCategory.NETWORK_TRANSIENT to "retry"),
    )) {
        val response = httpResponseErrorSample(status)
        for (cause in listOf(response, IOException("outer", response))) {
            val actual = classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, cause)
            check((actual.category to actual.action) == expected) { "HTTP $status: $actual" }
        }
    }
    assertCategory(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackErrorCategory.SOURCE_GONE)
    assertCategory(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, PlaybackErrorCategory.HTTP_PERMISSION)
    assertCategory(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, PlaybackErrorCategory.HTTP_PERMISSION)
    assertCategory(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackErrorCategory.DECODING)
    assertCategory(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackErrorCategory.DECODING)
    assertCategory(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackErrorCategory.DECODING)
    assertCategory(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackErrorCategory.PARSING)
    assertCategory(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackErrorCategory.PARSING)
    assertCategory(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackErrorCategory.PARSING)
    assertCategory(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackErrorCategory.PARSING)
    assertCategory(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackErrorCategory.SOURCE_GONE)
    assertCategory(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackErrorCategory.CERT_INVALID,
        SSLHandshakeException("sample"),
    )
    check(classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException(CertificateException())).let {
        it.category == PlaybackErrorCategory.CERT_INVALID && it.action == "check_certificate"
    })
    for (code in listOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)) {
        check(classify(code, IOException("wrapped audio error")).let {
            it.category == PlaybackErrorCategory.AUDIO_OUTPUT && it.action == "switch_mpv_or_reduce_pcm"
        })
    }
}

fun main() = runPlaybackErrorClassificationAssertions()
