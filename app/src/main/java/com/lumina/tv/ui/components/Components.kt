package com.lumina.tv.ui.components

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.zIndex

import android.graphics.Bitmap
import android.app.ActivityManager
import com.lumina.tv.ui.theme.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.compositeOver
import kotlinx.coroutines.yield
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.Glass
import com.lumina.tv.ui.theme.GlassLine
import com.lumina.tv.ui.theme.Ink
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime
import com.lumina.tv.ui.theme.Radius
import com.lumina.tv.ui.theme.ShapeScale
import com.lumina.tv.ui.theme.TypeScale
import dev.chrisbanes.haze.HazeState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/** Shared static Canvas; decorative idle motion is intentionally absent. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // Canvas is deliberately static. OFF shares the same solid fallback without light blobs.
    Box(modifier.fillMaxSize().luminaSurface(SurfaceLevel.Canvas), content = content)
}

/** Compatibility entry for Content. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    focused: Boolean = false,
    fillColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    LuminaSurface(SurfaceLevel.Content, modifier,
        shape = RoundedCornerShape(corner ?: TvTokens.GroupCorner), focused = focused,
        content = content)
}

@Composable
fun FrostPanel(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    corner: Dp = Radius.l,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    // Keep the local low-RAM/API guard as well as the central capability policy. Fail closed.
    val supported = manager?.isLowRamDevice == false && android.os.Build.VERSION.SDK_INT >= 31
    LuminaSurface(SurfaceLevel.Floating, modifier, RoundedCornerShape(corner),
        tone = if (strong) SurfaceTone.Raised else SurfaceTone.Neutral,
        hazeState = hazeState, allowBlur = supported, floatingEffect = true, content = content)
}

enum class FocusFamily { Poster, DenseGrid, Button, DetailPanel }

object FocusScale {
    const val POSTER_LARGE = TvTokenValues.FOCUS_SCALE
    const val POSTER_MEDIUM = TvTokenValues.FOCUS_SCALE
    const val DENSE_GRID = TvTokenValues.FOCUS_SCALE
    const val ULTRA_DENSE = TvTokenValues.FOCUS_SCALE
}

internal fun posterTitleAreaHeight(fontScale: Float): Dp = (20f * fontScale + 16f).dp

private const val FOCUS_ANIMATION_DURATION_MS = TvTokenValues.FOCUS_MS

/** 放大后需要的额外内边距，避免四角卡被 Lazy 容器裁切。 */
internal fun focusGutter(cardWidth: Dp, maxScale: Float): Dp =
    (((maxScale - 1f) / 2f) * cardWidth.value).dp + 8.dp

private data class FocusSpec(
    val scale: Float,
    val duration: Int,
)
private fun FocusFamily.spec(): FocusSpec = when (this) {
    FocusFamily.Poster -> FocusSpec(FocusScale.POSTER_LARGE, FOCUS_ANIMATION_DURATION_MS)
    FocusFamily.DenseGrid -> FocusSpec(FocusScale.DENSE_GRID, FOCUS_ANIMATION_DURATION_MS)
    FocusFamily.Button -> FocusSpec(TvTokenValues.FOCUS_SCALE, FOCUS_ANIMATION_DURATION_MS)
    FocusFamily.DetailPanel -> FocusSpec(TvTokenValues.FOCUS_SCALE, FOCUS_ANIMATION_DURATION_MS)
}

internal fun activateFocusSurface(enabled: Boolean = true, onClick: () -> Unit) {
    if (enabled) onClick()
}

internal suspend fun requestSurfaceInitialFocus(
    requested: Boolean,
    enabled: () -> Boolean,
    request: () -> Unit,
) {
    if (!requested || !enabled()) return
    yield() // Cancellation/disabled-state checkpoint; mounting is awaited by the caller.
    if (enabled()) request()
}

internal fun relocateDisabledSurfaceFocus(
    enabled: Boolean,
    wasFocused: Boolean,
    next: () -> Boolean,
    previous: () -> Boolean,
    clear: () -> Unit,
): Boolean {
    if (enabled || !wasFocused) return false
    if (!next() && !previous()) clear()
    return true
}

/** 可聚焦容器：缩放 + 高光描边的统一交互语言。 */
@Composable
fun FocusSurface(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.l,
    scaleTo: Float? = null,
    focusFamily: FocusFamily = FocusFamily.Button,
    requestFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    surfaceFill: ((focused: Boolean) -> Color)? = null,
    surfaceBorderColor: ((focused: Boolean) -> Color)? = null,
    surfaceBorderWidth: ((focused: Boolean) -> Dp)? = null,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val interactionFocused by interaction.collectIsFocusedAsState()
    val focused = enabled && interactionFocused
    var wasFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val currentEnabled by rememberUpdatedState(enabled)
    val focusSpec = focusFamily.spec()
    val scale = remember { Animatable(1f) }
    val motion = ThemeRuntime.motion
    LaunchedEffect(focused, motion) {
        scale.animateTo(
            if (focused) minOf(scaleTo ?: focusSpec.scale, motion.focusScale) else 1f,
            tween(motion.duration(focusSpec.duration), easing = EaseOutCubic),
        )
    }
    val requestFocusRequester = remember { FocusRequester() }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus, enabled) {
        if (!requestFocus || !enabled) return@LaunchedEffect
        snapshotFlow { mounted }.first { it }
        requestSurfaceInitialFocus(requestFocus, { currentEnabled }) {
            runCatching { requestFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(enabled) {
        if (relocateDisabledSurfaceFocus(enabled, wasFocused,
                next = { focusManager.moveFocus(FocusDirection.Next) },
                previous = { focusManager.moveFocus(FocusDirection.Previous) },
                clear = { focusManager.clearFocus(force = true) })) wasFocused = false
    }
    Box(
        modifier
            .onGloballyPositioned { mounted = true }
            .then(if (requestFocus) Modifier.focusRequester(requestFocusRequester) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = enabled }
            .onFocusChanged {
                // Keep ownership until the disable effect can choose a legal successor.
                if (enabled || it.isFocused) wasFocused = it.isFocused
                onFocusChanged?.invoke(enabled && it.isFocused)
                if (enabled && it.isFocused) onFocused?.invoke()
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shadowElevation = 0f
                shape = RoundedCornerShape(corner)
                clip = false
            }
            .clip(RoundedCornerShape(corner))
            .luminaSurface(SurfaceLevel.Content)
            .then(surfaceFill?.invoke(focused)?.let { Modifier.luminaStatus(it) } ?: Modifier)
            .border(
                if (focused) TvTokens.FocusBorder else (surfaceBorderWidth?.invoke(false) ?: 1.dp),
                if (focused) Color.White else (surfaceBorderColor?.invoke(false) ?: TvTokens.Boundary),
                RoundedCornerShape(corner),
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                onClick = { activateFocusSurface(enabled, onClick) })
    ) { content(focused) }
}

@Composable
internal fun ResumeRemovalPill(focusRequester: FocusRequester, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    FocusSurface(
        modifier = Modifier.size(width = 84.dp, height = 34.dp),
        corner = 17.dp,
        scaleTo = TvTokenValues.FOCUS_SCALE,
        focusRequester = focusRequester,
        onClick = onClick,
    ) { focused ->
        val tint = if (focused) Color(0xFFFFC5BD) else TextMain
        Row(
            Modifier.fillMaxSize()
                .luminaSurface(SurfaceLevel.Content, shape, tone = SurfaceTone.Danger),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Text("移除", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A poster keeps its focus animation local to its own interaction state. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun PosterCard(
    item: MediaItem,
    imageUrl: String?,
    width: Dp? = null,
    aspectRatio: Float = 1.5f,
    titleOverlay: Boolean = false,
    titleAlwaysVisible: Boolean = false,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onMenuKey: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val initialFocusRequester = remember { FocusRequester() }
    val focused by interaction.collectIsFocusedAsState()
    val cardShape = RoundedCornerShape(TvTokens.ContentCorner)
    val scale = remember { Animatable(1f) }

    val motion = ThemeRuntime.motion
    LaunchedEffect(focused, motion) {
        scale.animateTo(
            targetValue = if (focused) motion.focusScale else 1f,
            animationSpec = tween(durationMillis = motion.duration(TvTokenValues.FOCUS_MS), easing = EaseOutCubic),
        )
    }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(requestInitialFocus, mounted) {
        if (requestInitialFocus && mounted) {
            yield()
            runCatching { initialFocusRequester.requestFocus() }
        }
    }
    Column(
        Modifier
            .onGloballyPositioned { mounted = true }
            .zIndex(if (focused) 1f else 0f)
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .then(if (titleOverlay) Modifier.aspectRatio(1f / aspectRatio) else Modifier)
            .focusRequester(initialFocusRequester)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { if (upFocus != null) up = upFocus }
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shape = cardShape
                clip = false
            }
            .onPreviewKeyEvent { event ->
                if (onMenuKey != null && event.type == KeyEventType.KeyDown && event.key == Key.Menu &&
                    event.nativeKeyEvent.repeatCount == 0
                ) {
                    onMenuKey()
                    true
                } else false
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / aspectRatio)
                .clip(cardShape)
                .luminaSurface(SurfaceLevel.Content)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (AppRuntime.prefs?.showPosterWatchState ?: true) {
                if (item.played) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .size(20.dp)
                            .luminaSurface(SurfaceLevel.Content, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (item.playedPercentage >= 90.0) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .size(20.dp)
                            .luminaSurface(SurfaceLevel.Content, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = Color(0x99FFFFFF), fontSize = 11.sp)
                    }
                }
            }
            if (AppRuntime.prefs?.showPosterRating ?: true) {
                val rating = item.communityRating ?: item.rating
                if (rating != null && rating > 0) {
                    Text(
                        "%.1f".format(rating),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f)),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
            if (titleOverlay) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44.dp)
                        .luminaScrim(BackdropShade.PosterTitle)
                )
                Text(
                    item.name,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 9.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.positionTicks > 0 && !item.played) {
                val total = item.runtimeTicks ?: 0L
                val ratio = if (total > 0) (item.positionTicks.toFloat() / total).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66000000))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .height(4.dp)
                            .background(Accent)
                    )
                }
            }
            Box(
                Modifier.matchParentSize().drawBehind {
                    val edgeWidth = if (focused) TvTokens.FocusBorder.toPx() else 1.dp.toPx()
                    inset(edgeWidth / 2f) {
                        drawOutline(
                            cardShape.createOutline(size, layoutDirection, this),
                            color = if (focused) Color.White else GlassLine,
                            style = Stroke(width = edgeWidth),
                        )
                    }
                }
            )
        }
        if (!titleOverlay) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(posterTitleAreaHeight(LocalDensity.current.fontScale))
                    .graphicsLayer { alpha = if (titleAlwaysVisible || focused) 1f else 0f }
                    .padding(top = 10.dp)
            ) {
                Text(
                    item.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
fun TvHintBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(24.dp).padding(start = 34.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Column(modifier) {
        Text(
            text = text,
            color = TextMain,
            style = TypeScale.section,
        )
        if (trailing != null) {
            Text(text = trailing, color = TextSub, fontSize = 11.sp)
        }
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier, text: String = "正在加载…") {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text(text, color = TextSub, fontSize = 13.sp)
        }
    }
}

@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextSub, fontSize = 14.sp)
    }
}

@Composable
fun QrCode(content: String, size: Dp = 200.dp) {
    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap: ImageBitmap? = remember(content, pixels) {
        runCatching { generateQr(content, pixels).asImageBitmap() }.getOrNull()
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.m))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "扫码配对",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun generateQr(text: String, px: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 4,
        EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(px * px)
    for (y in 0 until px) {
        val off = y * px
        for (x in 0 until px) {
            pixels[off + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, px, 0, 0, px, px)
    return bmp
}

@Suppress("unused")
private val unusedArrangement = Arrangement.Start
