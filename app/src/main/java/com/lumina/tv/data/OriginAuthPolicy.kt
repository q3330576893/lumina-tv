package com.lumina.tv.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** One HTTP call keeps the account that started it, including redirects back to that origin. */
internal data class OriginCredentials(val baseUrl: String, val authorization: String)

internal fun OkHttpClient.Builder.originAuthentication(credentials: () -> OriginCredentials): OkHttpClient.Builder =
    addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().tag(OriginCredentials::class.java,
            request.tag(OriginCredentials::class.java) ?: credentials()).build())
    }.addNetworkInterceptor(OriginAuthInterceptor(credentials))

/** Network interceptors run for every redirect and every HLS/segment/subtitle request. */
internal class OriginAuthInterceptor(private val credentials: () -> OriginCredentials) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val account = request.tag(OriginCredentials::class.java) ?: credentials()
        val builder = request.newBuilder().removeHeader("X-Emby-Authorization")
        if (isSameOrigin(account.baseUrl, request.url.toString())) {
            builder.header("X-Emby-Authorization", account.authorization)
        }
        return chain.proceed(builder.build())
    }
}

/** libmpv has no per-hop header hook. Never give its global header list an Emby credential. */
internal fun nativePlaybackHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys { !it.equals("X-Emby-Authorization", ignoreCase = true) }
