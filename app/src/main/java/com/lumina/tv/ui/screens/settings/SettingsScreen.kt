package com.lumina.tv.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.components.FrostPanel
import com.lumina.tv.ui.motion.Motion
import com.lumina.tv.AppRuntime
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

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
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
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
        delay(120)
        if (focusedRowKeys[selectedCategory] == null) {
            runCatching { railRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF121A26), Color(0xFF0B1018)),
                    ),
                ),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val railWidth = if (maxWidth < 1100.dp) 192.dp else SettingsTokens.RailWidth
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = SettingsTokens.PagePaddingH,
                        vertical = SettingsTokens.PagePaddingV,
                    ),
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
                        .widthIn(max = SettingsTokens.ContentMaxWidth)
                        .weight(1f)
                        .focusGroup(),
                ) {
                    Text(
                        text = selectedCategory.label,
                        color = TextMain,
                        fontSize = SettingsTokens.TitleSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (selectedCategory == SettingsCategory.Appearance) {
                            "主题色与玻璃质感 · 即时生效"
                        } else {
                            "设置分类"
                        },
                        color = TextSub,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(24.dp))
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

    LaunchedEffect(selectedCategory) {
        val epochAtSwitch = focusEpoch
        delay(Motion.CATEGORY.toLong())
        if (focusEpoch != epochAtSwitch) return@LaunchedEffect
        val key = focusedRowKeys[selectedCategory]
        if (key == null) {
            runCatching { railRequester.requestFocus() }
            return@LaunchedEffect
        }
        val index = SettingsFocusMemory.rowIndices[key] ?: 0
        val scrollOffset = SettingsFocusMemory.rowScrollOffsets[key] ?: 0
        var restored = false
        for (attempt in 0 until 10) {
            if (focusEpoch != epochAtSwitch) return@LaunchedEffect
            val state = listStates.getValue(selectedCategory)
            runCatching { state.scrollToItem(index, scrollOffset) }
            delay(40)
            val currentRequester = focusRequesters[key]
            if (currentRequester != null && runCatching { currentRequester.requestFocus() }.isSuccess) {
                restored = true
                break
            }
        }
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
            Text("设置", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
                        .background(if (selected || focused) ThemeRuntime.frostStrongTint else Color.Transparent, RoundedCornerShape(12.dp))
                        .then(if (enabled) Modifier.clickable {
                            onCategorySelected(category)
                        } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected || focused) Box(Modifier.width(SettingsTokens.FocusBarW).height(SettingsTokens.FocusBarH).background(Accent, RoundedCornerShape(2.dp)))
                    Box(Modifier.padding(start = 14.dp).size(14.dp).background(if (selected) Accent else TextSub.copy(alpha = .75f), RoundedCornerShape(4.dp)))
                    Text(category.label, Modifier.padding(start = 10.dp), color = if (selected || focused) TextMain else TextSub, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}
