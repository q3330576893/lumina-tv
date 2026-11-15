package com.lumina.tv.data

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.lumina.tv.AppRuntime
import com.lumina.tv.diag.PageLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PlaybackCache {
    private const val DEFAULT_LIMIT = 512L * 1024L * 1024L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val sensitiveParams = setOf("api_key", "apikey", "token", "access_token", "authorization", "auth", "x-emby-token")
    @Volatile private var instance: SimpleCache? = null
    @Volatile private var limitBytes = DEFAULT_LIMIT

    fun setLimit(bytes: Long) {
        val normalized = bytes.coerceAtLeast(0L)
        synchronized(this) {
            if (limitBytes == normalized) return
            runCatching { instance?.release() }
            instance = null
            limitBytes = normalized
        }
    }

    fun enabled(): Boolean = limitBytes > 0L

    fun get(context: Context): SimpleCache? {
        if (!enabled()) return null
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return runCatching {
                SimpleCache(
                    File(context.applicationContext.filesDir, "playback_cache"),
                    LeastRecentlyUsedCacheEvictor(limitBytes),
                    StandaloneDatabaseProvider(context.applicationContext),
                ).also { instance = it }
            }.getOrNull()
        }
    }

    fun dataSourceFactory(context: Context, upstream: androidx.media3.datasource.DataSource.Factory): CacheDataSource.Factory? =
        runCatching {
            val cache = get(context) ?: return null
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(CacheKeyFactory { spec -> safeKey(spec) })
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }.getOrNull()

    fun spaceBytes(context: Context): Long = get(context)?.cacheSpace ?: 0L

    fun clear(context: Context) {
        runCatching { get(context)?.let { cache -> cache.keys.toList().forEach(cache::removeResource) } }
    }

    suspend fun autoTrim(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - (AppRuntime.prefs?.playbackCacheLastTrimAt ?: 0L) < DAY_MS) return@withContext
        runCatching {
            val cache = get(context)
            if (cache != null) {
                val spans = cache.keys.flatMap { key ->
                    cache.getCachedSpans(key).mapNotNull { span -> span.file?.let { file -> span to file } }
                }
                spans.filter { (_, file) -> now - file.lastModified() >= DAY_MS }
                    .forEach { (span, _) -> runCatching { cache.removeSpan(span) } }

                val limit = limitBytes
                if (limit > 0L && cache.cacheSpace > limit * 0.9) {
                    val oldestFirst = spans.sortedBy { (_, file) -> file.lastModified() }
                    for ((span, _) in oldestFirst) {
                        if (cache.cacheSpace <= limit * 0.7) break
                        runCatching { cache.removeSpan(span) }
                    }
                }
            }
            AppRuntime.prefs?.playbackCacheLastTrimAt = now
        }.onFailure { error ->
            PageLog.add("playbackCache autoTrim failed=${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun mediaKey(server: String, user: String, itemId: String, sourceId: String?): String =
        "lumina:" + sha256(listOf(server, user, itemId, sourceId.orEmpty()).joinToString("\u0000"))

    private fun safeKey(spec: DataSpec): String {
        val raw = spec.key ?: spec.uri.toString()
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return sha256(raw)
        if (uri.isOpaque) return raw
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name.lowercase() !in sensitiveParams) {
                uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
            }
        }
        return builder.build().toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
