package com.lumina.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import com.lumina.tv.data.MediaPerson
import com.lumina.tv.data.MediaSource
import com.lumina.tv.data.TmdbClient
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    var sources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var isPlayed by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        similar = emptyList()
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
            // Cache renders immediately; refresh people and recommendations quietly.
            coroutineScope {
                val fresh = async { runCatching { c.getItem(id) }.getOrNull() }
                val related = async { runCatching { c.getSimilarItems(id, 12) }.getOrDefault(emptyList()) }
                fresh.await()?.let { item = it }
                similar = related.await()
            }
            return@LaunchedEffect
        }

        loading = true
        // 详情 / 分季 / 多源并行拉取，避免串行等待
        val it0 = coroutineScope {
            val sA = async { runCatching { c.getSeasons(id) }.getOrDefault(emptyList()) }
            val srcA = async { runCatching { c.getMediaSources(id) }.getOrDefault(emptyList()) }
            val relatedA = async { runCatching { c.getSimilarItems(id, 12) }.getOrDefault(emptyList()) }
            val main = c.getItem(id)
            similar = relatedA.await()
            if (main != null && main.type == "Series") {
                val ss = sA.await()
                seasons = ss
                val first = ss.firstOrNull()
                selectedSeason = first
                LibraryCache.episodesOf(id, first?.id)?.let { episodes = it }
            } else if (main != null) {
                sources = srcA.await()
            }
            main
        }
        item = it0
        loading = false
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

    Box(Modifier.fillMaxSize()) {
        val it0 = item
        if (it0 != null) {
            AsyncImage(
                model = client?.backdropUrl(it0, 1920),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(42.dp),
            )
            // 模糊海报 → 主色铺满：重模糊后叠深色渐层，页面颜色随封面走
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0x4D000000), Color(0x99000000), Color(0xE6000000), Color(0xF20B0A09))
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x4D000000), Color(0x00000000)))
                )
            )
        }

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
                            it0.seriesName ?: it0.name,
                            color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row {
                            FocusSurface(
                                modifier = Modifier.height(46.dp).width(150.dp),
                                corner = 23.dp,
                                onClick = {
                                    val target = if (it0.type == "Series") {
                                        episodes.firstOrNull { !it.played && it.positionTicks > 0 }
                                            ?: episodes.firstOrNull { !it.played }
                                            ?: episodes.firstOrNull()
                                    } else it0
                                    if (target != null) onPlay(target)
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
                            if (sources.size > 1) {
                                Spacer(Modifier.width(12.dp))
                                FocusSurface(
                                    modifier = Modifier.height(46.dp).width(130.dp),
                                    corner = 23.dp,
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
                                        Text("版本", color = TextMain, fontSize = 13.sp)
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

                val cast = it0!!.people.filter { person -> person.name.isNotBlank() }.take(16)
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(22.dp))
                    Text("主演与主创", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast, key = { it.id ?: "${it.name}-${it.role.orEmpty()}" }) { person ->
                            PersonCard(person = person, imageUrl = client?.personImageUrl(person))
                        }
                    }
                }

                if (similar.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("相关推荐", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(similar, key = { it.id }) { related ->
                            PosterCard(
                                item = related,
                                imageUrl = client?.imageUrl(related),
                                width = 138.dp,
                                onClick = { onOpenDetail(related.id) },
                            )
                        }
                    }
                }

                if (seasons.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(ep, client?.imageUrl(ep, 360)) { onPlay(ep) }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        // 版本选择菜单
        if (showSourceMenu) {
            val it0 = item
            SourceMenu(
                sources = sources,
                onPick = { sid ->
                    showSourceMenu = false
                    if (it0 != null) onPlayWithSource(it0, sid)
                },
                onClose = { showSourceMenu = false },
            )
        }
    }
}

@Composable
private fun PersonCard(person: MediaPerson, imageUrl: String?) {
    FocusSurface(
        modifier = Modifier.width(112.dp),
        corner = 14.dp,
        scaleTo = 1.04f,
        onClick = {},
    ) { focused ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(44.dp))
                    .background(if (focused) Color(0x334C8DFF) else Color(0x1AFFFFFF)),
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
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        com.lumina.tv.ui.components.GlassPanel(Modifier.width(560.dp), corner = 22.dp) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("选择版本", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                if (sources.isEmpty()) {
                    Text("正在读取版本列表…", color = TextSub, fontSize = 12.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { s ->
                        FocusSurface(
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            corner = 12.dp, scaleTo = 1.0f,
                            onClick = { onPick(s.id) },
                        ) { focused ->
                            Row(
                                Modifier.fillMaxSize()
                                    .background(
                                        if (focused) Color(0x2EFFFFFF) else Color(0x14FFFFFF),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Layers, null, tint = Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(s.displayLabel, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                FocusSurface(
                    modifier = Modifier.width(120.dp).height(42.dp),
                    corner = 21.dp,
                    onClick = onClose,
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) { Text("取消", color = TextMain, fontSize = 13.sp) }
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
