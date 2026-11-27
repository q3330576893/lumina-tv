package com.lumina.tv.player

import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.os.Looper
import com.lumina.tv.diag.DiagnosticCapture
import com.lumina.tv.diag.appendDiagnosticLog
import com.lumina.tv.diag.LatestDiagnosticWrite
import com.lumina.tv.diag.PageLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Observation only: never changes volume, channel count, filters or the output device. */
internal class MpvAudioDiagnostics(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val snapshots = File(context.filesDir, "mpv-audio.jsonl")
    private val systemLog = File(context.filesDir, "mpv-audio-system.log")
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "mpv-audio-diagnostics").apply { isDaemon = true } })
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private var session = sessionIds.get()
    @Volatile private var active: MpvPlayerController.LoadIdentity? = null
    @Volatile private var closed = false
    private var lastAudioPts: Double? = null
    private var lastSampleAt: Long? = null
    private var loadStartedAtMs = 0L

    fun beginLoad(identity: MpvPlayerController.LoadIdentity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        session = sessionIds.get()
        active = identity
        loadStartedAtMs = System.currentTimeMillis()
        lastAudioPts = null
        lastSampleAt = null
    }

    fun sample(identity: MpvPlayerController.LoadIdentity, point: String, positionMs: Long, properties: Map<String, String>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || active !== identity) return
        val frozenProperties = properties.toMap()
        val at = System.currentTimeMillis()
        val pts = properties["audio-pts"]?.toDoubleOrNull()
        val delta = pts?.let { now -> lastAudioPts?.let { now - it } }
        val sincePreviousSample = lastSampleAt?.let { at - it }
        val startedAt = loadStartedAtMs
        lastAudioPts = pts
        lastSampleAt = at
        try {
            worker.execute {
                if (closed || active !== identity) { dropped.incrementAndGet(); return@execute }
                runCatching {
                    writeSample(identity, point, positionMs, frozenProperties, at, pts, delta, sincePreviousSample)
                    captureSystemAudio(identity, point, at, startedAt)
                }.onFailure {
                    failures.incrementAndGet()
                    PageLog.add("mpvAudio ioFailure=${it.javaClass.simpleName} dropped=${dropped.get()}")
                }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun writeSample(
        identity: MpvPlayerController.LoadIdentity,
        point: String,
        positionMs: Long,
        properties: Map<String, String>,
        at: Long,
        pts: Double?,
        delta: Double?,
        sincePreviousSample: Long?,
    ) {
        val snapshot = JSONObject()
            .put("sample", point).put("atMs", at).put("pid", Process.myPid())
            .put("dropped", dropped.get()).put("ioFailures", failures.get())
            .put("generation", identity.generation).put("physicalLoadId", identity.physicalId)
            .put("software", identity.software).put("positionMs", positionMs)
            .put("sincePreviousSampleMs", sincePreviousSample ?: JSONObject.NULL)
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
        append(snapshots, snapshot.toString(), 384 * 1024, identity)
    }

    private fun captureSystemAudio(identity: MpvPlayerController.LoadIdentity, point: String, sampleAt: Long, startedAt: Long) {
        if (closed || active !== identity) return
        val capture = DiagnosticCapture.read(128 * 1024, 2000L) { resources ->
            val process = resources.process(ProcessBuilder("logcat", "-d", "-v", "epoch", "-t", "1200", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start())
            process.inputStream
        }
        if (closed || active !== identity) return
        val lines = capture.bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { AUDIO_TAG.containsMatchIn(it) }.take(500).toList()
        append(systemLog, "sampleAtMs=$sampleAt pid=${Process.myPid()} load=${identity.physicalId} point=$point " +
            "capture=${capture.status} truncated=${capture.truncated} dropped=${dropped.get()}\n" + lines.joinToString("\n"), 384 * 1024, identity)
        val observations = AudioLogCorrelation.observations(lines, Process.myPid(), startedAt, System.currentTimeMillis())
        append(snapshots, JSONObject().put("sample", "system-audio").put("point", point)
            .put("sampleAtMs", sampleAt).put("physicalLoadId", identity.physicalId)
            .put("captureStatus", capture.status).put("truncated", capture.truncated)
            .put("association", "PID/time candidates; session is only populated when explicitly logged")
            .put("observations", observations).toString(), 384 * 1024, identity)
    }

    private fun append(file: File, text: String, limit: Int, identity: MpvPlayerController.LoadIdentity) = synchronized(fileLock) {
        if (closed || active !== identity || session != sessionIds.get()) return@synchronized
        prepareSession(file.parentFile!!, session)
        appendDiagnosticLog(file, text + "\n", limit)
    }

    fun close() {
        closed = true
        active = null
        // Interrupt the collector's get(): its finally block kills/closes on the IO thread.
        // No stream close, process wait, or filesystem lock is taken by the release caller.
        dropped.addAndGet(worker.shutdownNow().size.toLong())
        PageLog.add("mpvAudio closed dropped=${dropped.get()} ioFailures=${failures.get()}")
    }

    companion object {
        private val fileLock = Any()
        private val AUDIO_TAG = Regex("(?i)AudioTrack|AudioFlinger|AudioSystem|AudioPolicy|AAudio|OpenSLES")

        private val sessionIds = AtomicLong()
        private var initializedSession = -1L
        private val resetWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "mpv-audio-files").apply { isDaemon = true }
        }
        private val resets = LatestDiagnosticWrite<Pair<File, Long>>({ resetWorker.execute(it) }) { (directory, session) ->
            synchronized(fileLock) { if (session == sessionIds.get()) prepareSession(directory, session) }
        }

        private fun prepareSession(directory: File, session: Long) {
            if (initializedSession == session) return
            listOf("mpv-audio.jsonl", "mpv-audio-system.log").forEach { name ->
                File(directory, name).writeText("", Charsets.UTF_8)
            }
            initializedSession = session
        }

        fun beginSession(context: Context) {
            val session = sessionIds.incrementAndGet()
            resets.submit(session, context.filesDir to session)
        }
    }
}
