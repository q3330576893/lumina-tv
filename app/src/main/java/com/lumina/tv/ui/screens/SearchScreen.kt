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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.EmptyView
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.LoadingView
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val client = AppRuntime.client
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var keyword by rememberSaveable { mutableStateOf(initialKeyword) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var focusedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var yearFilter by rememberSaveable { mutableIntStateOf(0) }
    var ratingFilter by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var unwatchedOnly by rememberSaveable { mutableStateOf(false) }
    val resultGridState = rememberLazyGridState()

    suspend fun doSearch(k: String) {
        if (k.isBlank() || client == null) return
        loading = true
        results = runCatching { client.getItems(searchTerm = k, limit = 60) }.getOrDefault(emptyList())
        loading = false
        searched = true
    }

    LaunchedEffect(initialKeyword) {
        AppRuntime.screenLabel = "搜索"
        val query = keyword.ifBlank { initialKeyword }
        if (query.isNotBlank()) doSearch(query)
    }

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索电影、剧集…", color = TextSub.copy(alpha = 0.7f), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = GlassLine,
                    cursorColor = Accent,
                ),
                modifier = Modifier.weight(1f).widthIn(min = 0.dp),
            )
            Spacer(Modifier.width(12.dp))
            FocusSurface(
                modifier = Modifier.height(52.dp).width(96.dp),
                corner = 16.dp,
                onClick = { if (keyword.isNotBlank()) scope.launch { doSearch(keyword) } },
            ) { focused ->
                Box(
                    Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) { Text("搜索", color = TextMain, fontSize = 13.sp) }
            }
            Spacer(Modifier.width(10.dp))
            FocusSurface(
                modifier = Modifier.height(52.dp).width(96.dp),
                corner = 16.dp,
                onClick = onBack,
            ) { focused ->
                Box(
                    Modifier.fillMaxSize().background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) { Text("返回", color = TextMain, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "电影", "剧集").forEachIndexed { index, label ->
                FocusSurface(Modifier.height(34.dp).width(74.dp), corner = 17.dp, scaleTo = 1f,
                    onClick = { typeFilter = index }) { focused ->
                    Box(Modifier.fillMaxSize().background(
                        if (typeFilter == index) Accent.copy(alpha = .9f) else if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                        RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                        Text(label, color = if (typeFilter == index) Color(0xFF06101F) else TextMain, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部年份", "2020+", "2010-2019", "2020 以前").forEachIndexed { index, label ->
                FilterChip(label, yearFilter == index) { yearFilter = index }
            }
            listOf("不限分", "6+", "7+", "8+").forEachIndexed { index, label ->
                FilterChip(label, ratingFilter == index) { ratingFilter = index }
            }
            FilterChip("收藏", favoritesOnly) { favoritesOnly = !favoritesOnly }
            FilterChip("未观看", unwatchedOnly) { unwatchedOnly = !unwatchedOnly }
        }

        val visibleResults = remember(results, keyword, typeFilter, yearFilter, ratingFilter, favoritesOnly, unwatchedOnly) {
            val q = keyword.trim()
            results.filter { item ->
                val typeMatches = when (typeFilter) {
                    1 -> item.type.equals("Movie", true)
                    2 -> item.type.equals("Series", true) || item.type.equals("Episode", true)
                    else -> true
                }
                val yearMatches = when (yearFilter) {
                    1 -> (item.year ?: 0) >= 2020
                    2 -> item.year in 2010..2019
                    3 -> (item.year ?: Int.MAX_VALUE) < 2020
                    else -> true
                }
                val score = item.rating ?: item.communityRating ?: 0.0
                val ratingMatches = score >= listOf(0.0, 6.0, 7.0, 8.0)[ratingFilter]
                val textMatches = q.isBlank() || item.name.contains(q, true) ||
                    runCatching {
                        val full = Pinyin.toPinyin(item.name, "").filterNot(Char::isWhitespace)
                        val initials = Pinyin.toPinyin(item.name, " ").split(' ')
                            .mapNotNull { it.firstOrNull() }.joinToString("")
                        val needle = q.filterNot(Char::isWhitespace)
                        full.contains(needle, true) || initials.contains(needle, true)
                    }.getOrDefault(false)
                typeMatches && yearMatches && ratingMatches && textMatches &&
                    (!favoritesOnly || item.isFavorite) && (!unwatchedOnly || !item.played)
            }
        }
        val groups = remember(visibleResults) {
            listOf("电影" to visibleResults.filter { it.type.equals("Movie", true) },
                "剧集" to visibleResults.filter { it.type.equals("Series", true) || it.type.equals("Episode", true) },
                "其他" to visibleResults.filterNot { it.type.equals("Movie", true) || it.type.equals("Series", true) || it.type.equals("Episode", true) })
                .filter { it.second.isNotEmpty() }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> LoadingView(text = "搜索中…")
            visibleResults.isNotEmpty() -> LazyVerticalGrid(
                state = resultGridState,
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { (group, groupItems) ->
                item(key = "header:$group", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(group, color = TextMain, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                }
                items(groupItems, key = { it.id }) { item ->
                    PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp,
                        requestInitialFocus = item.id == focusedItemId || (focusedItemId == null && item == visibleResults.firstOrNull()),
                        onFocused = { focusedItemId = item.id }) {
                        onOpenDetail(item.seriesId ?: item.id)
                    }
                }
                }
            }
            searched -> EmptyView("没有找到「$keyword」相关内容")
            else -> EmptyView("输入关键词开始搜索")
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    FocusSurface(Modifier.height(32.dp).width(78.dp), corner = 16.dp, scaleTo = 1f, onClick = onClick) { focused ->
        Box(Modifier.fillMaxSize().background(
            if (active) Accent.copy(alpha = .9f) else if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF),
            RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) Color(0xFF06101F) else TextMain, fontSize = 10.sp, maxLines = 1)
        }
    }
}
