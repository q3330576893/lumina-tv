package com.lumina.tv.data

import com.lumina.tv.ui.screens.SeasonDirectoryStatus
import com.lumina.tv.ui.screens.beginSeasonDirectoryLoad
import com.lumina.tv.ui.screens.completeSeasonDirectoryLoad
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object B3Checks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        playbackCacheIdentity()
        seasonEpisodeCacheTtl()
        seasonDirectoryFailures()
        nextEpisodeFiltering()
        sourceInventoryRetryState()
        sourceInventoryReuse()
        println("B3 PASS Q-007/Q-009/Q-010/Q-011/Q-029: byte-aware playback keys, season TTL, missing-episode filtering, shared source inventory and explicit directory failures")
    }

    private fun playbackCacheIdentity() {
        val route = stablePlaybackRouteIdentity(
            "https://emby.example/Videos/item/stream?MediaSourceId=A&api_key=secret&PlaySessionId=first",
        )
        check("secret" !in route && "first" !in route)
        check(route == stablePlaybackRouteIdentity(
            "https://emby.example/Videos/item/stream?MediaSourceId=A&api_key=other&PlaySessionId=second",
        ))
        check(route != stablePlaybackRouteIdentity("https://emby.example/Videos/item/stream?MediaSourceId=B"))

        fun key(
            validator: String? = "etag-a",
            mode: String = "DirectPlay",
            routeIdentity: String = route,
            audio: Int? = 1,
            subtitleMode: String? = "AUTO",
            subtitle: Int? = null,
            start: Long = 0L,
        ) = playbackCacheKey("server", "user", "item", "source", validator, 100L, mode,
            routeIdentity, audio, subtitleMode, subtitle, start)

        val direct = key()
        check(direct != null)
        check(key(validator = "etag-b") != direct)
        check(key(mode = "DirectStream") != direct)
        check(key(routeIdentity = "$route&VideoCodec=h264") != direct)
        check(key(mode = "DirectStream", audio = 2) != key(mode = "DirectStream", audio = 1))
        check(key(mode = "DirectStream", start = 30_000) != key(mode = "DirectStream", start = 0))
        check(key(validator = null) == null)
        check(key(routeIdentity = "") == null)
        check(playbackContentValidator(JSONObject("{\"Size\":100}") ) == null)
        check(playbackContentValidator(JSONObject("{\"ETag\":\"v1\",\"Size\":100}")) != null)
        check(playbackContentValidator(JSONObject("{\"DateModified\":\"2026-01-01\",\"Size\":100}")) != null)
    }

    private fun seasonEpisodeCacheTtl() {
        val key = "b3-season-${System.nanoTime()}"
        val episode = MediaItem("ep", "Episode", "Episode")
        LibraryCache.putEpisodes(key, "season", listOf(episode), nowMs = 1_000L)
        check(LibraryCache.episodesOf(key, "season", nowMs = 180_999L) == listOf(episode))
        check(LibraryCache.episodesOf(key, "season", nowMs = 181_000L) == null)
        check(LibraryCache.episodesStaleOf(key, "season") == listOf(episode))

        val emptyKey = "$key-empty"
        LibraryCache.putEpisodes(emptyKey, "season", emptyList(), nowMs = 2_000L)
        check(LibraryCache.episodesOf(emptyKey, "season", nowMs = 2_001L) == emptyList<MediaItem>())
    }

    private fun seasonDirectoryFailures() {
        check(beginSeasonDirectoryLoad<String>().status == SeasonDirectoryStatus.LOADING)
        check(completeSeasonDirectoryLoad(Result.success(listOf("S1"))).status == SeasonDirectoryStatus.SUCCESS)
        check(completeSeasonDirectoryLoad(Result.success(emptyList<String>())).status == SeasonDirectoryStatus.EMPTY)
        val failure = completeSeasonDirectoryLoad<String>(Result.failure(IllegalStateException("offline")))
        check(failure.status == SeasonDirectoryStatus.FAILURE && failure.error != null && failure.seasons.isEmpty())

        val detail = File("src/main/java/com/lumina/tv/ui/screens/DetailScreen.kt").readText()
        check("if (it0.type != \"Series\" || seasons.isNotEmpty())" in detail)
        check("seasonResult.getOrNull()?.isEmpty() == true" in detail && "empty-season-directory" in detail)
        check("episodesStaleOf" in detail && "episodeReloadTick" in detail)
        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("LibraryCache.putEpisodes(sid, item.seasonId, episodes)" !in player)
    }

    private suspend fun nextEpisodeFiltering() {
        val missing = MediaItem("missing", "Missing", "Episode", indexNumber = 2, isMissing = true)
        val unavailable = MediaItem("unavailable", "Unavailable", "Episode", indexNumber = 3)
        val retryable = MediaItem("retryable", "Retryable", "Episode", indexNumber = 4)
        val available = MediaItem("available", "Available", "Episode", indexNumber = 5, sourceCount = 1)
        val probes = mutableListOf<String>()
        val next = selectNextPlayableEpisode(listOf(missing, unavailable, retryable, available)) { episode ->
            probes += episode.id
            when (episode.id) {
                "unavailable" -> EpisodeSourceAvailability.NO_SOURCE
                "retryable" -> EpisodeSourceAvailability.PROBE_FAILED
                else -> EpisodeSourceAvailability.HAS_SOURCE
            }
        }
        check(next == retryable)
        check(probes == listOf("unavailable", "retryable"))
        check(selectNextPlayableEpisode(listOf(missing, unavailable)) { EpisodeSourceAvailability.NO_SOURCE } == null)
        check(selectNextPlayableEpisode(listOf(available)) { error("known sources do not need a probe") } == available)

        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check(Regex("getNextEpisode\\(item\\)").findAll(player).count() >= 2)
    }

    private fun sourceInventoryReuse() {
        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("LaunchedEffect(item.id) {\n        mediaSources = catchingRequest" !in player)
        check("preparedTracks.availableSources" in player)
        check("if (client === c && AppRuntime.client === c" in player)
    }

    private fun sourceInventoryRetryState() {
        val failure = IllegalStateException("probe failed")
        check(recoveredSourceInventory(emptyList<String>(), Result.failure(failure)).exceptionOrNull() === failure)
        check(recoveredSourceInventory(listOf("initial"), Result.failure(failure)).getOrThrow() == listOf("initial"))
        check(recoveredSourceInventory(emptyList<String>(), Result.success(emptyList())).getOrThrow().isEmpty())
    }
}
