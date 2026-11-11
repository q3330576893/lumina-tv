package com.lumina.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

/** 极光背景：深空底 + 两团缓慢漂移的光晕，营造 iOS 玻璃质感的空间感。 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000), RepeatMode.Reverse),
        label = "drift",
    )
    Box(modifier.fillMaxSize().background(Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0B1020), Ink, Color(0xFF0A0D18)),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                )
            )
            val x1 = w * (0.12f + 0.10f * drift)
            val y1 = h * (0.10f + 0.06f * drift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Accent.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(x1, y1),
                    radius = w * 0.55f,
                ),
                radius = w * 0.55f,
                center = Offset(x1, y1),
            )
            val x2 = w * (0.86f - 0.08f * drift)
            val y2 = h * (0.16f + 0.10f * drift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Accent2.copy(alpha = 0.26f), Color.Transparent),
                    center = Offset(x2, y2),
                    radius = w * 0.45f,
                ),
                radius = w * 0.45f,
                center = Offset(x2, y2),
            )
            val x3 = w * (0.55f + 0.12f * drift)
            val y3 = h * (0.95f - 0.05f * drift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF35E0D0).copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(x3, y3),
                    radius = w * 0.38f,
                ),
                radius = w * 0.38f,
                center = Offset(x3, y3),
            )
        }
        content()
    }
}

/** 通用玻璃面板：半透明填色 + 1px 高光描边 + 大圆角，质感跟随设置页档位。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    focused: Boolean = false,
    fillColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(corner?.let { RoundedCornerShape(it) } ?: ShapeScale.panel)
            .background(fillColor ?: if (focused) ThemeRuntime.glassFill(true).copy(alpha = 1.4f) else ThemeRuntime.glassFill(false))
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = ThemeRuntime.glassLine(focused),
                shape = corner?.let { RoundedCornerShape(it) } ?: ShapeScale.panel,
            )
    ) {
        content()
        Box(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.06f), Color.Transparent)
                    )
                )
        )
    }
}

/** 可聚焦容器：缩放 + 高光描边的统一交互语言。 */
@Composable
fun FocusSurface(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.l,
    scaleTo: Float = 1.06f,
    requestFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) scaleTo else 1f, tween(130), label = "scale")
    val requestFocusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            kotlinx.coroutines.delay(100)
            runCatching { requestFocusRequester.requestFocus() }
        }
    }
    Box(
        modifier
            .then(if (requestFocus) Modifier.focusRequester(requestFocusRequester) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 24f else 0f
                shape = RoundedCornerShape(corner)
                clip = false
            }
            .clip(RoundedCornerShape(corner))
            .background(ThemeRuntime.glassFill(focused))
            .border(
                if (focused) 1.25.dp else 1.dp,
                if (focused) Accent else ThemeRuntime.glassLine(false),
                RoundedCornerShape(corner),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** 海报卡片：竖版封面 + 进度条 + 标题。 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun PosterCard(
    item: MediaItem,
    imageUrl: String?,
    width: Dp = 200.dp,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val initialFocusRequester = remember { FocusRequester() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(180), label = "posterScale")
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            kotlinx.coroutines.delay(120)
            runCatching { initialFocusRequester.requestFocus() }
        }
    }
    Column(
        Modifier
            .width(width)
            .then(if (requestInitialFocus) Modifier.focusRequester(initialFocusRequester) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { if (upFocus != null) up = upFocus }
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(width * 1.5f)
                .clip(ShapeScale.card)
                .background(Color(0xFF141B2B))
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Accent else GlassLine,
                    ShapeScale.card,
                )
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
                            .background(Color(0xCC000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (item.playedPercentage >= 90.0) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .size(20.dp)
                            .background(Color(0xCC000000), CircleShape),
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
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.name,
            color = if (focused) TextMain else TextMain.copy(alpha = 0.92f),
            style = TypeScale.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            color = TextSub,
            style = TypeScale.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val bitmap: ImageBitmap? = remember(content) { runCatching { generateQr(content).asImageBitmap() }.getOrNull() }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.m))
            .background(Color.White)
            .padding(12.dp),
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

private fun generateQr(text: String, px: Int = 640): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(px * px)
    for (y in 0 until px) {
        val off = y * px
        for (x in 0 until px) {
            pixels[off + x] = if (matrix[x, y]) 0xFF0B1020.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, px, 0, 0, px, px)
    return bmp
}

@Suppress("unused")
private val unusedArrangement = Arrangement.Start
