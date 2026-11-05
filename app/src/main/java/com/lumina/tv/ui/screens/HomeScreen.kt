package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
    Genre("题材"),
}

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val client = AppRuntime.client

    var loading by remember { mutableStateOf(LibraryCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resume by remember { mutableStateOf(LibraryCache.home?.resume ?: emptyList()) }
    var latest by remember { mutableStateOf(LibraryCache.home?.latest ?: emptyList()) }
    var movies by remember { mutableStateOf(LibraryCache.home?.movies ?: emptyList()) }
    var series by remember { mutableStateOf(LibraryCache.home?.series ?: emptyList()) }
    var favorites by remember { mutableStateOf(LibraryCache.home?.favorites ?: emptyList()) }
    var genres by remember { mutableStateOf(LibraryCache.home?.genres ?: emptyList()) }
    var views by remember { mutableStateOf(LibraryCache.home?.views ?: emptyList()) }

    var tab by remember { mutableStateOf(HomeTab.Recommend) }
    var genre by remember { mutableStateOf<String?>(null) }
    var gridItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var gridLoading by remember { mutableStateOf(false) }
    // 各媒体库视图的横向内容（id → items）
    var viewContents by remember { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    // 导航栏自动隐藏
    var navHidden by remember { mutableStateOf(false) }

    // A genre is an in-page route: Android/TV Back returns to the genre list, then home.
    BackHandler(enabled = tab == HomeTab.Genre) {
        if (genre != null) {
            genre = null
            gridItems = emptyList()
            gridLoading = false
        } else {
            tab = HomeTab.Recommend
        }
    }

    // 首页数据：命中缓存立即渲染，过期才在后台刷新（并行请求）
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
                val gA = async { runCatching { c.getGenres() }.getOrDefault(emptyList()) }
                val vA = async { runCatching { c.getViews() }.getOrDefault(emptyList()) }

                val r = rA.await()
                val m = mA.await()
                val s = sA.await()
                val f = fA.await()
                var g = gA.await()
                if (g.isEmpty()) {
                    // 按出现频率排序，取前 20，避免分类堆砌
                    g = (m + s).flatMap { it.genres }
                        .filter { it.isNotBlank() && it.length <= 6 }
                        .groupingBy { it }.eachCount()
                        .entries.sortedByDescending { it.value }
                        .take(20).map { it.key }
                }
                val v = vA.await()
                val l = if (m.isNotEmpty()) m else runCatching { c.getLatest(null, 24) }.getOrDefault(emptyList())

                resume = r
                latest = l
                movies = m
                series = s
                favorites = f
                genres = g
                views = v
                LibraryCache.putHome(
                    LibraryCache.HomeData(
                        resume = r, latest = l, movies = m, series = s,
                        favorites = f, genres = g, views = v, at = System.currentTimeMillis(),
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
    LaunchedEffect(tab, genre) {
        val requestTab = tab
        val requestGenre = genre
        gridLoading = false
        if (requestTab == HomeTab.Genre && requestGenre != null) gridItems = emptyList()
        if (requestTab == HomeTab.Recommend || requestTab == HomeTab.Media) return@LaunchedEffect
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
        if (requestTab == HomeTab.Genre && requestGenre == null) {
            gridItems = emptyList()
            return@LaunchedEffect
        }
        val key = when (requestTab) {
            HomeTab.Movies -> "movies"
            HomeTab.Series -> "series"
            HomeTab.Favorites -> "favorites"
            HomeTab.Genre -> "genre:${requestGenre.orEmpty()}"
            else -> return@LaunchedEffect
        }
        LibraryCache.browseOf(key)?.let {
            gridItems = it
            return@LaunchedEffect
        }
        gridLoading = true
        try {
            val list = runCatching {
                when (requestTab) {
                    HomeTab.Movies -> c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Series -> c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Favorites -> c.getFavorites(120)
                    HomeTab.Genre -> c.getByGenre(requestGenre.orEmpty(), 120)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            currentCoroutineContext().ensureActive()
            if (tab != requestTab || genre != requestGenre) return@LaunchedEffect
            LibraryCache.putBrowse(key, list)
            gridItems = list
        } finally {
            if (tab == requestTab && genre == requestGenre) gridLoading = false
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
    val genreGridState = rememberLazyGridState()

    // 导航自动隐藏：往下滑离开顶部即隐藏，回到顶部/切换标签恢复
    LaunchedEffect(tab, genre) {
        navHidden = false
        snapshotFlow {
            when {
                tab == HomeTab.Recommend -> recListState.firstVisibleItemIndex to recListState.firstVisibleItemScrollOffset
                tab == HomeTab.Movies || tab == HomeTab.Series || tab == HomeTab.Favorites -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                tab == HomeTab.Genre && genre == null -> genreGridState.firstVisibleItemIndex to genreGridState.firstVisibleItemScrollOffset
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
                    if (genres.isNotEmpty()) item { GenreQuickRow(genres) { g -> tab = HomeTab.Genre; genre = g } }
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
                    item { Spacer(Modifier.height(36.dp)) }
                }
                tab == HomeTab.Media -> MediaLibrary(
                    views = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) },
                    contents = viewContents,
                    onOpenDetail = onOpenDetail,
                )
                tab == HomeTab.Genre && genre == null -> GenrePicker(genres, genreGridState) { genre = it }
                tab == HomeTab.Genre && genre != null -> Column(Modifier.fillMaxSize().padding(top = 70.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(
                            modifier = Modifier.height(34.dp),
                            corner = 17.dp,
                            onClick = { genre = null },
                        ) { focused ->
                            Row(
                                Modifier.fillMaxSize().background(
                                    if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                                    RoundedCornerShape(17.dp)
                                ).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("← 全部类型", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(genre ?: "", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("共 ${gridItems.size} 部", color = TextSub, fontSize = 12.sp)
                    }
                    if (gridLoading) {
                        LoadingView(text = "正在筛选 $genre…")
                    } else if (gridItems.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("未找到该分类的内容", color = TextSub, fontSize = 13.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(gridItems, key = { it.id }) { it ->
                                PosterCard(item = it, imageUrl = client?.imageUrl(it), onClick = { onOpenDetail(it.id) })
                            }
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
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 88.dp, bottom = 40.dp),
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
                onSelect = { t, g ->
                    tab = t
                    genre = g
                },
                onSearch = onSearch,
                onSettings = onSettings,
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
) {
    // 手机与TV自适应紧凑导航栏：左品牌，中可横滑Tab，右常驻搜索+设置
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xF0070A12), Color(0x99070A12), Color(0x00070A12))),
            )
            .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌：Lumina
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lumina", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.width(16.dp))
        // 中间横向滑动 Tab，确保在手机端不会撑破布局或把右侧按钮挤掉
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(HomeTab.entries.filterNot { it == HomeTab.Favorites }) { t ->
                TabChip(t.label, tab == t, 60.dp, height = 28.dp) {
                    onSelect(t, null)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // 右侧常驻操作区：手机与TV都永远可见！
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TopAction("搜索", Icons.Rounded.Search, onSearch)
            TopAction("收藏", Icons.Rounded.Favorite) { onSelect(HomeTab.Favorites, null) }
            TopAction("设置", Icons.Rounded.Settings, onSettings)
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

@Composable
private fun GenrePicker(
    genres: List<String>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onPick: (String) -> Unit,
) {
    if (genres.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("服务器没有返回类型信息", color = TextSub, fontSize = 13.sp)
        }
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 88.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(genres, key = { it }) { g ->
            FocusSurface(
                modifier = Modifier.height(56.dp).fillMaxWidth(),
                corner = 14.dp,
                scaleTo = 1.03f,
                onClick = { onPick(g) },
            ) { focused ->
                Box(
                    Modifier.fillMaxSize().background(
                        if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                        RoundedCornerShape(14.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text(g, color = TextMain, fontSize = 13.sp) }
            }
        }
    }
}

/** 媒体库：展示服务器自带的全部分类（按 setup 顺序），可一直往下滑；隐藏项由设置页控制。 */
@Composable
private fun MediaLibrary(
    views: List<LibraryView>,
    contents: Map<String, List<MediaItem>>,
    onOpenDetail: (String) -> Unit,
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
                    SectionTitle(v.name, Modifier.padding(start = 34.dp, bottom = 10.dp))
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
private fun GenreQuickRow(genres: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        SectionTitle("分类看片", Modifier.padding(start = 34.dp, bottom = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 34.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(genres, key = { it }) { g ->
                FocusSurface(
                    modifier = Modifier.height(36.dp).width(110.dp),
                    corner = 18.dp,
                    scaleTo = 1.06f,
                    onClick = { onPick(g) },
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(
                            if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                            RoundedCornerShape(18.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(g, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
