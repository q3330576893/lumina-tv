package com.lumina.tv.player

import com.lumina.tv.player.MpvPlayerController.LoadIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Per PlayerScreen instance, never global. Only numeric timings and fixed event names enter the log. */
internal class PlaybackDiagnostics(private val log: (String) -> Unit = {}) {
    val sessionId: String = UUID.randomUUID().toString()
    var identity: LoadIdentity? = null
        private set
    private var startedAt = 0L
    private var firstFrameMs: Long? = null
    private var stallAt: Long? = null
    private var bufferingAt: Long? = null
    private var stallCount = 0
    private var stallDurationMs = 0L
    private var retries = 0
    private var watchdogHits = 0
    private val events = ArrayDeque<JSONObject>()
    private val firstFailureTrace = mutableListOf<JSONObject>()
    private var failureCount = 0
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var audioOutput: JSONObject? = null

    fun begin(generation: Int, now: Long): LoadIdentity {
        val previous = identity
        // Negative IDs identify preparation / Media3 loads; mpv supplies its real positive physical ID.
        val next = LoadIdentity(generation, -ids.incrementAndGet(), false)
        activate(next, now)
        if (previous != null) {
            retries++
            event(next, "retry", now, JSONObject().put("fromLoadIdentity", previous.json()))
        }
        return next
    }

    fun activate(load: LoadIdentity, now: Long) {
        if (identity == load) return
        val previous = identity
        // Complete an open interval under its original identity before changing ownership.
        previous?.let { stalled(it, false, now, false) }
        identity = load
        startedAt = now
        firstFrameMs = null
        stallAt = null
        bufferingAt = null
        stallCount = 0
        stallDurationMs = 0L
        watchdogHits = 0
        videoDecoder = null
        audioDecoder = null
        audioOutput = null
        event(load, "load_started", now, JSONObject().put("previousLoadIdentity", previous?.json() ?: JSONObject.NULL)
            .put("reason", if (previous == null) "initial_load" else "replacement_load"))
    }

    fun retry(load: LoadIdentity, now: Long) {
        if (load != identity) return
        retries++
        event(load, "retry", now)
    }

    fun firstFrame(load: LoadIdentity, now: Long, evidence: String) {
        if (load != identity || firstFrameMs != null) return
        firstFrameMs = (now - startedAt).coerceAtLeast(0L)
        event(load, "first_frame", now, JSONObject().put("latencyMs", firstFrameMs).put("evidence", evidence))
    }

    fun buffering(load: LoadIdentity, active: Boolean, now: Long, evidence: String) {
        if (load != identity) return
        if (active && bufferingAt == null) {
            bufferingAt = now
            event(load, "buffering_started", now, JSONObject()
                .put("reason", if (firstFrameMs == null) "startup" else "rebuffer").put("evidence", evidence))
        } else if (!active && bufferingAt != null) {
            event(load, "buffering_ended", now, JSONObject().put("durationMs", now - checkNotNull(bufferingAt)).put("evidence", evidence))
            bufferingAt = null
        }
    }

    fun stalled(load: LoadIdentity, stalled: Boolean, now: Long, countRebuffer: Boolean, bufferingObserved: Boolean = true) {
        if (load != identity) return
        if (bufferingObserved) buffering(load, stalled, now, "observed playback state")
        if (stalled && countRebuffer && stallAt == null) {
            stallAt = now
            stallCount++
            event(load, "stall_started", now)
        } else if (!stalled && stallAt != null) {
            val elapsed = (now - checkNotNull(stallAt)).coerceAtLeast(0L)
            stallDurationMs += elapsed
            stallAt = null
            event(load, "stall_ended", now, JSONObject().put("durationMs", elapsed))
        }
    }

    fun watchdog(load: LoadIdentity, now: Long, reason: String = "unknown", evidence: JSONObject = JSONObject()) {
        if (load != identity) return
        watchdogHits++
        event(load, "watchdog", now, JSONObject().put("reason", reason).put("evidence", evidence))
    }

    fun failure(load: LoadIdentity, now: Long, reason: String, evidence: JSONObject) {
        if (load != identity) return
        event(load, "failure_observed", now, JSONObject().put("reason", reason)
            .put("failureNumber", failureCount + 1).put("evidence", evidence))
        failureCount++
    }

    fun recovery(load: LoadIdentity, now: Long, reason: String) {
        event(load, "recovery_action", now, JSONObject().put("reason", reason))
    }

    fun decoder(load: LoadIdentity, audio: Boolean, name: String?) {
        if (load != identity) return
        if (audio) audioDecoder = name else videoDecoder = name
    }

    fun audioTrack(load: LoadIdentity, encoding: Int, sampleRate: Int, channelMask: Int, offload: Boolean) {
        if (load != identity) return
        audioOutput = JSONObject().put("driver", "AudioTrack").put("encoding", encoding)
            .put("sampleRate", sampleRate).put("channelMask", channelMask).put("offload", offload)
            .put("evidence", "Media3.AnalyticsListener.onAudioTrackInitialized.AudioTrackConfig")
            .put("deviceWireFormat", "unknown")
    }

    fun releaseAudioTrack(load: LoadIdentity) {
        if (load == identity) audioOutput = null
    }

    fun actualDecoder(engine: String, readback: Map<String, String>): JSONObject = JSONObject()
        .put("audio", observed(if (engine == "mpv") readback["audio-decoder"] else audioDecoder,
            if (engine == "mpv") "mpv.audio-decoder" else "Media3.onAudioDecoderInitialized"))
        .put("video", observed(if (engine == "mpv") readback["video-decoder"] else videoDecoder,
            if (engine == "mpv") "mpv.video-decoder" else "Media3.onVideoDecoderInitialized"))
        .put("hardwareAcceleration", if (engine == "mpv") observed(readback["hwdec-current"], "mpv.hwdec-current")
            else observed(null, "unavailable"))

    fun actualAudioOutput(engine: String, readback: Map<String, String>): JSONObject =
        if (engine == "mpv") JSONObject()
            .put("driver", observed(readback["current-ao"], "mpv.current-ao"))
            .put("format", observed(readback["audio-out-params/format"], "mpv.audio-out-params/format"))
            .put("channels", observed(readback["audio-out-params/channels"], "mpv.audio-out-params/channels"))
            .put("sampleRate", observed(readback["audio-out-params/samplerate"], "mpv.audio-out-params/samplerate"))
            .put("deviceWireFormat", "unknown")
        else audioOutput?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("value", "unknown").put("evidence", "no AudioTrack initialization callback for this load")

    fun snapshot(now: Long): JSONObject = JSONObject().put("sessionId", sessionId)
        .put("loadIdentity", identity?.json() ?: JSONObject.NULL)
        .put("firstFrameMs", firstFrameMs ?: JSONObject.NULL)
        .put("rebufferCount", stallCount)
        .put("rebufferDurationMs", stallDurationMs + (stallAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L))
        .put("retryCountInSession", retries).put("watchdogHits", watchdogHits)
        .put("events", JSONArray(events.toList()))
        .put("firstFailureTrace", JSONArray(firstFailureTrace))

    fun identityJson(): JSONObject = JSONObject().put("sessionId", sessionId)
        .put("loadIdentity", identity?.json() ?: JSONObject.NULL)

    private fun event(load: LoadIdentity, name: String, now: Long, fields: JSONObject = JSONObject()) {
        if (load != identity) return
        fields.put("event", name).put("elapsedRealtimeMs", now).put("sessionId", sessionId).put("loadIdentity", load.json())
        events.addLast(fields)
        // Preserve the original failure chain across subsequent loads and manual retries.
        if (failureCount < 2 && firstFailureTrace.size < 64) firstFailureTrace += JSONObject(fields.toString())
        if (events.size > 160) events.removeFirst()
        log("playbackMetric event=$name session=$sessionId generation=${load.generation} load=${load.physicalId} software=${load.software} atMs=$now")
    }

    private fun observed(value: String?, evidence: String): JSONObject {
        val actual = value?.trim()?.takeIf { it.isNotEmpty() && it !in setOf("unavailable", "unknown", "N/A", "(null)") }
        return JSONObject().put("value", actual ?: "unknown").put("evidence", evidence)
    }

    private fun LoadIdentity.json() = JSONObject().put("generation", generation).put("physicalId", physicalId).put("software", software)
    private companion object { val ids = AtomicLong() }
}
