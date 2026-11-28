package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.*
import com.lumina.tv.ui.theme.TypeScale

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.components.FrostPanel
import com.lumina.tv.ui.theme.TvMotion as Motion
import com.lumina.tv.AppRuntime
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private object SettingsFocusMemory {
    var category: SettingsCategory = SettingsCategory.Appearance
    val rowKeys = mutableMapOf<SettingsCategory, String>()
    val rowIndices = mutableMapOf<String, Int>()
    val rowScrollOffsets = mutableMapOf<String, Int>()
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val hazeState = remember { HazeState() }
    val railRequester = remember { FocusRequester() }
    val contentFirstRequester = remember { FocusRequester() }
    var selectedCategory by remember { mutableStateOf(SettingsFocusMemory.category) }
    val focusedRowKeys = remember { mutableStateMapOf<SettingsCategory, String>().apply { putAll(SettingsFocusMemory.rowKeys) } }
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val listStates = remember { SettingsCategory.entries.associateWith { LazyListState() } }
    var focusEpoch by remember { mutableStateOf(0) }
    var previousCategory by remember { mutableStateOf(selectedCategory) }

    fun rememberRowFocus(category: SettingsCategory, key: String) {
        focusedRowKeys[category] = key
        SettingsFocusMemory.rowKeys[category] = key
        val state = listStates.getValue(category)
        SettingsFocusMemory.rowIndices[key] = state.firstVisibleItemIndex
        SettingsFocusMemory.rowScrollOffsets[key] = state.firstVisibleItemScrollOffset
        focusEpoch++
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (focusedRowKeys[selectedCategory] == null) {
            runCatching { railRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .luminaSurface(SurfaceLevel.Canvas),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val railWidth = if (maxWidth < 1100.dp) 192.dp else SettingsTokens.RailWidth
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = TvTokens.safeInset(maxWidth),
                        vertical = SettingsTokens.PagePaddingV,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    Modifier
                        .widthIn(max = railWidth + SettingsTokens.RailContentGap + SettingsTokens.ContentMaxWidth)
                        .fillMaxSize(),
                ) {
                    SettingsCategoryRail(
                        selectedCategory = selectedCategory,
                        railWidth = railWidth,
                        hazeState = hazeState,
                        selectedRequester = railRequester,
                        contentRequester = contentFirstRequester,
                        onCategorySelected = {
                            if (selectedCategory != it) {
                                previousCategory = selectedCategory
                                selectedCategory = it
                                SettingsFocusMemory.category = it
                            }
                        },
                        onRailFocusChanged = { if (it) focusEpoch++ },
                    )
                    Spacer(Modifier.width(SettingsTokens.RailContentGap))
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .focusGroup()
                            .padding(top = 12.dp),
                    ) {
                        val direction = if (selectedCategory.ordinal >= previousCategory.ordinal) 1f else -1f
                        CompositionLocalProvider(
                            LocalSettingsFocusKeys provides focusRequesters,
                            LocalSettingsListStates provides listStates,
                        ) {
                        AnimatedContent(
                            targetState = selectedCategory,
                            transitionSpec = {
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.CATEGORY, easing = Motion.enterEasing),
                                    initialOffsetX = { (it * Motion.SHIFT_CATEGORY * direction).toInt() },
                                ) + fadeIn(tween(Motion.CATEGORY, easing = Motion.enterEasing))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(Motion.CATEGORY, easing = Motion.exitEasing),
                                        targetOffsetX = { (-it * Motion.SHIFT_CATEGORY * direction).toInt() },
                                    ) + fadeOut(tween(Motion.CATEGORY, easing = Motion.exitEasing))) using
                                    SizeTransform(clip = false)
                            },
                            contentKey = { it.key },
                            label = "settingsCategory",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { category ->
                            when (category) {
                                SettingsCategory.Account -> SettingsAccount(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    onLogout = onLogout,
                                    onServerSwitched = onBack,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.Appearance -> SettingsAppearance(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.Playback -> SettingsPlayback(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.Subtitles -> SettingsSubtitles(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.Compatibility -> SettingsBackend(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.About -> SettingsAbout(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SettingsCategory.Home -> SettingsHome(
                                    firstFocusRequester = contentFirstRequester,
                                    railRequester = railRequester,
                                    onFocused = { key -> rememberRowFocus(category, key) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedCategory) {
        val epochAtSwitch = focusEpoch
        withFrameNanos { }
        if (focusEpoch != epochAtSwitch) return@LaunchedEffect
        val key = focusedRowKeys[selectedCategory]
        if (key == null) {
            runCatching { railRequester.requestFocus() }
            return@LaunchedEffect
        }
        val index = SettingsFocusMemory.rowIndices[key] ?: 0
        val scrollOffset = SettingsFocusMemory.rowScrollOffsets[key] ?: 0
        if (focusEpoch != epochAtSwitch) return@LaunchedEffect
        val state = listStates.getValue(selectedCategory)
        val scrolled = runCatching { state.scrollToItem(index, scrollOffset) }.isSuccess
        if (!scrolled) {
            if (focusEpoch == epochAtSwitch) runCatching { railRequester.requestFocus() }
            return@LaunchedEffect
        }
        withFrameNanos { }
        snapshotFlow {
            focusEpoch != epochAtSwitch || state.layoutInfo.visibleItemsInfo.any { it.index == index }
        }.first { it }
        withFrameNanos { }
        if (focusEpoch != epochAtSwitch) return@LaunchedEffect
        snapshotFlow { focusEpoch != epochAtSwitch || focusRequesters.containsKey(key) }.first { it }
        if (focusEpoch != epochAtSwitch) return@LaunchedEffect
        val currentRequester = focusRequesters[key]
        val restored = currentRequester != null && runCatching { currentRequester.requestFocus() }.isSuccess
        if (!restored && focusEpoch == epochAtSwitch) runCatching { railRequester.requestFocus() }
    }
}

@Composable
private fun SettingsCategoryRail(
    selectedCategory: SettingsCategory,
    railWidth: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    selectedRequester: FocusRequester,
    contentRequester: FocusRequester,
    onCategorySelected: (SettingsCategory) -> Unit,
    onRailFocusChanged: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(SettingsTokens.GroupCorner)
    FrostPanel(Modifier.width(railWidth).fillMaxHeight(), hazeState, SettingsTokens.GroupCorner) {
        Column(Modifier.fillMaxSize().glassBorder(shape).focusGroup().padding(horizontal = 10.dp, vertical = 16.dp)) {
            Text("设置", color = TextMain, fontSize = TypeScale.sp18, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.height(10.dp))
            SettingsCategory.entries.forEach { category ->
                val enabled = category in setOf(SettingsCategory.Account, SettingsCategory.Appearance, SettingsCategory.Home, SettingsCategory.Playback, SettingsCategory.Subtitles, SettingsCategory.Compatibility, SettingsCategory.About)
                val selected = category == selectedCategory
                var focused by remember(category.key) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .then(if (category == selectedCategory) Modifier.focusRequester(selectedRequester) else Modifier)
                        .focusProperties { if (category == selectedCategory) right = contentRequester }
                        .onFocusChanged {
                            focused = it.isFocused
                            onRailFocusChanged(it.isFocused)
                        }
                        .luminaSurface(SurfaceLevel.Content, RoundedCornerShape(12.dp), focused = focused, selected = selected)
                        .then(if (enabled) Modifier.clickable {
                            onCategorySelected(category)
                        } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected || focused) Box(Modifier.width(SettingsTokens.FocusBarW).height(SettingsTokens.FocusBarH).luminaStatus(Accent, shape = RoundedCornerShape(2.dp)))
                    Box(Modifier.padding(start = 14.dp).size(14.dp).luminaStatus(if (selected) Accent else TextSub, RoundedCornerShape(4.dp)))
                    Text(category.label, Modifier.padding(start = 10.dp), color = if (selected || focused) TextMain else TextSub, fontSize = TypeScale.sp15, maxLines = 1)
                }
            }
        }
    }
}
