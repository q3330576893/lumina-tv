package com.lumina.tv.ui.screens

import android.os.Build
import android.util.TypedValue
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import com.lumina.tv.ui.theme.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource as ExoSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.LibraryCache
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.MediaChapter
import com.lumina.tv.data.MediaStream
import com.lumina.tv.data.MediaSource
import com.lumina.tv.ui.components.FocusSurface
import com.lumina.tv.ui.components.GlassPanel
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.TypeScale
import com.lumina.tv.player.SubtitleRenderersFactory
import com.lumina.tv.player.MpvPlayerController
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.DiagPayload
import com.lumina.tv.ui.components.DiagExportDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject


/** 可聚焦进度条：左右键累积预览目标，松开后由上层提交。 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    stepMs: Long,
    onFocusedChange: (Boolean) -> Unit = {},
    onSeekHold: (Int, Int) -> Unit = { _, _ -> },
    onSeekRelease: (Int) -> Unit = {},
    bufferedMs: Long = -1L,
    chapters: List<MediaChapter> = emptyList(),
    previewTargetMs: Long? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val playedRatio = if (duration > 0L) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedRatio = if (duration > 0L && bufferedMs >= 0L) {
        (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .focusRequester(focusRequester)
            .focusProperties { up = upFocus }
            .onFocusChanged {
                focused = it.isFocused
                onFocusedChange(it.isFocused)
            }
            .focusable(enabled = duration > 0L)
            .onKeyEvent { ev ->
                when {
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft -> { onSeekHold(-1, ev.nativeKeyEvent.repeatCount); true }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight -> { onSeekHold(1, ev.nativeKeyEvent.repeatCount); true }
                    ev.type == KeyEventType.KeyUp && ev.key == Key.DirectionLeft -> { onSeekRelease(-1); true }
                    ev.type == KeyEventType.KeyUp && ev.key == Key.DirectionRight -> { onSeekRelease(1); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackH = if (focused) 10.dp else 6.dp
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(trackH)
        ) {
            if (duration <= 0L) return@Canvas

            val trackWidth = size.width
            val centerY = size.height / 2f
            val trackCorner = CornerRadius(size.height / 2f)

            drawRoundRect(
                color = Color(0x33FFFFFF),
                topLeft = Offset(0f, 0f),
                size = Size(trackWidth, size.height),
                cornerRadius = trackCorner,
            )

            if (bufferedMs >= 0L && bufferedRatio > 0f) {
                drawRoundRect(
                    color = Color(0x59FFFFFF),
                    topLeft = Offset(0f, 0f),
                    size = Size(
                        (bufferedRatio * trackWidth).coerceAtLeast(size.height),
                        size.height,
                    ),
                    cornerRadius = trackCorner,
                )
            }

            val playedW = playedRatio * trackWidth
            if (playedW > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Accent, Accent2),
                        startX = 0f,
                        endX = playedW,
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(playedW.coerceAtLeast(size.height), size.height),
                    cornerRadius = trackCorner,
                )
            }

            if (duration >= 60_000L && chapters.isNotEmpty()) {
                val tickH = 8.dp.toPx()
                val minGap = 4.dp.toPx()
                var lastX = -minGap
                chapters
                    .filter { it.startMs > 0L && it.startMs < duration }
                    .sortedBy { it.startMs }
                    .forEach { chapter ->
                        val x = ((chapter.startMs.toFloat() / duration) * trackWidth)
                            .coerceIn(0f, trackWidth)
                        if (x - lastX >= minGap) {
                            drawLine(
                                color = Color(0x66FFFFFF),
                                start = Offset(x, centerY - tickH / 2f),
                                end = Offset(x, centerY + tickH / 2f),
                                strokeWidth = 1.dp.toPx(),
                            )
                            lastX = x
                        }
                    }
            }

            val radius = 7.dp.toPx()
            val targetRatio = previewTargetMs?.let { (it.toFloat() / duration).coerceIn(0f, 1f) }
            if (targetRatio != null) {
                val targetX = targetRatio * trackWidth
                val bandStartX = minOf(playedRatio * trackWidth, targetX)
                val bandEndX = maxOf(playedRatio * trackWidth, targetX)
                drawRoundRect(
                    color = Accent.copy(alpha = 0.28f),
                    topLeft = Offset(bandStartX, 0f),
                    size = Size(bandEndX - bandStartX, size.height),
                    cornerRadius = trackCorner,
                )
                val targetCenterX = targetX.coerceIn(radius, (trackWidth - radius).coerceAtLeast(radius))
                drawCircle(
                    color = Color.White,
                    radius = radius,
                    center = Offset(targetCenterX, centerY),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val lineHalfHeight = (size.height + 4.dp.toPx()) / 2f
                val currentX = (playedRatio * trackWidth)
                    .coerceIn(radius, (trackWidth - radius).coerceAtLeast(radius))
                drawLine(
                    color = Color(0xCCFFFFFF),
                    start = Offset(currentX, centerY - lineHalfHeight),
                    end = Offset(currentX, centerY + lineHalfHeight),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            val centerX = (playedRatio * trackWidth)
                .coerceIn(radius, (trackWidth - radius).coerceAtLeast(radius))
            val center = Offset(centerX, centerY)
            drawCircle(color = Color(0xCC000000), radius = radius, center = center)
            drawCircle(
                color = Color.White,
                radius = radius - 2.dp.toPx(),
                center = center,
            )

            if (focused) {
                drawRoundRect(
                    color = Color(0x99FFFFFF),
                    topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                    size = Size(
                        trackWidth - 1.5.dp.toPx(),
                        size.height - 1.5.dp.toPx(),
                    ),
                    cornerRadius = trackCorner,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        if (focused && duration > 0L) {
            Text(
                formatTime(position),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** 播放选项：倍速 / 字幕 / 音轨 / 画面比例 / 版本 / 选集 / 片头记忆。 */

@Composable
internal fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var m = Modifier.size(if (primary) 52.dp else 44.dp)
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    FocusSurface(
        modifier = m,
        corner = if (primary) 26.dp else 22.dp,
        scaleTo = TvTokenValues.FOCUS_SCALE,
        onClick = onClick,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().luminaStatus(if (primary) Accent else surfaceColor(SurfaceLevel.Floating, focused)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = if (primary) Color(0xFF06101F) else TextMain,
                modifier = Modifier.size(if (primary) 26.dp else 20.dp),
            )
        }
    }
}
