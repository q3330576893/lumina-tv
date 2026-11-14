@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumina.tv.ui.screens

import android.util.Log
import android.app.Activity
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.LibraryView
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.FocusScale
import com.lumina.tv.ui.components.GlassTone
import com.lumina.tv.ui.components.glassSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.SectionTitle
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Ink
import com.lumina.tv.ui.theme.InkSoft
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.theme.Space
import com.lumina.tv.ui.theme.ShapeScale
import com.lumina.tv.ui.theme.TypeScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job

private fun calculateHeroHeight(screenHeight: Dp): Dp = screenHeight * 0.5f

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
    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    LaunchedEffect(context) {
        ThemeRuntime.updateDeviceBlurSupported(
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true,
        )
    }

    var loading by remember { mutableStateOf(LibraryCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(LibraryCache.home?.resume ?: emptyList()) }
    var latest by remember { mutableStateOf(LibraryCache.home?.latest ?: emptyList()) }
    var movies by remember { mutableStateOf(LibraryCache.home?.movies ?: emptyList()) }
    var series by remember { mutableStateOf(LibraryCache.home?.series ?: emptyList()) }
    var favorites by remember { mutableStateOf(LibraryCache.home?.favorites ?: emptyList()) }
    var views by remember { mutableStateOf(LibraryCache.home?.views ?: emptyList()) }
    var libraryCounts by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var libraryRecentBackdropUrls by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

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
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val gridStateKey = when {
        tab == HomeTab.Media -> "library:${selectedLibraryId ?: "list"}"
        else -> "tab:${tab.name}"
    }
    val gridState = remember(gridStateKey) {
        gridStates.getOrPut(gridStateKey) { LazyGridState() }
    }
    // 待恢复的滚动位置：null 表示没有待恢复目标（此时才允许把位置写回缓存）
    var pendingGridRestore by remember(gridStateKey) { mutableStateOf(LibraryCache.gridPosition(gridStateKey)) }
    val homeExceptionHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            Log.e("HomeScreen", "Unhandled home coroutine failure", throwable)
        }
    }
    val scope = rememberCoroutineScope { homeExceptionHandler }
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
    // 保存位置：只有"恢复完成"之后才写回，避免刚进页面时的 (0,0) 把记忆冲掉
    LaunchedEffect(gridStateKey, gridState, pendingGridRestore) {
        if (pendingGridRestore != null) return@LaunchedEffect
        snapshotFlow {
            Triple(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, gridItems.size)
        }.collect { (index, offset, loaded) ->
            if (gridItems.isNotEmpty()) LibraryCache.putGridPosition(gridStateKey, index, offset, loaded)
        }
    }

    // 恢复位置：必须等条目到齐（含上次已加载的分页数量）再滚，否则会被列表钳到 0
    LaunchedEffect(gridStateKey, gridState, pendingGridRestore, gridItems.size) {
        val target = pendingGridRestore ?: return@LaunchedEffect
        val (wantIndex, wantOffset, wantLoaded) = target
        if (gridItems.isEmpty()) return@LaunchedEffect
        // 电影/剧集/收藏是一次性全量拉取，没有分页，直接进入滚动恢复
        if (selectedLibrary != null && gridItems.size < wantLoaded && gridHasMore && !gridAppending) {
            val lib = selectedLibrary
            if (lib != null && client != null) {
                val (sb, so) = sortParams(librarySort, librarySortOrder)
                gridAppending = true
                try {
                    val page = client.getItems(
                        parentId = lib.id, types = collectionTypeToItemTypes(lib.type),
                        sortBy = sb, sortOrder = so, limit = 50, startIndex = gridNextIndex,
                    )
                    if (page.isNotEmpty()) {
                        gridItems = (gridItems + page).distinctBy { it.id }
                        gridNextIndex += page.size
                        gridHasMore = page.size == 50
                    } else {
                        gridHasMore = false
                    }
                } catch (_: Exception) {
                    gridHasMore = false
                } finally {
                    gridAppending = false
                }
            }
            return@LaunchedEffect
        }
        // 列表布局完成后再滚，必要时重试几帧（数据刚到的那一帧 LazyGrid 还没测量）
        var attempt = 0
        while (attempt < 3) {
            runCatching { gridState.scrollToItem(wantIndex.coerceAtLeast(0), wantOffset.coerceAtLeast(0)) }
            withFrameNanos { }
            if (gridState.firstVisibleItemIndex == wantIndex) break
            attempt++
        }
        pendingGridRestore = null
    }
    // 各媒体库视图的横向内容（id → items）
    var viewContents by remember { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    var initialFocusPending by rememberSaveable { mutableStateOf(true) }
    var focusedMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedMediaSection by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var heroIndex by rememberSaveable { mutableStateOf(0) }
    var lastExitBackAt by remember { mutableStateOf(0L) }

    // A genre is an in-page route: Android/TV Back returns to the genre list, then home.
    BackHandler(enabled = true) {
        when {
            sortMenuOpen -> closeSortMenu()
            libraryServerMenuOpen -> libraryServerMenuOpen = false
            tab == HomeTab.Media && selectedLibraryId != null -> {
                selectedLibraryId = null
                gridItems = emptyList()
            }
            tab != HomeTab.Recommend -> {
                tab = HomeTab.Recommend
                selectedLibraryId = null
                gridItems = emptyList()
            }
            recListState.firstVisibleItemIndex > 0 || recListState.firstVisibleItemScrollOffset > 0 -> {
                scope.launch { recListState.animateScrollToItem(0) }
            }
            else -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastExitBackAt <= 2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastExitBackAt = now
                    android.widget.Toast.makeText(context, "\u518D\u6309\u4E00\u6B21\u9000\u51FA", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(reloadToken, client) {
        AppRuntime.screenLabel = "首页"
        val c = client
        if (c == null) {
            error = "尚未登录"
            loading = false
            return@LaunchedEffect
        }
        val session = AppRuntime.session
        val diskCached = session?.let { LibraryCache.readHome(context, it.baseUrl, it.userId) }
        val cached = diskCached ?: LibraryCache.home
        if (cached != null) {
            resume = cached.resume
            latest = cached.latest
            movies = cached.movies
            series = cached.series
            favorites = cached.favorites
            views = cached.views
            loading = false
        }
        error = null
        offline = false
        try {
            coroutineScope {
                val rA = async { runCatching { c.getResume(20) } }
                val mA = async { runCatching { c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) } }
                val sA = async { runCatching { c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) } }
                val fA = async { runCatching { c.getFavorites(24) } }
                val vA = async { runCatching { c.getViews() } }
                val lA = async { runCatching {
                    c.getLatest(null, 24).distinctBy { it.id }.filter { it.type != "Movie" && it.type != "Series" }
                        .ifEmpty { cached?.latest.orEmpty() }
                } }

                val results = listOf(rA.await(), mA.await(), sA.await(), fA.await(), vA.await(), lA.await())
                val failed = results.count { it.isFailure }
                offline = failed > 0
                if (failed == results.size && cached == null) {
                    error = c.lastApiFailure ?: results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                } else if (failed < results.size) {
                    val r = rA.await().getOrElse { cached?.resume.orEmpty() }
                    val m = mA.await().getOrElse { cached?.movies.orEmpty() }
                    val s = sA.await().getOrElse { cached?.series.orEmpty() }
                    val f = fA.await().getOrElse { cached?.favorites.orEmpty() }
                    val v = vA.await().getOrElse { cached?.views.orEmpty() }
                    val l = lA.await().getOrElse { cached?.latest.orEmpty() }
                    resume = r; latest = l; movies = m; series = s; favorites = f; views = v
                    val updated = LibraryCache.HomeData(
                        resume = r, latest = l, movies = m, series = s,
                        favorites = f, genres = emptyList(), views = v, at = System.currentTimeMillis(),
                    )
                    LibraryCache.putHome(updated)
                    session?.let { LibraryCache.writeHome(context, it.baseUrl, it.userId, updated) }
                }
            }
        } catch (e: Exception) {
            if (cached == null) error = e.message else offline = true
        } finally {
            loading = false
            c.requestProgressFlush()
        }
    }

    LaunchedEffect(views, client, reloadToken) {
        val c = client ?: return@LaunchedEffect
        val visibleViews = views.filterNot { AppRuntime.prefs?.isViewHidden(it.id) ?: false }
        val prefetchedViews = supervisorScope {
            visibleViews.map { view ->
                async {
                    val cacheKey = "library:${view.id}:date:Descending"
                    val viewItems = try {
                        LibraryCache.browseOf(cacheKey) ?: c.getItems(
                            parentId = view.id,
                            types = collectionTypeToItemTypes(view.type),
                            sortBy = "DateCreated",
                            sortOrder = "Descending",
                            limit = 50,
                        ).also { LibraryCache.putBrowse(cacheKey, it) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("LuminaTV", "Library row load failed: ${view.id}", e)
                        LibraryCache.browseOf(cacheKey).orEmpty()
                    }
                    val recentBackdropUrl = viewItems.firstNotNullOfOrNull { item -> c.backdropUrl(item, 640) }
                    Triple(view, viewItems, recentBackdropUrl)
                }
            }.awaitAll()
        }
        viewContents = viewContents + prefetchedViews.associate { it.first.id to it.second }
        libraryRecentBackdropUrls = libraryRecentBackdropUrls + prefetchedViews.associate { it.first.id to it.third }
    }

    LaunchedEffect(views, client, reloadToken) {
        val c = client ?: return@LaunchedEffect
        val counts = coroutineScope {
            views.map { view -> async { view.id to runCatching { c.getLibraryItemCount(view.id) }.getOrNull() } }.awaitAll()
        }
        libraryCounts = libraryCounts + counts
    }

    LaunchedEffect(loading, initialFocusPending, resume, favorites, latest, movies, series, views) {
        if (!loading && initialFocusPending && (resume.isNotEmpty() || favorites.isNotEmpty() || latest.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty() || views.isNotEmpty())) {
            delay(240)
            initialFocusPending = false
        }
    }

    LaunchedEffect(resume, favorites, latest, movies, series, viewContents) {
        val c = client ?: return@LaunchedEffect
        val preloadItems = (resume.take(8) + favorites.take(8) + latest.take(8) + movies.take(8) + series.take(8) +
            viewContents.values.flatMap { it.take(4) }).distinctBy { it.id }
        preloadItems.forEach { media ->
            val url = c.imageUrl(media) ?: return@forEach
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).crossfade(false).build()
            )
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
        LibraryCache.browseOf(key)?.let {
            gridItems = it
            if (requestTab == HomeTab.Media) {
                libraryTotalCount = requestLibrary?.let { libraryCounts[it.id] }
                gridNextIndex = it.size
                gridHasMore = it.size == 50
            }
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
        val missing = views.filter { v ->
            !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) && viewContents[v.id] == null
        }
        val cached = missing.mapNotNull { view ->
            LibraryCache.browseOf("library:${view.id}:date:Descending")?.let { view.id to it }
        }.toMap()
        if (cached.isNotEmpty()) viewContents = viewContents + cached
    }

    val heroPool = (latest + movies + series + favorites).distinctBy { it.id }
    val heroItems = remember(heroPool) { pickHeroItems(heroPool, latest.map { it.id }.toHashSet(), 6) }
    LaunchedEffect(heroItems) { heroIndex = 0 }
    val hero = heroItems.getOrNull(heroIndex) ?: resume.firstOrNull()
    val homeLibraries = views.filterNot { AppRuntime.prefs?.isViewHidden(it.id) ?: false }
    val firstSectionKey: String? = when {
        resume.isNotEmpty() -> "resume"
        favorites.isNotEmpty() -> "favorites"
        else -> homeLibraries.firstNotNullOfOrNull { view ->
            viewContents[view.id]?.takeIf { it.isNotEmpty() }?.let { "view-${view.id}" }
        }
    }
    val topRenderedRow = when {
        resume.isNotEmpty() -> "resume" to resume
        favorites.isNotEmpty() -> "favorites" to favorites
        else -> homeLibraries.firstNotNullOfOrNull { view ->
            viewContents[view.id]?.takeIf { it.isNotEmpty() }?.let { "view-${view.id}" to it }
        }
    }
    val topRenderedRowKey = topRenderedRow?.first
    val navFocus = remember { FocusRequester() }
    val libraryBackFocus = remember { FocusRequester() }
    val librarySortFocus = remember { FocusRequester() }
    val favoritesContentFocus = remember { FocusRequester() }
    val mediaLibraryContentFocus = remember { FocusRequester() }
    val libraryGridColumns = ((LocalConfiguration.current.screenWidthDp - 80 + 20) / 150).coerceAtLeast(1)

    val heroPlayFocus = remember { FocusRequester() }
    // Hero 在 LazyColumn 里，滚出屏幕会被回收 → heroPlayFocus 变成未挂载，
    // 此时其它行的 focusProperties.up 指向它就会抛 "FocusRequester is not initialized" 而闪退。
    var heroComposed by remember { mutableStateOf(false) }
    val navContentFocus = when (tab) {
        HomeTab.Recommend -> if (heroComposed) heroPlayFocus else FocusRequester.Cancel
        HomeTab.Favorites -> if (gridItems.isNotEmpty()) favoritesContentFocus else FocusRequester.Cancel
        HomeTab.Media -> if (selectedLibraryId == null && views.any { !(AppRuntime.prefs?.isViewHidden(it.id) ?: false) }) mediaLibraryContentFocus else FocusRequester.Cancel
        else -> FocusRequester.Cancel
    }

    // 导航在顶部，右键不再劫持（原来的右键跳竖栏逻辑会让影片行无法继续右移）
    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
            runCatching { navFocus.requestFocus() }.isSuccess
        } else false
    }) {
        when {
            loading -> LoadingView(text = "正在读取影库…")
                error != null -> Column(
                    Modifier.fillMaxSize().clickable { reloadToken++ },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("点击任意位置重试", color = Accent, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("加载失败：$error", color = TextSub, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("服务器：${AppRuntime.session?.baseUrl}", color = TextSub.copy(alpha = 0.6f), fontSize = 11.sp)
                    AppRuntime.client?.lastApiFailure?.let { failure ->
                        Text("诊断：$failure", color = TextSub.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 2)
                    }
                }
                tab == HomeTab.Recommend -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = recListState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    item {
                        val displayedHeroItems = heroItems.ifEmpty { listOfNotNull(hero) }
                        Hero(
                            items = displayedHeroItems,
                            currentIndex = heroIndex,
                            onPrevious = {
                                if (displayedHeroItems.isNotEmpty()) {
                                    heroIndex = (heroIndex - 1 + displayedHeroItems.size) % displayedHeroItems.size
                                }
                            },
                            onNext = {
                                if (displayedHeroItems.isNotEmpty()) {
                                    heroIndex = (heroIndex + 1) % displayedHeroItems.size
                                }
                            },
                            onSelectIndex = { heroIndex = it },
                            onPlay = onPlay,
                            onOpenDetail = onOpenDetail,
                            playFocus = heroPlayFocus,
                            onPlayFocused = { scope.launch { recListState.animateScrollToItem(0) } },
                            onNextCardDown = {
                                val target = topRenderedRow
                                if (target == null) false else {
                                    // Reuse RowSection's existing first-card focus/restore path.
                                    scope.launch {
                                        focusedMediaId = null
                                        focusedMediaSection = null
                                        val firstContentIndex = 2 + if (resume.isEmpty() && favorites.isEmpty()) 1 else 0
                                        recListState.animateScrollToItem(firstContentIndex)
                                        withFrameNanos { }
                                        withFrameNanos { }
                                        focusedMediaSection = target.first
                                        focusedMediaId = target.second.first().id
                                    }
                                    true
                                }
                            },
                            hazeState = hazeState,
                            onComposed = { heroComposed = it },
                        )
                    }
                    if (!loading && resume.isEmpty() && favorites.isEmpty()) {
                        item {
                            FocusSurface(
                                modifier = Modifier.padding(horizontal = 34.dp, vertical = 12.dp)
                                    .fillMaxWidth().height(56.dp),
                                corner = 28.dp,
                                scaleTo = 1.02f,
                                requestFocus = initialFocusPending,
                                onClick = { tab = HomeTab.Media; selectedLibraryId = null },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(if (focused) Color(0x24FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(28.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("还没有观看记录 · 去媒体库逛逛", color = TextMain, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (resume.isNotEmpty()) item { RowSection("继续观看", resume, width = 170.dp, aspectRatio = 16f / 9f, titleOverlay = true, requestInitialFocus = initialFocusPending && focusedMediaId == null && firstSectionKey == "resume", focusItemId = focusedMediaId, sectionKey = "resume", focusedSection = focusedMediaSection, upFocus = if (hero != null && heroComposed && topRenderedRowKey == "resume") heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "resume" }) { onOpenDetail(it.seriesId ?: it.id) } }
                    if (homeLibraries.isNotEmpty()) item(key = "home-libraries") {
                        HomeLibraryRow(
                            views = homeLibraries,
                            contents = viewContents,
                            counts = libraryCounts,
                            onOpenLibrary = { tab = HomeTab.Media; selectedLibraryId = it.id; focusedLibraryId = it.id; focusedMediaId = null; LibraryCache.clearGridPosition("library:${it.id}") },
                        )
                    }
                    if (favorites.isNotEmpty()) item { RowSection("我的收藏", favorites, width = 130.dp, requestInitialFocus = initialFocusPending && focusedMediaId == null && firstSectionKey == "favorites", focusItemId = focusedMediaId, sectionKey = "favorites", focusedSection = focusedMediaSection, upFocus = if (hero != null && heroComposed && topRenderedRowKey == "favorites") heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "favorites" }) { onOpenDetail(it.id) } }
                    // 媒体库全分类直接融入首页推荐流，支持无限往下滑
                    val enabledViews = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
                    enabledViews.forEach { v ->
                        val items = viewContents[v.id].orEmpty()
                        if (items.isNotEmpty()) {
                            item(key = "home-view-${v.id}") {
                                RowSection(v.name, items, requestInitialFocus = initialFocusPending && focusedMediaId == null && firstSectionKey == "view-" + v.id, focusItemId = focusedMediaId, sectionKey = "view-" + v.id, focusedSection = focusedMediaSection, upFocus = if (hero != null && heroComposed && topRenderedRowKey == "view-" + v.id) heroPlayFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "view-" + v.id }) { onOpenDetail(it.id) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(140.dp)) }
                    }
                }
                tab == HomeTab.Media && selectedLibrary == null -> MediaLibrary(
                    views = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) },
                    contents = viewContents,
                    focusedLibraryId = focusedLibraryId,
                    onLibraryFocused = { focusedLibraryId = it },
                    onOpenLibrary = { selectedLibraryId = it.id; focusedLibraryId = it.id; focusedMediaId = null; LibraryCache.clearGridPosition("library:${it.id}") },
                    firstItemFocus = mediaLibraryContentFocus,
                )
                tab == HomeTab.Media && selectedLibrary != null -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(96.dp).padding(start = 40.dp, end = 40.dp, top = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(modifier = Modifier.size(40.dp).focusRequester(libraryBackFocus).focusProperties { right = librarySortFocus }, corner = 20.dp, onClick = { selectedLibraryId = null; gridItems = emptyList() }) { focused ->
                            Box(
                                Modifier.fillMaxSize().then(if (focused) Modifier.border(1.dp, Accent, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("‹", color = if (focused) Accent else TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(
                                selectedLibrary?.name.orEmpty(),
                                color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(libraryTotalCount?.let { "共 $it 部" } ?: "已加载 ${gridItems.size} 部", color = TextMain.copy(alpha = 0.6f), fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Box {
                            FocusSurface(
                                modifier = Modifier.height(40.dp).width(112.dp).focusRequester(librarySortFocus).focusProperties { left = libraryBackFocus },
                                corner = 20.dp, scaleTo = 1.03f,
                                requestFocus = restoreSortFocus,
                                onClick = { sortMenuOpen = true },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(
                                            if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF),
                                            RoundedCornerShape(20.dp),
                                        )
                                        .then(
                                            if (focused) Modifier.border(1.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                                            else Modifier.border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("排序", color = if (focused) Accent else TextSub, fontSize = 9.sp, lineHeight = 11.sp)
                                        Text(SORT_LABELS[librarySort] ?: "默认", color = if (focused) Accent else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { closeSortMenu() },
                                offset = DpOffset(0.dp, 6.dp),
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
                                                        // 换排序等于换内容顺序，旧位置作废
                                                        LibraryCache.clearGridPosition(gridStateKey)
                                                        scope.launch { runCatching { gridState.scrollToItem(0) } }
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
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(Color(0x26FFFFFF)))
                                        listOf("Descending" to "降序 ↓", "Ascending" to "升序 ↑").forEach { (order, label) ->
                                            val selected = librarySortOrder == order
                                            var orderFocused by remember(order) { mutableStateOf(false) }
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
                                                        librarySortOrder = order
                                                        AppRuntime.prefs?.librarySortOrder = order
                                                        LibraryCache.clearGridPosition(gridStateKey)
                                                        scope.launch { runCatching { gridState.scrollToItem(0) } }
                                                        closeSortMenu()
                                                    }
                                                    .background(if (orderFocused) Color(0x26FFFFFF) else if (selected) Accent.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 18.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(if (selected) "✓" else "", color = Accent, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                                                Text(label, color = if (orderFocused || selected) TextMain else TextSub, fontSize = 14.sp)
                                            }
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
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(gridItems, key = { _, item -> item.id }) { index, media ->
                            PosterCard(item = media, imageUrl = client?.imageUrl(media), requestInitialFocus = focusedMediaId == media.id || (initialFocusPending && focusedMediaId == null && index == 0), upFocus = if (index < libraryGridColumns) librarySortFocus else null, onFocused = { focusedMediaId = media.id }, onClick = { onOpenDetail(media.id) })
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
                            columns = GridCells.Adaptive(minSize = 130.dp),
                    contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 76.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(gridItems, key = { _, item -> item.id }) { index, it ->
                        PosterCard(
                            item = it,
                            imageUrl = client?.imageUrl(it),
                            requestInitialFocus = focusedMediaId == it.id || (initialFocusPending && focusedMediaId == null && it == gridItems.firstOrNull()),
                            focusRequester = if (tab == HomeTab.Favorites && index == 0) favoritesContentFocus else null,
                            onFocused = { focusedMediaId = it.id },
                            onClick = { onOpenDetail(it.id) },
                        )
                    }
                }
            }

        if (offline && !loading && error == null) {
            Text(
                "离线，显示上次内容",
                color = Color(0xFF9AA4B2),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).zIndex(3f).padding(top = 18.dp, end = 34.dp),
            )
        }
        if (!(tab == HomeTab.Media && selectedLibraryId != null)) NavBar(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().zIndex(2f),
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
                    offline = false
                    resume = emptyList()
                    latest = emptyList()
                    movies = emptyList()
                    series = emptyList()
                    favorites = emptyList()
                    views = emptyList()
                    viewContents = emptyMap()
                    libraryCounts = emptyMap()
                    libraryRecentBackdropUrls = emptyMap()
                    selectedLibraryId = null
                    gridItems = emptyList()
                    focusedMediaId = null
                    reloadToken++
                },
                navFocus = navFocus,
                contentFocus = navContentFocus,
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
    contentFocus: FocusRequester,
    hazeState: HazeState,
) {
    Box(modifier) {
        ServerCapsule(
            modifier = Modifier.align(Alignment.TopStart).padding(start = Space.l + Space.xs, top = 16.dp),
            onSwitchServer = onSwitchServer,
            onLogout = onLogout,
            onServerSwitched = onServerSwitched,
            navFocus = navFocus,
            contentFocus = contentFocus,
        )
        TopNavBar(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = Space.l),
            tab = tab,
            onSelect = onSelect,
            onSettings = onSettings,
            onSearch = onSearch,
            navFocus = navFocus,
            contentFocus = contentFocus,
            hazeState = hazeState,
        )
    }
}

@Composable
private fun ServerCapsule(
    modifier: Modifier,
    onSwitchServer: () -> Unit,
    onLogout: () -> Unit,
    onServerSwitched: () -> Unit,
    navFocus: FocusRequester,
    contentFocus: FocusRequester,
) {
    val context = LocalContext.current
    var serverMenuOpen by remember { mutableStateOf(false) }
    val capsuleInteraction = remember { MutableInteractionSource() }
    val capsuleFocused by capsuleInteraction.collectIsFocusedAsState()
    val glassShape = RoundedCornerShape(10.dp)
    val menuShape = ShapeScale.panel
    Box(modifier.wrapContentSize()) {
        Box(
            Modifier
                .height(34.dp)
                .widthIn(min = 92.dp, max = 204.dp)
                .clip(glassShape)
                .glassSurface(glassShape, tone = if (capsuleFocused) GlassTone.Accent else GlassTone.Normal)
                .clickable(
                    interactionSource = capsuleInteraction,
                    indication = null,
                    onClick = { serverMenuOpen = true },
                )
                .focusProperties { down = contentFocus }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.DirectionDown -> runCatching { contentFocus.requestFocus() }.isSuccess
                        Key.DirectionRight -> {
                            runCatching { navFocus.requestFocus() }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Text(
                    AppRuntime.prefs?.serverName?.takeIf { it.isNotBlank() }
                        ?: AppRuntime.session?.serverName ?: "Emby",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(Modifier.size(8.dp).align(Alignment.CenterEnd)) {
                    val chevron = Path().apply {
                        moveTo(1.4.dp.toPx(), 2.9.dp.toPx())
                        lineTo(4.dp.toPx(), 5.2.dp.toPx())
                        lineTo(6.6.dp.toPx(), 2.9.dp.toPx())
                    }
                    drawPath(
                        path = chevron,
                        color = Color.White.copy(alpha = if (capsuleFocused) 0.80f else 0.45f),
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
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
private fun TopNavBar(
    modifier: Modifier,
    tab: HomeTab,
    onSelect: (HomeTab, String?) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    navFocus: FocusRequester,
    contentFocus: FocusRequester,
    hazeState: HazeState,
) {
    val glassShape = RoundedCornerShape(24.dp)
    val entries = listOf(
        Triple("首页", Icons.Rounded.Home, tab == HomeTab.Recommend),
        Triple("收藏", Icons.Rounded.Favorite, tab == HomeTab.Favorites),
        Triple("媒体库", Icons.Rounded.Movie, tab == HomeTab.Media),
        Triple("设置", Icons.Rounded.Settings, false),
        Triple("搜索", Icons.Rounded.Search, false),
    )
    Row(
        modifier
            .shadow(18.dp, glassShape, clip = false)
            .clip(glassShape)
            .then(
                if (ThemeRuntime.trueBlurEnabled) Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = ThemeRuntime.frostFallback,
                        tint = HazeTint(ThemeRuntime.frostTint),
                        blurRadius = ThemeRuntime.glassBlurRadius,
                        noiseFactor = 0f,
                    ),
                ) else Modifier.background(ThemeRuntime.frostFallback)
            )
            .border(1.dp, ThemeRuntime.glassEdge, glassShape)
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, (label, icon, active) ->
            Box(Modifier.width(44.dp).height(40.dp)) {
            var focusedState by remember { mutableStateOf(false) }
            var labelVisible by remember { mutableStateOf(false) }
            LaunchedEffect(focusedState) {
                if (focusedState) {
                    delay(220)
                    labelVisible = true
                } else labelVisible = false
            }
            FocusSurface(
                modifier = Modifier.fillMaxSize()
                    .then(if (active) Modifier.focusRequester(navFocus) else Modifier)
                    .focusProperties { down = contentFocus }
                    .onPreviewKeyEvent { event ->
                        // 导航在顶部，下键应当直接进内容区；内容区没目标时放行让系统自己找
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            runCatching { contentFocus.requestFocus() }.isSuccess
                        } else false
                    },
                corner = 20.dp,
                scaleTo = 1.08f,
                onClick = {
                    when (index) {
                        0 -> onSelect(HomeTab.Recommend, null)
                        1 -> onSelect(HomeTab.Favorites, null)
                        2 -> onSelect(HomeTab.Media, null)
                        3 -> onSettings()
                        else -> onSearch()
                    }
                },
            ) { focused ->
                focusedState = focused
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent, RoundedCornerShape(20.dp))
                        .then(if (focused) Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = label, tint = if (active || focused) Accent else Color(0xFFD6DEEB), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(12.dp).height(3.dp).background(if (active) Accent.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(2.dp)))
                    }
                }
            }
            if (labelVisible) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, with(LocalDensity.current) { 66.dp.roundToPx() }),
                    properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
                ) {
                    Text(
                        label,
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(8.dp))
                            .background(Color(0xE610141D), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
            }
            if (index == 2) {
                Spacer(Modifier.width(6.dp))
                Spacer(Modifier.width(1.dp).height(16.dp).background(Color(0x26FFFFFF)))
                Spacer(Modifier.width(6.dp))
            } else if (index < entries.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

/** 内部排序键 → Emby SortBy/SortOrder，多处复用避免各写一份。 */
private fun sortParams(sort: String, order: String): Pair<String, String> = when (sort) {
    "name" -> "SortName" to order
    "rating" -> "CommunityRating" to order
    "year" -> "ProductionYear" to order
    "plays" -> "PlayCount" to order
    "runtime" -> "Runtime" to order
    "random" -> "Random" to "Ascending"
    else -> "DateCreated" to order
}

private val SORT_LABELS = linkedMapOf(
    "date" to "最新添加",
    "name" to "名称",
    "rating" to "评分",
    "year" to "年份",
    "plays" to "播放次数",
    "runtime" to "时长",
    "random" to "随机",
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
private fun HomeLibraryRow(
    views: List<LibraryView>,
    contents: Map<String, List<MediaItem>>,
    counts: Map<String, Int?>,
    onOpenLibrary: (LibraryView) -> Unit,
) {
    Column(Modifier.padding(top = Space.section)) {
        Text(
            "媒体库",
            color = TextMain,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(views, key = { "libcard-${it.id}" }) { view ->
                val shape = RoundedCornerShape(10.dp)
                val ownImage = remember(view.id, view.imageTag) {
                    view.imageTag?.let { AppRuntime.client?.libraryPrimaryImageUrl(view.id) }
                }
                val posterImage = contents[view.id]?.firstOrNull()?.let { AppRuntime.client?.imageUrl(it) }
                val imageUrl = ownImage ?: posterImage
                FocusSurface(
                    modifier = Modifier.width(280.dp).height(150.dp),
                    corner = 10.dp,
                    scaleTo = 1.04f,
                    onClick = { onOpenLibrary(view) },
                ) { focused ->
                    Box(Modifier.fillMaxSize().background(InkSoft, shape)) {
                        if (imageUrl != null) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))),
                        )
                        counts[view.id]?.let { count ->
                            Text(
                                "$count 部",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                            )
                        }
                        if (focused) Box(Modifier.fillMaxSize().border(1.5.dp, Accent, shape))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaLibrary(
    views: List<LibraryView>,
    contents: Map<String, List<MediaItem>>,
    focusedLibraryId: String?,
    onLibraryFocused: (String) -> Unit,
    onOpenLibrary: (LibraryView) -> Unit,
    firstItemFocus: FocusRequester,
) {
    if (views.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("服务器暂无媒体库分类", color = TextSub, fontSize = 13.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 34.dp, end = 34.dp, top = 76.dp, bottom = 6.dp)) {
            Text("媒体库", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(views, key = { it.id }) { v ->
                val libraryItems = contents[v.id].orEmpty()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FocusSurface(
                        modifier = Modifier.width(300.dp).height(132.dp),
                        corner = 20.dp,
                        scaleTo = 1.04f,
                        focusRequester = if (v == views.firstOrNull()) firstItemFocus else null,
                        requestFocus = focusedLibraryId == v.id,
                        onFocused = { onLibraryFocused(v.id) },
                        onClick = { onOpenLibrary(v) },
                    ) { focused ->
                        val shape = RoundedCornerShape(20.dp)
                        Box(
                            Modifier.fillMaxSize()
                                .then(if (focused) Modifier.shadow(16.dp, shape, clip = false) else Modifier)
                                .background(Color(0xFF141B2B), shape)
                                .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
                                .clip(shape)
                        ) {
                            if (libraryItems.isNotEmpty()) {
                                Row(Modifier.fillMaxSize()) {
                                    repeat(2) { column ->
                                        Column(Modifier.weight(1f).fillMaxHeight()) {
                                            repeat(2) { row ->
                                                val imageItem = libraryItems.getOrNull(column * 2 + row)
                                                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A2233))) {
                                                    imageItem?.let { media ->
                                                        AsyncImage(
                                                            model = AppRuntime.client?.imageUrl(media, 360),
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize(),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Box(Modifier.fillMaxSize().background(if (focused) Color(0x77000000) else Color(0x99000000)))
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)).align(Alignment.TopCenter))
                            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                Text(v.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (libraryItems.isEmpty()) "—" else "共 ${libraryItems.size} 部",
                                    color = Color(0xCCFFFFFF),
                                    fontSize = 11.sp,
                                )
                            }
                            Text("›", color = Color(0xCCFFFFFF), fontSize = 24.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
                        }
                    }
                }
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

/**
 * 轮播选片：打分排序后，用「当天日期」做随机种子从前 20 里抽 6 个。
 * 同一天内保持稳定，隔天自动换一批，不需要联网。
 */
private fun pickHeroItems(pool: List<MediaItem>, recentIds: Set<String>, size: Int = 6): List<MediaItem> {
    if (pool.isEmpty()) return emptyList()
    val calendar = java.util.Calendar.getInstance()
    val daySeed = (calendar.get(java.util.Calendar.YEAR) * 367L + calendar.get(java.util.Calendar.DAY_OF_YEAR))
    fun score(item: MediaItem): Double {
        var s = 0.0
        val hasBackdrop = !item.backdropTag.isNullOrBlank()
        if (hasBackdrop) s += 3.0
        if (!item.logoTag.isNullOrBlank()) s += 2.0
        if (item.positionTicks > 0 && !item.played) s += 3.0
        if (item.id in recentIds) s += 2.0
        if (item.played) s -= 2.0
        // 60 分钟以内多半是花絮/预告，不该占轮播位
        val minutes = (item.runtimeTicks ?: 0L) / 600_000_000L
        if (minutes in 1..59) s -= 3.0
        val rating = item.communityRating ?: item.rating ?: 0.0
        s += (rating / 3.0).coerceAtMost(3.0)
        return s
    }
    val ranked = pool.sortedByDescending { score(it) }
    val shuffled = ranked.take(20).shuffled(java.util.Random(daySeed))
    // 没有横版剧照的片子裁出来一定难看，优先只放有 backdrop 的
    val withBackdrop = shuffled.filter { !it.backdropTag.isNullOrBlank() }
    val picked = withBackdrop.take(size)
    return when {
        picked.size >= 3 -> picked
        withBackdrop.isNotEmpty() -> (withBackdrop + shuffled.filterNot { it in withBackdrop }).distinctBy { it.id }.take(size)
        else -> shuffled.take(size)
    }
}

@Composable
private fun Hero(
    items: List<MediaItem>,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (String) -> Unit,
    playFocus: FocusRequester,
    onPlayFocused: () -> Unit,
    onNextCardDown: () -> Boolean,
    hazeState: HazeState,
    onComposed: (Boolean) -> Unit = {},
) {
    val item = items.getOrNull(currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    if (item == null) {
        Box(Modifier.fillMaxWidth().height(180.dp))
        return
    }
    DisposableEffect(Unit) {
        onComposed(true)
        onDispose { onComposed(false) }
    }
    val context = LocalContext.current
    val transitionScope = rememberCoroutineScope()
    val client = AppRuntime.client
    val detailFocus = remember { FocusRequester() }
    val nextCardFocus = remember { FocusRequester() }
    val resolveHeroImage: (MediaItem?) -> String? = { heroItem ->
        heroItem?.let { client?.backdropUrl(it, 1280) }
            ?.takeIf { it.isNotBlank() }
            ?: heroItem?.let { client?.imageUrl(it, 720) }
    }
    val zoomStates = remember(items) { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val zoomJobs = remember(items) { mutableMapOf<String, Job>() }
    val wipeProgress = remember(items) { Animatable(1f) }
    var bottomImage by remember(items, client) { mutableStateOf(resolveHeroImage(item)) }
    var topImage by remember(items, client) { mutableStateOf<String?>(null) }
    var topLoaded by remember(items, client) { mutableStateOf(false) }
    var isForward by remember(items) { mutableStateOf(true) }
    var lastRequestedIndex by remember(items) { mutableStateOf(currentIndex) }
    var transitionGeneration by remember(items) { mutableStateOf(0) }
    var transitionJob by remember(items) { mutableStateOf<Job?>(null) }

    fun zoomFor(image: String): Animatable<Float, AnimationVector1D> =
        zoomStates.getOrPut(image) { Animatable(0f) }

    LaunchedEffect(items) {
        bottomImage?.let { image ->
            val zoom = zoomFor(image)
            zoom.snapTo(0f)
            zoom.animateTo(1f, tween(15_000, easing = LinearEasing))
        }
    }
    LaunchedEffect(currentIndex, items) {
        val targetImage = resolveHeroImage(items.getOrNull(currentIndex))
        if (targetImage == null || targetImage == topImage || (topImage == null && targetImage == bottomImage)) {
            lastRequestedIndex = currentIndex
            return@LaunchedEffect
        }

        transitionJob?.cancel()
        transitionGeneration += 1
        if (topLoaded && wipeProgress.value > 0f) {
            topImage?.let { bottomImage = it }
        }
        val forwardDistance = (currentIndex - lastRequestedIndex + items.size) % items.size
        val backwardDistance = (lastRequestedIndex - currentIndex + items.size) % items.size
        isForward = forwardDistance <= backwardDistance
        lastRequestedIndex = currentIndex
        topImage = targetImage
        topLoaded = false
        wipeProgress.snapTo(0f)
    }
    LaunchedEffect(topLoaded, transitionGeneration) {
        val image = topImage ?: return@LaunchedEffect
        if (!topLoaded) return@LaunchedEffect
        val generation = transitionGeneration
        val zoom = zoomFor(image)
        zoomJobs.remove(image)?.cancel()
        zoomJobs[image] = transitionScope.launch {
            zoom.snapTo(0f)
            zoom.animateTo(1f, tween(15_000, easing = LinearEasing))
        }
        transitionJob?.cancel()
        transitionJob = transitionScope.launch {
            wipeProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
            if (generation == transitionGeneration && image == topImage) {
                bottomImage = image
                topImage = null
                topLoaded = false
            }
        }
    }
    DisposableEffect(items) {
        onDispose {
            transitionJob?.cancel()
            zoomJobs.values.forEach { it.cancel() }
        }
    }
    var lastInteractionAt by remember(items) { mutableStateOf(System.currentTimeMillis()) }
    var focusedTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun reportFocus(key: String, focused: Boolean) {
        val next = if (focused) focusedTargets + key else focusedTargets - key
        focusedTargets = next
    }
    LaunchedEffect(items) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(500)
            if (System.currentTimeMillis() - lastInteractionAt >= 10_000L) {
                onNext()
                lastInteractionAt = System.currentTimeMillis()
            }
        }
    }
    // 标题优先用透明 Logo：Emby 自带优先，没有再用 TMDB 的
    val embyLogo = remember(item.id, item.logoTag) {
        AppRuntime.client?.logoUrl(item)?.takeIf { it.isNotBlank() }
    }
    var tmdbInfo by remember(item.id) { mutableStateOf<TmdbClient.TmdbInfo?>(null) }
    LaunchedEffect(item.id) {
        if (embyLogo != null && (item.communityRating ?: item.rating) != null) return@LaunchedEffect
        tmdbInfo = TmdbClient.lookup(item.type, item.seriesName ?: item.name, item.year)
    }
    val titleLogo = embyLogo ?: tmdbInfo?.logoUrl
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
    val heroHeight = calculateHeroHeight(LocalConfiguration.current.screenHeightDp.dp)
    Box(Modifier.hazeSource(hazeState).fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(RoundedCornerShape(bottomEnd = 34.dp))
                .background(Ink)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || items.size < 2) return@onPreviewKeyEvent false
                    // Let normal focus search handle the play -> detail -> next-card path.
                    val navigatingNextCard = "next-card" in focusedTargets ||
                        (items.size > 1 && ("detail" in focusedTargets ||
                            ("play" in focusedTargets && event.key == Key.DirectionRight)))
                    if (navigatingNextCard) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { lastInteractionAt = System.currentTimeMillis(); onPrevious(); true }
                        Key.DirectionRight -> { lastInteractionAt = System.currentTimeMillis(); onNext(); true }
                        else -> false
                    }
                }
        ) {
            bottomImage?.let { image ->
                HeroBackdropImage(
                    imageUrl = image,
                    zoom = zoomFor(image).value,
                )
            }
            topImage?.let { image ->
                HeroBackdropImage(
                    imageUrl = image,
                    zoom = zoomFor(image).value,
                    wipeProgress = wipeProgress.value,
                    isForward = isForward,
                    onLoaded = {
                        if (image == topImage) topLoaded = true
                    },
                )
            }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.50f to Ink.copy(alpha = 0.00f),
                    0.62f to Ink.copy(alpha = 0.16f),
                    0.78f to Ink.copy(alpha = 0.58f),
                    0.92f to Ink.copy(alpha = 0.88f),
                    1.00f to Ink,
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 34.dp, bottom = 24.dp).widthIn(max = 520.dp)
        ) {
            Text("为你推荐", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            if (!titleLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(titleLogo).crossfade(true).build(),
                    contentDescription = item.seriesName ?: item.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.heightIn(max = 66.dp).widthIn(max = 430.dp),
                )
            } else {
                Text(
                    item.seriesName ?: item.name,
                    color = TextMain, style = TypeScale.display,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val hasOwnRating = (item.rating ?: item.communityRating) != null
            val ratingText = if (!hasOwnRating) tmdbInfo?.rating?.let { "TMDB ★ %.1f".format(it) } else null
            val sub = listOfNotNull(
                item.subtitle.takeIf { it.isNotBlank() },
                item.genres.take(2).joinToString(" / ").takeIf { it.isNotBlank() },
                ratingText,
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row {
                FocusSurface(
                    modifier = Modifier.height(42.dp).width(128.dp)
                        .focusProperties { if (items.size > 1) down = nextCardFocus }
                        .onFocusChanged { reportFocus("play", it.hasFocus || it.isFocused) },
                    corner = 21.dp,
                    scaleTo = 1.05f,
                    focusRequester = playFocus,
                    onFocused = onPlayFocused,
                    onClick = { lastInteractionAt = System.currentTimeMillis(); onPlay(item) },
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
                            color = Color(0xFF06101F), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FocusSurface(
                    modifier = Modifier.height(42.dp).width(108.dp)
                        .focusProperties { if (items.size > 1) { right = nextCardFocus; down = nextCardFocus } }
                        .onFocusChanged { reportFocus("detail", it.hasFocus || it.isFocused) },
                    corner = 21.dp,
                    scaleTo = 1.05f,
                    focusRequester = detailFocus,
                    onClick = { onOpenDetail(item.seriesId ?: item.id) },
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) { Text("查看详情", color = TextMain, fontSize = 13.sp) }
                }
            }
        }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 34.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.indices.forEach { index ->
                        val width by animateDpAsState(if (index == currentIndex) 22.dp else 8.dp, label = "hero-indicator-width")
                        FocusSurface(
                            modifier = Modifier.width(width).height(8.dp)
                                .onFocusChanged { reportFocus("indicator-$index", it.hasFocus || it.isFocused) },
                            corner = 4.dp,
                            scaleTo = 1f,
                            onClick = { lastInteractionAt = System.currentTimeMillis(); onSelectIndex(index) },
                        ) { focused ->
                            Box(
                                Modifier.fillMaxSize()
                                    .background(
                                        if (index == currentIndex) Accent else Color(0x99FFFFFF),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .then(
                                        if (focused) Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp))
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
                if (items.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    val nextItem = items[(currentIndex + 1) % items.size]
                    val nextImage = nextItem.let { client?.backdropUrl(it, 1280) }
                        ?.takeIf { it.isNotBlank() }
                        ?: nextItem.let { client?.imageUrl(it, 720) }
                    FocusSurface(
                        modifier = Modifier.size(width = 168.dp, height = 94.dp)
                            .focusRequester(nextCardFocus)
                            .focusProperties { left = detailFocus }
                            .onFocusChanged { reportFocus("next-card", it.isFocused) }
                            .onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onNextCardDown()
                            },
                        corner = 9.dp,
                        scaleTo = FocusScale.POSTER_MEDIUM,
                        onFocused = { lastInteractionAt = System.currentTimeMillis() },
                        onClick = { lastInteractionAt = System.currentTimeMillis(); onNext() },
                    ) { focused ->
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (focused) 1f else 0.75f }) {
                            if (!nextImage.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(nextImage).crossfade(true).build(),
                                    contentDescription = nextItem.seriesName ?: nextItem.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))),
                            )
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text("下一个", color = Accent, fontSize = 10.sp, maxLines = 1)
                                Text(
                                    nextItem.seriesName ?: nextItem.name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (focused) Box(Modifier.fillMaxSize().border(1.5.dp, Accent, RoundedCornerShape(9.dp)))
                        }
                    }
                }
                AnimatedVisibility(visible = focusedTargets.any { it.startsWith("indicator-") }) {
                    Text("${currentIndex + 1} / ${items.size}", color = TextSub, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun HeroBackdropImage(
    imageUrl: String,
    zoom: Float,
    wipeProgress: Float = 1f,
    isForward: Boolean = true,
    onLoaded: () -> Unit = {},
) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(imageUrl) {
            ImageRequest.Builder(context).data(imageUrl).crossfade(false).build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        onSuccess = { onLoaded() },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val z = 1f + zoom * 0.05f
                scaleX = z
                scaleY = z
                translationX = (if (isForward) 1f else -1f) * 18.dp.toPx() * (1f - wipeProgress)
                transformOrigin = TransformOrigin(0.5f, 0.32f)
                clip = true
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                if (wipeProgress <= 0f) return@drawWithContent
                drawContent()
                if (wipeProgress < 1f) {
                    val softEdge = 36.dp.toPx()
                    val edge = if (isForward) size.width * wipeProgress else size.width * (1f - wipeProgress)
                    val startX = if (isForward) (edge - softEdge).coerceAtLeast(0f) else edge
                    val endX = if (isForward) edge else (edge + softEdge).coerceAtMost(size.width)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = if (isForward) listOf(Color.White, Color.Transparent)
                            else listOf(Color.Transparent, Color.White),
                            startX = startX,
                            endX = endX.coerceAtLeast(startX + 0.1f),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    )
}

@Composable
private fun RowSection(
    title: String,
    items: List<MediaItem>,
    width: Dp = 170.dp,
    aspectRatio: Float = 1.5f,
    titleOverlay: Boolean = false,
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
        if (titleOverlay) {
            Text(title, Modifier.padding(start = 40.dp, bottom = 10.dp), color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        } else SectionTitle(title, Modifier.padding(start = 40.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            items(items, key = { it.id }) { it ->
                PosterCard(
                    item = it,
                    imageUrl = client?.imageUrl(it),
                    width = width,
                    aspectRatio = aspectRatio,
                    titleOverlay = titleOverlay,
                    focusScale = FocusScale.POSTER_MEDIUM,
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
