package com.lumina.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.lumina.tv.AppRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 进程内媒体库缓存：避免每次返回首页 / 详情页都重新拉全套数据。
 * 命中缓存立即渲染，过期后在后台静默刷新。
 */
object LibraryCache {

    var userDataRevision by mutableLongStateOf(0L)
        private set

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
    private const val SEASON_EPISODES_TTL = 3 * 60_000L
    private const val DISK_HOME_TTL = 24 * 60 * 60_000L
    private const val HOME_WRITE_INTERVAL = 30_000L

    @Volatile
    var home: HomeData? = null
        private set

    private val details = LinkedHashMap<String, DetailData>()
    private data class SeasonEpisodesEntry(val items: List<MediaItem>, val fetchedAt: Long)
    private val seasonEpisodes = HashMap<String, SeasonEpisodesEntry>()
    private val homeDisk = HomeCacheDiskStore(this, HOME_WRITE_INTERVAL,
        currentAccount = { AppRuntime.session?.let { identityScope(it.baseUrl, it.userId) } },
        currentRevision = { userDataRevision })

    private fun homeFile(context: Context, baseUrl: String, userId: String) =
        File(context.filesDir, "home_cache_${stableCacheDigest(baseUrl, userId)}.json")

    suspend fun readHome(context: Context, baseUrl: String, userId: String): HomeData? {
        val snapshot = synchronized(this) {
            homeDisk.snapshot(homeFile(context, baseUrl, userId), identityScope(baseUrl, userId), userDataRevision)
        } ?: return null
        return withContext(Dispatchers.IO) {
            homeDisk.read(snapshot, ::decodeHome)
        }
    }

    suspend fun writeHome(context: Context, baseUrl: String, userId: String, data: HomeData,
        revision: Long = userDataRevision) {
        val (snapshot, saved) = synchronized(this) {
            val ticket = homeDisk.snapshot(homeFile(context, baseUrl, userId), identityScope(baseUrl, userId),
                revision, forWrite = true) ?: return
            ticket to data.copy(resume = data.resume.toList(), latest = data.latest.toList(),
                movies = data.movies.toList(), series = data.series.toList(), favorites = data.favorites.toList(),
                genres = data.genres.toList(), views = data.views.toList())
        }
        withContext(Dispatchers.IO) {
            val requestContext = currentCoroutineContext()
            homeDisk.write(snapshot) {
                requestContext.ensureActive()
                encodeHome(saved)
            }
        }
    }

    fun removeHomeDisk(context: Context, baseUrl: String, userId: String) {
        homeDisk.invalidate(homeFile(context, baseUrl, userId))
    }

    internal fun encodeHome(saved: HomeData): String {
        val json = JSONObject().put("schemaVersion", CacheMediaItemCodec.SCHEMA_VERSION).put("savedAt", saved.at)
        listOf("resume" to saved.resume, "latest" to saved.latest, "movies" to saved.movies,
            "series" to saved.series, "favorites" to saved.favorites).forEach { (key, items) ->
            json.put(key, JSONArray().also { array -> items.forEach { array.put(it.toJson()) } })
        }
        json.put("genres", JSONArray(saved.genres))
        json.put("views", JSONArray().also { array -> saved.views.forEach { view ->
            array.put(JSONObject().put("id", view.id).put("name", view.name).put("type", view.type).putNullable("imageTag", view.imageTag))
        } })
        return json.toString()
    }

    internal fun decodeHome(text: String): HomeData? = try {
        val json = JSONObject(text)
        val version = json.opt("schemaVersion")
        // Future/invalid home schemas are cache misses; leave the file available to a compatible reader.
        if (json.has("schemaVersion") && version != CacheMediaItemCodec.SCHEMA_VERSION) null else {
            if (version != null) {
                listOf("resume", "latest", "movies", "series", "favorites", "genres", "views").forEach {
                    require(json.get(it) is JSONArray)
                }
            }
            val timestamp = json.get("savedAt")
            require(timestamp is Long || timestamp is Int)
            val savedAt = (timestamp as Number).toLong()
            if (savedAt <= 0L) null else {
                val cacheAt = if (System.currentTimeMillis() - savedAt > DISK_HOME_TTL) 0L else savedAt
                val genres = json.optJSONArray("genres")?.let { array ->
                    (0 until array.length()).map { array.get(it) as String }
                }.orEmpty()
                HomeData(
                    resume = json.readItems("resume"), latest = json.readItems("latest"),
                    movies = json.readItems("movies"), series = json.readItems("series"),
                    favorites = json.readItems("favorites"), genres = genres,
                    views = json.readViews(), at = cacheAt,
                )
            }
        }
    } catch (_: Exception) { null }

    private fun MediaItem.toJson() = CacheMediaItemCodec.encode(this)

    private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableString(key: String) = optString(key).takeUnless { it == "null" || it.isBlank() }

    private fun JSONObject.readItems(key: String): List<MediaItem> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(CacheMediaItemCodec::decode)?.item
        }
    }

    private fun JSONObject.readViews(): List<LibraryView> {
        val array = optJSONArray("views") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { j ->
            LibraryView(j.optString("id"), j.optString("name"), j.optString("type"), j.nullableString("imageTag"))
        }?.takeIf { it.id.isNotBlank() } }
    }

    fun putHome(data: HomeData, revision: Long = userDataRevision) {
        synchronized(this) { if (revision == userDataRevision) home = data }
    }

    fun detail(id: String, ttl: Long = DETAIL_TTL): DetailData? {
        val d = synchronized(this) { details[id] } ?: return null
        return if (System.currentTimeMillis() - d.at < ttl) d else null
    }

    fun detailStale(id: String): DetailData? = synchronized(this) { details[id] }

    fun putDetail(id: String, data: DetailData, revision: Long = userDataRevision) {
        synchronized(this) {
            if (revision != userDataRevision) return
            details[id] = data
            if (details.size > 12) {
                val firstKey = details.keys.firstOrNull()
                if (firstKey != null) details.remove(firstKey)
            }
        }
    }

    fun episodesOf(seriesId: String, seasonId: String?, nowMs: Long = System.currentTimeMillis()): List<MediaItem>? =
        synchronized(this) {
            seasonEpisodes["$seriesId|${seasonId ?: ""}"]
                ?.takeIf { nowMs >= it.fetchedAt && nowMs - it.fetchedAt < SEASON_EPISODES_TTL }
                ?.items
        }

    fun episodesStaleOf(seriesId: String, seasonId: String?): List<MediaItem>? =
        synchronized(this) { seasonEpisodes["$seriesId|${seasonId ?: ""}"]?.items }

    fun putEpisodes(seriesId: String, seasonId: String?, list: List<MediaItem>, revision: Long = userDataRevision,
        nowMs: Long = System.currentTimeMillis()) {
        synchronized(this) {
            if (revision != userDataRevision) return
            seasonEpisodes["$seriesId|${seasonId ?: ""}"] = SeasonEpisodesEntry(list, nowMs)
            if (seasonEpisodes.size > 24) {
                seasonEpisodes.keys.firstOrNull()?.let { seasonEpisodes.remove(it) }
            }
        }
    }

    // ---- 浏览页（电影/剧集/收藏/类型）短期缓存 ----
    private data class BrowseEntry(val items: List<MediaItem>, val at: Long, val ttlMs: Long, val fixedSample: Boolean)
    private val browse = LinkedHashMap<String, BrowseEntry>()

    fun browseOf(key: String, ttl: Long = 3 * 60_000L): List<MediaItem>? {
        val e = synchronized(this) { browse[key] } ?: return null
        val age = System.currentTimeMillis() - e.at
        return if ((e.fixedSample && age < 10 * 60_000L) ||
            (!e.fixedSample && age < minOf(ttl, e.ttlMs))) e.items else null
    }

    fun putBrowse(key: String, list: List<MediaItem>, revision: Long = userDataRevision, fixedSample: Boolean = false) {
        // 空结果只缓存 30 秒，避免一次超时导致长时间显示“无内容”
        val ttlMs = if (list.isEmpty()) 30_000L else 3 * 60_000L
        synchronized(this) {
            if (revision != userDataRevision) return
            if (fixedSample) browse.entries.removeAll { it.key != key && it.value.fixedSample }
            browse[key] = BrowseEntry(list, System.currentTimeMillis(), ttlMs, fixedSample)
            if (browse.size > 40) browse.entries.firstOrNull { !it.value.fixedSample }?.key?.let { browse.remove(it) }
        }
    }

    fun removeBrowse(key: String) {
        synchronized(this) { browse.remove(key) }
    }

    /** 收藏/已看变更后让缓存失效，避免返回首页看到旧状态。 */
    fun invalidateHome() {
        synchronized(this) { home = null }
    }

    /** Called after a confirmed mutation, including when its detail page has already closed. */
    fun invalidateUserData(context: Context, baseUrl: String, userId: String, itemId: String,
        favorite: Boolean? = null, played: Boolean? = null) {
        synchronized(this) {
            FavoritesCache.invalidate(favoritesQueryKey(baseUrl, userId), itemId, favorite, played)
            removeHomeDisk(context, baseUrl, userId)
            home = null
            details.clear()
            seasonEpisodes.clear()
            // Preserve the membership/order of an active random sample while updating its flags.
            val samples = browse.filterValues { it.fixedSample }.mapValues { (_, entry) ->
                entry.copy(items = entry.items.map { item ->
                    if (item.id == itemId) item.copy(isFavorite = favorite ?: item.isFavorite, played = played ?: item.played)
                    else item
                })
            }
            browse.clear()
            browse.putAll(samples)
            userDataRevision++
        }
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
            FavoritesCache.clear()
            userDataRevision++
            home = null
            details.clear()
            seasonEpisodes.clear()
            browse.clear()
            gridPositions.clear()
            homeDisk.invalidate()
        }
    }
}

/** Payload I/O runs outside [lock]; publication and invalidation share its short commit boundary. */
internal class HomeCacheDiskStore(
    private val lock: Any,
    private val writeIntervalMs: Long,
    private val currentAccount: () -> String?,
    private val currentRevision: () -> Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    internal data class Snapshot(val file: File, val account: String, val revision: Long,
        val generation: Long, val writeId: Long?)

    private var generation = 0L
    private var nextWriteId = 0L
    private val writes = HashMap<File, Long>()
    private val writeTimes = HashMap<File, Long>()

    fun snapshot(file: File, account: String?, revision: Long, forWrite: Boolean = false): Snapshot? = synchronized(lock) {
        if (account == null || account != currentAccount() || revision != currentRevision()) return@synchronized null
        val lastWrite = writeTimes[file]
        if (forWrite && lastWrite != null && nowMs() - lastWrite < writeIntervalMs) return@synchronized null
        val writeId = if (forWrite) (++nextWriteId).also { writes[file] = it } else null
        Snapshot(file, account, revision, generation, writeId)
    }

    private fun isCurrent(snapshot: Snapshot): Boolean = snapshot.generation == generation &&
        snapshot.account == currentAccount() && snapshot.revision == currentRevision() &&
        (snapshot.writeId == null || writes[snapshot.file] == snapshot.writeId)

    fun <T> read(snapshot: Snapshot, decode: (String) -> T): T? {
        val data = try {
            decode(snapshot.file.readText())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return synchronized(lock) { if (isCurrent(snapshot)) data else null }
    }

    fun write(snapshot: Snapshot, encode: () -> String): Boolean {
        var temp: File? = null
        return try {
            if (!synchronized(lock) { isCurrent(snapshot) && snapshot.writeId != null }) return false
            val payload = encode()
            val staged = File.createTempFile(snapshot.file.name, ".tmp", snapshot.file.parentFile)
            temp = staged
            staged.writeText(payload)
            synchronized(lock) {
                if (!isCurrent(snapshot)) return@synchronized false
                // Same-directory atomic replacement; never copy over the live file on failure.
                Files.move(staged.toPath(), snapshot.file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                writeTimes[snapshot.file] = nowMs()
                true
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            runCatching { temp?.delete() }
        }
    }

    fun invalidate(file: File? = null) = synchronized(lock) {
        generation++
        if (file == null) {
            writes.clear()
            writeTimes.clear()
        } else {
            writes.remove(file)
            writeTimes.remove(file)
            runCatching { file.delete() }
        }
        Unit
    }
}
