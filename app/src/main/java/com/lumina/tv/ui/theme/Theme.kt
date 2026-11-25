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
    GlassPreset("frosted", "磨砂"),
    GlassPreset("clear", "清透"),
    GlassPreset("off", "关闭"),
)

/**
 * 运行时主题状态：主题色与玻璃质感可从设置页即时切换。
 * Accent / Accent2 保持 val 语义（getter 读取 state），全部既有调用点无需改动。
 */
object ThemeRuntime {
    var bgTop by mutableStateOf(Color(0xFF000000))
        private set
    var bgCard by mutableStateOf(Color(0xFF0A0A0C))
        private set
    var glassFill by mutableStateOf(Color(0x14FFFFFF))
        private set
    var glassStrong by mutableStateOf(Color(0x1FFFFFFF))
        private set
    var glassLine by mutableStateOf(Color(0x22FFFFFF))
        private set
    var accent by mutableStateOf(Color(0xFF7C9CF5))
        private set
    var accent2 by mutableStateOf(Color(0xFF5FD3C4))
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

    fun applyBase(key: String) {
        val p = BASE_PRESETS.firstOrNull { it.key == key } ?: BASE_PRESETS[0]
        bgTop = p.bgTop
        bgCard = p.bgCard
        glassFill = p.glassFill
        glassStrong = p.glassStrong
        glassLine = p.glassLine
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
