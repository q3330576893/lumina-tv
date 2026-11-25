package com.lumina.tv.data

import com.lumina.tv.diag.PageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class ProgressEventType { Start, Progress, Stop }

/** An immutable snapshot. Playback position is payload, never an ordering or ACK key. */
data class PendingProgress(
    val localSessionId: String,
    val sequence: Long,
    val eventId: String,
    val playSessionId: String?,
    val sourceId: String?,
    val itemId: String,
    val eventType: ProgressEventType,
    val positionMs: Long,
    val paused: Boolean,
    val playMethod: String,
    val at: Long,
)

/** Only the owning sender may allocate sequences or close this local playback session. */
class PlaybackProgressSession internal constructor(
    internal val owner: Any,
    val itemId: String,
    val sourceId: String?,
    val playSessionId: String?,
    val playMethod: String,
) {
    val localSessionId: String = UUID.randomUUID().toString()
    internal var sequence = 0L
    @Volatile var isClosed = false
        internal set
    @Volatile internal var stopBarrier: ProgressBarrier? = null
}

/** One application-owned worker handles both new reports and persisted retries. */
internal class ProgressSender(
    scope: CoroutineScope,
    private val queue: ProgressQueue,
    private val retryDelayMs: Long = 10_000L,
    private val send: suspend (PendingProgress) -> Boolean,
) {
    private val admissionLock = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private val worker = scope.launch {
        queue.awaitLoaded()
        for (ignored in wakeups) {
            while (true) {
                val event = queue.claimNext() ?: break
                val handled = try {
                    val terminal = if (!queue.flushAndAwait()) false else try {
                        send(event)
                    } catch (cancelled: CancellationException) {
                        // A single request's deadline must not retire the application sender.
                        currentCoroutineContext().ensureActive()
                        false
                    } catch (failure: EmbyException) {
                        val status = failure.httpStatus
                        if (status == 401 || status == 403) {
                            runCatching { PageLog.add("progress authentication required http=$status; pending retained") }
                            // This client's credentials cannot recover. Leave the exact head intact;
                            // activating the same identity with a new token creates a fresh sender.
                            // Exiting also prevents queued/new wakeups from spinning on rejected auth.
                            return@launch
                        }
                        val permanentFailure = status != null && status in 400..499 && status != 408 && status != 429
                        if (permanentFailure) runCatching {
                            PageLog.add("progress dropped event=${event.eventType} eventId=${event.eventId} http=$status")
                        }
                        permanentFailure
                    } catch (_: Exception) {
                        false
                    }
                    if (terminal) synchronized(admissionLock) {
                        if (!closed) queue.acknowledge(event)
                    }
                    terminal
                } finally {
                    queue.release(event)
                }
                if (!handled) {
                    // Keep the failed head ahead of newer events; there is no separate live path.
                    delay(retryDelayMs)
                }
            }
        }
    }

    init { requestFlush() }

    fun start(
        itemId: String,
        positionMs: Long,
        sourceId: String?,
        playSessionId: String?,
        playMethod: String,
        paused: Boolean,
    ): PlaybackProgressSession = synchronized(admissionLock) {
        check(!closed) { "Progress sender is closed" }
        require(itemId.isNotBlank())
        PlaybackProgressSession(this, itemId, sourceId, playSessionId, playMethod).also {
            enqueue(it, ProgressEventType.Start, positionMs, paused)
        }
    }

    fun progress(session: PlaybackProgressSession, positionMs: Long, paused: Boolean): Boolean =
        synchronized(admissionLock) {
            if (!accepts(session)) return false
            enqueue(session, ProgressEventType.Progress, positionMs, paused)
            true
        }

    fun stop(session: PlaybackProgressSession, positionMs: Long, paused: Boolean): Boolean =
        synchronized(admissionLock) {
            if (!accepts(session)) return false
            // Close synchronously, before enqueueing: exit/dispose and late heartbeats share this gate.
            // Retries retain this Stop's identity; this is local submission deduplication.
            session.isClosed = true
            session.stopBarrier = enqueue(session, ProgressEventType.Stop, positionMs, paused)
            true
        }

    private fun accepts(session: PlaybackProgressSession): Boolean =
        !closed && session.owner === this && !session.isClosed

    // Admission, sequence allocation and enqueueing share a lock, including across caller threads.
    private fun enqueue(session: PlaybackProgressSession, type: ProgressEventType, positionMs: Long, paused: Boolean): ProgressBarrier {
        val barrier = queue.enqueue(PendingProgress(
            localSessionId = session.localSessionId,
            sequence = ++session.sequence,
            eventId = UUID.randomUUID().toString(),
            playSessionId = session.playSessionId,
            sourceId = session.sourceId,
            itemId = session.itemId,
            eventType = type,
            positionMs = positionMs.coerceAtLeast(0L),
            paused = paused,
            playMethod = session.playMethod,
            at = System.currentTimeMillis(),
        ))
        requestFlush()
        return barrier
    }

    suspend fun awaitStopPersisted(session: PlaybackProgressSession): Boolean {
        require(session.owner === this)
        return session.stopBarrier?.let { queue.flushAndAwait(it) } ?: false
    }

    fun requestFlush() {
        wakeups.trySend(Unit)
    }

    fun close() = synchronized(admissionLock) {
        closed = true
        wakeups.close()
        worker.cancel()
    }
}
