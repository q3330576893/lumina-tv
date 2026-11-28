package com.lumina.tv

import com.lumina.tv.data.*
import com.lumina.tv.player.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Batch 1: exercises production transports against loopback HTTP/TLS, without credentials/devices. */
object B1Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        java.util.logging.Logger.getLogger("fi.iki.elonen.NanoHTTPD").level = java.util.logging.Level.OFF // Expected disconnects in timeout fixtures.
        networkBoundaries()
        tlsBoundaries()
        playbackRetryAndTlsClassification()
        B1SubtitleChecks.run()
        B1ResumeChecks.run()
        B1InputChecks.run()
        println("B1 PASS: Q-001 Q-002 Q-003 Q-004 Q-005 Q-022 production policies and offline regressions")
    }

    private suspend fun networkBoundaries() {
        data class Captured(val path: String, val auth: String?, val required: String?)
        val seen = CopyOnWriteArrayList<Captured>()
        var base = ""
        var external = ""
        var onFirstHop: () -> Unit = {}
        fun server() = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                seen += Captured(session.uri, session.headers["x-emby-authorization"], session.headers["x-source-required"])
                val redirect = when (session.uri) {
                    "/same" -> "$base/ok"
                    "/cross", "/Users/user/Items/Latest" -> "$external/ok"
                    "/back" -> "$base/ok"
                    "/switch" -> { onFirstHop(); "$external/back" }
                    else -> null
                }
                return if (redirect != null) newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT, MIME_PLAINTEXT, "")
                    .apply { addHeader("Location", redirect) }
                else newFixedLengthResponse("[]")
            }
        }
        val a = server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val b = server().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        base = "http://127.0.0.1:${a.listeningPort}"
        external = "http://127.0.0.1:${b.listeningPort}"
        val account = EmbyClient(base, token = "b1-account-a", userId = "user")
        val otherAccount = EmbyClient(base, token = "b1-account-b", userId = "user")
        fun assertHops(expected: List<Boolean>, token: String = "b1-account-a", required: Boolean = true) {
            check(seen.map { it.auth != null } == expected) { seen.toString() }
            check(seen.filter { it.auth != null }.all { it.auth!!.contains("Token=\"$token\"") }) { seen.toString() }
            if (required) check(seen.all { it.required == "keep-business-header" })
        }
        val transports = listOf(account.playbackHttpClient, PlaybackRangeProbe.httpClient(account.playbackHttpClient), account.previewHttpClient)
        try {
            // This is the real metadata endpoint (not only a standalone interceptor).
            check(account.getLatest(null).isEmpty())
            assertHops(listOf(true, false), required = false)
            for (client in transports) {
                fun get(url: String, expected: List<Boolean>, token: String = "b1-account-a") {
                    seen.clear()
                    val request = Request.Builder().url(url)
                        .header("x-eMbY-aUtHoRiZaTiOn", "untrusted-source-value")
                        .header("X-Source-Required", "keep-business-header")
                        .header("Referer", "https://business.invalid/").build()
                    client.newCall(request).execute().use { check(it.isSuccessful) }
                    assertHops(expected, token)
                }
                get("$base/same", listOf(true, true))
                get("$base/cross", listOf(true, false))
                get("$external/back", listOf(false, true))
                get("http://localhost:${a.listeningPort}/ok", listOf(false)) // Different host, same socket.
                // Independent HLS child requests get the policy too; no inherited parent token.
                get("$base/master.m3u8", listOf(true))
                get("$external/segment.ts", listOf(false))
            }
            seen.clear()
            val probe = PlaybackRangeProbe.probe("$base/cross", mapOf("X-Source-Required" to "keep-business-header"), account.playbackHttpClient)
            check(probe.getInt("status") == 200 && probe.getInt("redirects") == 1)
            assertHops(listOf(true, false))
            seen.clear()
            onFirstHop = { account.token = "b1-next-token" }
            account.playbackHttpClient.newCall(Request.Builder().url("$base/switch").build()).execute().close()
            assertHops(listOf(true, false, true), required = false) // A call never changes accounts mid-redirect.
            for ((client, token) in listOf(account to "b1-next-token", otherAccount to "b1-account-b")) {
                seen.clear()
                client.playbackHttpClient.newCall(Request.Builder().url("$base/ok").build()).execute().close()
                assertHops(listOf(true), token, required = false)
            }
            check(!isSameOrigin("https://emby.invalid", "http://emby.invalid"))
            check(nativePlaybackHeaders(mapOf("x-emby-authorization" to "secret", "Referer" to "business")) == mapOf("Referer" to "business"))
            val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
            check(!player.contains("DefaultHttpDataSource.Factory"))
            // The actual source transport derives from the authenticated playback client,
            // adding diagnostics without replacing its interceptors/account policy.
            check(player.contains("val observedClient = c.playbackHttpClient.newBuilder().eventListenerFactory(playbackHttpTrace).build()"))
            check(player.contains("val upstreamFactory = OkHttpDataSource.Factory(observedClient)"))
            check(player.contains("targetCacheFactory ?: upstreamFactory"))
            check(player.contains("val sourceWiring = Media3SourceWiring(optionalFactory)"))
            println("B1 Q-001 PASS: real metadata/playback/probe redirects, HLS child policy, host/port/scheme, business headers and account snapshots")
        } finally {
            account.close(); otherAccount.close(); a.stop(); b.stop()
            (transports + otherAccount.playbackHttpClient).forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        }
    }

    private fun playbackRetryAndTlsClassification() {
        fun error(status: Int, headers: Map<String, List<String>> = emptyMap()) = playbackErrorSample(
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            java.io.IOException("wrapped", androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                status, "sample", null, headers, androidx.media3.datasource.DataSpec::class.java.cast(null), ByteArray(0))))
        for (status in listOf(408, 429, 500, 503)) {
            val failure = error(status)
            val classification = classifyPlaybackError(failure)
            check(classification.category == PlaybackErrorCategory.NETWORK_TRANSIENT && classification.action == "retry")
            check(playbackNetworkRetryDelayMs(failure, 0, 0L) == 1_000L)
            check(playbackNetworkRetryDelayMs(failure, 1, 0L) == 3_000L)
            check(!shouldRetryNetworkPlayback(classification.category, 2))
            check(playbackNetworkRetryDelayMs(failure, 2, 0L) == null)
        }
        check(playbackNetworkRetryDelayMs(error(429, mapOf("retry-after" to listOf("12"))), 0, 0L) == 12_000L)
        check(playbackNetworkRetryDelayMs(error(408, mapOf("Retry-After" to listOf("60"))), 1, 0L) == 60_000L)
        for (value in listOf("61", "9999999999999999999999999")) {
            check(playbackNetworkRetryDelayMs(error(429, mapOf("Retry-After" to listOf(value))), 0, 0L) == null)
        }
        val dates = mapOf("Retry-After" to listOf("Wed, 25 Nov 2026 00:00:15 GMT"),
            "Date" to listOf("Wed, 25 Nov 2026 00:00:00 GMT"))
        check(playbackNetworkRetryDelayMs(error(429, dates), 0, 123L) == 15_000L) // Use server clock.
        check(playbackNetworkRetryDelayMs(error(429, mapOf("Retry-After" to listOf("invalid"))), 1, 0L) == 3_000L)
        val handshake = SSLHandshakeException("protocol negotiation failed")
        check(!isCertificateFailure(handshake))
        check(!isCertificateFailure(java.io.IOException("wrapped", handshake)))
        check(isCertificateFailure(SSLHandshakeException("verify failed").apply {
            initCause(java.security.cert.CertificateException("expired"))
        }))
        check(isCertificateFailure(SSLPeerUnverifiedException("hostname mismatch")))
        check(isCertificateFailure(java.security.cert.CertPathValidatorException("untrusted issuer")))
        check(classifyPlaybackError(playbackErrorSample(
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, handshake))
            .category == PlaybackErrorCategory.NETWORK_TRANSIENT)

        fun assertTlsClassification(cause: Throwable, certificate: Boolean) {
            val expected = if (certificate) PlaybackErrorCategory.CERT_INVALID else PlaybackErrorCategory.NETWORK_TRANSIENT
            val action = if (certificate) "check_certificate" else "retry"
            for (wrapped in listOf(cause, java.io.IOException("wrapped TLS", cause))) {
                check(isCertificateFailure(wrapped) == certificate) { "TLS cause: $cause" }
                for (code in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)) {
                    val actual = classifyPlaybackError(playbackErrorSample(code, wrapped))
                    check(actual.category == expected && actual.action == action && actual.code == code) { "$cause: $actual" }
                }
            }
        }
        // The samples contract retains CERT_INVALID for an otherwise unspecified handshake.
        assertTlsClassification(SSLHandshakeException("sample"), certificate = true)
        for (message in listOf("protocol negotiation failed", "Received fatal alert: protocol_version",
            "Received fatal alert: handshake_failure", "No appropriate protocol (protocol is disabled)",
            "no cipher suites in common", "Remote host terminated the handshake", "Read timed out")) {
            assertTlsClassification(SSLHandshakeException(message), certificate = false)
        }
        for (transport in listOf(SSLProtocolException("unexpected record"), SocketException("connection reset"),
            java.net.SocketTimeoutException("timeout"), java.io.EOFException("peer disconnected"))) {
            assertTlsClassification(SSLHandshakeException("sample").apply { initCause(transport) }, certificate = false)
        }
        assertTlsClassification(SSLException("TLS I/O error"), certificate = false)
        assertTlsClassification(SSLProtocolException("bad record MAC"), certificate = false)
        assertTlsClassification(SSLHandshakeException("protocol negotiation failed").apply {
            initCause(java.security.cert.CertificateException("expired"))
        }, certificate = true)
        check(!isCertificateFailure(java.io.IOException("certificate verification failed")))
    }

    private fun tlsBoundaries() {
        val password = "b1-test-only".toCharArray()
        fun store(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
            B1Checks::class.java.getResourceAsStream("/b1-tls/$name.store").use { input ->
                checkNotNull(input) { "Missing TLS fixture $name" }; load(input, password)
            }
        }
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password); setCertificateEntry("b1-test-root", store("valid").getCertificate("root"))
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(clientContext.socketFactory, trustManager).build()
        val downgradeHeaders = CopyOnWriteArrayList<Map<String, String>>()
        val plain = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                downgradeHeaders += session.headers.toMap()
                return newFixedLengthResponse("ok")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        try {
            for ((fixture, valid) in listOf("valid" to true, "expired" to false, "wrong-host" to false, "self-signed" to false)) {
                val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store(fixture), password) }
                val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
                val server = context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
                val worker = thread(isDaemon = true, name = "b1-tls-$fixture") {
                    while (!server.isClosed) {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 2_000
                                val reader = socket.getInputStream().bufferedReader()
                                val path = reader.readLine()?.split(' ')?.getOrNull(1)
                                val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }
                                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                                if (path == "/downgrade") {
                                    downgradeHeaders += headers
                                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://localhost:${plain.listeningPort}/end\r\n" +
                                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                                } else socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            }
                        } catch (_: java.io.IOException) { /* Failed handshakes are expected in the negative fixtures. */ }
                    }
                }
                try {
                    val result = runCatching { client.newCall(Request.Builder().url("https://localhost:${server.localPort}/").build()).execute().use { check(it.isSuccessful) } }
                    check(result.isSuccess == valid) { "$fixture: $result" }
                    if (!valid) check(isCertificateFailure(result.exceptionOrNull()!!)) { "$fixture was not CERT_INVALID" }
                    if (valid) {
                        val origin = "https://localhost:${server.localPort}"
                        val secure = client.newBuilder().originAuthentication { OriginCredentials(origin, "b1-tls-token") }.build()
                        secure.newCall(Request.Builder().url("$origin/downgrade").header("X-Source-Required", "keep").build()).execute().use { check(it.isSuccessful) }
                        check(downgradeHeaders.size == 2)
                        check(downgradeHeaders[0]["x-emby-authorization"] == "b1-tls-token")
                        check(downgradeHeaders[1]["x-emby-authorization"] == null)
                        check(downgradeHeaders.all { it["x-source-required"] == "keep" })
                    }
                } finally { server.close(); worker.join(3_000); check(!worker.isAlive) }
            }
            for (message in listOf("certificate verify failed", "Peer certificate cannot be authenticated", "SSL peer certificate or SSH remote key was not OK", "TLS certificate verification failed", "hostname does not match", "The certificate is NOT trusted.", "Peer certificate failed verification", "Error in the certificate verification.", "Failed to verify server certificate")) {
                check(isMpvCertificateFailure(message)) { message }
            }
            check(!isMpvCertificateFailure("HTTP 403 server returned error"))
            check(!isMpvCertificateFailure("TLS certificate verification succeeded"))
            check(!isMpvCertificateFailure("The certificate is trusted."))
            check(!isMpvCertificateFailure("Failed to open https://example.invalid/certificate.mp4"))
            ServerCertificateErrors.record("https://EMBY.invalid:443/Base/")
            check(ServerCertificateErrors.get("https://emby.invalid/Base") == "CERT_INVALID")
            check(ServerCertificateErrors.get("https://emby.invalid/Other") == null)
            check(ServerCertificateErrors.get("https://other.invalid/Base") == null)
            check(MpvTlsPolicy.options(File("fixture.pem"))["tls-verify"] == "yes")
            println("B1 Q-002 PASS: trusted CA, expired, hostname mismatch, unauthorized self-signed, CERT_INVALID and server identity isolation (JVM TLS; native handshake requires device)")
        } finally { plain.stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
