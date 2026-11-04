package com.lumina.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class EmbyException(message: String) : Exception(message)

/** Emby REST 客户端。所有方法都是挂起函数，网络在 IO 线程执行。 */
class EmbyClient(
    var baseUrl: String,
    var token: String = "",
    var userId: String = "",
    private val deviceId: String = "lumina-tv",
    val userName: String = "",
    val serverName: String = "Emby",
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun authHeader(withToken: Boolean = true): String {
        val sb = StringBuilder()
            .append("MediaBrowser Client=\"LuminaTV\", Device=\"Android TV\", DeviceId=\"")
            .append(deviceId).append("\", Version=\"1.0.0\"")
        if (withToken && token.isNotEmpty()) sb.append(", Token=\"").append(token).append("\"")
        return sb.toString()
    }

    private fun url(path: String, params: Map<String, String?> = emptyMap()): String {
        val sb = StringBuilder(normalize(baseUrl)).append(path)
        val q = StringBuilder()
        params.forEach { (k, v) -> if (v != null) { if (q.isNotEmpty()) q.append('&'); q.append(k).append('=').append(enc(v)) } }
        if (token.isNotEmpty()) { if (q.isNotEmpty()) q.append('&'); q.append("api_key=").append(enc(token)) }
        if (q.isNotEmpty()) sb.append('?').append(q)
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getSync(url: String, withToken: Boolean = true): JSONObject {
        val req = Request.Builder().url(url).header("X-Emby-Authorization", authHeader(withToken)).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}: ${body.take(200)}")
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun postSync(url: String, body: String?): JSONObject {
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader())
            .post((body ?: "{}").toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}: ${text.take(200)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    // ---------------- 认证 ----------------

    suspend fun login(server: String, user: String, pass: String): Session = withContext(Dispatchers.IO) {
        baseUrl = normalize(server)
        token = ""
        val body = JSONObject().put("Username", user).put("Pw", pass).toString()
        val resp = postSync(url("/Users/AuthenticateByName", emptyMap()), body)
        val u = resp.optJSONObject("User")
            ?: throw EmbyException("服务器返回格式异常，请确认地址是 Emby 服务")
        token = resp.optString("AccessToken")
        userId = u.optString("Id")
        if (token.isBlank() || userId.isBlank()) throw EmbyException("账号或密码不正确")
        Session(
            baseUrl = baseUrl,
            token = token,
            userId = userId,
            userName = u.optString("Name", user),
            serverName = getServerNameSafe(),
        )
    }

    private fun getServerNameSafe(): String = try {
        val info = getSync(url("/System/Info/Public"))
        info.optString("ServerName", "Emby")
    } catch (_: Exception) { "Emby" }

    // ---------------- 内容 ----------------

    suspend fun getViews(): List<LibraryView> = withContext(Dispatchers.IO) {
        val arr = getSync(url("/Users/$userId/Views")).optJSONArray("Items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LibraryView(
                id = o.optString("Id"),
                name = o.optString("Name"),
                type = o.optString("CollectionType", "movies"),
                imageTag = o.optString("ImageTags.Primary").ifBlank { null },
            )
        }.filter { it.id.isNotBlank() }
    }

    suspend fun getFavorites(limit: Int = 24): List<MediaItem> = withContext(Dispatchers.IO) {
        getItems(types = "Movie,Series,Episode", sortBy = "DateCreated", sortOrder = "Descending", limit = limit, filters = "IsFavorite")
    }

    /** 全部类型（题材）名称。多级兜底：/Genres → /Items/Filters → 本地汇总。 */
    suspend fun getGenres(): List<String> = withContext(Dispatchers.IO) {
        // 1) 标准 /Genres
        val direct = runCatching {
            val params = mapOf(
                "UserId" to userId,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "SortBy" to "SortName",
                "SortOrder" to "Ascending",
            )
            val arr = getSync(url("/Genres", params)).optJSONArray("Items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("Name")?.ifBlank { null }
            }
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return@withContext direct

        // 2) Emby 常用 /Items/Filters（返回字符串数组）
        val viaFilters = runCatching {
            val j = getSync(
                url("/Items/Filters", mapOf("UserId" to userId, "IncludeItemTypes" to "Movie,Series"))
            )
            val arr = j.optJSONArray("Genres") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.getOrDefault(emptyList())
        if (viaFilters.isNotEmpty()) return@withContext viaFilters

        // 3) 从库里直接汇总（最慢但最兼容）
        runCatching {
            val set = LinkedHashSet<String>()
            listOf("Movie", "Series").forEach { t ->
                getItems(types = t, sortBy = "DateCreated", sortOrder = "Descending", limit = 300)
                    .forEach { item -> set.addAll(item.genres) }
            }
            set.toList().sorted()
        }.getOrDefault(emptyList())
    }

    /** 按类型取影片（网格浏览用）。 */
    suspend fun getByGenre(genre: String, limit: Int = 120, startIndex: Int = 0): List<MediaItem> =
        withContext(Dispatchers.IO) {
            getItems(
                types = "Movie,Series",
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = limit,
                startIndex = startIndex,
                genre = genre,
            )
        }

    suspend fun getItems(
        parentId: String? = null,
        types: String? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 40,
        startIndex: Int = 0,
        searchTerm: String? = null,
        genre: String? = null,
        filters: String? = null,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "Recursive" to "true",
            "Limit" to limit.toString(),
            "StartIndex" to startIndex.toString(),
            "SortBy" to sortBy,
            "SortOrder" to sortOrder,
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
            "ImageTypeLimit" to "1",
            "EnableImageTypes" to "Primary,Backdrop,Thumb",
        )
        if (parentId != null) params["ParentId"] = parentId
        if (types != null) params["IncludeItemTypes"] = types
        if (searchTerm != null) params["SearchTerm"] = searchTerm
        if (genre != null) params["Genres"] = genre
        if (filters != null) params["Filters"] = filters
        val arr = getSync(url("/Users/$userId/Items", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getResume(limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "Limit" to limit.toString(),
            "MediaTypes" to "Video",
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,ProductionYear",
            "EnableImageTypes" to "Primary,Thumb,Backdrop",
        )
        val arr = getSync(url("/Users/$userId/Items/Resume", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getLatest(parentId: String?, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "Limit" to limit.toString(),
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,ProductionYear",
        )
        if (parentId != null) params["ParentId"] = parentId
        val arr = getSync(url("/Users/$userId/Items/Latest", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getItem(id: String): MediaItem? = withContext(Dispatchers.IO) {
        try {
            parseItem(getSync(url("/Users/$userId/Items/$id")))
        } catch (_: Exception) { null }
    }

    suspend fun getSeasons(seriesId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mapOf("UserId" to userId, "Fields" to "Overview,PrimaryImageAspectRatio,UserData")
        val arr = getSync(url("/Shows/$seriesId/Seasons", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String?): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "UserId" to userId,
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData",
        )
        if (seasonId != null) params["SeasonId"] = seasonId
        val arr = getSync(url("/Shows/$seriesId/Episodes", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    private fun parseItems(arr: JSONArray): List<MediaItem> =
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseItem(it) } }

    private fun parseItem(o: JSONObject): MediaItem {
        val ud = o.optJSONObject("UserData")
        val tags = o.optJSONObject("ImageTags")
        val backdrops = o.optJSONObject("BackdropImageTags")
        val streams = o.optJSONArray("MediaStreams")
        var w = 0
        var h = 0
        var isHdr = false
        var isDv = false
        var audioChannels: String? = null
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val type = s.optString("Type")
                if (type == "Video") {
                    w = s.optInt("Width", w)
                    h = s.optInt("Height", h)
                    val vr = s.optString("VideoRange").uppercase()
                    val title = s.optString("Title").uppercase()
                    val profile = s.optString("Profile").uppercase()
                    if ("DOVI" in vr || "DOLBY" in vr || "DV" in profile) isDv = true
                    if ("HDR" in vr || "HDR10" in vr || "HLG" in vr || "HDR" in title) isHdr = true
                } else if (type == "Audio" && audioChannels == null) {
                    val ch = s.optInt("Channels", 0)
                    val layout = s.optString("ChannelLayout")
                    val codec = s.optString("Codec").uppercase()
                    audioChannels = when {
                        "ATMOS" in codec || "ATMOS" in s.optString("Title").uppercase() -> "ATMOS"
                        ch >= 8 -> "7.1"
                        ch >= 6 || "5.1" in layout -> "5.1"
                        ch >= 2 -> "2.0"
                        else -> null
                    }
                }
            }
        }
        val res = when {
            w >= 3800 || h >= 2100 -> "4K"
            w >= 1900 || h >= 1000 -> "1080P"
            w >= 1200 || h >= 700 -> "720P"
            else -> null
        }
        return MediaItem(
            id = o.optString("Id"),
            name = o.optString("Name"),
            type = o.optString("Type"),
            imageTag = tags?.optString("Primary")?.ifBlank { null },
            backdropTag = backdrops?.optString("0")?.ifBlank { null },
            overview = o.optString("Overview").ifBlank { null },
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            runtimeTicks = o.optLong("RunTimeTicks", 0L).takeIf { it > 0 },
            seriesId = o.optString("SeriesId").ifBlank { null },
            seasonId = o.optString("SeasonId").ifBlank { null },
            indexNumber = o.optInt("IndexNumber", 0).takeIf { it > 0 },
            parentIndexNumber = o.optInt("ParentIndexNumber", -1).takeIf { it >= 0 },
            seriesName = o.optString("SeriesName").ifBlank { null },
            positionTicks = ud?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            played = ud?.optBoolean("Played", false) ?: false,
            playedPercentage = ud?.optDouble("PlayedPercentage", 0.0) ?: 0.0,
            primaryImageAspectRatio = o.optDouble("PrimaryImageAspectRatio", 0.0).takeIf { it > 0 },
            communityRating = o.optDouble("CommunityRating", 0.0).takeIf { it > 0 },
            genres = o.optJSONArray("Genres")?.let { g ->
                (0 until g.length()).map { g.optString(it) }
            } ?: emptyList(),
            isFavorite = ud?.optBoolean("IsFavorite", false) ?: false,
            resolution = res,
            isHdr = isHdr,
            isDolbyVision = isDv,
            audioFormat = audioChannels,
        )
    }

    // ---------------- 图片 ----------------

    fun imageUrl(item: MediaItem, maxHeight: Int = 480): String? {
        val tag = item.imageTag ?: return null
        return url("/Items/${item.id}/Images/Primary", mapOf("maxHeight" to maxHeight.toString(), "tag" to tag, "quality" to "90"))
    }

    fun imageUrl(id: String, tag: String?, maxHeight: Int = 480): String? {
        if (tag == null) return null
        return url("/Items/$id/Images/Primary", mapOf("maxHeight" to maxHeight.toString(), "tag" to tag, "quality" to "90"))
    }

    fun backdropUrl(item: MediaItem, maxWidth: Int = 1920): String? {
        val tag = item.backdropTag
        if (tag != null) return url("/Items/${item.id}/Images/Backdrop/0", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to "90"))
        val primaryTag = item.imageTag ?: return null
        return url("/Items/${item.id}/Images/Primary", mapOf("maxWidth" to maxWidth.toString(), "tag" to primaryTag, "quality" to "90"))
    }

    fun backdropUrl(id: String, tag: String?, maxWidth: Int = 1920): String? {
        if (tag == null) return null
        return url("/Items/$id/Images/Backdrop/0", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to "90"))
    }

    // ---------------- 播放 ----------------

    data class PlaybackTarget(
        val url: String,
        val direct: Boolean,
        val source: MediaSource?,
        val startPositionMs: Long,
    )

    /** 解析单个 MediaSource JSON 为模型（含大小与分辨率）。 */
    private fun parseMediaSource(o: JSONObject): MediaSource {
        val streams = mutableListOf<MediaStream>()
        var resolution: String? = null
        o.optJSONArray("MediaStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                streams += MediaStream(
                    index = s.optInt("Index"),
                    type = s.optString("Type"),
                    codec = s.optString("Codec").ifBlank { null },
                    language = s.optString("Language").ifBlank { null },
                    title = s.optString("Title").ifBlank { null },
                    isDefault = s.optBoolean("IsDefault", false),
                    isExternal = s.optBoolean("IsExternal", false),
                    displayTitle = s.optString("DisplayTitle").ifBlank { null },
                )
                if (s.optString("Type") == "Video" && resolution == null) {
                    val h = s.optInt("Height", 0)
                    resolution = when {
                        h >= 2000 -> "4K"
                        h >= 1000 -> "${h}p"
                        h >= 700 -> "720p"
                        h > 0 -> "${h}p"
                        else -> null
                    }
                }
            }
        }
        return MediaSource(
            id = o.optString("Id"),
            name = o.optString("Name", "源").ifBlank { "源" },
            container = o.optString("Container").ifBlank { null },
            supportsDirectPlay = o.optBoolean("SupportsDirectPlay", true),
            supportsDirectStream = o.optBoolean("SupportsDirectStream", true),
            streams = streams,
            size = o.optLong("Size", 0L),
            resolution = resolution,
        )
    }

    /** 拉取全部可选源（选源菜单用）。 */
    suspend fun getMediaSources(itemId: String): List<MediaSource> = withContext(Dispatchers.IO) {
        runCatching {
            val infoBody = JSONObject()
                .put("UserId", userId)
                .put("IsPlayback", false)
                .put("AutoOpenLiveStream", false)
                .toString()
            val info = postSync(url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId)), infoBody)
            val sources = info.optJSONArray("MediaSources") ?: JSONArray()
            (0 until sources.length()).mapNotNull { i ->
                sources.optJSONObject(i)?.let { parseMediaSource(it) }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun resolvePlayback(
        item: MediaItem,
        forceTranscode: Boolean = false,
        mediaSourceId: String? = null,
    ): PlaybackTarget =
        withContext(Dispatchers.IO) {
            val infoBody = JSONObject()
                .put("UserId", userId)
                .put("StartTimeTicks", item.positionTicks)
                .put("IsPlayback", true)
                .put("AutoOpenLiveStream", false)
                .put("MaxStreamingBitrate", 120_000_000)
            if (mediaSourceId != null) infoBody.put("MediaSourceId", mediaSourceId)
            val info = try {
                postSync(url("/Items/${item.id}/PlaybackInfo", mapOf("UserId" to userId)), infoBody.toString())
            } catch (_: Exception) { JSONObject() }
            val sources = info.optJSONArray("MediaSources")
            val chosen = if (mediaSourceId != null) {
                (0 until (sources?.length() ?: 0)).mapNotNull { i -> sources?.optJSONObject(i) }
                    .firstOrNull { it.optString("Id") == mediaSourceId }
            } else null
            val first = chosen ?: sources?.optJSONObject(0)
            val msId = first?.optString("Id")?.ifBlank { null } ?: item.id
            val source = if (first != null) parseMediaSource(first) else MediaSource(
                id = msId, name = "源", container = null,
                supportsDirectPlay = true, supportsDirectStream = true, streams = emptyList(),
            )
            val directUrl = url(
                "/Videos/${item.id}/stream",
                mapOf("static" to "true", "MediaSourceId" to msId, "DeviceId" to deviceId)
            )
            val hlsUrl = url(
                "/Videos/${item.id}/master.m3u8",
                mapOf(
                    "MediaSourceId" to msId,
                    "DeviceId" to deviceId,
                    "VideoCodec" to "h264",
                    "AudioCodec" to "aac",
                    "MaxAudioChannels" to "2",
                    "SegmentContainer" to "ts",
                    "MinSegments" to "1",
                    "BreakOnNonKeyFrames" to "true",
                )
            )
            val useDirect = !forceTranscode && source.supportsDirectPlay
            PlaybackTarget(
                url = if (useDirect) directUrl else hlsUrl,
                direct = useDirect,
                source = source,
                startPositionMs = item.positionTicks / 10_000,
            )
        }

    fun subtitleUrl(itemId: String, mediaSourceId: String, index: Int, format: String = "srt"): String =
        url("/Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.$format")

    // ---------------- 收藏 / 已看 ----------------

    suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = if (favorite) {
                postSync(url("/Users/$userId/FavoriteItems/$itemId", emptyMap()), "{}")
            } else {
                val req = Request.Builder().url(url("/Users/$userId/FavoriteItems/$itemId", emptyMap()))
                    .header("X-Emby-Authorization", authHeader())
                    .delete()
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw EmbyException("HTTP ${r.code}")
                    JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                }
            }
            resp.optJSONObject("UserData")?.optBoolean("IsFavorite", favorite) ?: favorite
        }.getOrDefault(favorite)
    }

    suspend fun setPlayed(itemId: String, played: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = if (played) {
                postSync(url("/Users/$userId/PlayedItems/$itemId", emptyMap()), "{}")
            } else {
                val req = Request.Builder().url(url("/Users/$userId/PlayedItems/$itemId", emptyMap()))
                    .header("X-Emby-Authorization", authHeader())
                    .delete()
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw EmbyException("HTTP ${r.code}")
                    JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                }
            }
            resp.optJSONObject("UserData")?.optBoolean("Played", played) ?: played
        }.getOrDefault(played)
    }

    // ---------------- 进度上报 ----------------

    suspend fun reportStart(itemId: String, positionMs: Long, mediaSourceId: String?, playMethod: String) = withContext(Dispatchers.IO) {
        runCatching {
            postSync(url("/Sessions/Playing", emptyMap()), JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", positionMs * 10_000)
                .put("CanSeek", true)
                .put("IsPaused", false)
                .put("PlayMethod", playMethod)
                .put("MediaSourceId", mediaSourceId ?: itemId)
                .toString())
        }
    }

    suspend fun reportProgress(itemId: String, positionMs: Long, paused: Boolean, mediaSourceId: String?) = withContext(Dispatchers.IO) {
        runCatching {
            postSync(url("/Sessions/Playing/Progress", emptyMap()), JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", positionMs * 10_000)
                .put("IsPaused", paused)
                .put("CanSeek", true)
                .put("PlayMethod", "DirectPlay")
                .put("MediaSourceId", mediaSourceId ?: itemId)
                .toString())
        }
    }

    suspend fun reportStop(itemId: String, positionMs: Long) = withContext(Dispatchers.IO) {
        runCatching {
            postSync(url("/Sessions/Playing/Stopped", emptyMap()), JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", positionMs * 10_000)
                .toString())
        }
    }

    suspend fun testConnection(server: String): String = withContext(Dispatchers.IO) {
        baseUrl = normalize(server)
        val info = getSync(url("/System/Info/Public"), withToken = false)
        info.optString("ServerName", "Emby")
    }

    companion object {
        fun normalize(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.trimEnd('/')
            if (s.endsWith("/web/index.html")) s = s.removeSuffix("/web/index.html")
            if (s.endsWith("/web")) s = s.removeSuffix("/web")
            return s
        }
    }
}
