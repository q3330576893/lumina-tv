package com.lumina.tv

import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.screens.UserFlagMutation
import com.lumina.tv.ui.screens.libraryBrowseKey
import com.lumina.tv.ui.screens.sortParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises real HTTP methods/read-back and asynchronous UI state, without a device or credentials. */
object P1RegressionChecks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        userDataHttpChecks()
        mutationChecks()
        browseChecks()
        println("P1 regression checks passed (HTTP, mutation ordering/rollback/isolation, browse identity/sample retention).")
    }

    private suspend fun userDataHttpChecks() {
        val responses = LinkedBlockingQueue<Pair<Int, String>>()
        val requests = LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "p1-local-http") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine().split(' ')
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    repeat(headers["content-length"]?.toIntOrNull() ?: 0) { check(input.read() != -1) }
                    requests.add("${first[0]} ${first[1].substringBefore('?')}")
                    val (code, body) = responses.remove()
                    val bytes = body.toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) serverFailure.set(error)
            }
        }
        val client = EmbyClient("http://127.0.0.1:${server.localPort}", userId = "user")
        try {
            for (nested in listOf(false, true)) {
                for ((endpoint, field) in listOf("FavoriteItems" to "IsFavorite", "PlayedItems" to "Played")) {
                    for (target in listOf(true, false)) {
                        val data = "\"$field\":$target"
                        responses.add(200 to if (nested) "{\"UserData\":{$data}}" else "{$data}")
                        val result = if (field == "Played") client.setPlayed("item", target) else client.setFavorite("item", target)
                        check(result.isSuccess && result.getOrThrow() == target)
                        check(requests.remove() == "${if (target) "POST" else "DELETE"} /Users/user/$endpoint/item")
                    }
                }
            }
            // The server's actual value wins even when it differs from the requested value.
            responses.add(200 to "{\"IsFavorite\":false}")
            check(client.setFavorite("item", true).getOrThrow() == false)
            requests.remove()
            responses.add(204 to "")
            responses.add(200 to "{\"UserData\":{\"Played\":false}}")
            check(client.setPlayed("item", true).getOrThrow() == false)
            check(requests.remove() == "POST /Users/user/PlayedItems/item")
            check(requests.remove() == "GET /Users/user/Items/item")
            responses.add(500 to "failure")
            check(client.setFavorite("item", false).isFailure)
            requests.remove()
            responses.add(200 to "{}")
            responses.add(200 to "{\"UserData\":{}}")
            check(client.setPlayed("item", true).isFailure)
            requests.remove(); requests.remove()
            check(responses.isEmpty() && requests.isEmpty())
            server.close()
            check(client.setFavorite("item", true).isFailure)
            println("D01 HTTP PASS: four actions, root/nested JSON, server override, 204 read-back, HTTP/network/missing-state failures")
        } finally {
            server.close()
            worker.join(1_000)
            client.close()
            serverFailure.get()?.let { throw it }
        }
    }

    private suspend fun mutationChecks() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val action = UserFlagMutation(initial = true)
            val response = CompletableDeferred<Result<Boolean>>()
            val completed = CompletableDeferred<Unit>()
            var requests = 0
            var invalidations = 0
            action.toggle(owner, { target -> check(!target); requests++; response.await() },
                { actual -> check(!actual); invalidations++; completed.complete(Unit) }, { error -> throw error })
            check(action.pending && !action.value)
            action.toggle(owner, { requests++; Result.success(it) }, {}, { throw it })
            check(requests == 1) // Repeated remote key events cannot overlap the accepted operation.
            action.syncFromServer(true) // Late detail refresh from before the click.
            check(!action.value)
            val nextItem = UserFlagMutation(initial = true)
            response.complete(Result.success(false))
            withTimeout(5_000) { completed.await() }
            check(!action.value && !action.pending && invalidations == 1 && nextItem.value)

            val failed = CompletableDeferred<Unit>()
            action.toggle(owner, { Result.failure(IllegalStateException("offline")) },
                { error("failure must not invalidate caches") }, { failed.complete(Unit) })
            withTimeout(5_000) { failed.await() }
            check(!action.value && !action.pending)
            action.toggle(owner, { Result.success(false) }, {}, { throw it })
            check(!action.value && !action.pending)
            println("D01 UI state PASS: cancel favorite stays false; pending dedup; late refresh ignored; failure rollback; next item isolated")
        } finally { owner.cancel() }
    }

    private fun browseChecks() {
        LibraryCache.clear()
        for (order in listOf("Ascending", "Descending")) {
            check(sortParams("runtime", order) == "Runtime" to order)
            check(libraryBrowseKey("library", "runtime", order).endsWith(":Runtime:$order"))
        }
        check(libraryBrowseKey("library", "runtime", "Ascending") != libraryBrowseKey("library", "runtime", "Descending"))
        check(sortParams("random", "Descending") == "Random" to "Ascending")
        val key = libraryBrowseKey("library", "random", "Descending")
        check(key == libraryBrowseKey("library", "random", "Ascending"))
        val sample = (1..50).map { MediaItem("$it", "Item $it") }
        LibraryCache.putBrowse(key, sample, fixedSample = true)
        repeat(45) { LibraryCache.putBrowse("prefetch:$it", emptyList()) }
        check(LibraryCache.browseOf(key, ttl = -1L) == sample) // Expiry/prefetch cannot reshuffle an active sample.
        val revision = LibraryCache.userDataRevision
        LibraryCache.clear()
        LibraryCache.putBrowse("favorites", sample, revision)
        check(LibraryCache.browseOf("favorites") == null) // Late reads cannot resurrect invalidated data.
        println("D03 PASS: runtime directions, final SortBy/SortOrder identity, fixed random sample survives TTL/prefetch; stale cache writes rejected")
    }
}
