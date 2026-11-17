package com.lumina.tv.data

import android.content.SharedPreferences
import com.lumina.tv.ui.screens.loadDetailRecommendations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Account isolation, atomic migration, live recommendation invalidation and reverse-proxy HTTP paths. */
object P5Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        addressChecks()
        contentStateChecks()
        previewChecks()
        withTimeout(20_000L) { recommendationAndHttpChecks() }
        println("P5 PASS: normalized identity/basePath, account partitions, atomic/idempotent migration, recommendation invalidation, preview versions/recovery, proxy HTTP")
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
