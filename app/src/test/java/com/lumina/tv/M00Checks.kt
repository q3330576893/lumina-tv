package com.lumina.tv

import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.LogcatCapture
import com.lumina.tv.player.PlaybackFailureInput
import com.lumina.tv.player.PlaybackHttpTrace
import com.lumina.tv.player.diagnosticsGap
import com.lumina.tv.player.nativeVersionDiagnosticProperties
import com.lumina.tv.player.playbackFailureJson
import fi.iki.elonen.NanoHTTPD
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.concurrent.thread

object M00Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("capture-child"))) {
            println("logcat-fixture-child")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        archivePermissionsAndRedaction()
        refusedConnectionEvidence()
        unknownHostEvidence()
        socketTimeoutEvidence()
        missingPhaseCallbacksEvidence()
        http404Evidence()
        missingFieldEvidence()
        logcatDeadline()
        nativeVersionStates()
        println("M00 PASS: local HTTP evidence, permission fallback and ZIP redaction, gap validation, single-flight deadline, and native version states")
    }

    private fun archivePermissionsAndRedaction() = temporary("m00-archive") { directory ->
        val archive = File(directory, "permission-success.zip")
        val modes = mutableListOf<Pair<String, Int>>()
        DiagExporter.writeArchive(archive, mapOf(
            "meta.json" to JSONObject().put("api_key", "archive-api-secret").put("password", "archive-password-secret").toString(),
            "app.log" to "X-Emby-Token: token-fixture-secret",
        )) { file, mode -> modes += file.name to mode }
        check(modes.any { it.first == directory.name && it.second == 0x1F8 })
        check(modes.any { it.first == archive.name && it.second == 0x1B0 })
        ZipFile(archive).use { zip ->
            val meta = JSONObject(zip.getInputStream(zip.getEntry("meta.json")).bufferedReader().use { it.readText() })
            check(meta.getString("archivePermissions") == "0660")
            assertRedacted(meta.getString("api_key"), "api_key", "archive-api-secret")
            assertRedacted(meta.getString("password"), "password", "archive-password-secret")
            check(JSONObject(zip.getInputStream(zip.getEntry("archive-permissions.json")).bufferedReader().use { it.readText() })
                .getString("permissions") == "0660")
            val all = zip.entries().asSequence().map { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.joinToString("\n")
            check("token-fixture-secret" !in all && "archive-password-secret" !in all && "archive-api-secret" !in all)
        }

        val failed = File(directory, "permission-failure.zip")
        DiagExporter.writeArchive(failed, mapOf("meta.json" to "{}")) { _, _ -> throw IOException("simulated chmod failure") }
        check(failed.isFile && ZipFile(failed).use { zip ->
            val meta = JSONObject(zip.getInputStream(zip.getEntry("meta.json")).bufferedReader().use { it.readText() })
            val permissions = JSONObject(zip.getInputStream(zip.getEntry("archive-permissions.json")).bufferedReader().use { it.readText() })
            meta.getString("archivePermissions") == "unknown" && permissions.getString("permissions") == "unknown"
        })
        println("M00-1 JVM PASS: ZIP/meta readable, token/api_key/password redacted, requested modes=0x1f8/0x1b0; simulated chmod failure retained ZIP with permissions=unknown")
    }

    private fun refusedConnectionEvidence() {
        val identity = identity()
        val port = ServerSocket(0).use { it.localPort }
        val trace = PlaybackHttpTrace { identity }
        val token = "token-refused-fixture-secret"
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/video.mp4?X-Emby-Token=$token&api_key=api-fixture-secret")
            .header("X-Emby-Token", token)
            .build()
        val client = OkHttpClient.Builder().eventListenerFactory(trace).build()
        val failure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
            ?: error("Expected a refused local connection")
        val record = record(failure, trace.snapshot(identity))
        val http = record.getJSONObject("http")
        val attempt = assertFailedAttempt(http, failure, "connect")
        check(record.getJSONObject("exception").getString("class") != "A0")
        check(attempt.getJSONObject("timings").getLong("connect") >= 0L)
        check(attempt.getJSONObject("evidence").getString("start") == "EventListener.callStart")
        check(http.getString("url").contains(":$port/video.mp4"))
        check(token !in record.toString() && "api-fixture-secret" !in record.toString())
        val gap = diagnosticsGap(record)
        check(!gap.getJSONArray("missing").toString().contains("http.status"))
        println("M00-2 REFUSED PASS: exception.class=${record.getJSONObject("exception").getString("class")} status=null attempts=${http.getInt("attemptCount")} phase=${attempt.getString("phase")} result=${attempt.getString("result")} totalMs=${attempt.getJSONObject("timings").getLong("total")}")
        temporary("m00-refused") { directory ->
            val archive = File(directory, "failure.zip")
            DiagExporter.writeArchive(archive, mapOf("meta.json" to "{}", "playback-failure.json" to record.toString())) { _, _ -> }
            ZipFile(archive).use { zip ->
                val packaged = zip.getInputStream(zip.getEntry("playback-failure.json")).bufferedReader().use { it.readText() }
                check(token !in packaged && "api-fixture-secret" !in packaged)
                assertFailedAttempt(JSONObject(packaged).getJSONObject("http"), failure, "connect")
                println("M00-2 SAMPLE playback-failure.json=$packaged")
            }
        }
    }

    private fun unknownHostEvidence() {
        val identity = identity()
        val trace = PlaybackHttpTrace { identity }
        val token = "dns-fixture-secret"
        val client = OkHttpClient.Builder().eventListenerFactory(trace).proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("$hostname token=$token")
            }).build()
        val failure = runCatching {
            client.newCall(Request.Builder().url("http://m00-dns.invalid/video.mp4?token=$token").build()).execute().close()
        }.exceptionOrNull() ?: error("Expected the fixture DNS lookup to fail")
        check(failure is UnknownHostException)
        val http = trace.snapshot(identity)
        val attempt = assertFailedAttempt(http, failure, "dns")
        check(attempt.getJSONObject("timings").getLong("dns") >= 0L)
        check(token !in record(failure, http).toString())
        println("M00-2 DNS PASS: real OkHttp call, fixture DNS throws UnknownHostException; attempts=1 phase=dns result=failed errorMessage=${attempt.getString("errorMessage")}")
    }

    private fun socketTimeoutEvidence() {
        val identity = identity()
        val trace = PlaybackHttpTrace { identity }
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "m00-timeout-server", isDaemon = true) {
                server.accept().use {
                    accepted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val client = OkHttpClient.Builder().eventListenerFactory(trace).proxy(Proxy.NO_PROXY)
                    .retryOnConnectionFailure(false).readTimeout(200, TimeUnit.MILLISECONDS)
                    .callTimeout(3, TimeUnit.SECONDS).build()
                val failure = runCatching {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/timeout").build()).execute().close()
                }.exceptionOrNull() ?: error("Expected a socket read timeout")
                check(accepted.count == 0L && failure is SocketTimeoutException)
                val attempt = assertFailedAttempt(trace.snapshot(identity), failure, "request")
                println("M00-2 TIMEOUT PASS: real loopback SocketTimeoutException; attempts=1 phase=request result=failed totalMs=${attempt.getJSONObject("timings").getLong("total")}")
            } finally {
                release.countDown()
                server.close()
                worker.join(1000L)
                check(!worker.isAlive)
            }
        }
    }

    private fun missingPhaseCallbacksEvidence() {
        val token = "callback-fixture-secret"
        val client = OkHttpClient()
        for ((failure, phase) in listOf(
            ConnectException("connect token=$token") to "connect",
            UnknownHostException("dns token=$token") to "dns",
            SocketTimeoutException("timeout token=$token") to "request",
        )) {
            val identity = identity()
            val trace = PlaybackHttpTrace { identity }
            val call = client.newCall(Request.Builder().url("http://m00-callback.invalid/video?token=$token")
                .header("X-Emby-Token", token).build())
            val listener = trace.create(call)
            listener.callStart(call)
            listener.callFailed(call, failure)
            val http = trace.snapshot(identity)
            val attempt = assertFailedAttempt(http, failure, phase)
            val evidence = attempt.getJSONObject("evidence")
            check(evidence.getString("request") == "Call.request")
            check(evidence.getString("phase") == "EventListener.callFailed.exception")
            check(token !in record(failure, http).toString())
        }
        println("M00-2 CALLBACK FALLBACK PASS: simulated absence of phase callbacks; callStart/callFailed + Request snapshot retained ConnectException/UnknownHostException/SocketTimeoutException, with redacted errors")
    }

    private fun assertFailedAttempt(http: JSONObject, failure: Throwable, phase: String): JSONObject {
        val attempts = http.getJSONArray("attempts")
        check(http.getInt("attemptCount") == 1 && attempts.length() == 1)
        check(http.isNull("status") && http.getString("statusState") == "no_response_received")
        val attempt = attempts.getJSONObject(0)
        check(attempt.getString("phase") == phase)
        check(attempt.getString("result") == "failed")
        check(attempt.getString("errorClass") == failure.javaClass.name)
        check(attempt.getString("errorMessage").isNotBlank())
        check(attempt.getJSONObject("timings").getLong("total") >= 0L)
        check(attempt.getJSONObject("evidence").getString("completion") == "EventListener.callFailed")
        return attempt
    }

    private fun assertRedacted(value: String, name: String, raw: String) {
        check(raw !in value) { "Original credential must be absent from $name" }
        val sha8 = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
        check(value == "***" || value == "$name(len=${raw.toByteArray(Charsets.UTF_8).size}, sha8=$sha8)")
    }

    private fun http404Evidence() {
        val identity = identity()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/redirect" -> newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "redirect")
                    .apply { addHeader("Location", "/missing?token=redirect-fixture-secret") }
                "/missing" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                else -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val trace = PlaybackHttpTrace { identity }
            val token = "token-http-fixture-secret"
            val url = "http://127.0.0.1:${server.listeningPort}/redirect?api_key=api-fixture-secret"
            val client = OkHttpClient.Builder().eventListenerFactory(trace).build()
            val response = client.newCall(Request.Builder().url(url).header("X-Emby-Token", token).build())
                .execute().use { it.code }
            val record = record(IOException("HTTP $response"), trace.snapshot(identity))
            val http = record.getJSONObject("http")
            val redirects = http.getJSONArray("redirectChain")
            check(response == 404 && http.getInt("status") == 404)
            check(redirects.length() == 2 && redirects.getJSONObject(1).getInt("status") == 404)
            check(http.getString("responseContentType").startsWith("text/plain"))
            val requestHeaders = http.getJSONArray("requestHeaders")
            val descriptor = (0 until requestHeaders.length()).map { requestHeaders.getString(it) }
                .first { it.startsWith("X-Emby-Token(len=") }
            val sha8 = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(4)
                .joinToString("") { "%02x".format(it) }
            check(descriptor == "X-Emby-Token(len=${token.toByteArray().size}, sha8=$sha8)")

            val archive = File.createTempFile("m00-redaction-", ".zip")
            try {
                DiagExporter.writeArchive(archive, mapOf("meta.json" to "{}", "playback-failure.json" to record.toString())) { _, _ -> }
                ZipFile(archive).use { zip ->
                    val packaged = zip.getInputStream(zip.getEntry("playback-failure.json")).bufferedReader().use { it.readText() }
                    check(token !in packaged && "api-fixture-secret" !in packaged && "redirect-fixture-secret" !in packaged)
                    check(descriptor in packaged)
                }
            } finally { archive.delete() }
            println("M00-2 HTTP404 PASS: status=404 redirectChain=${redirects.length()} responseContentType=${http.getString("responseContentType")}")
            println("M00-2 HEADER PASS: $descriptor; raw token absent from playback-failure.json ZIP entry")
        } finally { server.stop() }
    }

    private fun missingFieldEvidence() {
        val gap = diagnosticsGap(JSONObject()
            .put("exception", JSONObject().put("message", "fixture"))
            .put("http", JSONObject()))
        val missing = gap.getJSONArray("missing").let { array -> (0 until array.length()).map(array::getString) }
        check("exception.class" in missing && "http.status" in missing)
        println("M00-2 GAP PASS: ${gap.toString()}")
    }

    private fun logcatDeadline() {
        val entered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int {
                entered.countDown()
                while (allowClose.count > 0) try { allowClose.await() } catch (_: InterruptedException) { }
                exited.countDown()
                throw IOException("closed fixture")
            }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = read()
            override fun close() {
                closeEntered.countDown()
                while (allowClose.count > 0) try { allowClose.await() } catch (_: InterruptedException) { }
                super.close()
            }
        }
        val result = AtomicReference<com.lumina.tv.diag.DiagnosticReadResult>()
        val started = System.nanoTime()
        val caller = thread { result.set(LogcatCapture.read(128, 120L) { input }) }
        check(entered.await(1, TimeUnit.SECONDS))
        caller.join(1000L)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        check(!caller.isAlive && result.get().status == "timed_out" && elapsedMs < 500L)
        check(closeEntered.await(1, TimeUnit.SECONDS))
        check(LogcatCapture.read(8, 50L) { error("A second logcat source must be dropped, not queued") }.status == "reader_busy")
        allowClose.countDown()
        check(exited.await(1, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (LogcatCapture.captureActive && System.nanoTime() < deadline) Thread.yield()
        check(!LogcatCapture.captureActive)

        val childRef = AtomicReference<Process>()
        val childResult = AtomicReference<com.lumina.tv.diag.DiagnosticReadResult>()
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable").path
        val childCaller = thread {
            childResult.set(LogcatCapture.read(4096, 250L) { resources ->
                val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), M00Checks::class.java.name, "capture-child")
                    .redirectErrorStream(true).start()
                childRef.set(process)
                resources.process(process).inputStream
            })
        }
        childCaller.join(1200L)
        check(!childCaller.isAlive && childResult.get().status == "timed_out")
        val child = childRef.get() ?: error("Logcat fixture child did not start")
        check(child.waitFor(2, TimeUnit.SECONDS) && !child.isAlive)
        println("M00-6 PASS: blocked read returned timed_out in ${elapsedMs}ms, close ran, reader exited, second sample was dropped; real child process was destroyed at the capture deadline")
    }

    private fun nativeVersionStates() {
        val notInitialized = JSONObject(nativeVersionDiagnosticProperties(false) { error("must not read before initialization") })
        check(notInitialized.getJSONObject("mpv-version").getString("state") == "not_initialized")
        val available = JSONObject(nativeVersionDiagnosticProperties(true) { key ->
            if (key == "mpv-version") "v0.41.0" else "n8.1"
        })
        check(available.getJSONObject("mpv-version").getString("value") == "v0.41.0")
        check(available.getJSONObject("ffmpeg-version").getString("value") == "n8.1")
        val failed = JSONObject(nativeVersionDiagnosticProperties(true) { throw IOException("probe failed") })
        check(failed.getJSONObject("mpv-version").getString("state") == "read_failed")
        println("M00-4 PASS: readProperty(libmpv.mpv-version, libmpv.ffmpeg-version) -> v0.41.0/n8.1; separate not_initialized/read_failed states verified")
    }

    private fun record(error: Throwable, http: JSONObject): JSONObject = playbackFailureJson(PlaybackFailureInput(
        failure = error,
        playbackException = null,
        http = http,
        sessionId = "m00-session",
        generation = 7,
        loadIdentity = identity(),
        engine = "media3",
        decoderName = null,
        decoderMime = null,
        hardwareAccelerated = null,
        initResult = "not_observed",
        fallbackHistory = JSONArray(),
        audioProbe = JSONObject().put("api", "fixture").put("input", JSONObject()).put("rawResult", JSONObject()).put("state", "ok"),
        audioSelection = JSONObject().put("requestedMode", "auto").put("engine", "media3"),
        phaseTimeline = JSONObject().put("loadStartedMs", 0).put("failureElapsedMs", 14)
            .put("mediaPeriodId", JSONObject.NULL),
        nativeVersions = JSONObject().put("mpv-version", JSONObject().put("state", "not_initialized"))
            .put("ffmpeg-version", JSONObject().put("state", "not_initialized")),
        abi = JSONArray().put("x86_64"),
        hdr = JSONArray(),
        engineSelectionReason = "fixture",
    ))

    private fun identity() = JSONObject().put("sessionId", "m00-session")
        .put("loadIdentity", JSONObject().put("generation", 7).put("physicalId", 11).put("software", false))

    private fun temporary(prefix: String, block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory(prefix).toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
