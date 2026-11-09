package com.lumina.tv.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.ui.theme.Accent
import com.lumina.tv.ui.theme.Accent2
import com.lumina.tv.ui.theme.Ink
import com.lumina.tv.ui.theme.TextSub
import kotlin.math.roundToInt

@Composable
fun SplashScreen(serverName: String, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(200),
        label = "splash-alpha",
    )
    val transition = rememberInfiniteTransition(label = "splash-shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -60f,
        targetValue = 160f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splash-shimmer-x",
    )

    Box(
        modifier = modifier
            .background(Color(0xFF07060C))
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center,
            ) {
                Text("L", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "LUMINA",
                color = Ink.copy(alpha = 0.9f),
                fontSize = 14.sp,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "正在连接 ${serverName.ifBlank { "Emby" }}",
                color = TextSub,
                fontSize = 11.sp,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .width(160.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color(0x22FFFFFF), RoundedCornerShape(1.dp)),
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(shimmerX.roundToInt(), 0) }
                    .width(60.dp)
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Accent, Accent2, Color.Transparent),
                        ),
                    ),
            )
        }
    }
}
