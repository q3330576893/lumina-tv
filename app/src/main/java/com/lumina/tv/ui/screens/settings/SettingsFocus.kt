package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumina.tv.ui.theme.*

internal data class SettingsFocusContext(
    val category: String = "preview",
    val accountScope: String = "none",
    val expansionState: String = "collapsed",
    val isActive: () -> Boolean = { true },
    val record: (SettingsFocusKey) -> Unit = {},
    val restore: (SettingsFocusKey) -> Unit = {},
) {
    fun key(row: String, identity: String = row.substringAfter(':')) =
        SettingsFocusKey(category, row, identity, expansionState, accountScope)
}

internal val LocalSettingsFocusContext = staticCompositionLocalOf { SettingsFocusContext() }
internal val LocalSettingsFocusKeys = staticCompositionLocalOf<MutableMap<String, FocusRequester>?> { null }
internal val LocalSettingsListStates = staticCompositionLocalOf<Map<SettingsCategory, LazyListState>?> { null }

@Composable
internal fun settingsListState(category: SettingsCategory): LazyListState =
    requireNotNull(LocalSettingsListStates.current?.get(category))

internal fun Modifier.settingsFocusTarget(row: String, requester: FocusRequester,
    onFocused: (String) -> Unit = {}): Modifier = composed {
    val context = LocalSettingsFocusContext.current
    val key = context.key(row)
    val registry = LocalSettingsFocusKeys.current
    DisposableEffect(key, requester, registry) {
        registry?.set(key.encoded, requester)
        onDispose { if (registry?.get(key.encoded) === requester) registry.remove(key.encoded) }
    }
    focusRequester(requester).focusProperties { canFocus = context.isActive() }.onFocusChanged {
        if (it.isFocused && context.isActive()) { context.record(key); onFocused(row) }
    }
}

internal fun Modifier.settingsEditorFocus(row: String, requester: FocusRequester): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    settingsFocusTarget(row, requester).onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = if (focused) TvTokenValues.FOCUS_SCALE else 1f; scaleY = scaleX }
        .tvFocusBoundary()
}

/** Dialog nodes have their own expansion identity; closing restores the invoking row. */
@Composable
internal fun SettingsDialog(rowKey: String, title: String, onDismissRequest: () -> Unit,
    restoreFocusOnClose: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val parent = LocalSettingsFocusContext.current
    val returnKey = parent.key(rowKey)
    val close = {
        onDismissRequest()
        if (restoreFocusOnClose && parent.isActive()) parent.restore(returnKey)
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSettingsFocusContext provides parent.copy(expansionState = "dialog:$rowKey")) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                LuminaSurface(SurfaceLevel.Overlay,
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxHeight),
                    shape = RoundedCornerShape(TvTokens.ModalCorner)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, color = TextMain, style = TypeScale.title)
                        content(close)
                    }
                }
            }
        }
    }
}
