package com.lumina.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class PendingProgress(val itemId: String, val positionMs: Long, val at: Long)

class ProgressQueue(
    context: Context,
    serverUrl: String,
    userId: String,
) {
    private val file = File(
        context.filesDir,
        "progress_queue_${serverHash(serverUrl)}_${safeUserId(userId)}.json",
    )
    private val entries = ArrayDeque<PendingProgress>()

    init {
        load()
    }

    @Synchronized
    fun enqueue(progress: PendingProgress) {
        if (progress.itemId.isBlank()) return
        entries.removeAll { it.itemId == progress.itemId }
        entries.addLast(progress)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        persist()
    }

    @Synchronized
    fun peek(limit: Int = RETRY_BATCH_SIZE): List<PendingProgress> =
        entries.take(limit.coerceIn(0, RETRY_BATCH_SIZE))

    @Synchronized
    fun remove(progress: PendingProgress) {
        if (entries.remove(progress)) persist()
    }

    @Synchronized
    fun removeThrough(itemId: String, positionMs: Long) {
        if (entries.removeAll { it.itemId == itemId && it.positionMs <= positionMs }) persist()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun clear() {
        entries.clear()
        if (file.exists()) file.delete()
    }

    private fun load() {
        val restored = runCatching {
            val array = JSONArray(file.takeIf { it.exists() }?.readText().orEmpty().ifBlank { "[]" })
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    PendingProgress(
                        itemId = item.optString("itemId"),
                        positionMs = item.optLong("positionMs"),
                        at = item.optLong("at"),
                    )
                }?.takeIf { it.itemId.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        restored.sortedBy { it.at }.forEach { progress ->
            entries.removeAll { it.itemId == progress.itemId }
            entries.addLast(progress)
        }
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        if (restored.size != entries.size) persist()
    }

    private fun persist() {
        runCatching {
            val json = JSONArray().apply {
                entries.forEach { progress ->
                    put(JSONObject()
                        .put("itemId", progress.itemId)
                        .put("positionMs", progress.positionMs)
                        .put("at", progress.at))
                }
            }.toString()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                file.writeText(json)
                temp.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 50
        const val RETRY_BATCH_SIZE = 5

        private fun serverHash(serverUrl: String): String = MessageDigest.getInstance("SHA-256")
            .digest(EmbyClient.normalize(serverUrl).lowercase().toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

        private fun safeUserId(userId: String): String =
            userId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }
}
