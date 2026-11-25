package com.lumina.tv.data

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
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
    private var configured = false

    /** Only applies prefs; does not create SimpleCache. Safe before every entry point. */
    @Synchronized
    fun initialize(context: Context) {
        AppRuntime.init(context)
        val savedLimit = requireNotNull(AppRuntime.prefs).playbackCacheBytes
        setLimit(savedLimit)
        if (!configured) {
            configured = true
            PageLog.add("playbackCache initialized bytes=$savedLimit enabled=${savedLimit > 0L}")
        }
    }

    fun setLimit(bytes: Long) {
        val normalized = bytes.coerceAtLeast(0L)
        synchronized(this) {
            if (limitBytes == normalized) return
            releaseLocked()
            limitBytes = normalized
        }
    }

    fun enabled(): Boolean = limitBytes > 0L

    fun get(context: Context): SimpleCache? {
        synchronized(this) {
            return runCatching {
                initialize(context)
                if (!enabled()) return null
                openCacheLocked(context)
            }.getOrNull()
        }
    }

    private fun cacheDirectory(context: Context) = File(context.applicationContext.filesDir, "playback_cache")

    /** Callers hold this object's lock, including maintenance while normal cache use is disabled. */
    private fun openCacheLocked(context: Context): SimpleCache = instance ?: SimpleCache(
        cacheDirectory(context),
        if (enabled()) LeastRecentlyUsedCacheEvictor(limitBytes) else NoOpCacheEvictor(),
        StandaloneDatabaseProvider(context.applicationContext),
    ).also { instance = it }

    private fun releaseLocked() {
        val cache = instance ?: return
        try { cache.release() } finally { instance = null }
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

    fun spaceBytes(context: Context): Long = PlaybackCacheStorage(cacheDirectory(context), this).spaceBytes()

    fun clear(context: Context): Result<Unit> = PlaybackCacheStorage(cacheDirectory(context), this).clear(
        release = ::releaseLocked,
        delete = { directory ->
            val database = StandaloneDatabaseProvider(context.applicationContext)
            try { SimpleCache.delete(directory, database) } finally { database.close() }
        },
    ).onFailure { PageLog.add("playbackCache clear failed=${it.javaClass.simpleName}") }

    suspend fun autoTrim(context: Context, force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(this@PlaybackCache) {
            runCatching {
                initialize(context)
                val now = System.currentTimeMillis()
                if (!force && now - (AppRuntime.prefs?.playbackCacheLastTrimAt ?: 0L) < DAY_MS) return@runCatching
                if (cacheDirectory(context).exists()) {
                    val cache = openCacheLocked(context)
                    try {
                        val spans = cache.keys.flatMap { key ->
                            cache.getCachedSpans(key).mapNotNull { span -> span.file?.let { file -> span to file } }
                        }
                        spans.filter { (_, file) -> now - file.lastModified() >= DAY_MS }
                            .forEach { (span, _) -> cache.removeSpan(span) }

                        val limit = limitBytes
                        if (limit > 0L && cache.cacheSpace > limit * 0.9) {
                            val oldestFirst = spans.sortedBy { (_, file) -> file.lastModified() }
                            for ((span, _) in oldestFirst) {
                                if (cache.cacheSpace <= limit * 0.7) break
                                cache.removeSpan(span)
                            }
                        }
                    } finally {
                        if (!enabled()) releaseLocked()
                    }
                }
                AppRuntime.prefs?.playbackCacheLastTrimAt = now
            }
        }.onFailure { error ->
            PageLog.add("playbackCache autoTrim failed=${error.javaClass.simpleName}")
        }
    }

    fun mediaKey(server: String, user: String, itemId: String, source: MediaSource,
        target: EmbyClient.PlaybackTarget): String? = playbackCacheKey(
        server = server,
        user = user,
        itemId = itemId,
        sourceId = source.id,
        contentValidator = source.contentValidator,
        sourceSize = source.size,
        mode = target.playMethod,
        routeIdentity = stablePlaybackRouteIdentity(target.url),
        audioIndex = target.trackIntent?.audioIndex,
        subtitleMode = target.trackIntent?.subtitleMode?.name,
        subtitleIndex = target.trackIntent?.requestedSubtitleIndex,
        startPositionMs = target.startPositionMs,
    )

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

/** Maintenance shares the instance lock and reports failed/partial deletes instead of claiming success. */
internal class PlaybackCacheStorage(private val directory: File, private val lock: Any) {
    fun spaceBytes(): Long = synchronized(lock) { diskBytes(directory) }

    private fun diskBytes(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> checkNotNull(file.listFiles()) { "Cannot inspect playback cache" }.sumOf(::diskBytes)
    }

    fun clear(release: () -> Unit, delete: (File) -> Unit): Result<Unit> = synchronized(lock) {
        runCatching {
            release()
            if (directory.exists()) {
                delete(directory)
                check(!directory.exists()) { "Playback cache was only partially cleared" }
            }
        }
    }
}
