package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
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

    var loading by remember { mutableStateOf(LibraryCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resume by remember { mutableStateOf(LibraryCache.home?.resume ?: emptyList()) }
    var latest by remember { mutableStateOf(LibraryCache.home?.latest ?: emptyList()) }
    var movies by remember { mutableStateOf(LibraryCache.home?.movies ?: emptyList()) }
    var series by remember { mutableStateOf(LibraryCache.home?.series ?: emptyList()) }
    var favorites by remember { mutableStateOf(LibraryCache.home?.favorites ?: emptyList()) }
    var views by remember { mutableStateOf(LibraryCache.home?.views ?: emptyList()) }

    var tab by remember { mutableStateOf(HomeTab.Recommend) }
    var selectedLibrary by remember { mutableStateOf<LibraryView?>(null) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var librarySort by remember { mutableStateOf("date") }
    var gridItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var gridLoading by remember { mutableStateOf(false) }
    // 各媒体库视图的横向内容（id → items）
    var viewContents by remember { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    // 导航栏自动隐藏
    var navHidden by remember { mutableStateOf(false) }

    // A genre is an in-page route: Android/TV Back returns to the genre list, then home.
    BackHandler(enabled = tab != HomeTab.Recommend) {
        if (tab == HomeTab.Media && selectedLibrary != null) {
            selectedLibrary = null
            gridItems = emptyList()
            gridLoading = false
        } else {
            tab = HomeTab.Recommend
            selectedLibrary = null
            gridItems = emptyList()
        }
    }

    LaunchedEffect(Unit) {
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
                val l = if (m.isNotEmpty()) m else runCatching { c.getLatest(null, 24) }.getOrDefault(emptyList())

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

    // 切换标签：Movies/Series 直接复用首页已加载的数据，不再额外请求
    LaunchedEffect(tab, selectedLibrary, librarySort) {
        val requestTab = tab
        val requestLibrary = selectedLibrary
        val requestSort = librarySort
        gridLoading = false
        libraryError = null
        if (requestTab == HomeTab.Media && requestLibrary != null) gridItems = emptyList()
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
            HomeTab.Media -> "library:${requestLibrary?.id.orEmpty()}:$requestSort"
            else -> return@LaunchedEffect
        }
        LibraryCache.browseOf(key)?.let {
            gridItems = it
            return@LaunchedEffect
        }
        gridLoading = true
        try {
            val result = runCatching {
                when (requestTab) {
                    HomeTab.Movies -> c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Series -> c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Favorites -> c.getFavorites(120)
                    HomeTab.Media -> requestLibrary?.let {
                        val (sb, so) = when (requestSort) {
                            "name" -> "SortName" to "Ascending"
                            "rating" -> "CommunityRating" to "Descending"
                            "year" -> "ProductionYear" to "Descending"
                            else -> "DateCreated" to "Descending"
                        }
                        c.getAllItems(it.id, sortBy = sb, sortOrder = so)
                    }.orEmpty()
                    else -> emptyList()
                }
            }
            currentCoroutineContext().ensureActive()
            if (tab != requestTab || selectedLibrary != requestLibrary || librarySort != requestSort) return@LaunchedEffect
            result.onSuccess { list ->
                LibraryCache.putBrowse(key, list)
                gridItems = list
            }.onFailure { failure ->
                libraryError = failure.message ?: "媒体库读取失败"
                gridItems = emptyList()
            }
        } finally {
            if (tab == requestTab && selectedLibrary == requestLibrary && librarySort == requestSort) gridLoading = false
        }
    }

    // 媒体库视图内容：启动后即在后台预热拉取各视图最近内容，无需等切换Tab
    LaunchedEffect(views) {
        val c = client ?: return@LaunchedEffect
        if (views.isEmpty()) return@LaunchedEffect
        val enabled = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
        val missing = enabled.filter { v -> viewContents[v.id] == null }
        if (missing.isEmpty()) return@LaunchedEffect
        val result = coroutineScope {
            missing.map { v ->
                async {
                    v.id to runCatching {
                        c.getItems(parentId = v.id, sortBy = "DateCreated", sortOrder = "Descending", limit = 30)
                    }.getOrDefault(emptyList())
                }
            }.map { it.await() }.toMap()
        }
        viewContents = viewContents + result
    }

    val hero = resume.firstOrNull() ?: movies.firstOrNull() ?: series.firstOrNull()

    val recListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val navFocus = remember { FocusRequester() }

    // 导航自动隐藏：往下滑离开顶部即隐藏，回到顶部/切换标签恢复
    LaunchedEffect(tab, selectedLibrary) {
        navHidden = false
        snapshotFlow {
            when {
                tab == HomeTab.Recommend -> recListState.firstVisibleItemIndex to recListState.firstVisibleItemScrollOffset
                tab == HomeTab.Movies || tab == HomeTab.Series || tab == HomeTab.Favorites -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                tab == HomeTab.Media -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                else -> 0 to 0
            }
        }.collect { (index, offset) ->
            if (index == 0 && offset < 40) {
                navHidden = false
            } else if (index > 0 || offset > 100) {
                // 向下滚离开顶部立刻隐藏，不挡视线
                navHidden = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    item { Hero(hero, client?.backdropUrl(hero ?: MediaItem("", ""), 1920), onPlay, onOpenDetail) }
                    if (resume.isNotEmpty()) item { RowSection("继续观看", resume) { onOpenDetail(it.id) } }
                    if (favorites.isNotEmpty()) item { RowSection("我的收藏", favorites) { onOpenDetail(it.id) } }
                    if (latest.isNotEmpty()) item { RowSection("最新添加", latest) { onOpenDetail(it.id) } }
                    if (movies.isNotEmpty()) item { RowSection("电影", movies) { onOpenDetail(it.id) } }
                    if (series.isNotEmpty()) item { RowSection("剧集", series) { onOpenDetail(it.id) } }
                    // 媒体库全分类直接融入首页推荐流，支持无限往下滑
                    val enabledViews = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
                    enabledViews.forEach { v ->
                        val items = viewContents[v.id].orEmpty()
                        if (items.isNotEmpty()) {
                            item(key = "home-view-${v.id}") {
                                RowSection(v.name, items) { onOpenDetail(it.id) }
                            }
                        }
                    }
                    item { OpenNavButton { navHidden = false; scope.launch { delay(220); runCatching { navFocus.requestFocus() } } } }
                    item { Spacer(Modifier.height(140.dp)) }
                }
                tab == HomeTab.Media && selectedLibrary == null -> MediaLibrary(
                    views = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) },
                    contents = viewContents,
                    onOpenDetail = onOpenDetail,
                    onOpenLibrary = { selectedLibrary = it },
                )
                tab == HomeTab.Media && selectedLibrary != null -> Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(modifier = Modifier.height(40.dp).width(96.dp), corner = 20.dp, onClick = { selectedLibrary = null; gridItems = emptyList() }) { focused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("‹ 返回", color = if (focused) Accent else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                selectedLibrary?.name.orEmpty(),
                                color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("共 ${gridItems.size} 部", color = TextSub, fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        FocusSurface(
                            modifier = Modifier.height(40.dp).width(112.dp),
                            corner = 20.dp, scaleTo = 1.03f,
                            onClick = {
                                val keys = SORT_LABELS.keys.toList()
                                val idx = keys.indexOf(librarySort)
                                librarySort = keys[(idx + 1) % keys.size]
                            },
                        ) { focused ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("排序", color = if (focused) Accent else TextSub, fontSize = 9.sp, lineHeight = 11.sp)
                                    Text(SORT_LABELS[librarySort] ?: "默认", color = if (focused) Accent else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SORT_LABELS.forEach { (key, label) ->
                            val active = librarySort == key
                            FocusSurface(modifier = Modifier.height(32.dp).width(86.dp), corner = 16.dp, scaleTo = 1.03f, onClick = { librarySort = key }) { focused ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        color = when {
                                            active -> Color(0xFF06101F)
                                            focused -> Accent
                                            else -> TextSub
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (gridLoading) LoadingView(text = "正在读取 ${selectedLibrary?.name.orEmpty()}…")
                    else if (libraryError != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("读取失败：${libraryError}", color = TextSub, fontSize = 13.sp)
                    }
                    else if (gridItems.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此媒体库暂无内容", color = TextSub, fontSize = 13.sp)
                    }
                    else LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(gridItems, key = { it.id }) { media ->
                            PosterCard(item = media, imageUrl = client?.imageUrl(media), onClick = { onOpenDetail(media.id) })
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OpenNavButton { navHidden = false; scope.launch { delay(220); runCatching { navFocus.requestFocus() } } }
                        }
                    }
                }
                gridLoading -> LoadingView(text = "正在加载…")
                gridItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还没有内容", color = TextSub, fontSize = 13.sp)
                }
                else -> LazyVerticalGrid(
                    state = gridState,
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 88.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(gridItems, key = { it.id }) { it ->
                        PosterCard(
                            item = it,
                            imageUrl = client?.imageUrl(it),
                            onClick = { onOpenDetail(it.id) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OpenNavButton { navHidden = false; scope.launch { delay(220); runCatching { navFocus.requestFocus() } } }
                    }
                }
            }

        // 紧凑悬浮导航：盖在内容上方，下滑自动隐藏
        AnimatedVisibility(
            visible = !navHidden,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f),
        ) {
NavBar(
                tab = tab,
                onSelect = { t, _ -> tab = t; selectedLibrary = null },
                onSearch = onSearch,
                onSettings = onSettings,
                onSwitchServer = onSwitchServer,
                onLogout = onLogout,
                navFocus = navFocus,
            )
        }
    }
}

@Composable
private fun NavBar(
    tab: HomeTab,
    onSelect: (HomeTab, String?) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onLogout: () -> Unit,
    navFocus: FocusRequester,
) {
    var serverMenuOpen by remember { mutableStateOf(false) }
    val glassShape = RoundedCornerShape(28.dp)
    val menuShape = RoundedCornerShape(24.dp)
    if (serverMenuOpen) BackHandler { serverMenuOpen = false }
    Box(Modifier.fillMaxSize()) {
        if (serverMenuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { serverMenuOpen = false },
            )
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xEE070A12), Color(0x00070A12))))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusSurface(modifier = Modifier.height(46.dp).width(150.dp), corner = 23.dp,
                onClick = { serverMenuOpen = true }) { focused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("L", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Lumina", color = if (focused) Accent else TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("▾", color = if (focused) Accent else TextSub, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            GlassPanel(Modifier.height(46.dp).weight(1f), corner = 23.dp) {
                Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text("当前服务器", color = TextSub, fontSize = 9.sp, lineHeight = 11.sp)
                        Text(
                            AppRuntime.session?.serverName ?: "未连接",
                            color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (serverMenuOpen) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, top = 72.dp)
                    .width(320.dp)
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
                        Text("Lumina · Emby", color = TextSub, fontSize = 9.sp)
                        Text(
                            AppRuntime.session?.userName ?: "未登录",
                            color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MenuRowGlass("切换服务器 / 账号", "重新扫码或输入地址登录") { serverMenuOpen = false; onSwitchServer() }
                MenuRowGlass("退出当前服务器", "清除登录状态并返回登录页", danger = true) { serverMenuOpen = false; onLogout() }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 92.dp, bottom = 20.dp)
                .clip(glassShape)
                .background(Brush.horizontalGradient(listOf(Color(0xBCE4E4EE), Color(0x85B7C6D9), Color(0xA8EEE9F6))))
                .border(1.dp, Color(0x55FFFFFF), glassShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsNav(tab, onSelect, onSettings, navFocus)
        }
        FocusSurface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp).size(64.dp),
            corner = 34.dp, scaleTo = 1.05f, onClick = onSearch,
        ) { focused ->
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(34.dp)).background(if (focused) Color(0xEEFFFFFF) else Color(0xB8FFFFFF)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(34.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = Color(0xFF111522), modifier = Modifier.size(29.dp))
            }
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
            Modifier.height(56.dp).width(76.dp).focusRequester(navFocus)
        } else {
            Modifier.height(56.dp).width(76.dp)
        }
        FocusSurface(modifier = itemModifier, corner = 24.dp, onClick = { onSelect(entry, null) }) { focused ->
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(if (active) Color(0x58FFFFFF) else if (focused) Color(0x35FFFFFF) else Color.Transparent).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = label, tint = if (active) Color(0xFF171A24) else Color(0xFF363B47), modifier = Modifier.size(23.dp))
                Text(label, color = Color(0xFF171A24), fontSize = 10.sp)
            }
        }
    }
    FocusSurface(modifier = Modifier.height(56.dp).width(70.dp), corner = 24.dp, onClick = onSettings) { focused ->
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(if (focused) Color(0x35FFFFFF) else Color.Transparent).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = Color(0xFF363B47), modifier = Modifier.size(23.dp))
            Text("设置", color = Color(0xFF171A24), fontSize = 10.sp)
        }
    }
}

private val SORT_LABELS = linkedMapOf(
    "date" to "最新添加",
    "name" to "名称",
    "rating" to "评分",
    "year" to "年份",
)

@Composable
private fun OpenNavButton(onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        FocusSurface(
            modifier = Modifier.height(44.dp).width(190.dp),
            corner = 22.dp, scaleTo = 1.04f, onClick = onOpen,
        ) { focused ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("⌄ 打开导航栏", color = if (focused) Accent else TextSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

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
    views: List<LibraryView>,
    contents: Map<String, List<MediaItem>>,
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
    LazyColumn(Modifier.fillMaxSize().padding(top = 80.dp)) {
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
                    FocusSurface(modifier = Modifier.padding(start = 34.dp, bottom = 10.dp).height(34.dp), corner = 17.dp, onClick = { onOpenLibrary(v) }) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(17.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                            Text("${v.name}  ›  查看全部", color = if (focused) Accent else TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (items.isEmpty()) {
                        Text("正在加载…", color = TextSub, fontSize = 11.sp, modifier = Modifier.padding(start = 34.dp))
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(start = 34.dp, end = 34.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(items, key = { it.id }) { it ->
                                PosterCard(item = it, imageUrl = client?.imageUrl(it), onClick = { onOpenDetail(it.id) })
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
    item: MediaItem?,
    backdrop: String?,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    if (item == null) {
        Box(Modifier.fillMaxWidth().height(180.dp))
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(bottomEnd = 34.dp))
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF2070A12), Color(0xB3070A12), Color(0x00070A12)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x00070A12), Color(0x66070A12), Color(0xF2070A12)))
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 34.dp, bottom = 24.dp, end = 200.dp)
        ) {
            Text("为你推荐", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                item.seriesName ?: item.name,
                color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                item.subtitle.takeIf { it.isNotBlank() },
                item.genres.take(2).joinToString(" / ").takeIf { it.isNotBlank() },
            ).joinToString("   ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, color = TextSub, fontSize = 12.sp)
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
    }
}

@Composable
private fun RowSection(title: String, items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    val client = AppRuntime.client
    Column(Modifier.padding(top = 16.dp)) {
        SectionTitle(title, Modifier.padding(start = 34.dp, bottom = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 34.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.id }) { it ->
                PosterCard(item = it, imageUrl = client?.imageUrl(it), onClick = { onClick(it) })
            }
        }
    }
}
