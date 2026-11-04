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
 * TMDB 元数据补充（评分等）。
 * 主用 api.tmdb.org（国内可达性更好），超时短，任何失败静默返回 null，不影响主流程。
 */
object TmdbClient {

    data class TmdbInfo(
        val rating: Double?,
        val posterUrl: String?,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 按名称(+年份)搜索，返回首个匹配。 */
    suspend fun lookup(type: String, name: String, year: Int?): TmdbInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val isTv = type == "Series"
            val path = if (isTv) "tv" else "movie"
            val sb = StringBuilder("https://api.tmdb.org/3/search/").append(path)
                .append("?query=").append(URLEncoder.encode(name, "UTF-8"))
                .append("&language=zh-CN&api_key=").append(BuildConfig.TMDB_API_KEY)
            if (year != null && year in 1900..2100) {
                sb.append("&").append(if (isTv) "first_air_date_year" else "year").append("=").append(year)
            }
            val req = Request.Builder()
                .url(sb.toString())
                .header("User-Agent", "LuminaTV/1.3")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val arr = JSONObject(body).optJSONArray("results") ?: return@runCatching null
                if (arr.length() == 0) return@runCatching null
                val first = arr.optJSONObject(0) ?: return@runCatching null
                val avg = first.optDouble("vote_average", 0.0)
                val poster = first.optString("poster_path", "")
                TmdbInfo(
                    rating = if (avg > 0) avg else null,
                    posterUrl = if (poster.isNotBlank()) "https://image.tmdb.org/t/p/w500$poster" else null,
                )
            }
        }.getOrNull()
    }
}
