package com.lumina.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.identityScope
import com.lumina.tv.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class SettingsFocusPoint(val key: SettingsFocusKey, val index: Int, val offset: Int)
private object SettingsFocusMemory {
    val categories = mutableMapOf<String, SettingsCategory>()
    val rows = mutableMapOf<Pair<String, SettingsCategory>, SettingsFocusPoint>()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val session = AppRuntime.session
    val accountScope = identityScope(session?.baseUrl.orEmpty(), session?.userId.orEmpty())?.toString() ?: "signed-out"
    var selectedCategory by remember(accountScope) { mutableStateOf(SettingsFocusMemory.categories[accountScope] ?: SettingsCategory.Appearance) }
    val selection = remember(accountScope) { SettingsFocusSession(selectedCategory.key, accountScope) }
    val railRequesters = remember(accountScope) { SettingsCategory.entries.associateWith { FocusRequester() } }
    val firstRequesters = remember(accountScope) { SettingsCategory.entries.associateWith { FocusRequester() } }
    val listStates = remember(accountScope) { SettingsCategory.entries.associateWith { LazyListState() } }
    val registry = remember(accountScope) { mutableStateMapOf<String, FocusRequester>() }
    val saveable = rememberSaveableStateHolder()
    var restoreRevision by remember(accountScope) { mutableIntStateOf(0) }
    var restorePoint by remember(accountScope) { mutableStateOf(SettingsFocusMemory.rows[accountScope to selectedCategory]) }
    var restoreTicket by remember(accountScope) { mutableStateOf(selection.ticket()) }
    var railFocused by remember { mutableStateOf(false) }
    var enterContent by remember { mutableStateOf(false) }

    fun requestRestore(point: SettingsFocusPoint?, enter: Boolean = false) {
        restorePoint = point
        enterContent = enter
        restoreTicket = selection.ticket()
        restoreRevision++
    }
    fun select(category: SettingsCategory) {
        if (category != selectedCategory) {
            selection.select(category.key)
            selectedCategory = category
            SettingsFocusMemory.categories[accountScope] = category
        }
        requestRestore(SettingsFocusMemory.rows[accountScope to category])
    }

    BackHandler {
        if (railFocused) onBack() else railRequesters.getValue(selectedCategory).requestFocus()
    }

    BoxWithConstraints(Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Canvas)) {
        val railWidth = if (maxWidth < 1100.dp) 192.dp else SettingsTokens.RailWidth
        Row(Modifier.fillMaxSize().padding(horizontal = TvTokens.safeInset(maxWidth), vertical = 28.dp)) {
            LuminaSurface(SurfaceLevel.Floating, Modifier.width(railWidth).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().focusGroup().padding(10.dp)) {
                    Text("设置", color = TextMain, style = TypeScale.body, modifier = Modifier.padding(12.dp))
                    SettingsCategory.entries.forEach { category ->
                        var focused by remember(category) { mutableStateOf(false) }
                        val selected = category == selectedCategory
                        Text(category.label, color = TextMain, fontSize = TypeScale.sp15,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .focusRequester(railRequesters.getValue(category))
                                .focusProperties { if (selected) right = firstRequesters.getValue(category) }
                                .onPreviewKeyEvent {
                                    if (selected && it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                                        requestRestore(SettingsFocusMemory.rows[accountScope to category], enter = true)
                                        true
                                    } else false
                                }
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) { railFocused = true; selection.userMoved() }
                                }
                                .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
                                .luminaSurface(SurfaceLevel.Floating, RoundedCornerShape(TvTokens.ContentCorner), focused, selected)
                                .semantics { this.selected = selected; stateDescription = if (selected) "当前分类" else "切换分类" }
                                .clickable { select(category) }.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            val category = selectedCategory
            val context = SettingsFocusContext(category.key, accountScope,
                isActive = { selectedCategory == category },
                record = { key ->
                    railFocused = false
                    selection.userMoved()
                    if (!key.expansionState.startsWith("dialog:")) {
                        val list = listStates.getValue(category)
                        SettingsFocusMemory.rows[accountScope to category] = SettingsFocusPoint(key,
                            list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                    }
                },
                restore = { key ->
                    if (selectedCategory == category) {
                        val list = listStates.getValue(category)
                        requestRestore(SettingsFocusPoint(key, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset))
                    }
                })
            // Removing the outgoing category also cancels its account-deletion and dialog coroutines.
            key(accountScope, category) {
                CompositionLocalProvider(LocalSettingsFocusKeys provides registry,
                    LocalSettingsListStates provides listStates, LocalSettingsFocusContext provides context) {
                    saveable.SaveableStateProvider("$accountScope:${category.key}") {
                        val first = firstRequesters.getValue(category)
                        val rail = railRequesters.getValue(category)
                        val content = Modifier.weight(1f).fillMaxHeight().focusGroup()
                        when (category) {
                            SettingsCategory.Account -> SettingsAccount(first, rail, {}, onLogout, onBack, content)
                            SettingsCategory.Appearance -> SettingsAppearance(first, rail, {}, content)
                            SettingsCategory.Home -> SettingsHome(first, rail, {}, content)
                            SettingsCategory.Playback -> SettingsPlayback(first, rail, {}, content)
                            SettingsCategory.Subtitles -> SettingsSubtitles(first, rail, {}, content)
                            SettingsCategory.Compatibility -> SettingsBackend(first, rail, {}, content)
                            SettingsCategory.About -> SettingsAbout(first, rail, {}, content)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(accountScope, selectedCategory, restoreRevision) {
        val ticket = restoreTicket
        val category = selectedCategory
        val point = restorePoint
        withFrameNanos { }
        if (!selection.isCurrent(ticket)) return@LaunchedEffect
        if (point == null) {
            if (enterContent) {
                listStates.getValue(category).scrollToItem(0)
                withFrameNanos { }
                if (!selection.isCurrent(ticket)) return@LaunchedEffect
                runCatching { firstRequesters.getValue(category).requestFocus() }
            } else railRequesters.getValue(category).requestFocus()
            return@LaunchedEffect
        }
        val list = listStates.getValue(category)
        list.scrollToItem(point.index, point.offset)
        if (!selection.isCurrent(ticket)) return@LaunchedEffect
        withFrameNanos { }
        if (!selection.isCurrent(ticket)) return@LaunchedEffect
        val requester = withTimeoutOrNull(1200) {
            snapshotFlow { registry[point.key.encoded] }.first { it != null }
        }
        if (!selection.isCurrent(ticket)) return@LaunchedEffect
        if (requester != null) runCatching { requester.requestFocus() }
        else runCatching { firstRequesters.getValue(category).requestFocus() }
    }
}
