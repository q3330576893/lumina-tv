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

val Ink = Color(0xFF070A12)
val InkSoft = Color(0xFF0E1424)
val Glass = Color(0x14FFFFFF)
val GlassStrong = Color(0x1FFFFFFF)
val GlassLine = Color(0x22FFFFFF)
val Mint = Color(0xFF57E3C6)
val TextMain = Color(0xFFEAF1FF)
val TextSub = Color(0xFF91A0BA)

/** 主题预设 */
data class ThemePreset(val key: String, val label: String, val accent: Color, val accent2: Color)

val THEME_PRESETS = listOf(
    ThemePreset("blue", "琉璃蓝", Color(0xFF6EA8FF), Color(0xFF9B8CFF)),
    ThemePreset("purple", "星紫", Color(0xFFB48CFF), Color(0xFF8CA8FF)),
    ThemePreset("teal", "青碧", Color(0xFF4FD8C4), Color(0xFF6EA8FF)),
    ThemePreset("pink", "樱粉", Color(0xFFFF8FB8), Color(0xFFC48CFF)),
    ThemePreset("gold", "暖金", Color(0xFFF2C46D), Color(0xFFFF9E7A)),
)

/** 玻璃质感档位 */
data class GlassPreset(val key: String, val label: String)

val GLASS_PRESETS = listOf(
    GlassPreset("frosted", "磨砂"),
    GlassPreset("clear", "清透"),
    GlassPreset("off", "关闭"),
)

/**
 * 运行时主题状态：主题色与玻璃质感可从设置页即时切换。
 * Accent / Accent2 保持 val 语义（getter 读取 state），全部既有调用点无需改动。
 */
object ThemeRuntime {
    var accent by mutableStateOf(Color(0xFF6EA8FF))
        private set
    var accent2 by mutableStateOf(Color(0xFF9B8CFF))
        private set
    var glassStyle by mutableStateOf("frosted")
        private set
    var glassEnabled by mutableStateOf(true)
        private set
    var deviceBlurSupported by mutableStateOf(true)
        private set

    fun applyTheme(colorKey: String) {
        val p = THEME_PRESETS.firstOrNull { it.key == colorKey } ?: THEME_PRESETS[0]
        accent = p.accent
        accent2 = p.accent2
    }

    fun applyGlass(styleKey: String) {
        glassStyle = if (styleKey in listOf("frosted", "clear", "off")) styleKey else "frosted"
        glassEnabled = glassStyle != "off"
    }

    fun updateDeviceBlurSupported(supported: Boolean) { deviceBlurSupported = supported }

    val trueBlurEnabled: Boolean
        get() = glassEnabled && deviceBlurSupported && android.os.Build.VERSION.SDK_INT >= 31

    val glassBlurRadius: Dp
        get() = if (glassStyle == "clear") 12.dp else 24.dp

    val blurRadiusDp: Dp get() = glassBlurRadius

    val frostTint: Color get() = if (glassStyle == "clear") Color(0x14FFFFFF) else Color(0x1AFFFFFF)
    val frostStrongTint: Color get() = if (glassStyle == "clear") Color(0x24FFFFFF) else Color(0x2EFFFFFF)
    val frostFallback: Color get() = if (glassStyle == "off") Color(0xFF141B2B) else Color(0xE6151B2A)
    val frostStrongFallback: Color get() = if (glassStyle == "off") Color(0xFF1B2438) else Color(0xF21A2232)
    val glassHighlight: Color get() = Color(0x0FFFFFFF)

    /** 玻璃面板填充色 */
    fun glassFill(focused: Boolean): Color = if (focused) glassFillStrong else glassFillSubtle
    val glassFillSubtle: Color get() = when (glassStyle) {
        "clear" -> Color(0x0AFFFFFF)
        "off" -> frostFallback
        else -> Color(0x14FFFFFF)
    }
    val glassFillStrong: Color get() = when (glassStyle) {
        "clear" -> Color(0x1FFFFFFF)
        "off" -> frostStrongFallback
        else -> Color(0x2EFFFFFF)
    }

    /** 玻璃面板描边色 */
    fun glassLine(focused: Boolean): Color = when (glassStyle) {
        "clear" -> if (focused) accent.copy(alpha = 0.7f) else Color(0x14FFFFFF)
        "off" -> if (focused) accent.copy(alpha = 0.9f) else Color(0x0FFFFFFF)
        else -> if (focused) accent.copy(alpha = 0.9f) else Color(0x22FFFFFF)
    }
    val glassEdge: Color get() = glassLine(false)
    val glassEdgeNeutral: Color get() = Color(0x2EFFFFFF)
    val glassEdgeStrong: Color get() = accent.copy(alpha = 0.72f)
}

val Accent: Color get() = ThemeRuntime.accent
val Accent2: Color get() = ThemeRuntime.accent2

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF06101F),
        secondary = Accent2,
        background = Ink,
        onBackground = TextMain,
        surface = InkSoft,
        onSurface = TextMain,
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
}

object ShapeScale {
    val capsule = RoundedCornerShape(26.dp)
    val panel = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(14.dp)
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
