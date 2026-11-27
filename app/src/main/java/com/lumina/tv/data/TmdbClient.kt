package com.lumina.tv.data

import com.lumina.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.PageLog
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Language rank is independent of response order; ties have a stable path order. */
internal fun preferredTmdbLogoPath(logos: org.json.JSONArray): String? =
    (0 until logos.length()).mapNotNull { index ->
        val logo = logos.optJSONObject(index) ?: return@mapNotNull null
        val file = logo.optString("file_path", "").trim()
            .takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
        val language = logo.optString("iso_639_1", "").trim()
            .replace('_', '-').substringBefore('-').lowercase(java.util.Locale.ROOT)
        val rank = when (language) { "zh", "cn" -> 0; "en" -> 1; else -> 2 }
        rank to file
    }.minWithOrNull(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })?.second

internal fun tmdbExternalCacheIsFresh(cachedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    nowMs >= cachedAtMs && nowMs - cachedAtMs < ttlMs

/** Only top-level Movie/Series IDs belong to these TMDB namespaces. */
internal fun tmdbMediaPath(type: String): String? = when (type) {
    "Movie" -> "movie"
    "Series" -> "tv"
    else -> null
}

/** Episode IDs and air years must never be reused as their parent show's identity. */
internal suspend fun resolveTmdbMetadataItem(
    item: MediaItem,
    loadSeries: suspend (String) -> MediaItem?,
): MediaItem? {
    if (tmdbMediaPath(item.type) != null) return item
    if (item.type != "Episode") return null
    val seriesId = item.seriesId?.takeIf(String::isNotBlank) ?: return null
    return catchingRequest { loadSeries(seriesId) }.getOrNull()
        ?.takeIf { it.id == seriesId && it.type == "Series" }
}

/**
 * TMDB 元数据补充：评分 + 透明标题图（logo）。
 * 主用 api.tmdb.org（国内可达性更好），超时短，任何失败静默返回 null，不影响主流程。
 * 结果按「类型|名字|年份」进程内缓存，避免轮播反复请求。
 */
object TmdbClient {

    private const val METADATA_CALL_TIMEOUT_MS = 8_000L

    data class ExternalIds(val imdbId: String?, val tvdbId: String?)

    data class ExternalLinks(val imdbId: String?, val tmdbId: String?, val tvdbId: String?)

    data class TmdbInfo(
        val rating: Double?,
        val posterUrl: String?,
        val logoUrl: String? = null,
        val backdropUrl: String? = null,
    )


    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val lookupCache = NullableRequestCache<TmdbInfo>(missTtlMs = 60_000L, maxEntries = 200)
    private data class CachedValue<T>(val cachedAtMs: Long, val value: T)
    private val externalIdsCache = LinkedHashMap<String, CachedValue<ExternalIds>>()
    private val externalIdsNoHit = HashMap<String, Long>()
    private val externalLinksCache = LinkedHashMap<String, CachedValue<ExternalLinks>>()
    private val externalLinksNoHit = HashMap<String, Long>()
    @Volatile var lastRequestFailure: String? = null
        private set
    @Volatile private var externalLinkDiagnostic = JSONObject().put("status", "not_attempted").toString()

    private const val EXTERNAL_POSITIVE_CACHE_TTL_MS = 300_000L

    fun externalLinkDiagnosticSnapshot(): String = externalLinkDiagnostic

    /** Resolve missing provider IDs for a detail item without blocking its first render. */
    suspend fun externalLinks(
        type: String,
        title: String,
        year: Int?,
        embyImdbId: String?,
        embyTmdbId: String?,
        embyTvdbId: String?,
    ): ExternalLinks? = withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS) {
        if (tmdbMediaPath(type) == null) return@withThirdPartyMetadataContext null
        val key = "$type|$title|${year ?: 0}|${embyImdbId.orEmpty()}|${embyTmdbId.orEmpty()}|${embyTvdbId.orEmpty()}"
        synchronized(externalLinksCache) {
            val cached = externalLinksCache[key]
            if (cached != null) {
                if (tmdbExternalCacheIsFresh(cached.cachedAtMs, System.currentTimeMillis(), EXTERNAL_POSITIVE_CACHE_TTL_MS)) {
                    return@withThirdPartyMetadataContext cached.value
                }
                externalLinksCache.remove(key)
            }
            if ((externalLinksNoHit[key] ?: 0L) > System.currentTimeMillis()) return@withThirdPartyMetadataContext null
            externalLinksNoHit.remove(key)
        }

        val searchResult = if (embyTmdbId.isNullOrBlank()) searchTmdbId(type, title, year) else null
        val tmdbId = embyTmdbId?.takeIf(String::isNotBlank) ?: searchResult
        currentCoroutineContext().ensureActive()
        val external = tmdbId?.let { externalIds(type, it) }
        val result = ExternalLinks(
            imdbId = embyImdbId?.takeIf(String::isNotBlank) ?: external?.imdbId,
            tmdbId = tmdbId,
            tvdbId = embyTvdbId?.takeIf(String::isNotBlank) ?: external?.tvdbId,
        ).takeIf { it.imdbId != null || it.tmdbId != null || it.tvdbId != null }
        currentCoroutineContext().ensureActive()
        externalLinkDiagnostic = JSONObject()
            .put("title", title)
            .put("tmdbId", tmdbId ?: JSONObject.NULL)
            .put("tmdbSearch", when {
                !embyTmdbId.isNullOrBlank() -> "emby_provider_id"
                searchResult != null -> "matched"
                title.isBlank() -> "empty_title"
                else -> lastRequestFailure ?: "no_results"
            })
            .put("externalIds", when {
                !embyTmdbId.isNullOrBlank() && external == null -> lastRequestFailure ?: "no_external_ids"
                tmdbId != null && external != null -> "matched"
                tmdbId == null -> "not_requested"
                else -> lastRequestFailure ?: "no_external_ids"
            })
            .put("imdbId", result?.imdbId ?: JSONObject.NULL)
            .put("tvdbId", result?.tvdbId ?: JSONObject.NULL)
            .toString()
        synchronized(externalLinksCache) {
            if (result == null) externalLinksNoHit[key] = System.currentTimeMillis() + EXTERNAL_POSITIVE_CACHE_TTL_MS
            else externalLinksCache[key] = CachedValue(System.currentTimeMillis(), result)
            if (externalLinksCache.size > 200) externalLinksCache.clear()
            if (externalLinksNoHit.size > 200) externalLinksNoHit.clear()
        }
        result
    }

    private suspend fun searchTmdbId(type: String, title: String, year: Int?): String? {
        if (title.isBlank()) return null
        val path = tmdbMediaPath(type) ?: return null
        val isTv = path == "tv"
        val url = buildString {
            append("https://api.tmdb.org/3/search/").append(path)
            append("?query=").append(URLEncoder.encode(title, "UTF-8"))
            if (year != null && year in 1900..2100) {
                append("&").append(if (isTv) "first_air_date_year" else "year").append("=").append(year)
            }
            append("&api_key=").append(BuildConfig.TMDB_API_KEY)
        }
        val first = getJson(url)?.optJSONArray("results")?.optJSONObject(0) ?: return null
        return first.optLong("id", 0L).takeIf { it > 0 }?.toString()
    }

    /** Resolve IMDb and TVDB IDs from an Emby-provided TMDB ID without delaying detail rendering. */
    suspend fun externalIds(type: String, tmdbId: String): ExternalIds? = withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS) {
        val path = tmdbMediaPath(type) ?: return@withThirdPartyMetadataContext null
        val numericId = tmdbId.toLongOrNull()?.takeIf { it > 0 } ?: return@withThirdPartyMetadataContext null
        val key = "$path|$numericId"
        synchronized(externalIdsCache) {
            val cached = externalIdsCache[key]
            if (cached != null) {
                if (tmdbExternalCacheIsFresh(cached.cachedAtMs, System.currentTimeMillis(), EXTERNAL_POSITIVE_CACHE_TTL_MS)) {
                    return@withThirdPartyMetadataContext cached.value
                }
                externalIdsCache.remove(key)
            }
            if ((externalIdsNoHit[key] ?: 0L) > System.currentTimeMillis()) return@withThirdPartyMetadataContext null
            externalIdsNoHit.remove(key)
        }
        val result = getJson(
            "https://api.tmdb.org/3/$path/$numericId/external_ids?api_key=${BuildConfig.TMDB_API_KEY}"
        )?.let { json ->
            ExternalIds(
                imdbId = json.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" },
                tvdbId = json.optLong("tvdb_id", 0L).takeIf { it > 0 }?.toString(),
            )
        }?.takeIf { it.imdbId != null || it.tvdbId != null }
        currentCoroutineContext().ensureActive()
        synchronized(externalIdsCache) {
            if (result == null) externalIdsNoHit[key] = System.currentTimeMillis() + EXTERNAL_POSITIVE_CACHE_TTL_MS
            else externalIdsCache[key] = CachedValue(System.currentTimeMillis(), result)
            if (externalIdsCache.size > 200) externalIdsCache.clear()
            if (externalIdsNoHit.size > 200) externalIdsNoHit.clear()
        }
        result
    }

    /** 按名称(+年份)搜索，返回首个匹配；带进程内缓存。 */
    suspend fun lookup(type: String, name: String, year: Int?): TmdbInfo? = withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS) {
        if (tmdbMediaPath(type) == null) return@withThirdPartyMetadataContext null
        val key = "$type|$name|${year ?: 0}"
        lookupCache.getOrLoad(key) { catchingSuspendRequest { request(type, name, year) }.getOrNull() }
    }

    suspend fun lookupHero(type: String, name: String, year: Int?, originalTitle: String?): TmdbInfo? =
        withThirdPartyMetadataContext(METADATA_CALL_TIMEOUT_MS) {
        if (tmdbMediaPath(type) == null) return@withThirdPartyMetadataContext null
        val attempts = buildList {
            add(name to year)
            if (year != null) add(name to null)
            if (!originalTitle.isNullOrBlank() && originalTitle != name) {
                add(originalTitle to year)
                if (year != null) add(originalTitle to null)
            }
        }.distinct()
        var result: TmdbInfo? = null
        for ((query, queryYear) in attempts) {
            currentCoroutineContext().ensureActive()
            result = lookup(type, query, queryYear)
            if (result?.backdropUrl != null) break
        }
        currentCoroutineContext().ensureActive()
        PageLog.add("tmdbHero name=${name.take(48)} hit=${if (result != null) 1 else 0} backdrop=${if (result?.backdropUrl != null) 1 else 0}")
        result
    }

    private suspend fun request(type: String, name: String, year: Int?): TmdbInfo? {
        val path = tmdbMediaPath(type) ?: return null
        val isTv = path == "tv"
        val sb = StringBuilder("https://api.tmdb.org/3/search/").append(path)
            .append("?query=").append(URLEncoder.encode(name, "UTF-8"))
            .append("&language=zh-CN&api_key=").append(BuildConfig.TMDB_API_KEY)
        if (year != null && year in 1900..2100) {
            sb.append("&").append(if (isTv) "first_air_date_year" else "year").append("=").append(year)
        }
        val search = getJson(sb.toString()) ?: return null
        val arr = search.optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        val id = first.optInt("id", 0)
        val avg = first.optDouble("vote_average", 0.0)
        val poster = first.optString("poster_path", "")
        val backdrop = first.optString("backdrop_path", "")
        currentCoroutineContext().ensureActive()
        return TmdbInfo(
            rating = if (avg > 0) avg else null,
            posterUrl = if (poster.isNotBlank()) "https://image.tmdb.org/t/p/w500$poster" else null,
            logoUrl = if (id > 0) fetchLogo(path, id) else null,
            backdropUrl = if (backdrop.isNotBlank()) "https://image.tmdb.org/t/p/w1280$backdrop" else null,
        )
    }

    /** 取中文优先的透明标题图，其次英文，最后任意。 */
    private suspend fun fetchLogo(path: String, id: Int): String? {
        val url = "https://api.tmdb.org/3/$path/$id/images?api_key=${BuildConfig.TMDB_API_KEY}"
        val json = getJson(url) ?: return null
        val logos = json.optJSONArray("logos") ?: return null
        return preferredTmdbLogoPath(logos)?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private suspend fun getJson(url: String): JSONObject? {
        currentCoroutineContext().ensureActive()
        val started = System.currentTimeMillis()
        val req = try {
            Request.Builder().url(url).header("User-Agent", "LuminaTV/1.3").build()
        } catch (failure: Exception) {
            lastRequestFailure = "request_error:${failure.javaClass.simpleName}"
            NetLog.add(started, "GET", url, System.currentTimeMillis() - started, null, failure.javaClass.simpleName)
            return null
        }
        return try {
            http.readCancellable(req) { resp ->
                NetLog.add(started, req.method, url, System.currentTimeMillis() - started, resp.code, if (resp.isSuccessful) null else "HTTP")
                if (!resp.isSuccessful) {
                    lastRequestFailure = "http_${resp.code}"
                    return@readCancellable null
                }
                val body = resp.body?.string() ?: return@readCancellable null
                lastRequestFailure = null
                JSONObject(body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            lastRequestFailure = if (failure is java.net.SocketTimeoutException) "timeout" else "request_error:${failure.javaClass.simpleName}"
            NetLog.add(started, "GET", url, System.currentTimeMillis() - started, null, failure.javaClass.simpleName)
            null
        }
    }
}
