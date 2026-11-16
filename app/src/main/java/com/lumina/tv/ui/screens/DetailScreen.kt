package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.tv.AppRuntime
import com.lumina.tv.R
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.EmbyException
import com.lumina.tv.data.MdblistClient
import com.lumina.tv.data.MediaPerson
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.bucketOf
import com.lumina.tv.data.sameRegionBonus
import com.lumina.tv.player.AudioCapabilities
import com.lumina.tv.player.AudioCaps
import com.lumina.tv.player.detect
import com.lumina.tv.player.sourceHint
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.diag.PageLog
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.FocusScale
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.DetailVideoBackground
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.Glass
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.GlassStrong
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.components.FrostPanel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.net.URLEncoder

private val failedDetailBackgroundKeys = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
private val hlsUnsupportedItems = java.util.Collections.synchronizedSet(mutableSetOf<String>())
private data class RelatedCacheEntry(val items: List<MediaItem>, val storedAt: Long, val dayIndex: String, val empty: Boolean)
private val detailRelatedCache = java.util.Collections.synchronizedMap(mutableMapOf<String, RelatedCacheEntry>())
private const val DETAIL_RELATED_TTL_MS = 6 * 60 * 60 * 1000L
private const val DETAIL_RELATED_EMPTY_TTL_MS = 30 * 60 * 1000L

@OptIn(ExperimentalFoundationApi::class)
private object DetailNoBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return when {
            size > containerSize || offset < 0f -> offset
            else -> offset + size - containerSize
        }
    }
}
private const val DETAIL_PREVIEW_FAILURE_TTL_MS = 10 * 60 * 1000L

private suspend fun loadDetailRecommendations(
    client: com.lumina.tv.data.EmbyClient,
    itemId: String,
    current: MediaItem,
): List<MediaItem> {
    val genres = current.genres.map { it.trim() }.filter(String::isNotBlank).distinctBy { it.lowercase() }
    val genreKey = genres.map { it.lowercase() }.sorted().joinToString("|")
    val calendar = java.util.Calendar.getInstance()
    val dayIndex = "%04d-%03d".format(
        calendar.get(java.util.Calendar.YEAR),
        calendar.get(java.util.Calendar.DAY_OF_YEAR),
    )
    val type = current.type.takeIf { it == "Movie" || it == "Series" } ?: return emptyList()
    val currentRegion = bucketOf(current)
    val currentBucket = currentRegion.first
    val cacheKey = "$itemId|$type|$currentBucket|$genreKey"
    detailRelatedCache[cacheKey]?.let { entry ->
        val ttl = if (entry.empty) DETAIL_RELATED_EMPTY_TTL_MS else DETAIL_RELATED_TTL_MS
        if ((entry.empty || entry.dayIndex == dayIndex) && System.currentTimeMillis() - entry.storedAt < ttl) {
            PageLog.add("detailRelated id=$itemId top12=${entry.items.take(12).joinToString(",") { "${it.id}:${it.name}" }} cache=hit")
            return entry.items
        }
        detailRelatedCache.remove(cacheKey)
    }
    var requestFailed = false
    val result = try {
        withTimeoutOrNull(5_000L) {
        coroutineScope {
            val similar = async { runCatching { client.getSimilarItems(itemId, 20) }.getOrDefault(emptyList()) }
            val genreCandidates = mutableListOf<MediaItem>()
            val mainGenre = genres.firstOrNull()
            if (mainGenre != null) {
                for (page in 0..2) {
                    val pageItems = runCatching {
                        client.getItems(
                            types = type, sortBy = "Random", limit = 60, startIndex = page * 60,
                            genre = mainGenre, requestTimeoutSeconds = 5,
                        )
                    }.getOrDefault(emptyList())
                    genreCandidates += pageItems
                    if (pageItems.size < 60) break
                }
            }
            val queryItems = genreCandidates.distinctBy { it.id }
            val queriedIds = queryItems.mapTo(mutableSetOf()) { it.id }
            val supplements = similar.await().filterNot { it.id in queriedIds }
            val pool = (queryItems + supplements).asSequence()
                .filter { it.id != itemId && it.type == type }
                .filterNot { current.seriesId != null && it.seriesId == current.seriesId }
                .distinctBy { it.id }
                .toList()

            fun score(candidate: MediaItem): Double {
                val currentGenres = current.genres.map { it.trim() }
                val candidateGenres = candidate.genres.map { it.trim() }
                val sameMainGenre = currentGenres.firstOrNull()?.let { main ->
                    candidateGenres.any { it.equals(main, ignoreCase = true) }
                } == true
                val secondaryGenres = currentGenres.drop(1).take(2)
                val sameSecondaryGenre = secondaryGenres.any { genre ->
                    candidateGenres.any { it.equals(genre, ignoreCase = true) }
                }
                val rating = ((candidate.communityRating ?: candidate.rating ?: 0.0) / 10.0).coerceIn(0.0, 1.0)
                val watchedPenalty = if (candidate.positionTicks > 0 || candidate.played) 4.0 else 0.0
                return sameRegionBonus(currentRegion, bucketOf(candidate)) +
                    (if (sameMainGenre) 1.0 else 0.0) +
                    (if (sameSecondaryGenre) 0.5 else 0.0) + rating - watchedPenalty
            }
            val daySeed = dayIndex.hashCode()
            pool.sortedWith(
                compareByDescending<MediaItem> { score(it) }
                    .thenBy { it.id.hashCode() xor daySeed }
            ).take(12)
        }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        requestFailed = true
        null
    }
    val results = if (result == null) {
        val fallback = runCatching { client.getSimilarItems(itemId, 20) }.getOrDefault(emptyList())
            .asSequence()
            .filterNot { it.id == itemId }
            .filterNot { current.seriesId != null && it.seriesId == current.seriesId }
            .sortedByDescending { it.communityRating ?: it.rating ?: 0.0 }
            .take(12)
            .toList()
        if (requestFailed) {
            PageLog.add("detailRelated id=$itemId failed=true fallback=similar count=${fallback.size}")
        } else {
            PageLog.add("detailRelated id=$itemId timeout=true fallback=similar count=${fallback.size}")
        }
        return fallback
    } else result
    detailRelatedCache[cacheKey] = RelatedCacheEntry(results, System.currentTimeMillis(), dayIndex, results.isEmpty())
    if (detailRelatedCache.size > 100) detailRelatedCache.keys.firstOrNull()?.let(detailRelatedCache::remove)
    PageLog.add("detailRelated id=$itemId top12=${results.joinToString(",") { "${it.id}:${it.name}" }}")
    return results
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun DetailScreen(
    id: String,
    onPlay: (MediaItem) -> Unit,
    onPlayWithSource: (MediaItem, String) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sourceAudioCaps = remember { runCatching { detect(context) }.getOrDefault(AudioCapabilities(emptyMap())) }
    val client = AppRuntime.client
    val detailHazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember(id) { mutableStateOf(false) }
    var itemNotFound by remember(id) { mutableStateOf(false) }
    var detailRetry by remember(id) { mutableStateOf(0) }
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var seasons by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<MediaItem?>(null) }
    var tmdb by remember { mutableStateOf<TmdbClient.TmdbInfo?>(null) }
    var mdb by remember(id) { mutableStateOf<MdblistClient.Ratings?>(null) }
    var resolvedExternalLinks by remember(id) { mutableStateOf<TmdbClient.ExternalLinks?>(null) }
    var mediaInfoExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var detailPreviewUrl by remember(id) { mutableStateOf<String?>(null) }
    var detailPreviewKey by remember(id) { mutableStateOf("") }
    var detailPreviewIsTrailer by remember(id) { mutableStateOf(false) }
    var detailPreviewStartMs by remember(id) { mutableStateOf<Long?>(null) }
    var detailVideoPlaying by remember(id) { mutableStateOf(false) }
    var detailPreviewFailure by remember(id) { mutableStateOf<String?>(null) }
    var detailPreviewFromStage by remember(id) { mutableStateOf("hls") }
    var detailPreviewStage by remember(id) { mutableStateOf<String?>(null) }
    var detailPreviewRequestKey by remember(id) { mutableStateOf("") }
    var detailPreviewFailures by remember(id) { mutableStateOf<List<String>>(emptyList()) }
    var detailPreviewRetry by remember(id) { mutableIntStateOf(0) }
    val previewResults = remember(id) { mutableMapOf<String, Triple<String?, String, Boolean>>() }
    val previewStartTimes = remember(id) { mutableMapOf<String, Long?>() }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var similarLoading by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var sourceMenuWasOpen by remember { mutableStateOf(false) }
    var selectedSourceId by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, sources, sourcesLoading) {
        if (!sourcesLoading && selectedSourceId == null) {
            val remembered = AppRuntime.prefs?.lastSourceFor(id)
            if (remembered != null && sources.any { it.id == remembered }) selectedSourceId = remembered
        }
    }
    var showSettingsPicker by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var pickerTitle by remember { mutableStateOf("") }
    var pickerOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pickerCurrentKey by remember { mutableStateOf<String?>(null) }
    var pickerAction by remember { mutableStateOf<(String) -> Unit>({}) }
    var selectedAudioIndex by remember(id) { mutableStateOf(AppRuntime.prefs?.audioTrackFor(id)) }
    var selectedSubtitleKey by remember(id) { mutableStateOf(AppRuntime.prefs?.subtitleTrackFor(id)) }
    // 单集的版本选择
    var episodeTarget by remember { mutableStateOf<MediaItem?>(null) }
    var episodeSources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var episodeSourcesLoading by remember { mutableStateOf(false) }
    var showEpisodeSourceMenu by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var isPlayed by remember { mutableStateOf(false) }
    var focusedRelatedId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val castRowState = rememberLazyListState()
    val similarRowState = rememberLazyListState()
    val detailScrollState = rememberScrollState()
    val playFocus = remember { FocusRequester() }
    val playFromStartFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    var playButtonFocused by remember { mutableStateOf(false) }
    var topRegionFocused by remember { mutableStateOf(false) }

    LaunchedEffect(playButtonFocused, topRegionFocused) {
        if (playButtonFocused || topRegionFocused) detailScrollState.scrollTo(0)
    }

    LaunchedEffect(id) {
        detailPreviewFromStage = "hls"
        detailPreviewFailures = emptyList()
        detailPreviewStage = null
        detailPreviewRetry = 0
        detailPreviewFailure = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && detailPreviewFromStage == "done") {
                detailPreviewFromStage = "hls"
                detailPreviewFailures = emptyList()
                detailPreviewStage = null
                detailPreviewFailure = null
                detailPreviewUrl = null
                detailVideoPlaying = false
                detailPreviewRetry++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(id, detailRetry) {
        synchronized(failedDetailBackgroundKeys) {
            val now = System.currentTimeMillis()
            failedDetailBackgroundKeys.entries.removeAll { now - it.value >= DETAIL_PREVIEW_FAILURE_TTL_MS }
        }
        similar = emptyList()
        similarLoading = true
        showSourceMenu = false
        AppRuntime.screenLabel = "详情"
        val c = client ?: run {
            loading = false
            loadFailed = true
            return@LaunchedEffect
        }

        // 命中缓存：立即渲染，不再转圈
        val cached = LibraryCache.detail(id)?.takeIf { it.item != null }
        if (cached != null) {
            item = cached.item
            seasons = cached.seasons
            episodes = cached.episodes
            sources = cached.sources
            isFav = cached.item?.isFavorite ?: false
            isPlayed = cached.item?.played ?: false
            selectedSeason = cached.seasons.firstOrNull()
            loading = false
            sourcesLoading = cached.sources.isEmpty()
            val startedAt = System.currentTimeMillis()
            PageLog.add("detailLoad id=$id stage=cache-main ms=0")
            val freshItemJob = scope.launch {
                val freshItem = runCatching { c.getItem(id) }.getOrNull()
                if (freshItem != null) item = freshItem
                PageLog.add("detailLoad id=$id stage=main-refresh ms=${System.currentTimeMillis() - startedAt}")
            }
            val sourceJob = scope.launch {
                sources = if (cached.sources.isEmpty()) runCatching { c.getMediaSources(id) }.getOrDefault(emptyList()) else cached.sources
                sourcesLoading = false
                PageLog.add("detailLoad id=$id stage=sources ms=${System.currentTimeMillis() - startedAt}")
            }
            val recommendationJob = scope.launch {
                val current = cached.item ?: return@launch
                similar = loadDetailRecommendations(c, id, current)
                similarLoading = false
                PageLog.add("detailLoad id=$id stage=similar ms=${System.currentTimeMillis() - startedAt}")
            }
            scope.launch {
                joinAll(freshItemJob, sourceJob, recommendationJob)
                LibraryCache.putDetail(id, cached.copy(item = item ?: cached.item, sources = sources, at = System.currentTimeMillis()))
                PageLog.add("detailLoad id=$id stage=complete ms=${System.currentTimeMillis() - startedAt}")
            }
            return@LaunchedEffect
        }

        loading = true
        loadFailed = false
        itemNotFound = false
        sourcesLoading = true
        // 详情 / 分季 / 多源并行拉取，避免串行等待
        val loadStartedAt = System.currentTimeMillis()
        PageLog.add("detailLoad id=$id stage=start")
        val mainWaitNotice = scope.launch {
            delay(5_000L)
            if (loading && item == null) {
                loading = false
                loadFailed = true
                PageLog.add("detailLoad id=$id stage=main-wait-limit ms=5000")
            }
        }
        var main: MediaItem? = null
        var loadError: Throwable? = null
        try {
            for (attempt in 0..2) {
                try {
                    main = c.getItemForDetail(id)
                    loadError = null
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadError = e
                    if ((e as? EmbyException)?.httpStatus == 404) {
                        break
                    }
                    val networkFailure = generateSequence(e as Throwable?) { it.cause }.any { it is java.io.IOException }
                    if (attempt < 2 && networkFailure) delay(if (attempt == 0) 250L else 500L) else break
                }
            }
        } finally {
            mainWaitNotice.cancel()
        }
        if (loadError != null) {
            loading = false
            sourcesLoading = false
            itemNotFound = (loadError as? EmbyException)?.httpStatus == 404
            loadFailed = !itemNotFound
            return@LaunchedEffect
        }
        val it0 = main
        item = it0
        loading = false
        loadFailed = false
        sourcesLoading = true
        PageLog.add("detailLoad id=$id stage=main-ready ms=${System.currentTimeMillis() - loadStartedAt}")
        // TMDB 补充：仅当 Emby 自身无评分时异步拉取，失败静默
        if (it0 != null && it0.rating == null && it0.communityRating == null) {
            tmdb = runCatching { TmdbClient.lookup(it0.type, it0.seriesName ?: it0.name, it0.year) }.getOrNull()
        }
        if (it0 != null) {
            isFav = it0.isFavorite
            isPlayed = it0.played
        }
        if (it0 != null) {
            val seasonJob = scope.launch {
                val loadedSeasons = if (it0.type == "Series") runCatching { c.getSeasons(id) }.getOrDefault(emptyList()) else emptyList()
                seasons = loadedSeasons
                val first = loadedSeasons.firstOrNull()
                val loadedEpisodes = if (first != null) {
                    LibraryCache.episodesOf(id, first.id) ?: runCatching { c.getEpisodes(id, first.id) }.getOrDefault(emptyList())
                } else emptyList()
                if (first != null) LibraryCache.putEpisodes(id, first.id, loadedEpisodes)
                episodes = loadedEpisodes
                selectedSeason = first
                PageLog.add("detailLoad id=$id stage=seasons ms=${System.currentTimeMillis() - loadStartedAt}")
            }
            val sourceJob = scope.launch {
                sources = runCatching { c.getMediaSources(id) }.getOrDefault(emptyList())
                sourcesLoading = false
                PageLog.add("detailLoad id=$id stage=sources ms=${System.currentTimeMillis() - loadStartedAt}")
            }
            similarLoading = true
            val recommendationJob = scope.launch {
                similar = loadDetailRecommendations(c, id, it0)
                similarLoading = false
                PageLog.add("detailLoad id=$id stage=similar ms=${System.currentTimeMillis() - loadStartedAt}")
            }
            scope.launch {
                joinAll(seasonJob, sourceJob, recommendationJob)
                LibraryCache.putDetail(id, LibraryCache.DetailData(it0, seasons, episodes, sources, System.currentTimeMillis()))
                PageLog.add("detailLoad id=$id stage=complete ms=${System.currentTimeMillis() - loadStartedAt}")
            }
        }
    }

    LaunchedEffect(selectedSeason) {
        val c = client ?: return@LaunchedEffect
        val it0 = item ?: return@LaunchedEffect
        if (it0.type == "Series" && selectedSeason != null) {
            val sid = selectedSeason?.id
            LibraryCache.episodesOf(it0.id, sid)?.let { hit ->
                if (hit != episodes) episodes = hit
                return@LaunchedEffect
            }
            val list = runCatching { c.getEpisodes(it0.id, sid) }.getOrDefault(emptyList())
            LibraryCache.putEpisodes(it0.id, sid, list)
            episodes = list
        }
    }

    LaunchedEffect(id, item?.id, item?.type, item?.name, item?.seriesName, item?.year, item?.providerIds) {
        val current = item ?: return@LaunchedEffect
        resolvedExternalLinks = null
        resolvedExternalLinks = runCatching {
            TmdbClient.externalLinks(
                type = current.type,
                title = current.seriesName ?: current.name,
                year = current.year,
                embyImdbId = current.providerIds["imdb"],
                embyTmdbId = current.providerIds["tmdb"],
                embyTvdbId = current.providerIds["tvdb"],
            )
        }.getOrNull()
    }

    LaunchedEffect(id, item?.id, item?.type, item?.providerIds, resolvedExternalLinks) {
        val current = item ?: return@LaunchedEffect
        val imdbId = current.providerIds["imdb"]?.takeIf(String::isNotBlank)
            ?: resolvedExternalLinks?.imdbId?.takeIf(String::isNotBlank)
        val tmdbId = current.providerIds["tmdb"]?.takeIf(String::isNotBlank)
            ?: resolvedExternalLinks?.tmdbId?.takeIf(String::isNotBlank)
        if (imdbId == null && tmdbId == null) {
            mdb = null
            return@LaunchedEffect
        }
        mdb = runCatching { MdblistClient.ratings(current.type, imdbId, tmdbId) }.getOrNull()
    }

    val sourceIdsKey = sources.joinToString(",") { it.id }
    LaunchedEffect(id, item?.id, selectedSourceId, selectedSeason?.id, episodes.firstOrNull()?.id, sourceIdsKey, detailPreviewRetry) {
        val c = client ?: run { PageLog.add("detailPreview stage=skip reason=client-null"); return@LaunchedEffect }
        val current = item ?: run { PageLog.add("detailPreview stage=skip reason=item-null"); return@LaunchedEffect }
        val requestKey = "${current.id}|${selectedSourceId.orEmpty()}|${selectedSeason?.id.orEmpty()}|${episodes.firstOrNull()?.id.orEmpty()}"
        if (detailPreviewRequestKey != requestKey) {
            detailPreviewRequestKey = requestKey
            detailPreviewFromStage = "hls"
            detailPreviewFailures = emptyList()
            detailPreviewStage = null
        }
        if (detailPreviewRetry == 0) previewResults[requestKey]?.takeIf { !it.first.isNullOrBlank() }?.let { cached ->
            detailPreviewUrl = cached.first
            detailPreviewKey = cached.second
            detailPreviewIsTrailer = cached.third
            detailPreviewStartMs = previewStartTimes[requestKey]
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=cache-hit")
            return@LaunchedEffect
        }
        detailPreviewUrl = null
        if (detailPreviewRetry == 0) detailPreviewFailure = null
        val previewItem = when (current.type) {
            "Series" -> episodes.firstOrNull() ?: runCatching {
                c.getEpisodes(current.id, selectedSeason?.id).firstOrNull()
            }.getOrNull()
            else -> current
        } ?: run {
            detailPreviewKey = "${current.id}:unknown"
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=preview-item-null")
            return@LaunchedEffect
        }
        val trailer = runCatching { c.getLocalTrailers(previewItem.id).firstOrNull() }.getOrNull()
        val sourceItem = trailer ?: previewItem
        val previewSource = if (sourceItem.id == current.id) {
            if (sources.isEmpty() && sourcesLoading) {
                PageLog.add("detailPreview stage=skip reason=no-cache-source")
                return@LaunchedEffect
            }
            sources.firstOrNull { it.id == selectedSourceId && it.streams.any { stream -> stream.type == "Video" } }
                ?: sources.firstOrNull { source -> source.streams.any { it.type == "Video" } }
        } else {
            runCatching { c.getMediaSources(sourceItem.id) }.getOrDefault(emptyList())
                .firstOrNull { source -> source.streams.any { it.type == "Video" } }
        }
        if (previewSource == null && sourceItem.id == current.id && sourcesLoading) {
            PageLog.add("detailPreview stage=skip reason=no-cache-source")
            return@LaunchedEffect
        }
        val resolvedPreviewSource = previewSource ?: run {
            detailPreviewKey = "${previewItem.id}:${sourceItem.id}:unknown"
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=preview-source-null")
            return@LaunchedEffect
        }
        val hlsKey = "${sourceItem.id}|${resolvedPreviewSource.id}"
        val backgroundMode = AppRuntime.prefs?.detailBackgroundVideo ?: "auto"
        if (backgroundMode != "720p" && hlsKey in hlsUnsupportedItems && detailPreviewFromStage == "hls") {
            detailPreviewFromStage = "direct"
            PageLog.add("detailPreview stage=skip reason=hls-known-broken")
        }
        detailPreviewIsTrailer = trailer != null
        detailPreviewStartMs = if (trailer != null) 0L else {
            AppRuntime.prefs?.introSecondsFor(previewItem.id)?.takeIf { it > 0 }?.times(1000L)
        }
        detailPreviewKey = "${previewItem.id}:${sourceItem.id}:${resolvedPreviewSource.id}"
        val failedAt = failedDetailBackgroundKeys[detailPreviewKey]
        if (failedAt != null && System.currentTimeMillis() - failedAt < DETAIL_PREVIEW_FAILURE_TTL_MS) {
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=blacklist")
            return@LaunchedEffect
        }
        if (failedAt != null) failedDetailBackgroundKeys.remove(detailPreviewKey)
        if (AppRuntime.prefs?.detailBackgroundVideo == "off") {
            PageLog.add("detailPreview stage=skip reason=mode-off")
            return@LaunchedEffect
        }
        val resolution = runCatching {
            c.resolveDetailPreviewUrl(
                sourceItem.id,
                resolvedPreviewSource.id,
                AppRuntime.prefs?.detailBackgroundVideo ?: "auto",
                fromStage = detailPreviewFromStage,
            )
        }.getOrNull()
        detailPreviewFailures = (detailPreviewFailures + resolution?.failures.orEmpty()).distinct()
        if (backgroundMode != "720p" && resolution?.failures.orEmpty().any { it == "hls:not-m3u8" }) {
            hlsUnsupportedItems.add(hlsKey)
        }
        detailPreviewUrl = resolution?.url
        if (detailPreviewUrl.isNullOrBlank()) {
            detailVideoPlaying = false
            if (detailPreviewFailures.isEmpty()) {
                detailPreviewFailures = listOf("no-url:${AppRuntime.prefs?.detailBackgroundVideo ?: "auto"}")
            }
            detailPreviewFailure = detailPreviewFailures.joinToString(" / ")
            detailPreviewFromStage = "done"
            PageLog.add("detailPreview stage=done failures=${detailPreviewFailure}")
        } else {
            detailPreviewStage = resolution?.stage
            detailPreviewFailure = null
            previewResults[requestKey] = Triple(detailPreviewUrl, detailPreviewKey, detailPreviewIsTrailer)
            previewStartTimes[requestKey] = detailPreviewStartMs
        }
    }

    LaunchedEffect(item?.id) {
        if (item == null) return@LaunchedEffect
        var focused = false
        for (attempt in 0 until 3) {
            withFrameNanos { }
            runCatching { playFocus.requestFocus() }
            withFrameNanos { }
            focused = playButtonFocused
            if (focused) break
            if (attempt < 2) delay(120)
        }
        if (!focused) PageLog.add("focus=play-failed")
    }

    LaunchedEffect(showSourceMenu) {
        if (showSourceMenu) {
            sourceMenuWasOpen = true
        } else if (sourceMenuWasOpen) {
            delay(120)
            runCatching { sourceFocus.requestFocus() }
            sourceMenuWasOpen = false
        }
    }

    /** 单集有多个版本时先选版本，只有一个版本才直接播。 */
    fun pickEpisode(ep: MediaItem) {
        if (ep.sourceCount <= 1) {
            onPlay(ep)
            return
        }
        episodeTarget = ep
        episodeSources = emptyList()
        episodeSourcesLoading = true
        showEpisodeSourceMenu = true
        scope.launch {
            episodeSources = runCatching { client?.getMediaSources(ep.id) }.getOrDefault(emptyList()).orEmpty()
            episodeSourcesLoading = false
            if (episodeSources.isEmpty()) {
                showEpisodeSourceMenu = false
                onPlay(ep)
            }
        }
    }

    // 部分服务器在剧集条目上不带 People：换列表接口再取一次，避免"主演"整块消失
    LaunchedEffect(id, item) {
        val current = item ?: return@LaunchedEffect
        if (current.people.isNotEmpty()) return@LaunchedEffect
        val extra = runCatching { client?.getItemPeople(id) }.getOrNull().orEmpty()
        if (extra.isNotEmpty()) item = current.copy(people = extra)
    }

    Box(Modifier.fillMaxSize()) {
        val it0 = item
        if (it0 != null) {
            AsyncImage(
                model = client?.backdropUrl(it0, 1920),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(42.dp).hazeSource(detailHazeState),
            )
            AsyncImage(
                model = client?.backdropUrl(it0, 1920),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            DetailVideoBackground(
                url = detailPreviewUrl,
                mediaKey = detailPreviewKey,
                requestHeaders = client?.previewHeaders().orEmpty(),
                playing = !loading,
                onStatus = { status ->
                    detailVideoPlaying = status == "play"
                    if (status.startsWith("skip=") && status != "skip=no-preview-url" && detailPreviewStage != null && detailPreviewKey.isNotBlank()) {
                        val failedStage = detailPreviewStage ?: "unknown"
                        val reason = "$failedStage:playback-failed"
                        detailPreviewFailures = (detailPreviewFailures + reason).distinct()
                        PageLog.add("detailPreview stage=$failedStage verdict=playback-failed")
                        if (failedStage == "hls" && status.contains("#EXTM3U", ignoreCase = true) && AppRuntime.prefs?.detailBackgroundVideo != "720p") {
                            hlsUnsupportedItems.add(detailPreviewKey.split(':').takeLast(2).joinToString("|"))
                        }
                        detailPreviewUrl = null
                        if (failedStage == "static") {
                            detailPreviewFromStage = "done"
                            detailPreviewFailure = detailPreviewFailures.joinToString(" / ")
                        } else {
                            detailPreviewFromStage = when (failedStage) {
                                "hls" -> "direct"
                                "direct" -> "static"
                                else -> "done"
                            }
                            detailPreviewRetry++
                        }
                    }
                },
                startAtMs = detailPreviewStartMs,
                loopWholeItem = detailPreviewIsTrailer,
                modifier = Modifier.fillMaxSize(),
            )
            // 模糊海报 → 主色铺满：重模糊后叠深色渐层，页面颜色随封面走
            val videoPlaying = detailVideoPlaying
            val v0 by animateColorAsState(if (videoPlaying) Color(0x00000000) else Color(0x4D000000), tween(500), label = "detailMaskTop")
            val v1 by animateColorAsState(if (videoPlaying) Color(0x33000000) else Color(0x99000000), tween(500), label = "detailMaskUpper")
            val v2 by animateColorAsState(Color(0x99000000), tween(500), label = "detailMaskLower")
            val v3 by animateColorAsState(if (videoPlaying) Color(0xCC000000) else Color(0xF20B0A09), tween(500), label = "detailMaskBottom")
            val h0 by animateColorAsState(if (videoPlaying) Color(0x99000000) else Color(0xCC000000), tween(500), label = "detailMaskLeft")
            val h1 by animateColorAsState(if (videoPlaying) Color(0x33000000) else Color(0x4D000000), tween(500), label = "detailMaskCenter")
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(v0, v1, v2, v3))))
            Box(Modifier.fillMaxWidth(if (videoPlaying) 0.7f else 1f).fillMaxSize().background(
                Brush.horizontalGradient(listOf(h0, h1, Color(0x00000000)))
            ))
        }

        if (loading) {
            LoadingView(text = "正在读取详情…")
        } else if (itemNotFound) {
            LoadingView(text = "\u672a\u627e\u5230\u8be5\u5185\u5bb9")
        } else if (loadFailed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\u52a0\u8f7d\u5931\u8d25", color = TextMain, fontSize = 16.sp)
                Spacer(Modifier.height(14.dp))
                FocusSurface(Modifier.width(156.dp).height(42.dp), corner = 12.dp, requestFocus = true, onClick = {
                    loading = true
                    loadFailed = false
                    detailRetry++
                }) { focused ->
                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("\u70b9\u51fb\u91cd\u8bd5", color = TextMain, fontSize = 13.sp)
                    }
                }
            }
        } else if (item == null) {
            LoadingView(text = "未找到该内容")
        } else {
            Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides DetailNoBringIntoViewSpec) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(detailScrollState)
                            .padding(start = 48.dp, end = 48.dp, bottom = 24.dp)
                    ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x14FFFFFF), RoundedCornerShape(26.dp))
                            .padding(20.dp)
                            .focusGroup()
                            .onFocusChanged { topRegionFocused = it.hasFocus }
                    ) {
                    Poster(it0!!, client?.imageUrl(it0, 720))
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.weight(1f).padding(top = 30.dp)) {
                        Text(
                            if (it0.type == "Episode" && it0.name.isNotBlank()) it0.name else it0.seriesName ?: it0.name,
                            color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xE6000000), Offset(2f, 3f), 8f)),
                        )
                        Spacer(Modifier.height(8.dp))
                        val ownRating = (it0.communityRating ?: it0.rating)?.takeIf { it > 0 }
                        val infoParts = when (it0.type) {
                            "Episode" -> listOfNotNull(
                                if (it0.parentIndexNumber != null && it0.indexNumber != null) "S%02dE%02d".format(it0.parentIndexNumber, it0.indexNumber) else null,
                                ownRating?.let { r -> "★ %.1f".format(r) },
                            )
                            "Series" -> listOfNotNull(
                                it0.year?.toString(),
                                it0.genres.take(3).joinToString(" / ").takeIf { it.isNotBlank() },
                                ownRating?.let { r -> "★ %.1f".format(r) },
                                seasons.size.takeIf { it > 0 }?.let { "共 $it 季" },
                            )
                            else -> listOfNotNull(
                                it0.year?.toString(),
                                it0.genres.take(3).joinToString(" / ").takeIf { it.isNotBlank() },
                                ownRating?.let { r -> "★ %.1f".format(r) },
                                if (ownRating == null) tmdb?.rating?.takeIf { it > 0 }?.let { r -> "TMDB ★ %.1f".format(r) } else null,
                            )
                        }
                        if (infoParts.isNotEmpty()) {
                            Text(infoParts.joinToString("   "), color = TextSub, fontSize = 12.sp)
                        }
                        mdb?.let { ratings ->
                            val ratingLine = listOfNotNull(
                                ratings.imdb?.takeIf { it > 0 }?.let { "IMDb %.1f".format(it) },
                                ratings.tomato?.takeIf { it > 0 }?.let { "番茄 $it%" },
                                ratings.metacritic?.takeIf { it > 0 }?.let { "MC $it" },
                                ratings.trakt?.takeIf { it > 0 }?.let { "Trakt $it" },
                            ).joinToString(" · ")
                            if (ratingLine.isNotBlank()) Text(ratingLine, color = TextSub, fontSize = 11.sp)
                        }
                        val badgeSource = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                        val topBadges = badgeSource?.let { source ->
                            val video = source.streams.firstOrNull { it.type.equals("Video", true) }
                            video?.let { videoBadges(it, source.audioStreams.firstOrNull()) }.orEmpty()
                        } ?: tagBadges(it0.tags)
                        if (topBadges.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                topBadges.take(5).forEach { SpecBadge(it, containerHeight = 22.dp, imageHeight = 13.dp) }
                            }
                        }
                        if (it0.isDolbyVision) {
                            Text(
                                "DV 原片可能触发电视切换显示模式",
                                color = TextSub,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        it0.overview?.let {
                            Text(
                                it, color = Color(0xFFC9D5E8), fontSize = 13.sp, lineHeight = 21.sp,
                                maxLines = 8, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xD9000000), Offset(1f, 2f), 6f)),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.focusGroup().focusProperties { up = playFocus }) {
                            FocusSurface(
                                modifier = Modifier.height(52.dp).width(150.dp).focusProperties {
                                    if (it0.positionTicks > 0L) right = playFromStartFocus
                                },
                                corner = 23.dp,
                                focusRequester = playFocus,
                                onFocused = { playButtonFocused = true },
                                onFocusChanged = { playButtonFocused = it },
                                onClick = {
                                    val target = if (it0.type == "Series") {
                                        episodes.firstOrNull { !it.played && it.positionTicks > 0 }
                                            ?: episodes.firstOrNull { !it.played }
                                            ?: episodes.firstOrNull()
                                    } else it0
                                    if (target != null) {
                                        val sourceId = selectedSourceId
                                        if (target.id == it0.id && sourceId != null) onPlayWithSource(target, sourceId)
                                        else onPlay(target)
                                    }
                                },
                            ) { focused ->
                                Row(
                                    Modifier.fillMaxSize().background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Accent.copy(alpha = if (focused) 1f else 0.9f),
                                                Accent2.copy(alpha = if (focused) 1f else 0.9f),
                                            )
                                        )
                                    ).padding(horizontal = 28.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF06101F), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("播放", color = Color(0xFF06101F), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (it0.positionTicks > 0L) {
                                Spacer(Modifier.width(10.dp))
                                FocusSurface(
                                    modifier = Modifier.height(52.dp).width(132.dp).focusProperties {
                                        left = playFocus
                                        up = playFocus
                                    },
                                    corner = 23.dp,
                                    focusRequester = playFromStartFocus,
                                    onClick = {
                                        val target = if (it0.type == "Series") {
                                            episodes.firstOrNull { !it.played && it.positionTicks > 0 }
                                                ?: episodes.firstOrNull { !it.played }
                                                ?: episodes.firstOrNull()
                                        } else it0
                                        if (target != null) onPlayFromStart(target)
                                    },
                                ) { focused ->
                                    Box(
                                        Modifier.fillMaxSize()
                                            .background(if (focused) Color(0x33FFFFFF) else Color(0x1FFFFFFF))
                                            .border(
                                                if (focused) 2.dp else 0.dp,
                                                Color.White,
                                                RoundedCornerShape(23.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("从头播放", color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                            if (sources.isNotEmpty()) {
                                Spacer(Modifier.width(12.dp))
                                FocusSurface(
                                    modifier = Modifier.height(46.dp).width(210.dp),
                                    corner = 23.dp,
                                    focusRequester = sourceFocus,
                                    onClick = { showSourceMenu = true },
                                ) { focused ->
                                    Row(
                                        Modifier.fillMaxSize().background(
                                            if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                        ),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Layers, null, tint = TextMain, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "版本 · ${sources.firstOrNull()?.displayLabel ?: "默认源"}",
                                            color = TextMain,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            // 收藏
                            FocusSurface(
                                modifier = Modifier.size(46.dp),
                                corner = 23.dp,
                                onClick = {
                                    isFav = !isFav
                                    LibraryCache.invalidateHome()
                                    LibraryCache.invalidateDetail(it0.id)
                                    scope.launch {
                                        val saved = runCatching { client?.setFavorite(it0.id, isFav) }.getOrNull()
                                        if (saved == false) isFav = !isFav
                                    }
                                },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (isFav) Accent.copy(alpha = 0.85f) else if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = "收藏",
                                        tint = if (isFav) Color(0xFF06101F) else TextMain,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            // 标记已看
                            FocusSurface(
                                modifier = Modifier.size(46.dp),
                                corner = 23.dp,
                                onClick = {
                                    isPlayed = !isPlayed
                                    LibraryCache.invalidateHome()
                                    LibraryCache.invalidateDetail(it0.id)
                                    scope.launch {
                                        val saved = runCatching { client?.setPlayed(it0.id, isPlayed) }.getOrNull()
                                        if (saved == false) isPlayed = !isPlayed
                                    }
                                },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (isPlayed) Accent.copy(alpha = 0.85f) else if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = "已看",
                                        tint = if (isPlayed) Color(0xFF06101F) else TextMain,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            FocusSurface(
                                Modifier.height(46.dp).width(46.dp),
                                corner = 23.dp,
                                onClick = { showTrackSheet = true },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF),
                                        RoundedCornerShape(23.dp),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Tune, contentDescription = "播放设置", tint = TextMain, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            FocusSurface(
                                modifier = Modifier.height(48.dp).width(120.dp),
                                corner = 23.dp,
                                onClick = onBack,
                            ) { focused ->
                                Row(
                                    Modifier.fillMaxSize().background(
                                        if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ).padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.ArrowBack, null, tint = TextMain, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("返回", color = TextMain, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }

                if (seasons.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("季数与剧集", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(seasons, key = { it.id }) { s ->
                            val active = s.id == selectedSeason?.id
                            FocusSurface(
                                modifier = Modifier.height(34.dp),
                                corner = 17.dp,
                                scaleTo = 1.06f,
                                onClick = { selectedSeason = s },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (active) Color(0x3D6EA8FF) else if (focused) Color(0x2AFFFFFF) else Color(0x14FFFFFF)
                                    ).padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        s.name,
                                        color = if (active) Accent else TextMain,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                if (episodes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(state = castRowState, modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(ep, client?.imageUrl(ep, 360)) { pickEpisode(ep) }
                        }
                    }
                }
                val cast = it0!!.people.filter { person -> person.name.isNotBlank() }.take(16)
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(22.dp))
                    Text("演职人员", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast, key = { it.id ?: "${it.name}-${it.role.orEmpty()}" }) { person ->
                            PersonCard(person = person, imageUrl = client?.personImageUrl(person))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("相关推荐", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                if (similarLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(4) {
                            Box(Modifier.width(138.dp).height(190.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x1AFFFFFF)))
                        }
                    }
                } else if (similar.isEmpty()) {
                    Text(
                        "暂无相关推荐",
                        color = TextSub, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    LazyRow(state = similarRowState, modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(similar, key = { it.id }) { related ->
                            PosterCard(
                                item = related,
                                imageUrl = client?.imageUrl(related),
                                width = 138.dp,
                                focusScale = FocusScale.DENSE_GRID,
                                requestInitialFocus = related.id == focusedRelatedId,
                                onFocused = { focusedRelatedId = related.id },
                                onClick = { onOpenDetail(related.id) },
                            )
                        }
                    }
                }

                val activeSource = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                if (activeSource != null && hasMediaInfo(activeSource)) {
                    Spacer(Modifier.height(24.dp))
                    MediaInfoSection(
                        source = activeSource,
                        expanded = mediaInfoExpanded,
                        onToggle = { mediaInfoExpanded = !mediaInfoExpanded },
                        hazeState = detailHazeState,
                    )
                }

                val providerIds = it0!!.providerIds
                val resolvedLinks = resolvedExternalLinks
                val searchTitle = (it0.seriesName?.takeIf(String::isNotBlank) ?: it0.name)
                    .takeIf(String::isNotBlank)
                    ?: sources.firstOrNull()?.name.orEmpty()
                val encodedTitle = URLEncoder.encode(searchTitle.ifBlank { "文件名" }, "UTF-8")
                val tmdbId = (providerIds["tmdb"] ?: resolvedLinks?.tmdbId)?.takeIf(String::isNotBlank)
                val imdbId = (providerIds["imdb"] ?: resolvedLinks?.imdbId)?.takeIf(String::isNotBlank)
                val mediaType = if (it0.type == "Series") "tv" else "movie"
                val providerLinks = listOf(
                    "TMDB" to (tmdbId?.let { "https://www.themoviedb.org/$mediaType/$it" }
                        ?: "https://www.themoviedb.org/search?query=$encodedTitle"),
                    "IMDB" to (imdbId?.let { "https://www.imdb.com/title/$it/" }
                        ?: "https://www.imdb.com/find/?q=$encodedTitle"),
                    "ROTTEN TOMATOES" to "https://www.rottentomatoes.com/search?search=$encodedTitle",
                    "DOUBAN" to "https://search.douban.com/movie/subject_search?search_text=$encodedTitle",
                )
                if (providerLinks.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("外部链接", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    val uriHandler = LocalUriHandler.current
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        providerLinks.forEachIndexed { index, (label, uri) ->
                            val brandResource = when (index) {
                                0 -> R.drawable.link_brand_tmdb
                                1 -> R.drawable.link_brand_imdb
                                2 -> R.drawable.link_brand_rt
                                else -> R.drawable.link_brand_douban
                            }
                            val brandHeight = if (index == 0) 8.dp else 12.dp
                            FocusGlassSurface(
                                Modifier.height(34.dp).then(
                                    if (index == 3) Modifier.focusProperties { right = androidx.compose.ui.focus.FocusRequester.Cancel }
                                    else Modifier
                                ),
                                corner = 10.dp,
                                normalFill = Color(0x14FFFFFF),
                                onClick = { runCatching { uriHandler.openUri(uri) } },
                            ) { focused ->
                                Row(
                                    Modifier.padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val brandPainter = painterResource(brandResource)
                                    Image(
                                        painter = brandPainter,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.height(brandHeight)
                                            .then(if (index == 0) Modifier.aspectRatio(190.24f / 81.52f) else Modifier.aspectRatio(1f))
                                            .graphicsLayer { alpha = if (focused) 1f else 0.85f },
                                    )
                                    Text(label, color = if (focused) Color.White else Color(0xD9FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.96.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }

        if (showTrackSheet && it0 != null) {
            val currentItem = it0!!
            val activeSource = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
            TrackSheet(onDismiss = { showTrackSheet = false }) {
                GlassPanel(Modifier.fillMaxWidth(), corner = 12.dp) {
                    Column(Modifier.padding(6.dp)) {
                        SettingRow("版本", activeSource?.displayLabel ?: "默认", enabled = sources.size > 1) {
                            if (sources.size > 1) {
                                pickerTitle = "选择版本"
                                pickerOptions = sources.map { it.id to it.displayLabel }
                                pickerCurrentKey = activeSource?.id
                                pickerAction = { key -> selectedSourceId = key }
                                showSettingsPicker = true
                            }
                        }
                        val audioTracks = activeSource?.streams.orEmpty().filter { it.type == "Audio" }
                        SettingRow("音频", audioTracks.firstOrNull { it.index == selectedAudioIndex }?.let(::audioLabel)
                            ?: audioTracks.firstOrNull()?.let(::audioLabel) ?: "默认", enabled = audioTracks.size > 1) {
                            if (audioTracks.isNotEmpty()) {
                                pickerTitle = "选择音频"
                                pickerOptions = audioTracks.map { it.index.toString() to audioLabel(it) }
                                pickerCurrentKey = (selectedAudioIndex ?: audioTracks.firstOrNull()?.index)?.toString()
                                pickerAction = { key ->
                                    selectedAudioIndex = key.toIntOrNull()
                                    key.toIntOrNull()?.let { AppRuntime.prefs?.rememberAudioTrack(currentItem.id, it) }
                                }
                                showSettingsPicker = true
                            }
                        }
                        val subtitleTracks = activeSource?.streams.orEmpty().filter { it.type == "Text" || it.type == "Subtitle" }
                        SettingRow("字幕", if (selectedSubtitleKey == "-1") "关闭" else subtitleTracks.firstOrNull { subtitleKey(it) == selectedSubtitleKey }?.language ?: subtitleTracks.firstOrNull()?.language ?: "默认", enabled = subtitleTracks.isNotEmpty()) {
                            pickerTitle = "选择字幕"
                            pickerOptions = listOf("-1" to "关闭") + subtitleTracks.map { subtitleKey(it) to (it.language ?: "未知语言") }
                            pickerCurrentKey = selectedSubtitleKey
                            pickerAction = { key ->
                                selectedSubtitleKey = key
                                AppRuntime.prefs?.rememberSubtitleTrack(currentItem.id, key)
                            }
                            showSettingsPicker = true
                        }
                    }
                }
            }
        }

        // 单集版本选择
        if (showEpisodeSourceMenu && episodeTarget != null) {
            val ep = episodeTarget!!
            SourceMenu(
                sources = episodeSources,
                sourcesLoading = episodeSourcesLoading,
                currentSourceId = episodeSources.firstOrNull()?.id,
                engine = AppRuntime.prefs?.playerEngine ?: "media3",
                audioCaps = sourceAudioCaps,
                title = "选择版本 · ${ep.name}",
                onPick = { sid ->
                    showEpisodeSourceMenu = false
                    AppRuntime.prefs?.rememberSource(ep.id, sid)
                    onPlayWithSource(ep, sid)
                },
                onClose = { showEpisodeSourceMenu = false },
            )
        }

        // 版本选择菜单
        if (showSourceMenu) {
            SourceMenu(
                sources = sources,
                sourcesLoading = sourcesLoading,
                currentSourceId = selectedSourceId ?: sources.firstOrNull()?.id,
                engine = AppRuntime.prefs?.playerEngine ?: "media3",
                audioCaps = sourceAudioCaps,
                onPick = { sid ->
                    selectedSourceId = sid
                    AppRuntime.prefs?.rememberSource(id, sid)
                    showSourceMenu = false
                },
                onClose = { showSourceMenu = false },
            )
        }
    }
}

@Composable
private fun PersonCard(person: MediaPerson, imageUrl: String?) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(112.dp)
            .graphicsLayer {
                val scale = if (focused) 1.08f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(Modifier.fillMaxWidth().background(if (focused) Color(0x20FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp)).padding(6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(Color(0x1AFFFFFF)).then(if (focused) Modifier.border(2.dp, Accent, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(imageUrl, contentDescription = person.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Text(person.name.take(1), color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(person.name, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(person.role ?: person.type ?: "演职人员", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
}

/** 多版本选择弹层。 */
@Composable
private fun SourceMenu(
    sources: List<MediaSource>,
    sourcesLoading: Boolean,
    currentSourceId: String?,
    engine: String,
    audioCaps: AudioCaps,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    title: String = "选择版本",
) {
    val options = sources.withIndex()
        .sortedWith(compareBy<IndexedValue<MediaSource>> { sourceHint(it.value, engine, audioCaps).priority }.thenBy { it.index })
        .map { (_, source) ->
            val hint = sourceHint(source, engine, audioCaps)
            source.id to (source.displayLabel + (hint.label?.let { "  ·  $it" } ?: ""))
        }
    PickerDialog(title, options, currentSourceId, onPick, onClose, loading = sourcesLoading)
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentKey: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    loading: Boolean = false,
) {
    val requesters = remember(options) { options.map { FocusRequester() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(options) {
            if (options.isNotEmpty()) {
                delay(120)
                runCatching { requesters.first().requestFocus() }
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.width(560.dp), corner = 22.dp) {
                Column(Modifier.padding(22.dp)) {
                    Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    if (loading) Text("正在读取列表…", color = TextSub, fontSize = 12.sp)
                    else if (options.isEmpty()) Text("没有可选项", color = TextSub, fontSize = 12.sp)
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(max = 46.dp * 8 + 40.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        options.forEachIndexed { index, (key, label) ->
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                                    .focusRequester(requesters[index])
                                    .focusProperties {
                                        up = requesters.getOrNull(index - 1) ?: FocusRequester.Cancel
                                        down = requesters.getOrNull(index + 1) ?: FocusRequester.Cancel
                                    },
                                corner = 12.dp, scaleTo = 1f, onClick = { onPick(key) },
                            ) { focused ->
                                Row(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (key == currentKey) Icons.Rounded.CheckCircle else Icons.Rounded.Layers, null, tint = if (key == currentKey) Accent else TextSub, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(label, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    FocusSurface(Modifier.fillMaxWidth().height(52.dp), corner = 10.dp, scaleTo = 1f, onClick = onClick) { focused ->
        Row(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label：$value", color = if (enabled) TextMain else TextSub.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("›", color = if (enabled) Accent else TextSub.copy(alpha = 0.5f), fontSize = 20.sp)
        }
    }
}

private enum class MediaBadge { AtmosDolbyVision, DolbyVision, Hdr10Plus, Hdr10, Hdr, DolbyAtmos, DtsX, DtsHd, TrueHd, Imax, FourK, FullHd, Hd720, Surround71, Surround51 }

private fun hasMediaInfo(source: MediaSource): Boolean = source.name.isNotBlank() ||
    !source.container.isNullOrBlank() || source.size > 0 || (source.bitrate ?: 0) > 0 ||
    !source.resolution.isNullOrBlank() || source.streams.any {
        it.type.equals("Video", true)
    }

@Composable
private fun MediaInfoSection(source: MediaSource, expanded: Boolean, onToggle: () -> Unit, hazeState: HazeState) {
    val video = source.streams.firstOrNull { it.type.equals("Video", true) }
    val fileRows = listOfNotNull(
        source.name.takeIf(String::isNotBlank)?.let { "文件名" to it },
        source.container?.takeIf(String::isNotBlank)?.uppercase()?.let { "容器" to it },
        source.size.takeIf { it > 0 }?.let { "大小" to formatMediaSize(it) },
        source.bitrate?.takeIf { it > 0 }?.let { "总码率" to formatBitrate(it) },
    )
    val videoRows = listOfNotNull(
        videoResolution(source, video)?.let { "分辨率" to it },
        video?.frameRate?.takeIf { it > 0 }?.let { "帧率" to formatFrameRate(it) },
    )
    val videoBadges = video?.let { videoBadges(it, source.audioStreams.firstOrNull()) }.orEmpty()
    val summaryBadges = videoBadges.take(4)
    val technicalSummary = listOfNotNull(
        source.container?.takeIf(String::isNotBlank)?.uppercase(),
        source.size.takeIf { it > 0 }?.let(::formatMediaSize),
        videoResolution(source, video),
    ).joinToString(" · ")
    val hasCards = fileRows.isNotEmpty() || videoRows.isNotEmpty() || videoBadges.isNotEmpty()
    if (!hasCards) return

    BackHandler(enabled = expanded) { onToggle() }
    var hasFocus by remember { mutableStateOf(false) }

    FrostPanel(Modifier.fillMaxWidth().height(52.dp), hazeState = hazeState, corner = 12.dp, strong = false) {
      Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hasFocus) GlassStrong else Glass)
            .border(if (hasFocus) 1.dp else 0.5.dp, if (hasFocus) Accent else GlassLine, RoundedCornerShape(12.dp))
            .onFocusChanged { hasFocus = it.hasFocus }
            .clickable(onClick = onToggle),
      ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("媒体信息", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                summaryBadges.forEach { SpecBadge(it) }
                if (summaryBadges.isEmpty()) {
                    if (technicalSummary.isNotBlank()) Text(technicalSummary, color = if (hasFocus) TextMain.copy(alpha = 0.92f) else TextSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (expanded) "收起" else "展开", color = TextSub, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = if (hasFocus) Accent else TextSub, modifier = Modifier.size(18.dp))
        }
      }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            val twoColumns = maxWidth >= 600.dp
            if (twoColumns) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (fileRows.isNotEmpty()) MediaGlassCard("文件", Icons.Rounded.InsertDriveFile, "" + fileRows.size + " 项", Modifier.weight(1f)) { DetailRows(fileRows) }
                    if (videoRows.isNotEmpty() || videoBadges.isNotEmpty()) MediaGlassCard("视频", Icons.Rounded.Movie, video?.let { "1 条" }, Modifier.weight(1f)) { VideoCardContent(videoBadges, videoRows) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (fileRows.isNotEmpty()) MediaGlassCard("文件", Icons.Rounded.InsertDriveFile, "" + fileRows.size + " 项", Modifier.fillMaxWidth()) { DetailRows(fileRows) }
                    if (videoRows.isNotEmpty() || videoBadges.isNotEmpty()) MediaGlassCard("视频", Icons.Rounded.Movie, video?.let { "1 条" }, Modifier.fillMaxWidth()) { VideoCardContent(videoBadges, videoRows) }
                }
            }
        }
    }
}

@Composable
private fun FocusGlassSurface(
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp,
    normalFill: Color = Color(0x12FFFFFF),
    onClick: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var userMovedFocus by remember { mutableStateOf(false) }
    val showHighlight = focused && userMovedFocus
    val scale by animateFloatAsState(if (showHighlight) 1.01f else 1f, tween(240), label = "mediaInfoFocus")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(if (showHighlight) Color(0x0AFFFFFF) else normalFill)
            .border(if (showHighlight) 1.5.dp else 1.dp, if (showHighlight) ThemeRuntime.glassEdgeStrong else ThemeRuntime.glassEdge, RoundedCornerShape(corner))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) userMovedFocus = true
                false
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(showHighlight) }
}

@Composable
private fun MediaGlassCard(title: String, icon: ImageVector, count: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x12FFFFFF)).border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(14.dp)).focusGroup()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color(0xD9FFFFFF), modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color(0xEBFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                count?.let { Text(it, color = Color(0x73FFFFFF), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun DetailRows(rows: List<Pair<String, String>>) {
    Column {
        rows.forEachIndexed { index, (label, value) ->
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color(0x80FFFFFF), fontSize = 11.sp, modifier = Modifier.width(72.dp))
                Text(value, color = Color(0xEBFFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (index < rows.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(0.5.dp).background(GlassLine))
            }
        }
    }
}

@Composable
private fun VideoCardContent(badges: List<MediaBadge>, rows: List<Pair<String, String>>) {
    if (badges.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { badges.forEach { SpecBadge(it) } }
        if (rows.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
    DetailRows(rows)
}

@Composable
private fun SpecBadge(badge: MediaBadge, containerHeight: androidx.compose.ui.unit.Dp = 20.dp, imageHeight: androidx.compose.ui.unit.Dp = 12.dp) {
    val resource = when (badge) {
        MediaBadge.AtmosDolbyVision -> R.drawable.badge_wm_atmos_dv
        MediaBadge.DolbyVision -> R.drawable.badge_wm_dv
        MediaBadge.Hdr10Plus -> R.drawable.badge_wm_hdr10plus
        MediaBadge.Hdr10 -> R.drawable.badge_wm_hdr10
        MediaBadge.Hdr -> R.drawable.badge_wm_hdr
        MediaBadge.DolbyAtmos -> R.drawable.badge_wm_atmos
        MediaBadge.DtsX -> R.drawable.badge_wm_dtsx
        MediaBadge.DtsHd -> R.drawable.badge_wm_dtshdma
        MediaBadge.TrueHd -> R.drawable.badge_wm_truehd
        MediaBadge.Imax -> R.drawable.badge_wm_imax
        MediaBadge.FourK -> R.drawable.badge_wm_4k
        MediaBadge.FullHd -> R.drawable.badge_wm_1080p
        MediaBadge.Hd720 -> R.drawable.badge_wm_720p
        MediaBadge.Surround71 -> R.drawable.badge_wm_71
        MediaBadge.Surround51 -> R.drawable.badge_wm_51
    }
    val painter = painterResource(resource)
    Box(Modifier.height(containerHeight).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Image(painter, contentDescription = badge.name, contentScale = ContentScale.Fit,
            modifier = Modifier.height(imageHeight).aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height))
    }
}

private fun tagBadges(tags: List<String>): List<MediaBadge> = tags.mapNotNull { tag ->
    when (normalizeMediaTag(tag)) {
        "DOLBYVISIONATMOS", "ATMOSDOLBYVISION", "DVATMOS" -> MediaBadge.AtmosDolbyVision
        "DOLBYVISION", "DV", "DOVI" -> MediaBadge.DolbyVision
        "HDR10PLUS" -> MediaBadge.Hdr10Plus
        "HDR10" -> MediaBadge.Hdr10
        "HDR" -> MediaBadge.Hdr
        "DOLBYATMOS", "ATMOS" -> MediaBadge.DolbyAtmos
        "DTSX" -> MediaBadge.DtsX
        "DTSHD", "DTSHDMA" -> MediaBadge.DtsHd
        "TRUEHD" -> MediaBadge.TrueHd
        "IMAX" -> MediaBadge.Imax
        "4K", "UHD", "2160P" -> MediaBadge.FourK
        "1080P", "FULLHD", "FHD" -> MediaBadge.FullHd
        "720P", "HD720" -> MediaBadge.Hd720
        "71", "SURROUND71" -> MediaBadge.Surround71
        "51", "SURROUND51" -> MediaBadge.Surround51
        else -> null
    }
}.distinct()

private fun videoBadges(stream: MediaStream, audio: MediaStream?): List<MediaBadge> = buildList {
    val range = normalizeMediaTag(stream.videoRangeType)
    val profile = normalizeMediaTag(stream.profile)
    val display = normalizeMediaTag(stream.displayTitle)
    val title = normalizeMediaTag(stream.title)
    val rangeIsDolbyVision = range in setOf(
        "DOVI", "DOVIWITHHDR10", "DOVIWITHHDR10PLUS", "DOVIWITHHLG", "DOVIWITHSDR",
        "DOVIWITH", "DOVIWITHHDR10PLUS", "DOLBYVISION", "DOVIINVALID",
    ) || range.startsWith("DOVI")
    val profileIsDolbyVision = stream.dvProfile?.let { it > 0 } == true ||
        profile.contains("DVHE") || profile.contains("DVH1") || profile.contains("DVPROFILE") || profile.contains("DOLBYVISION")
    val displayIsDolbyVision = display.contains("DOLBYVISION") || display.contains("DOVI") ||
        title.contains("DOLBYVISION") || title.contains("DOVI")
    val isDolbyVision = rangeIsDolbyVision || profileIsDolbyVision || displayIsDolbyVision
    val audioTags = normalizeMediaTag(listOfNotNull(audio?.title, audio?.displayTitle, audio?.codec, audio?.profile).joinToString(" "))
    val videoTags = normalizeMediaTag(listOfNotNull(stream.title, stream.displayTitle, stream.codec).joinToString(" "))
    val hdrTags = listOf(range, profile, display, title).joinToString(" ")
    val isAtmos = audioTags.contains("ATMOS") || videoTags.contains("ATMOS")
    when {
        isAtmos && isDolbyVision -> add(MediaBadge.AtmosDolbyVision)
        isDolbyVision -> add(MediaBadge.DolbyVision)
        "HDR10PLUS" in hdrTags -> add(MediaBadge.Hdr10Plus)
        "HDR10" in hdrTags -> add(MediaBadge.Hdr10)
        "HDR" in hdrTags -> add(MediaBadge.Hdr)
    }
    when {
        isAtmos && isDolbyVision -> Unit
        isAtmos -> add(MediaBadge.DolbyAtmos)
        audioTags.contains("DTSX") -> add(MediaBadge.DtsX)
        audioTags.contains("DTSHDMA") -> add(MediaBadge.DtsHd)
        audioTags.contains("TRUEHD") -> add(MediaBadge.TrueHd)
    }
    if (videoTags.contains("IMAX")) add(MediaBadge.Imax)
    when {
        (stream.width ?: 0) >= 3840 || (stream.height ?: 0) >= 2160 -> add(MediaBadge.FourK)
        (stream.width ?: 0) >= 1920 || (stream.height ?: 0) >= 1080 -> add(MediaBadge.FullHd)
        (stream.width ?: 0) >= 1280 || (stream.height ?: 0) >= 720 -> add(MediaBadge.Hd720)
    }
    when (audio?.channels) {
        8 -> add(MediaBadge.Surround71)
        6 -> add(MediaBadge.Surround51)
    }
}

private fun normalizeMediaTag(value: String?): String = value.orEmpty()
    .uppercase()
    .filter(Char::isLetterOrDigit)

private fun videoResolution(source: MediaSource, stream: MediaStream?): String? = when {
    stream?.width != null && stream.height != null -> "${stream.width} × ${stream.height}"
    else -> source.resolution?.takeIf(String::isNotBlank)
}

private fun formatFrameRate(frameRate: Double): String {
    val number = if (frameRate % 1.0 == 0.0) frameRate.toInt().toString() else "%.3f".format(frameRate).trimEnd('0').trimEnd('.')
    return "$number fps"
}

private fun channelLabel(channels: Int?): String? = when (channels) { 6 -> "5.1"; 8 -> "7.1"; else -> null }

private fun audioLabel(stream: com.lumina.tv.data.MediaStream): String =
    listOfNotNull(stream.language ?: "未知语言", stream.codec, stream.channels?.let { "${it}ch" }).joinToString(" ")

private fun subtitleKey(stream: com.lumina.tv.data.MediaStream): String = "${stream.index}:0"

private fun formatBitrate(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)
    bitsPerSecond >= 1_000 -> "%.0f kbps".format(bitsPerSecond / 1_000.0)
    else -> "$bitsPerSecond bps"
}

private fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1_024.0)
}

@Composable
private fun TrackSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)
                    .shadow(20.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF20C0E16))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("播放设置", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                content()
                Spacer(Modifier.height(2.dp))
                FocusSurface(Modifier.fillMaxWidth().height(44.dp), corner = 22.dp, onClick = onDismiss) { focused ->
                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Text("完成", color = TextMain, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: MediaItem, url: String?) {
    Box(
        Modifier
            .width(190.dp)
            .height(285.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x1AFFFFFF))
    ) {
        AsyncImage(
            model = url, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EpisodeCard(ep: MediaItem, url: String?, onClick: () -> Unit) {
    FocusSurface(modifier = Modifier.width(200.dp), corner = 10.dp, scaleTo = 1.05f, onClick = onClick) { focused ->
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1AFFFFFF))
            ) {
                AsyncImage(
                    model = url, contentDescription = ep.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                if (ep.positionTicks > 0) {
                    val progress = ep.runtimeTicks?.takeIf { it > 0L }
                        ?.let { (ep.positionTicks.toFloat() / it).coerceIn(0f, 1f) } ?: 0.45f
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Accent))
                }
                val episodeNumber = ep.indexNumber
                if (episodeNumber != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("第 ${episodeNumber} 集", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1)
                    }
                }
                ep.runtimeTicks?.takeIf { it > 0L }?.let { ticks ->
                    val totalMinutes = ticks / 600_000_000L
                    val duration = if (totalMinutes >= 60) "${totalMinutes / 60}:${(totalMinutes % 60).toString().padStart(2, '0')}" else "${totalMinutes} 分钟"
                    Text(
                        duration,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1,
                    )
                }
            }
            if (ep.indexNumber == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    ep.name, color = TextMain.copy(alpha = if (focused) 1f else 0.9f),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
