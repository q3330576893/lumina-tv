package com.lumina.tv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaPerson
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.DetailVideoBackground
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumina.tv.ui.components.GlassPanel
import androidx.compose.ui.platform.LocalUriHandler
import java.net.URLEncoder

private val failedDetailBackgroundKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

@Composable
fun DetailScreen(
    id: String,
    onPlay: (MediaItem) -> Unit,
    onPlayWithSource: (MediaItem, String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val client = AppRuntime.client
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var seasons by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<MediaItem?>(null) }
    var tmdb by remember { mutableStateOf<TmdbClient.TmdbInfo?>(null) }
    var tmdbExternalIds by remember(id) { mutableStateOf<TmdbClient.ExternalIds?>(null) }
    var mediaInfoExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var detailPreviewUrl by remember(id) { mutableStateOf<String?>(null) }
    var detailPreviewKey by remember(id) { mutableStateOf("") }
    var detailPreviewIsTrailer by remember(id) { mutableStateOf(false) }
    var detailPreviewStartMs by remember(id) { mutableStateOf<Long?>(null) }
    var detailBackgroundStatus by remember(id) { mutableStateOf("no-url") }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var sourceMenuWasOpen by remember { mutableStateOf(false) }
    var selectedSourceId by remember(id) { mutableStateOf<String?>(null) }
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
    val playFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }

    LaunchedEffect(id) {
        similar = emptyList()
        showSourceMenu = false
        AppRuntime.screenLabel = "详情"
        val c = client ?: return@LaunchedEffect

        // 命中缓存：立即渲染，不再转圈
        val cached = LibraryCache.detail(id)
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
            // Cache renders immediately; refresh people and recommendations quietly.
            coroutineScope {
                val fresh = async { runCatching { c.getItem(id) }.getOrNull() }
                val related = async { runCatching { c.getSimilarItems(id, 12) }.getOrDefault(emptyList()) }
                val freshSources = async {
                    if (cached.sources.isEmpty()) {
                        runCatching { c.getMediaSources(id) }.getOrDefault(emptyList())
                    } else cached.sources
                }
                sources = freshSources.await()
                sourcesLoading = false
                val freshItem = fresh.await()
                if (freshItem != null) item = freshItem
                LibraryCache.putDetail(
                    id,
                    cached.copy(item = freshItem ?: cached.item, sources = sources, at = System.currentTimeMillis()),
                )
                similar = related.await()
            }
            return@LaunchedEffect
        }

        loading = true
        sourcesLoading = true
        // 详情 / 分季 / 多源并行拉取，避免串行等待
        val it0 = coroutineScope {
            val sA = async { runCatching { c.getSeasons(id) }.getOrDefault(emptyList()) }
            val srcA = async { runCatching { c.getMediaSources(id) }.getOrDefault(emptyList()) }
            val relatedA = async { runCatching { c.getSimilarItems(id, 12) }.getOrDefault(emptyList()) }
            val main = c.getItem(id)
            similar = relatedA.await()
            sources = srcA.await()
            if (main != null && main.type == "Series") {
                val ss = sA.await()
                seasons = ss
                val first = ss.firstOrNull()
                selectedSeason = first
                LibraryCache.episodesOf(id, first?.id)?.let { episodes = it }
            }
            main
        }
        item = it0
        loading = false
        sourcesLoading = false
        LibraryCache.putDetail(
            id,
            LibraryCache.DetailData(
                item = it0, seasons = seasons, episodes = episodes,
                sources = sources, at = System.currentTimeMillis(),
            )
        )
        // TMDB 补充：仅当 Emby 自身无评分时异步拉取，失败静默
        if (it0 != null && it0.rating == null && it0.communityRating == null) {
            tmdb = TmdbClient.lookup(it0.type, it0.seriesName ?: it0.name, it0.year)
        }
        if (it0 != null) {
            isFav = it0.isFavorite
            isPlayed = it0.played
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

    LaunchedEffect(id, item?.providerIds?.get("tmdb")) {
        val current = item ?: return@LaunchedEffect
        val tmdbId = current.providerIds["tmdb"] ?: return@LaunchedEffect
        tmdbExternalIds = runCatching { TmdbClient.externalIds(current.type, tmdbId) }.getOrNull()
    }

    LaunchedEffect(id, item?.type, sources, episodes) {
        val current = item ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        detailPreviewUrl = null
        val previewItem = when (current.type) {
            "Series" -> episodes.firstOrNull() ?: runCatching {
                c.getEpisodes(current.id, selectedSeason?.id).firstOrNull()
            }.getOrNull()
            else -> current
        } ?: run {
            detailPreviewKey = "${current.id}:unknown"
            detailBackgroundStatus = "skip=url"
            failedDetailBackgroundKeys += detailPreviewKey
            return@LaunchedEffect
        }
        if (failedDetailBackgroundKeys.any { it.startsWith("${previewItem.id}:") }) {
            detailPreviewKey = failedDetailBackgroundKeys.first { it.startsWith("${previewItem.id}:") }
            detailBackgroundStatus = "skip=err"
            return@LaunchedEffect
        }
        val trailer = runCatching { c.getLocalTrailers(previewItem.id).firstOrNull() }.getOrNull()
        val sourceItem = trailer ?: previewItem
        val previewSource = if (sourceItem.id == current.id && sources.isNotEmpty()) {
            sources.firstOrNull { source -> source.streams.any { it.type == "Video" } }
        } else {
            runCatching { c.getMediaSources(sourceItem.id) }.getOrDefault(emptyList())
                .firstOrNull { source -> source.streams.any { it.type == "Video" } }
        } ?: run {
            detailPreviewKey = "${previewItem.id}:${sourceItem.id}:unknown"
            detailBackgroundStatus = "skip=url"
            failedDetailBackgroundKeys += detailPreviewKey
            return@LaunchedEffect
        }
        detailPreviewIsTrailer = trailer != null
        detailPreviewStartMs = if (trailer != null) 0L else {
            AppRuntime.prefs?.introSecondsFor(previewItem.id)?.takeIf { it > 0 }?.times(1000L)
        }
        detailPreviewKey = "${previewItem.id}:${sourceItem.id}:${previewSource.id}"
        detailPreviewUrl = c.resolveDetailPreviewUrl(sourceItem.id, previewSource.id)
        if (detailPreviewUrl.isNullOrBlank()) {
            detailBackgroundStatus = "skip=url"
            failedDetailBackgroundKeys += detailPreviewKey
        }
    }

    LaunchedEffect(item?.id) {
        if (item == null) return@LaunchedEffect
        delay(240)
        runCatching { playFocus.requestFocus() }
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

    // 相关推荐为空 → 退化成同类型高分片，避免整栏空白
    LaunchedEffect(id, item) {
        if (similar.isNotEmpty()) return@LaunchedEffect
        val current = item ?: return@LaunchedEffect
        val genre = current.genres.firstOrNull { it.isNotBlank() } ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        similar = runCatching {
            c.getItems(
                types = if (current.type == "Series") "Series" else "Movie,Series",
                sortBy = "CommunityRating", sortOrder = "Descending",
                limit = 14, genre = genre,
            )
        }.getOrDefault(emptyList()).filterNot { it.id == id }.take(12)
    }

    Box(Modifier.fillMaxSize()) {
        val it0 = item
        if (it0 != null) {
            AsyncImage(
                model = client?.backdropUrl(it0, 1920),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(42.dp),
            )
            DetailVideoBackground(
                url = detailPreviewUrl,
                mediaKey = detailPreviewKey,
                playing = !loading,
                onStatus = {
                    detailBackgroundStatus = it
                    if (it.startsWith("skip=") && detailPreviewKey.isNotBlank()) {
                        failedDetailBackgroundKeys += detailPreviewKey
                    }
                },
                startAtMs = detailPreviewStartMs,
                loopWholeItem = detailPreviewIsTrailer,
                modifier = Modifier.fillMaxSize(),
            )
            // 模糊海报 → 主色铺满：重模糊后叠深色渐层，页面颜色随封面走
            val videoPlaying = detailBackgroundStatus == "play"
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

        Text(
            text = "bg:$detailBackgroundStatus",
            color = Color(0xCCFFFFFF),
            fontSize = 8.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
        )

        if (loading) {
            LoadingView(text = "正在读取详情…")
        } else if (item == null) {
            LoadingView(text = "未找到该内容")
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, end = 48.dp, top = 34.dp, bottom = 24.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14FFFFFF), RoundedCornerShape(26.dp))
                        .padding(20.dp)
                ) {
                    Poster(it0!!, client?.imageUrl(it0, 720))
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (it0.type == "Episode" && it0.name.isNotBlank()) it0.name else it0.seriesName ?: it0.name,
                            color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color(0xE6000000), Offset(2f, 3f), 8f)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            listOfNotNull(
                                it0.year?.toString(),
                                it0.subtitle.takeIf { s -> s.isNotBlank() },
                                it0.genres.take(3).joinToString(" / ").takeIf { s -> s.isNotBlank() },
                                (it0.communityRating ?: it0.rating)?.let { r -> "★ %.1f".format(r) },
                                tmdb?.rating?.let { r -> "TMDB ★ %.1f".format(r) },
                            ).joinToString("   "),
                            color = TextSub, fontSize = 12.sp,
                        )
                        if (it0.tags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                it0.tags.forEach { tag ->
                                    Box(
                                        Modifier
                                            .background(
                                                if (tag == "DOLBY VISION") Color(0x33FFB300) else Color(0x2EFFFFFF),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            tag,
                                            color = if (tag == "DOLBY VISION") Color(0xFFFFD54F) else TextMain,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
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
                        Row {
                            FocusSurface(
                                modifier = Modifier.height(46.dp).width(150.dp),
                                corner = 23.dp,
                                focusRequester = playFocus,
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
                                    ),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF06101F), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("播放", color = Color(0xFF06101F), fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                                modifier = Modifier.height(46.dp).width(120.dp),
                                corner = 23.dp,
                                onClick = onBack,
                            ) { focused ->
                                Row(
                                    Modifier.fillMaxSize().background(
                                        if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                                    ),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.ArrowBack, null, tint = TextMain, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("返回", color = TextMain, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                if (seasons.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("季数与剧集", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    LazyRow(state = castRowState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(ep, client?.imageUrl(ep, 360)) { pickEpisode(ep) }
                        }
                    }
                }
                val cast = it0!!.people.filter { person -> person.name.isNotBlank() }.take(16)
                Spacer(Modifier.height(22.dp))
                Text("主演与主创", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (cast.isEmpty()) {
                    Text(
                        "暂无演职人员信息",
                        color = TextSub, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast, key = { it.id ?: "${it.name}-${it.role.orEmpty()}" }) { person ->
                            PersonCard(person = person, imageUrl = client?.personImageUrl(person))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("相关推荐", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (similar.isEmpty()) {
                    Text(
                        "暂无相关推荐",
                        color = TextSub, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    LazyRow(state = similarRowState, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(similar, key = { it.id }) { related ->
                            PosterCard(
                                item = related,
                                imageUrl = client?.imageUrl(related),
                                width = 138.dp,
                                requestInitialFocus = related.id == focusedRelatedId,
                                onFocused = { focusedRelatedId = related.id },
                                onClick = { onOpenDetail(related.id) },
                            )
                        }
                    }
                }

                val activeSource = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()
                if (activeSource != null) {
                    Spacer(Modifier.height(24.dp))
                    Text("媒体信息${activeSource.name.takeIf { sources.size > 1 && it.isNotBlank() }?.let { " · $it" }.orEmpty()}", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val summary = listOfNotNull(
                        activeSource.container?.takeIf(String::isNotBlank)?.uppercase(),
                        activeSource.bitrate?.takeIf { it > 0 }?.let(::formatBitrate),
                        activeSource.size.takeIf { it > 0 }?.let(::formatMediaSize),
                        activeSource.resolution?.takeIf(String::isNotBlank),
                    ).joinToString(" · ").ifBlank { activeSource.displayLabel }
                    FocusSurface(
                        Modifier.fillMaxWidth().height(46.dp),
                        corner = 8.dp,
                        scaleTo = 1.015f,
                        onClick = { mediaInfoExpanded = !mediaInfoExpanded },
                    ) { focused ->
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                summary,
                                color = TextMain,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (mediaInfoExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (mediaInfoExpanded) "收起媒体信息" else "展开媒体信息",
                                tint = if (focused) Accent else TextSub,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (mediaInfoExpanded) {
                        Spacer(Modifier.height(10.dp))
                        val video = activeSource.streams.firstOrNull { it.type == "Video" }
                        val infoLines = buildList {
                            activeSource.container?.takeIf(String::isNotBlank)?.let { add("容器：${it.uppercase()}") }
                            activeSource.bitrate?.takeIf { it > 0 }?.let { add("总码率：${formatBitrate(it)}") }
                            activeSource.size.takeIf { it > 0 }?.let { add("文件大小：${formatMediaSize(it)}") }
                            activeSource.resolution?.takeIf(String::isNotBlank)?.let { add("分辨率：$it") }
                            video?.let { stream ->
                                listOfNotNull(stream.codec?.uppercase(), stream.profile?.takeIf(String::isNotBlank))
                                    .takeIf { it.isNotEmpty() }?.let { add("视频：${it.joinToString(" ")}") }
                                stream.bitDepth?.takeIf { it > 0 }?.let { add("位深：${it} bit") }
                                val color = listOfNotNull(
                                    stream.colorPrimaries?.let(::formatColor),
                                    stream.colorTransfer?.let(::formatColor),
                                    stream.colorSpace?.let(::formatColor),
                                ).distinct()
                                if (color.isNotEmpty()) add("色彩：${color.joinToString(" / ")}")
                                val range = stream.videoRangeType.orEmpty().uppercase()
                                when {
                                    stream.dvProfile != null || "DOVI" in range || "DOLBY" in range || stream.profile?.contains("DV", true) == true -> add("动态范围：Dolby Vision")
                                    "HDR" in range || "HLG" in range || stream.colorTransfer?.contains("2084", true) == true || stream.colorTransfer?.contains("PQ", true) == true ->
                                        add("动态范围：${range.takeIf(String::isNotBlank)?.replace('_', ' ') ?: stream.colorTransfer}")
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            infoLines.forEach { Text(it, color = TextSub, fontSize = 12.sp) }
                            activeSource.audioStreams.forEachIndexed { index, stream ->
                                streamSummary(stream)?.let { track ->
                                    val bitrate = stream.bitRate?.takeIf { it > 0 }?.let { " · ${formatBitrate(it)}" }.orEmpty()
                                    Text("音轨 ${index + 1}：$track$bitrate", color = TextSub, fontSize = 12.sp)
                                }
                            }
                            activeSource.subtitleStreams.forEachIndexed { index, stream ->
                                streamSummary(stream)?.let { track ->
                                    val sourceType = if (stream.isExternal) "外挂" else "内封"
                                    Text("字幕 ${index + 1}（$sourceType）：$track", color = TextSub, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                val providerIds = it0!!.providerIds
                val providerLinks = buildList {
                    (providerIds["imdb"] ?: tmdbExternalIds?.imdbId)?.takeIf(String::isNotBlank)
                        ?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
                    providerIds["tmdb"]?.takeIf(String::isNotBlank)?.let {
                        val type = if (it0.type == "Series") "tv" else "movie"
                        add("TMDB" to "https://www.themoviedb.org/$type/$it")
                    }
                    (providerIds["tvdb"] ?: tmdbExternalIds?.tvdbId)?.takeIf(String::isNotBlank)?.let {
                        val type = if (it0.type == "Series") "series" else "movie"
                        add("TheTVDB" to "https://thetvdb.com/dereferrer/$type/$it")
                    }
                    val title = (it0.seriesName ?: it0.name).takeIf(String::isNotBlank)
                    title?.let {
                        val encoded = URLEncoder.encode(it, "UTF-8")
                        add("豆瓣" to "https://search.douban.com/movie/subject_search?search_text=$encoded&cat=1002")
                    }
                }
                if (providerLinks.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("外部链接", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val uriHandler = LocalUriHandler.current
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        providerLinks.forEach { (label, uri) ->
                            FocusSurface(Modifier.height(38.dp), corner = 8.dp, onClick = { runCatching { uriHandler.openUri(uri) } }) { focused ->
                                Box(
                                    Modifier.background(if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
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
                title = "选择版本 · ${ep.name}",
                onPick = { sid ->
                    showEpisodeSourceMenu = false
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
                onPick = { sid ->
                    selectedSourceId = sid
                    showSourceMenu = false
                },
                onClose = { showSourceMenu = false },
            )
        }
    }
}

@Composable
private fun PersonCard(person: MediaPerson, imageUrl: String?) {
    Box(Modifier.width(112.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(44.dp))
                    .background(Color(0x1AFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(imageUrl, contentDescription = person.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(44.dp)))
                } else {
                    Text(person.name.take(1), color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(person.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(person.role ?: person.type ?: "演职人员", color = TextSub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 多版本选择弹层。 */
@Composable
private fun SourceMenu(
    sources: List<MediaSource>,
    sourcesLoading: Boolean,
    currentSourceId: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    title: String = "选择版本",
) {
    PickerDialog(title, sources.map { it.id to it.displayLabel }, currentSourceId, onPick, onClose, loading = sourcesLoading)
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

private fun audioLabel(stream: com.lumina.tv.data.MediaStream): String =
    listOfNotNull(stream.language ?: "未知语言", stream.codec, stream.channels?.let { "${it}ch" }).joinToString(" ")

private fun subtitleKey(stream: com.lumina.tv.data.MediaStream): String = "${stream.index}:0"

private fun streamSummary(stream: com.lumina.tv.data.MediaStream): String? =
    listOfNotNull(
        stream.codec?.takeIf(String::isNotBlank)?.uppercase(),
        stream.channels?.takeIf { it > 0 }?.let { "${it}ch" },
        stream.language?.takeIf(String::isNotBlank),
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

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

private fun formatColor(value: String): String? = when (value.uppercase()) {
    "BT2020", "BT.2020" -> "BT.2020"
    "SMPTE2084", "SMPTE ST 2084", "PQ" -> "PQ"
    "BT709", "BT.709" -> "BT.709"
    "BT601", "BT.601" -> "BT.601"
    else -> value.takeIf(String::isNotBlank)
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
            .width(196.dp)
            .height(294.dp)
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
    FocusSurface(modifier = Modifier.width(230.dp), corner = 16.dp, scaleTo = 1.05f, onClick = onClick) { focused ->
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF))
            ) {
                AsyncImage(
                    model = url, contentDescription = ep.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                if (ep.positionTicks > 0 && !ep.played) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.45f).height(3.dp).background(Accent))
                }
                if (ep.sourceCount > 1) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("${ep.sourceCount} 版本", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                ep.name, color = if (focused) TextMain else TextMain.copy(alpha = 0.9f),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                ep.subtitle.ifBlank { "第 ${ep.indexNumber ?: 0} 集" },
                color = TextSub, fontSize = 10.sp,
            )
        }
    }
}
