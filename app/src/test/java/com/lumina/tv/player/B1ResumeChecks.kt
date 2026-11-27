package com.lumina.tv.player

import com.lumina.tv.data.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal object B1ResumeChecks {
    suspend fun run() {
        val initial = PlaybackStartIntent.Resume
        check(playbackReloadIntent(initial, null) == initial)
        check(playbackReloadIntent(initial, TimelinePosition(null, 600_000)) == initial)
        check(playbackReloadIntent(initial, TimelinePosition(0, null)) == initial) // Uncalibrated load/seek origin.
        check(playbackReloadIntent(PlaybackStartIntent.FromStart, null) == PlaybackStartIntent.FromStart)
        check(playbackReloadIntent(initial, TimelinePosition(0, 0)) == PlaybackStartIntent.Restore(0))
        val measured = TimelineCalibrationWindow(0, 900_000).apply { firstFrame(0); sample(120_000, 900_000, 1) }
        check(measured.position(120_000).contentPositionMs == 120_000L)
        val restored = playbackReloadIntent(initial, measured.position(120_000))
        check(restored == PlaybackStartIntent.Restore(120_000))
        check(playbackReloadIntent(restored, null) == restored) // Repeated failure before a new sample.

        val ticks = CopyOnWriteArrayList<Long>()
        val timeout = AtomicBoolean(false)
        val finish = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                check(session.uri == "/Items/b1-resume/PlaybackInfo")
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                ticks += JSONObject(checkNotNull(files["postData"])).getLong("StartTimeTicks")
                if (timeout.get()) return newChunkedResponse(Response.Status.OK, "application/json", object : InputStream() {
                    override fun read(): Int { finish.await(); return -1 }
                })
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
            }
        }
        var client: EmbyClient? = null
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            val activeClient = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "b1-test")
            client = activeClient
            val source = MediaSource("source", "source", "mkv", true, true, emptyList())
            val prepared = PreparedPlaybackTracks(source, resolvePlaybackTrackIntent(source, null, "auto", null, null), listOf(source))
            val item = MediaItem("b1-resume", "Batch 1 Resume", "Movie", positionTicks = 600_000L * 10_000L)
            suspend fun negotiate(intent: PlaybackStartIntent) = activeClient.resolvePlayback(item, startIntent = intent, preparedTracks = prepared)
            suspend fun denied(intent: PlaybackStartIntent) {
                val failure = runCatching { negotiate(intent) }.exceptionOrNull()
                check(failure is EmbyException && failure.httpStatus == 401) { "Unexpected failure: $failure" }
            }
            denied(initial)
            denied(playbackReloadIntent(initial, null))
            check(ticks.toList() == listOf(6_000_000_000L, 6_000_000_000L))
            timeout.set(true)
            check(withTimeoutOrNull(300) { negotiate(initial) } == null)
            timeout.set(false)
            denied(playbackReloadIntent(initial, null))
            check(ticks.takeLast(2) == listOf(6_000_000_000L, 6_000_000_000L))
            denied(restored)
            check(ticks.last() == 1_200_000_000L)
            denied(PlaybackStartIntent.FromStart)
            check(ticks.last() == 0L)
            val player = java.io.File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
            check(!player.contains("PlaybackStartIntent.Restore(position"))
            println("B1 Q-004 PASS: real PlaybackInfo bodies retain 600s after 401/timeout, observed 120s restores, observed zero and explicit FromStart remain distinct")
        } finally {
            finish.countDown()
            try {
                server.stop()
            } finally {
                client?.close()
            }
        }
    }
}
