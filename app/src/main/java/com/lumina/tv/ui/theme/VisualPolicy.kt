package com.lumina.tv.ui.theme

/** Persisted frosted/clear/off values remain readable; clear now explicitly requests REDUCED. */
enum class EffectTier { FULL, REDUCED, OFF }
enum class SurfaceLevel { Canvas, Content, Floating, Overlay }

fun resolveEffectTier(api: Int, lowRam: Boolean, glassStyle: String): EffectTier = when {
    glassStyle == "off" -> EffectTier.OFF
    glassStyle == "clear" -> EffectTier.REDUCED
    api >= 31 && !lowRam -> EffectTier.FULL
    else -> EffectTier.REDUCED
}

data class SurfacePolicy(
    val opacity: Float,
    val blur: Boolean = false,
    val highlight: Boolean = false,
    val reflection: Boolean = false,
)

/** Content is the solid, tint-composited fallback on every browsing page. Only an opted-in
 * Floating container can be translucent; an unavailable FULL blur falls back to a solid panel. */
fun surfacePolicy(
    tier: EffectTier,
    level: SurfaceLevel,
    floatingEffect: Boolean = false,
    blurGranted: Boolean = false,
): SurfacePolicy = when {
    tier == EffectTier.OFF -> SurfacePolicy(1f)
    level != SurfaceLevel.Floating || !floatingEffect -> SurfacePolicy(1f)
    tier == EffectTier.REDUCED -> SurfacePolicy(0.94f)
    blurGranted -> SurfacePolicy(0.94f, blur = true, highlight = true)
    else -> SurfacePolicy(1f)
}

/** Lease order is stable, and releasing a panel hands its slot to the next mounted panel. */
class BlurBudget(val limit: Int = MAX_BLURRED_FLOATING_LAYERS) {
    private val owners = linkedSetOf<Any>()
    fun request(owner: Any) { owners.add(owner) }
    fun release(owner: Any) { owners.remove(owner) }
    fun granted(owner: Any): Boolean = owner in owners.take(limit)
    companion object { const val MAX_BLURRED_FLOATING_LAYERS = 1 }
}

data class MotionPolicy(val reduced: Boolean) {
    val idleAnimations: Boolean get() = !reduced
    val carouselDisplacement: Boolean get() = !reduced
    val focusScale: Float get() = if (reduced) 1f else TvTokenValues.FOCUS_SCALE
    fun duration(millis: Int): Int = if (reduced) 0 else millis
}

fun motionPolicy(tier: EffectTier, systemRemovesAnimations: Boolean): MotionPolicy =
    MotionPolicy(systemRemovesAnimations || tier != EffectTier.FULL)

data class BaseThemeResolution(val key: String, val mapped: Boolean, val fallback: Boolean)

/** Mapping deliberately never rewrites or removes a stored preference. */
fun resolveBaseThemeKey(storedKey: String?): BaseThemeResolution = when (storedKey) {
    "oled" -> BaseThemeResolution("oled", mapped = false, fallback = false)
    "ink", null -> BaseThemeResolution("oled", mapped = true, fallback = false)
    else -> BaseThemeResolution("oled", mapped = false, fallback = true)
}

object TvTokenValues {
    const val SAFE_MIN_DP = 24f
    const val SAFE_MAX_DP = 48f
    const val FOCUS_BORDER_DP = 3f
    const val FOCUS_SCALE = 1.04f
    const val FOCUS_MS = 160
    const val PRESS_MS = 120
    const val PAGE_MS = 220
    const val CONTENT_RADIUS_DP = 14f
    const val GROUP_RADIUS_DP = 20f
    const val MODAL_RADIUS_DP = 24f
    const val ROW_DESCRIPTION_LINES = 2
    const val BODY_CONTRAST = 4.5
    const val CONTROL_CONTRAST = 3.0

    fun safeInset(availableWidthDp: Float): Float =
        (availableWidthDp * 0.04f).coerceIn(SAFE_MIN_DP, SAFE_MAX_DP)

    // Existing rail / brand / form hierarchy, admitted only when all three fit at the font size.
    fun loginColumnsFit(widthDp: Float, heightDp: Float, fontScale: Float): Boolean =
        widthDp - 2 * safeInset(widthDp) >= 78f + 60f + 280f * fontScale + 360f * fontScale &&
            heightDp >= 420f * fontScale
}
