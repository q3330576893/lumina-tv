package com.lumina.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.lumina.tv.data.MediaChapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.InterruptedIOException
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.net.Uri
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.PageLog

class EmbyException(message: String, val httpStatus: Int? = null) : Exception(message)

data class DetailPreviewResolution(
    val url: String?,
    val stage: String?,
    val failures: List<String>,
    val requiredHttpHeaders: Map<String, String> = emptyMap(),
)

private enum class PlaybackRequestFlag(
    val key: String,
    val send: Boolean,
    val value: Boolean,
) {
    ENABLE_DIRECT_PLAY("EnableDirectPlay", true, true),
    ENABLE_DIRECT_STREAM("EnableDirectStream", true, true),
    ENABLE_TRANSCODING("EnableTranscoding", true, true),
    ALLOW_VIDEO_STREAM_COPY("AllowVideoStreamCopy", true, true),
    ALLOW_AUDIO_STREAM_COPY("AllowAudioStreamCopy", true, true),
}

/** Emby REST 客户端。所有方法都是挂起函数，网络在 IO 线程执行。 */
class EmbyClient(
    var baseUrl: String,
    var token: String = "",
    var userId: String = "",
    private val deviceId: String = "lumina-tv",
    val userName: String = "",
    val serverName: String = "Emby",
    progressQueue: ProgressQueue? = null,
) {
    @Volatile var lastPlaybackInfoJson: String? = null
        private set
    @Volatile var lastTotalRecordCount: Int? = null
        private set
    @Volatile var lastApiFailure: String? = null
        private set
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val started = System.currentTimeMillis()
            val request = chain.request()
            try {
                val response = chain.proceed(request)
                NetLog.add(started, request.method, request.url.toString(), System.currentTimeMillis() - started, response.code, if (response.isSuccessful) null else "HTTP")
                response
            } catch (e: IOException) {
                NetLog.add(started, request.method, request.url.toString(), System.currentTimeMillis() - started, null, e.javaClass.simpleName)
                throw e
            }
        }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressOutbox = progressQueue ?: ProgressQueue()
    // AppRuntime owns this client. Playback screens only submit snapshots; they never own HTTP jobs.
    private val progressSender = ProgressSender(progressScope, progressOutbox, send = ::sendProgressEvent)
    private val trickplayMutex = Mutex()
    private val trickplayFiles = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var trickplayBytes = 0L
    private val unavailableTrickplay = ConcurrentHashMap.newKeySet<String>()
    private val playbackTargetCache = ConcurrentHashMap<TrackAwarePlaybackKey, Pair<Long, PlaybackTarget>>()
    // Diagnostic state lives with the client so it survives the next-episode screen replacement.
    private class PrewarmAttempt(
        val itemId: String,
        val sourceId: String?,
        val strategy: SourceStrategy,
        val requestKey: TrackAwarePlaybackKey,
    ) {
        @Volatile var state: String = "in_progress"
    }
    @Volatile private var lastPrewarmAttempt: PrewarmAttempt? = null
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

    fun previewHeaders(target: String?, requiredHttpHeaders: Map<String, String> = emptyMap()): Map<String, String> =
        requiredHttpHeaders.filterKeys { !it.equals("X-Emby-Authorization", true) }.toMutableMap().apply {
            if (isSameOrigin(baseUrl, target)) put("X-Emby-Authorization", authHeader())
        }

    val previewHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(PreviewOriginInterceptor({ baseUrl }, { authHeader() }))
            .build()
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

    private fun retryAfterMillis(value: String?, serverDate: String?): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val delay = raw.toLongOrNull()?.let { it.coerceAtMost(60L).coerceAtLeast(0L) * 1_000L } ?: catchingRequest {
            val retryAt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            val now = serverDate?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                ?: System.currentTimeMillis()
            (retryAt - now).coerceAtLeast(0L)
        }.getOrNull() ?: return null
        return delay.coerceAtMost(60_000L)
    }

    private suspend fun getJson(target: String, withToken: Boolean = true, timeoutSeconds: Int? = null): JSONObject =
        getJson(target, withToken, timeoutSeconds, ::parseJsonObject)

    private suspend fun <T : Any> getJson(
        target: String,
        withToken: Boolean = true,
        timeoutSeconds: Int? = null,
        decode: (String) -> T,
    ): T =
        withRequestBudget((timeoutSeconds?.toLong() ?: 20L) * 1_000L) {
            val request = Request.Builder().url(target).header("X-Emby-Authorization", authHeader(withToken)).build()
            requestJson(request, retryIo = timeoutSeconds != null, retryRateLimit = true, decode = decode)
        }

    private suspend fun postJson(target: String, body: String?): JSONObject = withRequestBudget {
        val request = Request.Builder().url(target).header("X-Emby-Authorization", authHeader())
            .post((body ?: "{}").toRequestBody(jsonType)).build()
        requestJson(request, retryIo = false, retryRateLimit = target.contains("/PlaybackInfo", true), decode = ::parseJsonObject)
    }

    private suspend fun <T : Any> requestJson(request: Request, retryIo: Boolean, retryRateLimit: Boolean, decode: (String) -> T): T {
        var attempts = 0
        var rateLimitRetries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                var retryDelay: Long? = null
                val result = http.readCancellable(request) { response ->
                    if (response.code == 429 && retryRateLimit && rateLimitRetries == 0) {
                        retryDelay = retryAfterMillis(response.header("Retry-After"), response.header("Date"))
                    }
                    if (retryDelay != null) null else {
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw EmbyException("HTTP ${response.code}: ${safeErrorBody(body)}", response.code)
                        decode(body)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (result != null) return result
                rateLimitRetries++
                delay(requireNotNull(retryDelay))
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                attempts++
                if (!retryIo || attempts >= SEARCH_REQUEST_ATTEMPTS) throw failure
                delay(150L)
            }
        }
    }

    private fun parseJsonObject(body: String): JSONObject =
        if (body.isBlank()) JSONObject() else catchingRequest { JSONObject(body) }
            .getOrElse { throw EmbyException("服务器返回了无效数据") }

    /** 避免服务端错误正文意外回显访问令牌或认证字段。 */
    private fun safeErrorBody(body: String): String = body
        .replace(Regex("(?i)(\\\"?(?:AccessToken|api_key|Token|Pw|Password)\\\"?\\s*[:=]\\s*\\\")([^\\\"]*)(\\\")"), "$1[redacted]$3")
        .take(200)

    // ---------------- 认证 ----------------

    suspend fun login(server: String, user: String, pass: String): Session = withMetadataContext {
        baseUrl = normalize(server)
        token = ""
        val body = JSONObject().put("Username", user).put("Pw", pass).toString()
        val resp = postJson(url("/Users/AuthenticateByName", emptyMap()), body)
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

    private suspend fun getServerNameSafe(): String = try {
        val info = getJson(url("/System/Info/Public", emptyMap()), withToken = false)
        info.optString("ServerName", "Emby")
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { "Emby" }

    // ---------------- 内容 ----------------

    suspend fun getViews(): List<LibraryView> = withMetadataContext {
        val arr = getJson(url("/Users/$userId/Views")).optJSONArray("Items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LibraryView(
                id = o.optString("Id"),
                name = o.optString("Name"),
                type = o.optString("CollectionType", "movies"),
                imageTag = o.optJSONObject("ImageTags")?.optString("Primary")?.ifBlank { null },
            )
        }.filter { it.id.isNotBlank() }
    }

    suspend fun getLibraryItemCount(parentId: String): Int? = withMetadataContext {
        val response = catchingRequest { getJson(url("/Items", mapOf(
            "ParentId" to parentId,
            "Recursive" to "true",
            "Limit" to "1",
            "EnableTotalRecordCount" to "true",
        ))) }.getOrNull()
        response?.optInt("TotalRecordCount", -1)?.takeIf { it >= 0 }?.let { return@withMetadataContext it }
        catchingRequest {
            val counts = getJson(url("/Items/Counts", mapOf("ParentId" to parentId, "Recursive" to "true")))
            val total = counts.optInt("TotalRecordCount", -1)
            if (total >= 0) total else {
                val keys = listOf("MovieCount", "SeriesCount", "EpisodeCount", "AlbumCount", "ArtistCount", "SongCount", "ItemCount")
                if (keys.none { counts.has(it) }) null else keys.map { counts.optInt(it, 0) }.sum()
            }
        }.getOrNull()
    }

    suspend fun getFavorites(limit: Int = 24, startIndex: Int = 0): FavoritesPage = withMetadataContext {
        require(limit > 0 && startIndex >= 0)
        val response = getJson(url("/Users/$userId/Items", mapOf(
            "Recursive" to "true", "IncludeItemTypes" to "Movie,Series,Episode",
            "Filters" to "IsFavorite", "SortBy" to FAVORITES_SORT, "SortOrder" to "Descending",
            "Limit" to limit.toString(), "StartIndex" to startIndex.toString(),
            "EnableTotalRecordCount" to "true",
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,ProductionLocations,OriginalLanguage",
            "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary,Backdrop,Thumb,Logo",
        )))
        val rows = response.optJSONArray("Items") ?: JSONArray()
        val total = response.optInt("TotalRecordCount", -1).takeIf { it >= 0 }
        // Advance by rows consumed from this response, never by the de-duplicated UI size.
        FavoritesPage(parseItems(rows), total, favoritesNextOffset(startIndex, rows.length(), total))
    }

    /** 全部类型（题材）名称。多级兜底：/Genres → /Items/Filters → 本地汇总。 */
    suspend fun getGenres(): List<String> = withMetadataContext {
        // 1) 标准 /Genres
        val direct = catchingRequest {
            val params = mapOf(
                "UserId" to userId,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "SortBy" to "SortName",
                "SortOrder" to "Ascending",
            )
            val arr = getJson(url("/Genres", params)).optJSONArray("Items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("Name")?.ifBlank { null }
            }
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return@withMetadataContext direct

        // 2) Emby 常用 /Items/Filters（返回字符串数组）
        val viaFilters = catchingRequest {
            val j = getJson(
                url("/Items/Filters", mapOf("UserId" to userId, "IncludeItemTypes" to "Movie,Series"))
            )
            val arr = j.optJSONArray("Genres") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.getOrDefault(emptyList())
        if (viaFilters.isNotEmpty()) return@withMetadataContext viaFilters

        // 3) 从库里直接汇总（最慢但最兼容）
        catchingRequest {
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
        withMetadataContext {
            val wanted = genre.trim()
            if (wanted.isBlank()) return@withMetadataContext emptyList()
            fun matches(items: List<MediaItem>) = items.filter { item ->
                item.genres.any { it.trim().equals(wanted, ignoreCase = true) }
            }
            // Emby servers usually honor Genres=, but some compatible servers ignore it.
            // Verify locally so every chip represents a real, distinct genre result.
            val remote = catchingRequest {
                getItems(types = "Movie,Series", sortBy = "DateCreated", sortOrder = "Descending",
                    limit = limit, startIndex = startIndex, genre = wanted)
            }.getOrDefault(emptyList())
            val exact = matches(remote)
            if (exact.isNotEmpty()) return@withMetadataContext exact.take(limit)

            // Compatibility fallback for servers that ignored/partially implemented Genres.
            val broad = catchingRequest {
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
        personIds: String? = null,
        filters: String? = null,
        fields: String = "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,ProductionLocations,OriginalLanguage",
        requestTimeoutSeconds: Int? = null,
    ): List<MediaItem> = withMetadataContext((requestTimeoutSeconds?.toLong() ?: 20L) * 1_000L) {
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
        if (personIds != null) params["PersonIds"] = personIds
        if (filters != null) params["Filters"] = filters
        val response = getJson(url("/Users/$userId/Items", params), timeoutSeconds = requestTimeoutSeconds)
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
    ): List<MediaItem> = withMetadataContext {
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

    suspend fun getResume(limit: Int = 20): List<MediaItem> = withMetadataContext {
        val params = mapOf(
            "Limit" to limit.toString(),
            "MediaTypes" to "Video",
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,ProductionYear,ProductionLocations,OriginalLanguage,LastPlayedDate",
            "EnableImageTypes" to "Primary,Thumb,Backdrop,Logo",
        )
        val arr = getJson(url("/Users/$userId/Items/Resume", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getLatest(parentId: String?, limit: Int = 20): List<MediaItem> = withTimeoutOrNull(6_000L) { withMetadataContext(6_000L) {
        val fields = "Overview,PrimaryImageAspectRatio,UserData,ProductionYear,ProductionLocations,OriginalLanguage"
        suspend fun query(path: String, params: Map<String, String?>): List<MediaItem>? = catchingRequest {
            val arr = getJson(url(path, params), decode = { body ->
                parseLatestItemsResponse(body) { lastApiFailure = it }
                    ?: throw EmbyException("服务器返回了无效的最新项目数据")
            })
            parseItems(arr)
        }.getOrNull()
        val minimal = mapOf<String, String?>("Limit" to limit.toString())
        query("/Users/$userId/Items/Latest", minimal)?.let { return@withMetadataContext it }
        val withFields = mutableMapOf<String, String?>("Limit" to limit.toString(), "Fields" to fields)
        if (parentId != null) withFields["ParentId"] = parentId
        query("/Users/$userId/Items/Latest", withFields)?.let { return@withMetadataContext it }
        val fallback = mutableMapOf<String, String?>("Recursive" to "true", "SortBy" to "DateCreated", "SortOrder" to "Descending", "IncludeItemTypes" to "Movie,Series", "Limit" to limit.toString(), "Fields" to fields)
        if (parentId != null) fallback["ParentId"] = parentId
        query("/Items", fallback) ?: emptyList()
    } } ?: emptyList()

    suspend fun getItem(id: String): MediaItem? = withMetadataContext {
        try {
            val params = mapOf("UserId" to userId,
                "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,People,MediaSources,ProviderIds,ProductionLocations,OriginalLanguage")
            parseItem(getJson(url("/Users/$userId/Items/$id", params)))
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }

    suspend fun getItemForDetail(id: String): MediaItem = withMetadataContext(4_000L) {
        val params = mapOf("UserId" to userId,
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,Genres,ProductionYear,People,MediaSources,ProviderIds,ProductionLocations,OriginalLanguage")
        parseItem(getJson(url("/Users/$userId/Items/$id", params), timeoutSeconds = 4))
    }

    suspend fun getLocalTrailers(itemId: String): List<MediaItem> = withMetadataContext {
        catchingRequest {
            val request = Request.Builder()
                .url(url("/Items/$itemId/LocalTrailers"))
                .header("X-Emby-Authorization", authHeader())
                .build()
            http.readCancellable(request) { response ->
                if (!response.isSuccessful) throw EmbyException("HTTP ${response.code}")
                val trailers = JSONArray(response.body?.string().orEmpty())
                (0 until trailers.length()).mapNotNull { index ->
                    trailers.optJSONObject(index)?.let(::parseItem)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 换列表接口按 Ids 再取一次 People：部分服务器在单条目接口不回传 People（剧集尤甚）。 */
    suspend fun getItemPeople(itemId: String): List<MediaPerson> = withMetadataContext {
        catchingRequest {
            val params = mapOf(
                "UserId" to userId,
                "Ids" to itemId,
                "Fields" to "People",
                "Limit" to "1",
            )
            val arr = getJson(url("/Users/$userId/Items", params)).optJSONArray("Items") ?: JSONArray()
            parseItems(arr).firstOrNull()?.people.orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun getChapters(itemId: String): List<MediaChapter> = withMetadataContext {
        chapterCache[itemId]?.let { return@withMetadataContext it }
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

        val independent = catchingRequest {
            val request = Request.Builder()
                .url(url("/Items/$itemId/Chapters"))
                .header("X-Emby-Authorization", authHeader())
                .build()
            val body = http.readCancellable(request) { response ->
                if (!response.isSuccessful) throw EmbyException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val chapters = catchingRequest { JSONArray(body) }.getOrElse {
                JSONObject(body).optJSONArray("Items") ?: JSONObject(body).optJSONArray("Chapters") ?: JSONArray()
            }
            parseChapters(chapters)
        }.getOrDefault(emptyList())
        (independent + catchingRequest {
            val response = getJson(url("/Users/$userId/Items/$itemId", mapOf(
                "Fields" to "Chapters",
            )))
            parseChapters(response.optJSONArray("Chapters") ?: JSONArray())
        }.getOrDefault(emptyList()))
            .distinctBy { it.name to it.startMs }.sortedBy { it.startMs }
            .also { currentCoroutineContext().ensureActive(); chapterCache[itemId] = it }
    }

    suspend fun getPlaybackMarkers(itemId: String): PlaybackMarkers = withMetadataContext {
        catchingRequest {
            val response = getJson(url("/Users/$userId/Items/$itemId", mapOf(
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

    suspend fun getNextEpisode(item: MediaItem): MediaItem? = withMetadataContext {
        val seriesId = item.seriesId ?: return@withMetadataContext null
        val current = item.seasonId?.let { catchingRequest { getEpisodes(seriesId, it) }.getOrDefault(emptyList()) }.orEmpty()
        val orderedCurrent = current.sortedWith(compareBy<MediaItem> { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
        val currentIndex = orderedCurrent.indexOfFirst { it.id == item.id }
        if (currentIndex >= 0) orderedCurrent.getOrNull(currentIndex + 1)?.let { return@withMetadataContext it }

        val seasons = catchingRequest { getSeasons(seriesId) }.getOrDefault(emptyList())
            .sortedWith(compareBy<MediaItem> { it.indexNumber ?: it.parentIndexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
        val seasonIndex = seasons.indexOfFirst { it.id == item.seasonId }
        if (seasonIndex < 0) return@withMetadataContext null
        for (season in seasons.drop(seasonIndex + 1)) {
            val seasonEpisodes = catchingRequest { getEpisodes(seriesId, season.id) }.getOrDefault(emptyList())
                .sortedWith(compareBy<MediaItem> { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.id })
            seasonEpisodes.firstOrNull()?.let { return@withMetadataContext it }
        }
        return@withMetadataContext null
    }

    private fun playbackTargetKey(itemId: String, forceTranscode: Boolean, sourceId: String?, audioStreamIndex: Int?,
                                  forceDirect: Boolean, maxStreamingBitrate: Long, strategy: SourceStrategy,
                                  startIntent: PlaybackStartIntent, requestedPositionMs: Long,
                                  manualAudioSelection: Boolean = false, removeSubtitleParams: Boolean = false,
                                  preferDirectStreamUrl: Boolean = false,
                                  trackIntent: PlaybackTrackIntent) = TrackAwarePlaybackKey(PlaybackRequestKey(itemId, forceTranscode,
        trackIntent.sourceId, trackIntent.audioIndex, forceDirect, maxStreamingBitrate, strategy, startIntent, requestedPositionMs,
        manualAudioSelection, removeSubtitleParams, preferDirectStreamUrl), trackIntent)

    fun cachedPlaybackTarget(itemId: String, forceTranscode: Boolean, sourceId: String?, audioStreamIndex: Int?,
                             forceDirect: Boolean, maxStreamingBitrate: Long = 120_000_000L, strategy: SourceStrategy = SourceStrategy(),
                             startIntent: PlaybackStartIntent, requestedPositionMs: Long, manualAudioSelection: Boolean = false,
                             trackIntent: PlaybackTrackIntent): PlaybackTarget? {
        val key = playbackTargetKey(itemId, forceTranscode, sourceId, audioStreamIndex, forceDirect, maxStreamingBitrate,
            strategy, startIntent, requestedPositionMs, manualAudioSelection, trackIntent = trackIntent)
        val cached = playbackTargetCache.remove(key)
        val target = cached?.second?.takeIf { System.currentTimeMillis() - cached.first < 120_000L }
        if (target != null) {
            PageLog.add("prewarm hit nextId=$itemId")
        } else {
            val attempt = lastPrewarmAttempt?.takeIf { it.itemId == itemId }
            val reason = when {
                attempt == null -> "not_started"
                sourceId != attempt.sourceId || strategy != attempt.strategy -> "source_changed"
                attempt.state == "in_progress" -> "in_progress"
                attempt.state == "failed" -> "failed"
                attempt.state == "cancelled" -> "cancelled"
                cached != null -> "expired"
                key != attempt.requestKey -> "request_changed"
                else -> "consumed_or_unavailable"
            }
            PageLog.add("prewarm miss nextId=$itemId reason=$reason")
        }
        return target
    }

    suspend fun prewarmPlayback(item: MediaItem, sourceId: String? = null, audioStreamIndex: Int? = null,
                                strategy: SourceStrategy = SourceStrategy()) {
        val prepared = preparePlaybackTracks(item.id, sourceId, audioStreamIndex, strategy)
        val forceTranscode = com.lumina.tv.AppRuntime.prefs?.forceTranscode ?: false
        val maxBitrate = com.lumina.tv.AppRuntime.prefs?.maxStreamingBitrate ?: 120_000_000L
        val attempt = PrewarmAttempt(item.id, sourceId, strategy,
            playbackTargetKey(item.id, forceTranscode, sourceId, audioStreamIndex, false, maxBitrate, strategy,
                PlaybackStartIntent.Resume, item.positionTicks / 10_000L, trackIntent = prepared.intent))
        lastPrewarmAttempt = attempt
        try {
            resolvePlayback(item, forceTranscode = forceTranscode, maxStreamingBitrate = maxBitrate,
                mediaSourceId = sourceId, startIntent = PlaybackStartIntent.Resume,
                audioStreamIndex = audioStreamIndex, recordDiagnostics = false, cacheResult = true, strategy = strategy,
                preparedTracks = prepared)
            attempt.state = "done"
        } catch (cancelled: CancellationException) {
            attempt.state = "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            attempt.state = "failed"
            throw failure
        }
    }

    /** Returns a JPEG frame from Emby's optional BIF file; failures are intentionally silent. */
    suspend fun getTrickplayFrame(itemId: String, positionMs: Long): ByteArray? = withMetadataContext {
        try {
            if (synchronized(trickplayFiles) { itemId in unavailableTrickplay }) return@withMetadataContext null
            val file = trickplayMutex.withLock {
                synchronized(trickplayFiles) { trickplayFiles[itemId] } ?: if (synchronized(trickplayFiles) { itemId in unavailableTrickplay }) null else run {
                    val request = Request.Builder()
                        .url(url("/Videos/$itemId/index.bif"))
                        .header("X-Emby-Authorization", authHeader())
                        .build()
                    val bytes = http.readCancellable(request) { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty BIF")
                        if (body.contentLength() > MAX_TRICKPLAY_FILE_BYTES) throw IOException("BIF too large")
                        val output = ByteArrayOutputStream(minOf(body.contentLength().coerceAtLeast(0L), MAX_TRICKPLAY_FILE_BYTES).toInt().coerceAtLeast(8192))
                        body.byteStream().buffered().use { input ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_TRICKPLAY_FILE_BYTES) throw IOException("BIF too large")
                                output.write(buffer, 0, read)
                            }
                        }
                        output.toByteArray()
                    }
                    if (bytes.size < 72 || !bytes.copyOfRange(0, 8).contentEquals(BIF_MAGIC)) {
                        throw IOException("Invalid BIF header")
                    }
                    val count = readLeInt(bytes, 12)
                    val interval = readLeInt(bytes, 16)
                    if (count <= 0 || count > MAX_TRICKPLAY_FRAMES || interval <= 0 || 64L + (count + 1L) * 8L > bytes.size) {
                        throw IOException("Invalid BIF index")
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(trickplayFiles) {
                        while (trickplayBytes + bytes.size > MAX_TRICKPLAY_BYTES) {
                            val eldest = trickplayFiles.entries.iterator().let { if (it.hasNext()) it.next() else null } ?: break
                            trickplayBytes -= eldest.value.size
                            trickplayFiles.remove(eldest.key)
                            unavailableTrickplay.remove(eldest.key)
                        }
                        trickplayFiles[itemId] = bytes
                        trickplayBytes += bytes.size
                    }
                    bytes
                }
            } ?: return@withMetadataContext null
            val interval = readLeInt(file, 16)
            val frameIndex = (positionMs.coerceAtLeast(0L) / interval).toInt()
                .coerceAtMost(readLeInt(file, 12) - 1)
            synchronized(trickplayFrames) { trickplayFrames[itemId to frameIndex]?.let { return@withMetadataContext it } }
            val entryOffset = 64 + frameIndex * 8
            val start = readLeInt(file, entryOffset + 4)
            val end = if (frameIndex == readLeInt(file, 12) - 1) file.size else readLeInt(file, entryOffset + 12)
            if (start < 0 || end <= start || end > file.size) return@withMetadataContext null
            val jpeg = file.copyOfRange(start, end)
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return@withMetadataContext null
            currentCoroutineContext().ensureActive()
            synchronized(trickplayFrames) { trickplayFrames[itemId to frameIndex] = jpeg }
            jpeg
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(trickplayFiles) { unavailableTrickplay.add(itemId) }
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
    suspend fun getSimilarItems(itemId: String, limit: Int = 12): List<MediaItem> =
        withTimeoutOrNull(4_000L) { withMetadataContext(4_000L) {
            catchingRequest {
            val params = mapOf("UserId" to userId, "Limit" to limit.toString(),
                "Fields" to "Overview,PrimaryImageAspectRatio,Genres,ProductionYear,UserData,ProductionLocations,OriginalLanguage",
                "EnableImageTypes" to "Primary,Backdrop,Thumb,Logo")
            val arr = getJson(url("/Items/$itemId/Similar", params)).optJSONArray("Items") ?: JSONArray()
            parseItems(arr).filterNot { it.id == itemId }.take(limit)
            }.getOrDefault(emptyList())
        } } ?: emptyList()

    suspend fun getSeasons(seriesId: String): List<MediaItem> = withMetadataContext {
        val params = mapOf("UserId" to userId, "Fields" to "Overview,PrimaryImageAspectRatio,UserData")
        val arr = getJson(url("/Shows/$seriesId/Seasons", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String?): List<MediaItem> = withMetadataContext {
        val params = mutableMapOf(
            "UserId" to userId,
            "Fields" to "Overview,PrimaryImageAspectRatio,UserData,MediaSources",
        )
        if (seasonId != null) params["SeasonId"] = seasonId
        val arr = getJson(url("/Shows/$seriesId/Episodes", params)).optJSONArray("Items") ?: JSONArray()
        parseItems(arr)
    }

    suspend fun resolvePlayableItem(item: MediaItem): PlayableItemResult = resolvePlayableItem(item,
        loadEpisodes = { seriesId ->
            withMetadataContext {
                val episodes = mutableListOf<MediaItem>()
                val seen = mutableSetOf<String>()
                var start = 0
                do {
                    val page = getJson(url("/Shows/$seriesId/Episodes", mapOf(
                        "UserId" to userId, "Fields" to "UserData,MediaSources,IsMissing,LocationType",
                        "SortBy" to "ParentIndexNumber,IndexNumber", "SortOrder" to "Ascending",
                        "StartIndex" to start.toString(), "Limit" to "200",
                    )))
                    val items = parseItems(page.optJSONArray("Items") ?: JSONArray())
                    val fresh = items.filter { seen.add(it.id) }
                    if (items.isNotEmpty() && fresh.isEmpty()) throw EmbyException("单集列表未能完整加载，请重试")
                    episodes += fresh
                    start += items.size
                    val total = page.optInt("TotalRecordCount", -1)
                    if (items.isEmpty() || total >= 0 && start >= total || total < 0 && items.size < 200) break
                } while (true)
                episodes
            }
        },
        isPlayable = { episode -> episode.sourceCount > 0 || getMediaSources(episode.id).isNotEmpty() },
    )

    private fun parseItems(arr: JSONArray): List<MediaItem> =
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseItem(it) } }

    private fun indexedTags(array: JSONArray): Map<Int, String> =
        (0 until array.length()).mapNotNull { index ->
            if (array.isNull(index)) null else array.optString(index).ifBlank { null }?.let { index to it }
        }.toMap()

    private fun indexedTags(obj: JSONObject): Map<Int, String> =
        obj.keys().asSequence().mapNotNull { key ->
            key.toIntOrNull()?.takeIf { it >= 0 && !obj.isNull(key) }?.let { index ->
                obj.optString(key).ifBlank { null }?.let { index to it }
            }
        }.toMap()

    private fun parseItem(o: JSONObject): MediaItem {
        val ud = o.optJSONObject("UserData")
        val tags = o.optJSONObject("ImageTags")
        val backdropTags: Map<Int, String> = run {
            val arr = o.optJSONArray("BackdropImageTags")
            if (arr != null) {
                indexedTags(arr)
            } else {
                o.optJSONObject("BackdropImageTags")?.let(::indexedTags) ?: emptyMap()
            }
        }
        val parentBackdropTags: Map<Int, String> = run {
            val arr = o.optJSONArray("ParentBackdropImageTags")
            if (arr != null) {
                indexedTags(arr)
            } else {
                o.optJSONObject("ParentBackdropImageTags")?.let(::indexedTags) ?: emptyMap()
            }
        }
        val parentBackdropItemId = o.optString("ParentBackdropItemId").takeUnless { it.isBlank() || it == "null" }
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
            contentVersion = previewContentVersion(o),
            id = o.optString("Id"),
            name = o.optString("Name"),
            type = o.optString("Type"),
            imageTag = tags?.optString("Primary")?.ifBlank { null },
            backdropTag = backdropTags[0],
            backdropTags = backdropTags,
            parentBackdropItemId = parentBackdropItemId,
            parentBackdropTags = parentBackdropTags,
            logoTag = tags?.optString("Logo")?.ifBlank { null },
            // 请求了 Fields=MediaSources 才有值，用来判断"这一集有几个版本"
            sourceCount = o.optJSONArray("MediaSources")?.length() ?: 0,
            isMissing = o.optBoolean("IsMissing", false) || o.optBoolean("IsVirtualItem", false) ||
                o.optString("LocationType").equals("Virtual", true),
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
            productionLocations = o.optJSONArray("ProductionLocations")?.let { locations ->
                (0 until locations.length()).mapNotNull { i -> locations.optString(i).trim().takeIf(String::isNotBlank) }
            } ?: emptyList(),
            originalLanguage = o.optString("OriginalLanguage").trim().ifBlank { null },
            lastPlayedDate = ud?.optString("LastPlayedDate")?.let { raw ->
                catchingRequest { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            },
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

    fun libraryPrimaryImageUrl(id: String): String = url("/Items/$id/Images/Primary", mapOf("maxHeight" to "360", "quality" to "90"))

    fun libraryBackdropImageUrl(id: String): String = url("/Items/$id/Images/Backdrop", mapOf("maxWidth" to "640", "quality" to "90"))

    fun personImageUrl(person: MediaPerson, maxHeight: Int = 320): String? {
        val id = person.id ?: return null
        val tag = person.imageTag ?: return null
        return url("/Items/$id/Images/Primary", mapOf("maxHeight" to maxHeight.toString(), "tag" to tag, "quality" to "85"))
    }

    fun backdropUrl(item: MediaItem, maxWidth: Int = 1920, quality: Int = 80): String? {
        return backdropUrl(item, 0, maxWidth, quality)
    }

    fun backdropUrl(item: MediaItem, index: Int, maxWidth: Int, quality: Int = 80): String? {
        val tag = item.backdropTags[index] ?: item.backdropTag?.takeIf { index == 0 }
        if (tag == null) return null
        return url("/Items/${item.id}/Images/Backdrop/$index", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to quality.toString()))
    }

    fun backdropUrlUntagged(id: String, index: Int, maxWidth: Int, quality: Int = 80): String {
        require(index >= 0)
        return url("/Items/$id/Images/Backdrop/$index", mapOf("maxWidth" to maxWidth.toString(), "quality" to quality.toString()))
    }

    /** Indexed Hero stills: use the existing authenticated image URL, not an extra metadata request. */
    fun heroStillUrl(id: String, index: Int, maxWidth: Int = 1920, quality: Int = 80): String {
        require(index in 1..3)
        return url("/Items/$id/Images/Backdrop/$index", mapOf("maxWidth" to maxWidth.toString(), "quality" to quality.toString()))
    }

    /** 透明标题图（Emby Logo 图），没有就返回 null 由调用方退回文字标题。 */
    fun logoUrl(item: MediaItem, maxWidth: Int = 900): String? {
        val tag = item.logoTag ?: return null
        return url("/Items/${item.id}/Images/Logo", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to "90"))
    }

    fun backdropUrl(id: String, tag: String?, maxWidth: Int = 1920, quality: Int = 80): String? {
        if (tag == null) return null
        return url("/Items/$id/Images/Backdrop/0", mapOf("maxWidth" to maxWidth.toString(), "tag" to tag, "quality" to quality.toString()))
    }

    /** Resolves a detail backdrop using validated HLS, direct stream, then the controlled static fallback. */
    suspend fun resolveDetailPreviewUrl(
        itemId: String,
        mediaSourceId: String,
        mode: String = "auto",
        fromStage: String = "hls",
    ): DetailPreviewResolution =
        withMetadataContext {
            if (mode == "off") {
                return@withMetadataContext DetailPreviewResolution(null, null, listOf("disabled"))
            }
            val startAt = listOf("hls", "direct", "static", "done").indexOf(fromStage).takeIf { it >= 0 } ?: 0
            val failures = mutableListOf<String>()
            val source = catchingRequest { getMediaSources(itemId).firstOrNull { it.id == mediaSourceId } }.getOrNull()
            val hls = url("/Videos/$itemId/master.m3u8", mapOf(
                "MediaSourceId" to mediaSourceId,
                "DeviceId" to deviceId,
                "VideoCodec" to "h264",
                "AudioCodec" to "aac",
                "MaxAudioChannels" to "2",
                "MaxWidth" to "1280",
                "MaxHeight" to "720",
                "VideoBitrate" to "2000000",
                "SegmentContainer" to "ts",
                "MinSegments" to "1",
                "BreakOnNonKeyFrames" to "true",
                "StartTimeTicks" to "0",
            ))
            if (startAt <= 0) {
                val hlsResult = probeDetailPreview("hls", hls)
                if (hlsResult.valid) return@withMetadataContext DetailPreviewResolution(hls, "hls", failures)
                failures += "hls:${hlsResult.verdict}"
            }
            if (mode == "720p" || startAt >= 3) {
                return@withMetadataContext DetailPreviewResolution(null, null, failures)
            }

            val direct = absUrl(source?.directStreamUrl)
            if (startAt <= 1) {
                if (direct == null) {
                    logDetailPreview("direct", "0", "none", "missing-url", null)
                    failures += "direct:missing-url"
                } else {
                    val headers = source?.requiredHttpHeaders.orEmpty()
                    val directResult = probeDetailPreview("direct", direct, headers)
                    if (directResult.valid) return@withMetadataContext DetailPreviewResolution(direct, "direct", failures, headers)
                    failures += "direct:${directResult.verdict}"
                }
            }

            if (startAt <= 2 && mode == "auto") {
                val staticUrl = url("/Videos/$itemId/stream", mapOf("static" to "true", "MediaSourceId" to mediaSourceId))
                val staticResult = probeDetailPreview("static", staticUrl)
                if (staticResult.valid) return@withMetadataContext DetailPreviewResolution(staticUrl, "static", failures)
                failures += "static:${staticResult.verdict}"
            }
            DetailPreviewResolution(null, null, failures)
        }

    private data class DetailPreviewProbe(val valid: Boolean, val verdict: String)

    private suspend fun probeDetailPreview(stage: String, target: String, requiredHttpHeaders: Map<String, String> = emptyMap()): DetailPreviewProbe = try {
        val request = Request.Builder().url(target).header("Range", "bytes=0-511").apply {
            previewHeaders(target, requiredHttpHeaders).forEach { (name, value) -> header(name, value) }
        }.get().build()
        // The network interceptor rechecks scheme/host/port on EVERY redirect.
        previewHttpClient.readCancellable(request) { response ->
            PageLog.add("detailPreviewAuth stage=$stage sameOrigin=${isSameOrigin(baseUrl, response.request.url.toString())} credential=${response.request.header("X-Emby-Authorization") != null}")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase().ifBlank { "none" }
            val prefix = response.body?.source()?.let { source ->
                val buffer = okio.Buffer()
                while (buffer.size < 512L) {
                    val count = source.read(buffer, 512L - buffer.size)
                    if (count == -1L) break
                }
                buffer.readByteArray().toString(Charsets.ISO_8859_1).removePrefix("\uFEFF").trimStart()
            }.orEmpty()
            val isHls = catchingRequest { Uri.parse(target).path.orEmpty().endsWith(".m3u8", true) }.getOrDefault(false)
            val verdict = when {
                !response.isSuccessful -> "http-${response.code}"
                isHls && !prefix.contains("#EXTM3U", true) -> "not-m3u8"
                !isHls && !(contentType.startsWith("video/") || contentType == "application/octet-stream") -> "not-video"
                else -> "ok"
            }
            logDetailPreview(stage, response.code.toString(), contentType, verdict, target)
            DetailPreviewProbe(verdict == "ok", verdict)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        logDetailPreview(stage, "0", "none", "error-${e.javaClass.simpleName}", target)
        DetailPreviewProbe(false, "error")
    }

    private fun logDetailPreview(stage: String, status: String, contentType: String, verdict: String, target: String?) {
        val host = catchingRequest { Uri.parse(target).host.orEmpty() }.getOrDefault("")
        val safeHost = if (host.isBlank()) "none" else "hash-${host.hashCode().toUInt().toString(16)}"
        com.lumina.tv.diag.PageLog.add("detailPreview stage=$stage status=$status contentType=$contentType verdict=$verdict host=$safeHost")
    }

    private fun isSafeLowRiskSource(source: JSONObject): Boolean {
        val streams = source.optJSONArray("MediaStreams") ?: return false
        val video = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }
            .firstOrNull { it.optString("Type").equals("Video", true) } ?: return false
        val bitrate = video.optLong("BitRate", -1L)
        val width = video.optInt("Width", -1)
        val height = video.optInt("Height", -1)
        if (bitrate !in 1..8_000_000L || width !in 1..1920 || height !in 1..1080) return false
        val range = listOf("VideoRangeType", "VideoRange").joinToString(" ") { video.optString(it) }
        if (!isExplicitSdr(range) || video.has("DvProfile") && !video.isNull("DvProfile")) return false
        val dvPattern = Regex("(?i)dv(?:he|h1|i)\\.?[0-9.]*")
        val markers = listOf("Codec", "CodecTag", "Profile", "CodecTagString").joinToString(" ") { video.optString(it) }
        return !dvPattern.containsMatchIn(markers) && !markers.contains("dolby vision", true)
    }

    private fun isSafeLowRiskSource(source: MediaSource): Boolean {
        val streamArray = JSONArray().apply {
            source.streams.forEach { stream ->
                put(JSONObject().put("Type", stream.type).put("BitRate", stream.bitRate ?: -1L)
                    .put("Width", stream.width ?: -1).put("Height", stream.height ?: -1)
                    .put("VideoRangeType", stream.videoRangeType ?: "").put("DvProfile", stream.dvProfile ?: JSONObject.NULL)
                    .put("Codec", stream.codec ?: "").put("Profile", stream.profile ?: ""))
            }
        }
        return isSafeLowRiskSource(JSONObject().put("MediaStreams", streamArray))
    }

    private fun isExplicitSdr(value: String?): Boolean {
        val normalized = value?.trim()?.uppercase().orEmpty()
        return normalized.isNotEmpty() && (normalized == "SDR" || normalized == "STANDARD") &&
            listOf("HDR", "PQ", "HLG", "DOVI", "DV").none(normalized::contains)
    }

    private fun absUrl(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank()) return null
        return if (candidate.startsWith("http", true)) candidate
        else requireNotNull(parseServerAddress(baseUrl)).resolve(candidate)
    }

    // ---------------- 播放 ----------------

    data class PlaybackTarget(
        val url: String,
        val mode: PlaybackMode,
        val source: MediaSource,
        /** Content position, never a player-relative timestamp. */
        val startPositionMs: Long,
        val playSessionId: String?,
        val audioStreamIndex: Int?,
        var streamStartOffsetMs: Long?,
        val timelineOrigin: TimelineOrigin,
        val headers: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val negotiationDiagnostics: JSONObject = JSONObject(),
        val originalFile: Boolean = false,
        val trackIntent: PlaybackTrackIntent? = null,
        val isStrmDirectLink: Boolean = false,
    ) {
        val direct: Boolean get() = mode == PlaybackMode.DirectPlay
        val playMethod: String get() = mode.name
        internal var timeline = TimelineCalibrationWindow(startPositionMs, durationMs)
            private set
        internal fun resetTimeline() { timeline = TimelineCalibrationWindow(startPositionMs, durationMs) }
        fun playerToContentPositionMs(playerMs: Long): Long? = timeline.calibration.playerToContentPositionMs(playerMs)
        fun contentToPlayerPositionMs(contentMs: Long): Long? = timeline.calibration.contentToPlayerPositionMs(contentMs)
        // An initial STRM seek is an unverified attempt, not evidence for offset=0.
        // Negotiated remux/transcode URLs already carry StartTimeTicks and get no additional seek here.
        val playerStartPositionMs: Long? get() = contentToPlayerPositionMs(startPositionMs)
            ?: startPositionMs.takeIf { timelineOrigin == TimelineOrigin.CONTENT || isStrmDirectLink }
    }

    /** 解析单个 MediaSource JSON 为模型（含大小与分辨率）。 */
    internal fun parseMediaSource(o: JSONObject): MediaSource {
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
                    dvProfile = parseDvProfile(s),
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
            contentVersion = previewContentVersion(o),
            id = o.optString("Id"),
            name = o.optString("Name", "源").ifBlank { "源" },
            container = normalizeContainer(o.optString("Container")),
            defaultAudioStreamIndex = o.optInt("DefaultAudioStreamIndex", -1).takeIf { it >= 0 },
            defaultSubtitleStreamIndex = o.optInt("DefaultSubtitleStreamIndex", -1).takeIf { o.has("DefaultSubtitleStreamIndex") && !o.isNull("DefaultSubtitleStreamIndex") && it >= -1 },
            supportsDirectPlay = o.optBoolean("SupportsDirectPlay", true),
            supportsDirectStream = o.optBoolean("SupportsDirectStream", true),
            streams = streams,
            size = o.optLong("Size", 0L),
            bitrate = o.optLong("Bitrate", 0L).takeIf { it > 0 },
            resolution = resolution,
            directStreamUrl = o.optString("DirectStreamUrl").ifBlank { null },
            additionalOptions = o.optString("AdditionalOptions").ifBlank { null },
            requiredHttpHeaders = o.optJSONObject("RequiredHttpHeaders")?.let { headers ->
                buildMap { headers.keys().forEach { name -> put(name, headers.optString(name)) } }
            }.orEmpty(),
        )
    }

    /** 拉取全部可选源（选源菜单用）。 */
    suspend fun getMediaSources(itemId: String): List<MediaSource> = withMetadataContext {
        catchingRequest {
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
            val initial = parseSources(postJson(playbackUrl, playbackInfoBody(false).toString()))
            if (initial.any { source -> source.streams.any { it.type == "Video" } }) initial
            else {
                val retried = catchingRequest {
                    parseSources(postJson(playbackUrl, playbackInfoBody(true).toString()))
                }.getOrDefault(emptyList())
                retried.ifEmpty { initial }
            }
        }.getOrDefault(emptyList())
    }

    /** 读取纯文本响应：.strm 内容是文本，不能按 JSON 解析 */
    private suspend fun getText(target: String): String? = catchingRequest {
        val req = Request.Builder().url(target)
            .header("X-Emby-Authorization", authHeader())
            .build()
        http.readCancellable(req) { resp ->
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

    private fun trackIntentFor(itemId: String, source: MediaSource, explicitAudio: Int? = null): PlaybackTrackIntent {
        val prefs = com.lumina.tv.AppRuntime.prefs
        return resolvePlaybackTrackIntent(source, prefs?.audioTrackFor(itemId, source),
            prefs?.subtitlePreferenceFor(itemId, source), prefs?.preferredAudioLanguage,
            prefs?.preferredSubtitleLanguage, explicitAudio)
    }

    suspend fun preparePlaybackTracks(itemId: String, sourceId: String?, audioIndex: Int?,
        strategy: SourceStrategy = SourceStrategy(), audioIdentity: String? = null): PreparedPlaybackTracks {
        val sources = getMediaSources(itemId)
        if (sources.isEmpty()) throw EmbyException("没有可用片源")
        val preferredSourceId = sourceId ?: com.lumina.tv.AppRuntime.prefs?.lastSourceFor(itemId)
        val selected = sources.firstOrNull { it.id == preferredSourceId }
            ?: if (sourceId != null) throw EmbyException("所选版本已不可用，请重新选择")
            else sources[chooseSource(sources.mapIndexed { index, source -> source.toSourceOption(index) }, strategy).index]
        return PreparedPlaybackTracks(selected, trackIntentFor(itemId, selected,
            audioIndex.takeIf { sourceId == selected.id && audioIdentity ==
                com.lumina.tv.player.trackMemoryIdentity(selected, "Audio") }), sources)
    }

    suspend fun resolvePlayback(
        item: MediaItem,
        forceTranscode: Boolean = false,
        mediaSourceId: String? = null,
        startIntent: PlaybackStartIntent = PlaybackStartIntent.Resume,
        audioStreamIndex: Int? = null,
        forceDirect: Boolean = false,
        removeSubtitleParams: Boolean = false,
        preferDirectStreamUrl: Boolean = false,
        manualAudioStreamSelection: Boolean = false,
        maxStreamingBitrate: Long = 120_000_000L,
        recordDiagnostics: Boolean = true,
        cacheResult: Boolean = false,
        strategy: SourceStrategy = SourceStrategy(),
        preparedTracks: PreparedPlaybackTracks? = null,
        audioIdentity: String? = null,
    ): PlaybackTarget =
        withMetadataContext {
            val startPositionMs = startIntent.positionMs(item.positionTicks / 10_000L)
            val prepared = preparedTracks ?: preparePlaybackTracks(item.id, mediaSourceId, audioStreamIndex, strategy, audioIdentity)
            var negotiationSource = prepared.source
            var trackIntent = prepared.intent
            val infoBody = JSONObject()
                .put("UserId", userId)
                .put("StartTimeTicks", startPositionMs.coerceAtLeast(0L) * 10_000)
                .put("IsPlayback", true)
                .put("AutoOpenLiveStream", false)
                .put("MaxStreamingBitrate", maxStreamingBitrate.coerceIn(20_000_000L, 120_000_000L))
                .put("DeviceProfile", deviceProfile())
                .apply {
                    PlaybackRequestFlag.values().filter { it.send }.forEach { flag ->
                        put(flag.key, flag.value)
                    }
                }
            if (forceTranscode) {
                infoBody.put("EnableDirectPlay", false).put("EnableDirectStream", false)
                    .put("AllowVideoStreamCopy", false).put("AllowAudioStreamCopy", false)
            }
            val playbackInfoParams = mapOf("UserId" to userId)
            var info = JSONObject()
            var first: JSONObject? = null
            val negotiatedSources = mutableSetOf<String>()
            while (true) {
                if (!negotiatedSources.add(negotiationSource.id) || negotiatedSources.size > 3) {
                    throw EmbyException("服务器反复变更播放版本，请重新选择片源")
                }
                trackIntent.applyToPlaybackInfo(infoBody, disableSubtitles = removeSubtitleParams)
                info = postJson(url("/Items/${item.id}/PlaybackInfo", playbackInfoParams), infoBody.toString())
                    .also { if (recordDiagnostics) lastPlaybackInfoJson = it.toString() }
                val sources = info.optJSONArray("MediaSources")
                val responseSources = (0 until (sources?.length() ?: 0)).mapNotNull { sources?.optJSONObject(it) }
                first = responseSources.firstOrNull { it.optString("Id") == negotiationSource.id }
                    ?: responseSources.firstOrNull() ?: throw EmbyException("PlaybackInfo returned no usable sources")
                val returnedId = first.optString("Id").takeIf { it.isNotBlank() }
                    ?: throw EmbyException("PlaybackInfo source is missing its identity")
                if (returnedId == negotiationSource.id) break
                // A source change invalidates numeric choices; re-resolve from that source's inventory.
                negotiationSource = getMediaSources(item.id).firstOrNull { it.id == returnedId }
                    ?: throw EmbyException("服务器返回的新版本已不可用")
                trackIntent = trackIntentFor(item.id, negotiationSource)
                PageLog.add("trackNegotiation action=source-changed retry=${negotiatedSources.size}")
            }
            PageLog.add("trackNegotiation audio=${trackIntent.audioIndex} subtitle=${trackIntent.subtitleMode}/${trackIntent.requestedSubtitleIndex}")
            val responsePositionTicks = info.optJSONObject("UserData")
                ?.optLong("PlaybackPositionTicks", -1L)
                ?: info.optJSONObject("Item")?.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", -1L)
                ?: first?.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", -1L)
                ?: -1L
            val resolvedStartPositionMs = startIntent.positionMs(item.positionTicks / 10_000L,
                responsePositionTicks.takeIf { it >= 0L }?.div(10_000L))
            val msId = first?.optString("Id")?.takeIf { it.isNotBlank() }
                ?: throw EmbyException("PlaybackInfo source is missing its identity")
            val responseSource = parseMediaSource(requireNotNull(first))
            // Keep original source identities even if the output inventory has been reduced.
            val source = responseSource.copy(streams = negotiationSource.streams,
                defaultAudioStreamIndex = negotiationSource.defaultAudioStreamIndex,
                defaultSubtitleStreamIndex = negotiationSource.defaultSubtitleStreamIndex)
            val selectedAudioIndex = trackIntent.audioIndex
            val playSessionId = info.optString("PlaySessionId").takeIf { it.isNotBlank() }
            val directUrl = url("/Videos/${item.id}/stream", mapOf(
                "static" to "true", "MediaSourceId" to msId, "DeviceId" to deviceId,
                "PlaySessionId" to playSessionId,
            ))
            val transcodingUrl = usablePlaybackUrl(first.optString("TranscodingUrl"))
            val serverDirectUrl = usablePlaybackUrl(first.optString("DirectStreamUrl"))
            val isStrmItem = isStrmSource(first)
            val strmText = if (isStrmItem && serverDirectUrl == null && !forceTranscode) {
                getText(url("/Items/${item.id}/Download", mapOf("UserId" to userId)))
            } else null
            val directLinkUrl = serverDirectUrl ?: extractStrmUrl(strmText)?.let(::usablePlaybackUrl)
            // One selected route owns URL, mode and timeline together. No synthetic HLS fallback.
            val route = when {
                forceTranscode -> {
                    if (transcodingUrl == null || first.optString("PlayMethod").equals("DirectStream", true)) {
                        throw EmbyException("Forced transcoding is unavailable for the selected source")
                    }
                    Triple(transcodingUrl, PlaybackMode.Transcode, TimelineOrigin.UNKNOWN)
                }
                isStrmItem -> Triple(directLinkUrl ?: throw EmbyException("STRM has no usable HTTP URL"),
                    PlaybackMode.DirectPlay, TimelineOrigin.UNKNOWN)
                forceDirect -> Triple(directUrl, PlaybackMode.DirectPlay, TimelineOrigin.CONTENT)
                preferDirectStreamUrl && serverDirectUrl != null && source.supportsDirectStream ->
                    Triple(serverDirectUrl, PlaybackMode.DirectStream, TimelineOrigin.UNKNOWN)
                source.supportsDirectPlay -> Triple(directUrl, PlaybackMode.DirectPlay, TimelineOrigin.CONTENT)
                source.supportsDirectStream && serverDirectUrl != null ->
                    Triple(serverDirectUrl, PlaybackMode.DirectStream, TimelineOrigin.UNKNOWN)
                transcodingUrl != null -> Triple(transcodingUrl,
                    if (first.optString("PlayMethod").equals("DirectStream", true)) PlaybackMode.DirectStream else PlaybackMode.Transcode,
                    TimelineOrigin.UNKNOWN)
                else -> throw EmbyException("The selected source has no usable negotiated playback route")
            }
            val finalUrl = if (route.third == TimelineOrigin.CONTENT || isStrmItem && !forceTranscode) route.first
                else negotiatedUrl(route.first, resolvedStartPositionMs, selectedAudioIndex, playSessionId, removeSubtitleParams)
            val finalPath = android.net.Uri.parse(finalUrl).path.orEmpty()
            android.util.Log.d("EmbyClient", "playback: isStrmItem=$isStrmItem hasDirectLink=${directLinkUrl != null} final=$finalPath")
            // 请求头：#EXTVLCOPT / #EXTHTTP 既可能来自 MediaSources.AdditionalOptions，也可能在 .strm 文本里
            val sourceHeaders = first?.optJSONObject("RequiredHttpHeaders")
                ?.let { json -> buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } } }
                .orEmpty()
            val strmHeaders = buildMap {
                putAll(parseStrmOptions(source.additionalOptions))
                putAll(parseStrmOptions(strmText))
            }
            val headers = linkedMapOf<String, String>().apply {
                putAll(strmHeaders)
                sourceHeaders.forEach { (name, value) ->
                    keys.filter { it.equals(name, ignoreCase = true) }.forEach(::remove)
                    put(name, value)
                }
            }
            if (sourceHeaders.isNotEmpty()) {
                android.util.Log.d("EmbyClient", "playback headers from source: names=${sourceHeaders.keys.sorted()} count=${sourceHeaders.size}")
            }
            PlaybackTarget(
                url = finalUrl,
                mode = route.second,
                source = source,
                startPositionMs = resolvedStartPositionMs,
                playSessionId = playSessionId,
                audioStreamIndex = selectedAudioIndex,
                trackIntent = trackIntent,
                originalFile = route.second == PlaybackMode.DirectPlay && !isStrmItem && route.first == directUrl,
                isStrmDirectLink = isStrmItem && !forceTranscode,
                // Runtime calibration replaces this diagnostic value after the first frame.
                streamStartOffsetMs = if (route.third == TimelineOrigin.CONTENT) 0L else null,
                timelineOrigin = route.third,
                headers = previewHeaders(finalUrl, headers).toMap(),
                durationMs = first.optLong("RunTimeTicks", item.runtimeTicks ?: 0L).takeIf { it > 0L }?.div(10_000L),
                negotiationDiagnostics = JSONObject()
                    .put("request", JSONObject().apply {
                        // 如实记录：这 5 个布尔当前没发给服务器，就记 null，不假装 true
                        listOf("EnableDirectPlay", "EnableDirectStream", "EnableTranscoding",
                            "AllowVideoStreamCopy", "AllowAudioStreamCopy").forEach { key ->
                            put(key, if (infoBody.has(key)) infoBody.opt(key) else JSONObject.NULL)
                        }
                        put("MaxStreamingBitrate", infoBody.opt("MaxStreamingBitrate") ?: JSONObject.NULL)
                        put("DeviceProfilePresent", infoBody.has("DeviceProfile"))
                        put("AudioStreamIndex", infoBody.opt("AudioStreamIndex") ?: JSONObject.NULL)
                        put("SubtitleStreamIndex", infoBody.opt("SubtitleStreamIndex") ?: JSONObject.NULL)
                    })
                    .put("server", JSONObject().put("playMethod", first?.optString("PlayMethod")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        .put("transcodingUrlType", when {
                            first?.optString("TranscodingUrl").orEmpty().isBlank() -> "none"
                            first?.optString("TranscodingUrl").orEmpty().contains(".m3u8", true) -> "hls"
                            else -> "static"
                        }).put("transcodeReasons", first?.optJSONArray("TranscodeReasons") ?: JSONArray()))
                    .put("selectedAudioIndex", selectedAudioIndex ?: JSONObject.NULL)
                    .put("mode", route.second.name).put("timelineOrigin", route.third.name)
                    .put("streamStartOffsetMs", if (route.third == TimelineOrigin.CONTENT) 0L else JSONObject.NULL)
                    .put("selectedSubtitleIndex", trackIntent.requestedSubtitleIndex ?: JSONObject.NULL)
                    .put("subtitleIntent", trackIntent.subtitleMode.name),
            ).also { target ->
                currentCoroutineContext().ensureActive()
                if (cacheResult && first != null && target.url.isNotBlank()) playbackTargetCache[playbackTargetKey(item.id, forceTranscode, mediaSourceId, audioStreamIndex, forceDirect, maxStreamingBitrate,
                        strategy, startIntent, startPositionMs, manualAudioStreamSelection, removeSubtitleParams, preferDirectStreamUrl, trackIntent)] =
                    System.currentTimeMillis() to target
            }
        }

    private fun deviceProfile(): JSONObject = JSONObject()
        .put("DirectPlayProfiles", JSONArray()
            .put(JSONObject().put("Type", "Video").put("Container", "mkv,mp4,m4v,ts,mov")
                .put("VideoCodec", "h264,hevc").put("AudioCodec", if (com.lumina.tv.AppRuntime.prefs?.declareDtsDirectPlay == true)
                    "aac,ac3,eac3,mp3,opus,flac,vorbis,dts,truehd" else "aac,ac3,eac3,mp3,opus,flac,vorbis"))
            .put(JSONObject().put("Type", "Audio").put("Container", "mp3").put("AudioCodec", "mp3")))
        .put("SubtitleProfiles", JSONArray()
            .put(JSONObject().put("Format", "srt").put("Method", "External"))
            .put(JSONObject().put("Format", "subrip").put("Method", "External"))
            .put(JSONObject().put("Format", "vtt").put("Method", "External"))
            .put(JSONObject().put("Format", "webvtt").put("Method", "External")))
        .put("TranscodingProfiles", JSONArray()
            .put(JSONObject().put("Container", "ts").put("Type", "Video").put("VideoCodec", "h264")
                .put("AudioCodec", "aac").put("Protocol", "hls").put("MaxAudioChannels", "2"))
            .put(JSONObject().put("Container", "ts").put("Type", "Audio").put("AudioCodec", "aac")
                .put("Protocol", "hls").put("MaxAudioChannels", "2")))

    private fun usablePlaybackUrl(raw: String?): String? = catchingRequest { absUrl(raw)?.takeIf { value ->
        val uri = java.net.URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } }.getOrNull()

    private fun negotiatedUrl(target: String, contentStartMs: Long, audioIndex: Int?, sessionId: String?, removeSubtitles: Boolean): String {
        val uri = Uri.parse(target)
        val builder = uri.buildUpon().clearQuery()
        val replaced = setOf("starttimeticks", "audiostreamindex", "playsessionid") +
            if (removeSubtitles) setOf("subtitlestreamindex", "subtitlemethod") else emptySet()
        uri.queryParameterNames.filterNot { it.lowercase() in replaced }.forEach { key ->
            uri.getQueryParameters(key).forEach { builder.appendQueryParameter(key, it) }
        }
        builder.appendQueryParameter("StartTimeTicks", (contentStartMs * 10_000L).toString())
        audioIndex?.let { builder.appendQueryParameter("AudioStreamIndex", it.toString()) }
        sessionId?.let { builder.appendQueryParameter("PlaySessionId", it) }
        return builder.build().toString()
    }

    fun subtitleUrl(itemId: String, mediaSourceId: String, index: Int, format: String = "srt"): String =
        url("/Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.$format")

    // ---------------- 收藏 / 已看 ----------------

    private val userDataMutationMutex = Mutex()

    suspend fun setFavorite(itemId: String, favorite: Boolean): Result<Boolean> =
        setUserDataFlag(itemId, "FavoriteItems", "IsFavorite", favorite)

    suspend fun setPlayed(itemId: String, played: Boolean): Result<Boolean> =
        setUserDataFlag(itemId, "PlayedItems", "Played", played)
            .onSuccess { DetailRecommendationCache.watchStateChanged(baseUrl, userId) }

    private suspend fun setUserDataFlag(itemId: String, endpoint: String, field: String, target: Boolean): Result<Boolean> =
        userDataMutationMutex.withLock {
            withMetadataContext {
                try {
                    val resp = if (target) {
                        postJson(url("/Users/$userId/$endpoint/$itemId"), "{}")
                    } else {
                        val req = Request.Builder().url(url("/Users/$userId/$endpoint/$itemId"))
                            .header("X-Emby-Authorization", authHeader())
                            .delete()
                            .build()
                        http.readCancellable(req) { r ->
                            if (!r.isSuccessful) throw EmbyException("HTTP ${r.code}", r.code)
                            JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                        }
                    }
                    // Emby versions return either UserItemDataDto at the root or a wrapped UserData.
                    // Empty 2xx responses require a read-back; never invent the requested state.
                    val actual = userDataFlag(resp, field)
                        ?: userDataFlag(getJson(url("/Users/$userId/Items/$itemId")), field)
                        ?: throw EmbyException("响应缺少 $field，无法确认状态")
                    Result.success(actual)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
        }

    private fun userDataFlag(json: JSONObject, field: String): Boolean? {
        fun read(data: JSONObject): Boolean? = when (val value = data.opt(field)) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
        return json.optJSONObject("UserData")?.let(::read) ?: read(json)
    }

    // ---------------- 进度上报 ----------------

    fun reportStart(
        itemId: String,
        positionMs: Long,
        sourceId: String?,
        playMethod: String,
        playSessionId: String?,
        paused: Boolean = false,
    ): PlaybackProgressSession = progressSender.start(itemId, positionMs, sourceId, playSessionId, playMethod, paused)

    // Local diagnostic metadata only. It never enters PendingProgress, the outbox or the Emby body.
    @Volatile var lastProgressDiagnostics: JSONObject = JSONObject()
        private set

    fun reportProgress(session: PlaybackProgressSession, positionMs: Long, paused: Boolean,
        timelineUnverified: Boolean = false, playerPositionMs: Long? = null): Boolean {
        val admitted = progressSender.progress(session, positionMs, paused)
        if (admitted) recordProgressDiagnostics("Progress", positionMs, timelineUnverified, playerPositionMs)
        return admitted
    }

    fun reportStop(session: PlaybackProgressSession, positionMs: Long, paused: Boolean = true,
        timelineUnverified: Boolean = false, playerPositionMs: Long? = null): Boolean {
        val admitted = progressSender.stop(session, positionMs, paused)
        if (admitted) recordProgressDiagnostics("Stop", positionMs, timelineUnverified, playerPositionMs)
        return admitted
    }

    private fun recordProgressDiagnostics(event: String, positionMs: Long, unverified: Boolean, playerMs: Long?) {
        lastProgressDiagnostics = JSONObject().put("event", event).put("positionMs", positionMs)
            .put("contentPos", if (unverified) JSONObject.NULL else positionMs)
            .put("playerPos", playerMs ?: JSONObject.NULL).put("timelineUnverified", unverified)
    }

    /** The sole HTTP entry for Start/Progress/Stop, invoked only by the serial sender. */
    private suspend fun sendProgressEvent(event: PendingProgress): Boolean = catchingRequest {
        val path = when (event.eventType) {
            ProgressEventType.Start -> "/Sessions/Playing"
            ProgressEventType.Progress -> "/Sessions/Playing/Progress"
            ProgressEventType.Stop -> "/Sessions/Playing/Stopped"
        }
        val body = JSONObject()
            .put("ItemId", event.itemId)
            .put("PositionTicks", event.positionMs * 10_000L)
            .put("IsPaused", event.paused)
            .put("PlayMethod", event.playMethod)
            .put("MediaSourceId", event.sourceId ?: event.itemId)
        event.playSessionId?.let { body.put("PlaySessionId", it) }
        if (event.eventType != ProgressEventType.Stop) body.put("CanSeek", true)
        postJson(url(path), body.toString())
    }.onSuccess {
        DetailRecommendationCache.watchStateChanged(baseUrl, userId)
    }.onFailure {
        lastApiFailure = "report${event.eventType}: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}"
    }.isSuccess

    fun requestProgressFlush() = progressSender.requestFlush()

    fun clearPendingProgress() = progressOutbox.clear()

    fun close() {
        progressSender.close()
        progressScope.cancel()
    }

    suspend fun clearWatchHistory(): Int = withMetadataContext {
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
                if (http.readCancellable(request) { it.isSuccessful }) {
                    cleared++
                    DetailRecommendationCache.watchStateChanged(baseUrl, userId)
                }
            }
            if (page.size < 100) break
        }
        cleared
    }

    suspend fun testConnection(server: String): String = withMetadataContext {
        baseUrl = normalize(server)
        val info = getJson(url("/System/Info/Public"), withToken = false)
        info.optString("ServerName", "Emby")
    }

    companion object {
        private const val SEARCH_REQUEST_ATTEMPTS = 2
        private val BIF_MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MAX_TRICKPLAY_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_TRICKPLAY_BYTES = 64L * 1024 * 1024
        private const val MAX_TRICKPLAY_FRAMES = 1_000_000

        fun normalize(raw: String): String {
            if (raw.isBlank()) return ""
            return requireNotNull(parseServerAddress(raw)) { "Invalid server address" }.baseUrl
        }
    }
}

/** Latest accepts either an Items envelope or a root array, without changing request/fallback policy. */
internal fun parseLatestItemsResponse(body: String, onFailure: (String) -> Unit): JSONArray? {
    val root = try {
        JSONTokener(body).nextValue()
    } catch (_: JSONException) {
        onFailure(if (body.trimStart().startsWith("["))
            "HTTP 200 invalid response: top-level array parse failed"
        else "HTTP 200 invalid response: malformed JSON (expected Items object or top-level array)")
        return null
    }
    val items = (root as? JSONObject)?.optJSONArray("Items") ?: root as? JSONArray
    if (items == null) onFailure("HTTP 200 invalid response: missing Items and no top-level array")
    return items
}

private fun parseDvProfile(stream: JSONObject): Int? {
    val profile = listOf("VideoDoViProfile", "doviProfile", "dvProfile")
        .firstNotNullOfOrNull { stream.optNormalizedValue(it) }
        ?.filter(Char::isLetterOrDigit)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    if (profile != null) return profile

    val codecTag = listOf("CodecTag", "CodecTagString", "Codec")
        .firstNotNullOfOrNull { stream.optNormalizedValue(it) }
    return Regex("(?i)dv(?:he|h1|i)\\.(\\d+)")
        .find(codecTag.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

private fun JSONObject.optNormalizedValue(normalizedKey: String): String? {
    val normalized = normalizedKey.filter(Char::isLetterOrDigit)
    val key = keys().asSequence().firstOrNull { candidate ->
        candidate.filter(Char::isLetterOrDigit).equals(normalized, ignoreCase = true)
    } ?: return null
    return opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
}
