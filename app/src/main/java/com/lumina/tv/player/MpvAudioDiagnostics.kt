package com.lumina.tv.player

import android.content.Context
import android.media.AudioManager
import android.os.Process
import com.lumina.tv.diag.LogRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Observation only: never changes volume, channel count, filters or the output device. */
internal class MpvAudioDiagnostics(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val snapshots = File(context.filesDir, "mpv-audio.jsonl")
    private val systemLog = File(context.filesDir, "mpv-audio-system.log")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mpv-audio-diagnostics").apply { isDaemon = true }
    }
    @Volatile private var active: MpvPlayerController.LoadIdentity? = null
    @Volatile private var closed = false
    @Volatile private var captureProcess: java.lang.Process? = null
    private var lastAudioPts: Double? = null
    private var lastSampleAt: Long? = null
    private var loadStartedAtMs = 0L

    fun beginLoad(identity: MpvPlayerController.LoadIdentity) {
        active = identity
        loadStartedAtMs = System.currentTimeMillis()
        lastAudioPts = null
        lastSampleAt = null
    }

    fun sample(identity: MpvPlayerController.LoadIdentity, point: String, positionMs: Long, properties: Map<String, String>) {
        if (closed || active !== identity) return
        val at = System.currentTimeMillis()
        val pts = properties["audio-pts"]?.toDoubleOrNull()
        val delta = pts?.let { now -> lastAudioPts?.let { now - it } }
        val snapshot = JSONObject()
            .put("sample", point).put("atMs", at).put("pid", Process.myPid())
            .put("generation", identity.generation).put("physicalLoadId", identity.physicalId)
            .put("software", identity.software).put("positionMs", positionMs)
            .put("sincePreviousSampleMs", lastSampleAt?.let { at - it } ?: JSONObject.NULL)
            .put("properties", JSONObject(properties).apply {
                listOf("aid", "audio-params", "audio-out-params", "current-ao", "audio-device").forEach { name ->
                    if (!has(name)) put(name, JSONObject.NULL)
                }
            })
            .put("pcmEvidence", JSONObject()
                .put("audioPtsSeconds", pts ?: JSONObject.NULL)
                .put("audioPtsDeltaSeconds", delta ?: JSONObject.NULL)
                .put("audioTimelineAdvancing", delta?.let { it > 0.0 } ?: JSONObject.NULL)
                .put("sampleAmplitude", "unavailable: MPVLib does not expose PCM samples")
                .put("deviceWriteCounters", "see correlated AudioTrack system logs; timeline progress alone does not prove audible PCM"))
            .put("systemVolume", JSONObject()
                .put("music", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("musicMax", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("musicMuted", audioManager.isStreamMute(AudioManager.STREAM_MUSIC)))
            .put("candidateRoutes", JSONArray(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
                JSONObject().put("id", device.id).put("type", device.type)
                    .put("name", device.productName.toString()).put("channels", JSONArray(device.channelCounts.toList()))
                    .put("sampleRates", JSONArray(device.sampleRates.toList()))
                    .put("role", "candidate route; not proof of this native instance's actual route")
            }))
            .put("nativeAudioTrackSession", "unavailable from MPVLib; correlate PID/time/session in system observations")
        lastAudioPts = pts
        lastSampleAt = at
        append(snapshots, snapshot.toString(), 384 * 1024, identity)
        val startedAt = loadStartedAtMs
        worker.execute { captureSystemAudio(identity, point, at, startedAt) }
    }

    private fun captureSystemAudio(identity: MpvPlayerController.LoadIdentity, point: String, sampleAt: Long, startedAt: Long) {
        if (closed || active !== identity) return
        val process = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "epoch", "-t", "1200", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return
        captureProcess = process
        try {
            if (closed || active !== identity) return
            // A bounded capture cannot retain a reader thread after playback exit.
            val lines = process.inputStream.bufferedReader().useLines { log ->
                log.filter { AUDIO_TAG.containsMatchIn(it) }.take(500).toList()
            }
            process.waitFor(2, TimeUnit.SECONDS)
            if (closed || active !== identity) return
            append(systemLog, "sampleAtMs=$sampleAt pid=${Process.myPid()} load=${identity.physicalId} point=$point\n" + lines.joinToString("\n"), 384 * 1024, identity)
            val observations = AudioLogCorrelation.observations(lines, Process.myPid(), startedAt, System.currentTimeMillis())
            append(snapshots, JSONObject().put("sample", "system-audio").put("point", point)
                .put("sampleAtMs", sampleAt).put("physicalLoadId", identity.physicalId)
                .put("association", "PID/time candidates; session is only populated when explicitly logged")
                .put("observations", observations).toString(), 384 * 1024, identity)
        } finally {
            process.destroy()
            if (captureProcess === process) captureProcess = null
        }
    }

    private fun append(file: File, text: String, limit: Int, identity: MpvPlayerController.LoadIdentity) = synchronized(fileLock) {
        if (closed || active !== identity) return@synchronized
        runCatching {
            file.appendText(LogRedactor.redact(text) + "\n", Charsets.UTF_8)
            if (file.length() > limit) {
                val tail = file.readText(Charsets.UTF_8).takeLast(limit / 2).substringAfter('\n', "")
                file.writeText(tail, Charsets.UTF_8)
            }
        }
        Unit
    }

    fun close() {
        synchronized(fileLock) { closed = true; active = null }
        captureProcess?.destroy()
        worker.shutdownNow()
    }

    companion object {
        private val fileLock = Any()
        private val AUDIO_TAG = Regex("(?i)AudioTrack|AudioFlinger|AudioSystem|AudioPolicy|AAudio|OpenSLES")

        fun beginSession(context: Context) = synchronized(fileLock) {
            listOf("mpv-audio.jsonl", "mpv-audio-system.log").forEach { name ->
                runCatching { File(context.filesDir, name).writeText("", Charsets.UTF_8) }
            }
        }
    }
}
