package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.LibraryView
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
fun SettingsHome(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    val views = LibraryCache.home?.views.orEmpty()
    var hiddenIds by remember { mutableStateOf(prefs?.hiddenViewIds.orEmpty()) }
    val itemCounts = remember { mutableStateMapOf<String, Int>() }
    var countLoadComplete by remember { mutableStateOf(false) }
    val requesters = remember(views.map(LibraryView::id)) {
        buildMap {
            views.forEachIndexed { index, view ->
                put(view.id, if (index == 0) firstFocusRequester else FocusRequester())
            }
            put("home:show-all", if (views.isEmpty()) firstFocusRequester else FocusRequester())
            put("home:hide-all", FocusRequester())
        }
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LaunchedEffect(views) {
        countLoadComplete = false
        if (views.isEmpty()) return@LaunchedEffect
        val counts = coroutineScope {
            views.map { view ->
                async { view.id to runCatching { AppRuntime.client?.getLibraryItemCount(view.id) }.getOrNull() }
            }.awaitAll()
        }
        counts.forEach { (id, count) -> if (count != null) itemCounts[id] = count }
        countLoadComplete = true
    }

    LazyColumn(modifier = modifier) {
        item(key = "home:group-label") {
            Text("首页媒体库", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        if (views.isEmpty()) {
            item(key = "home:not-synced") {
                Column(
                    Modifier.fillMaxWidth()
                        .background(ThemeRuntime.frostTint, groupShape)
                        .glassBorder(groupShape)
                        .padding(SettingsTokens.RowPaddingH),
                ) {
                    Text("打开一次首页后会自动同步服务器分类", color = TextSub, fontSize = 14.sp)
                }
            }
        } else {
            items(views, key = LibraryView::id) { view ->
                val requester = requireNotNull(requesters[view.id])
                ToggleRow(
                    focusKey = view.id,
                    title = view.name,
                    description = itemCounts[view.id]?.let { "$it 个条目" }
                        ?: if (countLoadComplete) "条目数未知" else "正在获取条目数",
                    checked = view.id !in hiddenIds,
                    onCheckedChange = {
                        prefs?.toggleViewHidden(view.id)
                        hiddenIds = prefs?.hiddenViewIds.orEmpty()
                    },
                    focusRequester = requester,
                    leftRequester = railRequester,
                    upRequester = if (view.id == views.firstOrNull()?.id) requester else null,
                    onFocused = onFocused,
                    modifier = Modifier.background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape),
                )
            }
        }
        item(key = "home:actions") {
            Column(Modifier.fillMaxWidth().padding(top = SettingsTokens.GroupGap)) {
                ActionRow(
                    focusKey = "home:show-all",
                    title = "全部显示",
                    description = "显示所有首页媒体库",
                    actionLabel = "全部显示",
                    onClick = { prefs?.hiddenViewIds = emptySet(); hiddenIds = emptySet() },
                    focusRequester = requireNotNull(requesters["home:show-all"]),
                    leftRequester = railRequester,
                    upRequester = if (views.isEmpty()) requireNotNull(requesters["home:show-all"]) else null,
                    onFocused = onFocused,
                )
                ActionRow(
                    focusKey = "home:hide-all",
                    title = "全部隐藏",
                    description = "隐藏所有首页媒体库",
                    actionLabel = "全部隐藏",
                    onClick = {
                        val allIds = views.map(LibraryView::id).toSet()
                        prefs?.hiddenViewIds = allIds
                        hiddenIds = allIds
                    },
                    focusRequester = requireNotNull(requesters["home:hide-all"]),
                    leftRequester = railRequester,
                    upRequester = requireNotNull(requesters["home:show-all"]),
                    downRequester = requireNotNull(requesters["home:hide-all"]),
                    showDivider = false,
                    onFocused = onFocused,
                )
            }
        }
        item(key = "home:bottom-space") { Spacer(Modifier.height(SettingsTokens.GroupGap)) }
    }
}
