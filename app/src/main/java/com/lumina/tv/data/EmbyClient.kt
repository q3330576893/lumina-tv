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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import android.net.Uri
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val trickplayMutex = Mutex()
    private val trickplayFiles = mutableMapOf<String, ByteArray>()
    private val unavailableTrickplay = ConcurrentHashMap.newKeySet<String>()
    private val playbackTargetCache = ConcurrentHashMap<String, Pair<Long, PlaybackTarget>>()
    private val chapterCache = ConcurrentHashMap<String, List<MediaChapter>>()
    private val trickplayFrames = object : LinkedHashMap<Pair<String, Int>, ByteArray>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, ByteArray>?): Boolean = size > 24
    }

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

    private fun getSync(url: String, withToken: Boolean = true, timeoutSeconds: Int? = null): JSONObject {
        val req = Request.Builder().url(url).header("X-Emby-Authorization", authHeader(withToken)).build()
        var attempts = 0
        while (true) {
            try {
                http.newCall(req).apply { timeoutSeconds?.let { timeout().timeout(it.toLong(), TimeUnit.SECONDS) } }.execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw EmbyException("HTTP ${resp.code}: ${safeErrorBody(body)}")
                    return parseJsonObject(body)
                }
            } catch (e: IOException) {
                attempts++
                val timedOut = e is InterruptedIOException || e.message.orEmpty().contains("timeout", true)
                if (timeoutSeconds == null || !timedOut || attempts >= SEARCH_REQUEST_ATTEMPTS) throw e
            }
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
        fields: String = "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear",
        requestTimeoutSeconds: Int? = null,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "Recursive" to "true",
            "Limit" to limit.toString(),
            "StartIndex" to startIndex.toString(),
            "SortBy" to sortBy,
            "SortOrder" to sortOrder,
            "Fields" to fields,
            "ImageTypeLimit" to "1",
            "EnableImageTypes" to "Primary,Backdrop,Thumb,Logo",
        )
        if (parentId != null) params["ParentId"] = parentId
        if (types != null) params["IncludeItemTypes"] = types
        if (searchTerm != null) params["SearchTerm"] = searchTerm
        if (genre != null) params["Genres"] = genre
        if (filters != null) params["Filters"] = filters
        val response = getSync(url("/Users/$userId/Items", params), timeoutSeconds = requestTimeoutSeconds)
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
                "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,People,MediaSources,ProviderIds")
            parseItem(getSync(url("/Users/$userId/Items/$id", params)))
        } catch (_: Exception) { null }
    }

    suspend fun getLocalTrailers(itemId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url("/Items/$itemId/LocalTrailers"))
                .header("X-Emby-Authorization", authHeader())
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw EmbyException("HTTP ${response.code}")
                val trailers = JSONArray(response.body?.string().orEmpty())
                (0 until trailers.length()).mapNotNull { index ->
                    trailers.optJSONObject(index)?.let(::parseItem)
                }
            }
        }.getOrDefault(emptyList())
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
        chapterCache[itemId]?.let { return@withContext it }
        fun parseChapters(chapters: JSONArray): List<MediaChapter> =
            (0 until chapters.length()).mapNotNull { i ->
                val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
                val ticks = chapter.optLong("StartPositionTicks", -1L)
                if (ticks < 0) return@mapNotNull null
                val name = chapter.optString("Name").trim()
                if (name.matches(Regex("(?i)(intro|opening)\\s*(start|end)|credits?\\s*(start|end)?|片头(开始|结束)|片尾(开始|结束)"))) {
                    return@mapNotNull null
                }
                MediaChapter(
                    name = name.ifBlank { "章节 ${i + 1}" },
                    startMs = ticks / 10_000L,
                    imageTag = chapter.optString("ImageTag").ifBlank { null },
                )
            }.sortedBy { it.startMs }

        val independent = runCatching {
            val request = Request.Builder()
                .url(url("/Items/$itemId/Chapters"))
                .header("X-Emby-Authorization", authHeader())
                .build()
            val body = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw EmbyException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val chapters = runCatching { JSONArray(body) }.getOrElse {
                JSONObject(body).optJSONArray("Items") ?: JSONObject(body).optJSONArray("Chapters") ?: JSONArray()
            }
            parseChapters(chapters)
        }.getOrDefault(emptyList())
        (independent + runCatching {
            val response = getSync(url("/Users/$userId/Items/$itemId", mapOf(
                "Fields" to "Chapters",
            )))
            parseChapters(response.optJSONArray("Chapters") ?: JSONArray())
        }.getOrDefault(emptyList()))
            .distinctBy { it.name to it.startMs }.sortedBy { it.startMs }
            .also { chapterCache[itemId] = it }
    }

    suspend fun getPlaybackMarkers(itemId: String): PlaybackMarkers = withContext(Dispatchers.IO) {
        runCatching {
            val response = getSync(url("/Users/$userId/Items/$itemId", mapOf(
                "Fields" to "IntroStartPositionTicks,IntroEndPositionTicks,CreditsPositionTicks",
            )))
            fun marker(field: String): Long? = response.optLong(field, -1L)
                .takeIf { it > 0L }?.div(10_000L)
            PlaybackMarkers(
                introStartMs = marker("IntroStartPositionTicks"),
                introEndMs = marker("IntroEndPositionTicks"),
                creditsStartMs = marker("CreditsPositionTicks"),
            )
        }.getOrDefault(PlaybackMarkers())
    }

    suspend fun getNextEpisode(item: MediaItem): MediaItem? {
        val seriesId = item.seriesId ?: return null
        val current = item.seasonId?.let { runCatching { getEpisodes(seriesId, it) }.getOrDefault(emptyList()) }.orEmpty()
        val orderedCurrent = current.sortedWith(compareBy<MediaItem> { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
        val currentIndex = orderedCurrent.indexOfFirst { it.id == item.id }
        if (currentIndex >= 0) orderedCurrent.getOrNull(currentIndex + 1)?.let { return it }

        val seasons = runCatching { getSeasons(seriesId) }.getOrDefault(emptyList())
            .sortedWith(compareBy<MediaItem> { it.indexNumber ?: it.parentIndexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
        val seasonIndex = seasons.indexOfFirst { it.id == item.seasonId }
        if (seasonIndex < 0) return null
        for (season in seasons.drop(seasonIndex + 1)) {
            val seasonEpisodes = runCatching { getEpisodes(seriesId, season.id) }.getOrDefault(emptyList())
                .sortedWith(compareBy<MediaItem> { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
            seasonEpisodes.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun playbackTargetKey(itemId: String, forceTranscode: Boolean, sourceId: String?, audioStreamIndex: Int?, forceDirect: Boolean) =
        "$itemId|$forceTranscode|${sourceId.orEmpty()}|${audioStreamIndex ?: ""}|$forceDirect"

    fun cachedPlaybackTarget(itemId: String, forceTranscode: Boolean, sourceId: String?, audioStreamIndex: Int?, forceDirect: Boolean): PlaybackTarget? {
        val cached = playbackTargetCache.remove(playbackTargetKey(itemId, forceTranscode, sourceId, audioStreamIndex, forceDirect)) ?: return null
        return cached.second.takeIf { System.currentTimeMillis() - cached.first < 120_000L }
    }

    suspend fun prewarmPlayback(item: MediaItem, sourceId: String? = null, audioStreamIndex: Int? = null) {
        runCatching {
            resolvePlayback(item, mediaSourceId = sourceId, startPositionMs = item.positionTicks / 10_000L,
                audioStreamIndex = audioStreamIndex, recordDiagnostics = false, cacheResult = true)
        }
    }

    /** Returns a JPEG frame from Emby's optional BIF file; failures are intentionally silent. */
    suspend fun getTrickplayFrame(itemId: String, positionMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (itemId in unavailableTrickplay) return@withContext null
            val file = trickplayMutex.withLock {
                trickplayFiles[itemId] ?: if (itemId in unavailableTrickplay) null else run {
                    val request = Request.Builder()
                        .url(url("/Videos/$itemId/index.bif"))
                        .header("X-Emby-Authorization", authHeader())
                        .build()
                    val bytes = http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty BIF")
                        if (body.contentLength() > MAX_TRICKPLAY_BYTES) throw IOException("BIF too large")
                        body.bytes().takeIf { it.size <= MAX_TRICKPLAY_BYTES } ?: throw IOException("BIF too large")
                    }
                    if (bytes.size < 72 || !bytes.copyOfRange(0, 8).contentEquals(BIF_MAGIC)) {
                        throw IOException("Invalid BIF header")
                    }
                    val count = readLeInt(bytes, 12)
                    val interval = readLeInt(bytes, 16)
                    if (count <= 0 || count > MAX_TRICKPLAY_FRAMES || interval <= 0 || 64L + (count + 1L) * 8L > bytes.size) {
                        throw IOException("Invalid BIF index")
                    }
                    trickplayFiles[itemId] = bytes
                    bytes
                }
            } ?: return@withContext null
            val interval = readLeInt(file, 16)
            val frameIndex = (positionMs.coerceAtLeast(0L) / interval).toInt()
                .coerceAtMost(readLeInt(file, 12) - 1)
            synchronized(trickplayFrames) { trickplayFrames[itemId to frameIndex]?.let { return@withContext it } }
            val entryOffset = 64 + frameIndex * 8
            val start = readLeInt(file, entryOffset + 4)
            val end = if (frameIndex == readLeInt(file, 12) - 1) file.size else readLeInt(file, entryOffset + 12)
            if (start < 0 || end <= start || end > file.size) return@withContext null
            val jpeg = file.copyOfRange(start, end)
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return@withContext null
            synchronized(trickplayFrames) { trickplayFrames[itemId to frameIndex] = jpeg }
            jpeg
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailableTrickplay.add(itemId)
            null
        }
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
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
        val providerIds = buildMap {
            o.optJSONObject("ProviderIds")?.let { ids ->
                val names = ids.names() ?: JSONArray()
                for (i in 0 until names.length()) {
                    val originalKey = names.optString(i)
                    val value = ids.optString(originalKey).trim()
                    if (originalKey.isNotBlank() && value.isNotBlank()) put(originalKey.lowercase(), value)
                }
            }
        }
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
            providerIds = providerIds,
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

    /** Resolves the server-created low-bitrate preview session, falling back to the original file. */
    suspend fun resolveDetailPreviewUrl(itemId: String, mediaSourceId: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("UserId", userId)
                .put("StartTimeTicks", 0)
                .put("IsPlayback", true)
                .put("AutoOpenLiveStream", false)
                .put("EnableDirectPlay", false)
                .put("EnableDirectStream", false)
                .put("EnableTranscoding", true)
                .put("MaxStreamingBitrate", DETAIL_PREVIEW_BITRATE)
                .put("MediaSourceId", mediaSourceId)
                .put("DeviceProfile", detailPreviewDeviceProfile())
            val info = runCatching {
                postSync(
                    url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId)),
                    body.toString(),
                )
            }.getOrNull()
            val first = info?.optJSONArray("MediaSources")?.optJSONObject(0)
            if (first != null && isStrmSource(first)) {
                val strmUrl = first.optString("DirectStreamUrl")
                    .takeIf { it.startsWith("http", true) }
                    ?: extractStrmUrl(getTextSync(url("/Items/$itemId/Download", mapOf("UserId" to userId))))
                return@withContext strmUrl?.takeIf { it.startsWith("http", true) }
            }
            first?.optString("TranscodingUrl")
                ?.takeIf { it.startsWith("http", true) }
                // The PlaybackInfo URL already contains the complete transcoding session query.
                ?: (first?.optString("Id")?.ifBlank { null } ?: mediaSourceId.ifBlank { null })?.let { sourceId ->
                    url(
                        "/Videos/$itemId/stream",
                        mapOf("static" to "true", "MediaSourceId" to sourceId),
                    )
                }
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
                    videoRangeType = s.optString("VideoRangeType").ifBlank { s.optString("VideoRange").ifBlank { null } },
                    dvProfile = s.optInt("DvProfile", -1).takeIf { it > 0 },
                    bitDepth = s.optInt("BitDepth", 0).takeIf { it > 0 },
                    colorSpace = s.optString("ColorSpace").ifBlank { null },
                    colorTransfer = s.optString("ColorTransfer").ifBlank { null },
                    colorPrimaries = s.optString("Color primaries").ifBlank { s.optString("ColorPrimaries").ifBlank { null } },
                    frameRate = s.optDouble("RealFrameRate", 0.0).takeIf { it > 0 }
                        ?: s.optDouble("AverageFrameRate", 0.0).takeIf { it > 0 },
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
            bitrate = o.optLong("Bitrate", 0L).takeIf { it > 0 },
            resolution = resolution,
            directStreamUrl = o.optString("DirectStreamUrl").ifBlank { null },
            additionalOptions = o.optString("AdditionalOptions").ifBlank { null },
        )
    }

    /** 拉取全部可选源（选源菜单用）。 */
    suspend fun getMediaSources(itemId: String): List<MediaSource> = withContext(Dispatchers.IO) {
        runCatching {
            fun playbackInfoBody(includeDeviceProfile: Boolean) = JSONObject()
                .put("UserId", userId)
                .put("IsPlayback", false)
                .put("AutoOpenLiveStream", false)
                .apply { if (includeDeviceProfile) put("DeviceProfile", deviceProfile()) }
            fun parseSources(info: JSONObject): List<MediaSource> {
                val sources = info.optJSONArray("MediaSources") ?: JSONArray()
                return (0 until sources.length()).mapNotNull { i ->
                    sources.optJSONObject(i)?.let { parseMediaSource(it) }
                }
            }
            val playbackUrl = url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId))
            val initial = parseSources(postSync(playbackUrl, playbackInfoBody(false).toString()))
            if (initial.any { source -> source.streams.any { it.type == "Video" } }) initial
            else {
                val retried = runCatching {
                    parseSources(postSync(playbackUrl, playbackInfoBody(true).toString()))
                }.getOrDefault(emptyList())
                retried.ifEmpty { initial }
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
        val container = o.optString("Container")
        val path = o.optString("Path")
        return container.contains("strm", ignoreCase = true) ||
            path.endsWith(".strm", ignoreCase = true)
    }

    /** STRM 直链探活：只发 HEAD，2 秒超时，2xx/3xx 视为可达。 */
    private fun strmUrlReachable(u: String): Boolean = runCatching {
        val request = Request.Builder().url(u).head().build()
        http.newCall(request).apply { timeout().timeout(2, TimeUnit.SECONDS) }
            .execute().use { it.isSuccessful || it.code in 300..399 }
    }.getOrDefault(false)

    suspend fun resolvePlayback(
        item: MediaItem,
        forceTranscode: Boolean = false,
        mediaSourceId: String? = null,
        startPositionMs: Long = item.positionTicks / 10_000,
        audioStreamIndex: Int? = null,
        forceDirect: Boolean = false,
        recordDiagnostics: Boolean = true,
        cacheResult: Boolean = false,
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
                    .also { if (recordDiagnostics) lastPlaybackInfoJson = it.toString() }
            } catch (failure: Exception) {
                if (recordDiagnostics) lastPlaybackInfoJson = JSONObject().put("reason", failure.message ?: "PlaybackInfo request failed").toString()
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
            val strmReachable = if (isStrm) strmUrlReachable(strmUrl!!) else true
            if (isStrm && !strmReachable && !forceDirect) {
                throw EmbyException("STRM 直链无法访问（网盘链接可能已失效或需要登录），路径：${strmUrl.take(80)}")
            }
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
            ).also { target ->
                if (cacheResult && first != null && target.url.isNotBlank()) playbackTargetCache[playbackTargetKey(item.id, forceTranscode, mediaSourceId, audioStreamIndex, forceDirect)] =
                    System.currentTimeMillis() to target
            }
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

    private fun detailPreviewDeviceProfile(): JSONObject = JSONObject()
        .put("MaxStreamingBitrate", DETAIL_PREVIEW_BITRATE)
        .put("MaxAudioChannels", "2")
        .put("DirectPlayProfiles", JSONArray())
        .put("TranscodingProfiles", JSONArray().put(
            JSONObject()
                .put("Container", "ts")
                .put("Type", "Video")
                .put("VideoCodec", "h264")
                .put("AudioCodec", "aac")
                .put("Protocol", "hls")
                .put("MaxAudioChannels", "2")
                .put("MaxWidth", "960")
        ))

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

    suspend fun getSubtitleText(itemId: String, mediaSourceId: String, index: Int): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(subtitleUrl(itemId, mediaSourceId, index, "vtt"))
            .header("X-Emby-Authorization", authHeader())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EmbyException("字幕读取失败（HTTP ${response.code}）")
            response.body?.string().orEmpty()
        }
    }

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
        private const val SEARCH_REQUEST_ATTEMPTS = 2
        private val BIF_MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MAX_TRICKPLAY_BYTES = 64L * 1024 * 1024
        private const val MAX_TRICKPLAY_FRAMES = 1_000_000

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

private const val DETAIL_PREVIEW_BITRATE = 1_200_000
