package com.lumina.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.lumina.tv.ui.theme.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 详情页 hero 的层间错位量，范围 -1..1，静止时为 0。 */
@Stable
internal class DetailHeroDrift internal constructor(private val scope: CoroutineScope) {
    internal val x = Animatable(0f)
    internal val y = Animatable(0f)

    fun nudge(dx: Int, dy: Int) {
        if (ThemeRuntime.motion.reduced) { reset(); return }
        if (dx != 0) scope.launch {
            x.snapTo(dx.toFloat())
            x.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
        }
        if (dy != 0) scope.launch {
            y.snapTo(dy.toFloat())
            y.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
        }
    }

    fun reset() {
        scope.launch {
            x.snapTo(0f)
            y.snapTo(0f)
        }
    }
}

@Composable
internal fun rememberDetailHeroDrift(): DetailHeroDrift {
    val scope = rememberCoroutineScope()
    return remember { DetailHeroDrift(scope) }
}

@Composable
internal fun DetailHeroParallax(
    backdropUrl: String?,
    drift: DetailHeroDrift,
    hazeState: HazeState,
    parallaxEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // Canvas: static poster crop; browsing never requests blur.
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().luminaSurface(SurfaceLevel.Canvas),
        )
        // 近景：清晰，位移
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (parallaxEnabled && !ThemeRuntime.motion.reduced && backdropUrl != null) {
                    translationX = drift.x.value * NEAR_DEPTH_PX
                    translationY = drift.y.value * NEAR_DEPTH_PX
                } else {
                    translationX = 0f
                    translationY = 0f
                }
            },
        )
    }
}

private const val NEAR_DEPTH_PX = 5f   // 1080p 下 5px ≈ 2.5dp
