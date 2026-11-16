package com.lumina.tv.player

import com.lumina.tv.data.PreviewOriginInterceptor
import com.lumina.tv.data.isSameOrigin
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Local HTTP requests and deterministic recovery-budget checks; no device/server credentials. */
object A1ReliabilityChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        check(isSameOrigin("https://emby.example/base", "https://EMBY.example:443/video"))
        check(!isSameOrigin("https://emby.example", "http://emby.example/video"))
        check(!isSameOrigin("https://emby.example", "https://emby.example:8443/video"))
        check(!isSameOrigin("https://emby.example", "https://cdn.example/video"))
        check(!isSameOrigin("https://emby.example", null))
        val first = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val other = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${first.localPort}"
        val external = "http://127.0.0.1:${other.localPort}"
        data class Captured(val path: String, val credential: String?, val sourceHeader: String?)
        val seen = LinkedBlockingQueue<Captured>()
        listOf(first, other).forEach { server ->
            thread(isDaemon = true, name = "a1-local-http") {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        seen.add(Captured(path, headers["x-emby-authorization"], headers["x-source-required"]))
                        val redirect = when (path) {
                            "/same" -> "$base/ok"
                            "/cross" -> "$external/ok"
                            "/back" -> "$base/ok"
                            else -> null
                        }
                        val response = if (redirect != null)
                            "HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    }
                } catch (e: SocketException) {
                    if (!server.isClosed) throw e
                }
            }
        }
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(PreviewOriginInterceptor({ base }, { "test-emby-credential" })).build()
        try {
            fun request(url: String, expected: List<Boolean>) {
                val req = Request.Builder().url(url)
                    .header("X-Emby-Authorization", "test-emby-credential")
                    .header("X-Source-Required", "keep-source-header").build()
                client.newCall(req).execute().use { check(it.isSuccessful) }
                val actual = buildList { while (seen.isNotEmpty()) add(seen.remove()) }
                check(actual.map { it.credential != null } == expected) { actual.toString() }
                check(actual.all { it.sourceHeader == "keep-source-header" })
            }
            request("$base/ok", listOf(true))
            request("$external/ok", listOf(false))
            request("$base/same", listOf(true, true))
            request("$base/cross", listOf(true, false))
            request("$external/back", listOf(false, true))
            println("A01 PASS: origin scheme/host/port; same/cross/redirect/back headers; RequiredHttpHeaders retained")
        } finally {
            first.close()
            other.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        val budget = PlaybackRecoveryBudget()
        check(budget.tryAcquire(0)) // Audio fallback.
        check(!budget.tryAcquire(59_999)) // Watchdog cannot bypass the same cooldown.
        check(budget.cooldownRemainingMs(59_999) == 1L)
        check(budget.tryAcquire(60_000)) // Watchdog consumes the second shared slot.
        check(!budget.tryAcquire(60_001))
        check(budget.tryAcquire(120_000))
        check(budget.exhausted && budget.attempts == 3)
        check(!budget.tryAcquire(1_000_000))
        println("A02 PASS: shared audio/watchdog budget, 60s cooldown, at most 3 attempts")
    }
}
