package com.lumina.tv

import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.shouldCacheChapterResult
import com.lumina.tv.data.tmdbExternalCacheIsFresh
import com.lumina.tv.player.audioMimeForSourceHint
import com.lumina.tv.player.shouldSyncMpvAudioTrack
import com.lumina.tv.ui.screens.libraryCategoryCountLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Batch 11 P3 behavior and wiring regressions. */
object B11Checks {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        libraryCounts()
        responseLocalTotals()
        retryableCaches()
        lifecycleAndPlaybackWiring()
        println("B11 PASS: Q-050 full category totals; Q-051 request-local page totals; Q-052 foreground Hero; Q-053 paused Aurora; Q-054 retryable chapters/BIF; Q-055 expiring TMDB cache; Q-056 API 30 frame rate overload; Q-057 event-bound mpv track sync; Q-058 FLAC/Vorbis MIME")
    }

    private fun libraryCounts() {
        check(libraryCategoryCountLabel(120, 50) == "共 120 部")
        check(libraryCategoryCountLabel(null, 50) == "预览 50 部")
        check(libraryCategoryCountLabel(null, 0) == "数量未知")
        val home = source("ui/screens/HomeScreen.kt")
        check("counts = libraryCounts" in home)
        check("libraryTotalCount = totalCount" in home)
        check("AppRuntime.client?.lastTotalRecordCount" !in home)
    }

    private suspend fun responseLocalTotals() = coroutineScope {
        val queries = CopyOnWriteArrayList<Map<String, String>>()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "b11-items-page") {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val target = input.readLine().split(' ')[1]
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val query = target.substringAfter('?').split('&').associate {
                            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                        queries += query
                        val total = when (query["ParentId"]) {
                            "catalog-a" -> 123
                            "catalog-b" -> 456
                            else -> error("unexpected page request")
                        }
                        val body = JSONObject().put("TotalRecordCount", total).put("Items", JSONArray()).toString().toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().write(body)
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) failure.set(error)
            }
        }
        try {
            val client = EmbyClient("http://127.0.0.1:${server.localPort}", userId = "test")
            val first = async { client.getItemsPage(parentId = "catalog-a", limit = 50) }
            val second = async { client.getItemsPage(parentId = "catalog-b", limit = 50) }
            check(first.await().totalRecordCount == 123)
            check(second.await().totalRecordCount == 456)
            check(queries.mapNotNull { it["ParentId"] }.toSet() == setOf("catalog-a", "catalog-b"))
        } finally {
            server.close()
            worker.join(5_000)
            failure.get()?.let { throw it }
        }
    }

    private fun retryableCaches() {
        check(!shouldCacheChapterResult(false, false))
        check(shouldCacheChapterResult(true, false) && shouldCacheChapterResult(false, true))
        check(tmdbExternalCacheIsFresh(1_000L, 1_299L, 300L))
        check(!tmdbExternalCacheIsFresh(1_000L, 1_300L, 300L))
        check(!tmdbExternalCacheIsFresh(1_100L, 1_000L, 300L))
        check("unavailableTrickplay" !in source("data/EmbyClient.kt"))
    }

    private fun lifecycleAndPlaybackWiring() {
        check(!shouldSyncMpvAudioTrack(ready = true, becameReady = false, trackListChanged = false, hasPendingManualAudio = true))
        check(shouldSyncMpvAudioTrack(ready = true, becameReady = true, trackListChanged = false, hasPendingManualAudio = true))
        check(shouldSyncMpvAudioTrack(ready = true, becameReady = false, trackListChanged = true, hasPendingManualAudio = true))
        check(!shouldSyncMpvAudioTrack(ready = true, becameReady = true, trackListChanged = true, hasPendingManualAudio = false))
        check(!shouldSyncMpvAudioTrack(ready = false, becameReady = false, trackListChanged = true, hasPendingManualAudio = true))
        check(audioMimeForSourceHint("flac") == "audio/flac")
        check(audioMimeForSourceHint("vorbis") == "audio/vorbis")

        val home = source("ui/screens/HomeScreen.kt")
        check("active = active" in home)
        check("if (!active || items.size < 2) return@LaunchedEffect" in home)
        val mainActivity = sourceRoot("MainActivity.kt")
        check("AuroraBackground(active = !bootDone)" in mainActivity)
        val player = source("ui/screens/PlayerScreen.kt")
        check("Build.VERSION.SDK_INT >= 31" in player)
        check("surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)" in player)
        check("shouldSyncMpvAudioTrack(" in player && "hasPendingManualAudio" in player)
        val mpv = source("player/MpvPlayerController.kt")
        check("emit(load, trackListChanged = true)" in mpv)
        val hints = source("player/SourcePlaybackHints.kt")
        check("audioMimeForSourceHint(codec)" in hints)
        check("audioMimeForSourceHint(codec.orEmpty())" in player)
    }

    private fun source(path: String): String = File("src/main/java/com/lumina/tv", path).readText()
    private fun sourceRoot(path: String): String = File("src/main/java/com/lumina/tv", path).readText()
}
