package com.lumina.tv

import android.app.Application
import com.lumina.tv.diag.AppLog
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Process-wide image loader shared by every Compose surface. */
class LuminaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(filesDir, "pid=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT}")
        CrashHandler.install(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
}
