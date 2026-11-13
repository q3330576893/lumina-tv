package com.lumina.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubtitlePreviewCard(
    scale: Float,
    color: Color,
    outline: Boolean,
    position: Float,
    bg: Boolean,
    modifier: Modifier = Modifier,
) {
    val outlineWidthPx = with(LocalDensity.current) { 1.dp.toPx() }
    val shape = RoundedCornerShape(14.dp)
    val bottomPadding = when {
        position <= 0.04f -> 8.dp
        position >= 0.15f -> 30.dp
        else -> 18.dp
    }
    Box(
        modifier.fillMaxWidth().height(96.dp)
            .clip(shape)
            .background(Color(0xFF07090E))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding)
                .then(if (bg) Modifier.background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val previewText = "这是字幕样式的实时预览效果"
            if (outline) {
                Text(
                    text = previewText,
                    color = Color(0xFF07090E),
                    fontSize = 18.sp * scale,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(drawStyle = Stroke(width = outlineWidthPx)),
                    maxLines = 1,
                )
            }
            Text(
                text = previewText,
                color = color,
                fontSize = 18.sp * scale,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Text(
            text = "预览 · 随设置变化",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopEnd)
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(bottomStart = 8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
