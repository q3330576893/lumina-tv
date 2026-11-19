package com.lumina.tv.player

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** Null means the platform did not provide display HDR capability data. */
internal fun readDisplayHdrTypes(context: Context): IntArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay ?: return null
    return runCatching { display.hdrCapabilities?.supportedHdrTypes?.copyOf() }.getOrNull()
}

/** Null for unknown/non-HDR/Dolby Vision content keeps existing playback policy unchanged. */
internal fun supportsContentHdr(supported: IntArray?, contentHdr: String?): Boolean? {
    if (supported == null) return null
    val normalized = contentHdr.orEmpty().uppercase().filter(Char::isLetterOrDigit)
    val requiredType = when (normalized) {
        "HDR10PLUS", "HDR10P" -> Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        "HDR10", "PQ", "SMPTE2084", "SMPTEST2084" -> Display.HdrCapabilities.HDR_TYPE_HDR10
        "HLG", "ARIBSTDB67" -> Display.HdrCapabilities.HDR_TYPE_HLG
        else -> return null
    }
    return requiredType in supported
}

internal fun displayHdrDiagnostic(supported: IntArray?): List<String>? = supported?.map { type ->
    when (type) {
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        else -> "unknown:$type"
    }
}
