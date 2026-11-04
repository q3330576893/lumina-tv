package com.lumina.tv.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import coil.compose.AsyncImage
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
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

private enum class HomeTab(val label: String) {
    Recommend("推荐"),
    Movies("电影"),
    Series("剧集"),
    Favorites("收藏"),
    Genre("分类"),
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

    var tab by remember { mutableStateOf(HomeTab.Recommend) }
    var genre by remember { mutableStateOf<String?>(null) }
    var gridItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var gridLoading by remember { mutableStateOf(false) }

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

                val r = rA.await()
                val m = mA.await()
                val s = sA.await()
                val f = fA.await()
                var g = gA.await()
                if (g.isEmpty()) {
                    g = (m + s).flatMap { it.genres }.distinct().filter { it.isNotBlank() }.sorted()
                }
                val l = if (m.isNotEmpty()) m else runCatching { c.getLatest(null, 24) }.getOrDefault(emptyList())

                resume = r
                latest = l
                movies = m
                series = s
                favorites = f
                genres = g
                LibraryCache.putHome(
                    LibraryCache.HomeData(
                        resume = r, latest = l, movies = m, series = s,
                        favorites = f, genres = g, at = System.currentTimeMillis(),
                    )
                )
            }
        } catch (e: Exception) {
            if (cached == null) error = e.message
        } finally {
            loading = false
        }
    }

    // 切换标签：优先读缓存，没有才请求
    LaunchedEffect(tab, genre) {
        if (tab == HomeTab.Recommend) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val key = when (tab) {
            HomeTab.Movies -> "movies"
            HomeTab.Series -> "series"
            HomeTab.Favorites -> "favorites"
            HomeTab.Genre -> "genre:${genre ?: ""}"
            else -> return@LaunchedEffect
        }
        if (tab == HomeTab.Genre && genre == null) {
            gridItems = emptyList()
            return@LaunchedEffect
        }
        LibraryCache.browseOf(key)?.let {
            gridItems = it
            return@LaunchedEffect
        }
        gridLoading = true
        val list = runCatching {
            when (tab) {
                HomeTab.Movies -> c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                HomeTab.Series -> c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                HomeTab.Favorites -> c.getFavorites(120)
                HomeTab.Genre -> c.getByGenre(genre ?: "", 120)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        LibraryCache.putBrowse(key, list)
        gridItems = list
        gridLoading = false
    }

    val hero = resume.firstOrNull() ?: movies.firstOrNull() ?: series.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        TopBar(onSearch = onSearch, onSettings = onSettings)
        TabBar(tab, genres, genre) { t, g ->
            tab = t
            genre = g
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
                tab == HomeTab.Recommend -> LazyColumn(Modifier.fillMaxSize()) {
                    item { Hero(hero, client?.backdropUrl(hero ?: MediaItem("", ""), 1920), onPlay, onOpenDetail) }
                    if (resume.isNotEmpty()) item { RowSection("继续观看", resume) { onOpenDetail(it.id) } }
                    if (favorites.isNotEmpty()) item { RowSection("我的收藏", favorites) { onOpenDetail(it.id) } }
                    if (latest.isNotEmpty()) item { RowSection("最新添加", latest) { onOpenDetail(it.id) } }
                    if (movies.isNotEmpty()) item { RowSection("电影", movies) { onOpenDetail(it.id) } }
                    if (series.isNotEmpty()) item { RowSection("剧集", series) { onOpenDetail(it.id) } }
                    item { Spacer(Modifier.height(36.dp)) }
                }
                tab == HomeTab.Genre && genre == null -> GenrePicker(genres) { genre = it }
                gridLoading -> LoadingView(text = "正在加载…")
                gridItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还没有内容", color = TextSub, fontSize = 13.sp)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 10.dp, bottom = 40.dp),
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
        }
    }
}

@Composable
private fun TopBar(onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 34.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("琉璃", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val user = AppRuntime.session?.userName ?: ""
            if (user.isNotBlank()) Text(user, color = TextSub, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        TopButton(Icons.Rounded.Search, "搜索", onSearch)
        Spacer(Modifier.width(10.dp))
        TopButton(Icons.Rounded.Settings, "设置", onSettings)
    }
}

@Composable
private fun TabBar(
    tab: HomeTab,
    genres: List<String>,
    genre: String?,
    onSelect: (HomeTab, String?) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTab.entries.forEach { t ->
                TabChip(t.label, tab == t, 84.dp) { onSelect(t, if (t == HomeTab.Genre) genre else null) }
            }
        }
        if (genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(genres, key = { it }) { g ->
                    TabChip(g, tab == HomeTab.Genre && genre == g, 96.dp) { onSelect(HomeTab.Genre, g) }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.height(34.dp).width(width),
        corner = 17.dp,
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
                RoundedCornerShape(17.dp),
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
private fun GenrePicker(genres: List<String>, onPick: (String) -> Unit) {
    if (genres.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("服务器没有返回类型信息", color = TextSub, fontSize = 13.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 10.dp, bottom = 40.dp),
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
