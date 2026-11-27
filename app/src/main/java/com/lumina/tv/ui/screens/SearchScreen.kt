package com.lumina.tv.ui.screens

import com.lumina.tv.data.catchingRequest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.promeg.pinyinhelper.Pinyin
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.EmbyException
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.EmptyView
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.PosterFocusDir
import com.lumina.tv.ui.components.PosterFocusRole
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal const val SEARCH_REQUEST_TIMEOUT_SECONDS = 8

internal data class LatestItemsState(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

internal class LatestItemsLoader {
    private var requestSequence = 0L
    var state by mutableStateOf(LatestItemsState())
        private set

    suspend fun load(request: suspend () -> List<MediaItem>) {
        val sequence = ++requestSequence
        state = LatestItemsState()
        val result = catchingRequest { request() }
        currentCoroutineContext().ensureActive()
        // A late success or failure cannot replace a newer retry's state.
        if (sequence != requestSequence) return
        state = result.fold(
            onSuccess = { LatestItemsState(items = it, loading = false) },
            onFailure = { LatestItemsState(loading = false, error = "最近添加读取失败，请重试。") },
        )
    }

    fun cancel() { requestSequence++ }
}

@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val client = AppRuntime.client
    val prefs = AppRuntime.prefs
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val inputFocus = remember { FocusRequester() }
    var keyword by rememberSaveable { mutableStateOf(initialKeyword) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val latestLoader = remember(client) { LatestItemsLoader() }
    val latestState = latestLoader.state
    val latest = latestState.items
    var latestRetry by remember(client) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var appendError by remember { mutableStateOf<String?>(null) }
    var pageStart by remember { mutableIntStateOf(0) }
    var autoLoadedEmptyPages by remember { mutableIntStateOf(0) }
    var emptyPageContinuation by remember { mutableIntStateOf(0) }
    var searchNow by remember { mutableIntStateOf(0) }
    var handledSearchNow by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(prefs?.searchHistory().orEmpty().distinct()) }
    val historyRequesters = remember { mutableMapOf<String, FocusRequester>() }
    history.forEach { historyRequesters.getOrPut(it) { FocusRequester() } }
    var pendingHistoryFocus by remember { mutableStateOf<String?>(null) }
    var focusedHistoryEntry by remember { mutableStateOf<String?>(null) }
    var inputHasFocus by remember { mutableStateOf(false) }
    var focusedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedLatestId by remember { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var unwatchedOnly by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val latestGridState = rememberLazyGridState()
    var searchGridHasFocus by remember { mutableStateOf(false) }
    var latestGridHasFocus by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val searchGeneration = remember { SearchRequestGeneration() }
    val searchTicket = searchGeneration.update(listOf(client, keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow))

    fun invalidateSearchFocus() {
        searchGeneration.invalidate()
        focusedItemId = null
        focusedLatestId = null
    }

    fun submitSearch(query: String = keyword) {
        invalidateSearchFocus()
        keyword = query
        searchNow++
    }

    LaunchedEffect(pendingHistoryFocus, history, keyword) {
        val entry = pendingHistoryFocus ?: return@LaunchedEffect
        if (keyword.isBlank()) {
            val index = history.indexOf(entry)
            latestGridState.scrollToItem(if (index >= 0) index + 1 else 0)
            val requester = if (index >= 0) historyRequesters.getValue(entry) else inputFocus
            var restored = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                if (!searchGeneration.accepts(searchTicket)) break
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                restored = if (index >= 0) focusedHistoryEntry == entry else inputHasFocus
                if (restored) break
            }
            if (!restored && searchGeneration.accepts(searchTicket)) runCatching { inputFocus.requestFocus() }
        }
        pendingHistoryFocus = null
    }

    BackHandler {
        if (imeVisible) keyboard?.hide() else onBack()
    }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "搜索"
        runCatching { inputFocus.requestFocus() }
    }
    DisposableEffect(latestLoader) {
        onDispose { latestLoader.cancel() }
    }
    LaunchedEffect(latestLoader, latestRetry) {
        latestLoader.load {
            val currentClient = client ?: throw EmbyException("客户端未就绪")
            currentClient.getLatest(null, 12)
        }
    }

    LaunchedEffect(client, keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow) {
        if (!searchGeneration.accepts(searchTicket)) return@LaunchedEffect
        val query = keyword.trim()
        if (query.isBlank()) {
            results = emptyList()
            pageStart = 0
            autoLoadedEmptyPages = 0
            hasMore = false
            loading = false
            loadingMore = false
            searched = false
            searchError = null
            appendError = null
            gridState.requestScrollToItem(0)
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        results = emptyList()
        pageStart = 0
        autoLoadedEmptyPages = 0
        hasMore = false
        searched = true
        searchError = null
        appendError = null
        gridState.requestScrollToItem(0)
        if (searchNow != handledSearchNow) handledSearchNow = searchNow else delay(400)
        searchGeneration.ensureCurrent(searchTicket)
        val currentClient = client ?: run {
            searched = true
            loading = false
            searchError = "无法连接到媒体服务器：客户端未就绪"
            return@LaunchedEffect
        }
        val filters = listOfNotNull(
            "IsFavorite".takeIf { favoritesOnly },
            "IsUnplayed".takeIf { unwatchedOnly },
        ).joinToString(",").ifBlank { null }
        val types = when (typeFilter) {
            1 -> "Movie"
            2 -> "Series"
            else -> null
        }
        runSearchPageRequest(isCurrent = { searchGeneration.accepts(searchTicket) }, onFailure = { e ->
            searchError = searchFailureMessage(e)
            hasMore = false
        }, onFinished = { loading = false }) {
            val page = currentClient.getItems(
                types = types, sortBy = "SortName", sortOrder = "Ascending",
                limit = 40, startIndex = 0, searchTerm = query, filters = filters,
                fields = "PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
                requestTimeoutSeconds = SEARCH_REQUEST_TIMEOUT_SECONDS,
            )
            currentCoroutineContext().ensureActive()
            searchGeneration.ensureCurrent(searchTicket)
            results = page
            pageStart = page.size
            hasMore = page.size == 40 && pageStart < 300
            prefs?.rememberSearch(query)
            history = prefs?.searchHistory().orEmpty().distinct()
        }
    }

    LaunchedEffect(gridState, results.size, hasMore, loading, keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow, client) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.key == "search-load-more" } }
            .distinctUntilChanged()
            .collect { loadMoreVisible ->
                if (!searchGeneration.accepts(searchTicket)) return@collect
                if (!shouldAppendSearchPage(
                        reachedVisibleEnd = loadMoreVisible,
                        loadedItemCount = pageStart,
                        hasMore = hasMore,
                        loading = loading,
                        loadingMore = loadingMore,
                        keywordIsBlank = keyword.isBlank(),
                    )
                ) return@collect
                val currentClient = client ?: return@collect
                loadingMore = true
                val filters = listOfNotNull(
                    "IsFavorite".takeIf { favoritesOnly },
                    "IsUnplayed".takeIf { unwatchedOnly },
                ).joinToString(",").ifBlank { null }
                val types = when (typeFilter) { 1 -> "Movie"; 2 -> "Series"; else -> null }
                runSearchPageRequest(isCurrent = { searchGeneration.accepts(searchTicket) }, onFailure = { e ->
                    appendError = searchFailureMessage(e)
                    hasMore = false
                }, onFinished = { loadingMore = false }) {
                    val page = currentClient.getItems(
                        types = types, sortBy = "SortName", sortOrder = "Ascending",
                        limit = 40, startIndex = pageStart, searchTerm = keyword.trim(), filters = filters,
                        fields = "PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
                        requestTimeoutSeconds = SEARCH_REQUEST_TIMEOUT_SECONDS,
                    )
                    currentCoroutineContext().ensureActive()
                    searchGeneration.ensureCurrent(searchTicket)
                    results = (results + page).distinctBy { it.id }
                    appendError = null
                    pageStart += page.size
                    hasMore = page.size == 40 && pageStart < 300
                }
            }
    }

    val folded = remember(results) {
        results.groupBy { if (it.type.equals("Episode", true)) it.seriesId ?: it.id else it.id }
            .values.map { group -> group.firstOrNull { it.type.equals("Series", true) } ?: group.first() }
    }
    val visibleResults = remember(folded, keyword) {
        val query = keyword.trim()
        rankSearchItems(folded, query) { name, needle ->
            runCatching {
                val full = Pinyin.toPinyin(name, "").filterNot(Char::isWhitespace)
                val initials = Pinyin.toPinyin(name, " ").split(' ').mapNotNull { it.firstOrNull() }.joinToString("")
                when {
                    full.equals(needle, true) || initials.equals(needle, true) -> 0
                    full.startsWith(needle, true) || initials.startsWith(needle, true) -> 1
                    full.contains(needle, true) || initials.contains(needle, true) -> 2
                    else -> 3
                }
            }.getOrDefault(3)
        }
    }
    val errorPresentation = searchErrorPresentation(visibleResults.size, searchError, appendError)
    EmptySearchPageEffect(
        searchKey = listOf(keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow) + listOfNotNull(client),
        loadedItemCount = pageStart,
        visibleCount = visibleResults.size,
        hasMore = { hasMore },
        loading = { loading || loadingMore },
        automaticallyLoadedPages = { autoLoadedEmptyPages },
        continuationRequest = { emptyPageContinuation },
    ) {
        if (!searchGeneration.accepts(searchTicket)) return@EmptySearchPageEffect
        val currentClient = client ?: return@EmptySearchPageEffect
        val filters = listOfNotNull(
            "IsFavorite".takeIf { favoritesOnly },
            "IsUnplayed".takeIf { unwatchedOnly },
        ).joinToString(",").ifBlank { null }
        val types = when (typeFilter) { 1 -> "Movie"; 2 -> "Series"; else -> null }
        autoLoadedEmptyPages++
        loadingMore = true
        runSearchPageRequest(isCurrent = { searchGeneration.accepts(searchTicket) }, onFailure = { e ->
            appendError = searchFailureMessage(e)
            hasMore = false
        }, onFinished = { loadingMore = false }) {
            val page = currentClient.getItems(
                types = types, sortBy = "SortName", sortOrder = "Ascending",
                limit = 40, startIndex = pageStart, searchTerm = keyword.trim(), filters = filters,
                fields = "PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
                requestTimeoutSeconds = SEARCH_REQUEST_TIMEOUT_SECONDS,
            )
            currentCoroutineContext().ensureActive()
            searchGeneration.ensureCurrent(searchTicket)
            results = (results + page).distinctBy { it.id }
            appendError = null
            pageStart += page.size
            hasMore = page.size == 40 && pageStart < 300
        }
    }

    fun retryAppend() {
        appendError = null
        autoLoadedEmptyPages = 0
        emptyPageContinuation++
        hasMore = true
    }

    val filterDescription = buildList {
        add(when (typeFilter) { 1 -> "电影"; 2 -> "剧集"; else -> "全部类型" })
        if (favoritesOnly) add("收藏")
        if (unwatchedOnly) add("未看")
    }.joinToString(" · ")

    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { if (keyword != it) { invalidateSearchFocus(); keyword = it } },
                placeholder = { Text("按 OK 调出键盘，输入片名或拼音首字母", color = TextSub.copy(alpha = 0.7f), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    if (keyword.isNotEmpty()) FocusSurface(Modifier.width(42.dp).height(42.dp), corner = 21.dp,
                        onClick = { invalidateSearchFocus(); keyword = "" }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text("清除", color = TextSub, fontSize = 11.sp)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent.copy(alpha = 0.62f), unfocusedBorderColor = GlassLine, cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f).widthIn(min = 0.dp).focusRequester(inputFocus)
                    .onFocusChanged { inputHasFocus = it.isFocused; if (it.isFocused) focusedHistoryEntry = null }
                    .onPreviewKeyEvent { event ->
                        if (keyword.isBlank() && history.isNotEmpty() && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            pendingHistoryFocus = history.first()
                            true
                        } else false
                    },
            )
            Spacer(Modifier.width(12.dp))
            FocusSurface(Modifier.height(52.dp).width(96.dp), corner = 16.dp,
                onClick = { submitSearch() }) { focused ->
                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                    Text("搜索", color = TextMain, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            FocusSurface(Modifier.height(52.dp).width(96.dp), corner = 16.dp, onClick = onBack) { focused ->
                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                    Text("返回", color = TextMain, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "电影", "剧集").forEachIndexed { index, label ->
                FilterChip(label, typeFilter == index) {
                    if (typeFilter != index) { invalidateSearchFocus(); typeFilter = index }
                }
            }
            FilterChip("收藏", favoritesOnly) { invalidateSearchFocus(); favoritesOnly = !favoritesOnly }
            FilterChip("未看", unwatchedOnly) { invalidateSearchFocus(); unwatchedOnly = !unwatchedOnly }
        }

        Spacer(Modifier.height(10.dp))
        when {
            loading && results.isEmpty() -> LoadingView(text = "搜索中…")
            keyword.isNotBlank() && searched && errorPresentation.primary != null -> {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorPresentation.primary!!, color = TextSub, fontSize = 14.sp)
                    FocusSurface(Modifier.padding(top = 8.dp).height(42.dp).width(112.dp), corner = 12.dp,
                        onClick = { if (appendError != null) retryAppend() else submitSearch() }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                            Text("重试", color = TextMain, fontSize = 13.sp)
                        }
                    }
                }
            }
            keyword.isNotBlank() && searched -> {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("找到 ${visibleResults.size} 个结果", color = TextMain, fontSize = 14.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(filterDescription, color = TextSub, fontSize = 11.sp)
                }
                if (visibleResults.isEmpty()) {
                    when {
                        loadingMore || hasMore && autoLoadedEmptyPages < SEARCH_EMPTY_AUTO_PAGE_LIMIT -> LoadingView(text = "继续搜索中…")
                        incompleteEmptySearch(visibleResults.size, hasMore, loading || loadingMore, autoLoadedEmptyPages) ->
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("尚未搜完，可手动继续搜索", color = TextSub, fontSize = 14.sp)
                                FocusSurface(Modifier.padding(top = 12.dp).heightIn(min = 44.dp).widthIn(min = 136.dp),
                                    corner = 12.dp, onClick = { retryAppend() }) { focused ->
                                    Box(Modifier.background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
                                        .padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        Text("继续搜索", color = TextMain, fontSize = 13.sp)
                                    }
                                }
                            }
                        else -> EmptyView("没有找到相关内容")
                    }
                } else {
                    val focusColumns = remember(gridState.layoutInfo) {
                        val visible = gridState.layoutInfo.visibleItemsInfo
                        visible.firstOrNull()?.let { first ->
                            visible.count { it.index in first.index until (first.index + 50) && it.offset.y == first.offset.y }
                                .coerceAtLeast(1)
                        } ?: 1
                    }
                    val focusedIndex = if (searchGridHasFocus) visibleResults.indexOfFirst { it.id == focusedItemId } else -1
                    var previousFocusIndex by remember(visibleResults) { mutableIntStateOf(-1) }
                    var focusDir by remember(visibleResults) { mutableStateOf(PosterFocusDir.NONE) }
                    LaunchedEffect(focusedIndex) {
                        if (focusedIndex >= 0) {
                            if (previousFocusIndex >= 0) {
                                val delta = focusedIndex - previousFocusIndex
                                focusDir = when {
                                    kotlin.math.abs(delta) >= focusColumns -> if (delta > 0) PosterFocusDir.DOWN else PosterFocusDir.UP
                                    delta > 0 -> PosterFocusDir.RIGHT
                                    delta < 0 -> PosterFocusDir.LEFT
                                    else -> PosterFocusDir.NONE
                                }
                            } else focusDir = PosterFocusDir.NONE
                            previousFocusIndex = focusedIndex
                        } else {
                            previousFocusIndex = -1
                            focusDir = PosterFocusDir.NONE
                        }
                    }
                    fun roleOf(index: Int) = when {
                        focusedIndex < 0 -> PosterFocusRole.REST
                        index == focusedIndex -> PosterFocusRole.FOCUSED
                        kotlin.math.abs(index - focusedIndex) == 1 ||
                            kotlin.math.abs(index - focusedIndex) == focusColumns -> PosterFocusRole.NEIGHBOR
                        else -> PosterFocusRole.REST
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().onFocusChanged { searchGridHasFocus = it.hasFocus },
                    ) {
                        itemsIndexed(visibleResults, key = { _, item -> item.id }) { index, item ->
                            val requestFocus = item.id == focusedItemId ||
                                (focusedItemId == null && item == visibleResults.firstOrNull())
                            PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp,
                                requestInitialFocus = requestFocus,
                                focusRole = roleOf(index),
                                focusDir = focusDir,
                                snapAnimation = gridState.isScrollInProgress || requestFocus,
                                roleDriven = true,
                                onFocused = { searchGeneration.accept(searchTicket) { focusedItemId = item.id } }) {
                                searchGeneration.accept(searchTicket) { onOpenDetail(item.seriesId ?: item.id) }
                            }
                        }
                        if (hasMore || loadingMore || errorPresentation.append != null) item(key = "search-load-more") {
                            when {
                                loadingMore -> LoadingView(text = "Loading more…")
                                errorPresentation.append != null -> Column(
                                    Modifier.width(150.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(errorPresentation.append.orEmpty(), color = TextSub, fontSize = 11.sp)
                                    FocusSurface(Modifier.padding(top = 6.dp).height(38.dp).width(112.dp), corner = 12.dp,
                                        onClick = { retryAppend() }) { focused ->
                                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                                            Text("重试", color = TextMain, fontSize = 12.sp)
                                        }
                                    }
                                }
                                else -> Spacer(Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
            else -> {
                val latestFocusColumns = remember(latestGridState.layoutInfo) {
                    val visible = latestGridState.layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith("latest:") }
                    visible.firstOrNull()?.let { first ->
                        visible.count { it.index in first.index until (first.index + 50) && it.offset.y == first.offset.y }
                            .coerceAtLeast(1)
                    } ?: 1
                }
                val latestFocusedIndex = if (latestGridHasFocus) latest.indexOfFirst { it.id == focusedLatestId } else -1
                var previousLatestFocusIndex by remember(latest) { mutableIntStateOf(-1) }
                var latestFocusDir by remember(latest) { mutableStateOf(PosterFocusDir.NONE) }
                LaunchedEffect(latestFocusedIndex) {
                    if (latestFocusedIndex >= 0) {
                        if (previousLatestFocusIndex >= 0) {
                            val delta = latestFocusedIndex - previousLatestFocusIndex
                            latestFocusDir = when {
                                kotlin.math.abs(delta) >= latestFocusColumns -> if (delta > 0) PosterFocusDir.DOWN else PosterFocusDir.UP
                                delta > 0 -> PosterFocusDir.RIGHT
                                delta < 0 -> PosterFocusDir.LEFT
                                else -> PosterFocusDir.NONE
                            }
                        } else latestFocusDir = PosterFocusDir.NONE
                        previousLatestFocusIndex = latestFocusedIndex
                    } else {
                        previousLatestFocusIndex = -1
                        latestFocusDir = PosterFocusDir.NONE
                    }
                }
                fun latestRoleOf(index: Int) = when {
                    latestFocusedIndex < 0 -> PosterFocusRole.REST
                    index == latestFocusedIndex -> PosterFocusRole.FOCUSED
                    kotlin.math.abs(index - latestFocusedIndex) == 1 ||
                        kotlin.math.abs(index - latestFocusedIndex) == latestFocusColumns -> PosterFocusRole.NEIGHBOR
                    else -> PosterFocusRole.REST
                }
                LazyVerticalGrid(
                    state = latestGridState,
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().onFocusChanged { latestGridHasFocus = it.hasFocus },
                ) {
                    if (history.isNotEmpty()) {
                        item(key = "search-history-heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text("最近搜索", color = TextSub, fontSize = 12.sp)
                        }
                        items(history, key = { searchHistoryItemKey(it) }) { entry ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(entry.take(12), false,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                                        .focusRequester(historyRequesters.getValue(entry))
                                        .semantics { contentDescription = "搜索历史：$entry" },
                                    onFocused = { searchGeneration.accept(searchTicket) { focusedLatestId = null; focusedHistoryEntry = entry } },
                                ) { searchGeneration.accept(searchTicket) { submitSearch(entry) } }
                                FocusSurface(Modifier.width(40.dp).heightIn(min = 40.dp)
                                    .semantics { contentDescription = "删除搜索历史：$entry" }, corner = 15.dp,
                                    onFocused = { searchGeneration.accept(searchTicket) { focusedLatestId = null; focusedHistoryEntry = null } },
                                    onClick = {
                                        prefs?.removeSearch(entry)
                                        val updated = prefs?.searchHistory().orEmpty().distinct()
                                        pendingHistoryFocus = focusNeighborAfterRemoval(history, entry, updated) ?: ""
                                        history = updated
                                    }) { focused ->
                                    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                                        .background(if (focused) Color(0x33FFFFFF) else Color.Transparent), contentAlignment = Alignment.Center) {
                                        Text("×", color = TextSub, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "search-latest-heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text("最近添加", color = TextMain, fontSize = 16.sp, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    itemsIndexed(latest, key = { _, it -> "latest:${it.id}" }) { index, item ->
                        PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp,
                            requestInitialFocus = false,
                            focusRole = latestRoleOf(index),
                            focusDir = latestFocusDir,
                            snapAnimation = latestGridState.isScrollInProgress,
                            roleDriven = true,
                            onFocused = { searchGeneration.accept(searchTicket) { focusedLatestId = item.id; focusedHistoryEntry = null } }) {
                            searchGeneration.accept(searchTicket) { onOpenDetail(item.seriesId ?: item.id) }
                        }
                    }
                    if (latest.isEmpty()) item(key = "search-latest-empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        when {
                            latestState.loading -> Text("正在读取最近添加…", color = TextSub, fontSize = 13.sp)
                            latestState.error != null -> Column {
                                Text(latestState.error, color = TextSub, fontSize = 13.sp)
                                FocusSurface(
                                    Modifier.padding(top = 8.dp).height(42.dp).width(112.dp), corner = 12.dp,
                                    onClick = { latestRetry++ },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("重试", color = TextMain, fontSize = 13.sp) }
                                }
                            }
                            else -> EmptyView("暂无最近添加内容")
                        }
                    }
                }
            }
        }
    }
}

/** Page progress restarts observation even when every appended page is filtered out. */
@Composable
internal fun EmptySearchPageEffect(
    searchKey: List<Any>,
    loadedItemCount: Int,
    visibleCount: Int,
    hasMore: () -> Boolean,
    loading: () -> Boolean,
    automaticallyLoadedPages: () -> Int,
    continuationRequest: () -> Int = { 0 },
    loadPage: suspend () -> Unit,
) {
    val currentLoadPage by rememberUpdatedState(loadPage)
    val continuation = continuationRequest()
    key(continuation) {
        LaunchedEffect(searchKey, loadedItemCount, visibleCount) {
            var requestedPage = false
            snapshotFlow { Triple(visibleCount, hasMore(), loading()) }
                .distinctUntilChanged()
                .collect { (visibleCount, more, isLoading) ->
                    val autoLoadedEmptyPages = automaticallyLoadedPages()
                    if (continuationRequest() != continuation) return@collect
                    if (requestedPage) return@collect
                    if (!shouldAutoLoadEmptySearchPage(visibleCount, more, isLoading, false, autoLoadedEmptyPages)) return@collect
                    requestedPage = true
                    currentLoadPage()
                }
        }
    }
}

internal suspend inline fun runSearchPageRequest(
    isCurrent: () -> Boolean = { true },
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
    request: () -> Unit,
) {
    try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        if (isCurrent()) onFailure(failure)
    } finally {
        if (isCurrent()) onFinished()
    }
}

internal fun searchFailureMessage(error: Throwable): String {
    val message = error.message.orEmpty().trim()
    return when (error) {
        is SocketTimeoutException -> "搜索超时，请检查服务器响应后重试。"
        is UnknownHostException -> "无法连接服务器：找不到服务器地址，请检查地址和网络。"
        is ConnectException -> "无法连接服务器：连接被拒绝或网络不可达。"
        is IOException -> if (message.contains("timeout", true) || message.contains("timed out", true)) {
            "搜索超时，请检查服务器响应后重试。"
        } else {
            "网络连接失败：${message.ifBlank { "请检查设备网络和服务器状态" }}"
        }
        is EmbyException -> "服务器返回错误：$message"
        else -> "搜索失败：${message.ifBlank { error.javaClass.simpleName }}"
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusSurface(modifier.heightIn(min = 32.dp).widthIn(min = 54.dp).padding(horizontal = 0.dp), corner = 16.dp, scaleTo = 1f, onFocused = onFocused, onClick = onClick) { focused ->
        Box(Modifier.background(
            if (active) Accent.copy(alpha = 0.18f) else if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
            RoundedCornerShape(16.dp))
            .then(if (active) Modifier.border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) Accent else TextMain, fontSize = 10.sp, maxLines = 1)
        }
    }
}
