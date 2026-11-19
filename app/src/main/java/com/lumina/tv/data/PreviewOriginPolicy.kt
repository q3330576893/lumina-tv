package com.lumina.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Only the Emby credential is origin-bound; source-supplied headers remain intact. */
internal fun isSameOrigin(baseUrl: String, target: String?): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    val url = target?.toHttpUrlOrNull() ?: return false
    return base.scheme == url.scheme && base.host == url.host && base.port == url.port
}

internal class PreviewOriginInterceptor(
    private val baseUrl: () -> String,
    private val authorization: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return OriginAuthInterceptor { OriginCredentials(baseUrl(), authorization()) }.intercept(chain)
    }
}
