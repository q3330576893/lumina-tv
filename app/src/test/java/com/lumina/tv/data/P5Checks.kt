package com.lumina.tv.data

import android.content.SharedPreferences
import com.lumina.tv.ui.screens.loadDetailRecommendations
import com.lumina.tv.ui.components.VideoSourceProbe
import com.lumina.tv.ui.components.previewProbeCacheKey
import com.lumina.tv.ui.components.previewProbeRequestIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Account isolation, atomic cache commits, credential-scoped probes and reverse-proxy HTTP paths. */
object P5Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        addressChecks()
        contentStateChecks()
        previewChecks()
        homeDiskChecks()
        withTimeout(20_000L) {
            previewProbeChecks()
            recommendationAndHttpChecks()
        }
        println("P5 PASS: normalized identity/basePath, account partitions, atomic/idempotent migration, recommendation invalidation, preview versions/recovery, proxy HTTP; R5-04 atomic home commits; R7-3 credential/client-scoped probes")
    }

    private fun addressChecks() {
        val expected = "https://example.test/Emby"
        val manual = requireNotNull(parseServerAddress(" HTTPS://Example.Test:443/Emby/ "))
        val qr = requireNotNull(parseServerAddress("https://example.test/Emby/web/index.html?user=a&pass=b#login"))
        val pairing = requireNotNull(parseServerAddress("example.test", "https", "443", "/Emby/"))
        val legacyPairing = requireNotNull(parseServerAddress(expected))
        check(listOf(manual, qr, pairing, legacyPairing).all { it.baseUrl == expected })
        check(manual.withEndpoint("http", 8096).baseUrl == "http://example.test:8096/Emby")
        check(manual.resolve("/Videos/id/stream") == "$expected/Videos/id/stream")
        check(manual.resolve("/Emby/Videos/id/stream") == "$expected/Videos/id/stream")
        check(parseServerAddress("https://[::1]:8920/emby/")?.hostWithPath == "[::1]/emby")
        check(parseServerAddress("host/emby", "http", "8096")?.baseUrl == "http://host:8096/emby")
        check(parseServerAddress("host", "http", "99999") == null)
        check(parseServerAddress("ftp://host/emby") == null)
        check(identityScope(expected, "a") == identityScope("HTTPS://EXAMPLE.TEST:443/Emby/", "a"))
        check(identityScope(expected, "a") != identityScope(expected, "b"))
        check(identityScope(expected, "a") != identityScope("https://example.test/emby", "a"))
        check(identityScope(expected, "a") != identityScope("https://example.test", "a"))
        check(identityScope(expected, "") == null)
    }

    private fun contentStateChecks() {
        val a = requireNotNull(identityScope("https://host/emby", "a"))
        val b = requireNotNull(identityScope("https://host/emby", "b"))
        val item = MediaItem("same", "same", positionTicks = 123L)
        val prefs = MemoryPrefs(mapOf("global_intro_seconds" to 17))
        val first = AccountContentState(prefs.sp, a)
        val second = AccountContentState(prefs.sp, b)
        first.setIntroSeconds(item.id, 30)
        second.setIntroSeconds(item.id, 60)
        first.toggleViewHidden("library")
        first.hideResumeItem(item, 100L)
        check(JSONObject(first.introMapJson).getInt(item.id) == 30)
        check(JSONObject(second.introMapJson).getInt(item.id) == 60)
        check(first.hiddenViewIds == setOf("library") && second.hiddenViewIds.isEmpty())
        check(first.visibleResumeItems(listOf(item)).isEmpty())
        check(second.visibleResumeItems(listOf(item)) == listOf(item))
        check(AccountContentState(prefs.sp, a).visibleResumeItems(listOf(item)).isEmpty())
        check(first.visibleResumeItems(listOf(item.copy(positionTicks = 456L))).size == 1)
        check(first.hiddenResumeIds.isEmpty())
        check(prefs.values["global_intro_seconds"] == 17)
        prefs.transactions.filter { edit -> edit.keys.any { it.startsWith("hidden_resume_") } }.forEach { edit ->
            check(edit.containsKey("hidden_resume_ids_account_v1_$a"))
            check(edit.containsKey("hidden_resume_positions_account_v1_$a"))
        }

        val legacy = mapOf<String, Any>("intro_map" to "{\"same\":42}", "hidden_view_ids" to setOf("library"),
            "hidden_resume_ids" to "{\"same\":100,\"incomplete\":90}", "hidden_resume_positions" to "{\"same\":123}")
        val unknown = MemoryPrefs(legacy)
        listOf(a, b, a, b).forEach { scope ->
            val state = AccountContentState(unknown.sp, scope)
            check(state.introMapJson == "{}" && state.hiddenViewIds.isEmpty() && state.hiddenResumeIds.isEmpty())
        }
        check(legacy.all { (key, value) -> unknown.values[key] == value })

        val known = MemoryPrefs(legacy + ("legacy_content_owner_scope" to a))
        check(AccountContentState(known.sp, b).hiddenResumeIds.isEmpty())
        val migrated = AccountContentState(known.sp, a)
        check(JSONObject(migrated.introMapJson).getInt("same") == 42)
        check(migrated.hiddenViewIds == setOf("library"))
        check(migrated.hiddenResumeIds == mapOf("same" to 100L))
        check(migrated.visibleResumeItems(listOf(item)).isEmpty())
        val import = known.transactions.last()
        check(import.keys.containsAll(listOf("hidden_resume_ids", "hidden_resume_positions",
            "hidden_resume_ids_account_v1_$a", "hidden_resume_positions_account_v1_$a", "content_migrated_account_v1_$a")))
        val count = known.transactions.size
        AccountContentState(known.sp, a)
        check(known.transactions.size == count)
        check(AccountContentState(known.sp, b).hiddenResumeIds.isEmpty())

        val interrupted = MemoryPrefs(legacy + ("legacy_content_owner_scope" to a))
        interrupted.failApply = true
        check(runCatching { AccountContentState(interrupted.sp, a) }.isFailure)
        check(interrupted.values["hidden_resume_ids"] == legacy["hidden_resume_ids"])
        check(!interrupted.values.containsKey("content_migrated_account_v1_$a"))
        interrupted.failApply = false
        check(AccountContentState(interrupted.sp, a).hiddenResumeIds == migrated.hiddenResumeIds)
    }

    private fun previewChecks() {
        val item = MediaItem("same", "same", runtimeTicks = 100L, contentVersion = "v1")
        val source = MediaSource("source", "main", "mp4", true, true, emptyList(), size = 1000L)
        fun key(user: String = "a", current: MediaItem = item, media: MediaSource = source, type: String = "clip") =
            requireNotNull(previewCacheKey("https://host/emby", user, current, media, type))
        val first = key()
        check(first == key())
        check(first != key(user = "b"))
        check(first != key(media = source.copy(id = "other")))
        check(first != key(current = item.copy(contentVersion = "v2")))
        check(first != key(media = source.copy(size = 2000L)))
        check(first != key(type = "trailer"))
        check(first == key(current = item.copy(positionTicks = 99L, played = true)))
        check(previewResourceKey(first, "etag1", null, "1000") != previewResourceKey(first, "etag2", null, "1000"))
        check(previewContentVersion(JSONObject("{\"Path\":\"a\",\"DateModified\":\"v1\"}")) !=
            previewContentVersion(JSONObject("{\"Path\":\"a\",\"DateModified\":\"v2\"}")))
        val normal = PreviewCacheRecovery()
        check(!normal.shouldRemove(false) && !normal.shouldRemove(true))
        check(!normal.retry(false, false)) // Network / decoder capability failure.
        val suspected = PreviewCacheRecovery()
        check(suspected.retry(false, true) && !suspected.shouldRemove(false))
        check(suspected.shouldRemove(true) && !suspected.shouldRemove(true))
        check(!suspected.retry(false, true))
        val corrupt = PreviewCacheRecovery()
        check(corrupt.retry(true, false) && corrupt.shouldRemove(false))
        check(!corrupt.retry(true, false) && !corrupt.shouldRemove(true))
    }

    private fun homeDiskChecks() {
        val directory = Files.createTempDirectory("p5-home-disk").toFile()
        val lock = Any()
        val account = AtomicReference<String?>("a")
        val revision = AtomicLong(1L)
        var now = 100_000L
        val store = HomeCacheDiskStore(lock, 30_000L, account::get, revision::get) { now }
        val file = File(directory, "home.json")
        fun snapshot(write: Boolean = false) = requireNotNull(store.snapshot(file, account.get(), revision.get(), write))
        fun write(text: String): Boolean = store.write(snapshot(true)) { check(!Thread.holdsLock(lock)); text }
        try {
            check(store.snapshot(file, "b", revision.get()) == null)
            check(store.snapshot(file, "a", 0L) == null)
            check(write("first") && file.readText() == "first")
            check(store.snapshot(file, "a", revision.get(), forWrite = true) == null)
            check(store.read(snapshot()) { check(!Thread.holdsLock(lock)); it } == "first")
            now += 30_000L
            val older = snapshot(true)
            val newer = snapshot(true)
            check(store.write(newer) { "newest" })
            check(!store.write(older) { "older" } && file.readText() == "newest")

            store.invalidate()
            val obsoleteRevision = snapshot(true)
            revision.incrementAndGet()
            check(!store.write(obsoleteRevision) { "stale revision" })
            val obsoleteAccount = snapshot(true)
            val obsoleteRead = snapshot()
            account.set("b")
            check(!store.write(obsoleteAccount) { "wrong account" })
            check(store.read(obsoleteRead) { it } == null && file.readText() == "newest")
            account.set("a")
            store.invalidate()

            val readStarted = CountDownLatch(1)
            val readRelease = CountDownLatch(1)
            val result = AtomicReference<String?>("unpublished")
            val readerSnapshot = snapshot()
            val reader = thread {
                result.set(store.read(readerSnapshot) {
                    check(!Thread.holdsLock(lock))
                    readStarted.countDown()
                    check(readRelease.await(3, TimeUnit.SECONDS))
                    it
                })
            }
            try {
                check(readStarted.await(3, TimeUnit.SECONDS))
                synchronized(lock) { revision.incrementAndGet(); store.invalidate() }
            } finally { readRelease.countDown(); reader.join(3_000L) }
            check(!reader.isAlive && result.get() == null)

            // A paused serializer must not hold the cache lock or resurrect a removed/account-switched file.
            for (switchAccount in listOf(false, true)) {
                store.invalidate()
                val writeStarted = CountDownLatch(1)
                val writeRelease = CountDownLatch(1)
                val committed = AtomicReference<Boolean?>()
                val pending = snapshot(true)
                val writer = thread {
                    committed.set(store.write(pending) {
                        check(!Thread.holdsLock(lock))
                        writeStarted.countDown()
                        check(writeRelease.await(3, TimeUnit.SECONDS))
                        "stale"
                    })
                }
                try {
                    check(writeStarted.await(3, TimeUnit.SECONDS))
                    synchronized(lock) {
                        if (switchAccount) account.set("b")
                        store.invalidate(file)
                    }
                } finally { writeRelease.countDown(); writer.join(3_000L) }
                check(!writer.isAlive && committed.get() == false && !file.exists())
                check(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
                account.set("a")
            }

            // A failed replacement leaves the existing document intact and does not consume its retry interval.
            check(write("retained"))
            now += 30_000L
            check(!store.write(snapshot(true)) { throw java.io.IOException("encode failure") })
            check(file.readText() == "retained" && write("retry") && file.readText() == "retry")
            val blocked = File(directory, "blocked").also { check(it.mkdir()) }
            File(blocked, "child").writeText("keep")
            val blockedSnapshot = requireNotNull(store.snapshot(blocked, "a", revision.get(), true))
            check(!store.write(blockedSnapshot) { "cannot replace a nonempty directory" })
            check(File(blocked, "child").readText() == "keep")
        } finally { directory.deleteRecursively() }
    }

    private suspend fun previewProbeChecks() {
        val attempts = AtomicInteger()
        val firstClient = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            val allowed = chain.request().header("Authorization") == "Bearer restored-secret"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (allowed) 200 else 403).message("probe")
                .header("Content-Type", if (allowed) "video/mp4" else "application/json")
                .body((if (allowed) "0000ftypvideo" else "{}").toResponseBody()).build()
        }.build()
        val secondClient = firstClient.newBuilder().build()
        val url = "https://preview.example.test/movie.mp4?api_key=url-secret"
        val bad = linkedMapOf("Authorization" to "Bearer expired-secret", "Cookie" to "SID=cookie-secret")
        val good = bad + ("Authorization" to "Bearer restored-secret")
        try {
            val key = previewProbeCacheKey(url, "media", bad, firstClient)
            val reordered = linkedMapOf("cookie" to "SID=cookie-secret", "authorization" to "Bearer expired-secret")
            check(key == previewProbeCacheKey(url, "media", reordered, firstClient))
            check(key != previewProbeCacheKey(url, "media", good, firstClient))
            check(key != previewProbeCacheKey(url, "media", bad, secondClient))
            check(key != previewProbeCacheKey("$url&v=2", "media", bad, firstClient))
            check(key != previewProbeCacheKey(url, "other-media", bad, firstClient))
            check(previewProbeRequestIdentity(bad, null) != previewProbeRequestIdentity(good, null))
            check(key.resourceDigest.matches(Regex("[0-9a-f]{64}")))
            for (secret in listOf("expired-secret", "restored-secret", "cookie-secret", "url-secret")) {
                check(secret !in key.toString())
            }
            check(VideoSourceProbe.probe(url, "media", bad, firstClient).valid == false && attempts.get() == 1)
            check(VideoSourceProbe.probe(url, "media", reordered, firstClient).valid == false && attempts.get() == 1)
            check(VideoSourceProbe.probe(url, "media", good, firstClient).valid == true && attempts.get() == 2)
            check(VideoSourceProbe.probe(url, "media", good, firstClient).valid == true && attempts.get() == 3)
            check(VideoSourceProbe.probe(url, "media", bad, secondClient).valid == false && attempts.get() == 4)
        } finally {
            firstClient.connectionPool.evictAll()
            secondClient.connectionPool.evictAll()
            firstClient.dispatcher.executorService.shutdownNow()
            secondClient.dispatcher.executorService.shutdownNow()
        }
    }

    private suspend fun recommendationAndHttpChecks() {
        val requests = CopyOnWriteArrayList<String>()
        var watched = true
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "p5-proxy") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val line = input.readLine()
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    repeat(headers["content-length"]?.toIntOrNull() ?: 0) { input.read() }
                    val parts = line.split(' ')
                    val target = parts[1]
                    requests += line
                    val body = when {
                        target.contains("AuthenticateByName") -> "{\"AccessToken\":\"fake\",\"User\":{\"Id\":\"a\",\"Name\":\"A\"}}"
                        target.contains("/System/Info") -> "{\"ServerName\":\"proxy\"}"
                        target.contains("/PlayedItems/") -> { watched = parts[0] != "DELETE"; "{\"Played\":$watched}" }
                        target.contains("/Sessions/") -> "{}"
                        target.contains("/Similar") || target.contains("/Users/") -> {
                            val penalized = watched && !target.contains("/Users/b/") && !target.contains("UserId=b")
                            """{"Items":[{"Id":"candidate","Name":"candidate","Type":"Movie","Genres":["Drama"],"CommunityRating":9,"UserData":{"Played":$penalized}},{"Id":"other","Name":"other","Type":"Movie","Genres":["Drama"],"CommunityRating":1,"UserData":{"Played":false}}]}"""
                        }
                        else -> "{}"
                    }.toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                }
            } catch (e: SocketException) { check(server.isClosed) }
        }
        val base = "http://127.0.0.1:${server.localPort}/emby"
        val a = EmbyClient(base, userId = "a")
        val b = EmbyClient(base, userId = "b")
        try {
            check(a.testConnection("$base/") == "proxy")
            check(a.login(base, "a", "fake").baseUrl == base)
            check(a.imageUrl("item", "tag")?.startsWith("$base/Items/") == true)
            val absUrl = EmbyClient::class.java.getDeclaredMethod("absUrl", String::class.java).apply { isAccessible = true }
            check(absUrl.invoke(a, "/Videos/item/stream") == "$base/Videos/item/stream")
            check(absUrl.invoke(a, "/emby/Videos/item/stream") == "$base/Videos/item/stream")
            val current = MediaItem("current", "current", type = "Movie", genres = listOf("Drama"))
            check(loadDetailRecommendations(a, current.id, current).first().id == "other")
            val before = requests.size
            check(loadDetailRecommendations(a, current.id, current).first().id == "other")
            check(requests.size == before) // Normal re-entry makes no recommendation request.
            check(loadDetailRecommendations(b, current.id, current).first().id == "candidate")
            val account = requireNotNull(identityScope(base, "a"))
            val oldRevision = DetailRecommendationCache.revision(account)
            check(!a.setPlayed("candidate", false).getOrThrow())
            check(DetailRecommendationCache.revision(account) > oldRevision)
            check(loadDetailRecommendations(a, current.id, current).first().id == "candidate")
            val key = RelatedCacheKey(account, "race", "Movie", "", "")
            DetailRecommendationCache.put(key, listOf(current), "today", oldRevision)
            check(DetailRecommendationCache.get(key, "today") == null)
            val bBefore = requests.size
            loadDetailRecommendations(b, current.id, current)
            check(requests.size == bBefore) // A's mutation leaves B's cache intact.
            val revisionBeforeStart = DetailRecommendationCache.revision(account)
            val start = a.reportStart("candidate", 0L, "source", "DirectPlay", null)
            withTimeout(3_000L) {
                while (DetailRecommendationCache.revision(account) == revisionBeforeStart) yield()
            }
            val revisionBeforeProgress = DetailRecommendationCache.revision(account)
            a.reportProgress(start, 10_000L, false)
            withTimeout(3_000L) {
                while (DetailRecommendationCache.revision(account) == revisionBeforeProgress ||
                    requests.none { it.contains("/Sessions/Playing/Progress") }) yield()
            }
            check(requests.all { it.split(' ')[1].startsWith("/emby/") })
        } finally { a.close(); b.close(); server.close(); worker.join(1_000L) }
    }

    private class MemoryPrefs(initial: Map<String, Any>) {
        val values = initial.toMutableMap()
        val transactions = mutableListOf<Map<String, Any?>>()
        var failApply = false
        val sp: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getString", "getStringSet", "getBoolean" -> values[args[0]] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor()
                else -> error(name)
            }
        }
        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            lateinit var edit: SharedPreferences.Editor
            edit = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "remove" -> { changes[args[0] as String] = null; edit }
                    "putBoolean", "putString", "putStringSet" -> { changes[args[0] as String] = args[1]; edit }
                    "apply" -> {
                        check(!failApply)
                        transactions += changes.toMap()
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        null
                    }
                    else -> error(name)
                }
            }
            return edit
        }
    }

    private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        }))
}
