package com.lumina.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lumina.tv.data.MediaChapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import android.net.Uri
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
    @Volatile var lastPlaybackInfoJson: String? = null
        private set
    @Volatile var lastTotalRecordCount: Int? = null
        private set
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
        params.forEach { (k, v) ->
            if (v != null) {
                if (q.isNotEmpty()) q.append('&')
                q.append(enc(k)).append('=').append(enc(v))
            }
        }
        if (token.isNotEmpty()) {
            if (q.isNotEmpty()) q.append('&')
            q.append("api_key=").append(enc(token))
        }
        if (q.isNotEmpty()) sb.append('?').append(q)
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getSync(url: String, withToken: Boolean = true): JSONObject {
        val req = Request.Builder().url(url).header("X-Emby-Authorization", authHeader(withToken)).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}: ${safeErrorBody(body)}")
            return parseJsonObject(body)
        }
    }

    private fun postSync(url: String, body: String?): JSONObject {
        val req = Request.Builder().url(url)
            .header("X-Emby-Authorization", authHeader())
            .post((body ?: "{}").toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}: ${safeErrorBody(text)}")
            return parseJsonObject(text)
        }
    }

    private fun parseJsonObject(body: String): JSONObject =
        if (body.isBlank()) JSONObject() else runCatching { JSONObject(body) }
            .getOrElse { throw EmbyException("服务器返回了无效数据") }

    /** 避免服务端错误正文意外回显访问令牌或认证字段。 */
    private fun safeErrorBody(body: String): String = body
        .replace(Regex("(?i)(\\\"?(?:AccessToken|api_key|Token|Pw|Password)\\\"?\\s*[:=]\\s*\\\")([^\\\"]*)(\\\")"), "$1[redacted]$3")
        .take(200)

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
        val info = getSync(url("/System/Info/Public", emptyMap()), withToken = false)
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
            val wanted = genre.trim()
            if (wanted.isBlank()) return@withContext emptyList()
            fun matches(items: List<MediaItem>) = items.filter { item ->
                item.genres.any { it.trim().equals(wanted, ignoreCase = true) }
            }
            // Emby servers usually honor Genres=, but some compatible servers ignore it.
            // Verify locally so every chip represents a real, distinct genre result.
            val remote = runCatching {
                getItems(types = "Movie,Series", sortBy = "DateCreated", sortOrder = "Descending",
                    limit = limit, startIndex = startIndex, genre = wanted)
            }.getOrDefault(emptyList())
            val exact = matches(remote)
            if (exact.isNotEmpty()) return@withContext exact.take(limit)

            // Compatibility fallback for servers that ignored/partially implemented Genres.
            val broad = runCatching {
                getItems(types = "Movie,Series", sortBy = "DateCreated", sortOrder = "Descending",
                    limit = maxOf(400, limit * 4), startIndex = 0)
            }.getOrDefault(emptyList())
            matches(broad).drop(startIndex).take(limit)
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
            "EnableImageTypes" to "Primary,Backdrop,Thumb,Logo",
        )
        if (parentId != null) params["ParentId"] = parentId
        if (types != null) params["IncludeItemTypes"] = types
        if (searchTerm != null) params["SearchTerm"] = searchTerm
        if (genre != null) params["Genres"] = genre
        if (filters != null) params["Filters"] = filters
        val response = getSync(url("/Users/$userId/Items", params))
        lastTotalRecordCount = response.optInt("TotalRecordCount", -1).takeIf { it >= 0 }
        val arr = response.optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    /** 分页读取指定 Emby 媒体库，避免首页预览的 30 条限制截断完整库。 */
    suspend fun getAllItems(
        parentId: String,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        pageSize: Int = 200,
        maxItems: Int = 10000,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val output = ArrayList<MediaItem>()
        val ids = HashSet<String>()
        val size = pageSize.coerceIn(1, 500)
        var start = 0
        while (start < maxItems) {
            val page = getItems(parentId = parentId, sortBy = sortBy, sortOrder = sortOrder, limit = size, startIndex = start)
            if (page.isEmpty()) break
            page.forEach { if (ids.add(it.id)) output += it }
            start += page.size
            if (page.size < size) break
        }
        output.take(maxItems)
    }

    suspend fun getResume(limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "Limit" to limit.toString(),
            "MediaTypes" to "Video",
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,ProductionYear",
            "EnableImageTypes" to "Primary,Thumb,Backdrop,Logo",
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
            val params = mapOf("UserId" to userId,
                "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,People,MediaSources")
            parseItem(getSync(url("/Users/$userId/Items/$id", params)))
        } catch (_: Exception) { null }
    }

    /** 换列表接口按 Ids 再取一次 People：部分服务器在单条目接口不回传 People（剧集尤甚）。 */
    suspend fun getItemPeople(itemId: String): List<MediaPerson> = withContext(Dispatchers.IO) {
        runCatching {
            val params = mapOf(
                "UserId" to userId,
                "Ids" to itemId,
                "Fields" to "People",
                "Limit" to "1",
            )
            val arr = getSync(url("/Users/$userId/Items", params)).optJSONArray("Items") ?: JSONArray()
            parseItems(arr).firstOrNull()?.people.orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun getChapters(itemId: String): List<MediaChapter> = withContext(Dispatchers.IO) {
        runCatching {
            val response = getSync(url("/Users/$userId/Items/$itemId", mapOf("Fields" to "Chapters")))
            val chapters = response.optJSONArray("Chapters") ?: JSONArray()
            (0 until chapters.length()).mapNotNull { i ->
                val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
                val ticks = chapter.optLong("StartPositionTicks", -1L)
                if (ticks < 0) return@mapNotNull null
                MediaChapter(
                    name = chapter.optString("Name").ifBlank { "章节 ${i + 1}" },
                    startMs = ticks / 10_000L,
                    imageTag = chapter.optString("ImageTag").ifBlank { null },
                )
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    /** Emby returns cast/crew in People when requested through Fields=People. */
    suspend fun getSimilarItems(itemId: String, limit: Int = 12): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching {
            val params = mapOf("UserId" to userId, "Limit" to limit.toString(),
                "Fields" to "Overview,PrimaryImageAspectRatio,Genres,ProductionYear,UserData",
                "EnableImageTypes" to "Primary,Backdrop,Thumb,Logo")
            val arr = getSync(url("/Items/$itemId/Similar", params)).optJSONArray("Items") ?: JSONArray()
            parseItems(arr).filterNot { it.id == itemId }.take(limit)
        }.getOrDefault(emptyList())
    }

    suspend fun getSeasons(seriesId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mapOf("UserId" to userId, "Fields" to "Overview,PrimaryImageAspectRatio,UserData")
        val arr = getSync(url("/Shows/$seriesId/Seasons", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String?): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "UserId" to userId,
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,MediaSources",
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
            logoTag = tags?.optString("Logo")?.ifBlank { null },
            // 请求了 Fields=MediaSources 才有值，用来判断"这一集有几个版本"
            sourceCount = o.optJSONArray("MediaSources")?.length() ?: 0,
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
            people = o.optJSONArray("People")?.let { people ->
                (0 until people.length()).mapNotNull { i ->
                    people.optJSONObject(i)?.let { person ->
                        val name = person.optString("Name").trim()
                        if (name.isBlank()) null else MediaPerson(
                            id = person.optString("Id").ifBlank { null },
                            name = name,
                            role = person.optString("Role").ifBlank { null },
                            type = person.optString("Type").ifBlank { null },
                            imageTag = person.optString("PrimaryImageTag").ifBlank { null },
                        )
                    }
                }
            } ?: emptyList(),
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

    fun personImageUrl(person: MediaPerson, maxHeight: Int = 320): String? {
        val id = person.id ?: return null
        val tag = person.imageTag ?: return null
        return url("/Items/$id/Images/Primary", mapOf("maxHeight" to maxHeight.toString(), "tag" to tag, "quality" to "85"))
    }

    fun backdropUrl(item: MediaItem, maxWidth: Int = 1920): String? {
        val tag = item.backdropTag
        if (tag == null) return null
        return url("/Items/${item.id}/Images/Backdrop/0", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to "90"))
    }

    /** 透明标题图（Emby Logo 图），没有就返回 null 由调用方退回文字标题。 */
    fun logoUrl(item: MediaItem, maxWidth: Int = 900): String? {
        val tag = item.logoTag ?: return null
        return url("/Items/${item.id}/Images/Logo", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to "90"))
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
        val headers: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
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
                    profile = s.optString("Profile").ifBlank { null },
                    level = s.optInt("Level").takeIf { s.has("Level") },
                    width = s.optInt("Width").takeIf { s.has("Width") },
                    height = s.optInt("Height").takeIf { s.has("Height") },
                    bitRate = s.optLong("BitRate").takeIf { s.has("BitRate") },
                    channels = s.optInt("Channels").takeIf { s.has("Channels") },
                    sampleRate = s.optInt("SampleRate").takeIf { s.has("SampleRate") },
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
            directStreamUrl = o.optString("DirectStreamUrl").ifBlank { null },
            additionalOptions = o.optString("AdditionalOptions").ifBlank { null },
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

    /** 读取纯文本响应：.strm 内容是文本，不能按 JSON 解析 */
    private fun getTextSync(target: String): String? = runCatching {
        val req = Request.Builder().url(target)
            .header("X-Emby-Authorization", authHeader())
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}")
            text
        }
    }.getOrNull()

    /** 从 .strm 文本里取第一条有效地址，跳过空行与 # 注释行 */
    private fun extractStrmUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && !line.startsWith("#") &&
                    (line.startsWith("http://", true) || line.startsWith("https://", true))
            }
    }

    /** 解析 #EXTVLCOPT 和 #EXTHTTP 片段，返回请求头映射 */
    private fun parseStrmOptions(text: String?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (text?.isNotBlank() != true) return headers
        for (line in text.lines()) {
            val upper = line.uppercase()
            if (upper.startsWith("#EXTVLCOPT:HTTP-REFERRER=")) {
                val referrer = line.substringAfter('=').trim()
                headers["Referer"] = referrer
            } else if (upper.startsWith("#EXTVLCOPT:HTTP-USER-AGENT=")) {
                val ua = line.substringAfter('=').trim()
                headers["User-Agent"] = ua
            } else if (upper.startsWith("#EXTHTTP:")) {
                val jsonPart = line.substringAfter("#EXTHTTP:").trim()
                try {
                    val json = JSONObject(jsonPart)
                    json.keys().forEach { key ->
                        val value = json.optString(key)
                        if (value.isNotBlank()) headers[key] = value
                    }
                } catch (_: Exception) { /* ignore malformed EXTHTTP */ }
            }
        }
        return headers
    }

    /** 判断 MediaSource 是否为 STRM 条目 */
    private fun isStrmSource(o: JSONObject): Boolean {
        val size = o.optLong("Size", 0L)
        val container = o.optString("Container")
        val path = o.optString("Path")
        val isStrmName = container.contains("strm", ignoreCase = true) ||
            path.endsWith(".strm", ignoreCase = true)
        return isStrmName && size <= 0L
    }

    suspend fun resolvePlayback(
        item: MediaItem,
        forceTranscode: Boolean = false,
        mediaSourceId: String? = null,
        startPositionMs: Long = item.positionTicks / 10_000,
        audioStreamIndex: Int? = null,
        forceDirect: Boolean = false,
    ): PlaybackTarget =
        withContext(Dispatchers.IO) {
            val infoBody = JSONObject()
                .put("UserId", userId)
                .put("StartTimeTicks", startPositionMs.coerceAtLeast(0L) * 10_000)
                .put("IsPlayback", true)
                .put("AutoOpenLiveStream", false)
                .put("MaxStreamingBitrate", 120_000_000)
                .put("DeviceProfile", deviceProfile())
            if (audioStreamIndex != null) infoBody.put("AudioStreamIndex", audioStreamIndex)
            if (mediaSourceId != null) infoBody.put("MediaSourceId", mediaSourceId)
            val playbackInfoParams = mapOf("UserId" to userId)
            val info = try {
                postSync(url("/Items/${item.id}/PlaybackInfo", playbackInfoParams), infoBody.toString())
                    .also { lastPlaybackInfoJson = it.toString() }
            } catch (failure: Exception) {
                lastPlaybackInfoJson = JSONObject().put("reason", failure.message ?: "PlaybackInfo request failed").toString()
                JSONObject()
            }
            val sources = info.optJSONArray("MediaSources")
            val chosen = if (mediaSourceId != null) {
                (0 until (sources?.length() ?: 0)).mapNotNull { i -> sources?.optJSONObject(i) }
                    .firstOrNull { it.optString("Id") == mediaSourceId }
            } else null
            val first = chosen ?: sources?.optJSONObject(0)
            val responsePositionTicks = info.optJSONObject("UserData")
                ?.optLong("PlaybackPositionTicks", -1L)
                ?: info.optJSONObject("Item")?.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", -1L)
                ?: first?.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", -1L)
                ?: -1L
            val resolvedStartPositionMs = if (responsePositionTicks >= 0L) responsePositionTicks / 10_000L
                else startPositionMs.coerceAtLeast(0L)
            val msId = first?.optString("Id")?.ifBlank { null } ?: item.id
            val source = if (first != null) parseMediaSource(first) else MediaSource(
                id = msId, name = "源", container = null,
                supportsDirectPlay = true, supportsDirectStream = true, streams = emptyList(),
            )
            val videoStream = source.streams.firstOrNull { it.type == "Video" }
            val videoCodec = videoStream?.codec.orEmpty().lowercase()
            val sourceContainer = source.container.orEmpty().lowercase()
            val videoNeedsTranscode = videoCodec.isNotBlank() && videoCodec !in setOf("h264", "avc", "hevc", "h265")
            val containerNeedsTranscode = sourceContainer.isNotBlank() &&
                sourceContainer !in setOf("mkv", "mp4", "m4v", "ts", "mov")
            val needTranscode = forceTranscode || videoNeedsTranscode ||
                containerNeedsTranscode || !source.supportsDirectPlay
            val directUrl = url(
                "/Videos/${item.id}/stream",
                mapOf("static" to "true", "MediaSourceId" to msId, "DeviceId" to deviceId,
                    "AudioStreamIndex" to audioStreamIndex?.toString())
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
                    "AudioStreamIndex" to audioStreamIndex?.toString(),
                )
            )
            // forceDirect：服务端转码流坏了（HLS 解析失败）时的兜底，直接拉原文件
            val useDirect = forceDirect || !needTranscode
            val transcodingUrl = first?.optString("TranscodingUrl")?.takeIf { it.startsWith("http", true) }
                ?.let { addPlaybackQuery(it, audioStreamIndex) }
            // --- STRM 处理 ---
            // 只在确实是 STRM 条目时读一次文本，URL 与请求头都从这里出，避免重复请求。
            val strmText: String? =
                if (first != null && isStrmSource(first)) {
                    getTextSync(url("/Items/${item.id}/Download", mapOf("UserId" to userId)))
                } else null
            val strmUrl: String? = when {
                // Emby 已经在 MediaSources 里给了绝对直链
                source.directStreamUrl?.startsWith("http") == true -> source.directStreamUrl
                // 否则从 .strm 文本取第一条有效地址
                strmText != null -> extractStrmUrl(strmText)
                else -> null
            }
            val isStrm = strmUrl?.isNotBlank() == true
            // 是 STRM 条目却拿不到地址：直接报错，不要退化成转码流导致无限缓冲
            if (!isStrm && first != null && isStrmSource(first) &&
                source.directStreamUrl?.startsWith("http") != true
            ) {
                throw EmbyException("该 STRM 条目没有可用地址（文件为空或内容不是 http 链接）")
            }
            val finalUrl = if (isStrm && !forceTranscode) strmUrl!! else if (useDirect) directUrl else transcodingUrl ?: hlsUrl
            // 请求头：#EXTVLCOPT / #EXTHTTP 既可能来自 MediaSources.AdditionalOptions，也可能在 .strm 文本里
            val headers = buildMap {
                putAll(parseStrmOptions(source.additionalOptions))
                putAll(parseStrmOptions(strmText))
            }
            PlaybackTarget(
                url = finalUrl,
                // STRM 直链本身就是直连播放，不参与转码判定
                direct = if (isStrm && !forceTranscode) true else useDirect,
                source = source,
                startPositionMs = resolvedStartPositionMs,
                headers = headers,
            )
        }

    private fun deviceProfile(): JSONObject = JSONObject()
        .put("MaxAudioChannels", "2")
        .put("DirectPlayProfiles", JSONArray()
            .put(JSONObject().put("Type", "Video").put("Container", "mkv,mp4,m4v,ts,mov")
                .put("VideoCodec", "h264,hevc").put("AudioCodec", "aac,ac3,eac3,mp3,opus,flac,vorbis"))
            .put(JSONObject().put("Type", "Audio").put("Container", "mp3").put("AudioCodec", "mp3")))
        .put("TranscodingProfiles", JSONArray()
            .put(JSONObject().put("Container", "ts").put("Type", "Video").put("VideoCodec", "h264")
                .put("AudioCodec", "aac").put("Protocol", "hls").put("MaxAudioChannels", "2"))
            .put(JSONObject().put("Container", "ts").put("Type", "Audio").put("AudioCodec", "aac")
                .put("Protocol", "hls").put("MaxAudioChannels", "2")))
    private fun addPlaybackQuery(url: String, audioStreamIndex: Int?): String {
        val builder = Uri.parse(url).buildUpon()
        val names = Uri.parse(url).queryParameterNames
        if ("DeviceId" !in names) builder.appendQueryParameter("DeviceId", deviceId)
        if ("api_key" !in names && token.isNotEmpty()) builder.appendQueryParameter("api_key", token)
        if (audioStreamIndex != null) builder.appendQueryParameter("AudioStreamIndex", audioStreamIndex.toString())
        return builder.build().toString()
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

    suspend fun clearWatchHistory(): Int = withContext(Dispatchers.IO) {
        var cleared = 0
        while (cleared < 100_000) {
            val page = getItems(
                types = "Movie,Series,Episode",
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = 100,
                startIndex = 0,
                filters = "IsPlayed",
            )
            if (page.isEmpty()) break
            page.forEach { item ->
                val request = Request.Builder()
                    .url(url("/Users/$userId/PlayedItems/${item.id}"))
                    .header("X-Emby-Authorization", authHeader())
                    .delete()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) cleared++
                }
            }
            if (page.size < 100) break
        }
        cleared
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
