package com.lumina.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 进程内媒体库缓存：避免每次返回首页 / 详情页都重新拉全套数据。
 * 命中缓存立即渲染，过期后在后台静默刷新。
 */
object LibraryCache {

    data class HomeData(
        val resume: List<MediaItem>,
        val latest: List<MediaItem>,
        val movies: List<MediaItem>,
        val series: List<MediaItem>,
        val favorites: List<MediaItem>,
        val genres: List<String>,
        val views: List<LibraryView> = emptyList(),
        val at: Long,
    )

    data class DetailData(
        val item: MediaItem?,
        val seasons: List<MediaItem>,
        val episodes: List<MediaItem>,
        val sources: List<MediaSource>,
        val at: Long,
    )

    private const val DETAIL_TTL = 3 * 60_000L
    private const val DISK_HOME_TTL = 24 * 60 * 60_000L
    private const val HOME_WRITE_INTERVAL = 30_000L

    @Volatile
    var home: HomeData? = null
        private set

    private val details = LinkedHashMap<String, DetailData>()
    private val seasonEpisodes = HashMap<String, List<MediaItem>>()
    private val homeWriteTimes = HashMap<String, Long>()

    private fun homeFile(context: Context, baseUrl: String, userId: String) =
        File(context.filesDir, "home_cache_${baseUrl.hashCode()}_$userId.json")

    suspend fun readHome(context: Context, baseUrl: String, userId: String): HomeData? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(homeFile(context, baseUrl, userId).readText())
            val savedAt = json.optLong("savedAt", 0L)
            if (savedAt <= 0L) return@runCatching null
            val cacheAt = if (System.currentTimeMillis() - savedAt > DISK_HOME_TTL) 0L else savedAt
            HomeData(
                resume = json.readItems("resume"), latest = json.readItems("latest"),
                movies = json.readItems("movies"), series = json.readItems("series"),
                favorites = json.readItems("favorites"), genres = emptyList(),
                views = json.readViews(), at = cacheAt,
            )
        }.getOrNull()?.also { putHome(it) }
    }

    suspend fun writeHome(context: Context, baseUrl: String, userId: String, data: HomeData) = withContext(Dispatchers.IO) {
        val file = homeFile(context, baseUrl, userId)
        synchronized(homeWriteTimes) {
            val now = System.currentTimeMillis()
            if (now - (homeWriteTimes[file.name] ?: 0L) < HOME_WRITE_INTERVAL) return@withContext
            homeWriteTimes[file.name] = now
        }
        runCatching {
            val json = JSONObject().put("savedAt", data.at)
            listOf("resume" to data.resume, "latest" to data.latest, "movies" to data.movies,
                "series" to data.series, "favorites" to data.favorites).forEach { (key, items) ->
                json.put(key, JSONArray().also { array -> items.forEach { array.put(it.toJson()) } })
            }
            json.put("views", JSONArray().also { array -> data.views.forEach { view ->
                array.put(JSONObject().put("id", view.id).put("name", view.name).put("type", view.type).putNullable("imageTag", view.imageTag))
            } })
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun removeHomeDisk(context: Context, baseUrl: String, userId: String) {
        runCatching { homeFile(context, baseUrl, userId).delete() }
    }

    private fun MediaItem.toJson() = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .putNullable("imageTag", imageTag).putNullable("overview", overview).putNullable("year", year)
        .putNullable("rating", rating).putNullable("runtimeTicks", runtimeTicks).putNullable("seriesId", seriesId)
        .putNullable("seasonId", seasonId).putNullable("indexNumber", indexNumber).putNullable("parentIndexNumber", parentIndexNumber)
        .putNullable("seriesName", seriesName).put("positionTicks", positionTicks).put("played", played)
        .put("playedPercentage", playedPercentage).putNullable("primaryImageAspectRatio", primaryImageAspectRatio)
        .putNullable("backdropTag", backdropTag).putNullable("logoTag", logoTag).put("sourceCount", sourceCount)
        .putNullable("communityRating", communityRating).put("genres", JSONArray(genres)).put("isFavorite", isFavorite)
        .putNullable("resolution", resolution).put("isHdr", isHdr).put("isDolbyVision", isDolbyVision)
        .putNullable("audioFormat", audioFormat)
        .put("people", JSONArray().also { array -> people.forEach { p -> array.put(JSONObject()
            .putNullable("id", p.id).put("name", p.name).putNullable("role", p.role)
            .putNullable("type", p.type).putNullable("imageTag", p.imageTag)) } })
        .put("providerIds", JSONObject(providerIds))

    private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableString(key: String) = optString(key).takeUnless { it == "null" || it.isBlank() }

    private fun JSONObject.readItems(key: String): List<MediaItem> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { j ->
            fun <T> optional(key: String, value: T): T? = if (j.has(key) && !j.isNull(key)) value else null
            val people = j.optJSONArray("people") ?: JSONArray()
            MediaItem(
                id = j.optString("id"), name = j.optString("name"), type = j.optString("type"),
                imageTag = j.nullableString("imageTag"), overview = j.nullableString("overview"),
                year = optional("year", j.optInt("year")), rating = optional("rating", j.optDouble("rating")),
                runtimeTicks = optional("runtimeTicks", j.optLong("runtimeTicks")), seriesId = j.nullableString("seriesId"),
                seasonId = j.nullableString("seasonId"), indexNumber = optional("indexNumber", j.optInt("indexNumber")),
                parentIndexNumber = optional("parentIndexNumber", j.optInt("parentIndexNumber")), seriesName = j.nullableString("seriesName"),
                positionTicks = j.optLong("positionTicks"), played = j.optBoolean("played"), playedPercentage = j.optDouble("playedPercentage"),
                primaryImageAspectRatio = optional("primaryImageAspectRatio", j.optDouble("primaryImageAspectRatio")),
                backdropTag = j.nullableString("backdropTag"), logoTag = j.nullableString("logoTag"), sourceCount = j.optInt("sourceCount"),
                communityRating = optional("communityRating", j.optDouble("communityRating")),
                genres = j.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                isFavorite = j.optBoolean("isFavorite"), resolution = j.nullableString("resolution"), isHdr = j.optBoolean("isHdr"),
                isDolbyVision = j.optBoolean("isDolbyVision"), audioFormat = j.nullableString("audioFormat"),
                people = (0 until people.length()).mapNotNull { n -> people.optJSONObject(n)?.let { p -> MediaPerson(
                    p.nullableString("id"), p.optString("name"), p.nullableString("role"), p.nullableString("type"), p.nullableString("imageTag")) } },
                providerIds = j.optJSONObject("providerIds")?.let { ids -> ids.keys().asSequence().associateWith { ids.optString(it) } }.orEmpty(),
            ).takeIf { it.id.isNotBlank() }
        } }
    }

    private fun JSONObject.readViews(): List<LibraryView> {
        val array = optJSONArray("views") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { j ->
            LibraryView(j.optString("id"), j.optString("name"), j.optString("type"), j.nullableString("imageTag"))
        }?.takeIf { it.id.isNotBlank() } }
    }

    fun putHome(data: HomeData) {
        synchronized(this) { home = data }
    }

    fun detail(id: String, ttl: Long = DETAIL_TTL): DetailData? {
        val d = synchronized(this) { details[id] } ?: return null
        return if (System.currentTimeMillis() - d.at < ttl) d else null
    }

    fun detailStale(id: String): DetailData? = synchronized(this) { details[id] }

    fun putDetail(id: String, data: DetailData) {
        synchronized(this) {
            details[id] = data
            if (details.size > 12) {
                val firstKey = details.keys.firstOrNull()
                if (firstKey != null) details.remove(firstKey)
            }
        }
    }

    fun episodesOf(seriesId: String, seasonId: String?): List<MediaItem>? =
        synchronized(this) { seasonEpisodes["$seriesId|${seasonId ?: ""}"] }

    fun putEpisodes(seriesId: String, seasonId: String?, list: List<MediaItem>) {
        synchronized(this) {
            seasonEpisodes["$seriesId|${seasonId ?: ""}"] = list
            if (seasonEpisodes.size > 24) {
                seasonEpisodes.keys.firstOrNull()?.let { seasonEpisodes.remove(it) }
            }
        }
    }

    // ---- 浏览页（电影/剧集/收藏/类型）短期缓存 ----
    private data class BrowseEntry(val items: List<MediaItem>, val at: Long, val ttlMs: Long)
    private val browse = LinkedHashMap<String, BrowseEntry>()

    fun browseOf(key: String, ttl: Long = 3 * 60_000L): List<MediaItem>? {
        val e = synchronized(this) { browse[key] } ?: return null
        return if (System.currentTimeMillis() - e.at < minOf(ttl, e.ttlMs)) e.items else null
    }

    fun putBrowse(key: String, list: List<MediaItem>) {
        // 空结果只缓存 30 秒，避免一次超时导致长时间显示“无内容”
        val ttlMs = if (list.isEmpty()) 30_000L else 3 * 60_000L
        synchronized(this) {
            browse[key] = BrowseEntry(list, System.currentTimeMillis(), ttlMs)
            if (browse.size > 40) browse.keys.firstOrNull()?.let { browse.remove(it) }
        }
    }

    /** 收藏/已看变更后让缓存失效，避免返回首页看到旧状态。 */
    fun invalidateHome() {
        synchronized(this) { home = null }
    }

    // ---- 媒体库网格滚动位置：index / 像素偏移 / 当时已加载条目数 ----
    // 只放内存即可满足"点进影片再退出回到原位"；进程重启后从头开始是可接受的。
    private val gridPositions = HashMap<String, Triple<Int, Int, Int>>()

    fun gridPosition(key: String): Triple<Int, Int, Int>? = synchronized(this) { gridPositions[key] }

    fun putGridPosition(key: String, index: Int, offset: Int, loaded: Int) {
        synchronized(this) { gridPositions[key] = Triple(index, offset, loaded) }
    }

    fun clearGridPosition(key: String) {
        synchronized(this) { gridPositions.remove(key) }
    }

    fun invalidateDetail(id: String) {
        synchronized(this) { details.remove(id) }
    }

    fun clear() {
        synchronized(this) {
            home = null
            details.clear()
            seasonEpisodes.clear()
            browse.clear()
            gridPositions.clear()
            homeWriteTimes.clear()
        }
    }
}
