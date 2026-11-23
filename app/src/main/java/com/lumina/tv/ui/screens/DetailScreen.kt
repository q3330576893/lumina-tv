package com.lumina.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.lumina.tv.ui.theme.TypeScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.lumina.tv.data.catchingRequest
import com.lumina.tv.data.withRequestBudget
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
import androidx.compose.runtime.collectAsState
import com.lumina.tv.data.identityScope
import com.lumina.tv.data.DetailRecommendationCache
import com.lumina.tv.data.RelatedCacheKey
import com.lumina.tv.data.previewCacheKey
import com.lumina.tv.data.previewSourceVersion
import com.lumina.tv.data.resolveTmdbMetadataItem
import com.lumina.tv.data.tmdbMediaPath

private val failedDetailBackgroundKeys = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
private val hlsUnsupportedItems = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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

internal suspend fun loadDetailRecommendations(
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
    val account = identityScope(client.baseUrl, client.userId) ?: return emptyList()
    val revision = DetailRecommendationCache.revision(account)
    val cacheKey = RelatedCacheKey(account, itemId, type, currentBucket.toString(), genreKey)
    DetailRecommendationCache.get(cacheKey, dayIndex)?.let { cached ->
        PageLog.add("detailRelated id=$itemId top12=${cached.take(12).joinToString(",") { "${it.id}:${it.name}" }} cache=hit")
        return cached
    }
    var completedSimilar: List<MediaItem> = emptyList()
    var requestFailed = false
    val result = try {
        withTimeoutOrNull(5_000L) {
        withRequestBudget(5_000L) {
            val similar = async {
                catchingRequest { client.getSimilarItems(itemId, 20) }
                    .also { completedSimilar = it.getOrDefault(emptyList()) }
            }
            val genreCandidates = mutableListOf<MediaItem>()
            val mainGenre = genres.firstOrNull()
            if (mainGenre != null) {
                for (page in 0..2) {
                    val pageResult = catchingRequest {
                        client.getItems(
                            types = type, sortBy = "Random", limit = 60, startIndex = page * 60,
                            genre = mainGenre, requestTimeoutSeconds = 5,
                        )
                    }
                    if (pageResult.isFailure) {
                        requestFailed = true
                        break
                    }
                    val pageItems = pageResult.getOrThrow()
                    genreCandidates += pageItems
                    if (pageItems.size < 60) break
                }
            }
            val queryItems = genreCandidates.distinctBy { it.id }
            val queriedIds = queryItems.mapTo(mutableSetOf()) { it.id }
            val similarResult = similar.await()
            if (similarResult.isFailure) requestFailed = true
            val supplements = similarResult.getOrDefault(emptyList()).filterNot { it.id in queriedIds }
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
    currentCoroutineContext().ensureActive()
    val results = if (result == null) {
        val fallback = completedSimilar
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
    // Partial data is useful now, but must not suppress a retry after recovery.
    if (!requestFailed) DetailRecommendationCache.put(cacheKey, results, dayIndex, revision)
    else PageLog.add("detailRelated id=$itemId failed=true fallback=partial count=${results.size} cache=skip")
    PageLog.add("detailRelated id=$itemId top12=${results.joinToString(",") { "${it.id}:${it.name}" }}")
    return results
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun DetailScreen(
    id: String,
    previewActive: Boolean = true,
    onPlay: (MediaItem) -> Unit,
    onPlayWithSource: (MediaItem, String) -> Unit,
    onPlayFromStart: (MediaItem, String?) -> Unit,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sourceAudioCaps = remember { catchingRequest { detect(context) }.getOrDefault(AudioCapabilities(emptyMap())) }
    val client = AppRuntime.client
    val accountScope = client?.let { identityScope(it.baseUrl, it.userId) }
    val recommendationRevisions by DetailRecommendationCache.revisions.collectAsState()
    val recommendationRevision = recommendationRevisions[accountScope] ?: 0L
    val detailHazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember(id, accountScope) { mutableStateOf(false) }
    var itemNotFound by remember(id, accountScope) { mutableStateOf(false) }
    var detailRetry by remember(id, accountScope) { mutableStateOf(0) }
    var item by remember(id, client) { mutableStateOf<MediaItem?>(null) }
    var seasonDirectory by remember { mutableStateOf(beginSeasonDirectoryLoad<MediaItem>()) }
    val seasons = seasonDirectory.seasons
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var episodeLoading by remember { mutableStateOf(false) }
    var episodeLoadError by remember { mutableStateOf<String?>(null) }
    var episodeReloadTick by remember { mutableIntStateOf(0) }
    var selectedSeason by remember { mutableStateOf<MediaItem?>(null) }
    val episodePages = remember(id, client, LibraryCache.userDataRevision) { EpisodePickerPages<MediaItem>() }
    fun selectSeason(seriesId: String, season: MediaItem?) {
        val sid = season?.id
        val cached = season?.let { episodePages.cached(sid) { LibraryCache.episodesOf(seriesId, sid) } }
        episodes = cached ?: season?.let { LibraryCache.episodesStaleOf(seriesId, sid) }.orEmpty()
        episodeLoading = season != null && cached == null && episodes.isEmpty()
        episodeLoadError = null
        selectedSeason = season
    }
    var metadataItem by remember(id, client) { mutableStateOf<MediaItem?>(null) }
    var tmdb by remember(id, client) { mutableStateOf<TmdbClient.TmdbInfo?>(null) }
    var mdb by remember(id, accountScope) { mutableStateOf<MdblistClient.Ratings?>(null) }
    var resolvedExternalLinks by remember(id, accountScope) { mutableStateOf<TmdbClient.ExternalLinks?>(null) }
    var mediaInfoExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var sources by remember(id, client) { mutableStateOf<List<MediaSource>>(emptyList()) }
    var sourcesLoading by remember(id, client) { mutableStateOf(true) }
    var previewMetadataReady by remember(id, client) { mutableStateOf(false) }
    var detailPreviewFailureKey by remember(id, accountScope) { mutableStateOf("") }
    var detailPreviewHlsKey by remember(id, accountScope) { mutableStateOf("") }
    val previewStages = remember(id, accountScope) { mutableMapOf<String, String?>() }
    var detailPreviewUrl by remember(id, accountScope) { mutableStateOf<String?>(null) }
    var detailPreviewHeaders by remember(id, accountScope) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var detailPreviewKey by remember(id, accountScope) { mutableStateOf("") }
    var detailPreviewIsTrailer by remember(id, accountScope) { mutableStateOf(false) }
    var detailPreviewStartMs by remember(id, accountScope) { mutableStateOf<Long?>(null) }
    var detailVideoPlaying by remember(id, accountScope) { mutableStateOf(false) }
    var detailPreviewFailure by remember(id, accountScope) { mutableStateOf<String?>(null) }
    var detailPreviewFromStage by remember(id, accountScope) { mutableStateOf("hls") }
    var detailPreviewStage by remember(id, accountScope) { mutableStateOf<String?>(null) }
    var detailPreviewRequestKey by remember(id, accountScope) { mutableStateOf("") }
    var detailPreviewFailures by remember(id, accountScope) { mutableStateOf<List<String>>(emptyList()) }
    var detailPreviewRetry by remember(id, accountScope) { mutableIntStateOf(0) }
    val previewResults = remember(id, accountScope) { mutableMapOf<String, Triple<String?, String, Boolean>>() }
    val previewHeaderResults = remember(id, accountScope) { mutableMapOf<String, Map<String, String>>() }
    val previewStartTimes = remember(id, accountScope) { mutableMapOf<String, Long?>() }
    var similar by remember(id, accountScope) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var similarLoading by remember(id, accountScope) { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var sourceMenuWasOpen by remember { mutableStateOf(false) }
    var requestedSourceId by remember(id, client) { mutableStateOf(AppRuntime.prefs?.lastSourceFor(id)) }
    val selectedSourceId = requestedSourceId?.takeIf { selected -> sources.any { it.id == selected } }
    val selectedSourceLabel = sources.firstOrNull { it.id == selectedSourceId }?.displayLabel ?: "自动选择"
    fun selectSource(sourceId: String?) {
        requestedSourceId = sourceId?.takeIf { selected -> sources.any { it.id == selected } }
        AppRuntime.prefs?.rememberSource(id, requestedSourceId)
    }
    LaunchedEffect(id, sources, sourcesLoading) {
        if (!sourcesLoading && requestedSourceId != null && selectedSourceId == null) {
            selectSource(null)
        }
    }
    var showSettingsPicker by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var pickerTitle by remember { mutableStateOf("") }
    var pickerOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pickerCurrentKey by remember { mutableStateOf<String?>(null) }
    var pickerAction by remember { mutableStateOf<(String) -> Unit>({}) }
    val trackSource = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
    var selectedAudioIndex by remember(id, trackSource) { mutableStateOf(AppRuntime.prefs?.audioTrackFor(id, trackSource)) }
    var selectedSubtitleKey by remember(id, trackSource) { mutableStateOf(
        AppRuntime.prefs?.subtitlePreferenceFor(id, trackSource)?.let {
            when (it) { "off" -> "-1"; "auto" -> "auto"; else -> it.removePrefix("track:emby:") }
        }) }
    // 单集的版本选择
    var episodeTarget by remember { mutableStateOf<MediaItem?>(null) }
    var episodeSources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var episodeSourcesLoading by remember { mutableStateOf(false) }
    var showEpisodeSourceMenu by remember { mutableStateOf(false) }
    val favoriteAction = remember(id, client) { UserFlagMutation() }
    val playedAction = remember(id, client) { UserFlagMutation() }
    val isFav = favoriteAction.value
    val isPlayed = playedAction.value
    val pageActive = remember(id, client) { java.util.concurrent.atomic.AtomicBoolean(true) }
    androidx.compose.runtime.DisposableEffect(pageActive) {
        onDispose { pageActive.set(false) }
    }
    fun toggleUserFlag(action: UserFlagMutation, favorite: Boolean) {
        val c = client ?: return
        action.toggle(
            // Finish the accepted server write and invalidate caches even after leaving this page.
            scope = kotlinx.coroutines.CoroutineScope(AppRuntime.appScope.coroutineContext + Dispatchers.Main.immediate),
            request = { target -> if (favorite) c.setFavorite(id, target) else c.setPlayed(id, target) },
            onSuccess = { actual ->
                if (AppRuntime.client === c) {
                    LibraryCache.invalidateUserData(context.applicationContext, c.baseUrl, c.userId, id,
                        favorite = actual.takeIf { favorite }, played = actual.takeUnless { favorite })
                    if (pageActive.get()) item = item?.let {
                        if (favorite) it.copy(isFavorite = actual) else it.copy(played = actual)
                    }
                }
            },
            onFailure = { failure ->
                PageLog.add("userData update failed item=$id field=${if (favorite) "favorite" else "played"} error=${failure.javaClass.simpleName}")
                if (pageActive.get() && AppRuntime.client === c) {
                    android.widget.Toast.makeText(context, "${if (favorite) "收藏" else "已看状态"}更新失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    var focusedRelatedId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val castRowState = rememberLazyListState()
    val similarRowState = rememberLazyListState()
    val detailScrollState = rememberScrollState()
    val playFocus = remember { FocusRequester() }
    val overviewFocus = remember { FocusRequester() }
    var overviewExpanded by rememberSaveable(item?.id) { mutableStateOf(false) }
    var overviewFocused by remember { mutableStateOf(false) }
    val playFromStartFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    var playButtonFocused by remember { mutableStateOf(false) }
    var topRegionFocused by remember { mutableStateOf(false) }

    LaunchedEffect(playButtonFocused, topRegionFocused) {
        if (playButtonFocused || topRegionFocused) detailScrollState.scrollTo(0)
    }

    LaunchedEffect(id, accountScope) {
        detailPreviewFromStage = "hls"
        detailPreviewFailures = emptyList()
        detailPreviewStage = null
        detailPreviewRetry = 0
        detailPreviewFailure = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, id, accountScope) {
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

    LaunchedEffect(id, accountScope, detailRetry) {
        seasonDirectory = beginSeasonDirectoryLoad()
        previewMetadataReady = false
        val cacheRevision = LibraryCache.userDataRevision
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
            seasonDirectory = completeSeasonDirectoryLoad(Result.success(cached.seasons))
            sources = cached.sources
            favoriteAction.syncFromServer(cached.item?.isFavorite ?: false)
            playedAction.syncFromServer(cached.item?.played ?: false)
            selectSeason(id, cached.seasons.firstOrNull())
            loading = false
            sourcesLoading = true
            val startedAt = System.currentTimeMillis()
            PageLog.add("detailLoad id=$id stage=cache-main ms=0")
            val freshItemJob = launch {
                val freshItem = catchingRequest { c.getItem(id) }.getOrNull()
                if (freshItem != null) {
                    item = freshItem
                    favoriteAction.syncFromServer(freshItem.isFavorite)
                    playedAction.syncFromServer(freshItem.played)
                }
                PageLog.add("detailLoad id=$id stage=main-refresh ms=${System.currentTimeMillis() - startedAt}")
            }
            val sourceJob = launch {
                sources = catchingRequest { c.getMediaSources(id) }.getOrDefault(cached.sources)
                sourcesLoading = false
                PageLog.add("detailLoad id=$id stage=sources ms=${System.currentTimeMillis() - startedAt}")
            }
            val seasonRefreshJob = if (cached.item?.type == "Series") launch {
                val refreshedSeasons = catchingRequest { c.getSeasons(id) }.getOrNull()
                if (!refreshedSeasons.isNullOrEmpty()) {
                    seasonDirectory = completeSeasonDirectoryLoad(Result.success(refreshedSeasons))
                    if (selectedSeason == null) selectSeason(id, refreshedSeasons.first())
                }
                PageLog.add("detailLoad id=$id stage=seasons-refresh ms=${System.currentTimeMillis() - startedAt}")
            } else null
            launch {
                joinAll(freshItemJob, sourceJob)
                seasonRefreshJob?.join()
                previewMetadataReady = true
                if (cached.item?.type != "Series" || seasonDirectory.seasons.isNotEmpty()) {
                    LibraryCache.putDetail(
                        id,
                        cached.copy(
                            item = item ?: cached.item,
                            seasons = seasonDirectory.seasons,
                            sources = sources,
                            at = System.currentTimeMillis(),
                        ),
                        cacheRevision,
                    )
                }
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
        val mainWaitNotice = launch {
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
            val finished = withTimeoutOrNull(5_000L) { withRequestBudget(5_000L) {
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
            true
            } }
            if (finished == null) loadError = EmbyException("Detail request deadline exceeded")
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
        if (it0 != null) {
            favoriteAction.syncFromServer(it0.isFavorite)
            playedAction.syncFromServer(it0.played)
        }
        loading = false
        loadFailed = false
        sourcesLoading = true
        PageLog.add("detailLoad id=$id stage=main-ready ms=${System.currentTimeMillis() - loadStartedAt}")
        if (it0 != null) {
            val seasonJob = launch {
                val seasonResult: Result<List<MediaItem>> = if (it0.type == "Series") catchingRequest { c.getSeasons(id) }
                    else Result.success(emptyList())
                seasonDirectory = if (it0.type == "Series" && seasonResult.getOrNull()?.isEmpty() == true) {
                    SeasonDirectoryLoadState(error = IllegalStateException("empty-season-directory"))
                } else {
                    completeSeasonDirectoryLoad(seasonResult)
                }
                val loadedSeasons = seasonDirectory.seasons
                selectSeason(id, loadedSeasons.firstOrNull())
                PageLog.add("detailLoad id=$id stage=seasons ms=${System.currentTimeMillis() - loadStartedAt}")
            }
            val sourceJob = launch {
                sources = catchingRequest { c.getMediaSources(id) }.getOrDefault(emptyList())
                sourcesLoading = false
                PageLog.add("detailLoad id=$id stage=sources ms=${System.currentTimeMillis() - loadStartedAt}")
            }
            launch {
                joinAll(seasonJob, sourceJob)
                previewMetadataReady = true
                if (it0.type != "Series" || seasons.isNotEmpty()) {
                    LibraryCache.putDetail(id, LibraryCache.DetailData(it0, seasons, episodes, sources, System.currentTimeMillis()), cacheRevision)
                }
                PageLog.add("detailLoad id=$id stage=complete ms=${System.currentTimeMillis() - loadStartedAt}")
            }
        }
    }

    LaunchedEffect(id, accountScope, item?.type, item?.genres, item?.productionLocations, item?.originalLanguage, recommendationRevision) {
        val c = client ?: return@LaunchedEffect
        val current = item ?: return@LaunchedEffect
        similar = emptyList()
        similarLoading = true
        val startedAt = System.currentTimeMillis()
        try {
            val result = loadDetailRecommendations(c, id, current)
            if (accountScope != null && recommendationRevision == DetailRecommendationCache.revision(accountScope)) similar = result
            PageLog.add("detailLoad id=$id stage=similar ms=${System.currentTimeMillis() - startedAt}")
        } finally { similarLoading = false }
    }

    LaunchedEffect(previewActive, id, item?.id, selectedSeason?.id, episodeReloadTick, episodePages) {
        if (!previewActive) return@LaunchedEffect
        val cacheRevision = LibraryCache.userDataRevision
        val c = client ?: return@LaunchedEffect
        val it0 = item ?: return@LaunchedEffect
        if (it0.type == "Series" && selectedSeason != null) {
            val sid = selectedSeason?.id
            cachedSeasonEpisodeState(episodePages.cached(sid) { LibraryCache.episodesOf(it0.id, sid) })?.let { hit ->
                if (hit.episodes != episodes) episodes = hit.episodes
                episodeLoading = false
                episodeLoadError = null
                return@LaunchedEffect
            }
            val stale = LibraryCache.episodesStaleOf(it0.id, sid)
            episodes = stale ?: beginSeasonEpisodeLoad<MediaItem>().episodes
            episodeLoading = episodes.isEmpty()
            episodeLoadError = null
            val loaded = episodePages.fetch(sid, { c.getEpisodes(it0.id, sid) }) { list ->
                LibraryCache.putEpisodes(it0.id, sid, list, cacheRevision)
            }
            episodeLoading = false
            if (loaded.error == null) episodes = loaded.episodes
            episodeLoadError = loaded.error?.message ?: if (loaded.error != null) "剧集读取失败，请重试" else null
            if (loaded.error != null) {
                android.widget.Toast.makeText(context, "剧集读取失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(id, client, item) {
        metadataItem = null
        val current = item ?: return@LaunchedEffect
        metadataItem = resolveTmdbMetadataItem(current) { seriesId ->
            LibraryCache.detail(seriesId)?.item ?: client?.getItem(seriesId)
        }
    }

    // Optional ratings never delay the main item's season/source jobs, on either cache path.
    LaunchedEffect(id, client, metadataItem, item?.rating, item?.communityRating) {
        tmdb = null
        val current = item ?: return@LaunchedEffect
        val metadata = metadataItem ?: return@LaunchedEffect
        if (current.rating == null && current.communityRating == null) {
            tmdb = catchingRequest { TmdbClient.lookup(metadata.type, metadata.name, metadata.year) }.getOrNull()
        }
    }

    LaunchedEffect(id, client, metadataItem) {
        resolvedExternalLinks = null
        val current = metadataItem ?: return@LaunchedEffect
        resolvedExternalLinks = catchingRequest {
            TmdbClient.externalLinks(
                type = current.type,
                title = current.name,
                year = current.year,
                embyImdbId = current.providerIds["imdb"],
                embyTmdbId = current.providerIds["tmdb"],
                embyTvdbId = current.providerIds["tvdb"],
            )
        }.getOrNull()
    }

    LaunchedEffect(id, client, metadataItem, resolvedExternalLinks) {
        mdb = null
        val current = metadataItem ?: return@LaunchedEffect
        val imdbId = current.providerIds["imdb"]?.takeIf(String::isNotBlank)
            ?: resolvedExternalLinks?.imdbId?.takeIf(String::isNotBlank)
        val tmdbId = current.providerIds["tmdb"]?.takeIf(String::isNotBlank)
            ?: resolvedExternalLinks?.tmdbId?.takeIf(String::isNotBlank)
        if (imdbId == null && tmdbId == null) {
            mdb = null
            return@LaunchedEffect
        }
        mdb = catchingRequest { MdblistClient.ratings(current.type, imdbId, tmdbId) }.getOrNull()
    }

    val previewMovieSource = remember(item?.type, sources, selectedSourceId) {
        if (item?.type == "Series") null else {
            sources.firstOrNull { it.id == selectedSourceId && it.streams.any { stream -> stream.type == "Video" } }
                ?: sources.firstOrNull { source -> source.streams.any { it.type == "Video" } }
        }
    }
    val sourceKey = remember(previewMovieSource) { previewMovieSource?.let { it.id to previewSourceVersion(it) } }
    val firstPreviewEpisode = if (item?.type == "Series") episodes.firstOrNull() else null
    val episodeKey = firstPreviewEpisode?.let { Triple(it.id, it.contentVersion, it.runtimeTicks) }
    val seasonKey = selectedSeason?.id?.takeIf { item?.type == "Series" }
    val backgroundMode = AppRuntime.prefs?.detailBackgroundVideo ?: "auto"
    LaunchedEffect(previewActive, id, accountScope, item?.contentVersion, item?.runtimeTicks, previewMetadataReady, seasonKey, episodeKey, sourceKey, detailPreviewRetry, backgroundMode) {
        // Gate before cache reuse and every preview-only request (Episodes/trailers/sources/URL).
        if (backgroundMode == "off") {
            detailPreviewUrl = null
            detailPreviewHeaders = emptyMap()
            detailPreviewKey = ""
            detailPreviewIsTrailer = false
            detailPreviewStartMs = null
            detailVideoPlaying = false
            detailPreviewFailure = null
            detailPreviewFailures = emptyList()
            detailPreviewFromStage = "hls"
            detailPreviewStage = null
            detailPreviewRequestKey = ""
            detailPreviewFailureKey = ""
            detailPreviewHlsKey = ""
            detailPreviewRetry = 0
            previewResults.clear()
            previewHeaderResults.clear()
            previewStartTimes.clear()
            previewStages.clear()
            PageLog.add("detailPreview stage=skip reason=mode-off")
            return@LaunchedEffect
        }
        if (!previewActive) {
            detailVideoPlaying = false
            return@LaunchedEffect
        }
        if (!previewMetadataReady) return@LaunchedEffect
        val c = client ?: run { PageLog.add("detailPreview stage=skip reason=client-null"); return@LaunchedEffect }
        val current = item ?: run { PageLog.add("detailPreview stage=skip reason=item-null"); return@LaunchedEffect }
        val requestKey = "$accountScope|${current.id}|${current.contentVersion}|${current.runtimeTicks}|$sourceKey|$seasonKey|$episodeKey|$backgroundMode"
        if (detailPreviewRequestKey != requestKey) {
            detailPreviewRequestKey = requestKey
            detailPreviewFromStage = "hls"
            detailPreviewFailures = emptyList()
            detailPreviewStage = null
        }
        if (detailPreviewRetry == 0) previewResults[requestKey]?.takeIf { !it.first.isNullOrBlank() }?.let { cached ->
            detailPreviewUrl = cached.first
            detailPreviewHeaders = previewHeaderResults[requestKey].orEmpty()
            detailPreviewKey = cached.second
            detailPreviewIsTrailer = cached.third
            detailPreviewStartMs = previewStartTimes[requestKey]
            detailPreviewStage = previewStages[requestKey]
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=cache-hit")
            return@LaunchedEffect
        }
        detailPreviewUrl = null
        detailVideoPlaying = false
        if (detailPreviewRetry == 0) detailPreviewFailure = null
        val previewItem = when (current.type) {
            "Series" -> firstPreviewEpisode ?: if (seasonKey == null) {
                catchingRequest { c.getEpisodes(current.id, null).firstOrNull() }.getOrNull()
            } else null
            else -> current
        } ?: run {
            detailPreviewKey = "$accountScope:${current.id}:unknown"
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=preview-item-null")
            return@LaunchedEffect
        }
        val trailer = catchingRequest { c.getLocalTrailers(previewItem.id).firstOrNull() }.getOrNull()
        val sourceItem = trailer ?: previewItem
        val previewSource = if (sourceItem.id == current.id) {
            if (sources.isEmpty() && sourcesLoading) {
                PageLog.add("detailPreview stage=skip reason=no-cache-source")
                return@LaunchedEffect
            }
            previewMovieSource
        } else {
            catchingRequest { c.getMediaSources(sourceItem.id) }.getOrDefault(emptyList())
                .firstOrNull { source -> source.streams.any { it.type == "Video" } }
        }
        if (previewSource == null && sourceItem.id == current.id && sourcesLoading) {
            PageLog.add("detailPreview stage=skip reason=no-cache-source")
            return@LaunchedEffect
        }
        val resolvedPreviewSource = previewSource ?: run {
            detailPreviewKey = "$accountScope:${previewItem.id}:${sourceItem.id}:unknown"
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=preview-source-null")
            return@LaunchedEffect
        }
        val previewKind = if (trailer != null) "trailer" else "clip"
        val contentKey = previewCacheKey(c.baseUrl, c.userId, sourceItem, resolvedPreviewSource, previewKind) ?: return@LaunchedEffect
        val hlsKey = "$contentKey:hls"
        detailPreviewHlsKey = hlsKey
        if (backgroundMode != "720p" && hlsKey in hlsUnsupportedItems && detailPreviewFromStage == "hls") {
            detailPreviewFromStage = "direct"
            PageLog.add("detailPreview stage=skip reason=hls-known-broken")
        }
        detailPreviewIsTrailer = trailer != null
        detailPreviewStartMs = if (trailer != null) 0L else {
            AppRuntime.prefs?.introSecondsFor(previewItem.id)?.takeIf { it > 0 }?.times(1000L)
        }
        detailPreviewFailureKey = "$contentKey:$backgroundMode"
        val failedAt = failedDetailBackgroundKeys[detailPreviewFailureKey]
        if (failedAt != null && System.currentTimeMillis() - failedAt < DETAIL_PREVIEW_FAILURE_TTL_MS) {
            detailVideoPlaying = false
            PageLog.add("detailPreview stage=skip reason=blacklist")
            return@LaunchedEffect
        }
        if (failedAt != null) failedDetailBackgroundKeys.remove(detailPreviewFailureKey)
        val resolution = catchingRequest {
            c.resolveDetailPreviewUrl(
                sourceItem.id,
                resolvedPreviewSource.id,
                backgroundMode,
                fromStage = detailPreviewFromStage,
            )
        }.getOrNull()
        detailPreviewFailures = (detailPreviewFailures + resolution?.failures.orEmpty()).distinct()
        if (backgroundMode != "720p" && resolution?.failures.orEmpty().any { it == "hls:not-m3u8" }) {
            hlsUnsupportedItems.add(hlsKey)
        }
        detailPreviewUrl = resolution?.url
        detailPreviewHeaders = resolution?.requiredHttpHeaders.orEmpty()
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
            detailPreviewKey = "$contentKey:${resolution?.stage}:$backgroundMode"
            detailPreviewFailure = null
            previewResults[requestKey] = Triple(detailPreviewUrl, detailPreviewKey, detailPreviewIsTrailer)
            previewHeaderResults[requestKey] = detailPreviewHeaders
            previewStartTimes[requestKey] = detailPreviewStartMs
            previewStages[requestKey] = detailPreviewStage
        }
    }

    LaunchedEffect(item?.id) {
        if (item == null) return@LaunchedEffect
        var focused = false
        for (attempt in 0 until 3) {
            withFrameNanos { }
            catchingRequest { playFocus.requestFocus() }
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
            catchingRequest { sourceFocus.requestFocus() }
            sourceMenuWasOpen = false
        }
    }

    val playAction = rememberPlayableItemAction(listOf(id, selectedSourceId, item?.id), active = previewActive) { target, fromStart ->
        val sourceId = selectedSourceId.takeIf { target.id == id }
        when {
            fromStart -> onPlayFromStart(target, sourceId)
            sourceId != null -> onPlayWithSource(target, sourceId)
            else -> onPlay(target)
        }
    }

    /** 单集有多个版本时先选版本，只有一个版本才直接播。 */
    fun pickEpisode(ep: MediaItem) {
        if (episodeSourcesLoading || playAction.resolving) return
        if (ep.sourceCount <= 1) {
            playAction.play(ep)
            return
        }
        episodeTarget = ep
        episodeSources = emptyList()
        episodeSourcesLoading = true
        showEpisodeSourceMenu = true
        scope.launch {
            val loaded = catchingRequest { client?.getMediaSources(ep.id) }.getOrDefault(emptyList()).orEmpty()
            if (!pageActive.get() || AppRuntime.client !== client || episodeTarget?.id != ep.id || !showEpisodeSourceMenu) return@launch
            episodeSources = loaded
            episodeSourcesLoading = false
            if (episodeSources.isEmpty()) {
                showEpisodeSourceMenu = false
                playAction.play(ep)
            }
        }
    }

    // 部分服务器在剧集条目上不带 People：换列表接口再取一次，避免"主演"整块消失
    LaunchedEffect(id, item) {
        val current = item ?: return@LaunchedEffect
        if (current.people.isNotEmpty()) return@LaunchedEffect
        val extra = catchingRequest { client?.getItemPeople(id) }.getOrNull().orEmpty()
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
            // Navigation animations keep this detail page composed; remove the preview immediately.
            if (previewActive && backgroundMode != "off") DetailVideoBackground(
                url = detailPreviewUrl,
                mediaKey = detailPreviewKey,
                previewStage = detailPreviewStage,
                requestHeaders = client?.previewHeaders(detailPreviewUrl, detailPreviewHeaders).orEmpty(),
                requestClient = client?.previewHttpClient,
                playing = !loading,
                onStatus = { status ->
                    detailVideoPlaying = status == "play"
                    if (status.startsWith("skip=") && status != "skip=no-preview-url" && detailPreviewStage != null && detailPreviewKey.isNotBlank()) {
                        val failedStage = detailPreviewStage ?: "unknown"
                        val reason = "$failedStage:playback-failed"
                        detailPreviewFailures = (detailPreviewFailures + reason).distinct()
                        PageLog.add("detailPreview stage=$failedStage verdict=playback-failed")
                        if (failedStage == "hls" && status.contains("#EXTM3U", ignoreCase = true) && AppRuntime.prefs?.detailBackgroundVideo != "720p") {
                            hlsUnsupportedItems.add(detailPreviewHlsKey)
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
                Text("\u52a0\u8f7d\u5931\u8d25", color = TextMain, fontSize = TypeScale.sp16)
                Spacer(Modifier.height(14.dp))
                FocusSurface(Modifier.width(156.dp).height(42.dp), corner = 12.dp, requestFocus = true, onClick = {
                    loading = true
                    loadFailed = false
                    detailRetry++
                }) { focused ->
                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("\u70b9\u51fb\u91cd\u8bd5", color = TextMain, fontSize = TypeScale.sp13)
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
                    Poster(it0!!, client?.imageUrl(it0, 480), width = 149.dp)
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.weight(1f).padding(top = 30.dp)) {
                        Text(
                            if (it0.type == "Episode" && it0.name.isNotBlank()) it0.name else it0.seriesName ?: it0.name,
                            color = TextMain, fontSize = TypeScale.sp32, fontWeight = FontWeight.Bold,
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
                            Text(infoParts.joinToString("   "), color = TextSub, fontSize = TypeScale.sp12)
                        }
                        mdb?.let { ratings ->
                            val ratingLine = listOfNotNull(
                                ratings.imdb?.takeIf { it > 0 }?.let { "IMDb %.1f".format(it) },
                                ratings.tomato?.takeIf { it > 0 }?.let { "番茄 $it%" },
                                ratings.metacritic?.takeIf { it > 0 }?.let { "MC $it" },
                                ratings.trakt?.takeIf { it > 0 }?.let { "Trakt $it" },
                            ).joinToString(" · ")
                            if (ratingLine.isNotBlank()) Text(ratingLine, color = TextSub, fontSize = TypeScale.sp11)
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
                                fontSize = TypeScale.sp10,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        it0.overview?.let {
                            Box(
                                Modifier.fillMaxWidth()
                                    .animateContentSize(animationSpec = tween(260))
                                    .focusRequester(overviewFocus)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        down = playFocus
                                    }
                                    .onFocusChanged { overviewFocused = it.isFocused }
                                    .background(
                                        if (overviewFocused) Color(0x14FFFFFF) else Color.Transparent,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { overviewExpanded = !overviewExpanded }
                                    .focusable(),
                            ) {
                                Text(
                                    it, color = Color(0xFFC9D5E8), fontSize = TypeScale.sp13, lineHeight = 21.sp,
                                    maxLines = if (overviewExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
                                    style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xD9000000), Offset(1f, 2f), 6f)),
                                )
                                if (overviewFocused) {
                                    Text(
                                        if (overviewExpanded) "收起" else "展开",
                                        color = Color(0xFFC9D5E8),
                                        fontSize = TypeScale.sp11,
                                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.focusGroup().focusProperties {
                                up = if (it0.overview != null) overviewFocus else FocusRequester.Cancel
                            },
                        ) {
                            FocusSurface(
                                modifier = Modifier.height(52.dp).width(128.dp).focusProperties {
                                    if (it0.positionTicks > 0L) right = playFromStartFocus
                                },
                                corner = 23.dp,
                                focusRequester = playFocus,
                                onFocused = { playButtonFocused = true },
                                onFocusChanged = { playButtonFocused = it },
                                onClick = {
                                    playAction.play(it0)
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
                                    Text(if (playAction.resolving) "解析单集…" else "播放", color = Color(0xFF06101F), fontSize = TypeScale.sp17, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (it0.positionTicks > 0L) {
                                Spacer(Modifier.width(10.dp))
                                FocusSurface(
                                    modifier = Modifier.height(52.dp).width(108.dp).focusProperties {
                                        left = playFocus
                                    },
                                    corner = 23.dp,
                                    focusRequester = playFromStartFocus,
                                    onClick = {
                                        playAction.play(it0, fromStart = true)
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
                                        Text("从头播放", color = Color.White, fontSize = TypeScale.sp14)
                                    }
                                }
                            }
                            if (sources.isNotEmpty()) {
                                Spacer(Modifier.width(12.dp))
                                var sourceFocused by remember { mutableStateOf(false) }
                                val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.first()
                                val video = source.streams.firstOrNull { it.type.equals("Video", true) }
                                val bitrate = (video?.bitRate?.takeIf { it > 0 } ?: source.bitrate?.takeIf { it > 0 })
                                    ?.let { formatBitrate(it) }
                                val sourceLabel = listOfNotNull(
                                    selectedSourceLabel,
                                    source.resolution?.takeIf(String::isNotBlank),
                                    bitrate,
                                ).joinToString(" · ")
                                FocusSurface(
                                    modifier = Modifier
                                        .height(46.dp)
                                        .animateContentSize(
                                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                                        )
                                        .widthIn(min = 46.dp, max = 300.dp),
                                    corner = 23.dp,
                                    focusRequester = sourceFocus,
                                    onFocusChanged = { sourceFocused = it },
                                    onClick = { showSourceMenu = true },
                                ) { focused ->
                                    Row(
                                        Modifier.fillMaxHeight().background(
                                            if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                        ).padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Layers, null, tint = TextMain, modifier = Modifier.size(18.dp))
                                        if (focused) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                sourceLabel,
                                                color = TextMain,
                                                fontSize = TypeScale.sp13,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 252.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            // 收藏
                            FocusSurface(
                                modifier = Modifier.size(46.dp),
                                corner = 23.dp,
                                onClick = { toggleUserFlag(favoriteAction, favorite = true) },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (isFav) Accent.copy(alpha = 0.85f) else if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (favoriteAction.pending) CircularProgressIndicator(Modifier.size(20.dp), color = TextMain, strokeWidth = 2.dp)
                                    else Icon(
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
                                onClick = { toggleUserFlag(playedAction, favorite = false) },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(
                                        if (isPlayed) Accent.copy(alpha = 0.85f) else if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (playedAction.pending) CircularProgressIndicator(Modifier.size(20.dp), color = TextMain, strokeWidth = 2.dp)
                                    else Icon(
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
                                    Text("返回", color = TextMain, fontSize = TypeScale.sp15)
                                }
                            }
                        }
                    }
                }

                if (seasons.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("季数与剧集", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(seasons, key = { it.id }) { s ->
                            val active = s.id == selectedSeason?.id
                            FocusSurface(
                                modifier = Modifier.height(34.dp),
                                corner = 17.dp,
                                scaleTo = 1.06f,
                                onClick = {
                                    if (selectedSeason?.id != s.id) {
                                        selectSeason(id, s)
                                    } else if (episodeLoadError != null) {
                                        episodeLoadError = null
                                        episodeLoading = episodes.isEmpty()
                                        episodeReloadTick++
                                    }
                                },
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
                                        fontSize = TypeScale.sp12,
                                    )
                                }
                            }
                        }
                    }
                } else if (it0?.type == "Series") {
                    Spacer(Modifier.height(18.dp))
                    when (seasonDirectory.status) {
                        SeasonDirectoryStatus.LOADING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                            Text("季目录加载中", color = TextSub, fontSize = TypeScale.sp12)
                        }
                        SeasonDirectoryStatus.FAILURE -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("季目录读取失败，请重试", color = TextSub, fontSize = TypeScale.sp12)
                            FocusSurface(
                                Modifier.height(36.dp).width(100.dp), corner = 10.dp, scaleTo = 1.04f,
                                onClick = {
                                    seasonDirectory = beginSeasonDirectoryLoad()
                                    detailRetry++
                                },
                            ) { focused ->
                                Box(
                                    Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("重试", color = TextMain, fontSize = TypeScale.sp12) }
                            }
                        }
                        SeasonDirectoryStatus.EMPTY -> Text("暂无季目录", color = TextSub, fontSize = TypeScale.sp12)
                        SeasonDirectoryStatus.SUCCESS -> Unit
                    }
                }

                if (episodeLoading) {
                    Row(
                        Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                        Text("剧集加载中", color = TextSub, fontSize = TypeScale.sp12)
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
                if (episodeLoadError != null) {
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("剧集读取失败，请重试", color = TextSub, fontSize = TypeScale.sp12)
                        FocusSurface(
                            Modifier.height(36.dp).width(100.dp), corner = 10.dp, scaleTo = 1.04f,
                            onClick = {
                                episodeLoadError = null
                                episodeLoading = episodes.isEmpty()
                                episodeReloadTick++
                            },
                        ) { focused ->
                            Box(
                                Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                                contentAlignment = Alignment.Center,
                            ) { Text("重试", color = TextMain, fontSize = TypeScale.sp12) }
                        }
                    }
                } else if (!episodeLoading && episodes.isEmpty() && selectedSeason != null) {
                    Text("本季暂无剧集", color = TextSub, fontSize = TypeScale.sp12, modifier = Modifier.padding(top = 12.dp))
                }
                val cast = it0!!.people.filter { person -> person.name.isNotBlank() }.take(16)
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(22.dp))
                    Text("演职人员", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast, key = { it.id ?: "${it.name}-${it.role.orEmpty()}" }) { person ->
                            PersonCard(person = person, imageUrl = client?.personImageUrl(person))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("相关推荐", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Medium)
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
                        color = TextSub, fontSize = TypeScale.sp12,
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
                                onClick = { playAction.invalidate(); onOpenDetail(related.id) },
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

                val providerIds = metadataItem?.providerIds.orEmpty()
                val resolvedLinks = resolvedExternalLinks
                val searchTitle = (metadataItem?.name ?: it0!!.seriesName?.takeIf(String::isNotBlank) ?: it0!!.name)
                    .takeIf(String::isNotBlank)
                    ?: sources.firstOrNull()?.name.orEmpty()
                val encodedTitle = URLEncoder.encode(searchTitle.ifBlank { "文件名" }, "UTF-8")
                val tmdbId = (providerIds["tmdb"] ?: resolvedLinks?.tmdbId)?.takeIf(String::isNotBlank)
                val imdbId = (providerIds["imdb"] ?: resolvedLinks?.imdbId)?.takeIf(String::isNotBlank)
                val mediaType = metadataItem?.type?.let(::tmdbMediaPath)
                val providerLinks = listOf(
                    "TMDB" to (tmdbId?.takeIf { mediaType != null }?.let { "https://www.themoviedb.org/$mediaType/$it" }
                        ?: "https://www.themoviedb.org/search?query=$encodedTitle"),
                    "IMDB" to (imdbId?.let { "https://www.imdb.com/title/$it/" }
                        ?: "https://www.imdb.com/find/?q=$encodedTitle"),
                    "ROTTEN TOMATOES" to "https://www.rottentomatoes.com/search?search=$encodedTitle",
                    "DOUBAN" to "https://search.douban.com/movie/subject_search?search_text=$encodedTitle",
                )
                if (providerLinks.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("外部链接", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Medium)
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
                                onClick = { catchingRequest { uriHandler.openUri(uri) } },
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
                                    Text(label, color = if (focused) Color.White else Color(0xD9FFFFFF), fontSize = TypeScale.sp12, fontWeight = FontWeight.Medium, letterSpacing = 0.96.sp)
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
                        SettingRow("版本", selectedSourceLabel, enabled = sources.isNotEmpty()) {
                            showTrackSheet = false
                            showSourceMenu = true
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
                                    key.toIntOrNull()?.let { AppRuntime.prefs?.rememberAudioTrack(currentItem.id, it, activeSource) }
                                }
                                showSettingsPicker = true
                            }
                        }
                        val subtitleTracks = activeSource?.streams.orEmpty().filter { it.type == "Text" || it.type == "Subtitle" }
                        SettingRow("字幕", if (selectedSubtitleKey == "-1") "关闭" else subtitleTracks.firstOrNull { subtitleKey(it) == selectedSubtitleKey }?.language ?: subtitleTracks.firstOrNull()?.language ?: "默认", enabled = subtitleTracks.isNotEmpty()) {
                            pickerTitle = "选择字幕"
                            pickerOptions = listOf("auto" to "自动", "-1" to "关闭") + subtitleTracks.map { subtitleKey(it) to (it.language ?: "未知语言") }
                            pickerCurrentKey = selectedSubtitleKey
                            pickerAction = { key ->
                                selectedSubtitleKey = key
                                AppRuntime.prefs?.rememberSubtitleTrack(currentItem.id, when (key) {
                                    "-1" -> "off"; "auto" -> "auto"; else -> "track:emby:$key"
                                }, activeSource)
                            }
                            showSettingsPicker = true
                        }
                    }
                }
            }
        }

        if (showSettingsPicker) {
            PickerDialog(
                title = pickerTitle,
                options = pickerOptions,
                currentKey = pickerCurrentKey,
                onPick = { key ->
                    pickerAction(key)
                    showSettingsPicker = false
                },
                onClose = { showSettingsPicker = false },
            )
        }

        // 单集版本选择
        if (showEpisodeSourceMenu && episodeTarget != null) {
            val ep = episodeTarget!!
            SourceMenu(
                sources = episodeSources,
                sourcesLoading = episodeSourcesLoading,
                currentSourceId = AppRuntime.prefs?.lastSourceFor(ep.id)?.takeIf { selected -> episodeSources.any { it.id == selected } },
                engine = AppRuntime.prefs?.playerEngine ?: "media3",
                audioCaps = sourceAudioCaps,
                title = "选择版本 · ${ep.name}",
                onPick = { sid ->
                    if (pageActive.get() && AppRuntime.client === client && episodeTarget?.id == ep.id) {
                        playAction.invalidate()
                        showEpisodeSourceMenu = false
                        AppRuntime.prefs?.rememberSource(ep.id, sid.takeIf { it.isNotBlank() })
                        if (sid.isBlank()) onPlay(ep) else onPlayWithSource(ep, sid)
                    }
                },
                onClose = { showEpisodeSourceMenu = false; episodeSourcesLoading = false; episodeTarget = null },
            )
        }

        // 版本选择菜单
        if (showSourceMenu) {
            SourceMenu(
                sources = sources,
                sourcesLoading = sourcesLoading,
                currentSourceId = selectedSourceId,
                engine = AppRuntime.prefs?.playerEngine ?: "media3",
                audioCaps = sourceAudioCaps,
                onPick = { sid ->
                    selectSource(sid)
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
                    Text(person.name.take(1), color = Accent, fontSize = TypeScale.sp28, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(person.name, color = Color.White.copy(alpha = 0.9f), fontSize = TypeScale.sp12, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(person.role ?: person.type ?: "演职人员", color = Color.White.copy(alpha = 0.6f), fontSize = TypeScale.sp10, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    PickerDialog(title, listOf("" to "自动选择") + options, currentSourceId.orEmpty(), onPick, onClose, loading = sourcesLoading)
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
    val entries = options.distinctBy { it.first }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val entryFocuses = entries.map { (id, _) -> requesters.getOrPut(id) { FocusRequester() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val initialKey = entries.firstOrNull()?.first
        LaunchedEffect(initialKey) {
            withFrameNanos { }
            initialKey?.let { catchingRequest { requesters.getValue(it).requestFocus() } }
        }
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.width(560.dp), corner = 22.dp) {
                Column(Modifier.padding(22.dp)) {
                    Text(title, color = TextMain, fontSize = TypeScale.sp16, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    if (loading) Text("正在读取列表…", color = TextSub, fontSize = TypeScale.sp12)
                    else if (options.isEmpty()) Text("没有可选项", color = TextSub, fontSize = TypeScale.sp12)
                    Column(
                        Modifier.heightIn(max = 46.dp * 8 + 40.dp).verticalScroll(rememberScrollState()).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        entries.forEachIndexed { index, (id, label) ->
                          key(id) {
                            FocusSurface(
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                                    .focusRequester(entryFocuses[index])
                                    .focusProperties {
                                        up = pickerNeighbor(index, -1, entries.size)?.let(entryFocuses::get) ?: FocusRequester.Cancel
                                        down = pickerNeighbor(index, 1, entries.size)?.let(entryFocuses::get) ?: FocusRequester.Cancel
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                    },
                                corner = 12.dp, scaleTo = 1f, onClick = { onPick(id) },
                            ) { focused ->
                                Row(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (id == currentKey) Icons.Rounded.CheckCircle else Icons.Rounded.Layers, null, tint = if (id == currentKey) Accent else TextSub, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(label, color = TextMain, fontSize = TypeScale.sp13, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
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
            Text("$label：$value", color = if (enabled) TextMain else TextSub.copy(alpha = 0.65f), fontSize = TypeScale.sp13, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("›", color = if (enabled) Accent else TextSub.copy(alpha = 0.5f), fontSize = TypeScale.sp20)
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
            Text("媒体信息", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                summaryBadges.forEach { SpecBadge(it) }
                if (summaryBadges.isEmpty()) {
                    if (technicalSummary.isNotBlank()) Text(technicalSummary, color = if (hasFocus) TextMain.copy(alpha = 0.92f) else TextSub, fontSize = TypeScale.sp12, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (expanded) "收起" else "展开", color = TextSub, fontSize = TypeScale.sp11)
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
    val inputModeManager = LocalInputModeManager.current
    val showHighlight = focused && inputModeManager.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (showHighlight) 1.01f else 1f, tween(240), label = "mediaInfoFocus")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(if (showHighlight) Color(0x0AFFFFFF) else normalFill)
            .border(if (showHighlight) 1.5.dp else 1.dp, if (showHighlight) ThemeRuntime.glassEdgeStrong else ThemeRuntime.glassEdge, RoundedCornerShape(corner))
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
                Text(title, color = Color(0xEBFFFFFF), fontSize = TypeScale.sp13, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                count?.let { Text(it, color = Color(0x73FFFFFF), fontSize = TypeScale.sp11) }
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
                Text(label, color = Color(0x80FFFFFF), fontSize = TypeScale.sp11, modifier = Modifier.width(72.dp))
                Text(value, color = Color(0xEBFFFFFF), fontSize = TypeScale.sp13, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

private fun subtitleKey(stream: com.lumina.tv.data.MediaStream): String = "${stream.index}"

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
                Text("播放设置", color = TextMain, fontSize = TypeScale.sp16, fontWeight = FontWeight.SemiBold)
                content()
                Spacer(Modifier.height(2.dp))
                FocusSurface(Modifier.fillMaxWidth().height(44.dp), corner = 22.dp, onClick = onDismiss) { focused ->
                    Box(Modifier.fillMaxSize().background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Text("完成", color = TextMain, fontSize = TypeScale.sp13)
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: MediaItem, url: String?, width: Dp) {
    Box(
        Modifier
            .width(width)
            .height(width * 1.5f)
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
                        Text("第 ${episodeNumber} 集", color = Color.White.copy(alpha = 0.9f), fontSize = TypeScale.sp11, maxLines = 1)
                    }
                }
                ep.runtimeTicks?.takeIf { it > 0L }?.let { ticks ->
                    val totalMinutes = ticks / 600_000_000L
                    val duration = if (totalMinutes >= 60) "${totalMinutes / 60}:${(totalMinutes % 60).toString().padStart(2, '0')}" else "${totalMinutes} 分钟"
                    Text(
                        duration,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 2.dp),
                        color = Color.White.copy(alpha = 0.7f), fontSize = TypeScale.sp10, maxLines = 1,
                    )
                }
            }
            if (ep.indexNumber == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    ep.name, color = TextMain.copy(alpha = if (focused) 1f else 0.9f),
                    fontSize = TypeScale.sp12, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
