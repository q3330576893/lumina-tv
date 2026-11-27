package com.lumina.tv.ui.screens

import com.lumina.tv.data.catchingRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

data class SeasonEpisodeLoadState<T>(
    val episodes: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
)

enum class SeasonDirectoryStatus { LOADING, SUCCESS, EMPTY, FAILURE }

data class SeasonDirectoryLoadState<T>(
    val seasons: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
) {
    val status: SeasonDirectoryStatus
        get() = when {
            loading -> SeasonDirectoryStatus.LOADING
            error != null -> SeasonDirectoryStatus.FAILURE
            seasons.isEmpty() -> SeasonDirectoryStatus.EMPTY
            else -> SeasonDirectoryStatus.SUCCESS
        }
}

fun <T> beginSeasonDirectoryLoad(): SeasonDirectoryLoadState<T> =
    SeasonDirectoryLoadState(loading = true)

fun <T> completeSeasonDirectoryLoad(result: Result<List<T>>): SeasonDirectoryLoadState<T> = result.fold(
    onSuccess = { seasons -> SeasonDirectoryLoadState(seasons = seasons) },
    onFailure = { failure -> SeasonDirectoryLoadState(error = failure) },
)

fun <T> beginSeasonEpisodeLoad(): SeasonEpisodeLoadState<T> =
    SeasonEpisodeLoadState(loading = true)

fun <T> cachedSeasonEpisodeState(episodes: List<T>?): SeasonEpisodeLoadState<T>? =
    episodes?.let { SeasonEpisodeLoadState(episodes = it) }

inline fun <T> completeSeasonEpisodeLoad(
    result: Result<List<T>>,
    cache: (List<T>) -> Unit,
): SeasonEpisodeLoadState<T> = result.fold(
    onSuccess = { episodes ->
        cache(episodes)
        SeasonEpisodeLoadState(episodes = episodes)
    },
    onFailure = { failure -> SeasonEpisodeLoadState(error = failure) },
)

/** Loaded pages stay selectable for this screen visit, including a successfully empty season. */
internal class EpisodePickerPages<T> {
    private val pages = mutableMapOf<String?, List<T>>()

    fun cached(seasonId: String?, readShared: () -> List<T>?): List<T>? =
        pages[seasonId] ?: readShared()?.also { pages[seasonId] = it }

    suspend fun fetch(seasonId: String?, request: suspend () -> List<T>, saveShared: (List<T>) -> Unit) =
        completeSeasonEpisodeLoad(catchingRequest {
            request().also { currentCoroutineContext().ensureActive() }
        }) { episodes ->
            pages[seasonId] = episodes
            saveShared(episodes)
        }
}

internal fun pickerNeighbor(index: Int, delta: Int, count: Int): Int? =
    (index + delta).takeIf { it in 0 until count }

data class LibraryAppendProgress(
    val nextIndex: Int,
    val hasMore: Boolean,
    val failed: Boolean,
)

fun shouldRetryLibraryAppend(failed: Boolean, requested: Int, handled: Int): Boolean =
    failed && requested > handled

const val LIBRARY_PAGE_SIZE = 50

fun libraryAppendProgress(nextIndex: Int, hasMore: Boolean, pageSize: Int?, expectedPageSize: Int = LIBRARY_PAGE_SIZE): LibraryAppendProgress =
    if (pageSize == null) LibraryAppendProgress(nextIndex, hasMore, failed = true)
    else LibraryAppendProgress(nextIndex + pageSize, pageSize == expectedPageSize, failed = false)

const val SEARCH_EMPTY_AUTO_PAGE_LIMIT = 3

fun shouldAutoLoadEmptySearchPage(
    visibleCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    automaticallyLoadedPages: Int,
): Boolean = visibleCount == 0 && hasMore && !loading && !loadingMore &&
    automaticallyLoadedPages < SEARCH_EMPTY_AUTO_PAGE_LIMIT

internal fun incompleteEmptySearch(
    visibleCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    automaticallyLoadedPages: Int,
): Boolean = visibleCount == 0 && hasMore && !loading &&
    automaticallyLoadedPages >= SEARCH_EMPTY_AUTO_PAGE_LIMIT

/** A query change invalidates callbacks immediately, before its effects are restarted. */
internal class SearchRequestGeneration {
    private var key: List<Any?>? = null
    private var generation = 0L

    fun update(key: List<Any?>): Long {
        if (this.key != key) {
            this.key = key
            generation++
        }
        return generation
    }

    fun invalidate() { key = null; generation++ }
    fun accepts(ticket: Long): Boolean = generation == ticket
    fun accept(ticket: Long, action: () -> Unit) { if (accepts(ticket)) action() }
    fun ensureCurrent(ticket: Long) {
        if (!accepts(ticket)) throw CancellationException("Superseded search")
    }
}

internal fun searchHistoryItemKey(entry: String): String = "history:$entry"

/** Prefer the next surviving item, then the previous one; never reuse a removed index. */
internal fun <T> focusNeighborAfterRemoval(previous: List<T>, removed: T, remaining: List<T>): T? {
    val index = previous.indexOf(removed)
    return previous.drop(index + 1).firstOrNull { it in remaining }
        ?: previous.take(index.coerceAtLeast(0)).lastOrNull { it in remaining }
        ?: remaining.firstOrNull()
}

internal fun accountFocusAfterRemoval(previous: List<String>, removed: String, remaining: List<String>): String =
    focusNeighborAfterRemoval(previous, removed, remaining)?.let { "account:switch:$it" }
        ?: "account:save-current"

fun shouldAppendSearchPage(
    reachedVisibleEnd: Boolean,
    loadedItemCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    keywordIsBlank: Boolean,
): Boolean = reachedVisibleEnd && loadedItemCount in 1 until 300 && hasMore &&
    !loading && !loadingMore && !keywordIsBlank

data class AccountFocusTargets(val up: String? = null, val down: String? = null)

fun accountRequesterKeys(savedServerUrls: List<String>): List<String> = buildList {
    add("account:current-edit")
    add("account:save-current")
    savedServerUrls.forEach { url ->
        add("account:switch:$url")
        add("account:edit:$url")
        add("account:delete:$url")
    }
    add("account:logout")
}

fun accountFocusTargets(savedServerUrls: List<String>): Map<String, AccountFocusTargets> = buildMap {
    put("account:current-edit", AccountFocusTargets(down = savedServerUrls.firstOrNull()?.let { "account:switch:$it" } ?: "account:save-current"))
    savedServerUrls.forEachIndexed { index, url ->
        val switch = "account:switch:$url"
        val edit = "account:edit:$url"
        val delete = "account:delete:$url"
        put(switch, AccountFocusTargets(up = savedServerUrls.getOrNull(index - 1)?.let { "account:delete:$it" }, down = edit))
        put(edit, AccountFocusTargets(up = switch, down = delete))
        put(delete, AccountFocusTargets(up = edit, down = savedServerUrls.getOrNull(index + 1)?.let { "account:switch:$it" } ?: "account:save-current"))
    }
    put("account:save-current", AccountFocusTargets(
        up = savedServerUrls.lastOrNull()?.let { "account:delete:$it" } ?: "account:current-edit",
        down = "account:logout",
    ))
    put("account:logout", AccountFocusTargets(up = "account:save-current"))
}
