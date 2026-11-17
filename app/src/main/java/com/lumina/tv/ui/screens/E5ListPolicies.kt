package com.lumina.tv.ui.screens

data class SeasonEpisodeLoadState<T>(
    val episodes: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
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

data class LibraryAppendProgress(
    val nextIndex: Int,
    val hasMore: Boolean,
    val failed: Boolean,
)

fun shouldRetryLibraryAppend(failed: Boolean, requested: Int, handled: Int): Boolean =
    failed && requested > handled

fun libraryAppendProgress(nextIndex: Int, hasMore: Boolean, pageSize: Int?): LibraryAppendProgress =
    if (pageSize == null) LibraryAppendProgress(nextIndex, hasMore, failed = true)
    else LibraryAppendProgress(nextIndex + pageSize, pageSize == 50, failed = false)

const val SEARCH_EMPTY_AUTO_PAGE_LIMIT = 3

fun shouldAutoLoadEmptySearchPage(
    visibleCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    automaticallyLoadedPages: Int,
): Boolean = visibleCount == 0 && hasMore && !loading && !loadingMore &&
    automaticallyLoadedPages < SEARCH_EMPTY_AUTO_PAGE_LIMIT

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
    put("account:current-edit", AccountFocusTargets())
    savedServerUrls.forEachIndexed { index, url ->
        val switch = "account:switch:$url"
        val edit = "account:edit:$url"
        val delete = "account:delete:$url"
        put(switch, AccountFocusTargets(up = savedServerUrls.getOrNull(index - 1)?.let { "account:delete:$it" }, down = edit))
        put(edit, AccountFocusTargets(up = switch, down = delete))
        put(delete, AccountFocusTargets(up = edit, down = "account:save-current"))
    }
    put("account:save-current", AccountFocusTargets(
        up = savedServerUrls.lastOrNull()?.let { "account:delete:$it" } ?: "account:current-edit",
        down = "account:logout",
    ))
    put("account:logout", AccountFocusTargets(up = "account:save-current"))
}
