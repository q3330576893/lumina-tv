package com.lumina.tv.data

import com.lumina.tv.BuildConfig
import com.lumina.tv.diag.NetLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.roundToInt

object MdblistClient {

    private const val METADATA_CALL_TIMEOUT_MS = 8_000L
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

    suspend fun ratings(type: String, imdbId: String?, tmdbId: String?): Ratings? =
        withMetadataContext(METADATA_CALL_TIMEOUT_MS) {
        val endpointType = when (type) {
            "Movie" -> "movie"
            "Series" -> "show"
            else -> return@withMetadataContext null
        }
        val imdb = imdbId?.takeIf(String::isNotBlank)
        val tmdb = tmdbId?.takeIf(String::isNotBlank)
        val provider = if (imdb != null) "imdb" else "tmdb"
        val id = imdb ?: tmdb ?: return@withMetadataContext null
        val key = "$provider|$id|$endpointType"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { ratings ->
                if (now - (cacheTimes[key] ?: 0L) < SUCCESS_TTL_MS) return@withMetadataContext ratings
                cache.remove(key)
                cacheTimes.remove(key)
            }
            noHit[key]?.let {
                if (now - it < NO_HIT_TTL_MS) return@withMetadataContext null
                noHit.remove(key)
            }
        }
        val apiKey = BuildConfig.MDBLIST_API_KEY.takeIf(String::isNotBlank)
            ?: return@withMetadataContext null.also { rememberMiss(key, now) }
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val url = "https://api.mdblist.com/$provider/$endpointType/$encodedId/?apikey=$apiKey"
        val result = catchingSuspendRequest { getJson(url)?.let(::parseRatings) }.getOrNull()
        currentCoroutineContext().ensureActive()
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

    internal fun parseRatings(json: JSONObject): Ratings? {
        // Media Info returns source/value/score entries, not a map of rating objects.
        val ratings = json.optJSONArray("ratings") ?: return null
        val bySource = buildMap<String, JSONObject> {
            for (i in 0 until ratings.length()) {
                val entry = ratings.optJSONObject(i) ?: continue
                put(entry.optString("source").lowercase(Locale.ROOT), entry)
            }
        }
        fun number(raw: Any?): Double? {
            if (raw == null || raw == JSONObject.NULL) return null
            val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
            return value?.takeIf(Double::isFinite)
        }
        fun rating(name: String, scale: Double, vararg aliases: String): Double? {
            for (source in listOf(name) + aliases) {
                val entry = bySource[source] ?: continue
                number(entry.opt("value"))?.takeIf { it in 0.0..scale }?.let { return it }
                number(entry.opt("score"))?.takeIf { it in 0.0..100.0 }?.let { return it * scale / 100.0 }
            }
            return null
        }
        fun percent(name: String, vararg aliases: String): Int? = rating(name, 100.0, *aliases)?.roundToInt()
        val score = number(json.opt("score"))?.takeIf { it in 0.0..100.0 }?.roundToInt()
        return Ratings(
            imdb = rating("imdb", 10.0),
            tomato = percent("tomatoes", "tomato"),
            popcorn = percent("popcorn", "tomatoesaudience", "audience", "rtaudience"),
            metacritic = percent("metacritic"),
            trakt = percent("trakt"),
            tmdb = percent("tmdb"),
            // The Media Info schema uses a /10 value here (e.g. 8, score=80).
            letterboxd = rating("letterboxd", 10.0),
            score = score,
        ).takeIf { it.imdb != null || it.tomato != null || it.popcorn != null || it.metacritic != null ||
            it.trakt != null || it.tmdb != null || it.letterboxd != null || it.score != null }
    }

    private suspend fun getJson(url: String): JSONObject? {
        val started = System.currentTimeMillis()
        val request = try {
            Request.Builder().url(url).header("User-Agent", "LuminaTV/1.3").build()
        } catch (failure: Exception) {
            NetLog.add(started, "GET", url, System.currentTimeMillis() - started, null, failure.javaClass.simpleName)
            return null
        }
        return try {
            http.readCancellable(request) { response ->
                NetLog.add(started, request.method, url, System.currentTimeMillis() - started, response.code, if (response.isSuccessful) null else "HTTP")
                if (!response.isSuccessful) return@readCancellable null
                JSONObject(response.body?.string() ?: return@readCancellable null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            NetLog.add(started, "GET", url, System.currentTimeMillis() - started, null, failure.javaClass.simpleName)
            null
        }
    }
}
