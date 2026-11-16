package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import android.os.Bundle
import javax.net.ssl.SSLHandshakeException

/** Call from a local test harness to run playback error classification assertions. */
fun runPlaybackErrorClassificationAssertions() {
    fun classify(code: Int, cause: Throwable? = null) = classifyPlaybackError(
        // A fixed timestamp keeps this fixture independent of Android's native SystemClock.
        object : PlaybackException("sample", cause, code, Bundle.EMPTY, 0L) {}
    )

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

    // HTTP 状态码分支依赖 InvalidResponseCodeException 作为 cause，纯 Kotlin 构造不便，留真机验证。
    assertCategory(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackErrorCategory.OTHER)
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
}

fun main() = runPlaybackErrorClassificationAssertions()
