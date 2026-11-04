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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
    val client = AppRuntime.client
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var keyword by remember { mutableStateOf(initialKeyword) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    suspend fun doSearch(k: String) {
        if (k.isBlank() || client == null) return
        loading = true
        results = runCatching { client.getItems(searchTerm = k, limit = 60) }.getOrDefault(emptyList())
        loading = false
        searched = true
    }

    LaunchedEffect(initialKeyword) {
        AppRuntime.screenLabel = "搜索"
        if (initialKeyword.isNotBlank()) doSearch(initialKeyword)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
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
                modifier = Modifier.weight(1f),
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

        Spacer(Modifier.height(20.dp))

        when {
            loading -> LoadingView(text = "搜索中…")
            results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.id }) { item ->
                    PosterCard(item = item, imageUrl = client?.imageUrl(item), width = 150.dp) {
                        onOpenDetail(item.seriesId ?: item.id)
                    }
                }
            }
            searched -> EmptyView("没有找到「$keyword」相关内容")
            else -> EmptyView("输入关键词开始搜索")
        }
    }
}
