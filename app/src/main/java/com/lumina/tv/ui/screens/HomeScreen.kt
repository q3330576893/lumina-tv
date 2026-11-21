@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lumina.tv.ui.screens

import com.lumina.tv.data.catchingRequest

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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.TextStyle
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
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.ItemsPage
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.data.WideImageCandidate
import com.lumina.tv.data.wideImageCandidates
import com.lumina.tv.diag.PageLog
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.FocusScale
import com.lumina.tv.ui.components.GlassTone
import com.lumina.tv.ui.components.glassSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.ResumeRemovalPill
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withPermit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

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
    active: Boolean = true,
) {
    val client = AppRuntime.client
    val userDataRevision = LibraryCache.userDataRevision
    val context = LocalContext.current
    val prefetchSemaphore = remember { kotlinx.coroutines.sync.Semaphore(3) }
    val countSemaphore = remember { kotlinx.coroutines.sync.Semaphore(4) }
    val hazeState = remember { HazeState() }
    LaunchedEffect(context) {
        ThemeRuntime.updateDeviceBlurSupported(
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true,
        )
    }

    var loading by remember { mutableStateOf(LibraryCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var resumeItems by remember(userDataRevision) { mutableStateOf(LibraryCache.home?.resume ?: emptyList()) }
    var resumeFilterVersion by remember { mutableIntStateOf(0) }
    // SharedPreferences filtering runs only when these remember keys change.
    val resume = remember(resumeItems, resumeFilterVersion, client?.let { com.lumina.tv.data.identityScope(it.baseUrl, it.userId) }) {
        AppRuntime.prefs?.visibleResumeItems(resumeItems) ?: resumeItems
    }
    var pendingResumeRemoval by remember { mutableStateOf<MediaItem?>(null) }
    val removeFocus = remember { FocusRequester() }
    var latest by remember(userDataRevision) { mutableStateOf(LibraryCache.home?.latest ?: emptyList()) }
    var movies by remember(userDataRevision) { mutableStateOf(LibraryCache.home?.movies ?: emptyList()) }
    var series by remember(userDataRevision) { mutableStateOf(LibraryCache.home?.series ?: emptyList()) }
    var favorites by remember(userDataRevision) { mutableStateOf(LibraryCache.home?.favorites ?: emptyList()) }
    val favoritesPager = remember(client) {
        client?.let { c -> com.lumina.tv.data.FavoritesPager(
            com.lumina.tv.data.favoritesQueryKey(c.baseUrl, c.userId),
            currentRevision = { LibraryCache.userDataRevision }, fetch = c::getFavorites,
        ) }
    }
    val favoritesGridState = remember(favoritesPager) { LazyGridState() }
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
    val recListState = rememberLazyListState()
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val gridStateKey = when {
        tab == HomeTab.Media -> libraryBrowseKey(selectedLibraryId ?: "list", librarySort, librarySortOrder)
        else -> "tab:${tab.name}"
    }
    var gridItems by remember(gridStateKey, userDataRevision) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var libraryTotalCount by remember(gridStateKey, userDataRevision) { mutableStateOf<Int?>(null) }
    var gridLoading by remember(gridStateKey, userDataRevision) { mutableStateOf(true) }
    var gridRefreshing by remember(gridStateKey, userDataRevision) { mutableStateOf(false) }
    var gridAppending by remember(gridStateKey, userDataRevision) { mutableStateOf(false) }
    var gridNextIndex by remember(gridStateKey, userDataRevision) { mutableStateOf(0) }
    var gridHasMore by remember(gridStateKey, userDataRevision) { mutableStateOf(false) }
    var gridAppendFailed by remember(gridStateKey, userDataRevision) { mutableStateOf(false) }
    var gridRetryRequested by remember(gridStateKey, userDataRevision) { mutableIntStateOf(0) }
    var gridRetryHandled by remember(gridStateKey, userDataRevision) { mutableIntStateOf(0) }
    val gridState = remember(gridStateKey) {
        gridStates.getOrPut(gridStateKey) { LazyGridState() }
    }
    // 待恢复的滚动位置：null 表示没有待恢复目标（此时才允许把位置写回缓存）
    var pendingGridRestore by remember(gridStateKey, userDataRevision) { mutableStateOf(LibraryCache.gridPosition(gridStateKey)) }
    val homeExceptionHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            Log.e("HomeScreen", "Unhandled home coroutine failure", throwable)
        }
    }
    val scope = rememberCoroutineScope { homeExceptionHandler }
    val heroTopLogTimes = remember { mutableMapOf<String, Long>() }
    fun heroIsAtTop() = recListState.firstVisibleItemIndex == 0 && recListState.firstVisibleItemScrollOffset == 0
    suspend fun ensureHeroAtTop(source: String) {
        // scrollToItem already owns the list's scroll mutation.
        recListState.scrollToItem(0)
        val now = android.os.SystemClock.elapsedRealtime()
        val lastLoggedAt = heroTopLogTimes[source]
        if (lastLoggedAt == null || now - lastLoggedAt >= 300L) {
            heroTopLogTimes[source] = now
            PageLog.add("heroTop source=$source index=${recListState.firstVisibleItemIndex} offset=${recListState.firstVisibleItemScrollOffset}")
        }
    }
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
    LaunchedEffect(gridStateKey, gridState, pendingGridRestore, userDataRevision) {
        if (tab == HomeTab.Favorites) return@LaunchedEffect
        if (pendingGridRestore != null) return@LaunchedEffect
        snapshotFlow {
            Triple(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, gridItems.size)
        }.collect { (index, offset, loaded) ->
            if (gridItems.isNotEmpty()) LibraryCache.putGridPosition(gridStateKey, index, offset, loaded)
        }
    }

    // 恢复位置：条目覆盖目标索引后立即滚动，剩余分页后台补齐。
    LaunchedEffect(gridStateKey, gridState, pendingGridRestore, userDataRevision, selectedLibrary) {
        if (tab == HomeTab.Favorites) return@LaunchedEffect
        val target = pendingGridRestore ?: return@LaunchedEffect
        val (wantIndex, wantOffset, wantLoaded) = target
        snapshotFlow { gridLoading }.first { !it }
        if (gridItems.isEmpty()) {
            pendingGridRestore = null
            return@LaunchedEffect
        }
        // 收藏由 FavoritesPager 独立恢复；电影/剧集直接进入滚动恢复。
        while (selectedLibrary != null && librarySort != "random" && gridItems.size <= wantIndex && gridHasMore) {
            val lib = selectedLibrary
            if (client != null) {
                val (sb, so) = sortParams(librarySort, librarySortOrder)
                gridAppending = true
                try {
                    PageLog.add("libraryQuery path=restore SortBy=$sb SortOrder=$so start=$gridNextIndex limit=$LIBRARY_PAGE_SIZE")
                    val page = client.getItems(
                        parentId = lib.id, types = collectionTypeToItemTypes(lib.type),
                        sortBy = sb, sortOrder = so, limit = LIBRARY_PAGE_SIZE, startIndex = gridNextIndex,
                    )
                    currentCoroutineContext().ensureActive()
                    if (page.isNotEmpty()) {
                        gridItems = (gridItems + page).distinctBy { it.id }
                        val progress = libraryAppendProgress(gridNextIndex, gridHasMore, page.size)
                        gridNextIndex = progress.nextIndex
                        gridHasMore = progress.hasMore
                    } else {
                        gridHasMore = false
                        gridAppendFailed = false
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val progress = libraryAppendProgress(gridNextIndex, gridHasMore, pageSize = null)
                    gridNextIndex = progress.nextIndex
                    gridHasMore = progress.hasMore
                    gridAppendFailed = progress.failed
                    break
                } finally {
                    gridAppending = false
                }
            } else break
        }
        // 列表布局完成后再滚，必要时重试几帧（数据刚到的那一帧 LazyGrid 还没测量）
        PageLog.add("gridRestore phase=scroll index=$wantIndex")
        var attempt = 0
        while (attempt < 3) {
            runCatching { gridState.scrollToItem(wantIndex.coerceAtLeast(0), wantOffset.coerceAtLeast(0)) }
            withFrameNanos { }
            if (gridState.firstVisibleItemIndex == wantIndex) break
            attempt++
        }
        pendingGridRestore = null
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var backfillPages = 0
            while (selectedLibrary != null && librarySort != "random" && gridItems.size < wantLoaded && gridHasMore && backfillPages < 3) {
                val lib = selectedLibrary
                if (client != null) {
                    val (sb, so) = sortParams(librarySort, librarySortOrder)
                    backfillPages++
                    gridAppending = true
                    try {
                        PageLog.add("gridRestore phase=backfill loaded=${gridItems.size} want=$wantLoaded")
                        val page = client.getItems(
                            parentId = lib.id, types = collectionTypeToItemTypes(lib.type),
                            sortBy = sb, sortOrder = so, limit = LIBRARY_PAGE_SIZE, startIndex = gridNextIndex,
                        )
                        currentCoroutineContext().ensureActive()
                        if (page.isNotEmpty()) {
                            gridItems = (gridItems + page).distinctBy { it.id }
                            val progress = libraryAppendProgress(gridNextIndex, gridHasMore, page.size)
                            gridNextIndex = progress.nextIndex
                            gridHasMore = progress.hasMore
                        } else {
                            gridHasMore = false
                            gridAppendFailed = false
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        val progress = libraryAppendProgress(gridNextIndex, gridHasMore, pageSize = null)
                        gridNextIndex = progress.nextIndex
                        gridHasMore = progress.hasMore
                        gridAppendFailed = progress.failed
                        break
                    } finally {
                        gridAppending = false
                    }
                } else break
            }
        }
    }
    // 各媒体库视图的横向内容（id → items）
    var viewContents by remember(userDataRevision) { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    var initialFocusPending by rememberSaveable { mutableStateOf(true) }
    var focusedMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeRowFocused by remember { mutableStateOf(false) }
    var focusedMediaSection by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var heroIndex by rememberSaveable { mutableStateOf(0) }
    var unavailableHeroIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var heroHasFocus by remember { mutableStateOf(false) }
    var restoreHeroFocus by rememberSaveable { mutableStateOf(false) }
    var heroResetPending by remember { mutableStateOf(false) }
    var lastExitBackAt by remember { mutableStateOf(0L) }
    val inputModeManager = LocalInputModeManager.current

    LaunchedEffect(resume.isEmpty()) {
        if (resume.isEmpty()) resumeRowFocused = false
    }

    fun resetToHero() {
        focusedMediaId = null
        focusedMediaSection = null
        heroResetPending = true
        lastExitBackAt = 0L
    }

    // A genre is an in-page route: Android/TV Back returns to the genre list, then home.
    BackHandler(enabled = active) {
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
                if (!loading && error == null) resetToHero()
            }
            !loading && error == null && homeNeedsHeroReset(
                recListState.firstVisibleItemIndex,
                recListState.firstVisibleItemScrollOffset,
                inputModeManager.inputMode == InputMode.Keyboard,
                heroHasFocus,
                heroResetPending,
            ) -> resetToHero()
            else -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastExitBackAt != 0L && now - lastExitBackAt <= 2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastExitBackAt = now
                    android.widget.Toast.makeText(context, "\u518D\u6309\u4E00\u6B21\u9000\u51FA", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(reloadToken, client, userDataRevision) {
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
            resumeItems = cached.resume
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
                val rA = async { catchingRequest { c.getResume(20) } }
                val mA = async { catchingRequest { c.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) } }
                val sA = async { catchingRequest { c.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) } }
                val fA = async { catchingRequest { c.getFavorites(24, startIndex = 0).items } }
                val vA = async { catchingRequest { c.getViews() } }
                val lA = async { catchingRequest {
                    c.getLatest(null, 24).distinctBy { it.id }.filter { it.type != "Movie" && it.type != "Series" }
                        .ifEmpty { cached?.latest.orEmpty() }
                } }

                val results = listOf(rA.await(), mA.await(), sA.await(), fA.await(), vA.await(), lA.await())
                currentCoroutineContext().ensureActive()
                if (userDataRevision != LibraryCache.userDataRevision) return@coroutineScope
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
                    resumeItems = r; latest = l; movies = m; series = s; favorites = f; views = v
                    val updated = LibraryCache.HomeData(
                        resume = r, latest = l, movies = m, series = s,
                        favorites = f, genres = emptyList(), views = v, at = System.currentTimeMillis(),
                    )
                    LibraryCache.putHome(updated, userDataRevision)
                    session?.let { LibraryCache.writeHome(context, it.baseUrl, it.userId, updated, userDataRevision) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) error = e.message else offline = true
        } finally {
            loading = false
            c.requestProgressFlush()
        }
    }

    LaunchedEffect(views, client, reloadToken, userDataRevision) {
        val c = client ?: return@LaunchedEffect
        val visibleViews = views.filterNot { AppRuntime.prefs?.isViewHidden(it.id) ?: false }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val prefetchedViews = supervisorScope {
            visibleViews.map { view ->
                async {
                    val cacheKey = libraryBrowseKey(view.id, "date", "Descending")
                    val viewItems = try {
                        LibraryCache.browseOf(cacheKey) ?: prefetchSemaphore.withPermit {
                            c.getItems(
                                parentId = view.id,
                                types = collectionTypeToItemTypes(view.type),
                                sortBy = "DateCreated",
                                sortOrder = "Descending",
                                limit = 50,
                            ).also { LibraryCache.putBrowse(cacheKey, it, userDataRevision) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("LuminaTV", "Library row load failed: ${view.id}", e)
                        LibraryCache.browseOf(cacheKey).orEmpty()
                    }
                    val recentBackdropUrl = viewItems.firstNotNullOfOrNull { item -> c.backdropUrl(item, 640) }
                    viewContents = viewContents + (view.id to viewItems)
                    libraryRecentBackdropUrls = libraryRecentBackdropUrls + (view.id to recentBackdropUrl)
                    Triple(view, viewItems, recentBackdropUrl)
                }
            }.awaitAll()
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        val loadedCount = prefetchedViews.count { it.second.isNotEmpty() }
        PageLog.add("prefetch views=${views.size} limit=3 ms=${elapsed} loaded=${loadedCount}/${views.size}")
        viewContents = viewContents + prefetchedViews.associate { it.first.id to it.second }
    }

    LaunchedEffect(views, client, reloadToken) {
        val c = client ?: return@LaunchedEffect
        val counts = coroutineScope {
            views.map { view ->
                async {
                    view.id to catchingRequest { countSemaphore.withPermit { c.getLibraryItemCount(view.id) } }.getOrNull()
                }
            }.awaitAll()
        }
        libraryCounts = libraryCounts + counts
    }

    LaunchedEffect(resume, favorites, latest, movies, series, viewContents) {
        val c = client ?: return@LaunchedEffect
        val carouselItemIds = (latest + movies + series + favorites).mapTo(mutableSetOf()) { it.id }
        val preloadItems = (resume.take(8) + favorites.take(8) + latest.take(8) + movies.take(8) + series.take(8) +
            viewContents.values.flatMap { it.take(4) }).distinctBy { it.id }
        preloadItems.filterNot { it.id in carouselItemIds }.forEach { media ->
            val url = c.imageUrl(media) ?: return@forEach
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).crossfade(false).build()
            )
        }
    }

    // 切换标签：Movies/Series 直接复用首页已加载的数据，不再额外请求
    LaunchedEffect(tab, selectedLibrary, gridStateKey, userDataRevision) {
        if (tab == HomeTab.Favorites) return@LaunchedEffect
        val requestTab = tab
        val requestLibrary = selectedLibrary
        val requestSort = librarySort
        val requestOrder = librarySortOrder
        gridLoading = true
        libraryError = null
        if (requestTab == HomeTab.Media && requestLibrary != null) {
            gridNextIndex = 0
            gridHasMore = false
            gridAppendFailed = false
        }
        if (requestTab == HomeTab.Recommend || (requestTab == HomeTab.Media && selectedLibraryId == null)) {
            gridLoading = false
            return@LaunchedEffect
        }
        // A saved library id can precede the views response. Keep restoration waiting for its first page.
        if (requestTab == HomeTab.Media && requestLibrary == null) return@LaunchedEffect
        val c = client ?: run { gridLoading = false; return@LaunchedEffect }
        if (requestTab == HomeTab.Movies && movies.isNotEmpty()) {
            gridItems = movies
            gridLoading = false
            gridRefreshing = false
            return@LaunchedEffect
        }
        if (requestTab == HomeTab.Series && series.isNotEmpty()) {
            gridItems = series
            gridLoading = false
            gridRefreshing = false
            return@LaunchedEffect
        }
        val key = when (requestTab) {
            HomeTab.Movies -> "movies"
            HomeTab.Series -> "series"
            HomeTab.Media -> libraryBrowseKey(requestLibrary?.id.orEmpty(), requestSort, requestOrder)
            else -> return@LaunchedEffect
        }
        LibraryCache.browseOf(key)?.let {
            gridItems = it
            if (requestTab == HomeTab.Media) {
                libraryTotalCount = requestLibrary?.let { libraryCounts[it.id] }
                gridNextIndex = it.size
                gridHasMore = requestSort != "random" && it.size == LIBRARY_PAGE_SIZE
                gridAppendFailed = false
            }
            gridLoading = false
            gridRefreshing = false
            return@LaunchedEffect
        }
        gridLoading = requestTab == HomeTab.Media || gridItems.isEmpty()
        if (requestTab == HomeTab.Media && gridItems.isNotEmpty()) gridRefreshing = true
        try {
            val result = runCatching {
                when (requestTab) {
                    HomeTab.Movies -> c.getItemsPage(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Series -> c.getItemsPage(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 120)
                    HomeTab.Media -> {
                        val library = checkNotNull(requestLibrary)
                        val (sb, so) = sortParams(requestSort, requestOrder)
                        PageLog.add("libraryQuery path=initial SortBy=$sb SortOrder=$so start=0 limit=$LIBRARY_PAGE_SIZE")
                        c.getItemsPage(parentId = library.id, types = collectionTypeToItemTypes(library.type),
                            sortBy = sb, sortOrder = so, limit = LIBRARY_PAGE_SIZE, startIndex = 0)
                    }
                    else -> ItemsPage(emptyList(), null)
                }
            }
            currentCoroutineContext().ensureActive()
            if (userDataRevision != LibraryCache.userDataRevision) return@LaunchedEffect
            if (tab != requestTab || selectedLibrary != requestLibrary || librarySort != requestSort || librarySortOrder != requestOrder) return@LaunchedEffect
            result.onSuccess { (list, totalCount) ->
                LibraryCache.putBrowse(key, list, userDataRevision, fixedSample = requestTab == HomeTab.Media && requestSort == "random")
                if (requestTab == HomeTab.Media) {
                    gridItems = list
                    libraryTotalCount = totalCount
                    gridNextIndex = list.size
                    gridHasMore = requestSort != "random" && list.size == LIBRARY_PAGE_SIZE
                    gridAppendFailed = false
                } else {
                    gridItems = list
                }
            }.onFailure { failure ->
                libraryError = failure.message ?: "媒体库读取失败"
                gridItems = emptyList()
            }
        } finally {
            if (tab == requestTab && selectedLibrary == requestLibrary && librarySort == requestSort && librarySortOrder == requestOrder) {
                gridLoading = false
                gridRefreshing = false
            }
        }
    }

    LaunchedEffect(selectedLibrary, gridStateKey, userDataRevision, gridRetryRequested) {
        if (tab == HomeTab.Favorites) return@LaunchedEffect
        if (selectedLibrary == null || librarySort == "random") return@LaunchedEffect
        val activeSort = librarySort
        val activeOrder = librarySortOrder
        snapshotFlow {
            val info = gridState.layoutInfo
            Triple(info.visibleItemsInfo.lastOrNull()?.index ?: -1, gridItems.size, gridLoading || pendingGridRestore != null)
        }.collect { (lastVisible, _, waiting) ->
            if (!waiting && lastVisible >= gridItems.lastIndex - 8 && gridHasMore && !gridLoading && !gridAppending &&
                (!gridAppendFailed || shouldRetryLibraryAppend(gridAppendFailed, gridRetryRequested, gridRetryHandled))
            ) {
                val c = client ?: return@collect
                val library = selectedLibrary ?: return@collect
                val (sortBy, sortOrder) = sortParams(activeSort, activeOrder)
                gridAppending = true
                gridRetryHandled = gridRetryRequested
                try {
                    PageLog.add("libraryQuery path=append SortBy=$sortBy SortOrder=$sortOrder start=$gridNextIndex limit=$LIBRARY_PAGE_SIZE")
                    val page = c.getItems(parentId = library.id, types = collectionTypeToItemTypes(library.type),
                        sortBy = sortBy, sortOrder = sortOrder,
                        limit = LIBRARY_PAGE_SIZE, startIndex = gridNextIndex)
                    currentCoroutineContext().ensureActive()
                    if (selectedLibrary == library && librarySort == activeSort && librarySortOrder == activeOrder) {
                        gridItems = (gridItems + page).distinctBy { it.id }
                        val progress = libraryAppendProgress(gridNextIndex, gridHasMore, page.size)
                        gridNextIndex = progress.nextIndex
                        gridHasMore = progress.hasMore
                        gridAppendFailed = false
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val progress = libraryAppendProgress(gridNextIndex, gridHasMore, pageSize = null)
                    gridNextIndex = progress.nextIndex
                    gridHasMore = progress.hasMore
                    gridAppendFailed = progress.failed
                } finally {
                    gridAppending = false
                }
            }
        }
    }

    // 媒体库视图内容：启动后即在后台预热拉取各视图最近内容，无需等切换Tab
    LaunchedEffect(views, reloadToken, userDataRevision) {
        val missing = views.filter { v ->
            !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) && viewContents[v.id] == null
        }
        val cached = missing.mapNotNull { view ->
            LibraryCache.browseOf(libraryBrowseKey(view.id, "date", "Descending"))?.let { view.id to it }
        }.toMap()
        if (cached.isNotEmpty()) viewContents = viewContents + cached
    }

    val heroItems = rememberHomeHeroItems(latest, movies, series, favorites, unavailableHeroIds, ::pickHeroItems)
    LaunchedEffect(heroItems.size) {
        if (heroItems.isEmpty()) heroIndex = 0
        else if (heroIndex > heroItems.lastIndex) heroIndex = heroItems.lastIndex
    }
    val hiddenHomeViewIds = AppRuntime.prefs?.hiddenViewIds.orEmpty()
    val homeLibraries = remember(views, hiddenHomeViewIds) {
        views.filterNot { it.id in hiddenHomeViewIds }
    }
    val firstSectionKey: String? = when {
        resume.isEmpty() && favorites.isEmpty() -> "empty"
        resume.isNotEmpty() -> "resume"
        homeLibraries.isNotEmpty() -> "library-${homeLibraries.first().id}"
        favorites.isNotEmpty() -> "favorites"
        else -> homeLibraries.firstNotNullOfOrNull { view ->
            viewContents[view.id]?.takeIf { it.isNotEmpty() }?.let { "view-${view.id}" }
        }
    }
    val navFocus = remember { FocusRequester() }
    val libraryBackFocus = remember { FocusRequester() }
    val librarySortFocus = remember { FocusRequester() }
    val favoritesContentFocus = remember { FocusRequester() }
    val mediaLibraryContentFocus = remember { FocusRequester() }

    val heroArtworkFocus = remember { FocusRequester() }
    val firstContentFocus = remember { FocusRequester() }
    val firstContentListIndex = remember(resume, homeLibraries, favorites, viewContents, views) {
        var index = 1 // Hero
        when {
            resume.isEmpty() && favorites.isEmpty() -> index // Empty-state action
            resume.isNotEmpty() -> index
            homeLibraries.isNotEmpty() -> index
            favorites.isNotEmpty() -> index
            else -> {
                index += if (homeLibraries.isNotEmpty()) 1 else 0
                if (favorites.isNotEmpty()) index++
                val firstView = views.firstOrNull { view ->
                    !(AppRuntime.prefs?.isViewHidden(view.id) ?: false) && viewContents[view.id]?.isNotEmpty() == true
                }
                if (firstView == null) -1 else {
                    val preceding = views.filterNot { AppRuntime.prefs?.isViewHidden(it.id) ?: false }
                        .takeWhile { it.id != firstView.id }
                    index + preceding.size
                }
            }
        }
    }
    val hasContentTarget = when (tab) {
        HomeTab.Recommend -> firstSectionKey != null
        HomeTab.Favorites -> favoritesPager?.state?.items?.isNotEmpty() == true
        HomeTab.Media -> selectedLibraryId == null && views.any { !(AppRuntime.prefs?.isViewHidden(it.id) ?: false) }
        else -> false
    }
    fun navigateToFirstContent(): Boolean {
        if (!hasContentTarget) return false
        val targetFocus = when (tab) {
            HomeTab.Recommend -> firstContentFocus
            HomeTab.Favorites -> favoritesContentFocus
            HomeTab.Media -> mediaLibraryContentFocus
            else -> return false
        }
        if (tab == HomeTab.Recommend) {
            focusedMediaId = null
            focusedMediaSection = null
        }
        if (runCatching { targetFocus.requestFocus() }.isSuccess) return true

        scope.launch {
            when (tab) {
                HomeTab.Recommend -> {
                    if (firstContentListIndex >= 0) {
                        recListState.scrollToItem(firstContentListIndex)
                        repeat(6) {
                            withFrameNanos { }
                            if (runCatching { firstContentFocus.requestFocus() }.isSuccess) return@launch
                            delay(40)
                        }
                    }
                    resetToHero()
                }
                HomeTab.Favorites -> {
                    favoritesGridState.scrollToItem(0)
                    withFrameNanos { }
                    withFrameNanos { }
                    runCatching { favoritesContentFocus.requestFocus() }
                }
                HomeTab.Media -> {
                    withFrameNanos { }
                    withFrameNanos { }
                    runCatching { mediaLibraryContentFocus.requestFocus() }
                }
                else -> Unit
            }
        }
        return true
    }
    val navContentFocus = when (tab) {
        HomeTab.Recommend -> heroArtworkFocus
        HomeTab.Favorites -> if (hasContentTarget) favoritesContentFocus else FocusRequester.Default
        HomeTab.Media -> if (selectedLibraryId == null && views.any { !(AppRuntime.prefs?.isViewHidden(it.id) ?: false) }) mediaLibraryContentFocus else FocusRequester.Default
        else -> FocusRequester.Default
    }

    LaunchedEffect(active, loading, error, tab, initialFocusPending, restoreHeroFocus, heroResetPending) {
        if (active && !loading && error == null && tab == HomeTab.Recommend &&
            (initialFocusPending || restoreHeroFocus || heroResetPending)
        ) {
            val source = if (heroResetPending) "back" else if (restoreHeroFocus) "return" else "initial"
            ensureHeroAtTop(source)
            withFrameNanos { }
            if (inputModeManager.inputMode == InputMode.Keyboard) runCatching { heroArtworkFocus.requestFocus() }
            initialFocusPending = false
            restoreHeroFocus = false
            heroResetPending = false
        }
    }

    // 导航在顶部，右键不再劫持（原来的右键跳竖栏逻辑会让影片行无法继续右移）
    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
            val inResume = resumeRowFocused || (focusedMediaId != null && resume.any { it.id == focusedMediaId })
            if (inResume) false else {
                runCatching { navFocus.requestFocus() }
                true
            }
        } else false
    }) {
        when {
            tab == HomeTab.Favorites && client != null && favoritesPager != null -> FavoritesScreen(
                client, favoritesPager, favoritesGridState, favorites, userDataRevision,
                favoritesContentFocus, onOpenDetail,
            )
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
                tab == HomeTab.Recommend -> BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
                    val heroWidth = maxWidth
                    val heroHeight = minOf(maxHeight * 0.86f, maxHeight - 172.dp).coerceAtLeast(320.dp)
                    LazyColumn(
                        state = recListState,
                        modifier = Modifier.fillMaxSize().background(Ink),
                    ) {
                    item(key = "home-hero") {
                        Hero(
                            items = heroItems,
                            active = active,
                            heroWidth = heroWidth,
                            heroHeight = heroHeight,
                            currentIndex = heroIndex,
                            onPrevious = {
                                if (heroItems.isNotEmpty()) {
                                    heroIndex = (heroIndex - 1 + heroItems.size) % heroItems.size
                                }
                            },
                            onNext = {
                                if (heroItems.isNotEmpty()) {
                                    heroIndex = (heroIndex + 1) % heroItems.size
                                }
                            },
                            onPlay = { restoreHeroFocus = true; onPlay(it) },
                            onOpenDetail = { restoreHeroFocus = true; onOpenDetail(it) },
                            artworkFocus = heroArtworkFocus,
                            navFocus = navFocus,
                            onNavigateDown = ::navigateToFirstContent,
                            onHeroFocusChanged = {
                                heroHasFocus = it
                                if (it && !heroIsAtTop() && !heroResetPending) {
                                    scope.launch { ensureHeroAtTop("focus") }
                                }
                            },
                            onItemUnavailable = { itemId -> unavailableHeroIds = unavailableHeroIds + itemId },
                            hazeState = hazeState,
                        )
                    }
                    if (!loading && resume.isEmpty() && favorites.isEmpty()) {
                        item {
                            FocusSurface(
                                modifier = Modifier.padding(horizontal = 34.dp, vertical = 12.dp)
                                    .fillMaxWidth().height(56.dp).focusProperties { up = heroArtworkFocus },
                                focusRequester = if (firstSectionKey == "empty") firstContentFocus else null,
                                corner = 28.dp,
                                scaleTo = 1.02f,
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
                    if (resume.isNotEmpty()) item { RowSection("继续观看", resume, width = 170.dp, titleOverlay = true, topPadding = 12.dp, requestInitialFocus = focusedMediaId != null && firstSectionKey == "resume", focusItemId = focusedMediaId, sectionKey = "resume", focusedSection = focusedMediaSection, firstItemFocus = if (firstSectionKey == "resume") firstContentFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "resume" }, onRowFocusChanged = { resumeRowFocused = it }, onItemMenu = { pendingResumeRemoval = it }) { onOpenDetail(it.seriesId ?: it.id) } }
                    if (homeLibraries.isNotEmpty()) item(key = "home-libraries") {
                        HomeLibraryRow(
                            views = homeLibraries,
                            contents = viewContents,
                            counts = libraryCounts,
                            firstItemFocus = if (firstSectionKey == "library-${homeLibraries.first().id}") firstContentFocus else null,
                            onOpenLibrary = { tab = HomeTab.Media; selectedLibraryId = it.id; focusedLibraryId = it.id; focusedMediaId = null; LibraryCache.clearGridPosition(libraryBrowseKey(it.id, librarySort, librarySortOrder)) },
                        )
                    }
                    if (favorites.isNotEmpty()) item { RowSection("我的收藏", favorites, width = 130.dp, requestInitialFocus = focusedMediaId != null && firstSectionKey == "favorites", focusItemId = focusedMediaId, sectionKey = "favorites", focusedSection = focusedMediaSection, firstItemFocus = if (firstSectionKey == "favorites") firstContentFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "favorites" }) { onOpenDetail(it.id) } }
                    // 媒体库全分类直接融入首页推荐流，支持无限往下滑
                    val enabledViews = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) }
                    enabledViews.forEach { v ->
                        val items = viewContents[v.id]
                        if (items == null) {
                            item(key = "home-view-skeleton-${v.id}") {
                                HomeRowSkeleton(v.name)
                            }
                        } else if (items.isNotEmpty()) {
                            item(key = "home-view-${v.id}") {
                                RowSection(v.name, items, requestInitialFocus = focusedMediaId != null && firstSectionKey == "view-" + v.id, focusItemId = focusedMediaId, sectionKey = "view-" + v.id, focusedSection = focusedMediaSection, firstItemFocus = if (firstSectionKey == "view-${v.id}") firstContentFocus else null, onItemFocused = { focusedMediaId = it; focusedMediaSection = "view-" + v.id }) { onOpenDetail(it.id) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(140.dp)) }
                    }
                }
                tab == HomeTab.Media && selectedLibrary == null -> MediaLibrary(
                    views = views.filter { v -> !(AppRuntime.prefs?.isViewHidden(v.id) ?: false) },
                    contents = viewContents,
                    counts = libraryCounts,
                    focusedLibraryId = focusedLibraryId,
                    onLibraryFocused = { focusedLibraryId = it },
                    onOpenLibrary = { selectedLibraryId = it.id; focusedLibraryId = it.id; focusedMediaId = null; LibraryCache.clearGridPosition(libraryBrowseKey(it.id, librarySort, librarySortOrder)) },
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
                            Text(if (librarySort == "random") "随机固定样本 · ${gridItems.size}/最多 50 部（非全库）"
                                else libraryTotalCount?.let { "共 $it 部" } ?: "已加载 ${gridItems.size} 部", color = TextMain.copy(alpha = 0.6f), fontSize = 11.sp)
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
                                        if (gridRefreshing) Text("刷新中", color = Accent, fontSize = 9.sp, lineHeight = 10.sp)
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
                                                        if (librarySort == key) { closeSortMenu(); return@clickable }
                                                        if (librarySort == "random") LibraryCache.removeBrowse(gridStateKey)
                                                        val nextKey = libraryBrowseKey(selectedLibraryId.orEmpty(), key, librarySortOrder)
                                                        LibraryCache.clearGridPosition(nextKey)
                                                        gridStates.remove(nextKey)
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
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(Color(0x26FFFFFF)))
                                        if (librarySort != "random") listOf("Descending" to "降序 ↓", "Ascending" to "升序 ↑").forEach { (order, label) ->
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
                                                        if (librarySortOrder == order) { closeSortMenu(); return@clickable }
                                                        val nextKey = libraryBrowseKey(selectedLibraryId.orEmpty(), librarySort, order)
                                                        LibraryCache.clearGridPosition(nextKey)
                                                        gridStates.remove(nextKey)
                                                        librarySortOrder = order
                                                        AppRuntime.prefs?.librarySortOrder = order
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
                    else BoxWithConstraints {
                        val libraryGridColumns = when {
                            maxWidth < 1400.dp -> 5
                            maxWidth < 2400.dp -> 7
                            else -> 9
                        }
                        val cardWidth = (maxWidth - 40.dp * 2 - 20.dp * (libraryGridColumns - 1)) / libraryGridColumns
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(libraryGridColumns),
                            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 160.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalArrangement = Arrangement.spacedBy(32.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(gridItems, key = { _, item -> item.id }) { index, media ->
                                val shouldRequestInitialFocus = remember(media.id) {
                                    derivedStateOf {
                                        focusedMediaId == media.id || (initialFocusPending && focusedMediaId == null && index == 0)
                                    }
                                }
                                PosterCard(
                                    item = media,
                                    imageUrl = client?.imageUrl(media),
                                    width = cardWidth,
                                    requestInitialFocus = shouldRequestInitialFocus.value,
                                    upFocus = if (index < libraryGridColumns) librarySortFocus else null,
                                    onFocused = { focusedMediaId = media.id },
                                    onClick = { onOpenDetail(media.id) },
                                )
                            }
                            if (gridAppending) item(span = { GridItemSpan(maxLineSpan) }) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), color = Accent, strokeWidth = 2.dp)
                            }
                            if (gridAppendFailed && gridHasMore && !gridAppending) item(span = { GridItemSpan(maxLineSpan) }) {
                                FocusSurface(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    onClick = { gridRetryRequested++ },
                                ) { focused ->
                                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x22FFFFFF) else Color.Transparent), contentAlignment = Alignment.Center) {
                                        Text("加载失败，按 OK 重试", color = TextMain, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                gridLoading -> LoadingView(text = "正在加载…")
                gridItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还没有内容", color = TextSub, fontSize = 13.sp)
                }
                else -> BoxWithConstraints {
                    val gridColumns = when {
                        maxWidth < 1400.dp -> 5
                        maxWidth < 2400.dp -> 7
                        else -> 9
                    }
                    val cardWidth = (maxWidth - 40.dp * 2 - 20.dp * (gridColumns - 1)) / gridColumns
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 76.dp, bottom = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(gridItems, key = { _, item -> item.id }) { index, it ->
                            val shouldRequestInitialFocus = remember(it.id) {
                                derivedStateOf {
                                    focusedMediaId == it.id || (initialFocusPending && focusedMediaId == null && it == gridItems.firstOrNull())
                                }
                            }
                            PosterCard(
                                item = it,
                                imageUrl = client?.imageUrl(it),
                                width = cardWidth,
                                requestInitialFocus = shouldRequestInitialFocus.value,
                                focusRequester = if (tab == HomeTab.Favorites && index == 0) favoritesContentFocus else null,
                                onFocused = { focusedMediaId = it.id },
                                onClick = { onOpenDetail(it.id) },
                            )
                        }
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
                    resumeItems = emptyList()
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
                onNavigateDown = {
                    if (tab == HomeTab.Recommend && !loading && error == null) {
                        resetToHero()
                        true
                    } else navigateToFirstContent()
                },
                hazeState = hazeState,
            )
        LaunchedEffect(pendingResumeRemoval?.id) {
            if (pendingResumeRemoval != null) {
                withFrameNanos { }
                runCatching { removeFocus.requestFocus() }
            }
        }
        pendingResumeRemoval?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingResumeRemoval = null },
                title = { Text("从继续观看中移除") },
                text = {
                    Column {
                        Text("《${item.name}》")
                        Text("只从这一行移除，不影响播放记录", color = TextSub, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    ResumeRemovalPill(
                        focusRequester = removeFocus,
                        onClick = {
                            val oldIndex = resume.indexOfFirst { it.id == item.id }
                            AppRuntime.prefs?.hideResumeItem(item)
                            val nextFocus = resume.getOrNull(oldIndex + 1) ?: resume.getOrNull(oldIndex - 1)
                            focusedMediaId = nextFocus?.id
                            focusedMediaSection = nextFocus?.let { "resume" }
                            pendingResumeRemoval = null
                            resumeFilterVersion++
                            scope.launch {
                                withFrameNanos { }
                                if (nextFocus == null) resetToHero()
                            }
                        }
                    )
                },
                dismissButton = { TextButton(onClick = { pendingResumeRemoval = null }) { Text("取消") } },
            )
        }
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
    onNavigateDown: () -> Boolean,
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
            onNavigateDown = onNavigateDown,
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
                                                    prefs.prepareServerLogin(server.url, server.userName)
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
    onNavigateDown: () -> Boolean,
    hazeState: HazeState,
) {
    val entries = listOf(
        Triple("首页", Icons.Rounded.Home, tab == HomeTab.Recommend),
        Triple("收藏", Icons.Rounded.Favorite, tab == HomeTab.Favorites),
        Triple("媒体库", Icons.Rounded.Movie, tab == HomeTab.Media),
        Triple("搜索", Icons.Rounded.Search, false),
        Triple("设置", Icons.Rounded.Settings, false),
    )
    Row(
        modifier
            .height(60.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, (label, icon, active) ->
            Box(Modifier.width(56.dp).height(48.dp)) {
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
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            onNavigateDown()
                        } else false
                    },
                corner = 24.dp,
                scaleTo = 1.08f,
                onClick = {
                    when (index) {
                        0 -> onSelect(HomeTab.Recommend, null)
                        1 -> onSelect(HomeTab.Favorites, null)
                        2 -> onSelect(HomeTab.Media, null)
                        3 -> onSearch()
                        4 -> onSettings()
                    }
                },
            ) { focused ->
                focusedState = focused
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent, RoundedCornerShape(24.dp))
                        .then(if (focused) Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(24.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = label, tint = if (active || focused) Accent else Color(0xFFD6DEEB), modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(16.dp).height(3.dp).background(if (active) Accent.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(2.dp)))
                    }
                }
            }
            if (labelVisible) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, with(LocalDensity.current) { 84.dp.roundToPx() }),
                    properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
                ) {
                    Text(
                        label,
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(8.dp))
                            .background(Color(0xE610141D), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
            }
            if (index == 2) {
                Spacer(Modifier.width(6.dp))
                Spacer(Modifier.width(1.dp).height(16.dp).background(Color(0x26FFFFFF)))
                Spacer(Modifier.width(6.dp))
            } else if (index < entries.lastIndex) Spacer(Modifier.width(8.dp))
        }
    }
}

/** 内部排序键 → Emby SortBy/SortOrder，多处复用避免各写一份。 */
internal fun sortParams(sort: String, order: String): Pair<String, String> = when (sort) {
    "name" -> "SortName" to order
    "rating" -> "CommunityRating" to order
    "year" -> "ProductionYear" to order
    "plays" -> "PlayCount" to order
    "runtime" -> "Runtime" to order
    "random" -> "Random" to "Ascending"
    else -> "DateCreated" to order
}

internal fun libraryBrowseKey(libraryId: String, sort: String, order: String): String {
    val (sortBy, sortOrder) = sortParams(sort, order)
    return "library:$libraryId:$sortBy:$sortOrder"
}

private val SORT_LABELS = linkedMapOf(
    "date" to "最新添加",
    "name" to "名称",
    "rating" to "评分",
    "year" to "年份",
    "plays" to "播放次数",
    "runtime" to "时长",
    "random" to "随机样本（最多50部）",
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
    firstItemFocus: FocusRequester?,
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
                    focusRequester = if (view == views.firstOrNull()) firstItemFocus else null,
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
                        Text(
                            libraryCategoryCountLabel(counts[view.id], contents[view.id].orEmpty().size),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                        )
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
    counts: Map<String, Int?>,
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
                                    libraryCategoryCountLabel(counts[v.id], libraryItems.size),
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

internal fun libraryCategoryCountLabel(totalCount: Int?, sampleCount: Int): String =
    totalCount?.let { "共 $it 部" }
        ?: sampleCount.takeIf { it > 0 }?.let { "预览 $it 部" }
        ?: "数量未知"

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
 * 轮播选片：按小时打乱前 40 个候选，并降低最近展示条目的权重。
 */
private val recentlyShownHeroIds = mutableListOf<String>()

internal fun pickHeroItems(pool: List<MediaItem>, recentIds: Set<String>, size: Int = 6): List<MediaItem> {
    if (pool.isEmpty() || size <= 0) return emptyList()
    val seed = System.currentTimeMillis() / 3_600_000L
    val shownIds = synchronized(recentlyShownHeroIds) { recentlyShownHeroIds.toSet() }
    fun score(item: MediaItem): Double {
        var s = 0.0
        if (item.backdropTags.isNotEmpty() || !item.backdropTag.isNullOrBlank()) s += 3.0
        if (!item.logoTag.isNullOrBlank()) s += 2.0
        if (item.positionTicks > 0 && !item.played) s += 3.0
        if (item.id in recentIds) s += 2.0
        if (item.id in shownIds) s -= 4.0
        if (item.played) s -= 2.0
        // 60 分钟以内多半是花絮/预告，不该占轮播位
        val minutes = (item.runtimeTicks ?: 0L) / 600_000_000L
        if (minutes in 1..59) s -= 3.0
        val rating = item.communityRating ?: item.rating ?: 0.0
        s += (rating / 3.0).coerceAtMost(3.0)
        return s
    }
    val ranked = pool.sortedByDescending { score(it) }
    val candidates = ranked.take(40).shuffled(java.util.Random(seed))
    val picked = mutableListOf<MediaItem>()
    val remaining = candidates.toMutableList()
    while (picked.size < size && remaining.isNotEmpty()) {
        val previousGenre = picked.lastOrNull()?.genres?.firstOrNull()?.trim()?.lowercase()
        val nextIndex = if (previousGenre == null) 0 else remaining.indexOfFirst {
            it.genres.firstOrNull()?.trim()?.lowercase() != previousGenre
        }.takeIf { it >= 0 } ?: 0
        picked += remaining.removeAt(nextIndex)
    }
    synchronized(recentlyShownHeroIds) {
        recentlyShownHeroIds.addAll(picked.map { it.id })
        while (recentlyShownHeroIds.size > 12) recentlyShownHeroIds.removeAt(0)
    }
    PageLog.add("heroPool candidates=${pool.size} ranked=${candidates.size} excluded=${(pool.size - picked.size).coerceAtLeast(0)} top3=${picked.take(3).joinToString(",") { it.id }}")
    return picked
}

@Composable
private fun Hero(
    items: List<MediaItem>,
    heroWidth: Dp,
    heroHeight: Dp,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (String) -> Unit,
    artworkFocus: FocusRequester,
    navFocus: FocusRequester,
    onNavigateDown: () -> Boolean,
    onHeroFocusChanged: (Boolean) -> Unit,
    onItemUnavailable: (String) -> Unit,
    hazeState: HazeState,
    active: Boolean = true,
) {
    val item = items.getOrNull(currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    if (item == null) {
        Box(Modifier.fillMaxWidth().height(heroHeight).background(Ink)
            .focusRequester(artworkFocus)
            .focusProperties { up = navFocus }
            .onFocusChanged { onHeroFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onNavigateDown() }
            .focusable()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(InkSoft, Ink))))
            Text("暂无横版剧照", Modifier.align(Alignment.Center), color = TextSub, fontSize = 13.sp)
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val transitionScope = rememberCoroutineScope()
    val client = AppRuntime.client
    val playFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val compact = heroWidth < 720.dp
    val horizontalInset = if (compact) 20.dp else 40.dp
    val bottomInset = if (compact) 20.dp else 28.dp
    val titleWidth = if (compact) (heroWidth - horizontalInset * 2).coerceAtLeast(0.dp) else heroWidth * 0.36f
    val actionWidth = ((titleWidth - 12.dp) / 2).coerceAtLeast(0.dp)
    val thumbnailWidth = if (compact) minOf(64.dp, heroWidth * 0.21f) else minOf(112.dp, heroWidth * 0.14f)
    var focusedHeroAction by remember { mutableStateOf(HeroActionFocus.NONE) }
    var tmdbBackdrops by remember(items) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tmdbResolvedIds by remember(items) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(items, active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (tmdbResolvedIds.isNotEmpty()) return@repeatOnLifecycle
            val pairs = items.map { media ->
                async {
                    media.id to catchingRequest {
                        TmdbClient.lookupHero(
                            media.type,
                            media.seriesName ?: media.name,
                            media.year,
                            media.providerIds["OriginalTitle"],
                        )?.backdropUrl
                    }.getOrNull()
                }
            }.awaitAll()
            tmdbBackdrops = pairs.mapNotNull { (id, url) ->
                url?.takeIf(String::isNotBlank)?.let { id to it }
            }.toMap()
            tmdbResolvedIds = pairs.mapTo(mutableSetOf()) { it.first }
            PageLog.add("heroBackdrop have=${tmdbBackdrops.size}/${items.size}")
        }
    }

    fun heroImageCandidates(heroItem: MediaItem?): List<WideImageCandidate> = heroItem?.let { media ->
        client?.wideImageCandidates(media, maxWidth = 1280, quality = 80, tmdbUrl = tmdbBackdrops[media.id]).orEmpty()
    }.orEmpty()
    val heroCandidatesById = remember(items, client, tmdbBackdrops) {
        items.associate { it.id to heroImageCandidates(it) }
    }
    var failedHeroImages by remember(client) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var failedThumbImages by remember(client) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    val lastHeroImageLogs = remember(client) { mutableMapOf<String, Pair<String, Boolean>>() }
    fun isRejectedHeroImage(image: String?): Boolean =
        image != null && failedHeroImages.values.any { image in it }
    fun resolveHeroCandidate(heroItem: MediaItem?): WideImageCandidate? = heroItem?.let { media ->
        heroCandidatesById[media.id].orEmpty().firstOrNull { !isRejectedHeroImage(it.url) }
    }
    fun resolveHeroImage(heroItem: MediaItem?): String? = resolveHeroCandidate(heroItem)?.url
    fun logHeroImage(media: MediaItem, image: String, success: Boolean) {
        // A successful top image becomes the bottom layer; do not log that same load twice.
        val event = image to success
        if (lastHeroImageLogs[media.id] == event) return
        lastHeroImageLogs[media.id] = event
        val cands = heroCandidatesById[media.id].orEmpty()
        val k = cands.indexOfFirst { it.url == image }
        val source = cands.getOrNull(k)?.source ?: "unknown"
        val maxWidth = 1280
        PageLog.add("heroImg cands=${cands.size} pick=$k src=$source w=$maxWidth ok=$success")
    }
    fun rejectHeroImage(media: MediaItem, image: String) {
        logHeroImage(media, image, false)
        failedHeroImages = failedHeroImages + (media.id to (failedHeroImages[media.id].orEmpty() + image))
    }
    fun heroImageLoaded(media: MediaItem, image: String, width: Int, height: Int) {
        val source = heroCandidatesById[media.id].orEmpty().firstOrNull { it.url == image }?.source
        if (width <= 0 || height <= 0 || (source != "poster" && width <= height)) {
            rejectHeroImage(media, image)
        } else {
            logHeroImage(media, image, true)
        }
    }
    val wipeProgress = remember(items, client) { Animatable(1f) }
    var bottomImage by remember(items, client) { mutableStateOf(resolveHeroImage(item)) }
    var topImage by remember(items, client) { mutableStateOf<String?>(null) }
    var topLoaded by remember(items, client) { mutableStateOf(false) }
    var isForward by remember(items) { mutableStateOf(true) }
    var lastRequestedIndex by remember(items) { mutableStateOf(currentIndex) }
    var transitionGeneration by remember(items, client) { mutableStateOf(0) }
    var transitionJob by remember(items, client) { mutableStateOf<Job?>(null) }

    LaunchedEffect(currentIndex, items, client, tmdbBackdrops, failedHeroImages, tmdbResolvedIds) {
        val targetItem = items.getOrNull(currentIndex)
        val targetImage = resolveHeroImage(targetItem)
        if (isRejectedHeroImage(bottomImage)) bottomImage = null
        if (targetImage == null || isRejectedHeroImage(topImage)) {
            // A rejected top candidate must not leave the wipe at zero over an empty layer.
            transitionJob?.cancel()
            transitionGeneration += 1
            topImage = null
            topLoaded = false
            wipeProgress.snapTo(1f)
        }
        if (targetImage == null) {
            if (targetItem != null && targetItem.id in tmdbResolvedIds) onItemUnavailable(targetItem.id)
            lastRequestedIndex = currentIndex
            return@LaunchedEffect
        }
        if (targetImage == topImage || (topImage == null && targetImage == bottomImage)) {
            lastRequestedIndex = currentIndex
            return@LaunchedEffect
        }

        transitionJob?.cancel()
        transitionGeneration += 1
        if (topLoaded && wipeProgress.value > 0f) {
            topImage?.let {
                bottomImage = it
            }
        }
        val forwardDistance = (currentIndex - lastRequestedIndex + items.size) % items.size
        val backwardDistance = (lastRequestedIndex - currentIndex + items.size) % items.size
        isForward = forwardDistance <= backwardDistance
        lastRequestedIndex = currentIndex
        topImage = targetImage
        topLoaded = false
        wipeProgress.snapTo(0f)
    }
    LaunchedEffect(topImage, topLoaded, transitionGeneration) {
        val image = topImage ?: return@LaunchedEffect
        if (!topLoaded || isRejectedHeroImage(image)) return@LaunchedEffect
        val generation = transitionGeneration
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
    DisposableEffect(items, client) {
        onDispose {
            transitionJob?.cancel()
        }
    }
    var lastInteractionAt by remember(items) { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    val displayItem = if (topImage != null && topLoaded && wipeProgress.value >= 0.5f) {
        items.getOrNull(currentIndex) ?: item
    } else {
        items.firstOrNull { resolveHeroImage(it) == bottomImage } ?: item
    }
    val playAction = rememberPlayableItemAction(listOf(item.id, displayItem.id, currentIndex), active = active) { target, _ -> onPlay(target) }
    val currentPlayAction by rememberUpdatedState(playAction)
    val advance by rememberUpdatedState(onNext)
    LaunchedEffect(items, playAction.resolving, active, lifecycle) {
        if (!active || items.size < 2 || playAction.resolving) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            lastInteractionAt = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val remainingMs = 10_000L - (android.os.SystemClock.elapsedRealtime() - lastInteractionAt)
                if (remainingMs > 0L) {
                    delay(remainingMs)
                } else {
                    if (currentPlayAction.resolving) return@repeatOnLifecycle
                    currentPlayAction.invalidate()
                    advance()
                    lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }
    // 标题优先用透明 Logo：Emby 自带优先，没有再用 TMDB 的
    val embyLogo = remember(displayItem.id, displayItem.logoTag) {
        AppRuntime.client?.logoUrl(displayItem)?.takeIf { it.isNotBlank() }
    }
    var tmdbInfo by remember(displayItem.id) { mutableStateOf<TmdbClient.TmdbInfo?>(null) }
    LaunchedEffect(displayItem.id, active, lifecycle) {
        if (!active) return@LaunchedEffect
        if (embyLogo != null && (displayItem.communityRating ?: displayItem.rating) != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (tmdbInfo == null) {
                tmdbInfo = TmdbClient.lookupHero(displayItem.type, displayItem.seriesName ?: displayItem.name, displayItem.year, displayItem.providerIds["OriginalTitle"])
            }
        }
    }
    val titleLogo = embyLogo ?: tmdbInfo?.logoUrl
    LaunchedEffect(currentIndex, items, client, active, lifecycle) {
        if (!active || items.size < 2) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val next = items[(currentIndex + 1) % items.size]
            val nextImage = heroCandidatesById[next.id]?.firstOrNull()?.url ?: return@repeatOnLifecycle
            if (isRejectedHeroImage(nextImage)) return@repeatOnLifecycle
            val request = ImageRequest.Builder(context).data(nextImage).crossfade(false).build()
            val prefetch = context.imageLoader.enqueue(request)
            try {
                prefetch.job.await()
            } finally {
                prefetch.dispose()
            }
        }
    }
    Box(
        Modifier
            .hazeSource(hazeState)
            .fillMaxWidth()
            .height(heroHeight)
            .clipToBounds()
            .onFocusChanged { onHeroFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (focusedHeroAction == HeroActionFocus.NONE || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionDown && focusedHeroAction != HeroActionFocus.ARTWORK) {
                    return@onPreviewKeyEvent onNavigateDown()
                }
                val direction = when (event.key) {
                    Key.DirectionLeft -> HeroDirectionKey.LEFT
                    Key.DirectionRight -> HeroDirectionKey.RIGHT
                    else -> return@onPreviewKeyEvent false
                }
                if (!shouldHeroConsumeCarouselDirection(focusedHeroAction, direction, items.size)) {
                    lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                    return@onPreviewKeyEvent false
                }
                lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                playAction.invalidate()
                if (direction == HeroDirectionKey.LEFT) onPrevious() else onNext()
                true
            }
            .focusGroup()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink)
        ) {
            if (bottomImage == null && topImage == null && item.id in tmdbResolvedIds) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(InkSoft, Ink))))
                Text("暂无横版剧照", Modifier.align(Alignment.Center), color = TextSub, fontSize = 13.sp)
            }
            bottomImage?.takeUnless(::isRejectedHeroImage)?.let { image ->
                val imageOwner = items.firstOrNull { media -> heroCandidatesById[media.id].orEmpty().any { it.url == image } } ?: item
                HeroBackdropImage(
                    imageUrl = image,
                    isPoster = heroCandidatesById[imageOwner.id].orEmpty().any {
                        it.url == image && it.source == "poster"
                    },
                    onSuccess = { width, height ->
                        heroImageLoaded(imageOwner, image, width, height)
                    },
                    onError = { rejectHeroImage(imageOwner, image) },
                )
            }
            topImage?.takeUnless(::isRejectedHeroImage)?.let { image ->
                val imageOwner = items.firstOrNull { media -> heroCandidatesById[media.id].orEmpty().any { it.url == image } } ?: item
                HeroBackdropImage(
                    imageUrl = image,
                    isPoster = heroCandidatesById[imageOwner.id].orEmpty().any {
                        it.url == image && it.source == "poster"
                    },
                    wipeProgress = wipeProgress.value,
                    isForward = isForward,
                    onLoaded = {
                        if (image == topImage && !isRejectedHeroImage(image)) topLoaded = true
                    },
                    onSuccess = { width, height ->
                        heroImageLoaded(imageOwner, image, width, height)
                    },
                    onError = { rejectHeroImage(imageOwner, image) },
                )
            }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.62f)
                .focusRequester(artworkFocus)
                .focusProperties { up = navFocus; down = playFocus }
                .onFocusChanged {
                    if (it.isFocused) focusedHeroAction = HeroActionFocus.ARTWORK
                    else if (focusedHeroAction == HeroActionFocus.ARTWORK) focusedHeroAction = HeroActionFocus.NONE
                }
                .onKeyEvent {
                    if (it.key != Key.DirectionCenter && it.key != Key.Enter && it.key != Key.NumPadEnter) false
                    else {
                        if (it.type == KeyEventType.KeyUp) playFocus.requestFocus()
                        true
                    }
                }
                .focusable()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.45f to Ink.copy(alpha = 0f),
                    1.00f to Ink.copy(alpha = 0.92f),
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).heightIn(max = (heroHeight - 40.dp).coerceAtLeast(0.dp))
                .padding(start = horizontalInset, bottom = bottomInset)
                .widthIn(max = titleWidth),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("为你推荐", color = TextSub, fontSize = 14.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            if (!titleLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(titleLogo).crossfade(true).build(),
                    contentDescription = displayItem.seriesName ?: displayItem.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.heightIn(max = 96.dp).widthIn(max = 345.dp),
                )
            } else {
                Text(
                    displayItem.seriesName ?: displayItem.name,
                    color = TextMain, style = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val hasOwnRating = (displayItem.rating ?: displayItem.communityRating) != null
            val ratingText = if (!hasOwnRating) tmdbInfo?.rating?.let { "TMDB ★ %.1f".format(it) } else null
            val sub = listOfNotNull(
                displayItem.subtitle.takeIf { it.isNotBlank() },
                displayItem.genres.take(2).joinToString(" / ").takeIf { it.isNotBlank() },
                ratingText,
            ).joinToString("   ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, color = TextSub, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            displayItem.overview?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it, color = Color(0xFFC9D5E8), fontSize = 14.sp, lineHeight = 21.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row {
                FocusSurface(
                    modifier = Modifier.height(52.dp).width(minOf(160.dp, actionWidth)).focusProperties {
                        right = detailFocus
                        up = artworkFocus
                    },
                    corner = 26.dp,
                    scaleTo = 1.05f,
                    focusRequester = playFocus,
                    onFocusChanged = {
                        if (it) {
                            focusedHeroAction = HeroActionFocus.PLAY
                        } else if (focusedHeroAction == HeroActionFocus.PLAY) {
                            focusedHeroAction = HeroActionFocus.NONE
                        }
                    },
                    onClick = {
                        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
                        if (displayItem.id == item.id) playAction.play(displayItem)
                    },
                ) { focused ->
                    Row(
                        Modifier.fillMaxSize().background(
                            Color(0xFFE8ECF2)
                        ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF0B1018), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (playAction.resolving) "解析单集…" else if (displayItem.id != item.id) "切换中…"
                            else if (displayItem.positionTicks > 0) "继续播放" else "立即播放",
                            color = Color(0xFF0B1018), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FocusSurface(
                    modifier = Modifier.height(52.dp).width(minOf(136.dp, actionWidth)).focusProperties {
                        left = playFocus
                        up = artworkFocus
                    },
                    corner = 26.dp,
                    scaleTo = 1.05f,
                    focusRequester = detailFocus,
                    onFocusChanged = {
                        if (it) {
                            focusedHeroAction = HeroActionFocus.DETAIL
                        } else if (focusedHeroAction == HeroActionFocus.DETAIL) {
                            focusedHeroAction = HeroActionFocus.NONE
                        }
                    },
                    onClick = { playAction.invalidate(); onOpenDetail(displayItem.seriesId ?: displayItem.id) },
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color(0x24FFFFFF) else Color(0x14FFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) { Text("查看详情", color = Color(0xFFE8ECF2), fontSize = 15.sp) }
                }
            }
        }
            Column(
                Modifier.align(if (compact) Alignment.TopEnd else Alignment.BottomEnd).padding(
                    end = horizontalInset,
                    top = if (compact) 64.dp else 0.dp,
                    bottom = if (compact) 0.dp else bottomInset,
                ),
                horizontalAlignment = Alignment.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.take(3).forEachIndexed { slot, _ ->
                        val thumbnailItem = items[(currentIndex + slot) % items.size]
                        Box(
                            Modifier.size(width = thumbnailWidth, height = thumbnailWidth * 9 / 16)
                                .clip(RoundedCornerShape(6.dp))
                                .background(InkSoft)
                        ) {
                            HeroThumb(
                                thumbnailItem, client, tmdbBackdrops[thumbnailItem.id],
                                failedImages = failedThumbImages[thumbnailItem.id].orEmpty(),
                                onFailed = { image ->
                                    failedThumbImages = failedThumbImages +
                                        (thumbnailItem.id to (failedThumbImages[thumbnailItem.id].orEmpty() + image))
                                },
                            )
                            Box(
                                Modifier.fillMaxSize().border(
                                    if (slot == 0) 2.dp else 1.dp,
                                    if (slot == 0) Accent else Color(0x33FFFFFF),
                                    RoundedCornerShape(6.dp),
                                )
                            )
                        }
                    }
                }
            }
        }
        if (focusedHeroAction == HeroActionFocus.ARTWORK) {
            Box(Modifier.matchParentSize().padding(4.dp).border(1.5.dp, Accent.copy(alpha = 0.76f), RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
private fun HeroThumb(
    item: MediaItem,
    client: EmbyClient?,
    tmdbBackdrop: String?,
    failedImages: Set<String>,
    onFailed: (String) -> Unit,
) {
    val candidates = remember(item, tmdbBackdrop, client) {
        client?.wideImageCandidates(item, maxWidth = 480, quality = 80, tmdbUrl = tmdbBackdrop).orEmpty()
    }
    val candidateIndex = candidates.indexOfFirst { it.url !in failedImages }
    val candidate = candidates.getOrNull(candidateIndex)
    val candidateFailed = candidate == null
    LaunchedEffect(item.id, candidate?.url) {
        PageLog.add("thumbImg id=${item.id} pick=$candidateIndex failed=$candidateFailed")
    }
    fun rejectCandidate() {
        val image = candidate?.url ?: return
        if (image in failedImages) return
        PageLog.add("thumbImg id=${item.id} pick=$candidateIndex failed=true")
        onFailed(image)
    }
    Box(Modifier.fillMaxSize().background(InkSoft)) {
        if (candidates.isEmpty() || candidateFailed) Icon(Icons.Rounded.Movie, null, tint = TextSub, modifier = Modifier.align(Alignment.Center).size(14.dp))
        candidate?.let { candidate ->
            AsyncImage(candidate.url, null, contentScale = ContentScale.Crop,
                onSuccess = {
                    val height = it.result.drawable.intrinsicHeight
                    val width = it.result.drawable.intrinsicWidth
                    if (width <= 0 || height <= 0 || (candidate.source != "poster" && width <= height)) rejectCandidate()
                },
                onError = { rejectCandidate() },
                modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HeroBackdropImage(
    imageUrl: String,
    isPoster: Boolean,
    wipeProgress: Float = 1f,
    isForward: Boolean = true,
    onLoaded: () -> Unit = {},
    onSuccess: (Int, Int) -> Unit = { _, _ -> },
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val request = remember(imageUrl) {
        ImageRequest.Builder(context).data(imageUrl).crossfade(false).build()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = (if (isForward) 1f else -1f) * 18.dp.toPx() * (1f - wipeProgress)
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
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = if (isPoster) ContentScale.Fit else ContentScale.Crop,
            alignment = Alignment.Center,
            onSuccess = {
                val width = it.result.drawable.intrinsicWidth
                val height = it.result.drawable.intrinsicHeight
                onSuccess(width, height)
                // The caller may reject a portrait candidate, but the wipe must always leave
                // the loading state so the previous image can remain visible during fallback.
                onLoaded()
            },
            onError = { onError() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun RowSection(
    title: String,
    items: List<MediaItem>,
    width: Dp = 170.dp,
    aspectRatio: Float = 1.5f,
    topPadding: Dp = Space.section,
    titleOverlay: Boolean = false,
    requestInitialFocus: Boolean = false,
    focusItemId: String? = null,
    sectionKey: String = title,
    focusedSection: String? = null,
    upFocus: FocusRequester? = null,
    firstItemFocus: FocusRequester? = null,
    onItemFocused: ((String) -> Unit)? = null,
    onRowFocusChanged: ((Boolean) -> Unit)? = null,
    onItemMenu: ((MediaItem) -> Unit)? = null,
    onClick: (MediaItem) -> Unit,
) {
    val client = AppRuntime.client
    Column(Modifier.padding(top = topPadding)) {
        if (titleOverlay) {
            Text(title, Modifier.padding(start = 40.dp, bottom = 10.dp), color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        } else SectionTitle(title, Modifier.padding(start = 40.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier
                .focusGroup()
                .then(if (onRowFocusChanged != null) Modifier.onFocusChanged { onRowFocusChanged(it.hasFocus) } else Modifier),
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
                    focusRequester = if (it == items.firstOrNull()) firstItemFocus else null,
                    upFocus = upFocus,
                    onFocused = { onItemFocused?.invoke(it.id) },
                    onMenuKey = onItemMenu?.let { callback -> { callback(it) } },
                    onClick = { onClick(it) },
                )
            }
        }
    }
}

@Composable
private fun HomeRowSkeleton(title: String) {
    Column(Modifier.padding(top = Space.section)) {
        Text(
            title,
            Modifier.padding(start = 40.dp, bottom = 10.dp),
            color = TextMain.copy(alpha = 0.38f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            repeat(5) {
                Box(
                    Modifier.width(170.dp).aspectRatio(1f / 1.5f)
                        .clip(ShapeScale.card)
                        .background(Color(0xFF18202D))
                )
            }
        }
    }
}
