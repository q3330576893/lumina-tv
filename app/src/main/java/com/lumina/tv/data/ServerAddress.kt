package com.lumina.tv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** basePath is encoded and case-sensitive; baseUrl has no trailing slash or credentials. */
class ServerAddress internal constructor(private val url: HttpUrl) {
    val scheme: String get() = url.scheme
    val host: String get() = url.host
    val port: Int get() = url.port
    val basePath: String get() = url.encodedPath.trimEnd('/')
    val baseUrl: String get() = url.toString().trimEnd('/')
    val hostWithPath: String get() = (if (':' in host) "[$host]" else host) + basePath

    fun resolve(relative: String): String {
        val path = relative.trimStart('/')
        val prefix = basePath.trimStart('/')
        return if (prefix.isNotEmpty() && (path == prefix || path.startsWith("$prefix/"))) {
            url.newBuilder().encodedPath("/").build().toString() + path
        } else "$baseUrl/$path"
    }

    fun withEndpoint(scheme: String, port: Int): ServerAddress =
        ServerAddress(url.newBuilder().scheme(scheme).port(port).build())
}

/** Full URLs take precedence over separate form fields. Bare hosts use the supplied defaults. */
fun parseServerAddress(
    raw: String,
    scheme: String = "http",
    port: String? = null,
    basePath: String? = null,
): ServerAddress? = runCatching {
    val input = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val hasScheme = input.contains("://")
    val parsed = (if (hasScheme) input else "$scheme://$input").toHttpUrlOrNull() ?: return null
    val authority = input.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hasPort = Regex(":\\d+$").containsMatchIn(authority)
    val builder = parsed.newBuilder().username("").password("").query(null).fragment(null)
    if (!hasScheme && !hasPort && !port.isNullOrBlank()) {
        builder.port(port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
    }
    var path = basePath?.trim()?.takeIf { it.isNotEmpty() && it != "/" } ?: parsed.encodedPath
    path = path.trimEnd('/').removeSuffix("/web/index.html").removeSuffix("/web")
    path = if (path.startsWith('/')) path else "/$path"
    builder.encodedPath(path)
    ServerAddress(builder.build())
}.getOrNull()

/** Shared by local content state, recommendations and preview caches. Never creates an anonymous partition. */
fun identityScope(serverUrl: String, userId: String): String? {
    if (userId.isBlank()) return null
    val server = parseServerAddress(serverUrl)?.baseUrl ?: return null
    return stableCacheDigest(server, userId)
}

internal fun stableCacheDigest(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
