package com.lumina.tv.data

import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object B2ProgressChecks {
    suspend fun run() {
        for (delayMs in listOf(100L, 1_000L)) slowStorage(delayMs)
        failureAndReopen()
        overlappingFailureAndRetry()
        senderStopBarrier()
        println("B2 PASS Q-006: serial IO; 100ms/1s slow storage; exact ACK; bounded Stop barrier; failure/retry; clear/close/reopen")
    }

    private fun event(sequence: Long, type: ProgressEventType = ProgressEventType.Progress) = PendingProgress(
        "b2-session", sequence, "b2-event-$sequence", "emby", "source", "item", type,
        600_000L - sequence, true, "DirectPlay", sequence,
    )

    private class Storage(private val delegate: ProgressStorage? = null) : ProgressStorage {
        @Volatile var json: String? = null
        @Volatile var gate: CountDownLatch? = null
        @Volatile var fail = false
        var delayMs = 0L
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val threads = CopyOnWriteArrayList<String>()
        override fun read(): String? {
            threads += Thread.currentThread().name
            return delegate?.read() ?: json
        }
        override fun write(json: String) {
            threads += Thread.currentThread().name
            entered.trySend(Unit)
            gate?.let { check(it.await(5, TimeUnit.SECONDS)) { "Storage gate timed out" } }
            if (delayMs > 0) Thread.sleep(delayMs)
            if (fail) throw IOException("injected disk failure")
            delegate?.write(json)
            this.json = json
        }
    }

    private suspend fun slowStorage(delayMs: Long) = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val io = SerialProgressIo(scope)
        val storage = Storage()
        val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "b2-ui") }.asCoroutineDispatcher()
        try {
            val queue = ProgressQueue(storage = storage, io = io)
            queue.awaitLoaded()
            check(queue.flushAndAwait())
            while (storage.entered.tryReceive().isSuccess) Unit
            val gate = CountDownLatch(1)
            storage.gate = gate
            storage.delayMs = delayMs
            try {
                val start = event(1, ProgressEventType.Start)
                withContext(ui) { queue.enqueue(start) }
                storage.entered.receive() // Real writer is now held in filesystem work.
                val stop = event(3, ProgressEventType.Stop)
                val barrier = withTimeout(1_000L) { withContext(ui) {
                    check(queue.claimNext() == start)
                    queue.enqueue(event(2))
                    check(!queue.acknowledge(start.copy(sequence = 2)))
                    check(queue.acknowledge(start))
                    queue.enqueue(stop)
                } }
                check(queue.snapshot().map { it.sequence } == listOf(2L, 3L))
                check(!queue.flushAndAwait(barrier, timeoutMs = 30L))
                gate.countDown()
                check(queue.flushAndAwait(barrier, timeoutMs = 4_000L))
                queue.close()
                check(queue.flushAndAwait())
                val restarted = ProgressQueue(storage = storage, io = io)
                restarted.awaitLoaded()
                check(restarted.snapshot() == listOf(event(2), stop))
                check(restarted.snapshot().last().eventType == ProgressEventType.Stop)
                restarted.close()
                check(restarted.flushAndAwait())
                check(storage.threads.none { it == "b2-ui" || it == Thread.currentThread().name })
            } finally { gate.countDown() }
        } finally {
            ui.close()
            io.closeAndAwait()
            scope.cancel()
        }
    }

    private suspend fun failureAndReopen() {
        val dir = Files.createTempDirectory("lumina-b2-progress-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val io = SerialProgressIo(scope)
        val file = java.io.File(dir, "queue.json")
        val storage = Storage(FileProgressStorage(file))
        try {
            val queue = ProgressQueue(storage = storage, io = io)
            queue.awaitLoaded()
            val first = queue.enqueue(event(1, ProgressEventType.Start))
            check(queue.flushAndAwait(first))
            val old = file.readText()
            storage.fail = true
            val stop = queue.enqueue(event(2, ProgressEventType.Stop))
            check(!queue.flushAndAwait(stop))
            check(file.readText() == old) // Failed write never truncates the last good file.
            storage.fail = false
            check(queue.flushAndAwait(stop))
            queue.clear()
            queue.close()
            // Reopen is queued immediately, without awaiting the old instance's final disk job.
            val reopened = ProgressQueue(storage = storage, io = io)
            reopened.awaitLoaded()
            check(reopened.snapshot().isEmpty()) { "An old writer resurrected cleared events" }
            check(runCatching { reopened.flushAndAwait(stop) }.isFailure) { "A foreign queue's barrier was accepted" }
            val fresh = event(9)
            reopened.enqueue(fresh)
            check(reopened.flushAndAwait())
            check(queue.flushAndAwait()) // A sealed old barrier cannot schedule stale writes.
            reopened.close()
            val final = ProgressQueue(file, io = io)
            final.awaitLoaded()
            check(final.snapshot() == listOf(fresh))
            final.close()
            check(final.flushAndAwait())
        } finally {
            io.closeAndAwait()
            scope.cancel()
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private suspend fun overlappingFailureAndRetry() = coroutineScope {
        run {
            val dir = Files.createTempDirectory("lumina-b13-progress-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val io = SerialProgressIo(scope)
            val file = java.io.File(dir, "queue.json")
            val delegate = FileProgressStorage(file)
            val allowFirstStart = CountDownLatch(1)
            val allowFailure = List(2) { CountDownLatch(1) }
            val writeEntered = List(2) { CompletableDeferred<Unit>() }
            var attempts = 0
            val stopEvent = event(2, ProgressEventType.Stop)
            val storage = object : ProgressStorage {
                override fun read() = delegate.read()

                override fun write(json: String) {
                    val events = JSONArray(json)
                    if (events.length() > 0 &&
                        events.getJSONObject(events.length() - 1).getString("eventId") == stopEvent.eventId
                    ) {
                        val attempt = attempts++
                        if (attempt < allowFailure.size) {
                            writeEntered[attempt].complete(Unit)
                            check(allowFailure[attempt].await(10, TimeUnit.SECONDS))
                            throw IOException("injected failure for write ${attempt + 1}")
                        }
                    }
                    delegate.write(json)
                }
            }
            try {
                val queue = ProgressQueue(storage = storage, io = io)
                queue.awaitLoaded()
                check(queue.flushAndAwait(queue.enqueue(event(1, ProgressEventType.Start))))
                val old = file.readText()

                io.submit { check(allowFirstStart.await(10, TimeUnit.SECONDS)) }
                val stop = queue.enqueue(stopEvent)
                val first = async(start = CoroutineStart.UNDISPATCHED) { queue.flushAndAwait(stop) }
                allowFirstStart.countDown()
                withTimeout(5_000L) { writeEntered[0].await() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { queue.flushAndAwait(stop) }
                allowFailure[0].countDown()
                check(!first.await())
                withTimeout(5_000L) { writeEntered[1].await() }
                check(file.readText() == old)

                // This attempt is already failing. A retry (or final close) must wait for the
                // next write, even though the next write will persist exactly the same revision.
                val retry = async(start = CoroutineStart.UNDISPATCHED) { queue.flushAndAwait(stop) }
                allowFailure[1].countDown()
                check(!second.await())
                check(retry.await()) { "An older write failure completed the Stop retry" }
                val saved = JSONArray(file.readText())
                check(saved.length() == 2 && saved.getJSONObject(1).let {
                    it.getString("eventId") == stopEvent.eventId &&
                        it.getLong("sequence") == stopEvent.sequence &&
                        it.getString("eventType") == "Stop" && it.getLong("positionMs") == stopEvent.positionMs
                })
                queue.close()
                check(queue.flushAndAwait())
            } finally {
                allowFirstStart.countDown()
                allowFailure.forEach { it.countDown() }
                io.closeAndAwait()
                scope.cancel()
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
        }
        println("B13 PASS progress retry isolation")
    }

    private suspend fun senderStopBarrier() = coroutineScope {
        val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val io = SerialProgressIo(persistenceScope)
        val queue = ProgressQueue(storage = Storage(), io = io)
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sendEntered = CompletableDeferred<Unit>()
        val holdHttp = CompletableDeferred<Boolean>()
        val sender = ProgressSender(senderScope, queue, retryDelayMs = 1) {
            sendEntered.complete(Unit)
            holdHttp.await()
        }
        try {
            val session = sender.start("item", 0, "source", "emby", "DirectPlay", false)
            sendEntered.await()
            check(sender.stop(session, 123_000, true))
            check(!sender.stop(session, 1, false) && !sender.progress(session, 2, false))
            sender.close() // HTTP cancellation must not cancel Stop persistence.
            queue.close()
            check(sender.awaitStopPersisted(session))
            check(queue.snapshot().last().let { it.sequence == 2L && it.eventType == ProgressEventType.Stop })
        } finally {
            sender.close()
            senderScope.cancel()
            io.closeAndAwait()
            persistenceScope.cancel()
        }
    }
}
