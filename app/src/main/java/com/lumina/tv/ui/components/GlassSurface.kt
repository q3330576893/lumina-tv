package com.lumina.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.lumina.tv.ui.theme.ThemeRuntime

enum class GlassTone { Normal, Accent }

fun Modifier.glassSurface(
    shape: Shape,
    tone: GlassTone = GlassTone.Normal,
    enabled: Boolean = ThemeRuntime.glassEnabled,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    val accent = ThemeRuntime.accent
    val fill = when (tone) {
        GlassTone.Normal -> Color.White.copy(alpha = if (enabled) 0.20f else 0.10f)
        GlassTone.Accent -> accent.copy(alpha = if (enabled) 0.16f else 0.10f)
    }
    val edgeStart = when (tone) {
        GlassTone.Normal -> Color.White.copy(alpha = 0.38f)
        GlassTone.Accent -> accent.copy(alpha = 0.45f)
    }
    val edgeEnd = when (tone) {
        GlassTone.Normal -> Color.White.copy(alpha = 0.10f)
        GlassTone.Accent -> accent.copy(alpha = 0.14f)
    }

    clipPath(path) {
        drawRect(fill)
        val edgeBrush = Brush.linearGradient(
            colors = listOf(edgeStart, edgeEnd),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        when (outline) {
            is Outline.Rounded -> drawRoundRect(
                brush = edgeBrush,
                topLeft = Offset(outline.roundRect.left, outline.roundRect.top),
                size = Size(outline.roundRect.width, outline.roundRect.height),
                cornerRadius = outline.roundRect.topLeftCornerRadius,
                style = Stroke(width = 1.dp.toPx()),
            )
            else -> drawPath(path, edgeBrush, style = Stroke(width = 1.dp.toPx()))
        }

        if (enabled) {
            val highlightColor = when (tone) {
                GlassTone.Normal -> Color.White.copy(alpha = 0.22f)
                GlassTone.Accent -> accent.copy(alpha = 0.18f)
            }
            val radius = size.height * 1.05f
            val center = Offset(size.width / 2f, size.height / 2f) +
                Offset(0.6f, 0.8f) * (0.30f * size.height)
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(highlightColor, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
            )
            val topHeight = minOf(14.dp.toPx(), size.height * 0.45f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                    startY = 0f,
                    endY = topHeight,
                ),
                size = Size(size.width, topHeight),
            )
            val bottomHeight = minOf(12.dp.toPx(), size.height * 0.35f)
            val bottomColor = when (tone) {
                GlassTone.Normal -> Color.White.copy(alpha = 0.09f)
                GlassTone.Accent -> accent.copy(alpha = 0.07f)
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(bottomColor, Color.Transparent),
                    startY = size.height - bottomHeight,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - bottomHeight),
                size = Size(size.width, bottomHeight),
            )
        }
    }
}
