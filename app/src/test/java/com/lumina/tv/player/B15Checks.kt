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
        for (propertySetWorks in listOf(false, true)) for (propertyReadWorks in listOf(false, true))
            for (optionWorks in listOf(false, true)) for (appendMask in 0..3) {
            val calls = mutableListOf<String>()
            val result = applyMpvHttpHeaders(headers,
                { name, _ ->
                    calls += "property:$name"
                    if (!propertySetWorks) throw IllegalStateException("property unavailable")
                },
                { name ->
                    calls += "read:$name"
                    if (propertyReadWorks) "Referer: https://example.invalid/,X-Auth: token" else null
                },
                { name, value ->
                    if (name == "http-header-fields") {
                        calls += "option:$name"
                        if (optionWorks) 0 else -1
                    } else {
                        calls += "append:$value"
                        val index = calls.count { it.startsWith("append:") } - 1
                        if (appendMask and (1 shl index) != 0) 0 else -1
                    }
                })
            val expectedCalls = mutableListOf("property:http-header-fields")
            if (propertySetWorks) expectedCalls += "read:http-header-fields"
            val expected = when {
                propertySetWorks && propertyReadWorks -> MpvHttpHeadersResult("property", 2, 2)
                optionWorks -> {
                    expectedCalls += "option:http-header-fields"
                    MpvHttpHeadersResult("option", 2, 2)
                }
                else -> {
                    expectedCalls += "option:http-header-fields"
                    // Preserve caller order and distinguish each append attempt.
                    expectedCalls += headers.map { (name, value) -> "append:$name: $value" }
                    MpvHttpHeadersResult("append", Integer.bitCount(appendMask), 2)
                }
            }
            check(result == expected && calls == expectedCalls) {
                "header probe mismatch propertySet=$propertySetWorks propertyRead=$propertyReadWorks option=$optionWorks appendMask=$appendMask calls=$calls result=$result"
            }
            if (result.accepted < result.total) check(result.notice?.contains("缺少") == true)
            combinations++
        }
        check(combinations == 32)
        val throwing = applyMpvHttpHeaders(headers,
            { _, _ -> throw IllegalStateException("property unavailable") },
            { _ -> throw IllegalStateException("readback unavailable") },
            { name, _ -> if (name == "http-header-fields") throw IllegalStateException("option unavailable") else 0 })
        check(throwing.mode == "append" && throwing.accepted == headers.size && throwing.complete)
        val empty = applyMpvHttpHeaders(emptyMap(), { _, _ -> error("must not probe empty headers") },
            { _ -> error("must not probe empty headers") }, { _, _ -> -1 })
        check(empty.mode == "none" && empty.complete)
        val source = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
        check(source.indexOf("applyMpvHttpHeaders(requestHeaders") < source.indexOf("load.engine.command(loadCommand"))
        check("state.httpHeadersNotice?.let { playbackStatusNotice = it }" in
            File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText())
        println("B15 PASS V2: all 32 API/readback/append acceptance combinations, fallback order, exception downgrade, visible missing-header notice")
    }

    private fun v3EngineCircuitBreaker() {
        val base = PlaybackPath("source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        for (reason in listOf(PlaybackFailureReason.LOAD, PlaybackFailureReason.HTTP,
            PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.DECODING)) {
            val failures = PlaybackPathFailures()
            failures.recordEngineFailure(PlaybackPathFailure(base.copy(engine = "mpv"), reason, 1, "injected fatal failure"))
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "media3")
            check(selectPlaybackEngine("mpv", "mpv", base.copy(audioMode = "soft", requestVariant = 2), failures, mpvAvailable = true) == "media3")
            check(!failures.excludes(base.copy(sourceId = "other", engine = "mpv")))
            failures.clearForManualRetry() // Manual selection starts a fresh attempt and clears the breaker.
            check(selectPlaybackEngine("mpv", "mpv", base, failures, mpvAvailable = true) == "mpv")
        }
        val audioFailures = PlaybackPathFailures()
        audioFailures.record(PlaybackPathFailure(base.copy(engine = "mpv"), PlaybackFailureReason.AUDIO_OUTPUT, 2, "silent"))
        check(!audioFailures.excludes(base.copy(engine = "mpv", audioMode = "soft")))
        val player = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("enginePinned = \"media3\"" in player && "已切回内置内核 Media3" in player)
        println("B15 PASS V3: source-scoped engine-wide fatal breaker; audio failures stay path-scoped; manual retry clears breaker")
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
            check(file.length() <= 256)
            store.recordExit("ANR", "fresh evidence", 789L)
            val recovered = JSONObject(file.readText())
            check(!recovered.optBoolean("truncated") && recovered.getJSONObject("lastCrash").getLong("at") == 789L)
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
        check(truncated.getBoolean("truncated") && truncated.getInt("maxBytes") == 32)
        val malformed = JSONObject(boundedDiagnosticJson("{broken", 32))
        check(malformed.getBoolean("invalid") && !malformed.has("truncated"))
        println("B15 PASS V4: atomic/concurrent write round-trip, valid overflow JSON and structure-safe redaction")
    }

    private fun v5EndToEndPlaybackPolicy() {
        val initial = PlaybackPath("b15-source", "media3", PlaybackDelivery.DIRECT_PLAY, "1", "auto")
        val failures = PlaybackPathFailures()
        failures.record(PlaybackPathFailure(initial, PlaybackFailureReason.AUDIO_OUTPUT, 1, "first-frame-audio-output"))
        check(!shouldAutomaticallyTryMpvAudioFallback(PlaybackFailureReason.AUDIO_OUTPUT, initial, failures, true, false))
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
        check(failures.excludes(recoveredSoftPath.copy(engine = "mpv"))) // LOAD trips the source-scoped mpv breaker.

        val normalUrl = "Opening https://example.invalid/Videos/stream?DeviceId=33a8cd48625f455e&variant=503"
        check(!isMpvHttpFailureLog(20, normalUrl))
        val headers = linkedMapOf("Referer" to "https://example.invalid/", "X-Authorization" to "secret")
        val missingHeaders = applyMpvHttpHeaders(headers,
            { _, _ -> throw IllegalStateException("property unavailable") },
            { _ -> null },
            { _, _ -> -1 })
        check(missingHeaders.accepted == 0 && missingHeaders.notice?.contains("缺少") == true)
        val controller = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
        val loadBlock = controller.substringAfter("val headerResult = applyMpvHttpHeaders")
            .substringBefore("}.onFailure {")
        check(loadBlock.indexOf("load.engine.command(loadCommand") > 0 && "return" !in loadBlock)

        val terminalSelection = selectPlaybackEngine("mpv", "media3", recoveredPassthroughPath, failures, mpvAvailable = true)
        check(terminalSelection != null && recovered == "media3")
        println("B15 PASS V5: explicit mpv choice, then Media3 AUDIO_OUTPUT -> automatic mpv -> LOAD breaker -> Media3 soft/passthrough; diagnostics do not dead-end")
    }

}
