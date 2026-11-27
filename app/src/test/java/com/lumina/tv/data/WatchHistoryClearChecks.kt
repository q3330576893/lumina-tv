package com.lumina.tv.data

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V6-T05-A. Every destructive request is confined to an in-process loopback fixture. */
object WatchHistoryClearChecks {
    suspend fun run() {
        withTimeout(15_000L) {
            deletionPages()
            failuresAndAcknowledgements()
            budgets()
            cancellation()
        }
        println("V6-T05-A PASS: 101/unknown-total first-page deletion, success/partial/failure, strict Int/DTO acknowledgements, unchanged 2xx, bounded requests and caller cancellation")
    }

    private class Fixture(count: Int) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
        @Volatile var remaining = (0 until count).map { "id" + it }
        @Volatile var totalKnown = true
        @Volatile var ignoreDeletes = false
        @Volatile var failedIds = emptySet<String>()
        @Volatile var deleteBody = """{"Played":false}"""
        @Volatile var rawPage: String? = null
        @Volatile var failReadAt: Int? = null
        @Volatile var pageSize = 100
        @Volatile var onRead: () -> Unit = {}
        val queries = CopyOnWriteArrayList<Map<String, String>>()
        val deletes = CopyOnWriteArrayList<String>()
        val client: EmbyClient

        init {
            start(SOCKET_READ_TIMEOUT, true)
            client = EmbyClient("http://127.0.0.1:" + listeningPort + "/emby", userId = "fixture")
        }

        override fun serve(session: IHTTPSession): Response {
            check(session.uri.startsWith("/emby/Users/fixture/"))
            if (session.method == Method.GET) {
                check(session.uri == "/emby/Users/fixture/Items")
                queries += session.parameters.mapValues { it.value.single() }
                onRead()
                if (queries.size == failReadAt)
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "fixture failure")
                val body = rawPage ?: JSONObject().apply {
                    put("Items", JSONArray(remaining.take(pageSize).map { JSONObject().put("Id", it) }))
                    if (totalKnown) put("TotalRecordCount", remaining.size)
                }.toString()
                return newFixedLengthResponse(Response.Status.OK, "application/json", body)
            }
            check(session.method == Method.DELETE && session.uri.contains("/PlayedItems/"))
            val id = session.uri.substringAfterLast('/')
            deletes += id
            if (id in failedIds)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "fixture failure")
            if (!ignoreDeletes) remaining = remaining.filterNot { it == id }
            return newFixedLengthResponse(if (deleteBody.isEmpty()) Response.Status.NO_CONTENT else Response.Status.OK,
                "application/json", deleteBody)
        }

        override fun close() { client.close(); stop() }
    }

    private suspend fun deletionPages() {
        for (known in listOf(true, false)) Fixture(101).use { fixture ->
            fixture.totalKnown = known
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.SUCCESS && result.cleared == 101 && result.attempted == 101)
            check(result.stop == WatchHistoryClearStop.COMPLETE && result.failures.isEmpty() && result.unconfirmedIds.isEmpty())
            check(fixture.remaining.isEmpty() && fixture.deletes.toSet().size == 101)
            check(fixture.queries.size == 3 && fixture.queries.all {
                it["StartIndex"] == "0" && it["Limit"] == "100" && it["Filters"] == "IsPlayed"
            })
        }
        // A short page is not completion: the following first-page read confirms deletion.
        Fixture(3).use { fixture ->
            fixture.pageSize = 1
            check(fixture.client.clearWatchHistory() == 3)
            check(fixture.queries.size == 4 && fixture.queries.all { it["StartIndex"] == "0" })
        }
        Fixture(0).use { check(it.client.clearWatchHistory() == 0 && it.deletes.isEmpty()) }
    }

    private suspend fun failuresAndAcknowledgements() {
        Fixture(3).use { fixture ->
            fixture.failedIds = setOf("id0")
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.PARTIAL_FAILURE && result.cleared == 2)
            check(result.stop == WatchHistoryClearStop.NO_PROGRESS && result.failures.single().itemId == "id0")
            check((result.failures.single().cause as EmbyException).httpStatus == 500)
            check(fixture.deletes.size == 3 && fixture.queries.size == 2)
        }
        Fixture(3).use { fixture ->
            fixture.failedIds = fixture.remaining.toSet()
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.FAILURE && result.cleared == 0 && result.failures.size == 3)
            check(fixture.deletes.size == 3 && fixture.queries.size == 2)
            try {
                fixture.client.clearWatchHistory()
                error("Legacy Int wrapper swallowed failed deletes")
            } catch (failure: WatchHistoryClearException) {
                check(failure.result.status == WatchHistoryClearStatus.FAILURE && failure.result.failures.size == 3)
            }
        }
        Fixture(2).use { fixture ->
            fixture.ignoreDeletes = true
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.FAILURE && result.cleared == 0)
            check(result.stop == WatchHistoryClearStop.NO_PROGRESS && result.failures.map { it.itemId }.toSet() == setOf("id0", "id1"))
            check(fixture.queries.size == 2 && fixture.deletes.size == 2) // 2xx is not progress.
        }
        for (body in listOf("", "0", "1", """{"Value":1}""", "{}", """{"Played":false}""",
            """{"UserData":{"Played":false}}""")) Fixture(1).use { fixture ->
            fixture.deleteBody = body
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.SUCCESS && result.cleared == 1) { "Rejected valid ACK: " + body }
        }
        for (body in listOf("null", "true", "1.5", "2147483648", "\"1\"", "[]", "{bad", "{} trailing",
            """{"Value":"broken"}""", """{"Value":1.5}""", """{"Played":true}""", """{"unexpected":1}""")) {
            Fixture(1).use { fixture ->
                fixture.deleteBody = body
                try {
                    fixture.client.clearWatchHistory()
                    error("Malformed acknowledgement became a successful Int: " + body)
                } catch (failure: WatchHistoryClearException) {
                    check(failure.result.status == WatchHistoryClearStatus.FAILURE && failure.result.failures.isNotEmpty())
                    check(failure.result.cleared == 0) // Even an actual deletion cannot hide a parse failure.
                }
            }
        }
        for (body in listOf("{}", "1", """{"Items":[{}]}""", """{"Items":[],"TotalRecordCount":1}""")) {
            Fixture(1).use { fixture ->
                fixture.rawPage = body
                val result = fixture.client.clearWatchHistoryResult()
                check(result.status == WatchHistoryClearStatus.FAILURE && result.stop == WatchHistoryClearStop.REQUEST_FAILED)
                check(result.failures.isNotEmpty() && fixture.deletes.isEmpty())
            }
        }
        Fixture(1).use { fixture ->
            fixture.failReadAt = 1
            check(fixture.client.clearWatchHistoryResult().status == WatchHistoryClearStatus.FAILURE)
        }
        Fixture(2).use { fixture ->
            fixture.pageSize = 1
            fixture.failReadAt = 3
            val result = fixture.client.clearWatchHistoryResult()
            check(result.status == WatchHistoryClearStatus.PARTIAL_FAILURE && result.cleared == 1)
            check(result.stop == WatchHistoryClearStop.REQUEST_FAILED && result.unconfirmedIds == setOf("id1"))
        }
    }

    private suspend fun budgets() {
        Fixture(3).use { fixture ->
            val result = fixture.client.clearWatchHistoryResult(maxDeletes = 1)
            check(result.status == WatchHistoryClearStatus.PARTIAL_FAILURE && result.cleared == 1 && result.attempted == 1)
            check(result.stop == WatchHistoryClearStop.BUDGET_EXHAUSTED && fixture.deletes.size == 1 && fixture.queries.size == 2)
        }
        Fixture(3).use { fixture ->
            fixture.pageSize = 1
            val result = fixture.client.clearWatchHistoryResult(maxPages = 2)
            check(result.stop == WatchHistoryClearStop.BUDGET_EXHAUSTED && result.cleared == 1)
            check(result.status == WatchHistoryClearStatus.PARTIAL_FAILURE && result.unconfirmedIds == setOf("id1"))
            check(fixture.queries.size == 2 && fixture.deletes.size == 2)
        }
        Fixture(1).use { fixture ->
            val result = fixture.client.clearWatchHistoryResult(maxPages = 1)
            check(result.status == WatchHistoryClearStatus.FAILURE && result.cleared == 0 && result.unconfirmedIds == setOf("id0"))
        }
    }

    private suspend fun cancellation() = coroutineScope {
        Fixture(1).use { fixture ->
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            fixture.onRead = { started.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            val request = async { fixture.client.clearWatchHistoryResult() }
            try {
                started.await()
                request.cancel(CancellationException("user cancelled history fixture"))
                try { request.await(); error("Cancellation became a result") }
                catch (_: CancellationException) { check(request.isCancelled && fixture.deletes.isEmpty()) }
            } finally {
                release.countDown()
                request.cancelAndJoin()
            }
        }
    }
}
