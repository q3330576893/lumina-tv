package com.lumina.tv.player

enum class PlaybackErrorCategory {
    NETWORK_TRANSIENT, HTTP_PERMISSION, HTTP_CONTENT_INVALID, PARSING, DECODING, OTHER,
    AUDIO_OUTPUT, RESOURCE_RECLAIMED, SOURCE_GONE, AUTH_FAILED, CERT_INVALID,
}

enum class PlaybackErrorRoute {
    CERTIFICATE, AUTHENTICATION, SOURCE_GONE, AUDIO_OUTPUT, NETWORK_RETRY,
    RESOURCE_RETRY, PERMISSION_DENIED, MEDIA_RECOVERY,
}

internal fun playbackErrorRoute(category: PlaybackErrorCategory): PlaybackErrorRoute = when (category) {
    PlaybackErrorCategory.CERT_INVALID -> PlaybackErrorRoute.CERTIFICATE
    PlaybackErrorCategory.AUTH_FAILED -> PlaybackErrorRoute.AUTHENTICATION
    PlaybackErrorCategory.SOURCE_GONE -> PlaybackErrorRoute.SOURCE_GONE
    PlaybackErrorCategory.AUDIO_OUTPUT -> PlaybackErrorRoute.AUDIO_OUTPUT
    PlaybackErrorCategory.NETWORK_TRANSIENT -> PlaybackErrorRoute.NETWORK_RETRY
    PlaybackErrorCategory.RESOURCE_RECLAIMED -> PlaybackErrorRoute.RESOURCE_RETRY
    PlaybackErrorCategory.HTTP_PERMISSION -> PlaybackErrorRoute.PERMISSION_DENIED
    else -> PlaybackErrorRoute.MEDIA_RECOVERY
}

internal fun playbackErrorAction(category: PlaybackErrorCategory): String = when (category) {
    PlaybackErrorCategory.AUDIO_OUTPUT -> "switch_mpv_or_reduce_pcm"
    PlaybackErrorCategory.RESOURCE_RECLAIMED -> "retry"
    PlaybackErrorCategory.SOURCE_GONE -> "change_source"
    PlaybackErrorCategory.HTTP_CONTENT_INVALID -> "retry_or_change_source"
    PlaybackErrorCategory.AUTH_FAILED -> "reauthenticate"
    PlaybackErrorCategory.CERT_INVALID -> "check_certificate"
    PlaybackErrorCategory.NETWORK_TRANSIENT -> "retry"
    PlaybackErrorCategory.HTTP_PERMISSION -> "check_permissions"
    PlaybackErrorCategory.PARSING -> "change_source_or_transcode"
    PlaybackErrorCategory.DECODING -> "switch_decoder"
    PlaybackErrorCategory.OTHER -> "inspect_diagnostics"
}

internal fun shouldRetryNetworkPlayback(category: PlaybackErrorCategory, retriesAlreadyUsed: Int): Boolean =
    category == PlaybackErrorCategory.NETWORK_TRANSIENT && retriesAlreadyUsed < 2

/** Wait for ExoPlayer's automatic selector before recovering a source that has audio tracks. */
internal fun shouldRecoverAudioWithoutSelection(
    sourceHasAudio: Boolean,
    selectedAudioTrack: Boolean,
    recoveryAlreadyTried: Boolean,
): Boolean = sourceHasAudio && !selectedAudioTrack && !recoveryAlreadyTried
