package com.lumina.tv

import com.lumina.tv.diag.CrashStore
import com.lumina.tv.player.MpvFocusEffect
import com.lumina.tv.player.MpvFocusPolicyState
import com.lumina.tv.player.MpvFocusSignal
import com.lumina.tv.player.PlaybackLoadCoordinator
import com.lumina.tv.player.MpvTlsPolicy
import com.lumina.tv.player.PlaybackErrorCategory
import com.lumina.tv.player.PlaybackErrorRoute
import com.lumina.tv.player.classifyPlaybackError
import com.lumina.tv.player.playbackErrorRoute
import com.lumina.tv.player.playbackErrorSample
import com.lumina.tv.player.shouldRecoverAudioWithoutSelection
import com.lumina.tv.player.PlayerHostLifecycleEvent
import com.lumina.tv.player.reduceMpvFocus
import com.lumina.tv.player.reducePlayerHostLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Regressions found while reviewing the B1–B12 changes, without an Android runtime. */
object B13Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val checks = linkedMapOf<String, suspend () -> Unit>(
            "crash" to { crashArchiveFailure() },
            "background" to { backgroundDuringLoad() },
            "tls" to { caBundleOwnership() },
            "audio" to { deferredAudioRecovery() },
        )
        val selected = if (args.isEmpty()) checks.keys else args.toList()
        require(selected.all { it in checks }) { "Unknown B13 check: ${args.toList()}" }
        for (name in selected) {
            checks.getValue(name)()
            println("B13 PASS $name")
        }
    }

    private fun deferredAudioRecovery() {
        check(shouldRecoverAudioWithoutSelection(sourceHasAudio = true, selectedAudioTrack = false, recoveryAlreadyTried = false))
        check(!shouldRecoverAudioWithoutSelection(sourceHasAudio = false, selectedAudioTrack = false, recoveryAlreadyTried = false))
        check(!shouldRecoverAudioWithoutSelection(sourceHasAudio = true, selectedAudioTrack = true, recoveryAlreadyTried = false))
        check(!shouldRecoverAudioWithoutSelection(sourceHasAudio = true, selectedAudioTrack = false, recoveryAlreadyTried = true))

        for (code in listOf(
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )) {
            val category = classifyPlaybackError(playbackErrorSample(code)).category
            check(category == PlaybackErrorCategory.AUDIO_OUTPUT && playbackErrorRoute(category) == PlaybackErrorRoute.AUDIO_OUTPUT) {
                "Real Media3 audio error no longer reaches AUDIO_OUTPUT: code=$code category=$category"
            }
        }

        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        val selectionRecovery = player.substringAfter("if (shouldRecoverAudioWithoutSelection(")
            .substringBefore("if (subtitleSelectionApplied)")
        check("delay(3_000)" in selectionRecovery)
        check("exo.playbackState == Player.STATE_READY" in selectionRecovery)
        check("selectedAudioAfterWait" in selectionRecovery)
        check("audio-track-not-selected" in selectionRecovery)
        check("requestAudioRecovery(reason)" in selectionRecovery)
    }

    private suspend fun caBundleOwnership() {
        val directory = Files.createTempDirectory("b13-ca").toFile()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            repeat(3) {
                val bundle = MpvTlsPolicy.platformCaBundle(directory)
                try {
                    check(bundle.isFile && bundle.length() > 0L)
                    bundle.inputStream().use { input ->
                        check(CertificateFactory.getInstance("X.509").generateCertificates(input).isNotEmpty())
                    }
                    check(MpvTlsPolicy.options(bundle)["tls-verify"] == "yes")
                } finally { check(bundle.delete()) }
            }
            check(directory.listFiles().orEmpty().isEmpty())

            var returned: File? = null
            val job = scope.launch { returned = MpvTlsPolicy.platformCaBundle(directory) }
            dispatcher.next().run() // Caller suspends while the real CA export runs on Dispatchers.IO.
            val resumeCaller = dispatcher.next() // IO finished; its File result is not delivered yet.
            check(directory.listFiles().orEmpty().single().length() > 0L)
            job.cancel()
            resumeCaller.run() // Prompt cancellation discards the result at the dispatcher boundary.
            check(job.isCancelled && job.isCompleted && returned == null)
            check(directory.listFiles().orEmpty().isEmpty()) { "Cancelled CA export leaked an unowned PEM" }

            val notDirectory = File(directory, "not-a-directory").apply { writeText("occupied") }
            check(runCatching { MpvTlsPolicy.platformCaBundle(notDirectory) }.exceptionOrNull() is IOException)
            check(directory.listFiles().orEmpty().toList() == listOf(notDirectory))
        } finally {
            scope.cancel()
            dispatcher.drain()
            directory.deleteRecursively()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "CA export did not resume" }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }

    private suspend fun backgroundDuringLoad() {
        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        val stop = player.substringAfter("if (event == Lifecycle.Event.ON_STOP) {")
            .substringBefore("val transition =")
        // JVM checks cannot dispatch Android lifecycle callbacks: pin the real Compose input too.
        check(Regex("val playbackRequested = loadCoordinator\\.desiredPlaying\\s*$").containsMatchIn(stop)) {
            "ON_STOP must read live play intent even while the backend is idle, awaiting Surface or focus"
        }
        for (engine in listOf("media3", "mpv")) {
            for (stage in listOf("negotiation", "surface", "focus")) coroutineScope {
                val coordinator = PlaybackLoadCoordinator("A")
                val generation = coordinator.beginLoad("A")
                val ready = CompletableDeferred<Unit>()
                var committedPlaying: Boolean? = null
                val lateLoad = async {
                    ready.await()
                    coordinator.commit(generation) { committedPlaying = it }
                }
                yield()
                val stopped = reducePlayerHostLifecycle(false, PlayerHostLifecycleEvent.ON_STOP,
                    coordinator.desiredPlaying)
                check(stopped.pausePlayback && stopped.resumedByBackground) { "$engine/$stage" }
                if (stopped.pausePlayback) coordinator.desiredPlaying = false
                ready.complete(Unit)
                check(lateLoad.await() && committedPlaying == false) { "Late $engine/$stage restarted playback" }
                check(coordinator.generation == generation) { "Background pause replaced the load" }
                val gained = reduceMpvFocus(MpvFocusPolicyState(resumeOnGain = true), MpvFocusSignal.GAIN,
                    desiredPlaying = coordinator.desiredPlaying, currentlyPlaying = false)
                check(gained.effect != MpvFocusEffect.RESUME && gained.effect != MpvFocusEffect.UNDUCK_AND_RESUME)
                val returned = reducePlayerHostLifecycle(stopped.resumedByBackground,
                    PlayerHostLifecycleEvent.ON_START, coordinator.desiredPlaying)
                check(returned.restoreControls && !returned.pausePlayback && !returned.resumedByBackground)
                check(!coordinator.desiredPlaying)
            }
        }
    }

    private fun crashArchiveFailure() {
        val directory = Files.createTempDirectory("b13-crash").toFile()
        try {
            // Rotation failures were already covered by B9; also fail the final archive write.
            val blockedArchive = File(directory, "crash-1.txt").apply { mkdirs() }
            File(blockedArchive, "block").writeText("keep archive unwritable")
            val store = CrashStore(directory)
            store.recordJava("same", "first scene token=b13-private-token", 1L)
            check("b13-private-token" !in File(directory, "crash-latest.txt").readText())
            val first = store.readStats().getJSONObject("same")
            check(first.getInt("count") == 1 && first.getLong("lastAt") == 1L)
            store.recordJava("same", "latest scene", 2L)
            val repeated = store.readStats().getJSONObject("same")
            check(repeated.getInt("count") == 2 && repeated.getLong("lastAt") == 2L)
            check(File(directory, "crash-latest.txt").readText() == "latest scene")
            check(blockedArchive.isDirectory && File(blockedArchive, "block").isFile)
        } finally { directory.deleteRecursively() }
    }
}
