package com.lumina.tv.player

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object B1SubtitleChecks {
    fun run() {
        check(missingSubtitleTrackNotice(CERTIFICATE_ERROR_MESSAGE) == CERTIFICATE_ERROR_MESSAGE)
        check(missingSubtitleTrackNotice(null) == "记住的字幕轨不存在，已恢复自动选择")
        commandRetirement()
        downloads()
        println("B1 Q-003 PASS: bounded HTTP/VTT downloads, slow/never EOF cancellation, serial JNI retirement, old identity callbacks and queued commands rejected")
    }

    private fun commandRetirement() {
        // Controllable commands stand for 3s, 20s and uninterruptible JNI. No test sleeps 20s.
        for (scenario in listOf("3s", "20s", "never-return-until-test-cleanup")) {
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val callbacks = LinkedBlockingQueue<() -> Unit>()
            val destroyed = AtomicBoolean()
            val cancelled = AtomicBoolean()
            val queuedRan = AtomicBoolean()
            val completed = AtomicInteger()
            val work = LoadScopedIo("old-$scenario", { it == "old-$scenario" }, { callbacks.add(it) })
            work.cancelWith { cancelled.set(true) }
            work.submit({ started.countDown(); finish.await(); check(!destroyed.get()); "done" }, { completed.incrementAndGet() })
            check(started.await(2, TimeUnit.SECONDS))
            work.submit({ queuedRan.set(true) }, { completed.incrementAndGet() })
            val before = System.nanoTime()
            val retired = work.retire { destroyed.set(true) }
            MpvRetirementFence.track(retired)
            check((System.nanoTime() - before) / 1_000_000L < 500L) { "Retirement blocked the caller ($scenario)" }
            check(cancelled.get() && !destroyed.get() && !retired.isCompleted)
            check(!work.submit({ error("retired submit") }, {}))
            finish.countDown()
            runBlocking { withTimeout(2_000) { MpvRetirementFence.await() } }
            while (callbacks.isNotEmpty()) callbacks.remove().invoke()
            check(destroyed.get() && !queuedRan.get() && completed.get() == 0)
        }
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        var activeIdentity = 1
        var count = 0
        val old = LoadScopedIo(1, { it == activeIdentity }, { callbacks.add(it) })
        old.submit({ "result-already-posted" }, { count++ })
        val posted = callbacks.poll(2, TimeUnit.SECONDS) ?: error("No completion")
        activeIdentity = 2
        posted()
        check(count == 0) // Validate identity after dispatch as well as before it.
        runBlocking { old.retire {}.await() }
    }

    private fun downloads() {
        val finishBody = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val cancelBodyStarted = CountDownLatch(1)
        val vtt = "WEBVTT\n\n00:00.000 --> 00:01.000\nBatch 1\n".toByteArray()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/never", "/cancel" -> newChunkedResponse(Response.Status.OK, "text/vtt", object : InputStream() {
                    override fun read(): Int {
                        if (session.uri == "/cancel") cancelBodyStarted.countDown() else bodyStarted.countDown()
                        finishBody.await(); return -1
                    }
                })
                "/oversize", "/chunked-oversize" -> {
                    val body = ByteArray((ExternalSubtitleDownload.MAX_BYTES + 1).toInt()) { 'x'.code.toByte() }.apply { vtt.copyInto(this) }
                    if (session.uri == "/oversize") newFixedLengthResponse(Response.Status.OK, "text/vtt", body.inputStream(), body.size.toLong())
                    else newChunkedResponse(Response.Status.OK, "text/vtt", body.inputStream())
                }
                "/playlist" -> newFixedLengthResponse("#EXTM3U\nhttps://never.invalid/playlist")
                "/slow" -> {
                    Thread.sleep(3_000)
                    newFixedLengthResponse(Response.Status.OK, "text/vtt", vtt.inputStream(), vtt.size.toLong())
                }
                else -> newFixedLengthResponse(Response.Status.OK, "text/vtt", vtt.inputStream(), vtt.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val client = OkHttpClient()
        val files = mutableListOf<File>()
        try {
            fun download(path: String, timeout: Long, success: Boolean) {
                val posts = LinkedBlockingQueue<() -> Unit>()
                val io = LoadScopedIo(path, { true }, { posts.add(it) })
                val file = File.createTempFile("b1-sub-", ".vtt").also(files::add)
                var result: Result<File>? = null
                val before = System.nanoTime()
                // Preserve Content-Length in this fixture; the chunked fixture separately tests
                // the streaming limit after transparent gzip/decompression.
                val headers = if (path == "oversize") mapOf("Accept-Encoding" to "identity") else emptyMap()
                io.submit({ ExternalSubtitleDownload(client, timeout).fetch("http://127.0.0.1:${server.listeningPort}/$path", headers, file, io) }, { result = it })
                (posts.poll(6, TimeUnit.SECONDS) ?: error("Download never completed: $path")).invoke()
                check(result?.isSuccess == success) { "$path: $result" }
                if (path == "never") check((System.nanoTime() - before) / 1_000_000L < 2_000)
                if (success) check(file.readBytes().contentEquals(vtt)) else check(!file.exists())
                runBlocking { withTimeout(2_000) { io.retire {}.await() } }
            }
            download("fast", 5_000, true)
            download("slow", 5_000, true)
            download("never", 250, false)
            check(bodyStarted.count == 0L)
            download("oversize", 1_000, false)
            download("chunked-oversize", 1_000, false)
            download("playlist", 1_000, false)

            // Active network work is cancelled by release, with no late result reaching a new load.
            val posts = LinkedBlockingQueue<() -> Unit>()
            val io = LoadScopedIo("release", { true }, { posts.add(it) })
            val file = File.createTempFile("b1-release-", ".vtt").also(files::add)
            var completed = false
            io.submit({ ExternalSubtitleDownload(client).fetch("http://127.0.0.1:${server.listeningPort}/cancel", emptyMap(), file, io) }, { completed = true })
            check(cancelBodyStarted.await(2, TimeUnit.SECONDS))
            val before = System.nanoTime()
            runBlocking { withTimeout(2_000) { io.retire { file.delete() }.await() } }
            while (posts.isNotEmpty()) posts.remove().invoke()
            check(!completed && !file.exists() && (System.nanoTime() - before) / 1_000_000L < 2_000)
        } finally {
            finishBody.countDown(); server.stop(); files.forEach(File::delete)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
