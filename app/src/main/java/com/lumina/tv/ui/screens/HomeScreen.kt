@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.LibraryView
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.SectionTitle
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.theme.Space
import com.lumina.tv.ui.theme.TypeScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private enum class HomeTab(val label: String) {
    Recommend("推荐"),
    Media("媒体库"),
    Movies("电影"),
    Series("剧集"),
    Favorites("收藏"),
}

private fun collectionTypeToItemTypes(type: String?): String = when (type?.lowercase()) {
    "movies" -> "Movie"
    "tvshows" -> "Series"
    "music" -> "Audio,MusicAlbum"
    "boxsets" -> "BoxSet"
    else -> "Movie,Series,Video"
}

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onLogout: () -> Unit,
) {
    val client = AppRuntime.client
    val hazeState = remember { HazeState() }

    var loading by remember { mutableStateOf(LibraryCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resume by remember { mutableStateOf(LibraryCache.home?.resume ?: emptyList()) }
    var latest by remember { mutableStateOf(LibraryCache.home?.latest ?: emptyList()) }
    var movies by remember { mutableStateOf(LibraryCache.home?.movies ?: emptyList()) }
    var series by remember { mutableStateOf(LibraryCache.home?.series ?: emptyList()) }
    var favorites by remember { mutableStateOf(LibraryCache.home?.favorites ?: emptyList()) }
    var views by remember { mutableStateOf(LibraryCache.home?.views ?: emptyList()) }

    var tab by rememberSaveable { mutableStateOf(HomeTab.Recommend) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLibrary = views.firstOrNull { it.id == selectedLibraryId }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var librarySort by rememberSaveable { mutableStateOf(AppRuntime.prefs?.librarySort ?: "date") }
    var librarySortOrder by rememberSaveable { mutableStateOf(AppRuntime.prefs?.librarySortOrder ?: "Descending") }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var restoreSortFocus by remember { mutableStateOf(false) }
    val sortItemRequesters = remember { SORT_LABELS.keys.associateWith { FocusRequester() } }
    var libraryServerMenuOpen by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var gridItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var libraryTotalCount by remember { mutableStateOf<Int?>(null) }
    var gridLoading by remember { mutableStateOf(false) }
    var gridAppending by remember { mutableStateOf(false) }
    var gridNextIndex by remember { mutableStateOf(0) }
    var gridHasMore by remember { mutableStateOf(true) }
    val recListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    var savedGridPosition by rememberSaveable { mutableStateOf("") }
    val gridStateKey = when {
        tab == HomeTab.Media -> "library:${selectedLibraryId ?: "list"}"
        else -> "tab:${tab.name}"
    }
    val gridState = remember(gridStateKey) {
        gridStates.getOrPut(gridStateKey) {
            val parts = savedGridPosition.split('|')
            val index = if (parts.getOrNull(0) == gridStateKey) parts.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            val offset = if (parts.getOrNull(0) == gridStateKey) parts.getOrNull(2)?.toIntOrNull() ?: 0 else 0
            LazyGridState(index, offset)
        }
    }
    val scope = rememberCoroutineScope()
    fun closeSortMenu() {
        sortMenuOpen = false
        restoreSortFocus = true
        scope.launch { delay(180); restoreSortFocus = false }
    }
    LaunchedEffect(sortMenuOpen, librarySort) {
        if (sortMenuOpen) {
            delay(80)
            runCatching { sortItemRequesters[librarySort]?.requestFocus() }
        }
    }
    LaunchedEffect(gridStateKey, gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> savedGridPosition = "$gridStateKey|$index|$offset" }
    }
    // 各媒体库视图的横向内容（id → items）
    var viewContents by remember { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    var initialFocusPending by rememberSaveable { mutableStateOf(true) }
    var focusedMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedMediaSection by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var heroIndex by rememberSaveable { mutableStateOf(0) }

    // A genre is an in-page route: Android/TV Back returns to the genre list, then home.
    BackHandler(enabled = tab != HomeTab.Recommend) {
        if (tab == HomeTab.Media && selectedLibrary != null) {
            selectedLibraryId = null
            gridItems = emptyList()
            gridLoading = false
        } else {
            tab = HomeTab.Recommend
            selectedLibraryId = null
            gridItems = emptyList()
        }
    }

    LaunchedEffect(reloadToken) {
        AppRuntime.screenLabel = "首页"
        val c = client
        if (c == null) {
            error = "尚未登录"
            loading = false
            return@LaunchedEffect
        }
        if (LibraryCache.homeFresh()) return@LaunchedEffect
        val cached = LibraryCache.home
        loading = cached == null
        error = null
        try {
            coroutineScope {
                val rA = async { runCatching { c.getResume(20) }.getOrDefault(emptyList()) }
                val mA = async {
                    runCatching {
                        c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 24)
                    }.getOrDefault(emptyList())
                }
                val sA = async {
                    runCatching {
                        c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 24)
                    }.getOrDefault(emptyList())
                }
                val fA = async { runCatching { c.getFavorites(24) }.getOrDefault(emptyList()) }
                val vA = async { runCatching { c.getViews() }.getOrDefault(emptyList()) }

                val r = rA.await()
                val m = mA.await()
                val s = sA.await()
                val f = fA.await()
                val v = vA.await()
                val l = runCatching { c.getLatest(null, 24) }.getOrDefault(emptyList())
                    .distinctBy { it.id }
                    .filter { it.type != "Movie" && it.type != "Series" }

                resume = r
                latest = l
                movies = m
                series = s
                favorites = f
                views = v
                LibraryCache.putHome(
                    LibraryCache.HomeData(
                        resume = r, latest = l, movies = m, series = s,
                        favorites = f, genres = emptyList(), views = v, at = System.currentTimeMillis(),
                    )
                )
            }
        } catch (e: Exception) {
            if (cached == null) error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(loading, initialFocusPending, resume, favorites, latest, movies, series) {
        if (!loading && initialFocusPending && (resume.isNotEmpty() || favorites.isNotEmpty() || latest.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty())) {
            delay(240)
            initialFocusPending = false
        }
    }

    // 切换标签：Movies/Series 直接复用首页已加载的数据，不再额外请求
    LaunchedEffect(tab, selectedLibrary, librarySort, librarySortOrder) {
        val requestTab = tab
        val requestLibrary = selectedLibrary
        val requestSort = librarySort
        val requestOrder = librarySortOrder
        gridLoading = false
        libraryError = null
        if (requestTab == HomeTab.Media && requestLibrary != null) {
            gridNextIndex = 0
            gridHasMore = true
        }
        if (requestTab == HomeTab.Recommend || (requestTab == HomeTab.Media && requestLibrary == null)) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        if (requestTab == HomeTab.Movies && movies.isNotEmpty()) {
            gridItems = movies
            return@LaunchedEffect
        }
        if (requestTab == HomeTab.Series && series.isNotEmpty()) {
            gridItems = series
            return@LaunchedEffect
        }
        if (requestTab == HomeTab.Favorites && favorites.isNotEmpty()) {
            gridItems = favorites
            return@LaunchedEffect
        }
        val key = when (requestTab) {
            HomeTab.Movies -> "movies"
            HomeTab.Series -> "series"
            HomeTab.Favorites -> "favorites"
            HomeTab.Media -> "library:${requestLibrary?.id.orEmpty()}:$requestSort:$requestOrder"
            else -> return@LaunchedEffect
        }
        if (requestTab != HomeTab.Media) LibraryCache.browseOf(key)?.let {
            gridItems = it
            return@LaunchedEffect
        }
        gridLoading = requestTab == HomeTab.Media || gridItems.isEmpty()
        try {
            val result = runCatching {
                when (requestTab) {
                    HomeTab.Movies -> c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Series -> c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Favorites -> c.getFavorites(120)
                    HomeTab.Media -> requestLibrary?.let {
                        val (sb, so) = when (requestSort) {
                            "name" -> "SortName" to librarySortOrder
                            "rating" -> "CommunityRating" to librarySortOrder
                            "year" -> "ProductionYear" to librarySortOrder
                            "plays" -> "PlayCount" to librarySortOrder
                            else -> "DateCreated" to librarySortOrder
                        }
                        c.getItems(parentId = it.id, types = collectionTypeToItemTypes(it.type),
                            sortBy = sb, sortOrder = so, limit = 50, startIndex = 0)
                    }.orEmpty()
                    else -> emptyList()
                }
            }
            currentCoroutineContext().ensureActive()
            if (tab != requestTab || selectedLibrary != requestLibrary || librarySort != requestSort || librarySortOrder != requestOrder) return@LaunchedEffect
            result.onSuccess { list ->
                if (requestTab == HomeTab.Media) {
                    gridItems = list
                    libraryTotalCount = AppRuntime.client?.lastTotalRecordCount
                    gridNextIndex = list.size
                    gridHasMore = list.size == 50
                } else {
                    LibraryCache.putBrowse(key, list)
                    gridItems = list
                }
            }.onFailure { failure ->
                libraryError = failure.message ?: "媒体库读取失败"
                gridItems = emptyList()
            }
        } finally {
            if (tab == requestTab && selectedLibrary == requestLibrary && librarySort == requestSort && librarySortOrder == requestOrder) gridLoading = false
        }
    }

    LaunchedEffect(selectedLibrary, librarySort, librarySortOrder) {
        if (selectedLibrary == null) return@LaunchedEffect
        val activeSort = librarySort
        val activeOrder = librarySortOrder
        snapshotFlow {
            val info = gridState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            if (lastVisible >= gridItems.lastIndex - 8 && gridHasMore && !gridLoading && !gridAppending) {
                val c = client ?: return@collect
                val library = selectedLibrary ?: return@collect
                val (sortBy, _) = when (activeSort) {
                    "name" -> "SortName" to activeOrder
                    "rating" -> "CommunityRating" to activeOrder
                    "year" -> "ProductionYear" to activeOrder
                    "plays" -> "PlayCount" to activeOrder
                    else -> "DateCreated" to activeOrder
                }
                gridAppending = true
                try {
                    val page = c.getItems(parentId = library.id, types = collectionTypeToItemTypes(library.type),
                        sortBy = sortBy, sortOrder = activeOrder,
                        limit = 50, startIndex = gridNextIndex)
                    if (selectedLibrary == library && librarySort == activeSort && librarySortOrder == activeOrder) {
                        gridItems = (gridItems + page).distinctBy { it.id }
                        gridNextIndex += page.size
                        gridHasMore = page.size == 50
                    }
                } catch (_: Exception) {
                    gridHasMore = false
                } finally {
                    gridAppending = false
                }
            }
        }
    }

    // 媒体库视图内容：启动后即在后台预热拉取各视图最近内容，无需等切换Tab
    LaunchedEffect(views, reloadToken) {
        val c = client ?: return@LaunchedEffect
        if (views.isEmpty()) return@LaunchedEffect
        val enabled = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
        val missing = enabled.filter { v -> viewContents[v.id] == null }
        if (missing.isEmpty()) return@LaunchedEffect
        val result = coroutineScope {
            missing.map { v ->
                async {
                    v.id to runCatching {
                        c.getItems(parentId = v.id, types = collectionTypeToItemTypes(v.type),
                            sortBy = "DateCreated", sortOrder = "Descending", limit = 30)
                    }.getOrDefault(emptyList())
                }
            }.map { it.await() }.toMap()
        }
        viewContents = viewContents + result
    }

    val heroItems = (latest + movies + series).distinctBy { it.id }.take(6)
    LaunchedEffect(heroItems) {
        if (heroItems.size < 2) return@LaunchedEffect
        while (true) {
            delay(7000)
            heroIndex = (heroIndex + 1) % heroItems.size
        }
    }
    val hero = heroItems.getOrNull(heroIndex) ?: resume.firstOrNull()

    val navFocus = remember { FocusRequester() }

    val heroPlayFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
            navFocus.requestFocus()
            true
        } else false
    }) {
        when {
            loading -> LoadingView(text = "正在读取影库…")
                error != null -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("加载失败：$error", color = TextSub, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("服务器：${AppRuntime.session?.baseUrl}", color = TextSub.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                tab == HomeTab.Recommend -> LazyColumn(state = recListState, modifier = Modifier.fillMaxSize()) {
                    item { Hero(heroItems.ifEmpty { listOfNotNull(hero) }, heroIndex, onPlay, onOpenDetail, heroPlayFocus, hazeState) }
                    if (resume.isNotEmpty()) item { RowSection("继续观看", resume, requestInitialFocus = initialFocusPending && focusedMediaId == null, focusItemId = focusedMediaId, sectionKey = "resume", focusedSection = focusedMediaSection, upFocus = if (hero != null) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "resume" }) { onPlay(it) } }
                    if (favorites.isNotEmpty()) item { RowSection("我的收藏", favorites, requestInitialFocus = initialFocusPending && focusedMediaId == null && resume.isEmpty(), focusItemId = focusedMediaId, sectionKey = "favorites", focusedSection = focusedMediaSection, upFocus = if (hero != null && resume.isEmpty()) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "favorites" }) { onOpenDetail(it.id) } }
                    if (latest.isNotEmpty()) item { RowSection("最新添加", latest, requestInitialFocus = initialFocusPending && focusedMediaId == null && resume.isEmpty() && favorites.isEmpty(), focusItemId = focusedMediaId, sectionKey = "latest", focusedSection = focusedMediaSection, upFocus = if (hero != null && resume.isEmpty() && favorites.isEmpty()) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "latest" }) { onOpenDetail(it.id) } }
                    if (movies.isNotEmpty()) item { RowSection("电影", movies, requestInitialFocus = initialFocusPending && focusedMediaId == null && resume.isEmpty() && favorites.isEmpty() && latest.isEmpty(), focusItemId = focusedMediaId, sectionKey = "movies", focusedSection = focusedMediaSection, upFocus = if (hero != null && resume.isEmpty() && favorites.isEmpty() && latest.isEmpty()) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "movies" }) { onOpenDetail(it.id) } }
                    if (series.isNotEmpty()) item { RowSection("剧集", series, requestInitialFocus = initialFocusPending && focusedMediaId == null && resume.isEmpty() && favorites.isEmpty() && latest.isEmpty() && movies.isEmpty(), focusItemId = focusedMediaId, sectionKey = "series", focusedSection = focusedMediaSection, upFocus = if (hero != null && resume.isEmpty() && favorites.isEmpty() && latest.isEmpty() && movies.isEmpty()) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "series" }) { onOpenDetail(it.id) } }
                    // 媒体库全分类直接融入首页推荐流，支持无限往下滑
                    val enabledViews = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
                    enabledViews.forEach { v ->
                        val items = viewContents[v.id].orEmpty()
                        if (items.isNotEmpty()) {
                            item(key = "home-view-${v.id}") {
                                RowSection(v.name, items, focusItemId = focusedMediaId, sectionKey = "view-" + v.id, focusedSection = focusedMediaSection, onItemFocused = { focusedMediaId = it; focusedMediaSection = "view-" + v.id }) { onOpenDetail(it.id) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(140.dp)) }
                }
                tab == HomeTab.Media && selectedLibrary == null -> MediaLibrary(
                    state = libraryListState,
                    views = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) },
                    contents = viewContents,
                    focusedItemId = focusedMediaId,
                    focusedLibraryId = focusedLibraryId,
                    onLibraryFocused = { focusedLibraryId = it },
                    onItemFocused = { focusedMediaId = it },
                    onOpenDetail = onOpenDetail,
                    onOpenLibrary = { selectedLibraryId = it.id; focusedLibraryId = it.id; focusedMediaId = null },
                )
                tab == HomeTab.Media && selectedLibrary != null -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(modifier = Modifier.height(40.dp).width(44.dp), corner = 20.dp, onClick = { selectedLibraryId = null; gridItems = emptyList() }) { focused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("‹", color = if (focused) Accent else TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Box {
                            FocusSurface(modifier = Modifier.height(40.dp).widthIn(min = 112.dp, max = 180.dp), corner = 20.dp,
                                onClick = { libraryServerMenuOpen = !libraryServerMenuOpen }) { focused ->
                                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(AppRuntime.prefs?.serverName ?: "Emby", color = if (focused) Accent else TextMain,
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Text(" ▾", color = if (focused) Accent else TextSub, fontSize = 11.sp)
                                }
                            }
                            if (libraryServerMenuOpen) Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(0, with(LocalDensity.current) { 46.dp.roundToPx() }),
                                onDismissRequest = { libraryServerMenuOpen = false },
                                properties = PopupProperties(focusable = true),
                            ) {
                                Column(Modifier.width(240.dp).clip(RoundedCornerShape(18.dp))
                                    .background(Brush.verticalGradient(listOf(Color(0xF51C2638), Color(0xEA121A28))))
                                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(18.dp)).padding(12.dp)) {
                                    Text("当前服务器", color = TextSub, fontSize = 10.sp)
                                    Text(AppRuntime.session?.serverName ?: "未连接", color = TextMain, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
                                    Text("切换服务器 / 账号", color = TextMain, fontSize = 12.sp,
                                        modifier = Modifier.fillMaxWidth().clickable { libraryServerMenuOpen = false; onSwitchServer() }.padding(vertical = 8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                selectedLibrary?.name.orEmpty(),
                                color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(libraryTotalCount?.let { "共 $it 部" } ?: "已加载 ${gridItems.size} 部", color = TextSub, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Box {
                            FocusSurface(
                                modifier = Modifier.height(40.dp).width(112.dp),
                                corner = 20.dp, scaleTo = 1.03f,
                                requestFocus = restoreSortFocus,
                                onClick = { sortMenuOpen = true },
                            ) { focused ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("排序", color = if (focused) Accent else TextSub, fontSize = 9.sp, lineHeight = 11.sp)
                                        Text(SORT_LABELS[librarySort] ?: "默认", color = if (focused) Accent else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { closeSortMenu() },
                            ) {
                                    Column(
                                        modifier = Modifier
                                            .width(180.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(Brush.verticalGradient(listOf(Color(0xF51C2638), Color(0xEA121A28))))
                                            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(22.dp))
                                            .padding(vertical = 8.dp),
                                    ) {
                                        SORT_LABELS.forEach { (key, label) ->
                                            val selected = librarySort == key
                                            var itemFocused by remember(key) { mutableStateOf(false) }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp)
                                                    .focusRequester(sortItemRequesters.getValue(key))
                                                    .onFocusChanged { itemFocused = it.isFocused }
                                                    .focusable()
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        librarySort = key
                                                        AppRuntime.prefs?.librarySort = key
                                                        closeSortMenu()
                                                    }
                                                    .background(if (itemFocused) Color(0x26FFFFFF) else if (selected) Accent.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(10.dp))
                                                    .border(if (selected) 1.5.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 18.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(if (selected) "✓" else "", color = Accent, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                                                Text(label, color = if (itemFocused || selected) TextMain else TextSub, fontSize = 14.sp)
                                            }
                                        }
                                        var orderFocused by remember { mutableStateOf(false) }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .onFocusChanged { orderFocused = it.isFocused }
                                                .focusable()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) {
                                                    val nextOrder = if (librarySortOrder == "Ascending") "Descending" else "Ascending"
                                                    librarySortOrder = nextOrder
                                                    AppRuntime.prefs?.librarySortOrder = nextOrder
                                                    closeSortMenu()
                                                }
                                                .background(if (orderFocused) Color(0x26FFFFFF) else Color.Transparent, RoundedCornerShape(10.dp))
                                                .padding(horizontal = 18.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Spacer(Modifier.width(22.dp))
                                            Text(
                                                if (librarySortOrder == "Ascending") "升序 ↑" else "降序 ↓",
                                                color = TextMain,
                                                fontSize = 14.sp,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                    if (gridLoading && gridItems.isEmpty()) LoadingView(text = "正在读取 ${selectedLibrary?.name.orEmpty()}…")
                    else if (libraryError != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("读取失败：${libraryError}", color = TextSub, fontSize = 13.sp)
                    }
                    else if (gridItems.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此媒体库暂无内容", color = TextSub, fontSize = 13.sp)
                    }
                    else LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 80.dp, bottom = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(gridItems, key = { _, item -> item.id }) { index, media ->
                            PosterCard(item = media, imageUrl = client?.imageUrl(media), requestInitialFocus = focusedMediaId == media.id || (initialFocusPending && focusedMediaId == null && index == 0), onFocused = { focusedMediaId = media.id }, onClick = { onOpenDetail(media.id) })
                        }
                        if (gridAppending) item(span = { GridItemSpan(maxLineSpan) }) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), color = Accent, strokeWidth = 2.dp)
                        }
                    }
                }
                gridLoading -> LoadingView(text = "正在加载…")
                gridItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还没有内容", color = TextSub, fontSize = 13.sp)
                }
                else -> LazyVerticalGrid(
                    state = gridState,
                            columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 80.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(gridItems, key = { _, item -> item.id }) { index, it ->
                        PosterCard(
                            item = it,
                            imageUrl = client?.imageUrl(it),
                            requestInitialFocus = focusedMediaId == it.id || (initialFocusPending && focusedMediaId == null && it == gridItems.firstOrNull()),
                            onFocused = { focusedMediaId = it.id },
                            onClick = { onOpenDetail(it.id) },
                        )
                    }
                }
            }

        NavBar(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2f)
                .padding(top = 16.dp, end = 24.dp),
                tab = tab,
                onSelect = { t, _ -> tab = t; selectedLibraryId = null },
                onSearch = onSearch,
                onSettings = onSettings,
                onSwitchServer = onSwitchServer,
                onLogout = onLogout,
                onServerSwitched = {
                    LibraryCache.clear()
                    loading = true
                    error = null
                    resume = emptyList()
                    latest = emptyList()
                    movies = emptyList()
                    series = emptyList()
                    favorites = emptyList()
                    views = emptyList()
                    viewContents = emptyMap()
                    selectedLibraryId = null
                    gridItems = emptyList()
                    focusedMediaId = null
                    reloadToken++
                },
                navFocus = navFocus,
                hazeState = hazeState,
            )
    }
}

@Composable
private fun NavBar(
    modifier: Modifier = Modifier,
    tab: HomeTab,
    onSelect: (HomeTab, String?) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onLogout: () -> Unit,
    onServerSwitched: () -> Unit,
    navFocus: FocusRequester,
    hazeState: HazeState,
) {
    val context = LocalContext.current
    var serverMenuOpen by remember { mutableStateOf(false) }
    val glassShape = RoundedCornerShape(26.dp)
    val menuShape = RoundedCornerShape(24.dp)
    Box(modifier.wrapContentSize()) {
        Row(
            Modifier
                .height(48.dp)
                .clip(glassShape)
                .then(
                    if (ThemeRuntime.trueBlurEnabled) Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color(0xD9151B2A),
                            tint = HazeTint(Color(0x38FFFFFF)),
                            blurRadius = ThemeRuntime.blurRadiusDp,
                            noiseFactor = 0f,
                        ),
                    ) else Modifier.background(
                        Brush.horizontalGradient(listOf(Color(0xCC1A2233), Color(0xE6151B2A)))
                    )
                )
                .border(1.dp, Color(0x44FFFFFF), glassShape)
                .padding(horizontal = 8.dp)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FocusSurface(
                    modifier = Modifier.height(40.dp).widthIn(min = 96.dp, max = 150.dp),
                    corner = 20.dp,
                    onClick = { serverMenuOpen = true },
                ) { focused ->
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            AppRuntime.prefs?.serverName?.takeIf { it.isNotBlank() }
                                ?: AppRuntime.session?.serverName ?: "Lumina",
                            modifier = Modifier.weight(1f),
                            color = if (focused) Accent else Color(0xFFD6DEEB),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("▾", color = if (focused) Accent else Color(0xFFD6DEEB), fontSize = 12.sp)
                    }
                }
                DropdownMenu(
                    expanded = serverMenuOpen,
                    onDismissRequest = { serverMenuOpen = false },
                    modifier = Modifier.width(260.dp),
                ) {
                    Column(
                        Modifier
                            .clip(menuShape)
                            .background(Brush.verticalGradient(listOf(Color(0xF51C2638), Color(0xEA121A28))))
                            .border(1.dp, Color(0x40FFFFFF), menuShape)
                            .padding(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0x2E6EA8FF)), contentAlignment = Alignment.Center) {
                                Text("L", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("当前服务器", color = TextSub, fontSize = 9.sp)
                                Text(
                                    AppRuntime.session?.userName ?: "未登录",
                                    color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val savedServers = AppRuntime.prefs?.savedServerList().orEmpty()
                        Column(
                            if (savedServers.size > 5) {
                                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                            } else Modifier,
                        ) {
                            savedServers.forEach { server ->
                                val current = server.url.trimEnd('/').equals(
                                    AppRuntime.session?.baseUrl?.trimEnd('/'),
                                    ignoreCase = true,
                                )
                                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                                    FocusSurface(
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        corner = 12.dp,
                                        scaleTo = 1f,
                                        onClick = {
                                            if (AppRuntime.switchToServer(server.url)) {
                                                serverMenuOpen = false
                                                onServerSwitched()
                                            } else {
                                                AppRuntime.prefs?.let { prefs ->
                                                    prefs.baseUrl = server.url
                                                    prefs.userName = server.userName
                                                    prefs.token = ""
                                                    prefs.userId = ""
                                                }
                                                serverMenuOpen = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "该服务器登录已过期，请重新登录",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                                onSwitchServer()
                                            }
                                        },
                                    ) { focused ->
                                        Row(
                                            Modifier.fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (focused) Color(0x26FFFFFF) else Color.Transparent)
                                                .padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (current) "✓ ${server.name}" else server.name,
                                                modifier = Modifier.weight(1f),
                                                color = if (current) Accent else TextMain,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                runCatching { android.net.Uri.parse(server.url).host }.getOrNull().orEmpty(),
                                                color = TextSub,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (savedServers.isNotEmpty()) {
                            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0x30FFFFFF)))
                            Spacer(Modifier.height(4.dp))
                        }
                        MenuRowGlass("切换服务器 / 账号", "重新扫码或输入地址登录") { serverMenuOpen = false; onSwitchServer() }
                        MenuRowGlass("退出当前服务器", "清除登录状态并返回登录页", danger = true) { serverMenuOpen = false; onLogout() }
                    }
                }
            }
            itemsNav(tab, onSelect, onSettings, onSearch, navFocus)
        }
    }
}

@Composable
private fun MenuRowGlass(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        corner = 16.dp, scaleTo = 1.0f, onClick = onClick,
    ) { focused ->
        Row(
            Modifier.fillMaxSize()
                .background(
                    when {
                        focused -> if (danger) Color(0x33FF6B81) else Color(0x2EFFFFFF)
                        else -> Color(0x0FFFFFFF)
                    },
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (danger) Color(0xFFFF8A9A) else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = TextSub, fontSize = 10.sp)
            }
            Text("›", color = TextSub, fontSize = 15.sp)
        }
    }
}

@Composable
private fun RowScope.itemsNav(
    tab: HomeTab,
    onSelect: (HomeTab, String?) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    navFocus: FocusRequester,
) {
    val entries = listOf(
        Triple(HomeTab.Recommend, "首页", Icons.Rounded.Home),
        Triple(HomeTab.Favorites, "收藏", Icons.Rounded.Favorite),
        Triple(HomeTab.Media, "媒体库", Icons.Rounded.Movie),
    )
    entries.forEachIndexed { index, (entry, label, icon) ->
        val active = tab == entry
        val itemModifier = if (index == 0) {
            Modifier.size(40.dp).focusRequester(navFocus)
        } else {
            Modifier.size(40.dp)
        }
        FocusSurface(modifier = itemModifier, corner = 20.dp, scaleTo = 1.08f, onClick = { onSelect(entry, null) }) { focused ->
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(
                    when {
                        active -> Color(0x40FFFFFF)
                        focused -> Color(0x33FFFFFF)
                        else -> Color.Transparent
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = if (active || focused) Accent else Color(0xFFD6DEEB), modifier = Modifier.size(20.dp))
            }
        }
    }
    listOf(
        "设置" to Icons.Rounded.Settings,
        "搜索" to Icons.Rounded.Search,
    ).forEach { (label, icon) ->
        FocusSurface(
            modifier = Modifier.size(40.dp),
            corner = 20.dp,
            scaleTo = 1.08f,
            onClick = if (label == "设置") onSettings else onSearch,
        ) { focused ->
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(if (focused) Color(0x33FFFFFF) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = if (focused) Accent else Color(0xFFD6DEEB), modifier = Modifier.size(20.dp))
            }
        }
    }
}

private val SORT_LABELS = linkedMapOf(
    "date" to "最新添加",
    "name" to "名称",
    "rating" to "评分",
    "year" to "年份",
    "plays" to "播放次数",
)

@Composable
private fun TopAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.height(30.dp),
        corner = 15.dp,
        scaleTo = 1.05f,
        onClick = onClick,
    ) { focused ->
        Row(
            Modifier.fillMaxSize().background(
                if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                RoundedCornerShape(15.dp),
            ).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = if (focused) Accent else TextMain, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = if (focused) Accent else TextMain, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    active: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp = 34.dp,
    onClick: () -> Unit,
) {
    FocusSurface(
        modifier = Modifier.height(height).width(width),
        corner = (height / 2).coerceAtLeast(17.dp),
        scaleTo = 1.06f,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                when {
                    active -> Accent.copy(alpha = 0.9f)
                    focused -> Color(0x33FFFFFF)
                    else -> Color(0x14FFFFFF)
                },
                RoundedCornerShape((height / 2).coerceAtLeast(17.dp)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (active) Color(0xFF06101F) else TextMain,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/** 媒体库：展示服务器自带的全部分类（按 setup 顺序），可一直往下滑；隐藏项由设置页控制。 */
@Composable
private fun MediaLibrary(
    state: LazyListState,
    views: List<LibraryView>,
    contents: Map<String, List<MediaItem>>,
    focusedItemId: String?,
    focusedLibraryId: String?,
    onLibraryFocused: (String) -> Unit,
    onItemFocused: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenLibrary: (LibraryView) -> Unit,
) {
    val client = AppRuntime.client
    if (views.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("服务器暂无媒体库分类", color = TextSub, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 34.dp, end = 34.dp, top = 8.dp, bottom = 6.dp)) {
        Text("媒体库", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("服务器自带的全部分类· 可在设置里增删", color = TextSub, fontSize = 11.sp)
            }
        }
        views.forEach { v ->
            val items = contents[v.id].orEmpty()
            item(key = "view-${v.id}") {
                Column(Modifier.padding(top = 16.dp)) {
                    FocusSurface(
                        modifier = Modifier.padding(start = 34.dp, bottom = 10.dp).height(34.dp),
                        corner = 17.dp,
                        requestFocus = focusedLibraryId == v.id,
                        onFocused = { onLibraryFocused(v.id) },
                        onClick = { onOpenLibrary(v) },
                    ) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                            Text("${v.name}  ›  查看全部", color = if (focused) Accent else TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (items.isEmpty()) {
                        Text("正在加载…", color = TextSub, fontSize = 11.sp, modifier = Modifier.padding(start = 34.dp))
                    } else {
                        LazyRow(
                            modifier = Modifier.focusGroup(),
                            contentPadding = PaddingValues(start = 34.dp, end = 34.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(items, key = { it.id }) { it ->
                                PosterCard(item = it, imageUrl = client?.imageUrl(it), requestInitialFocus = it.id == focusedItemId, onFocused = { onItemFocused(it.id) }, onClick = { onOpenDetail(it.id) })
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun TopButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.size(40.dp),
        corner = 20.dp,
        scaleTo = 1.12f,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (focused) Accent else TextMain, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun Hero(
    items: List<MediaItem>,
    currentIndex: Int,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (String) -> Unit,
    playFocus: FocusRequester,
    hazeState: HazeState,
) {
    val item = items.getOrNull(currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    if (item == null) {
        Box(Modifier.fillMaxWidth().height(180.dp))
        return
    }
    val context = LocalContext.current
    LaunchedEffect(currentIndex, items) {
        if (items.size < 2) return@LaunchedEffect
        val next = items[(currentIndex + 1) % items.size]
        val nextImage = AppRuntime.client?.backdropUrl(next, 1280)
            ?.takeIf { it.isNotBlank() }
            ?: AppRuntime.client?.imageUrl(next, 720)
            ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(nextImage).crossfade(false).build()
        context.imageLoader.enqueue(request)
    }
    BoxWithConstraints(Modifier.hazeSource(hazeState).fillMaxWidth()) {
        val heroHeight = minOf(560.dp, maxHeight * 0.66f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(RoundedCornerShape(bottomEnd = 34.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF174C58), Color(0xFF111827))))
        ) {
            Crossfade(targetState = currentIndex, animationSpec = tween(300), label = "hero-backdrop") { index ->
                val heroItem = items.getOrNull(index)
                val client = AppRuntime.client
                val image = heroItem?.let { client?.backdropUrl(it, 1280) }
                    ?.takeIf { it.isNotBlank() }
                    ?: heroItem?.let { client?.imageUrl(it, 720) }
                if (!image.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(image).crossfade(false).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF2070A12), Color(0xB3070A12), Color(0x00070A12)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x00070A12),
                    0.66f to Color(0x00070A12),
                    0.82f to Color(0x66070A12),
                    1f to Color(0xF2070A12),
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 34.dp, bottom = 24.dp, end = 200.dp)
        ) {
            Text("为你推荐", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                item.seriesName ?: item.name,
                color = TextMain, style = TypeScale.display,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                item.subtitle.takeIf { it.isNotBlank() },
                item.genres.take(2).joinToString(" / ").takeIf { it.isNotBlank() },
            ).joinToString("   ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, color = TextSub, style = TypeScale.body)
            }
            item.overview?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it, color = Color(0xFFC9D5E8), fontSize = 12.sp, lineHeight = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(560.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row {
                FocusSurface(
                    modifier = Modifier.height(42.dp).width(128.dp),
                    corner = 21.dp,
                    scaleTo = 1.05f,
                    focusRequester = playFocus,
                    onClick = { onPlay(item) },
                ) { focused ->
                    Row(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                listOf(
                                    Accent.copy(alpha = if (focused) 1f else 0.9f),
                                    Color(0xFF9B8CFF).copy(alpha = if (focused) 1f else 0.9f),
                                )
                            )
                        ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF06101F), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (item.positionTicks > 0) "继续播放" else "立即播放",
                            color = Color(0xFF06101F), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FocusSurface(
                    modifier = Modifier.height(42.dp).width(108.dp),
                    corner = 21.dp,
                    scaleTo = 1.05f,
                    onClick = { onOpenDetail(item.seriesId ?: item.id) },
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) { Text("查看详情", color = TextMain, fontSize = 13.sp) }
                }
            }
        }
        
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.indices.forEach { index ->
                    val width by animateDpAsState(if (index == currentIndex) 22.dp else 8.dp, label = "hero-indicator-width")
                    Box(Modifier.width(width).height(8.dp).background(
                        if (index == currentIndex) Accent else Color(0x99FFFFFF), RoundedCornerShape(4.dp),
                    ))
                }
            }
        }
    }
}

@Composable
private fun RowSection(
    title: String,
    items: List<MediaItem>,
    requestInitialFocus: Boolean = false,
    focusItemId: String? = null,
    sectionKey: String = title,
    focusedSection: String? = null,
    upFocus: FocusRequester? = null,
    onItemFocused: ((String) -> Unit)? = null,
    onClick: (MediaItem) -> Unit,
) {
    val client = AppRuntime.client
    Column(Modifier.padding(top = Space.section)) {
        SectionTitle(title, Modifier.padding(start = 34.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(start = 34.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            items(items, key = { it.id }) { it ->
                PosterCard(
                    item = it,
                    imageUrl = client?.imageUrl(it),
                    width = 200.dp,
                    requestInitialFocus = (focusedSection == sectionKey && it.id == focusItemId) ||
                        (requestInitialFocus && it == items.firstOrNull()),
                    upFocus = upFocus,
                    onFocused = { onItemFocused?.invoke(it.id) },
                    onClick = { onClick(it) },
                )
            }
        }
    }
}
