package com.lumina.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.screens.readableMediaTitle
import com.lumina.tv.ui.theme.*

/** Scale is drawn after measurement: focus never changes a neighbour's position. */
@Composable
internal fun BrowseFocusCard(
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (focused) TvTokenValues.FOCUS_SCALE else 1f,
        tween(ThemeRuntime.motion.duration(TvTokenValues.FOCUS_MS)), label = "browseFocus")
    Box(modifier.onFocusChanged { if (it.isFocused) onFocused() }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        content(focused)
    }
}

/** The outline belongs to the artwork; title/year remain below it at every font scale. */
@Composable
internal fun LibraryPosterCard(item: MediaItem, imageUrl: String?, modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false, upFocus: FocusRequester? = null,
    onFocused: () -> Unit = {}, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(requestInitialFocus, mounted) {
        if (requestInitialFocus && mounted) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    }
    BrowseFocusCard(modifier.onGloballyPositioned { mounted = true }.focusRequester(focus)
        .focusProperties { if (upFocus != null) up = upFocus }, onFocused = onFocused, onClick = onClick) { focused ->
        Column {
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)
                .luminaSurface(SurfaceLevel.Content)
                .then(if (focused) Modifier.border(TvTokens.FocusBorder, Color.White, shape) else Modifier)) {
                AsyncImage(imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(readableMediaTitle(item), color = if (focused) Color.White else Color(0xFF9AA3B2),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            item.year?.takeIf { it > 0 }?.let { year ->
                Text(year.toString(), color = Color(0xFF9AA3B2), fontSize = 12.sp, fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
