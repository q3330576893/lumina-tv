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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.components.SectionTitle
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val client = AppRuntime.client
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var resume by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var latest by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var movies by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        AppRuntime.screenLabel = "首页"
        if (client == null) {
            error = "尚未登录"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val r = runCatching { client.getResume(20) }.getOrDefault(emptyList())
            val s = runCatching { client.getItems(types = "Series", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) }.getOrDefault(emptyList())
            val m = runCatching { client.getItems(types = "Movie", sortBy = "DateCreated", sortOrder = "Descending", limit = 24) }.getOrDefault(emptyList())
            val l = if (m.isNotEmpty()) m else runCatching { client.getLatest(null, 24) }.getOrDefault(emptyList())
            val f = runCatching { client.getFavorites(24) }.getOrDefault(emptyList())
            resume = r
            latest = l
            movies = m
            series = s
            favorites = f
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    val hero = resume.firstOrNull() ?: movies.firstOrNull() ?: series.firstOrNull()

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
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { Hero(hero, client?.backdropUrl(hero ?: MediaItem("", ""), 1920), onPlay, onOpenDetail) }
                if (resume.isNotEmpty()) {
                    item { RowSection("继续观看", resume) { onOpenDetail(it.id) } }
                }
                if (favorites.isNotEmpty()) {
                    item { RowSection("我的收藏", favorites) { onOpenDetail(it.id) } }
                }
                if (latest.isNotEmpty()) {
                    item { RowSection("最新添加", latest) { onOpenDetail(it.id) } }
                }
                if (movies.isNotEmpty()) {
                    item { RowSection("电影", movies) { onOpenDetail(it.id) } }
                }
                if (series.isNotEmpty()) {
                    item { RowSection("剧集", series) { onOpenDetail(it.id) } }
                }
                item { Spacer(Modifier.height(36.dp)) }
            }
        }

        // 顶部玻璃悬浮栏（Apple TV 风格：内容全宽延伸到栏下）
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xD9070A12), Color(0x00070A12))))
                .padding(start = 34.dp, end = 34.dp, top = 16.dp, bottom = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("琉璃", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val user = AppRuntime.session?.userName ?: ""
                if (user.isNotBlank()) {
                    Text(user, color = TextSub, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            TopButton(Icons.Rounded.Search, "搜索", onSearch)
            Spacer(Modifier.width(10.dp))
            TopButton(Icons.Rounded.Settings, "设置", onSettings)
        }
    }
}

@Composable
private fun TopButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier.size(44.dp),
        corner = 22.dp,
        scaleTo = 1.12f,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = if (focused) Accent else TextMain,
                modifier = Modifier.size(20.dp),
            )
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
            .height(360.dp)
            .clip(RoundedCornerShape(bottomEnd = 34.dp))
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF2070A12), Color(0xB3070A12), Color(0x00070A12)),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x00070A12), Color(0x66070A12), Color(0xF2070A12)),
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 34.dp, bottom = 30.dp, end = 200.dp)
        ) {
            Text(
                "为你推荐",
                color = Accent, fontSize = 11.sp, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.seriesName ?: item.name,
                color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold,
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
            Spacer(Modifier.height(16.dp))
            Row {
                FocusSurface(
                    modifier = Modifier.height(44.dp).width(132.dp),
                    corner = 22.dp,
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
                    modifier = Modifier.height(44.dp).width(112.dp),
                    corner = 22.dp,
                    scaleTo = 1.05f,
                    onClick = {
                        onOpenDetail(item.seriesId ?: item.id)
                    },
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(
                            if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("查看详情", color = TextMain, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowSection(title: String, items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    val client = AppRuntime.client
    Column(Modifier.padding(top = 18.dp)) {
        SectionTitle(title, Modifier.padding(start = 34.dp, bottom = 10.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 34.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.id }) { it ->
                PosterCard(
                    item = it,
                    imageUrl = client?.imageUrl(it),
                    onClick = { onClick(it) },
                )
            }
        }
    }
}
