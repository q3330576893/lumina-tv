package com.lumina.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files

/** Real outbox and worker, deterministic delayed HTTP results, no Android runtime or server. */
object P3ProgressChecks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        withTimeout(15_000L) {
            delayedProgressAck()
            delayedStopAck()
            queueIdentityAndMigration()
            concurrentClose()
            requestTimeoutAndOwnerCancellation()
            httpFailureClassification()
        }
        println("P3 PASS: A seek-back ACK; B old Stop/new session; one sender for live/retry; exact ACK; Stop deduplication; closed heartbeat rejection; orphan migration; timeout/cancellation; HTTP failure classification")
    }

    private data class Attempt(val event: PendingProgress, val response: CompletableDeferred<Boolean>)

    private class Harness(parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        val queue = ProgressQueue()
        private val attempts = Channel<Attempt>(Channel.UNLIMITED)
        private var activeRequests = 0
        val sent = mutableListOf<PendingProgress>()
        val sender = ProgressSender(scope, queue, retryDelayMs = 1L) { event ->
            check(++activeRequests == 1) { "Live and retry requests overlapped" }
            try {
                sent += event
                val attempt = Attempt(event, CompletableDeferred())
                attempts.send(attempt)
                attempt.response.await()
            } finally {
                activeRequests--
            }
        }

        fun start(source: String = "source-a", playSession: String = "emby-a") =
            sender.start("same-item", 0L, source, playSession, "DirectPlay", false)

        suspend fun next(): Attempt = withTimeout(3_000L) { attempts.receive() }

        suspend fun drain() {
            while (queue.snapshot().isNotEmpty()) yield()
        }

        fun close() {
            sender.close()
            scope.cancel()
        }
    }

    private suspend fun delayedProgressAck() = coroutineScope {
        val h = Harness(this)
        try {
            val session = h.start()
            h.next().response.complete(true)
            check(h.sender.progress(session, 600_000L, false))
            val old = h.next()
            check(old.event.eventType == ProgressEventType.Progress && old.event.positionMs == 600_000L)
            // First send fails; requestFlush must use the same worker for its retry.
            old.response.complete(false)
            h.sender.requestFlush()
            val replay = h.next()
            check(replay.event == old.event)
            check(h.sender.progress(session, 120_000L, true))
            val newer = h.queue.snapshot().last()
            check(newer.sequence > replay.event.sequence && newer.eventId != replay.event.eventId)
            check(newer.positionMs == 120_000L && newer.paused)
            replay.response.complete(true)
            val seekBack = h.next()
            check(seekBack.event == newer)
            check(h.queue.snapshot() == listOf(newer)) { "A: old 600s ACK removed new 120s progress" }
            // Also retain the new progress after its own failure, and retry the identical snapshot.
            seekBack.response.complete(false)
            val retriedSeek = h.next()
            check(retriedSeek.event == newer)
            retriedSeek.response.complete(true)
            h.drain()
        } finally {
            h.close()
        }
    }

    private suspend fun delayedStopAck() = coroutineScope {
        val h = Harness(this)
        try {
            val oldSession = h.start()
            h.next().response.complete(true)
            check(h.sender.stop(oldSession, 600_000L, true))
            check(oldSession.isClosed)
            check(!h.sender.stop(oldSession, 120_000L, false)) // exit followed by onDispose
            check(!h.sender.progress(oldSession, 121_000L, false)) // terminal/late heartbeat
            val stop = h.next()
            check(stop.event.eventType == ProgressEventType.Stop && stop.event.sequence == 2L)
            val newSession = h.start(source = "source-b", playSession = "emby-b")
            check(oldSession.localSessionId != newSession.localSessionId)
            check(h.sender.progress(newSession, 120_000L, false))
            val newEvents = h.queue.snapshot().filter { it.localSessionId == newSession.localSessionId }
            check(newEvents.size == 2 && newEvents.map { it.sequence } == listOf(1L, 2L))
            check(newEvents.all { it.sourceId == "source-b" && it.playSessionId == "emby-b" })
            stop.response.complete(true)
            val newStart = h.next()
            check(h.queue.snapshot() == newEvents) { "B: old Stop ACK removed new session events" }
            check(!h.sender.stop(oldSession, 900_000L, true)) // still idempotent after ACK
            check(!h.sender.progress(oldSession, 900_001L, false))
            newStart.response.complete(true)
            h.next().response.complete(true)
            h.drain()
            check(h.sent.count { it.localSessionId == oldSession.localSessionId && it.eventType == ProgressEventType.Stop } == 1)
        } finally {
            h.close()
        }
    }

    private suspend fun queueIdentityAndMigration() {
        val file = Files.createTempFile("lumina-p3-progress-", ".json").toFile()
        try {
            file.writeText(JSONArray().put(JSONObject()
                .put("itemId", "same-item").put("positionMs", 600_000L).put("at", 20L)
                .put("eventType", "progress").put("mediaSourceId", "old-source"))
                .put(JSONObject().put("itemId", "same-item").put("positionMs", 120_000L)
                    .put("eventType", "stop").put("at", 10L).put("mediaSourceId", JSONObject.NULL)).toString())
            val queue = ProgressQueue(file)
            queue.awaitLoaded()
            val orphans = queue.snapshot()
            check(orphans.size == 2 && orphans.all { it.localSessionId.startsWith("orphan-") && it.sequence == 1L })
            check(orphans.map { it.localSessionId }.distinct().size == 2)
            check(orphans.map { it.positionMs } == listOf(600_000L, 120_000L))
            check(orphans.first().sourceId == "old-source" && orphans.last().sourceId == null)
            check(queue.flushAndAwait())
            val migrated = ProgressQueue(file)
            migrated.awaitLoaded()
            check(migrated.snapshot() == orphans) { "Migration identities changed on restart" }
            check(migrated.flushAndAwait())
            migrated.close()
            val live = orphans.first().copy(localSessionId = "live", eventId = "live-event", sequence = 7L,
                playSessionId = "emby-live", sourceId = "new-source", positionMs = 10_000L)
            queue.enqueue(live)
            check(!queue.acknowledge(live)) { "Unsent events cannot be acknowledged" }
            val claimed = checkNotNull(queue.claimNext())
            check(!queue.acknowledge(claimed.copy(localSessionId = "live")))
            check(!queue.acknowledge(claimed.copy(sequence = 999L)))
            check(!queue.acknowledge(claimed.copy(eventId = "other")))
            check(queue.acknowledge(claimed))
            check(!queue.acknowledge(claimed))
            check(queue.snapshot() == listOf(orphans.last(), live))
            check(queue.acknowledge(checkNotNull(queue.claimNext())))
            check(queue.snapshot() == listOf(live))
            check(queue.flushAndAwait())
            val restarted = ProgressQueue(file)
            restarted.awaitLoaded()
            check(restarted.snapshot() == listOf(live))
            check(restarted.flushAndAwait())
            restarted.close()
            // Capacity protection must leave an in-flight request and the newest event identifiable.
            check(queue.claimNext() == live)
            repeat(ProgressQueue.MAX_ENTRIES + 2) { index ->
                queue.enqueue(live.copy(eventId = "capacity-$index", sequence = 8L + index))
            }
            val newest = queue.snapshot().last()
            check(queue.snapshot().size == ProgressQueue.MAX_ENTRIES)
            check(queue.acknowledge(live))
            check(newest in queue.snapshot())
            queue.close()
            check(queue.flushAndAwait())
        } finally {
            file.delete()
            java.io.File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    private suspend fun concurrentClose() = coroutineScope {
        val h = Harness(this)
        try {
            val session = h.start()
            val start = h.next() // Hold Start so admission races cannot be hidden by fast HTTP.
            val closures = List(16) { index -> async(Dispatchers.Default) {
                if (index % 2 == 0) h.sender.progress(session, index.toLong(), true)
                h.sender.stop(session, index.toLong(), true)
            } }
            check(closures.count { it.await() } == 1)
            check(!h.sender.progress(session, 100L, false))
            val events = h.queue.snapshot()
            check(events.count { it.eventType == ProgressEventType.Stop } == 1)
            check(events.last().eventType == ProgressEventType.Stop)
            check(events.map { it.sequence } == (1L..events.size.toLong()).toList())
            check(events.map { it.eventId }.distinct().size == events.size)
            start.response.complete(true)
            repeat(events.size - 1) { h.next().response.complete(true) }
            h.drain()
        } finally {
            h.close()
        }
    }

    private suspend fun httpFailureClassification() = coroutineScope {
        for (status in listOf(400, 401, 403, 404, 410, 499, 408, 429, 500, 503)) {
            val harness = Harness(this)
            try {
                harness.start()
                val failed = harness.next()
                harness.sender.start("next-item", 0L, null, "emby-next", "DirectPlay", false)
                failed.response.completeExceptionally(EmbyException("HTTP $status", status))
                val retryable = status == 408 || status == 429 || status >= 500
                if (retryable) {
                    val retry = harness.next()
                    check(retry.event == failed.event) { "HTTP $status discarded a retryable event" }
                    retry.response.complete(true)
                }
                val next = harness.next()
                check(next.event.itemId == "next-item") { "HTTP $status blocked the next item" }
                next.response.complete(true)
                harness.drain()
                check(harness.sent.count { it.eventId == failed.event.eventId } == if (retryable) 2 else 1)
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun requestTimeoutAndOwnerCancellation() = coroutineScope {
        val queue = ProgressQueue()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val retried = CompletableDeferred<PendingProgress>()
        var attempts = 0
        val sender = ProgressSender(scope, queue, retryDelayMs = 1L) { event ->
            attempts++
            if (attempts == 1) withTimeout(1L) { CompletableDeferred<Unit>().await() }
            retried.complete(event)
            true
        }
        try {
            val session = sender.start("timeout-item", 0L, null, "emby", "DirectPlay", false)
            check(retried.await().localSessionId == session.localSessionId)
            while (queue.snapshot().isNotEmpty()) yield()
            check(attempts == 2) { "A per-request timeout killed the application sender" }
        } finally {
            sender.close()
            scope.cancel()
        }

        val h = Harness(this)
        try {
            val session = h.start()
            val inFlight = h.next()
            h.sender.close()
            h.scope.coroutineContext[Job]?.children?.forEach { it.join() }
            check(h.queue.snapshot() == listOf(inFlight.event)) { "Cancellation acknowledged an unsent result" }
            check(!h.sender.progress(session, 1L, false))
            check(!h.sender.stop(session, 1L, true))
        } finally {
            h.close()
        }
    }
}
