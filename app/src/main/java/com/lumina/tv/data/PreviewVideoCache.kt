package com.lumina.tv.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object PreviewVideoCache {
    private const val MAX_CACHE_BYTES = 300L * 1024L * 1024L

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, "preview_video"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    fun remove(context: Context, key: String) {
        runCatching { get(context).removeResource(key) }
    }
}
