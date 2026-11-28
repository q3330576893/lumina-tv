package com.lumina.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.lumina.tv.diag.PageLog
import com.lumina.tv.ui.theme.*
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Executable visual-contract regressions; uses the same policy and palette as the UI. */
object M05Checks {
    @JvmStatic fun main(args: Array<String>) {
        tiersAndSurfaces()
        blurLeases()
        motionAndTokens()
        keyMigration()
        contrast()
        integrationBoundaries()
        println("M05 PASS: tier capability matrix, opaque OFF, one floating blur lease, reduced motion, TV tokens, contrast, ink/oled mapping and six-screen material boundaries")
    }

    private fun tiersAndSurfaces() {
        for (api in listOf(26, 30, 31, 35)) for (lowRam in listOf(false, true)) {
            check(resolveEffectTier(api, lowRam, "off") == EffectTier.OFF)
            check(resolveEffectTier(api, lowRam, "clear") == EffectTier.REDUCED)
            check(resolveEffectTier(api, lowRam, "frosted") ==
                if (api >= 31 && !lowRam) EffectTier.FULL else EffectTier.REDUCED)
            ThemeRuntime.updateDeviceCapabilities(api, lowRam)
            for (style in listOf("off", "clear", "frosted")) {
                ThemeRuntime.applyGlass(style)
                check(ThemeRuntime.effectTier == resolveEffectTier(api, lowRam, style))
                check(ThemeRuntime.trueBlurEnabled == (ThemeRuntime.effectTier == EffectTier.FULL))
                if (style == "off") {
                    check(ThemeRuntime.glassBlurRadius.value == 0f)
                    check(ThemeRuntime.glassHighlight.alpha == 0f)
                    listOf(ThemeRuntime.glassFill, ThemeRuntime.glassStrong, ThemeRuntime.frostTint,
                        ThemeRuntime.frostStrongTint, ThemeRuntime.glassFill(false), ThemeRuntime.glassFill(true))
                        .forEach { check(it.alpha == 1f) }
                }
            }
        }
        for (tier in EffectTier.entries) for (level in SurfaceLevel.entries) {
            for (requested in listOf(false, true)) for (granted in listOf(false, true)) {
                val policy = surfacePolicy(tier, level, requested, granted)
                check(!policy.reflection)
                if (tier == EffectTier.OFF) check(policy == SurfacePolicy(1f))
                if (level != SurfaceLevel.Floating || !requested) check(policy == SurfacePolicy(1f))
                if (policy.blur) check(tier == EffectTier.FULL && level == SurfaceLevel.Floating && requested && granted)
                if (tier == EffectTier.REDUCED) check(!policy.blur && !policy.highlight)
            }
        }
        check(surfacePolicy(EffectTier.REDUCED, SurfaceLevel.Floating, true).opacity in 0.9f..<1f)
        check(surfacePolicy(EffectTier.FULL, SurfaceLevel.Floating, true, false).opacity == 1f)
        check(surfacePolicy(EffectTier.FULL, SurfaceLevel.Floating, true, true).blur)
    }

    private fun blurLeases() {
        val budget = BlurBudget()
        check(budget.limit == 1)
        val owners = List(8) { Any() }
        owners.forEach { owner ->
            budget.request(owner)
            budget.request(owner) // Recomposition cannot allocate a second slot.
            check(owners.count { budget.granted(it) } == 1)
        }
        owners.forEachIndexed { index, owner ->
            check(budget.granted(owner))
            budget.release(owner)
            check(!budget.granted(owner))
            check(owners.count { budget.granted(it) } == if (index == owners.lastIndex) 0 else 1)
        }
    }

    private fun motionAndTokens() {
        for (tier in EffectTier.entries) for (system in listOf(false, true)) {
            val policy = motionPolicy(tier, system)
            val reduced = system || tier != EffectTier.FULL
            check(policy.reduced == reduced)
            check(policy.idleAnimations == !reduced && policy.carouselDisplacement == !reduced)
            check(policy.focusScale == if (reduced) 1f else 1.04f)
            check(policy.duration(180) == if (reduced) 0 else 180)
            ThemeRuntime.updateDeviceCapabilities(35, false)
            ThemeRuntime.applyGlass(when (tier) {
                EffectTier.FULL -> "frosted"
                EffectTier.REDUCED -> "clear"
                EffectTier.OFF -> "off"
            })
            ThemeRuntime.updateSystemMotion(system)
            check(ThemeRuntime.motion == policy)
            check(TvMotion.FOCUS == if (reduced) 0 else TvTokenValues.FOCUS_MS)
            check(TvMotion.CATEGORY == if (reduced) 0 else TvTokenValues.PAGE_MS)
        }
        check(TvTokenValues.FOCUS_BORDER_DP == 3f && TvTokenValues.FOCUS_SCALE == 1.04f)
        check(TvTokenValues.FOCUS_MS in 140..200)
        check(TvTokenValues.PRESS_MS in 100..140)
        check(TvTokenValues.PAGE_MS in 180..260)
        check(TvTokenValues.CONTENT_RADIUS_DP <= TvTokenValues.GROUP_RADIUS_DP)
        check(TvTokenValues.GROUP_RADIUS_DP <= 20 && TvTokenValues.MODAL_RADIUS_DP <= 26)
        check(TvTokenValues.ROW_DESCRIPTION_LINES == 2)
        val insets = listOf(320f, 720f, 960f, 1280f, 1920f).map(TvTokenValues::safeInset)
        check(insets.first() == 24f && insets.last() == 48f && insets.zipWithNext().all { (a,b) -> a <= b })
        check(!TvTokenValues.loginColumnsFit(720f, 540f, 1f))
        check(TvTokenValues.loginColumnsFit(960f, 540f, 1f))
        check(!TvTokenValues.loginColumnsFit(960f, 540f, 1.5f))
        check(!TvTokenValues.loginColumnsFit(1920f, 300f, 1f))
    }

    private fun keyMigration() {
        check(resolveBaseThemeKey("ink") == BaseThemeResolution("oled", true, false))
        check(resolveBaseThemeKey("oled") == BaseThemeResolution("oled", false, false))
        for (key in listOf("graphite", "warm", "mint", "missing-theme")) {
            check(resolveBaseThemeKey(key) == BaseThemeResolution("oled", false, true))
            ThemeRuntime.applyBase(key)
            check(ThemeRuntime.baseThemeKey == "oled")
        }
        check("fallback=true storedPreference=preserved" in PageLog.tail())
        ThemeRuntime.applyBase("ink")
        check(BASE_PRESETS.single { it.key == ThemeRuntime.baseThemeKey }.bgTop == ThemeRuntime.bgTop)
        check("mapped=true fallback=false storedPreference=preserved" in PageLog.tail())
    }

    private fun contrast() {
        fun ratio(a: Color, b: Color): Double = (max(a.luminance(), b.luminance()) + 0.05).toDouble() /
            (min(a.luminance(), b.luminance()) + 0.05)
        for (preset in THEME_PRESETS) {
            ThemeRuntime.applyTheme(preset.key)
            for (level in SurfaceLevel.entries) for (focused in listOf(false, true))
                for (selected in listOf(false, true)) for (tone in SurfaceTone.entries) {
                val fill = surfaceColor(level, focused, selected, tone)
                check(fill.alpha == 1f)
                for (text in listOf(TextMain, TextSub)) check(ratio(text, fill) >= TvTokenValues.BODY_CONTRAST) {
                    "Body contrast ${ratio(text, fill)} below target on $level focused=$focused"
                }
                if (level != SurfaceLevel.Canvas) check(ratio(TvTokens.Boundary, fill) >= TvTokenValues.CONTROL_CONTRAST)
            }
            check(ratio(Color(0xFF06101F), Accent) >= TvTokenValues.BODY_CONTRAST)
        }
        // Brightest possible image underneath REDUCED's one panel.
        val panel = Color(0xFF1B2438).copy(alpha = 0.94f).compositeOver(Color.White)
        check(ratio(TextSub, panel) >= TvTokenValues.BODY_CONTRAST)
        ThemeRuntime.applyTheme("blue")
    }

    private fun integrationBoundaries() {
        val root = listOf(File("src/main/java/com/lumina/tv/ui"), File("app/src/main/java/com/lumina/tv/ui"))
            .first { it.isDirectory }
        val browse = listOf("Home", "Search", "Detail", "Favorites")
        for (screen in browse + "Login") {
            val source = File(root, "screens/${screen}Screen.kt").readText()
            check(".luminaSurface(" in source && ("TvTokens" in source || "tvPagePadding" in source))
            check(".background(" !in source && ".blur(" !in source && ".hazeEffect(" !in source)
            if (screen in browse) check("FrostPanel(" !in source && ".glassSurface(" !in source)
        }
        val renderer = File(root, "components/DetailHeroParallax.kt").readText()
        check(".blur(" !in renderer && "ThemeRuntime.motion.reduced" in renderer)
        val focus = File(root, "components/Components.kt").readText()
        check("delay(" !in focus && "onGloballyPositioned" in focus && "TvTokens.FocusBorder" in focus)
        check("LaunchedEffect(requestFocus, enabled)" in focus && "snapshotFlow { mounted }.first { it }" in focus)
        check("manager?.isLowRamDevice == false && android.os.Build.VERSION.SDK_INT >= 31" in focus)
        check("allowBlur = supported, floatingEffect = true" in focus)
        val adapter = File(root, "components/GlassSurface.kt").readText()
        check("luminaSurface(" in adapter && "drawBehind" !in adapter)
        val preview = File(root, "theme/EffectTierPreview.kt").readText()
        check("hazeEffect(" !in preview && ".blur(" !in preview)
        val appearance = File(root, "screens/settings/SettingsAppearance.kt").readText()
        check("resolveBaseThemeKey(prefs?.baseTheme).key" in appearance && "EffectTierPreview(" in appearance)
        val splash = File(root, "screens/SplashScreen.kt").readText()
        check(splash.indexOf("if (shimmerActive)") < splash.indexOf("rememberInfiniteTransition(label"))
        check("ThemeRuntime.motion.idleAnimations" in splash)
        val detail = File(root, "screens/DetailScreen.kt").readText()
        check("if (focused) 1.08f" !in detail && "label = \"personFocus\"" in detail)
        for (source in File(root, "screens/settings").listFiles().orEmpty().filter { it.extension == "kt" }) {
            check(".background(" !in source.readText()) { "Independent material in ${source.name}" }
        }
    }
}
