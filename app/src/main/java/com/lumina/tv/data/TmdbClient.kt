package com.lumina.tv.data

import com.lumina.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * TMDB 元数据补充：评分 + 透明标题图（logo）。
 * 主用 api.tmdb.org（国内可达性更好），超时短，任何失败静默返回 null，不影响主流程。
 * 结果按「类型|名字|年份」进程内缓存，避免轮播反复请求。
 */
object TmdbClient {

    data class ExternalIds(val imdbId: String?, val tvdbId: String?)

    data class ExternalLinks(val imdbId: String?, val tmdbId: String?, val tvdbId: String?)

    data class TmdbInfo(
        val rating: Double?,
        val posterUrl: String?,
        val logoUrl: String? = null,
    )

    private data class DiscoveryCacheEntry(val expiresAt: Long, val cards: List<ExternalCard>)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = LinkedHashMap<String, TmdbInfo?>()
    private val noHit = HashSet<String>()
    private val externalIdsCache = LinkedHashMap<String, ExternalIds?>()
    private val externalIdsNoHit = HashSet<String>()
    private val externalLinksCache = LinkedHashMap<String, ExternalLinks>()
    private val externalLinksNoHit = HashSet<String>()
    private val discoveryCache = LinkedHashMap<String, DiscoveryCacheEntry>()
    private val discoveryLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile var lastRequestFailure: String? = null
        private set
    @Volatile private var externalLinkDiagnostic = JSONObject().put("status", "not_attempted").toString()

    fun externalLinkDiagnosticSnapshot(): String = externalLinkDiagnostic

    suspend fun discovery(config: DiscoverySectionConfig): List<ExternalCard> {
        lastRequestFailure = null
        return runCatching {
        withContext(Dispatchers.IO) {
            discoveryLocks.getOrPut(config.id) { Mutex() }.withLock {
            require(config.sourceId == "tmdb") { "Unsupported discovery source: ${config.sourceId}" }
            val now = System.currentTimeMillis()
            synchronized(discoveryCache) {
                discoveryCache[config.id]?.takeIf { it.expiresAt > now }?.let { return@withLock it.cards }
                discoveryCache.remove(config.id)
            }

            val path = config.endpoint.trimStart('/')
            val url = "https://api.themoviedb.org/3/$path" +
                "?language=zh-CN&region=CN&page=1&include_adult=false" +
                "&sort_by=${URLEncoder.encode(config.sortBy, "UTF-8")}" +
                "&api_key=${BuildConfig.TMDB_API_KEY}"
            val response = getJson(url)
                ?: throw java.io.IOException("TMDB discovery request failed: ${lastRequestFailure ?: "unknown"}")
            val results = response.optJSONArray("results") ?: JSONArray()
            val cards = buildList {
                for (index in 0 until minOf(results.length(), config.pageSize.coerceAtLeast(0))) {
                    val result = results.optJSONObject(index) ?: continue
                    val id = result.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                    val title = result.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val releaseDate = result.optString("release_date")
                    val year = releaseDate.take(4).toIntOrNull()
                    val posterPath = result.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    val backdropPath = result.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                    val rating = result.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                    add(
                        ExternalCard(
                            tmdbId = id,
                            title = title,
                            year = year,
                            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                            backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                            rating = rating,
                        )
                    )
                }
            }
            synchronized(discoveryCache) {
                discoveryCache[config.id] = DiscoveryCacheEntry(
                    expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L,
                    cards = cards,
                )
            }
            cards
            }
        }
    }.onFailure {
        lastRequestFailure = when (it) {
            is java.net.SocketTimeoutException -> "timeout"
                else -> lastRequestFailure ?: "discovery_error:${it.javaClass.simpleName}"
        }
    }.getOrDefault(emptyList())
    }

    /** Resolve missing provider IDs for a detail item without blocking its first render. */
    suspend fun externalLinks(
        type: String,
        title: String,
        year: Int?,
        embyImdbId: String?,
        embyTmdbId: String?,
        embyTvdbId: String?,
    ): ExternalLinks? = withContext(Dispatchers.IO) {
        val key = "$type|$title|${year ?: 0}|${embyImdbId.orEmpty()}|${embyTmdbId.orEmpty()}|${embyTvdbId.orEmpty()}"
        synchronized(externalLinksCache) {
            externalLinksCache[key]?.let { return@withContext it }
            if (key in externalLinksNoHit) return@withContext null
        }

        val searchResult = if (embyTmdbId.isNullOrBlank()) searchTmdbId(type, title, year) else null
        val tmdbId = embyTmdbId?.takeIf(String::isNotBlank) ?: searchResult
        val external = tmdbId?.let { externalIds(type, it) }
        val result = ExternalLinks(
            imdbId = embyImdbId?.takeIf(String::isNotBlank) ?: external?.imdbId,
            tmdbId = tmdbId,
            tvdbId = embyTvdbId?.takeIf(String::isNotBlank) ?: external?.tvdbId,
        ).takeIf { it.imdbId != null || it.tmdbId != null || it.tvdbId != null }
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
            if (result == null) externalLinksNoHit.add(key) else externalLinksCache[key] = result
            if (externalLinksCache.size > 200) externalLinksCache.clear()
            if (externalLinksNoHit.size > 200) externalLinksNoHit.clear()
        }
        result
    }

    private fun searchTmdbId(type: String, title: String, year: Int?): String? {
        if (title.isBlank()) return null
        val isTv = type == "Series"
        val path = if (isTv) "tv" else "movie"
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
    suspend fun externalIds(type: String, tmdbId: String): ExternalIds? = withContext(Dispatchers.IO) {
        val numericId = tmdbId.toLongOrNull()?.takeIf { it > 0 } ?: return@withContext null
        val path = if (type == "Series") "tv" else "movie"
        val key = "$path|$numericId"
        synchronized(externalIdsCache) {
            if (externalIdsCache.containsKey(key)) return@withContext externalIdsCache[key]
            if (externalIdsNoHit.contains(key)) return@withContext null
        }
        val result = getJson(
            "https://api.tmdb.org/3/$path/$numericId/external_ids?api_key=${BuildConfig.TMDB_API_KEY}"
        )?.let { json ->
            ExternalIds(
                imdbId = json.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" },
                tvdbId = json.optLong("tvdb_id", 0L).takeIf { it > 0 }?.toString(),
            )
        }?.takeIf { it.imdbId != null || it.tvdbId != null }
        synchronized(externalIdsCache) {
            if (result == null) externalIdsNoHit.add(key) else externalIdsCache[key] = result
            if (externalIdsCache.size > 200) externalIdsCache.clear()
            if (externalIdsNoHit.size > 200) externalIdsNoHit.clear()
        }
        result
    }

    /** 按名称(+年份)搜索，返回首个匹配；带进程内缓存。 */
    suspend fun lookup(type: String, name: String, year: Int?): TmdbInfo? = withContext(Dispatchers.IO) {
        val key = "$type|$name|${year ?: 0}"
        synchronized(cache) {
            if (cache.containsKey(key)) return@withContext cache[key]
            if (noHit.contains(key)) return@withContext null
        }
        val result = runCatching { request(type, name, year) }.getOrNull()
        synchronized(cache) {
            if (result == null) noHit.add(key) else cache[key] = result
            if (cache.size > 200) cache.clear()
            if (noHit.size > 200) noHit.clear()
        }
        result
    }

    private fun request(type: String, name: String, year: Int?): TmdbInfo? {
        val isTv = type == "Series"
        val path = if (isTv) "tv" else "movie"
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
        return TmdbInfo(
            rating = if (avg > 0) avg else null,
            posterUrl = if (poster.isNotBlank()) "https://image.tmdb.org/t/p/w500$poster" else null,
            logoUrl = if (id > 0) fetchLogo(path, id) else null,
        )
    }

    /** 取中文优先的透明标题图，其次英文，最后任意。 */
    private fun fetchLogo(path: String, id: Int): String? {
        val url = "https://api.tmdb.org/3/$path/$id/images?include_image_language=zh,en,null&api_key=${BuildConfig.TMDB_API_KEY}"
        val json = getJson(url) ?: return null
        val logos = json.optJSONArray("logos") ?: return null
        if (logos.length() == 0) return null
        var fallback: String? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            val file = logo.optString("file_path", "")
            if (file.isBlank()) continue
            if (fallback == null) fallback = file
            val lang = logo.optString("iso_639_1", "")
            if (lang == "zh" || lang == "cn") return "https://image.tmdb.org/t/p/w500$file"
        }
        return fallback?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "LuminaTV/1.3").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastRequestFailure = "http_${resp.code}"
                return null
            }
            val body = resp.body?.string() ?: return null
            lastRequestFailure = null
            JSONObject(body)
        }
    }.onFailure { lastRequestFailure = if (it is java.net.SocketTimeoutException) "timeout" else "request_error:${it.javaClass.simpleName}" }
        .getOrNull()
}
