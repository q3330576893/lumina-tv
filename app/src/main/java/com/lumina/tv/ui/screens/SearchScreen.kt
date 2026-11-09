package com.lumina.tv.ui.screens

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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    var latest by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var pageStart by remember { mutableIntStateOf(0) }
    var searchNow by remember { mutableIntStateOf(0) }
    var handledSearchNow by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(prefs?.searchHistory().orEmpty()) }
    var focusedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var unwatchedOnly by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    fun submitSearch(query: String = keyword) {
        keyword = query
        searchNow++
    }

    BackHandler {
        if (imeVisible) keyboard?.hide() else onBack()
    }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "搜索"
        runCatching { inputFocus.requestFocus() }
        latest = runCatching { client?.getLatest(null, 12) }.getOrNull().orEmpty()
    }

    LaunchedEffect(keyword, typeFilter, favoritesOnly, unwatchedOnly, searchNow) {
        val query = keyword.trim()
        if (query.isBlank()) {
            results = emptyList()
            pageStart = 0
            hasMore = false
            loading = false
            loadingMore = false
            searched = false
            searchError = null
            gridState.requestScrollToItem(0)
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        results = emptyList()
        pageStart = 0
        hasMore = false
        searched = true
        searchError = null
        gridState.requestScrollToItem(0)
        if (searchNow != handledSearchNow) handledSearchNow = searchNow else delay(400)
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
        try {
            val page = currentClient.getItems(
                types = types, sortBy = "SortName", sortOrder = "Ascending",
                limit = 40, startIndex = 0, searchTerm = query, filters = filters,
                fields = "PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
                requestTimeoutSeconds = 4,
            )
            results = page
            pageStart = page.size
            hasMore = page.size == 40 && pageStart < 300
            prefs?.rememberSearch(query)
            history = prefs?.searchHistory().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = searchFailureMessage(e)
            hasMore = false
        } finally {
            loading = false
        }
    }

    LaunchedEffect(gridState, hasMore, loading, keyword, typeFilter, favoritesOnly, unwatchedOnly) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            Triple(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount, loadingMore)
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems, isLoadingMore) ->
                if (lastVisible == null || lastVisible < totalItems - 8 || !hasMore || isLoadingMore || loading || keyword.isBlank()) return@collect
                val currentClient = client ?: return@collect
                loadingMore = true
                val filters = listOfNotNull(
                    "IsFavorite".takeIf { favoritesOnly },
                    "IsUnplayed".takeIf { unwatchedOnly },
                ).joinToString(",").ifBlank { null }
                val types = when (typeFilter) { 1 -> "Movie"; 2 -> "Series"; else -> null }
                try {
                    val page = currentClient.getItems(
                        types = types, sortBy = "SortName", sortOrder = "Ascending",
                        limit = 40, startIndex = pageStart, searchTerm = keyword.trim(), filters = filters,
                        fields = "PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
                        requestTimeoutSeconds = 4,
                    )
                    results = (results + page).distinctBy { it.id }
                    pageStart += page.size
                    hasMore = page.size == 40 && pageStart < 300
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    searchError = searchFailureMessage(e)
                    hasMore = false
                } finally {
                    loadingMore = false
                }
            }
    }

    val folded = remember(results) {
        results.groupBy { if (it.type.equals("Episode", true)) it.seriesId ?: it.id else it.id }
            .values.map { group -> group.firstOrNull { it.type.equals("Series", true) } ?: group.first() }
    }
    val visibleResults = remember(folded, keyword) {
        val query = keyword.trim()
        val needle = query.filterNot(Char::isWhitespace)
        fun matchRank(item: MediaItem): Int {
            val name = item.name.trim()
            if (name.equals(query, true)) return 0
            if (name.startsWith(query, true)) return 1
            if (name.contains(query, true)) return 2
            val pinyin = runCatching {
                val full = Pinyin.toPinyin(name, "").filterNot(Char::isWhitespace)
                val initials = Pinyin.toPinyin(name, " ").split(' ').mapNotNull { it.firstOrNull() }.joinToString("")
                when {
                    full.equals(needle, true) || initials.equals(needle, true) -> 0
                    full.startsWith(needle, true) || initials.startsWith(needle, true) -> 1
                    full.contains(needle, true) || initials.contains(needle, true) -> 2
                    else -> 3
                }
            }.getOrDefault(3)
            return pinyin
        }
        folded.filter { item -> matchRank(item) < 3 }
            .sortedWith(compareBy<MediaItem> { matchRank(it) }
            .thenByDescending { it.rating ?: it.communityRating ?: 0.0 }
            .thenByDescending { it.year ?: 0 })
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
                onValueChange = { keyword = it },
                placeholder = { Text("按 OK 调出键盘，输入片名或拼音首字母", color = TextSub.copy(alpha = 0.7f), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    if (keyword.isNotEmpty()) FocusSurface(Modifier.width(42.dp).height(42.dp), corner = 21.dp,
                        onClick = { keyword = "" }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text("清除", color = TextSub, fontSize = 11.sp)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent.copy(alpha = 0.62f), unfocusedBorderColor = GlassLine, cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f).widthIn(min = 0.dp).focusRequester(inputFocus),
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
                FilterChip(label, typeFilter == index) { typeFilter = index }
            }
            FilterChip("收藏", favoritesOnly) { favoritesOnly = !favoritesOnly }
            FilterChip("未看", unwatchedOnly) { unwatchedOnly = !unwatchedOnly }
        }

        Spacer(Modifier.height(10.dp))
        when {
            loading && results.isEmpty() -> LoadingView(text = "搜索中…")
            keyword.isNotBlank() && searched && searchError != null -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(searchError!!)
                    FocusSurface(Modifier.padding(top = 8.dp).height(42.dp).width(112.dp), corner = 12.dp,
                        onClick = { submitSearch() }) { focused ->
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
                    EmptyView("没有找到相关内容")
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visibleResults, key = { it.id }) { item ->
                            PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp,
                                requestInitialFocus = item.id == focusedItemId || (focusedItemId == null && item == visibleResults.firstOrNull()),
                                onFocused = { focusedItemId = item.id }) {
                                onOpenDetail(item.seriesId ?: item.id)
                            }
                        }
                        if (loadingMore) item { LoadingView(text = "加载更多…") }
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (history.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("最近搜索", color = TextSub, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    history.forEach { entry ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            FilterChip(entry.take(12), false) { submitSearch(entry) }
                                            FocusSurface(Modifier.width(30.dp).height(32.dp), corner = 15.dp,
                                                onClick = { prefs?.removeSearch(entry); history = prefs?.searchHistory().orEmpty() }) { focused ->
                                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color.Transparent), contentAlignment = Alignment.Center) {
                                                    Text("×", color = TextSub, fontSize = 16.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text("最近添加", color = TextMain, fontSize = 16.sp, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    items(latest, key = { "latest:${it.id}" }) { item ->
                        PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp,
                            requestInitialFocus = item == latest.firstOrNull(), onFocused = {}) {
                            onOpenDetail(item.seriesId ?: item.id)
                        }
                    }
                    if (latest.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        EmptyView("暂无最近添加内容")
                    }
                }
            }
        }
    }
}

private fun searchFailureMessage(error: Throwable): String {
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
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    FocusSurface(Modifier.height(32.dp).widthIn(min = 54.dp).padding(horizontal = 0.dp), corner = 16.dp, scaleTo = 1f, onClick = onClick) { focused ->
        Box(Modifier.background(
            if (active) Accent.copy(alpha = 0.18f) else if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
            RoundedCornerShape(16.dp))
            .then(if (active) Modifier.border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) Accent else TextMain, fontSize = 10.sp, maxLines = 1)
        }
    }
}
