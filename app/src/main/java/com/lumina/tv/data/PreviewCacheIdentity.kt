package com.lumina.tv.data

import org.json.JSONObject

/** Only content metadata, never UserData, access tokens or playback/session identifiers. */
internal fun previewContentVersion(json: JSONObject): String = stableCacheDigest(
    *listOf("ETag", "DateModified", "DateLastSaved", "Path", "Size", "RunTimeTicks", "Container", "Bitrate")
        .map { name -> json.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }.toTypedArray(),
)

internal fun previewSourceVersion(source: MediaSource): String = stableCacheDigest(
    source.contentVersion, source.size.toString(), source.container.orEmpty(), source.bitrate.toString(),
    source.streams.joinToString("|") { "${it.type}:${it.codec}:${it.width}:${it.height}:${it.channels}:${it.sampleRate}" },
)

internal fun previewCacheKey(
    serverUrl: String, userId: String, item: MediaItem, source: MediaSource, previewType: String,
): String? = identityScope(serverUrl, userId)?.let { account ->
    "preview:v2:$account:" + stableCacheDigest(
        item.id, source.id, previewType, item.contentVersion, item.runtimeTicks.toString(), previewSourceVersion(source),
    )
}

/** HTTP validators catch replacements that retain the same item/source IDs and metadata. */
internal fun previewResourceKey(mediaKey: String, etag: String?, lastModified: String?, length: String?): String =
    "$mediaKey:${stableCacheDigest(etag.orEmpty(), lastModified.orEmpty(), length.orEmpty())}"

/** A failed cached parse is retried upstream once; only a successful fresh decode confirms bad bytes. */
internal class PreviewCacheRecovery {
    var retried: Boolean = false
        private set
    private var removed = false
    private var awaitingFreshFrame = false

    fun retry(cacheReadFailure: Boolean, malformedCachedInput: Boolean): Boolean {
        if (retried || (!cacheReadFailure && !malformedCachedInput)) return false
        retried = true
        awaitingFreshFrame = !cacheReadFailure
        return true
    }

    fun shouldRemove(freshFrame: Boolean): Boolean {
        if (!retried || removed || (awaitingFreshFrame && !freshFrame)) return false
        removed = true
        return true
    }
}
