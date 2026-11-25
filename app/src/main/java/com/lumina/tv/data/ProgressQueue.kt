package com.lumina.tv.data

import android.content.Context
import com.lumina.tv.diag.PageLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class ProgressQueue internal constructor(
    private val file: File? = null,
    private val storage: ProgressStorage? = file?.let(::FileProgressStorage),
    private val io: SerialProgressIo = ProgressPersistence.io,
) {
    constructor(context: Context, serverUrl: String, userId: String) : this(File(
        context.filesDir,
        "progress_queue_${serverHash(serverUrl)}_${safeUserId(userId)}.json",
    ))

    private val entries = ArrayDeque<PendingProgress>()
    private enum class LoadState { Loading, Missing, Restored, Failed }
    private var loadState = if (storage == null) LoadState.Missing else LoadState.Loading
    private var quarantinedContents: String? = null
    private var inFlight: PendingProgress? = null
    private val loaded = CompletableDeferred<Unit>()
    private var revision = 0L
    private var persistedRevision = -1L
    private var writeScheduled = false
    private var discardRestored = false
    private var closed = false
    private var closingRevision = Long.MAX_VALUE
    private val closedPersistence = CompletableDeferred<Boolean>()
    private val waiters = mutableListOf<Pair<Long, CompletableDeferred<Boolean>>>()

    init {
        if (storage == null) {
            persistedRevision = revision
            loaded.complete(Unit)
        } else io.submit {
            try {
                load()
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    internal suspend fun awaitLoaded() = loaded.await()

    @Synchronized
    fun barrier(): ProgressBarrier = ProgressBarrier(this, revision)

    /** True only after this revision (or a later snapshot) has actually reached storage. */
    suspend fun flushAndAwait(barrier: ProgressBarrier = barrier(), timeoutMs: Long = STOP_FLUSH_TIMEOUT_MS): Boolean {
        require(barrier.owner === this) { "Foreign progress barrier" }
        val completion = CompletableDeferred<Boolean>()
        val persisted = try {
            withTimeoutOrNull(timeoutMs) {
                awaitLoaded()
                synchronized(this@ProgressQueue) {
                    if (persistedRevision >= barrier.revision) completion.complete(true)
                    else if (closed && closedPersistence.isCompleted) completion.complete(false)
                    else {
                        waiters += barrier.revision to completion
                        if (!closed) scheduleWrite()
                    }
                }
                completion.await()
            } ?: false
        } finally {
            synchronized(this) { waiters.removeAll { it.second === completion } }
        }
        if (!persisted) logPersistence("progress flush failed")
        return persisted
    }

    @Synchronized
    internal fun enqueue(event: PendingProgress): ProgressBarrier {
        check(!closed) { "Progress queue is closed" }
        require(event.itemId.isNotBlank() && event.localSessionId.isNotBlank() && event.eventId.isNotBlank())
        require(event.sequence > 0L)
        if (entries.any { it.eventId == event.eventId }) return barrier()
        entries.addLast(event)
        trimToCapacity()
        changed()
        return barrier()
    }

    @Synchronized
    internal fun claimNext(): PendingProgress? {
        check(loaded.isCompleted) { "Progress queue has not loaded" }
        if (closed || loadState == LoadState.Failed) return null
        check(inFlight == null) { "Only one progress event may be in flight" }
        return entries.firstOrNull()?.also { inFlight = it }
    }

    @Synchronized
    internal fun release(event: PendingProgress) {
        if (inFlight == event) inFlight = null
    }

    /** Only the exact event claimed for this HTTP request is acknowledged. No position comparison. */
    @Synchronized
    internal fun acknowledge(sent: PendingProgress): Boolean {
        if (closed) return false
        if (inFlight != sent) return false
        val removed = entries.removeAll {
            it.localSessionId == sent.localSessionId && it.sequence == sent.sequence && it.eventId == sent.eventId
        }
        inFlight = null
        if (removed) changed()
        return removed
    }

    @Synchronized
    internal fun snapshot(): List<PendingProgress> = entries.toList()

    @Synchronized
    fun clear() {
        if (closed) return
        discardRestored = true
        entries.clear()
        changed()
    }

    /** Seal admissions, then leave the final snapshot on the application writer ahead of reopen. */
    @Synchronized
    internal fun close(): ProgressBarrier {
        if (closed) return barrier()
        closed = true
        closingRevision = revision
        scheduleWrite()
        if (storage == null) closedPersistence.complete(true)
        return barrier()
    }

    private fun load() {
        for (attempt in 1..LOAD_MAX_ATTEMPTS) {
            if (attempt > 1) {
                // Stay on FIFO IO: delayed recovery must not overwrite a replacement client's outbox.
                // Only this background writer waits; admissions and the flush deadline remain bounded.
                try {
                    Thread.sleep(LOAD_RETRY_DELAY_MS * (attempt - 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val result = runCatching {
                val json = checkNotNull(storage).read()
                if (json == null) {
                    // File.exists() may also be false when access is denied. Only confirmed absence
                    // may initialize an empty outbox; an existing/unknown file must stay untouched.
                    check(file == null || Files.notExists(file.toPath())) { "Progress snapshot absence is unconfirmed" }
                    LoadState.Missing to emptyList<PendingProgress>()
                } else {
                    val restored = try {
                        decode(json)
                    } catch (failure: Exception) {
                        quarantineCorruptFile(json)
                        throw failure
                    }
                    LoadState.Restored to restored
                }
            }.onFailure {
                logPersistence("progress load failed attempt=$attempt/$LOAD_MAX_ATTEMPTS error=${it.javaClass.simpleName}")
            }.getOrNull() ?: continue

            // File order is enqueue order. Wall clocks and playback positions may move backwards.
            synchronized(this) {
                loadState = result.first
                val admitted = entries.toList()
                entries.clear()
                entries.addAll(((if (discardRestored) emptyList() else result.second) + admitted).distinctBy { it.eventId })
                trimToCapacity()
                changed()
            }
            return
        }
        // Do not turn a failed read into an empty snapshot, including on clear/flush/close.
        // New admissions stay in memory; a fresh queue gets a new, bounded recovery attempt.
        synchronized(this) {
            loadState = LoadState.Failed
        }
    }

    private fun decode(json: String): List<PendingProgress> {
        val input = JSONTokener(json)
        val array = JSONArray(input)
        check(input.nextClean() == '\u0000') { "Trailing progress data" }
        return (0 until array.length()).map { index ->
            // A malformed row makes the snapshot untrusted; silently skipping it loses progress too.
            val item = array.getJSONObject(index)
            val itemId = item.stringOrNull("itemId")
                ?: error("Missing progress itemId")
            val localSessionId = item.stringOrNull("localSessionId")
            val eventId = item.stringOrNull("eventId")
            val sequence = item.optLong("sequence", 0L)
            val identified = localSessionId != null && eventId != null && sequence > 0L
            // Legacy rows cannot reveal playback boundaries. Give EACH row an isolated historical
            // session, persist it on this writer, and never infer a live session from item/source IDs.
            val orphanId = if (identified) null else UUID.randomUUID().toString()
            PendingProgress(
                localSessionId = if (identified) checkNotNull(localSessionId) else "orphan-$orphanId",
                sequence = if (identified) sequence else 1L,
                eventId = if (identified) checkNotNull(eventId) else "orphan-event-$orphanId",
                playSessionId = item.stringOrNull("playSessionId"),
                sourceId = item.stringOrNull("sourceId") ?: item.stringOrNull("mediaSourceId"),
                itemId = itemId,
                eventType = ProgressEventType.entries.firstOrNull { it.name.equals(item.optString("eventType"), true) }
                    ?: ProgressEventType.Stop,
                positionMs = item.optLong("positionMs").coerceAtLeast(0L),
                paused = item.optBoolean("paused", false),
                playMethod = item.optString("playMethod", "DirectPlay"),
                at = item.optLong("at"),
            )
        }
    }

    private fun quarantineCorruptFile(contents: String) {
        val original = file ?: return
        if (quarantinedContents == contents) return
        runCatching {
            val copy = File(original.parentFile, "${original.name}.corrupt-${UUID.randomUUID()}")
            original.inputStream().use { input ->
                FileOutputStream(copy).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }.onSuccess {
            quarantinedContents = contents
            logPersistence("progress corrupt snapshot quarantined; original retained")
        }.onFailure {
            logPersistence("progress quarantine failed error=${it.javaClass.simpleName}; original retained")
        }
    }

    private fun trimToCapacity() {
        // Retain the existing bounded outbox policy. Capacity eviction is not an HTTP ACK,
        // and must never evict the event whose request is still in flight.
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.firstOrNull { it != inFlight } ?: break
            entries.remove(oldest)
        }
    }

    // Only called under the memory lock; JSON and filesystem work never run under it.
    private fun changed() {
        revision++
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (storage == null) {
            persistedRevision = revision
            return
        }
        if (writeScheduled || closedPersistence.isCompleted) return
        writeScheduled = true
        io.submit {
            val (version, snapshot, failedWaiters) = synchronized(this) {
                writeScheduled = false
                val version = revision
                Triple(version, entries.toList(), waiters.filter { it.first <= version })
            }
            val recovered = synchronized(this) { loadState == LoadState.Missing || loadState == LoadState.Restored }
            val success = recovered && runCatching { persist(snapshot) }.onFailure {
                logPersistence("progress persistence failed=${it.javaClass.simpleName}")
            }.isSuccess
            synchronized(this) {
                if (success) persistedRevision = version
                // A failed snapshot only fails callers that were waiting when that attempt
                // began. Callers that arrived during the filesystem write must observe a
                // subsequent retry, even when it writes the same queue revision.
                val completed = if (success) waiters.filter { it.first <= version } else failedWaiters
                waiters.removeAll(completed.toSet())
                completed.forEach { it.second.complete(success) }
                if (closed && version >= closingRevision && !writeScheduled) {
                    closedPersistence.complete(success)
                    if (!success) logPersistence("progress close persistence failed; pending retained")
                }
            }
        }
    }

    private fun persist(snapshot: List<PendingProgress>) {
            val json = JSONArray().apply {
                snapshot.forEach { event ->
                    put(JSONObject()
                        .put("localSessionId", event.localSessionId)
                        .put("sequence", event.sequence)
                        .put("eventId", event.eventId)
                        .put("playSessionId", event.playSessionId ?: JSONObject.NULL)
                        .put("sourceId", event.sourceId ?: JSONObject.NULL)
                        .put("itemId", event.itemId)
                        .put("eventType", event.eventType.name)
                        .put("positionMs", event.positionMs)
                        .put("paused", event.paused)
                        .put("playMethod", event.playMethod)
                        .put("at", event.at))
                }
            }.toString()
            checkNotNull(storage).write(json)
    }

    companion object {
        const val MAX_ENTRIES = 50
        const val STOP_FLUSH_TIMEOUT_MS = 2_000L
        internal const val LOAD_MAX_ATTEMPTS = 3
        private const val LOAD_RETRY_DELAY_MS = 100L

        private fun logPersistence(message: String) {
            runCatching { PageLog.add(message) }
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun serverHash(serverUrl: String): String = MessageDigest.getInstance("SHA-256")
            .digest(requireNotNull(canonicalServerKey(serverUrl)) { "Invalid server address" }.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

        private fun safeUserId(userId: String): String =
            userId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }
}
