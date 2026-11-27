package com.lumina.tv.data

import com.lumina.tv.player.T07PlaybackChecks
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real protocol fixtures; E1 invokes this entry point so plain verifyOffline includes T07. */
object T07ProtocolChecks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        withTimeout(30_000L) {
            latestScopes()
            signedUrlsAndTimelines()
            bifParsingAndDownloads()
            T07PlaybackChecks.run()
        }
        println("V6-T07 PASS: origin, scope, signed URLs plus measured timelines, BIF, TLS/retry/cancel/budget")
    }

    private suspend fun latestScopes() {
        data class Reply(val body: String, val status: Int = 200)
        val replies = ConcurrentLinkedQueue<Reply>()
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests += session.uri to session.parameters.mapValues { it.value.first() }
                val reply = checkNotNull(replies.poll()) { "Unexpected Latest fallback" }
                return newFixedLengthResponse(Response.Status.lookup(reply.status), "application/json", reply.body)
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        try {
            // Exercise every return/fallback branch, with/without ParentId, arrays/envelopes and
            // successful empty results. Empty is terminal and must never broaden the scope.
            for (parent in listOf(null, "library", "library +/%", "")) {
                for (stage in 0..2) for (shape in listOf("array", "envelope", "empty-array", "empty-envelope")) {
                    for (httpFailure in listOf(false, true)) {
                        requests.clear()
                        repeat(stage) { replies += if (httpFailure) Reply("{}", 400) else Reply("{}") }
                        val item = """{"Id":"scoped","Name":"Scoped","Type":"Movie"}"""
                        replies += Reply(when (shape) {
                            "array" -> "[$item]"
                            "envelope" -> """{"Items":[$item]}"""
                            "empty-array" -> "[]"
                            else -> """{"Items":[]}"""
                        })
                        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}/Emby", userId = "user")
                        try {
                            val result = client.getLatest(parent, 7)
                            check(result.map { it.id } == if (shape.startsWith("empty")) emptyList<String>() else listOf("scoped"))
                            check(requests.size == stage + 1 && replies.isEmpty()) { "Empty success triggered fallback" }
                            requests.forEachIndexed { index, (path, params) ->
                                check(path == if (index == 2) "/Emby/Items" else "/Emby/Users/user/Items/Latest")
                                check(params["Limit"] == "7")
                                check(params["ParentId"] == parent && params.containsKey("ParentId") == (parent != null)) {
                                    "Latest stage=$index escaped parent=$parent: $params"
                                }
                                check(params.containsKey("Fields") == (index > 0))
                                if (index == 2) check(params["Recursive"] == "true" && params["SortBy"] == "DateCreated")
                            }
                        } finally { closeT07Client(client) }
                    }
                }
                requests.clear()
                repeat(3) { replies += Reply("{}") }
                val client = EmbyClient("http://127.0.0.1:${server.listeningPort}/Emby", userId = "user")
                try {
                    check(client.getLatest(parent).isEmpty() && requests.size == 3)
                    check(requests.all { it.second["ParentId"] == parent })
                } finally { closeT07Client(client) }
            }
        } finally { server.stop() }
        println("T07 Latest PASS: all three branches, ParentId/null/encoded/blank, arrays/envelopes, empty terminal and HTTP/parse failures")
    }

    private fun signedUrlsAndTimelines() {
        val base = "https://emby.example/Emby"
        val query = "sig=Ab%2fCd%2B%3D&dup=1&space=a%20b&dup=2&space=a+b&flag&empty=&pct=%252F" +
            "&StartTimeTicks=22&AudioStreamIndex=9&PlaySessionId=old&SubtitleStreamIndex=3&SubtitleMethod=Encode"
        val client = EmbyClient(base)
        val method = EmbyClient::class.java.getDeclaredMethod("negotiatedUrl", String::class.java,
            java.lang.Long.TYPE, java.lang.Integer::class.java, String::class.java, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }
        fun rewrite(target: String, remove: Boolean = true): String =
            method.invoke(client, target, 120_000L, 2, "new /+", remove) as String
        try {
            val opaque = listOf(
                "https://cdn.example/Videos/item/master.m3u8",
                "https://emby.example:9443/Emby/Videos/item/master.m3u8",
                "http://emby.example/Emby/Videos/item/master.m3u8",
                "$base/download/file", "$base/Videos/item/signed.bin", "$base/Videos/item/hls/1.ts",
                "https://emby.example/Other/Videos/item/master.m3u8",
                "$base/../Videos/item/master.m3u8", "$base/Videos/%2e%2e/master.m3u8",
            )
            for (path in opaque) for (remove in listOf(false, true)) {
                val target = "$path?$query#fragment%2f"
                check(rewrite(target, remove).toByteArray().contentEquals(target.toByteArray())) { "Signed URL changed: $path" }
            }
            for (endpoint in listOf("master.m3u8", "main.m3u8", "stream", "stream.ts", "stream.mp4")) {
                val path = "$base/Videos/item/$endpoint"
                val retained = query.substringBefore("&StartTimeTicks=")
                val expected = "$path?$retained&StartTimeTicks=1200000000&AudioStreamIndex=2&PlaySessionId=new%20%2F%2B#fragment%2f"
                check(rewrite("$path?$query#fragment%2f") == expected)
                check(rewrite("$path?$query", false).contains("&SubtitleStreamIndex=3&SubtitleMethod=Encode&StartTimeTicks="))
                check(rewrite(path) == "$path?StartTimeTicks=1200000000&AudioStreamIndex=2&PlaySessionId=new%20%2F%2B")
            }
            val mixedCase = "$base/Videos/item/master.m3u8?Start%54imeTicks=1&STARTTIMETICKS=2&dup=a&dup=b"
            check(rewrite(mixedCase).substringAfter('?') ==
                "dup=a&dup=b&StartTimeTicks=1200000000&AudioStreamIndex=2&PlaySessionId=new%20%2F%2B")
            check(runCatching { negotiatedPlaybackUrl(base, "$base/Videos/item/stream", Long.MAX_VALUE,
                null, null, false) }.exceptionOrNull() is ArithmeticException)

            // The exact opaque URL and the physical timeline must survive together. Calibration,
            // never URL host/container, decides full-file versus clipped-stream coordinates.
            val signed = opaque.first() + "?" + query
            for (strm in listOf(false, true)) for (clipped in listOf(false, true)) {
                val target = EmbyClient.PlaybackTarget(rewrite(signed), PlaybackMode.DirectStream,
                    MediaSource("source", "source", "mkv", false, true, emptyList()), 120_000L,
                    "session", 2, null, TimelineOrigin.UNKNOWN, durationMs = 600_000L, isStrmDirectLink = strm)
                check(target.url == signed && target.streamStartOffsetMs == null)
                check(target.playerStartPositionMs == if (strm) 120_000L else null)
                check(target.playerToContentPositionMs(4_000L) == null)
                target.timeline.firstFrame(0)
                target.timeline.sample(if (clipped) 400L else 120_400L, if (clipped) 480_000L else 600_000L, 100)
                check(target.contentToPlayerPositionMs(124_000L) == if (clipped) 4_000L else 124_000L)
                check(target.timeline.position(if (clipped) 4_000L else 124_000L).reportPositionMs == 124_000L)
                check(target.timeline.position(if (clipped) 60_000L else 180_000L).reportPositionMs == 180_000L)
                target.resetTimeline()
                check(target.playerToContentPositionMs(4_000L) == null && target.url == signed)
            }
            val source = File("src/main/java/com/lumina/tv/data/EmbyClient.kt").readText()
            check("else negotiatedUrl(route.first, resolvedStartPositionMs" in source)
            check("streamStartOffsetMs = if (route.third == TimelineOrigin.CONTENT) 0L else null" in source)
            check("timelineOrigin = route.third" in source)
        } finally { closeT07Client(client) }

        val seen = CopyOnWriteArrayList<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                seen += session.queryParameterString
                return newFixedLengthResponse("ok")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val transportClient = EmbyClient("http://127.0.0.1:${server.listeningPort}/Emby")
        try {
            val external = "http://127.0.0.1:${server.listeningPort}/signed?$query"
            val unchanged = negotiatedPlaybackUrl(transportClient.baseUrl, external, 120_000L, 2, "new", true)
            check(unchanged == external)
            transportClient.playbackHttpClient.newCall(Request.Builder().url(unchanged).build()).execute().use { check(it.isSuccessful) }
            check(seen.single() == query) { "On-wire query was normalized: $seen" }
        } finally { closeT07Client(transportClient); server.stop() }
        println("T07 URL/timeline PASS: production URL adapter; byte-exact duplicates/escapes/order/fragment and wire query; known endpoints only; full/clipped/STRM Progress/Stop coordinates")
    }

    private suspend fun bifParsingAndDownloads() = coroutineScope {
        val frames = (0..2).map { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), it.toByte(), 0xFF.toByte(), 0xD9.toByte()) }
        fun fixture(times: List<Long> = listOf(0, 10, 20), multiplier: Long = 1_000, trailing: Int = 0): ByteArray {
            val start = 64 + (times.size + 1) * 8
            val bytes = ByteArray(start + times.size * 5 + trailing)
            byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 13, 10, 26, 10).copyInto(bytes)
            putUint(bytes, 8, 0); putUint(bytes, 12, times.size.toLong()); putUint(bytes, 16, multiplier)
            times.forEachIndexed { i, time ->
                putUint(bytes, 64 + i * 8, time); putUint(bytes, 68 + i * 8, (start + i * 5).toLong())
                frames[i].copyInto(bytes, start + i * 5)
            }
            putUint(bytes, 64 + times.size * 8, 0xFFFFFFFFL)
            putUint(bytes, 68 + times.size * 8, (start + times.size * 5).toLong())
            return bytes
        }
        val standard = fixture()
        val parsed = BifIndex.parse(standard)
        for ((position, index) in listOf(Long.MIN_VALUE to 0, 0L to 0, 9_999L to 0, 10_000L to 1,
            19_999L to 1, 20_000L to 2, Long.MAX_VALUE to 2)) {
            check(parsed.frameIndex(position) == index && parsed.frame(index)!!.contentEquals(frames[index]))
        }
        check(parsed.frame(-1) == null && parsed.frame(3) == null)
        check(BifIndex.parse(fixture(multiplier = 0)).frameIndex(10_000) == 1)
        check(BifIndex.parse(fixture(multiplier = 250)).frameIndex(2_500) == 1)
        check(BifIndex.parse(fixture(trailing = 17)).frame(2)!!.contentEquals(frames[2]))
        check(BifIndex.parse(fixture(listOf(0), trailing = 9)).frame(0)!!.contentEquals(frames[0]))
        val unsignedTimes = BifIndex.parse(fixture(listOf(0, 0x80000000L, 0xFFFFFFFEL), 1))
        check(unsignedTimes.frameIndex(0x80000000L) == 1 && unsignedTimes.frameIndex(0xFFFFFFFEL) == 2)
        check(BifIndex.parse(fixture(multiplier = 0xFFFFFFFFL)).frameIndex(10L * 0xFFFFFFFFL) == 1)

        val corrupt = linkedMapOf<String, ByteArray>()
        fun changed(name: String, offset: Int, value: Long) {
            corrupt[name] = standard.copyOf().also { putUint(it, offset, value) }
        }
        changed("version-1", 8, 1); changed("unsigned-version", 8, 0xFFFFFFFFL)
        changed("zero-count", 12, 0); changed("count-cap", 12, 1_000_001)
        changed("count-int-overflow", 12, 0x7FFFFFFFL); changed("count-unsigned-overflow", 12, 0xFFFFFFFFL)
        changed("bad-sentinel", 64 + 3 * 8, 30); changed("early-sentinel", 64 + 8, 0xFFFFFFFFL)
        changed("descending-time", 64 + 2 * 8, 1)
        changed("offset-in-header", 68, 4); changed("offset-in-index", 68, 80)
        changed("unsigned-offset", 68, 0x80000000L); changed("max-unsigned-offset", 68, 0xFFFFFFFFL)
        changed("descending-offset", 68 + 8, 64); changed("equal-offset", 68 + 8, 96)
        changed("sentinel-before-frame", 68 + 3 * 8, 100)
        changed("sentinel-past-end", 68 + 3 * 8, standard.size + 1L)
        changed("unsigned-sentinel-offset", 68 + 3 * 8, 0xFFFFFFFFL)
        corrupt["timestamp-multiplication-overflow"] = fixture(listOf(0, 1, 0xFFFFFFFEL), 0xFFFFFFFFL)
        corrupt["bad-magic"] = standard.copyOf().also { it[0] = 0 }
        // Every strict prefix is truncated: this includes each header/index/sentinel/JPEG boundary.
        for (length in standard.indices) corrupt["truncated-$length"] = standard.copyOf(length)
        corrupt.forEach { (name, bytes) ->
            check(runCatching { BifIndex.parse(bytes) }.exceptionOrNull() is IOException) { "Accepted corrupt BIF: $name" }
        }

        val calls = AtomicInteger()
        val entered = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        val release = CountDownLatch(1)
        var payload = fixture(trailing = 17)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                calls.incrementAndGet()
                val bytes = payload
                val gate = entered.poll()
                val input = if (gate == null) ByteArrayInputStream(bytes) else object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        gate.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", input, bytes.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}/Emby", userId = "user")
        try {
            check(client.getTrickplayFrame("standard", 0)!!.contentEquals(frames[0]))
            check(client.getTrickplayFrame("standard", 10_000)!!.contentEquals(frames[1]))
            check(client.getTrickplayFrame("standard", Long.MAX_VALUE)!!.contentEquals(frames[2]))
            check(calls.get() == 1) { "Valid BIF was not cached" }
            for ((name, bytes) in corrupt) {
                payload = bytes
                check(client.getTrickplayFrame("bad-$name", 0) == null) { "Corrupt download: $name" }
            }
            payload = corrupt.getValue("unsigned-offset")
            check(client.getTrickplayFrame("retry-corrupt", 0) == null)
            payload = standard
            check(client.getTrickplayFrame("retry-corrupt", 0)!!.contentEquals(frames[0])) { "Corrupt BIF poisoned cache" }

            val cancelGate = CompletableDeferred<Unit>().also { entered += it }
            val cancelled = async { client.getTrickplayFrame("cancelled", 0) }
            cancelGate.await()
            cancelled.cancelAndJoin()
            check(cancelled.isCancelled)
            val budgetGate = CompletableDeferred<Unit>().also { entered += it }
            val budgetStarted = System.nanoTime()
            val exhausted = async {
                runCatching { withRequestBudget(150) { client.getTrickplayFrame("budget", 0) } }
            }
            budgetGate.await()
            val outcome = exhausted.await()
            // Optional BIF deliberately downgrades an HTTP call timeout to null. The inherited
            // coroutine deadline can win the same race and propagate RequestTimeoutException.
            check(outcome.getOrNull() == null &&
                (outcome.exceptionOrNull() == null || outcome.exceptionOrNull() is RequestTimeoutException)) {
                "Optional budget result: $outcome"
            }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - budgetStarted) < 2_000)
            currentCoroutineContext().ensureActive()
            val ownedBudget = runCatching {
                withRequestBudget(25) { withRequestBudget(2_000) { awaitCancellation() } }
            }.exceptionOrNull()
            check(ownedBudget is RequestTimeoutException && ownedBudget.budgetMs == 25L)
            release.countDown()
            check(client.getTrickplayFrame("cancelled", 0)!!.contentEquals(frames[0]))
            check(client.getTrickplayFrame("budget", 0)!!.contentEquals(frames[0]))
        } finally { release.countDown(); closeT07Client(client); server.stop() }
        println("T07 BIF PASS: v0/count@12, sentinel/final frame, unsigned times/offsets, multiplication/count overflow, every truncated prefix, download/cache/retry/cancel/budget")
    }

    private fun putUint(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
}

internal fun closeT07Client(client: EmbyClient) {
    client.close()
    val metadata = EmbyClient::class.java.getDeclaredField("http").apply { isAccessible = true }.get(client) as OkHttpClient
    for (transport in listOf(metadata, client.playbackHttpClient)) {
        transport.dispatcher.cancelAll()
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdown()
    }
}
