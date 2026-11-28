package com.lumina.tv.player

import java.io.File
import com.lumina.tv.diag.CrashStore
import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.boundedDiagnosticJson
import org.json.JSONObject

object B15Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        v1MpvHttpClassification()
        v2MpvHeaderApiProbe()
        v3EngineCircuitBreaker()
        v4CrashStatsJson()
        v5EndToEndPlaybackPolicy()
        println("B15 PASS V1/V2/V3/V4/V5: HTTP classification, headers, engine fallback, crash JSON and playback policy")
    }

    private fun v1MpvHttpClassification() {
        check(!isMpvHttpFailureLog(20,
            "Opening https://goemby.example/Videos/stream?DeviceId=33a8cd48625f455e"))
        check(!isMpvHttpFailureLog(20,
            "Opening done: https://goemby.example/Videos/stream?code=503"))
        check(!isMpvHttpFailureLog(30, "HTTP error 403 Forbidden"))
        check(!isMpvHttpFailureLog(40, "HTTP error 403 Forbidden"))
        check(!isMpvHttpFailureLog(0, "HTTP error 403 Forbidden"))
        check(isMpvHttpFailureLog(20, "HTTP error 403 Forbidden"))
        check(isMpvHttpFailureLog(20, "Server returned 404 Not Found"))
        check(isMpvHttpFailureLog(10, "HTTP status code 503"))
        check(isMpvHttpFailureLog(20, "Connection refused"))
        check(isMpvHttpFailureLog(20, "Failed to resolve hostname"))
    }

    private fun v2MpvHeaderApiProbe() {
        val headers = linkedMapOf("Referer" to "https://example.invalid/", "X-Auth" to "token")
        var combinations = 0
        for (optionWorks in listOf(false, true)) for (readWorks in listOf(false, true)) {
            // mpv_set_option_string has no -append suffix: one base-option set decides everything.
            val binding = B17HeaderBinding(readWorks, optionWorks)
            val result = binding.apply(headers)
            val expectedMode = when {
                !optionWorks -> "string-failed"
                !readWorks -> "string"
                else -> "string-verified"
            }
            val expectedValue = headers.entries.joinToString(",") { (name, value) -> "$name: $value" }
            check(result.mode == expectedMode) {
                "header probe mismatch option=$optionWorks read=$readWorks result=$result"
            }
            check(result.accepted == (if (optionWorks) 1 else 0) && result.total == 2)
            check(result.complete == optionWorks)
            check(binding.calls.first() == ("option:http-header-fields" to expectedValue))
            if (optionWorks) check(binding.elements == headers.map { (name, value) -> "$name: $value" })
            if (!optionWorks) check(result.notice?.contains("2/2") == true)
            combinations++
        }
        check(combinations == 4)
        val throwing = applyMpvHttpHeaders(headers, { _, _ -> throw IllegalStateException("option unavailable") })
        check(throwing.mode == "string-failed" && throwing.accepted == 0 && !throwing.complete)
        val empty = applyMpvHttpHeaders(emptyMap(), { _, _ -> error("must not probe empty headers") })
        check(empty.mode == "none" && empty.complete)
        val headerCall = "applyMpvHttpHeaders("
        val loadCall = "load.engine.command("
        val wiringFixture = "$headerCall)\n$loadCall)"
        assertMpvHeadersBeforeLoad(wiringFixture)
        val invalidWiring = listOf(
            "missing headers" to wiringFixture.replace(headerCall, ""),
            "missing load" to wiringFixture.replace(loadCall, ""),
            "reversed order" to "$loadCall)\n$headerCall)",
        )
        for ((label, fixture) in invalidWiring) {
            val failure = runCatching { assertMpvHeadersBeforeLoad(fixture) }.exceptionOrNull()
            check(failure is IllegalStateException) {
                "$label must reject invalid header/load wiring; failure=$failure"
            }
        }
        // Source wiring only; the binding assertions above do not prove native/device behavior.
        val source = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
        assertMpvHeadersBeforeLoad(source)
        check("state.httpHeadersNotice?.let { playbackStatusNotice = it }" in
            File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText())
        println("B15 PASS V2: base http-header-fields set, escape-aware list, no -append suffix, visible failure notice")
    }

    private fun assertMpvHeadersBeforeLoad(source: String) {
        val headerIndex = source.indexOf("applyMpvHttpHeaders(")
        val loadIndex = source.indexOf("load.engine.command(")
        check(headerIndex >= 0) { "Missing mpv HTTP header application call" }
        check(loadIndex >= 0) { "Missing mpv load command" }
        check(headerIndex < loadIndex) { "mpv HTTP headers must be applied before the load command" }
    }

    private fun v3EngineCircuitBreaker() {
        val base = PlaybackPath("source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        for (reason in listOf(PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.DECODING)) {
            val failures = PlaybackPathFailures()
            failures.recordEngineFailure(PlaybackPathFailure(base.copy(engine = "mpv"), reason, 1, "injected fatal failure"))
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "media3")
            check(selectPlaybackEngine("mpv", "mpv", base.copy(audioMode = "soft", requestVariant = 2), failures, mpvAvailable = true) == "mpv") // M03: preserve a distinct path.
            check(!failures.excludes(base.copy(sourceId = "other", engine = "mpv")))
            failures.clearForManualRetry() // Manual selection starts a fresh attempt and clears the breaker.
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "mpv")
        }
        for (reason in listOf(PlaybackFailureReason.HTTP, PlaybackFailureReason.LOAD)) {
            val failures = PlaybackPathFailures()
            failures.recordEngineFailure(PlaybackPathFailure(base.copy(engine = "mpv"), reason, 1, "injected path failure"))
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "media3")
            check(selectPlaybackEngine("mpv", "mpv", base.copy(audioMode = "soft", requestVariant = 2), failures, mpvAvailable = true) == "mpv")
            check(!failures.excludes(base.copy(sourceId = "other", engine = "mpv")))
            failures.clearForManualRetry() // Manual selection starts a fresh attempt and clears the breaker.
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "mpv")
        }
        val audioFailures = PlaybackPathFailures()
        audioFailures.record(PlaybackPathFailure(base.copy(engine = "mpv"), PlaybackFailureReason.AUDIO_OUTPUT, 2, "silent"))
        check(!audioFailures.excludes(base.copy(engine = "mpv", audioMode = "soft")))
        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("enginePinned = \"media3\"" in player && "已切回内置内核 Media3" in player)
        println("B15 PASS V3: initialization/decoding/HTTP/load/audio exclusions stay path-scoped; manual retry clears availability, retaining history")
    }

    private fun v4CrashStatsJson() {
        val directory = java.nio.file.Files.createTempDirectory("b15-crash-stats").toFile()
        try {
            val file = File(directory, "crash-stats.json")
            val store = CrashStore(directory)
            store.recordExit("NATIVE", "cookie: private evidence", 123L)
            store.recordJava("fingerprint-a", "report", 124L)
            val first = JSONObject(file.readText())
            check(!first.optBoolean("truncated"))
            check(first.getJSONObject("lastCrash").getString("type") == "NATIVE")
            check(first.getJSONObject("fingerprint-a").getInt("count") == 1)

            val writers = (1..8).map { index ->
                Thread { CrashStore(directory).recordJava("fingerprint-$index", "report", 200L + index) }
            }
            writers.forEach(Thread::start)
            writers.forEach(Thread::join)
            val concurrent = JSONObject(file.readText())
            check((1..8).all { concurrent.getJSONObject("fingerprint-$it").getInt("count") == 1 })

            val tinyStore = CrashStore(directory, maxStatsBytes = 256)
            tinyStore.recordExit("NATIVE", "large-evidence-".repeat(80), 456L)
            val truncated = JSONObject(file.readText())
            check(truncated.getBoolean("truncated") && truncated.getInt("maxBytes") == 256)
            check(file.length() <= 256 && truncated.has("preview"))
            store.recordExit("ANR", "fresh evidence", 789L)
            val recovered = JSONObject(file.readText())
            check(!recovered.optBoolean("truncated") && recovered.getJSONObject("lastCrash").getLong("at") == 789L)
            store.recordExit("NATIVE", "evidence-".repeat(3_000), 890L)
            val boundedEvidence = JSONObject(file.readText()).getJSONObject("lastCrash").getString("evidence")
            check(boundedEvidence.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
            file.writeText(JSONObject().put("truncated", true).put("originalBytes", 200_000)
                .put("maxBytes", 120 * 1024).put("preview", "legacy marker")
                .put("fingerprint-preserved", JSONObject().put("count", 7))
                .put("lastCrash", JSONObject().put("evidence", "discard this evidence")).toString())
            store.recordJava("fingerprint-new", "report", 901L)
            val retained = JSONObject(file.readText())
            check(!retained.optBoolean("truncated"))
            check(retained.getJSONObject("fingerprint-preserved").getInt("count") == 7)
            check(retained.getJSONObject("lastCrash").getString("evidence").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
        val rawJson = JSONObject().put("lastCrash", JSONObject()
            .put("evidence", "line\ncookie: secret\ntrailing-field: intact").put("cookie", "private")).toString()
        val safeJson = JSONObject(LogRedactor.redactJson(rawJson)).getJSONObject("lastCrash")
        check(safeJson.getString("cookie") == "***")
        check("trailing-field: intact" in safeJson.getString("evidence"))
        val truncated = JSONObject(boundedDiagnosticJson(
            JSONObject().put("evidence", "x".repeat(200)).toString(), 32))
        check(truncated.getBoolean("truncated") && truncated.toString().toByteArray(Charsets.UTF_8).size <= 32)
        val malformed = JSONObject(boundedDiagnosticJson("{broken", 32))
        check(malformed.getBoolean("invalidJson") && !malformed.has("truncated"))
        check(malformed.toString().toByteArray(Charsets.UTF_8).size <= 32)
        println("B15 PASS V4: atomic/concurrent write round-trip, valid overflow JSON and structure-safe redaction")
    }

    private fun v5EndToEndPlaybackPolicy() {
        val initial = PlaybackPath("b15-source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        val failures = PlaybackPathFailures()
        failures.record(PlaybackPathFailure(initial, PlaybackFailureReason.AUDIO_OUTPUT, 1, "first-frame-audio-output"))
        check(shouldAutomaticallyTryMpvAudioFallback(
            PlaybackFailureReason.AUDIO_OUTPUT, initial, failures, true, false, audioUndecodable = true))
        check(!shouldAutomaticallyTryMpvAudioFallback(
            PlaybackFailureReason.AUDIO_OUTPUT, initial, failures, true, false, audioUndecodable = false))
        check(shouldAutomaticallyTryMpvAudioFallback(PlaybackFailureReason.AUDIO_OUTPUT, initial, failures, true, true))
        check(!shouldAutomaticallyTryMpvAudioFallback(PlaybackFailureReason.LOAD, initial, failures, true, true))
        val automaticMpv = selectPlaybackEngine("media3", "mpv", initial, failures, mpvAvailable = true)
        check(automaticMpv == "mpv")
        val mpvPath = initial.copy(engine = "mpv")
        failures.recordEngineFailure(PlaybackPathFailure(mpvPath, PlaybackFailureReason.LOAD, 2, "injected-load-failure"))
        val recoveredSoftPath = initial.copy(audioMode = "soft", requestVariant = 1)
        val recoveredPassthroughPath = initial.copy(audioMode = "passthrough", delivery = PlaybackDelivery.DIRECT_STREAM)
        val recovered = selectPlaybackEngine("mpv", "media3", recoveredSoftPath, failures, mpvAvailable = true)
        check(recovered == "media3")
        check(!failures.excludes(recoveredSoftPath)) // AUDIO_OUTPUT stays scoped to the original media3 path.
        check(!failures.excludes(recoveredPassthroughPath))
        check(failures.excludes(mpvPath)) // LOAD excludes the original mpv path.
        check(!failures.excludes(recoveredSoftPath.copy(engine = "mpv"))) // Unfailed mpv variants remain available.

        val normalUrl = "Opening https://example.invalid/Videos/stream?DeviceId=33a8cd48625f455e&variant=503"
        check(!isMpvHttpFailureLog(20, normalUrl))
        val headers = linkedMapOf("Referer" to "https://example.invalid/", "X-Authorization" to "secret")
        val missingHeaders = applyMpvHttpHeaders(headers, { _, _ -> -1 })
        check(missingHeaders.accepted == 0 && missingHeaders.notice?.contains("失败") == true)
        val controller = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
        val loadBlock = controller.substringAfter("val headerResult = applyMpvHttpHeaders")
            .substringBefore("}.onFailure {")
        check(loadBlock.indexOf("load.engine.command(loadCommand") > 0 && "return" !in loadBlock)

        val terminalSelection = selectPlaybackEngine("mpv", "media3", recoveredPassthroughPath, failures, mpvAvailable = true)
        check(terminalSelection != null && recovered == "media3")
        println("B15 PASS V5: explicit mpv choice, then Media3 AUDIO_OUTPUT -> automatic mpv -> path-scoped LOAD failure -> Media3 soft/passthrough; diagnostics do not dead-end")
    }

}
