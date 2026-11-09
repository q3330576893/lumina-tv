package com.lumina.tv.data

import android.media.MediaCodecList

object DeviceCodecCapabilities {
    val supportsDolbyVision: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any {
                    it.equals("video/dolby-vision", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }
}
