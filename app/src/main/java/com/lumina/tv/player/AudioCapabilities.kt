package com.lumina.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioProfile
import android.media.MediaCodecList
import android.os.Build

data class AudioProfileInfo(
    val maxChannels: Int,
    val channelMasks: Set<Int>,
    val sampleRates: Set<Int>,
)

data class AudioCapabilities(val profiles: Map<Int, List<AudioProfileInfo>>)
typealias AudioCaps = AudioCapabilities

enum class AudioSupport { PASSTHROUGH, DECODE_ONLY, UNSUPPORTED }

object AudioCodecMatrix {
    val PASSTHROUGH_ENCODINGS: Set<Int> = setOf(5, 6, 7, 8, 14, 17, 18, 30)

    fun encoding(mime: String?, codecs: String?): Int {
        val normalizedMime = mime.orEmpty().substringBefore(';').trim().lowercase()
        val normalizedCodecs = codecs.orEmpty().lowercase()
        return when (normalizedMime) {
            "audio/ac3" -> 5
            "audio/eac3" -> 6
            "audio/eac3-joc" -> 18
            "audio/vnd.dts" -> 7
            "audio/vnd.dts.hd" -> if (mime.orEmpty().contains("profile=lbr", true)) 8 else 8
            "audio/vnd.dts.uhd" -> if (mime.orEmpty().contains("profile=p2", true)) 30 else 0
            "audio/true-hd" -> 14
            "audio/ac4" -> 17
            "audio/mpeg" -> 9
            "audio/opus" -> 20
            "audio/mp4a-latm" -> when {
                normalizedCodecs.contains(".40.29") -> AudioFormat.ENCODING_AAC_HE_V2
                normalizedCodecs.contains(".40.5") -> AudioFormat.ENCODING_AAC_HE_V1
                normalizedCodecs.contains(".40.39") -> AudioFormat.ENCODING_AAC_ELD
                else -> AudioFormat.ENCODING_AAC_LC
            }
            else -> 0
        }
    }

    fun fallback(enc: Int, caps: AudioCaps): Int {
        if (!caps.profiles[enc].isNullOrEmpty()) return enc
        val candidate = when (enc) {
            18 -> 6
            8, 30 -> 7
            else -> return 0
        }
        return candidate.takeIf { !caps.profiles[candidate].isNullOrEmpty() } ?: 0
    }

    fun channelMask(channels: Int): Int = when (channels) {
        1 -> 4
        2 -> 12
        6 -> 252
        8 -> 6396
        else -> 0
    }

    fun canPassthrough(enc: Int, channels: Int, sampleRate: Int, caps: AudioCaps): Boolean {
        if (enc !in PASSTHROUGH_ENCODINGS) return false
        val selectedEncoding = fallback(enc, caps)
        if (selectedEncoding == 0) return false
        val profiles = caps.profiles[selectedEncoding].orEmpty()
        val mask = channelMask(channels)
        return channels > 0 && mask != 0 && profiles.any { profile ->
            val rateOk = sampleRate <= 0 || profile.sampleRates.isEmpty() || sampleRate in profile.sampleRates
            channels <= profile.maxChannels && mask in profile.channelMasks && rateOk
        }
    }

    fun support(context: Context, enc: Int, channels: Int, sampleRate: Int, caps: AudioCaps, mime: String?): AudioSupport =
        support(enc, channels, sampleRate, caps, mime)

    fun support(enc: Int, channels: Int, sampleRate: Int, caps: AudioCaps, mime: String?): AudioSupport {
        if (canPassthrough(enc, channels, sampleRate, caps)) return AudioSupport.PASSTHROUGH
        if (enc == 0 && mime == "audio/raw") return AudioSupport.DECODE_ONLY
        val audioMime = mime ?: mimeForEncoding(enc)
        val decodable = audioMime != null && runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(audioMime, true) }
            }
        }.getOrDefault(false)
        return if (decodable) AudioSupport.DECODE_ONLY else AudioSupport.UNSUPPORTED
    }

    private fun mimeForEncoding(enc: Int): String? = when (enc) {
        5 -> "audio/ac3"
        6, 18 -> "audio/eac3"
        7, 8, 30 -> "audio/vnd.dts"
        9 -> "audio/mpeg"
        10, 11, 12, 15 -> "audio/mp4a-latm"
        14 -> "audio/true-hd"
        17 -> "audio/ac4"
        20 -> "audio/opus"
        else -> null
    }
}

fun detect(context: Context): AudioCaps {
    val profiles = linkedMapOf(
        AudioFormat.ENCODING_PCM_16BIT to mutableListOf(
            AudioProfileInfo(2, setOf(AudioFormat.CHANNEL_OUT_STEREO), emptySet())
        ),
    )
    if (Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getDirectProfilesForAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
        ).forEach { profile: AudioProfile ->
            val masks = profile.channelMasks.filter { it != 0 }.toSet()
            if (masks.isNotEmpty()) {
                val maxChannels = masks.maxOf { mask -> if (mask == 6396) 8 else Integer.bitCount(mask) }
                profiles.getOrPut(profile.format) { mutableListOf() }
                    .add(AudioProfileInfo(maxChannels, masks, profile.sampleRates.toSet()))
            }
        }
    } else {
        val intent = context.registerReceiver(null, android.content.IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        val connected = intent?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1
        if (connected) {
            val encodings: IntArray =
                intent?.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS) ?: intArrayOf()
            val maxChannels: Int =
                (intent?.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 2) ?: 2).coerceAtLeast(2)
            encodings.forEach { enc ->
                val masks = listOf(1, 2, 6, 8).mapNotNull { count ->
                    AudioCodecMatrix.channelMask(count).takeIf { count <= maxChannels }
                }.toSet()
                if (masks.isNotEmpty()) profiles[enc] = mutableListOf(AudioProfileInfo(maxChannels, masks, emptySet()))
            }
        }
    }
    return AudioCaps(profiles.mapValues { (_, encodingProfiles) -> encodingProfiles.toList() })
}
