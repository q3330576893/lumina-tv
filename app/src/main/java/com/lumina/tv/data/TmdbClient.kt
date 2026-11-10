package com.lumina.tv.data

import com.lumina.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMDB 元数据补充：评分 + 透明标题图（logo）。
 * 主用 api.tmdb.org（国内可达性更好），超时短，任何失败静默返回 null，不影响主流程。
 * 结果按「类型|名字|年份」进程内缓存，避免轮播反复请求。
 */
object TmdbClient {

    data class ExternalIds(val imdbId: String?, val tvdbId: String?)

    data class TmdbInfo(
        val rating: Double?,
        val posterUrl: String?,
        val logoUrl: String? = null,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cache = LinkedHashMap<String, TmdbInfo?>()
    private val noHit = HashSet<String>()
    private val externalIdsCache = LinkedHashMap<String, ExternalIds?>()
    private val externalIdsNoHit = HashSet<String>()

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
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            JSONObject(body)
        }
    }.getOrNull()
}
