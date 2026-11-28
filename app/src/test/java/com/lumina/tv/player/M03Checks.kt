package com.lumina.tv.player

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Startup regressions: these assertions were run against the original implementation first. */
object M03Checks {
    @JvmStatic fun main(args: Array<String>) {
        val failures = mutableListOf<String>()
        fun verify(name: String, action: () -> Unit) {
            runCatching(action).fold({ println("M03 $name PASS") }, {
                failures += name
                println("M03 $name FAIL: ${it.message}")
            })
        }
        verify("S1 startup BUFFERING evidence") {
            val diagnostics = PlaybackDiagnostics()
            val load = diagnostics.begin(1, 100)
            diagnostics.stalled(load, true, 110, countRebuffer = false)
            check(diagnostics.snapshot(120).getJSONArray("events").toString().contains("buffering_started")) {
                "First buffering interval is absent before first frame"
            }
            check(diagnostics.snapshot(120).getInt("rebufferCount") == 0)
            diagnostics.watchdog(load, 8_110, "BUFFERING_NO_PROGRESS")
            diagnostics.failure(load, 8_110, "WATCHDOG", JSONObject().put("path", "single"))
            diagnostics.recovery(load, 8_410, "watchdog:BUFFERING_NO_PROGRESS")
            val next = diagnostics.begin(2, 8_420)
            diagnostics.stalled(next, true, 8_430, countRebuffer = false)
            diagnostics.watchdog(next, 16_430, "BUFFERING_NO_PROGRESS")
            diagnostics.failure(next, 16_430, "WATCHDOG", JSONObject().put("path", "single"))
            val retained = diagnostics.snapshot(16_440).getJSONArray("firstFailureTrace").toString()
            repeat(200) { diagnostics.begin(it + 3, 20_000L + it) }
            check(diagnostics.snapshot(30_000).getJSONArray("firstFailureTrace").toString() == retained)
            val trace = JSONArray(retained)
            val stages = (0 until trace.length()).map { trace.getJSONObject(it).getString("event") }
            check(stages.indexOf("load_started") < stages.indexOf("buffering_started"))
            check(stages.indexOf("buffering_started") < stages.indexOf("watchdog"))
            check(stages.indexOf("watchdog") < stages.indexOf("recovery_action"))
            check(stages.last() == "failure_observed" && trace.getJSONObject(trace.length() - 1).getInt("failureNumber") == 2)
            val record = playbackFailureJson(PlaybackFailureInput(null, null, JSONObject(), "fixture", 2,
                diagnostics.identityJson(), "media3", null, null, null, null, JSONArray(), JSONObject(), JSONObject(),
                JSONObject().put("firstFailureTrace", trace), JSONObject(), JSONArray(), null, "fixture"))
            check(record.getJSONObject("timeline").getJSONArray("firstFailureTrace").length() == trace.length())
        }
        verify("S2 first watchdog preserves same path") {
            val paths = PlaybackPathFailures()
            val path = PlaybackPath("single", "media3", PlaybackDelivery.DIRECT_PLAY, "0", "auto")
            paths.record(PlaybackPathFailure(path, PlaybackFailureReason.WATCHDOG, 1, "BUFFERING_NO_PROGRESS"))
            check(selectPlaybackEngine("media3", null, path, paths, false) == "media3") {
                "First watchdog excludes the only Media3 path before reload/prepare"
            }
            var prepares = 0
            val coordinator = PlaybackLoadCoordinator("single")
            fun reload() = runBlocking {
                val generation = coordinator.beginLoad("single")
                check(selectPlaybackEngine("media3", null, path, paths, false) == "media3")
                val permit = checkNotNull(coordinator.grantPermit(generation, LoadRetirementFence().awaitPermit()))
                check(coordinator.commit(permit) { prepares++ })
            }
            check(retryAvailablePath(path, paths, ::reload) && prepares == 1)
            // Repeated callbacks from one physical load cannot spend the next physical load's budget.
            paths.record(PlaybackPathFailure(path, PlaybackFailureReason.WATCHDOG, 1, "duplicate"))
            check(!paths.excludes(path) && paths.snapshot().size == 1)
            paths.record(PlaybackPathFailure(path, PlaybackFailureReason.WATCHDOG, 2, "BUFFERING_NO_PROGRESS"))
            check(paths.excludes(path)) { "Second stall must exhaust the same-path retry budget" }
            check(!retryAvailablePath(path, paths, ::reload) && prepares == 1)
            for (other in listOf(path.copy(sourceId = "other"), path.copy(engine = "mpv"),
                path.copy(delivery = PlaybackDelivery.TRANSCODE), path.copy(audioTrack = "1"),
                path.copy(audioMode = "soft"), path.copy(requestVariant = 1))) check(!paths.excludes(other))
            check(paths.exclusionHistory().single().remainingAvailablePaths == 0)
            check(paths.exclusionHistory().single().observation.generation == 2)
            val history = paths.snapshot().size
            paths.clearForManualRetry()
            check(!paths.excludes(path) && paths.snapshot().size == history) { "Manual Retry must retain diagnostic history" }
            check(retryAvailablePath(path, paths, ::reload) && prepares == 2)
            val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
            val watchdog = screen.substringAfter("fun requestWatchdogRecovery(").substringBefore("LaunchedEffect(exo, item.id)")
            check("retryAvailablePath(retryPath, pathFailures)" in watchdog && "requestPlaybackReload(generation)" in watchdog)
            val reloadBody = screen.substringAfter("fun requestPlaybackReload(").substringBefore("fun checkStrmResume")
            check("reloadKey++" in reloadBody)
            check("LaunchedEffect(item.id, reloadKey)" in screen && "loadCoordinator.commit(loadPermit)" in screen)
            check("listenersReady.await()" in screen) // Metadata creates the player; its appearance cannot recursively reload.
            check(screen.indexOf("exo.setMediaSource(videoSource)") < screen.indexOf("exo.prepare()", screen.indexOf("exo.setMediaSource(videoSource)")))
        }
        verify("S3 renderer routing wiring") {
            val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
            check("classifyRendererFailure(err" in screen) { "No renderer evidence router at the production error entry" }
            check("audioDecodeMode == \"passthrough\" && decodingUnsupported" !in screen)
        }
        verify("S4 software retry permit wiring") {
            val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
            val controller = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
            check("renewSoftwareRetryPermit(" in screen && "onSoftwareRetryRetired" in controller) {
                "Software retry bypasses coordinator permit renewal"
            }
            val retry = controller.substringAfter("private fun retryInSoftware(").substringBefore("private fun handleProperty")
            check(retry.indexOf("destroyNativeLoad(load)") < retry.indexOf("onSoftwareRetryRetired("))
            check(retry.indexOf("onSoftwareRetryRetired(") < retry.indexOf("createNativeLoad(software = true)"))
            check(retry.indexOf("createNativeLoad(software = true)") < retry.indexOf("attachSurface(surface, width, height)"))
            check(retry.indexOf("attachSurface(surface, width, height)") < retry.indexOf("startPhysicalLoad(retry)"))
            check("freshPermit -> playbackLoadPermit = freshPermit" in screen)
            check("autoSoftwareRetry = AppRuntime.prefs?.autoSoftwareRetry" in screen)
        }
        check(failures.isEmpty()) { "M03 regressions: $failures" }
        P0RendererRoutingChecks.run()
        P0PermitChecks.run()
    }
}
