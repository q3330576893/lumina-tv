package com.lumina.tv.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

val EffectTier.displayName: String get() = when (this) {
    EffectTier.FULL -> "完整"
    EffectTier.REDUCED -> "精简"
    EffectTier.OFF -> "关闭"
}

/** Static comparison only: no haze source/effect, animation, or blur lease is created. */
@Composable
fun EffectTierPreview(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("三档静态对比 · 当前：${ThemeRuntime.effectTier.displayName}", color = TextSub, style = TypeScale.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EffectTier.entries.forEach { tier ->
                Column(Modifier.weight(1f).luminaSurface(SurfaceLevel.Content).padding(10.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(42.dp)) {
                        drawRect(Color(0xFF303B50))
                        drawRect(Color(0xFF59697D), size = Size(size.width / 2, size.height))
                        val panel = surfacePolicy(tier, SurfaceLevel.Floating, floatingEffect = true,
                            blurGranted = tier == EffectTier.FULL)
                        val inset = 6.dp.toPx()
                        drawRect(Color(0xFF1B2438).copy(alpha = panel.opacity),
                            topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2))
                        // FULL's edge is represented by a static stroke; the preview never blurs.
                        if (panel.highlight) drawRect(TvTokens.Boundary, topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(1.dp.toPx()))
                    }
                    Text(tier.displayName, color = TextMain, style = TypeScale.body)
                    Text(when (tier) {
                        EffectTier.FULL -> "浮层效果 · 内容实体"
                        EffectTier.REDUCED -> "静态背景 · 轻透浮层"
                        EffectTier.OFF -> "实体面板 · 即时焦点"
                    }, color = TextSub, style = TypeScale.caption, maxLines = 2)
                }
            }
        }
    }
}
