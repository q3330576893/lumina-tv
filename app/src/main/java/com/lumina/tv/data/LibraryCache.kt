package com.lumina.tv.data

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

    private const val HOME_TTL = 3 * 60_000L      // 3 分钟
    private const val DETAIL_TTL = 3 * 60_000L

    @Volatile
    var home: HomeData? = null
        private set

    private val details = LinkedHashMap<String, DetailData>()
    private val seasonEpisodes = HashMap<String, List<MediaItem>>()

    fun homeFresh(ttl: Long = HOME_TTL): Boolean {
        val h = home ?: return false
        return System.currentTimeMillis() - h.at < ttl
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
    private val browse = LinkedHashMap<String, Pair<List<MediaItem>, Long>>()

    fun browseOf(key: String, ttl: Long = 3 * 60_000L): List<MediaItem>? {
        val e = synchronized(this) { browse[key] } ?: return null
        return if (System.currentTimeMillis() - e.second < ttl) e.first else null
    }

    fun putBrowse(key: String, list: List<MediaItem>) {
        // 空结果只缓存 30 秒，避免一次超时导致长时间显示“无内容”
        val ttlMs = if (list.isEmpty()) 30_000L else 3 * 60_000L
        synchronized(this) {
            browse[key] = list to System.currentTimeMillis()
            if (browse.size > 40) browse.keys.firstOrNull()?.let { browse.remove(it) }
        }
    }

    /** 收藏/已看变更后让缓存失效，避免返回首页看到旧状态。 */
    fun invalidateHome() {
        synchronized(this) { home = null }
    }

    fun invalidateDetail(id: String) {
        synchronized(this) { details.remove(id) }
    }

    fun clear() {
        synchronized(this) {
            home = null
            details.clear()
            seasonEpisodes.clear()
        }
    }
}
