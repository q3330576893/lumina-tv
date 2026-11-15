package com.lumina.tv.data

import com.lumina.tv.BuildConfig
import com.lumina.tv.diag.NetLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object MdblistClient {
    data class Ratings(
        val imdb: Double?,
        val tomato: Int?,
        val popcorn: Int?,
        val metacritic: Int?,
        val trakt: Int?,
        val tmdb: Int?,
        val letterboxd: Double?,
        val score: Int?,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private val cache = LinkedHashMap<String, Ratings>()
    private val cacheTimes = HashMap<String, Long>()
    private val noHit = LinkedHashMap<String, Long>()
    private const val SUCCESS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val NO_HIT_TTL_MS = 15L * 60 * 1000

    suspend fun ratings(type: String, imdbId: String?, tmdbId: String?): Ratings? = withContext(Dispatchers.IO) {
        val imdb = imdbId?.takeIf(String::isNotBlank)
        val tmdb = tmdbId?.takeIf(String::isNotBlank)
        val provider = if (imdb != null) "imdb" else "tmdb"
        val id = imdb ?: tmdb ?: return@withContext null
        val key = "$provider|$id|${if (type == "Series") "show" else "movie"}"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { ratings ->
                if (now - (cacheTimes[key] ?: 0L) < SUCCESS_TTL_MS) return@withContext ratings
                cache.remove(key)
                cacheTimes.remove(key)
            }
            noHit[key]?.let {
                if (now - it < NO_HIT_TTL_MS) return@withContext null
                noHit.remove(key)
            }
        }
        val apiKey = BuildConfig.MDBLIST_API_KEY.takeIf(String::isNotBlank)
            ?: return@withContext null.also { rememberMiss(key, now) }
        val endpointType = if (type == "Series") "show" else "movie"
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val url = "https://api.mdblist.com/$provider/$endpointType/$encodedId/?apikey=$apiKey"
        val result = runCatching { getJson(url)?.let(::parseRatings) }.getOrNull()
        synchronized(cache) {
            if (result == null) {
                noHit[key] = now
            } else {
                cache[key] = result
                cacheTimes[key] = System.currentTimeMillis()
            }
            if (cache.size > 300) {
                cache.clear()
                cacheTimes.clear()
            }
            if (noHit.size > 300) noHit.clear()
        }
        result
    }

    private fun rememberMiss(key: String, timestamp: Long) = synchronized(cache) {
        noHit[key] = timestamp
        if (noHit.size > 300) noHit.clear()
    }

    private fun parseRatings(json: JSONObject): Ratings {
        val ratings = json.optJSONObject("ratings")
        fun rating(name: String): Double? {
            val raw = ratings?.optJSONObject(name)?.opt("rating") ?: return null
            val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
            return value?.takeIf(Double::isFinite)
        }
        fun intRating(name: String): Int? = rating(name)?.toInt()
        val score = json.opt("score")?.let { raw ->
            ((raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull())?.takeIf(Double::isFinite)?.toInt()
        }
        return Ratings(
            imdb = rating("imdb"),
            tomato = intRating("tomato"),
            popcorn = intRating("popcorn"),
            metacritic = intRating("metacritic"),
            trakt = intRating("trakt"),
            tmdb = intRating("tmdb"),
            letterboxd = rating("letterboxd"),
            score = score,
        )
    }

    private fun getJson(url: String): JSONObject? {
        val started = System.currentTimeMillis()
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "LuminaTV/1.3").build()
            http.newCall(request).execute().use { response ->
                NetLog.add(started, request.method, url, System.currentTimeMillis() - started, response.code, if (response.isSuccessful) null else "HTTP")
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string() ?: return null)
            }
        }.onFailure {
            NetLog.add(started, "GET", url, System.currentTimeMillis() - started, null, it.javaClass.simpleName)
        }.getOrNull()
    }
}
