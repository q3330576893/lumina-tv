package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.ORIGIN_AUTH_RESPONSE_HEADER
import com.lumina.tv.data.closeT07Client
import fi.iki.elonen.NanoHTTPD
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/** Origin evidence comes from real per-hop production interceptors, not a guessed initial URL. */
internal object T07PlaybackChecks {
    suspend fun run() {
        originsAndRedirects()
        retryAndTls()
        val source = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("classifyPlaybackError(err, client?.baseUrl.orEmpty())" in source)
        val permission = source.substringAfter("} else if (errorRoute == PlaybackErrorRoute.PERMISSION_DENIED) {")
            .substringBefore("} else if (")
        check("refresh_link_or_change_source" in permission && "链接可能已过期" in permission)
        check(listOf("logout(", "removeSession(", "token =", "reloadForceTranscode = true", "requestAutomaticReload(")
            .none { it in permission }) { "External denial entered session/transcode recovery" }
        println("T07 playback PASS: final-hop Emby 401/403 versus CDN 401/403, spoofed markers, redirect/back, preserved session, 408/429/TLS and bounded retries")
    }

    private fun failure(status: Int, headers: Map<String, List<String>>, wrapped: Boolean = false): PlaybackException {
        val response = HttpDataSource.InvalidResponseCodeException(status, "fixture", null, headers,
            DataSpec::class.java.cast(null), ByteArray(0))
        return playbackErrorSample(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            if (wrapped) IOException("nested", response) else response)
    }

    private suspend fun originsAndRedirects() {
        val cdnCredentials = CopyOnWriteArrayList<Boolean>()
        val embyCredentials = CopyOnWriteArrayList<Boolean>()
        lateinit var embyBase: String
        val cdn = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                cdnCredentials += (session.headers["x-emby-authorization"] != null)
                if (session.uri == "/back") return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                    .apply { addHeader("Location", "$embyBase/status/401") }
                val status = session.uri.substringAfterLast('/').toInt()
                return newFixedLengthResponse(Response.Status.lookup(status), "text/plain", "CDN denied")
                    .apply { addHeader(ORIGIN_AUTH_RESPONSE_HEADER, "emby") } // Must be overwritten.
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val emby = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                embyCredentials += (session.headers["x-emby-authorization"]?.contains("session-token") == true)
                val leaf = session.uri.substringAfterLast('/')
                if ("/redirect/" in session.uri) return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                    .apply { addHeader("Location", "http://127.0.0.1:${cdn.listeningPort}/status/$leaf") }
                if (leaf == "redirect-back") return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                    .apply { addHeader("Location", "http://127.0.0.1:${cdn.listeningPort}/back") }
                if (session.uri.endsWith("/Items/Latest")) return newFixedLengthResponse("[]")
                val status = leaf.toInt()
                return newFixedLengthResponse(Response.Status.lookup(status), "text/plain", "Emby denied")
                    .apply {
                        addHeader(ORIGIN_AUTH_RESPONSE_HEADER, "external") // Must be overwritten too.
                        if (status == 429) addHeader("Retry-After", "12")
                    }
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        embyBase = "http://127.0.0.1:${emby.listeningPort}/Emby"
        val client = EmbyClient(embyBase, token = "session-token", userId = "user")
        try {
            for (status in listOf(401, 403)) {
                for ((url, origin) in listOf(
                    "$embyBase/status/$status" to "emby",
                    "http://127.0.0.1:${cdn.listeningPort}/status/$status" to "external",
                    "$embyBase/redirect/$status" to "external",
                )) {
                    client.playbackHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.code == status && response.header(ORIGIN_AUTH_RESPONSE_HEADER) == origin)
                        for (wrapped in listOf(false, true)) {
                            val classified = classifyPlaybackError(failure(status, response.headers.toMultimap(), wrapped), embyBase)
                            check(classified.category == if (origin == "emby") PlaybackErrorCategory.AUTH_FAILED else PlaybackErrorCategory.HTTP_PERMISSION)
                            check(classified.action == if (origin == "emby") "reauthenticate" else "refresh_link_or_change_source")
                            check(playbackErrorRoute(classified.category) ==
                                if (origin == "emby") PlaybackErrorRoute.AUTHENTICATION else PlaybackErrorRoute.PERMISSION_DENIED)
                            check(!shouldRetryNetworkPlayback(classified.category, 0))
                        }
                    }
                    check(client.token == "session-token" && client.userId == "user")
                    check(client.getLatest("library").isEmpty()) // Still uses the same valid Emby session.
                }
            }
            client.playbackHttpClient.newCall(Request.Builder().url("$embyBase/redirect-back").build()).execute().use {
                check(it.header(ORIGIN_AUTH_RESPONSE_HEADER) == "emby")
                check(classifyPlaybackError(failure(it.code, it.headers.toMultimap()), embyBase).category == PlaybackErrorCategory.AUTH_FAILED)
            }
            for (status in listOf(408, 429)) {
                client.playbackHttpClient.newCall(Request.Builder().url("$embyBase/status/$status").build()).execute().use {
                    val error = failure(it.code, it.headers.toMultimap())
                    check(classifyPlaybackError(error, embyBase).category == PlaybackErrorCategory.NETWORK_TRANSIENT)
                    check(playbackNetworkRetryDelayMs(error, 0, 0) == if (status == 429) 12_000L else 1_000L)
                }
            }
            check(cdnCredentials.isNotEmpty() && cdnCredentials.none { it })
            check(embyCredentials.isNotEmpty() && embyCredentials.all { it })
            // Without final-hop evidence, an explicitly scoped caller cannot infer session expiry.
            check(classifyPlaybackError(failure(403, emptyMap()), embyBase).category == PlaybackErrorCategory.HTTP_PERMISSION)
            check(classifyPlaybackError(failure(403, emptyMap()), "").category == PlaybackErrorCategory.HTTP_PERMISSION)
        } finally { closeT07Client(client); emby.stop(); cdn.stop() }
    }

    private fun retryAndTls() {
        for (status in listOf(408, 429, 500, 503)) for (wrapped in listOf(false, true)) {
            val error = failure(status, emptyMap(), wrapped)
            val classified = classifyPlaybackError(error, "https://emby.example")
            check(classified.category == PlaybackErrorCategory.NETWORK_TRANSIENT && classified.action == "retry")
            check(playbackNetworkRetryDelayMs(error, 0, 0) == 1_000L)
            check(playbackNetworkRetryDelayMs(error, 1, 0) == 3_000L)
            check(playbackNetworkRetryDelayMs(error, 2, 0) == null)
            check(!shouldRetryNetworkPlayback(classified.category, 2))
        }
        for (value in listOf("61", "999999999999999999999999999")) {
            check(playbackNetworkRetryDelayMs(failure(429, mapOf("Retry-After" to listOf(value))), 0, 0) == null)
        }
        val tls = listOf(
            SSLProtocolException("bad record") to PlaybackErrorCategory.NETWORK_TRANSIENT,
            SSLException("I/O") to PlaybackErrorCategory.NETWORK_TRANSIENT,
            SSLHandshakeException("Received fatal alert: protocol_version") to PlaybackErrorCategory.NETWORK_TRANSIENT,
            SSLHandshakeException("Remote host terminated the handshake") to PlaybackErrorCategory.NETWORK_TRANSIENT,
            SSLPeerUnverifiedException("hostname mismatch") to PlaybackErrorCategory.CERT_INVALID,
            SSLHandshakeException("protocol negotiation failed").apply { initCause(CertificateException("expired")) } to PlaybackErrorCategory.CERT_INVALID,
        )
        for ((cause, expected) in tls) for (wrapped in listOf(cause, IOException("outer", cause))) {
            check(classifyPlaybackError(playbackErrorSample(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                wrapped), "https://emby.example").category == expected)
        }
        val path = PlaybackPath("source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        val paths = PlaybackPathFailures()
        paths.recordEngineFailure(PlaybackPathFailure(path, PlaybackFailureReason.HTTP, 1, "CDN 403"))
        check(paths.excludes(path) && !paths.excludes(path.copy(sourceId = "other")))
        check(!paths.excludes(path.copy(delivery = PlaybackDelivery.DIRECT_STREAM)))
        check(!paths.excludes(path.copy(engine = "mpv")))
    }
}
