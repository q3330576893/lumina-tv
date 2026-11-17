package com.lumina.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RelatedCacheKey(val scope: String, val itemId: String, val type: String, val region: String, val genres: String)
internal data class RelatedCacheEntry(
    val items: List<MediaItem>, val storedAt: Long, val dayIndex: String, val revision: Long,
)

/** Watch changes invalidate only this account. An in-flight read cannot republish an older revision. */
internal object DetailRecommendationCache {
    private val entries = LinkedHashMap<RelatedCacheKey, RelatedCacheEntry>()
    private val versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = versions.asStateFlow()

    fun revision(scope: String): Long = versions.value[scope] ?: 0L

    @Synchronized fun watchStateChanged(serverUrl: String, userId: String) {
        val scope = identityScope(serverUrl, userId) ?: return
        entries.keys.removeAll { it.scope == scope }
        versions.value = versions.value + (scope to revision(scope) + 1L)
    }

    @Synchronized fun get(key: RelatedCacheKey, day: String, now: Long = System.currentTimeMillis()): List<MediaItem>? {
        val entry = entries[key] ?: return null
        val empty = entry.items.isEmpty()
        val ttl = if (empty) 30 * 60 * 1000L else 6 * 60 * 60 * 1000L
        if (entry.revision == revision(key.scope) && (empty || day == entry.dayIndex) && now - entry.storedAt < ttl) return entry.items
        entries.remove(key)
        return null
    }

    @Synchronized fun put(key: RelatedCacheKey, items: List<MediaItem>, day: String, expectedRevision: Long) {
        if (expectedRevision != revision(key.scope)) return
        entries[key] = RelatedCacheEntry(items, System.currentTimeMillis(), day, expectedRevision)
        if (entries.size > 100) entries.remove(entries.keys.first())
    }
}
