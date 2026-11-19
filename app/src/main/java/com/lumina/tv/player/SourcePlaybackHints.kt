package com.lumina.tv.player

import com.lumina.tv.data.DeviceCodecCapabilities
import com.lumina.tv.data.MediaSource

data class SourceHint(val priority: Int, val label: String?)

fun sourceHint(source: MediaSource, engine: String, caps: AudioCaps): SourceHint {
    val video = source.streams.firstOrNull { it.type == "Video" }
    val range = video?.videoRangeType.orEmpty()
    val isDv = video?.dvProfile != null || range.contains("DOVI", true) ||
        range.contains("DOLBY", true) || range.equals("DV", true)
    val isDvP7 = video?.dvProfile == 7 || range.contains("WITHEL", true)
    val audio = source.audioStreams.firstOrNull { it.isDefault } ?: source.audioStreams.firstOrNull()

    var hint = when {
        isDvP7 && engine != "mpv" -> SourceHint(3, "DV P7 需 mpv")
        isDvP7 -> SourceHint(-2, null)
        isDv && engine != "mpv" && !DeviceCodecCapabilities.supportsDolbyVision -> SourceHint(2, "DV 可能不兼容")
        audio != null -> {
            val codec = listOfNotNull(audio.codec, audio.profile).joinToString(" ").lowercase()
            val mime = audioMimeForSourceHint(codec)
            val encoding = AudioCodecMatrix.encoding(mime, codec)
            when (AudioCodecMatrix.support(encoding, audio.channels ?: 2, audio.sampleRate ?: 0, caps, mime)) {
                AudioSupport.UNSUPPORTED -> SourceHint(2, "音轨可能不支持")
                AudioSupport.PASSTHROUGH -> SourceHint(-1, null)
                AudioSupport.DECODE_ONLY -> SourceHint(0, null)
            }
        }
        else -> SourceHint(0, null)
    }
    if (!source.supportsDirectPlay) {
        hint = hint.copy(label = listOfNotNull(hint.label, "需转码").joinToString(" · ").ifBlank { null })
    }
    return hint
}

internal fun audioMimeForSourceHint(codec: String): String? = when {
    codec.contains("eac3") || codec.contains("e-ac-3") -> if (codec.contains("joc") || codec.contains("atmos")) "audio/eac3-joc" else "audio/eac3"
    codec.contains("truehd") || codec.contains("mlp") -> "audio/true-hd"
    codec.contains("dts") && (codec.contains("uhd") || codec.contains("p2")) -> "audio/vnd.dts.uhd;profile=p2"
    codec.contains("dts") && (codec.contains("hd") || codec.contains("ma") || codec.contains("lbr")) -> "audio/vnd.dts.hd"
    codec.contains("dts") || codec.contains("dca") -> "audio/vnd.dts"
    codec.contains("ac3") -> "audio/ac3"
    codec.contains("ac4") -> "audio/ac4"
    codec.contains("aac") || codec.contains("mp4a") -> "audio/mp4a-latm"
    codec.contains("opus") -> "audio/opus"
    codec.contains("mp3") || codec.contains("mpeg") -> "audio/mpeg"
    codec.contains("flac") -> "audio/flac"
    codec.contains("vorbis") -> "audio/vorbis"
    codec.contains("pcm") || codec.contains("lpcm") -> "audio/raw"
    else -> null
}
