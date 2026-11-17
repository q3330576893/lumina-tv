package com.lumina.tv.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.FavoritesAnchor
import com.lumina.tv.data.FavoritesPager
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.PosterCard
import com.lumina.tv.ui.theme.TextSub
import kotlinx.coroutines.launch

@Composable
internal fun FavoritesScreen(
    client: EmbyClient,
    pager: FavoritesPager,
    gridState: LazyGridState,
    preview: List<MediaItem>,
    revision: Long,
    firstItemFocus: FocusRequester,
    onOpenDetail: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var restoring by remember { mutableStateOf(true) }
    var focusedId by remember { mutableStateOf(pager.anchor.focusedId) }
    var restoreFocusId by remember { mutableStateOf<String?>(null) }
    val state = pager.state

    fun savePosition() {
        if (restoring || pager.state.items.isEmpty()) return
        val items = pager.state.items
        val index = gridState.firstVisibleItemIndex.coerceAtMost(items.lastIndex)
        pager.saveAnchor(FavoritesAnchor(items.getOrNull(index)?.id, index,
            gridState.firstVisibleItemScrollOffset, focusedId,
            items.indexOfFirst { it.id == focusedId }.coerceAtLeast(0), items.size))
    }

    DisposableEffect(pager) {
        onDispose {
            savePosition()
            pager.cancel()
        }
    }
    LaunchedEffect(pager, revision, refresh) {
        savePosition()
        val target = pager.anchor
        restoring = true
        restoreFocusId = null
        pager.refresh(preview)
        if (pager.state.items.isNotEmpty()) {
            withFrameNanos { }
            gridState.scrollToItem(target.indexIn(pager.state.items), target.offset)
            withFrameNanos { }
            focusedId = target.focusIn(pager.state.items)
            restoreFocusId = focusedId
        }
        restoring = false
    }
    LaunchedEffect(pager, gridState) {
        snapshotFlow { listOf(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
            pager.state.items.size, focusedId, restoring) }.collect { savePosition() }
    }
    LaunchedEffect(pager, gridState) {
        snapshotFlow {
            val s = pager.state
            val ready = !restoring && !s.refreshing && !s.appending && !s.refreshRequired && s.error == null &&
                s.nextOffset != null && s.items.isNotEmpty() &&
                (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= s.items.lastIndex - 8
            ready to s.nextOffset
        }.collect { (ready, _) -> if (ready) pager.append() }
    }

    Column(Modifier.fillMaxSize().padding(top = 70.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (state.refreshRequired) "已显示 ${state.items.size} 部 · ${if (state.refreshing) "正在核对收藏" else "待刷新"}"
                    else "已加载 ${state.items.size} / 共 ${state.total?.toString() ?: "未知"} 部",
                    color = TextSub, fontSize = 12.sp)
                Text(state.error ?: when {
                    state.refreshing -> "正在刷新收藏…"
                    state.appending -> "正在加载更多…"
                    state.items.isEmpty() -> "这里还没有收藏"
                    else -> ""
                }, color = TextSub, fontSize = 11.sp)
            }
            TextButton(enabled = !state.refreshing && !state.appending, onClick = {
                if (state.error != null && !state.refreshRequired) scope.launch { pager.append() }
                else refresh++
            }) { Text(if (state.error != null) "重试" else "刷新") }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                PosterCard(item = item, imageUrl = client.imageUrl(item),
                    requestInitialFocus = !restoring && restoreFocusId == item.id,
                    focusRequester = if (index == 0) firstItemFocus else null,
                    onFocused = { focusedId = item.id; restoreFocusId = null; savePosition() },
                    onClick = { savePosition(); onOpenDetail(item.id) })
            }
        }
    }
}
