package com.lumina.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink: Color get() = ThemeRuntime.bgTop
val InkSoft: Color get() = ThemeRuntime.bgCard
val Glass: Color get() = ThemeRuntime.glassFill
val GlassStrong: Color get() = ThemeRuntime.glassStrong
val GlassLine: Color get() = ThemeRuntime.glassLine
val Mint = Color(0xFF57E3C6)
val TextMain = Color(0xFFEAF1FF)
val TextSub = Color(0xFF91A0BA)

/** 主题预设 */
data class ThemePreset(val key: String, val label: String, val accent: Color, val accent2: Color)

// 降低饱和度、拉近双色色相，避免“蓝紫渐变”的通用感；金色采用香槟调而非亮黄。
val THEME_PRESETS = listOf(
    ThemePreset("blue", "靛蓝", Color(0xFF7C9CF5), Color(0xFF5FD3C4)),
    ThemePreset("purple", "雾紫", Color(0xFFB6A2E8), Color(0xFFF0A6C0)),
    ThemePreset("teal", "青瓷", Color(0xFF6FD3C7), Color(0xFFA8C8F0)),
    ThemePreset("pink", "绛粉", Color(0xFFE89BB4), Color(0xFFC9A6E8)),
    ThemePreset("gold", "鎏金", Color(0xFFE3C08A), Color(0xFFF0DFC0)),
)

data class BasePreset(
    val key: String,
    val label: String,
    val bgTop: Color,
    val bgCard: Color,
    val glassFill: Color,
    val glassStrong: Color,
    val glassLine: Color,
)

val BASE_PRESETS = listOf(
    BasePreset(
        key = "oled",
        label = "纯黑",
        bgTop = Color(0xFF000000),
        bgCard = Color(0xFF0A0A0C),
        glassFill = Color(0x17FFFFFF),
        glassStrong = Color(0x24FFFFFF),
        glassLine = Color(0x26FFFFFF),
    ),
)

/** 玻璃质感档位 */
data class GlassPreset(val key: String, val label: String)

val GLASS_PRESETS = listOf(
    GlassPreset("frosted", "完整"),
    GlassPreset("clear", "精简"),
    GlassPreset("off", "关闭"),
)

/**
 * 运行时主题状态：主题色与玻璃质感可从设置页即时切换。
 * Accent / Accent2 保持 val 语义（getter 读取 state），全部既有调用点无需改动。
 */
object ThemeRuntime {
    var bgTop by mutableStateOf(Color.Black)
        private set
    var bgCard by mutableStateOf(Color(0xFF0A0A0C))
        private set
    var baseThemeKey by mutableStateOf("oled")
        private set
    var accent by mutableStateOf(Color(0xFF7C9CF5))
        private set
    var accent2 by mutableStateOf(Color(0xFF5FD3C4))
        private set
    var glassStyle by mutableStateOf("frosted")
        private set
    private var deviceApi by mutableStateOf(0)
    private var lowRam by mutableStateOf(true)
    var systemRemovesAnimations by mutableStateOf(true)
        private set

    val effectTier: EffectTier get() = resolveEffectTier(deviceApi, lowRam, glassStyle)
    val motion: MotionPolicy get() = motionPolicy(effectTier, systemRemovesAnimations)
    val deviceBlurSupported: Boolean get() = deviceApi >= 31 && !lowRam
    val glassEnabled: Boolean get() = effectTier != EffectTier.OFF
    val trueBlurEnabled: Boolean get() = effectTier == EffectTier.FULL

    fun updateDeviceCapabilities(api: Int, isLowRam: Boolean) {
        deviceApi = api
        lowRam = isLowRam
    }
    fun updateSystemMotion(removed: Boolean) { systemRemovesAnimations = removed }

    fun applyTheme(colorKey: String) {
        val p = THEME_PRESETS.firstOrNull { it.key == colorKey } ?: THEME_PRESETS[0]
        accent = p.accent
        accent2 = p.accent2
    }

    fun applyBase(key: String) {
        val resolution = resolveBaseThemeKey(key)
        val p = BASE_PRESETS.first { it.key == resolution.key }
        baseThemeKey = p.key
        bgTop = p.bgTop
        bgCard = p.bgCard
        if (resolution.mapped || resolution.fallback) {
            com.lumina.tv.diag.PageLog.add("themeBase effective=${p.key} mapped=${resolution.mapped} fallback=${resolution.fallback} storedPreference=preserved")
        }
    }

    fun applyGlass(styleKey: String) {
        glassStyle = styleKey.takeIf { it in setOf("frosted", "clear", "off") } ?: "frosted"
    }

    // Legacy callers share the solid Content fallback; transparency belongs only to LuminaSurface.
    val glassFill: Color get() = surfaceColor(SurfaceLevel.Content)
    val glassStrong: Color get() = surfaceColor(SurfaceLevel.Content, focused = true)
    val glassLine: Color get() = if (effectTier == EffectTier.OFF) Color.Transparent else TvTokens.Boundary
    val frostTint: Color get() = glassFill
    val frostStrongTint: Color get() = glassStrong
    val frostFallback: Color get() = glassFill
    val frostStrongFallback: Color get() = surfaceColor(SurfaceLevel.Floating)
    val glassHighlight: Color get() = Color.Transparent
    val glassBlurRadius: Dp get() = if (trueBlurEnabled) 16.dp else 0.dp
    val blurRadiusDp: Dp get() = glassBlurRadius
    fun glassFill(focused: Boolean): Color = if (focused) glassStrong else glassFill
    val glassFillSubtle: Color get() = glassFill
    val glassFillStrong: Color get() = glassStrong
    fun glassLine(focused: Boolean): Color = if (focused) Color.White else glassLine
    val glassEdge: Color get() = glassLine
    val glassEdgeNeutral: Color get() = glassLine
    val glassEdgeStrong: Color get() = Color.White
}

val Accent: Color get() = ThemeRuntime.accent
val Accent2: Color get() = ThemeRuntime.accent2

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    ObserveVisualEnvironment()
    val scheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF06101F),
        secondary = Accent2,
        background = Ink,
        onBackground = TextMain,
        surface = InkSoft,
        onSurface = TextMain,
        surfaceContainerHigh = surfaceColor(SurfaceLevel.Overlay),
        surfaceTint = Color.Transparent,
        surfaceVariant = Color(0xFF151C2E),
        onSurfaceVariant = TextSub,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = 0.5.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        ),
        content = content,
    )
}

object TypeScale {
    val display = TextStyle(fontSize = 34.sp, lineHeight = 42.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    val section = TextStyle(fontSize = 19.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 14.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Normal)
    val sp9 = 9.sp
    val sp10 = 10.sp
    val sp11 = 11.sp
    val sp12 = 12.sp
    val sp13 = 13.sp
    val sp14 = 14.sp
    val sp15 = 15.sp
    val sp16 = 16.sp
    val sp17 = 17.sp
    val sp18 = 18.sp
    val sp20 = 20.sp
    val sp22 = 22.sp
    val sp23 = 23.sp
    val sp24 = 24.sp
    val sp26 = 26.sp
    val sp28 = 28.sp
    val sp32 = 32.sp
    val sp40 = 40.sp
    val sp44 = 44.sp
}

object ShapeScale {
    val capsule = RoundedCornerShape(26.dp)
    val panel = RoundedCornerShape(TvTokens.GroupCorner)
    val card = RoundedCornerShape(TvTokens.ContentCorner)
    val chip = RoundedCornerShape(10.dp)
}

object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 26.dp
    val pill = 999.dp
}

object Elev {
    val s = 4.dp
    val m = 12.dp
    val l = 20.dp
}

object Motion {
    const val fast = 120
    const val base = 180
    const val slow = 300
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val section = 28.dp
}
