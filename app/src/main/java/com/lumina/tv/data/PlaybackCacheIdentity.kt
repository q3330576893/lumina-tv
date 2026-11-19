package com.lumina.tv.data

import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

internal fun playbackContentValidator(source: JSONObject): String? {
    fun value(name: String): String? = source.opt(name)
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val etag = value("ETag")
    val modified = value("DateModified") ?: value("DateLastSaved")
    if (etag == null && modified == null) return null
    return cacheDigest(etag.orEmpty(), modified.orEmpty(), value("Size").orEmpty())
}

internal fun stablePlaybackRouteIdentity(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return ""
    val authority = uri.rawAuthority?.substringAfterLast('@').orEmpty()
    if (uri.scheme.isNullOrBlank() || authority.isBlank()) return ""
    val safeQuery = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).filter { parameter ->
        val name = parameter.substringBefore('=').lowercase()
        name !in SENSITIVE_ROUTE_PARAMS && !name.contains("token") &&
            !name.contains("authorization") && !name.contains("session")
    }.sorted()
    return buildString {
        append(uri.scheme.lowercase()).append("://").append(authority.lowercase())
        append(uri.rawPath.orEmpty())
        if (safeQuery.isNotEmpty()) append('?').append(safeQuery.joinToString("&"))
    }
}

internal fun playbackCacheKey(
    server: String,
    user: String,
    itemId: String,
    sourceId: String,
    contentValidator: String?,
    sourceSize: Long,
    mode: String,
    routeIdentity: String,
    audioIndex: Int?,
    subtitleMode: String?,
    subtitleIndex: Int?,
    startPositionMs: Long,
): String? {
    val validator = contentValidator?.takeIf(String::isNotBlank) ?: return null
    if (routeIdentity.isBlank()) return null
    val outputRoute = listOf(
        mode,
        routeIdentity,
        if (mode == PlaybackMode.DirectPlay.name) "" else audioIndex?.toString().orEmpty(),
        if (mode == PlaybackMode.DirectPlay.name) "" else subtitleMode.orEmpty(),
        if (mode == PlaybackMode.DirectPlay.name) "" else subtitleIndex?.toString().orEmpty(),
        if (mode == PlaybackMode.DirectPlay.name) "0" else startPositionMs.toString(),
    )
    return "lumina:v2:" + cacheDigest(
        server, user, itemId, sourceId, validator, sourceSize.toString(), *outputRoute.toTypedArray(),
    )
}

private val SENSITIVE_ROUTE_PARAMS = setOf(
    "api_key", "apikey", "access_token", "authorization", "auth", "x-emby-token", "playsessionid", "deviceid",
)

private fun cacheDigest(vararg values: String): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
