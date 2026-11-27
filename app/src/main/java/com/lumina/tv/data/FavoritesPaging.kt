package com.lumina.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

internal const val FAVORITES_SORT = "DateCreated,SortName"
internal const val FAVORITES_PAGE_SIZE = 50
internal const val FAVORITES_MAX_PAGES = 200

data class FavoritesPage(val items: List<MediaItem>, val total: Int?, val nextOffset: Int?)

internal fun favoritesNextOffset(startIndex: Int, rows: Int, total: Int?): Int? {
    require(startIndex >= 0 && rows >= 0)
    val next = startIndex.toLong() + rows
    require(next <= Int.MAX_VALUE) { "Favorites offset overflow" }
    return next.toInt().takeIf { rows > 0 && (total == null || it < total) }
}

internal fun favoritesQueryKey(baseUrl: String, userId: String): String =
    "favorites:full:${identityScope(baseUrl, userId)}:Movie,Series,Episode:$FAVORITES_SORT:Descending"

internal data class FavoritesQueryIdentity(val key: String, val revision: Long, val generation: Long)

internal data class FavoritesAnchor(
    val firstId: String? = null, val index: Int = 0, val offset: Int = 0,
    val focusedId: String? = null, val focusedIndex: Int = 0, val loaded: Int = 0,
) {
    fun indexIn(items: List<MediaItem>): Int = items.indexOfFirst { it.id == firstId }
        .takeIf { it >= 0 } ?: index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    fun focusIn(items: List<MediaItem>): String? = focusedId?.let { id ->
        items.firstOrNull { it.id == id }?.id ?: items.getOrNull(focusedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))?.id
    }
}

internal data class FavoritesPagingState(
    val identity: FavoritesQueryIdentity? = null,
    val items: List<MediaItem> = emptyList(),
    val total: Int? = null,
    val nextOffset: Int? = null,
    val refreshRequired: Boolean = true,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
    val pageRequests: Int = 0,
    val pageIds: Set<Set<String>> = emptySet(),
) {
    val canAutoAppend: Boolean
        get() = !refreshing && !appending && !refreshRequired && error == null &&
            nextOffset != null && items.isNotEmpty()
}

private class FavoritesNoProgress(message: String) : Exception(message)

private fun checkFavoritesProgress(
    page: FavoritesPage,
    offset: Int,
    loadedIds: Set<String>,
    seenPages: Set<Set<String>>,
): Set<String> {
    val ids = page.items.mapTo(LinkedHashSet()) { it.id }
    if (ids.any { it.isBlank() }) throw FavoritesNoProgress("页面包含无效 ID")
    if (page.nextOffset != null && page.nextOffset <= offset)
        throw FavoritesNoProgress("分页偏移未推进")
    if (page.items.isEmpty()) {
        // An empty terminal probe is required when the server does not provide a total.
        if (page.nextOffset != null || (page.total != null && page.total > offset))
            throw FavoritesNoProgress("空页仍有后续收藏")
    } else {
        if (ids in seenPages) throw FavoritesNoProgress("服务器返回重复页")
        if (ids.all { it in loadedIds }) throw FavoritesNoProgress("页面没有新增 ID")
        if (page.nextOffset == null && page.total != null && offset.toLong() + page.items.size < page.total)
            throw FavoritesNoProgress("分页提前终止")
    }
    return ids
}

/** A separate memory cache: home_cache_*.json / HomeData.favorites only contain the preview. */
internal object FavoritesCache {
    data class Entry(val state: FavoritesPagingState, val anchor: FavoritesAnchor)
    private val entries = LinkedHashMap<String, Entry>()
    @Synchronized fun get(key: String): Entry? = entries[key]
    @Synchronized fun put(key: String, state: FavoritesPagingState, anchor: FavoritesAnchor) {
        entries[key] = Entry(state.copy(refreshing = false, appending = false), anchor)
        if (entries.size > 4) entries.remove(entries.keys.first())
    }
    @Synchronized fun invalidate(key: String, itemId: String, favorite: Boolean?, played: Boolean?) {
        val old = entries[key] ?: return
        val items = old.state.items.mapNotNull { item ->
            when {
                item.id != itemId -> item
                favorite == false -> null
                else -> item.copy(isFavorite = favorite ?: item.isFavorite, played = played ?: item.played)
            }
        }
        entries[key] = old.copy(state = old.state.copy(items = items, total = null,
            nextOffset = null, refreshRequired = true, error = null))
    }
    @Synchronized fun clear() = entries.clear()
}

/** One owner for initial refresh, append, mutation refresh and return-position reconstruction. */
internal class FavoritesPager(
    val key: String,
    private val currentRevision: () -> Long,
    private val maxPages: Int = FAVORITES_MAX_PAGES,
    private val fetch: suspend (limit: Int, startIndex: Int) -> FavoritesPage,
) {
    init { require(maxPages in 1..FAVORITES_MAX_PAGES) }
    var state by mutableStateOf(FavoritesCache.get(key)?.state ?: FavoritesPagingState())
        private set
    var anchor = FavoritesCache.get(key)?.anchor ?: FavoritesAnchor()
        private set

    fun saveAnchor(value: FavoritesAnchor) {
        anchor = value
        cache()
    }

    private fun owns(identity: FavoritesQueryIdentity) = state.identity == identity && currentRevision() == identity.revision
    private fun cache() {
        if (state.identity?.revision == currentRevision()) FavoritesCache.put(key, state, anchor)
    }

    fun cancel() {
        state = state.copy(identity = null, refreshing = false, appending = false)
    }

    suspend fun refresh(preview: List<MediaItem>) {
        val identity = FavoritesQueryIdentity(key, currentRevision(), generations.incrementAndGet())
        // A confirmed mutation may have patched the cache while this composable was still mounted.
        val cached = FavoritesCache.get(key)
        val retained = cached?.state?.items ?: state.items
        val target = maxOf(FAVORITES_PAGE_SIZE, anchor.loaded, retained.size)
        state = state.copy(identity = identity, items = retained.ifEmpty { preview },
            refreshRequired = true, refreshing = true, appending = false, error = null)
        try {
            var rebuilt = emptyList<MediaItem>()
            val rebuiltIds = HashSet<String>()
            val seenPages = LinkedHashSet<Set<String>>()
            var requests = 0
            var offset = 0
            var page: FavoritesPage
            do {
                if (requests >= maxPages) throw FavoritesNoProgress("已达到分页请求上限")
                requests++
                page = fetch(FAVORITES_PAGE_SIZE, offset)
                currentCoroutineContext().ensureActive()
                if (!owns(identity)) return
                val ids = checkFavoritesProgress(page, offset, rebuiltIds, seenPages)
                seenPages += ids
                rebuiltIds += ids
                rebuilt = (rebuilt + page.items).distinctBy { it.id }
                offset = page.nextOffset ?: break
            } while (rebuilt.size < target)
            // Swap the reconstructed prefix atomically; never combine new offsets with old membership.
            state = FavoritesPagingState(identity, rebuilt, page.total, page.nextOffset, refreshRequired = false,
                error = if (requests >= maxPages && page.nextOffset != null) "已达到分页请求上限，请重试继续加载" else null,
                pageRequests = requests, pageIds = seenPages)
            cache()
        } catch (cancelled: CancellationException) {
            if (owns(identity)) state = state.copy(error = "刷新已取消，已保留现有收藏，请重试")
            throw cancelled
        } catch (failure: Exception) {
            if (owns(identity)) state = state.copy(error = if (failure is FavoritesNoProgress)
                "刷新已停止：" + failure.message + "，已保留现有收藏，请重试"
                else "刷新失败，已保留现有收藏，请重试")
        } finally {
            if (state.identity == identity) state = state.copy(refreshing = false)
        }
    }

    suspend fun append(automatic: Boolean = false) {
        val identity = state.identity ?: return
        val offset = state.nextOffset ?: return
        if (!owns(identity) || state.refreshRequired || state.refreshing || state.appending) return
        if (automatic && !state.canAutoAppend) return
        // Only a user retry grants another bounded window; automatic collectors cannot reset it.
        val requests = if (!automatic && state.error != null) 0 else state.pageRequests
        if (requests >= maxPages) {
            state = state.copy(error = "已达到分页请求上限，请重试继续加载")
            cache()
            return
        }
        state = state.copy(appending = true, error = null, pageRequests = requests + 1)
        try {
            val page = fetch(FAVORITES_PAGE_SIZE, offset)
            currentCoroutineContext().ensureActive()
            if (!owns(identity)) return
            val ids = checkFavoritesProgress(page, offset, state.items.mapTo(HashSet()) { it.id }, state.pageIds)
            // Changed totals invalidate offsets. An explicit refresh owns the next reconstruction.
            if (state.total != null && page.total != null && state.total != page.total) {
                state = state.copy(refreshRequired = true, error = "收藏数量已变化，请重试核对")
                cache()
                return
            }
            state = state.copy(items = (state.items + page.items).distinctBy { it.id },
                total = page.total, nextOffset = page.nextOffset, appending = false,
                pageIds = state.pageIds + setOf(ids),
                error = if (state.pageRequests >= maxPages && page.nextOffset != null)
                    "已达到分页请求上限，请重试继续加载" else null)
            cache()
        } catch (cancelled: CancellationException) {
            if (owns(identity)) state = state.copy(error = "加载已取消，已保留现有收藏，请重试")
            throw cancelled
        } catch (failure: Exception) {
            if (owns(identity)) state = state.copy(error = if (failure is FavoritesNoProgress)
                "加载已停止：" + failure.message + "，已保留现有收藏，请重试"
                else "加载更多失败，已保留现有收藏，请重试")
        } finally {
            if (state.identity == identity) state = state.copy(appending = false)
        }
    }

    private companion object { val generations = AtomicLong() }
}
