package com.lumina.tv.player

import com.lumina.tv.data.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Production coordinator, sender and PlaybackInfo transport with deterministic suspension points. */
internal object B2PlaybackChecks {
    suspend fun run() = coroutineScope {
        val starts = Channel<JSONObject>(Channel.UNLIMITED)
        val requests = Channel<JSONObject>(Channel.UNLIMITED)
        val rebind = AtomicBoolean(false)
        val responseSource = """{"MediaSources":[{"Id":"B","Name":"B","Container":"mkv","SupportsDirectPlay":true,"SupportsDirectStream":true,"DefaultSubtitleStreamIndex":3,"MediaStreams":[{"Index":0,"Type":"Video","Codec":"h264"},{"Index":3,"Type":"Subtitle","Codec":"subrip","IsDefault":true}]}]}"""
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                val body = JSONObject(files["postData"] ?: "{}")
                if (session.uri == "/Sessions/Playing") {
                    starts.trySend(body)
                    return newFixedLengthResponse("{}")
                }
                check(session.uri == "/Items/b2/PlaybackInfo")
                if (body.has("MediaSourceId")) requests.trySend(body)
                if (rebind.get() && body.optString("MediaSourceId") != "B") {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", responseSource)
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{}")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}", userId = "b2-user")
        try {
            for (engine in listOf("media3", "mpv")) {
                for (inputs in listOf(listOf(false), listOf(false, true), listOf(false, true, false))) {
                    latestIntent(engine, client, starts, inputs, waitForSurface = false)
                    if (engine == "mpv") latestIntent(engine, client, starts, inputs, waitForSurface = true)
                }
            }
            staleLoad(client, starts)
            softwareRetry(client, starts)
            fallbackSources(client, requests)
            // The server itself may replace A with B inside one resolvePlayback call.
            rebind.set(true)
            denied(client, "A", Http400RequestVariant(1))
            val a = requests.receive()
            val b = requests.receive()
            check(a.getString("MediaSourceId") == "A" && a.getInt("SubtitleStreamIndex") == -1)
            check(b.getString("MediaSourceId") == "B" && b.getInt("SubtitleStreamIndex") == 3)
            println("B2 PASS Q-012: Deferred negotiation/Surface; latest pause/play; backend/UI intent and real Start IsPaused; software retry; stale load rejection")
            println("B2 PASS Q-014: A stages 1/2 -> B stage 0; same-source retry retained; B first 400; all source-entry policy; server replacement isolation")
        } finally { client.close(); server.stop() }
    }

    private suspend fun latestIntent(engine: String, client: EmbyClient, starts: Channel<JSONObject>,
        inputs: List<Boolean>, waitForSurface: Boolean) = coroutineScope {
        val coordinator = PlaybackLoadCoordinator()
        val load = coordinator.beginLoad("A")
        val negotiation = CompletableDeferred<Unit>()
        val waitingSurface = CompletableDeferred<Unit>()
        val surface = CompletableDeferred<Unit>()
        var playWhenReady: Boolean? = null
        var mpvPause: Boolean? = null
        val commit = async {
            negotiation.await()
            if (engine == "mpv") { waitingSurface.complete(Unit); surface.await() }
            if (engine == "media3") coordinator.commit(load) { playing ->
                playWhenReady = playing
                client.reportStart("b2", 600_000, "A", "DirectPlay", "emby", paused = !playing)
            } else {
                val playing = coordinator.playingFor(load) ?: return@async false
                mpvPause = !playing // The provider is sampled at native physical-load commit.
                client.reportStart("b2", 600_000, "A", "DirectPlay", "emby", paused = !playing)
                true
            }
        }
        if (waitForSurface) {
            negotiation.complete(Unit)
            waitingSurface.await()
            check(starts.tryReceive().isFailure) { "Start was reported before Surface commit" }
        }
        inputs.forEach { coordinator.desiredPlaying = it }
        negotiation.complete(Unit)
        surface.complete(Unit)
        check(commit.await())
        val wire = withTimeout(3_000) { starts.receive() }
        val desired = inputs.last()
        check(coordinator.desiredPlaying == desired)
        check(if (engine == "media3") playWhenReady == desired else mpvPause == !desired)
        check(wire.getBoolean("IsPaused") == !desired && wire.getLong("PositionTicks") == 6_000_000_000L)
    }

    private suspend fun staleLoad(client: EmbyClient, starts: Channel<JSONObject>) = coroutineScope {
        val coordinator = PlaybackLoadCoordinator()
        val old = coordinator.beginLoad("A")
        val negotiation = CompletableDeferred<Unit>()
        val waiting = async {
            negotiation.await()
            coordinator.commit(old) { client.reportStart("b2", 0, "A", "DirectPlay", null, paused = !it) }
        }
        val current = coordinator.beginLoad("B")
        negotiation.complete(Unit)
        check(!waiting.await() && starts.tryReceive().isFailure)
        check(!coordinator.resolveSource(old, "A") && !coordinator.advanceHttp400(old))
        check(coordinator.playingFor(old) == null && coordinator.accepts(current))
    }

    private suspend fun softwareRetry(client: EmbyClient, starts: Channel<JSONObject>) {
        val coordinator = PlaybackLoadCoordinator("A")
        val generation = coordinator.beginLoad("A")
        coordinator.desiredPlaying = false // Input while the old native instance is retiring.
        val provider = { coordinator.playingFor(generation) }
        val paused = !checkNotNull(provider())
        client.reportStart("b2", 600_000, "A", "DirectPlay", "software", paused = paused)
        check(withTimeout(3_000) { starts.receive() }.getBoolean("IsPaused"))
        coordinator.desiredPlaying = true // Reattached Surface samples the same live provider.
        check(provider() == true)
        coordinator.generation++
        check(provider() == null)
    }

    private suspend fun denied(client: EmbyClient, sourceId: String, variant: Http400RequestVariant) {
        val source = MediaSource(sourceId, sourceId, "mkv", true, true,
            listOf(MediaStream(3, "Subtitle", "subrip", "eng", null, true, false, null)),
            defaultSubtitleStreamIndex = 3)
        val intent = resolvePlaybackTrackIntent(source, null, "auto", null, null)
        val failure = runCatching {
            client.resolvePlayback(MediaItem("b2", "B2", "Movie"), mediaSourceId = sourceId,
                removeSubtitleParams = variant.removeSubtitleParams, preferDirectStreamUrl = variant.preferDirectStreamUrl,
                preparedTracks = PreparedPlaybackTracks(source, intent, listOf(source)))
        }.exceptionOrNull()
        check(failure is EmbyException && failure.message.orEmpty().contains("400")) { "Unexpected failure: $failure" }
    }

    private suspend fun fallbackSources(client: EmbyClient, requests: Channel<JSONObject>) {
        for (entry in listOf("error-page", "options-menu", "media3-auto", "watchdog")) {
            for (lastStage in listOf(1, 2)) {
                val coordinator = PlaybackLoadCoordinator("A")
                var load = coordinator.beginLoad("A")
                repeat(lastStage) { check(coordinator.advanceHttp400(load)) }
                check(coordinator.badHttpFallbackStage == lastStage)
                load = coordinator.beginLoad("A")
                check(coordinator.badHttpFallbackStage == lastStage) { "$entry lost same-source fallback" }
                val oldVariant = coordinator.requestVariant
                denied(client, "A", oldVariant)
                check(requests.receive().getInt("SubtitleStreamIndex") == if (lastStage == 1) -1 else 3)
                check(oldVariant.preferDirectStreamUrl == (lastStage == 2))
                val old = load
                load = coordinator.beginLoad("B")
                val fresh = coordinator.requestVariant
                check(fresh.stage == 0 && !fresh.removeSubtitleParams && !fresh.preferDirectStreamUrl)
                check(!coordinator.advanceHttp400(old) && !coordinator.resolveSource(old, "A"))
                denied(client, "B", fresh)
                check(requests.receive().getInt("SubtitleStreamIndex") == 3)
                check(coordinator.advanceHttp400(load) && coordinator.badHttpFallbackStage == 1)
                check(coordinator.advanceHttp400(load) && coordinator.badHttpFallbackStage == 2)
                check(!coordinator.advanceHttp400(load))
            }
        }
    }
}
