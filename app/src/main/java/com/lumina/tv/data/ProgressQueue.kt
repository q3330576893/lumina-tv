package com.lumina.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProgressQueue internal constructor(private val file: File? = null) {
    constructor(context: Context, serverUrl: String, userId: String) : this(File(
        context.filesDir,
        "progress_queue_${serverHash(serverUrl)}_${safeUserId(userId)}.json",
    ))

    private val entries = ArrayDeque<PendingProgress>()
    private var inFlight: PendingProgress? = null

    init { load() }

    @Synchronized
    internal fun enqueue(event: PendingProgress) {
        require(event.itemId.isNotBlank() && event.localSessionId.isNotBlank() && event.eventId.isNotBlank())
        require(event.sequence > 0L)
        if (entries.any { it.eventId == event.eventId }) return
        entries.addLast(event)
        trimToCapacity()
        persist()
    }

    @Synchronized
    internal fun claimNext(): PendingProgress? {
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
        if (inFlight != sent) return false
        val removed = entries.removeAll {
            it.localSessionId == sent.localSessionId && it.sequence == sent.sequence && it.eventId == sent.eventId
        }
        inFlight = null
        if (removed) persist()
        return removed
    }

    @Synchronized
    internal fun snapshot(): List<PendingProgress> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        if (file?.exists() == true) file.delete()
    }

    private fun load() {
        val restored = runCatching {
            val array = JSONArray(file?.takeIf { it.exists() }?.readText().orEmpty().ifBlank { "[]" })
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val itemId = item.optString("itemId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val localSessionId = item.stringOrNull("localSessionId")
                val eventId = item.stringOrNull("eventId")
                val sequence = item.optLong("sequence", 0L)
                val identified = localSessionId != null && eventId != null && sequence > 0L
                // Legacy rows cannot reveal playback boundaries. Give EACH row an isolated historical
                // session, persist it immediately, and never infer a live session from item/source IDs.
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
        }.getOrDefault(emptyList())

        // File order is enqueue order. Wall clocks and playback positions may move backwards.
        entries.addAll(restored.distinctBy { it.eventId })
        trimToCapacity()
        if (file?.exists() == true) persist()
    }

    private fun trimToCapacity() {
        // Retain the existing bounded outbox policy. Capacity eviction is not an HTTP ACK,
        // and must never evict the event whose request is still in flight.
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.firstOrNull { it != inFlight } ?: break
            entries.remove(oldest)
        }
    }

    private fun persist() {
        val destination = file ?: return
        runCatching {
            val json = JSONArray().apply {
                entries.forEach { event ->
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
            val temp = File(destination.parentFile, "${destination.name}.tmp")
            temp.writeText(json)
            if (!temp.renameTo(destination)) {
                destination.writeText(json)
                temp.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 50

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
