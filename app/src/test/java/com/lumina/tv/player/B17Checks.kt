package com.lumina.tv.player

import java.io.File

/** Mirrors mpv 0.41 string-list escaping rather than echoing the setter argument. */
internal class B17HeaderBinding(
    val readWorks: Boolean = true,
    val optionWorks: Boolean = true,
) {
    val elements = mutableListOf<String>()
    val calls = mutableListOf<Pair<String, String>>()
    private var propertyValue: String? = null

    fun getProperty(name: String): String? {
        calls += "read:$name" to ""
        return if (readWorks) propertyValue else null
    }

    fun setOption(name: String, value: String): Int {
        calls += "option:$name" to value
        check(name == "http-header-fields") { "must set the base option once" }
        if (!optionWorks) return -5
        elements.clear()
        elements += parseList(value)
        propertyValue = value
        return 0
    }

    fun apply(headers: Map<String, String>, logEmptyValue: (String) -> Unit = {}) =
        applyMpvHttpHeaders(headers, ::setOption, ::getProperty, logEmptyValue)

    companion object {
        fun parseList(value: String): List<String> {
            if (value.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            val element = StringBuilder()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length &&
                    (value[index + 1] == '\\' || value[index + 1] == ',')
                ) {
                    element.append(value[index + 1])
                    index += 2
                } else if (char == ',') {
                    result += element.toString()
                    element.setLength(0)
                    index++
                } else {
                    element.append(char)
                    index++
                }
            }
            result += element.toString()
            return result
        }
    }
}

object B17Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        val checks = listOf<Pair<String, () -> Unit>>(
            "W2 literal header elements" to ::headers,
            "W3 END_FILE wiring" to ::endFileWiring,
            "W3 natural EOF and queued EOF" to ::eofEvidence,
            "W3 real error evidence and manual stop" to ::errorAndStop,
            "W3 unknown end blocks automatic recovery" to ::unknownEnd,
            "B4 EOF seeks restore into a new load" to ::eofSeek,
            "W4 Surface pause wiring" to ::surfaceWiring,
            "W4 transient/delayed focus and latest pause intent" to ::focusPause,
            "T03 retirement permit and Media3 decoder wiring" to ::retirementPermitWiring,
        )
        val failures = checks.mapNotNull { (name, test) ->
            runCatching(test).fold({ println("B17 PASS $name"); null }, {
                println("B17 FAIL $name: ${it.message}"); name
            })
        }
        check(failures.isEmpty()) { "B17 failed: $failures" }
        println("B17 PASS: W2/W3/W4 mpv headers, end-file and focus pause")
    }

    private fun headers() {
        val headers = linkedMapOf("Referer" to "https://example.invalid/?a=1,b=2",
            "Cookie" to "token=first\\second", "X-Empty" to "", "X-Trailing" to "a\\",
            "X-Escaped" to "a\\,b", "X-Note" to "字幕,\r\n 中文", "" to "discard",
            "Bad:Name" to "discard")
        val binding = B17HeaderBinding()
        val emptyValueLogs = mutableListOf<String>()
        val result = binding.apply(headers, emptyValueLogs::add)
        val validHeaders = headers.filterKeys { it.isNotEmpty() && ':' !in it }
        val expected = validHeaders.map { (name, value) ->
            "$name: ${value.replace("\r", "").replace("\n", "")}"
        }
        check(binding.elements == expected) {
            "reported=$result; actual elements=${binding.elements.size}, expected=${expected.size}"
        }
        check(result.complete && result.accepted == 1 && result.total == validHeaders.size)
        check(result.failedNames.isEmpty() && result.notice == null)
        check(binding.calls.count { it.first == "option:http-header-fields" } == 1)
        check(binding.calls.any { it.first == "read:options/http-header-fields" })
        val serialized = binding.calls.single { it.first == "option:http-header-fields" }.second
        check("a=1\\,b=2" in serialized && "token=first\\\\second" in serialized)
        check(B17HeaderBinding.parseList(serialized) == expected)
        check(emptyValueLogs == listOf("X-Empty"))

        val failed = applyMpvHttpHeaders(headers, { _, _ -> -5 })
        check(failed.accepted == 0 && failed.total == validHeaders.size && !failed.complete)
        check(failed.failedNames == validHeaders.keys.toList())
        check(failed.notice == "mpv 请求头设置失败：${validHeaders.size}/${validHeaders.size}（缺 Referer 或鉴权头的片源可能无法播放）")
        check("token" !in checkNotNull(failed.notice))

        for (error in listOf<Throwable>(IllegalStateException("rejected"), UnsatisfiedLinkError("missing API"))) {
            var calls = 0
            val rejected = applyMpvHttpHeaders(headers, { _, _ ->
                calls++
                throw error
            })
            check(calls == 1 && rejected.accepted == 0 && !rejected.complete)
            check(rejected.failedNames == validHeaders.keys.toList() && rejected.notice != null)
        }

        val noHeaders = B17HeaderBinding().apply(emptyMap())
        check(noHeaders.total == 0 && noHeaders.accepted == 0 && noHeaders.notice == null)
        check(B17HeaderBinding().apply(mapOf("" to "x", "Bad:Name" to "y")).total == 0)
        val unavailableReadback = applyMpvHttpHeaders(mapOf("X-Test" to "ok"), { _, _ -> 0 }, { null })
        check(unavailableReadback.complete && unavailableReadback.notice == null)
    }

    private fun controller() = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()

    private fun endFileWiring() {
        val source = controller()
        val endFile = source.substringAfter("MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {")
            .substringBefore("private fun scheduleStableAudioSample")
        check("FailureKind.DECODING" !in endFile) { "END_FILE still defaults to DECODING" }
        check("classifyMpvEndFile" in endFile) { "END_FILE must use the production evidence classifier" }
        check("\"keep-open\" to \"no\"" in source)
        check("getPropertyBoolean(\"eof-reached\")" !in source)
        check("endFileEvidence.snapshot()" in source && "endFileEvidence.property(property, value)" in source)
        check("!mpvEndAllowsRecovery(load.endKind)" in source)
        check("MpvEndKind.EOF ->" in endFile && "emit(load, ended = true)" in endFile)
        val neutral = endFile.substringAfter("MpvEndKind.STOP, MpvEndKind.UNKNOWN ->")
        check("emit(load)" in neutral && "load.terminal = true" in neutral)
        check("onLoadTimeout" !in neutral && "error =" !in neutral)
        val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("if (state.ended) {\n                            reportStopInAppScope()\n                            startAutoNext()" in screen.replace("\r\n", "\n"))
        check(screen.split("mpvEndAllowsRecovery(mpvState.endKind)").size - 1 == 3)
    }

    private fun surfaceWiring() {
        val source = controller().substringAfter("fun attachSurface(").substringBefore("fun detachSurface(")
        check("shouldPauseMpv(pauseIntent, audioFocusHold)" in source) { "Surface can resume while focus is held" }
        check("setPropertyBoolean(\"pause\", pauseIntent)" !in controller())
    }

    private fun eofEvidence() {
        val native = MpvEndFileTracker()
        native.property("duration", 100.0)
        native.property("time-pos", 99.95)
        val natural = native.snapshot()
        check(classifyMpvEndFile(natural) == MpvEndKind.EOF)
        check(!mpvEndAllowsRecovery(classifyMpvEndFile(natural)))

        // UI handler does not run until after END_FILE and property reset. The
        // production recorder/snapshot, not UI state or eof-reached, owns evidence.
        val queued = native.snapshot()
        native.property("time-pos", 0.0)
        native.property("duration", Double.NaN)
        check(classifyMpvEndFile(queued) == MpvEndKind.EOF)
        check(queued.positionMs == 99_950L && queued.durationMs == 100_000L)
        check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.UNKNOWN)
        check(classifyMpvEndFile(natural.copy(positionMs = 99_000L)) == MpvEndKind.EOF)
        check(classifyMpvEndFile(natural.copy(positionMs = 98_999L)) == MpvEndKind.UNKNOWN)
        check(classifyMpvEndFile(MpvEndFileEvidence(0L, 500L)) == MpvEndKind.UNKNOWN)
        check(classifyMpvEndFile(MpvEndFileEvidence(499L, 500L)) == MpvEndKind.EOF)
        // A seek backwards must replace the sample, rather than keep a maximum.
        native.property("time-pos", 100.0)
        native.property("time-pos", 10.0)
        check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.UNKNOWN)
    }

    private fun errorAndStop() {
        for ((text, kind) in listOf("HTTP error 403 Forbidden" to MpvPlayerController.FailureKind.HTTP,
            "Error while decoding frame" to MpvPlayerController.FailureKind.DECODING,
            "certificate verify failed" to MpvPlayerController.FailureKind.CERT_INVALID)) {
            val native = MpvEndFileTracker()
            native.property("duration", 100.0)
            native.property("time-pos", 99.95) // Error evidence wins even at the end.
            native.log(20, text)
            val end = native.snapshot()
            check(end.failureKind == kind) { "$text classified as ${end.failureKind}" }
            check(classifyMpvEndFile(end) == MpvEndKind.ERROR)
            check(mpvEndAllowsRecovery(classifyMpvEndFile(end)))
            native.property("time-pos", 99.95)
            check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.ERROR)
            native.property("time-pos", 100.0)
            check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.EOF)
            check(classifyMpvEndFile(end) == MpvEndKind.ERROR)
            native.log(20, "unclassified native error")
            check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.UNKNOWN)
            native.log(20, text)
            check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.ERROR)
            native.stop()
            check(classifyMpvEndFile(native.snapshot()) == MpvEndKind.STOP)
            check(!mpvEndAllowsRecovery(classifyMpvEndFile(native.snapshot())))
        }
        val stopped = MpvEndFileTracker()
        stopped.property("duration", 100.0)
        stopped.property("time-pos", 100.0)
        stopped.stop()
        check(classifyMpvEndFile(stopped.snapshot()) == MpvEndKind.STOP)
        check(classifyMpvEndFile(MpvEndFileTracker().snapshot()) == MpvEndKind.UNKNOWN)
        val warning = MpvEndFileTracker()
        warning.property("duration", 100.0)
        warning.property("time-pos", 99.95)
        warning.log(30, "HTTP error 403 Forbidden")
        warning.log(40, "Opening https://example.invalid/?code=503")
        check(classifyMpvEndFile(warning.snapshot()) == MpvEndKind.EOF)
        warning.log(20, "unclassified native error")
        check(warning.snapshot().failureKind == null)
        check(classifyMpvEndFile(warning.snapshot()) == MpvEndKind.UNKNOWN)
        check(!mpvEndAllowsRecovery(classifyMpvEndFile(warning.snapshot())))
        warning.property("time-pos", 100.0)
        check(classifyMpvEndFile(warning.snapshot()) == MpvEndKind.EOF)
    }

    private fun unknownEnd() {
        for (end in listOf(MpvEndFileEvidence(null, null), MpvEndFileEvidence(20_000, 100_000),
            MpvEndFileEvidence(100, 0), MpvEndFileEvidence(100, null))) {
            val kind = classifyMpvEndFile(end)
            check(kind == MpvEndKind.UNKNOWN)
            check(!mpvEndAllowsRecovery(kind))
        }
        check(mpvSecondsToMs(Double.NaN) == null && mpvSecondsToMs(Double.POSITIVE_INFINITY) == null)
        check(mpvSecondsToMs(-1.0) == null && mpvSecondsToMs(null) == null)
        check(mpvEndAllowsRecovery(null)) // Ordinary startup must keep its timeout/retry.
    }

    private fun eofSeek() {
        check(mpvLoadAcceptsSeek(terminal = false, ended = false, endKind = null))
        check(!mpvLoadAcceptsSeek(terminal = true, ended = false, endKind = null))
        check(!mpvLoadAcceptsSeek(terminal = false, ended = true, endKind = null))
        for (kind in MpvEndKind.entries) check(!mpvLoadAcceptsSeek(false, false, kind))
        val controller = File("src/main/java/com/lumina/tv/player/MpvPlayerController.kt").readText()
        val seek = controller.substringAfter("fun seekTo(ms: Long)").substringBefore("fun setSpeed")
        check("check(mpvLoadAcceptsSeek(load.terminal, load.ended, load.endKind))" in seek)
        check("load.ended = false" !in seek)
        val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        val restore = screen.substringAfter("fun seekPlayerTo(").substringBefore("val playerPosition =")
        check("mpvState.endKind == com.lumina.tv.player.MpvEndKind.EOF" in restore)
        check("requestPlaybackReload(sessionGeneration, PlaybackStartIntent.Restore(displayTarget))" in restore)
    }

    private fun focusPause() {
        for (userPaused in listOf(false, true)) for (held in listOf(false, true)) {
            check(shouldPauseMpv(userPaused, held) == (userPaused || held))
        }
        val loss = reduceMpvFocus(MpvFocusPolicyState(), MpvFocusSignal.TRANSIENT_LOSS, true, true)
        check(loss.effect == MpvFocusEffect.PAUSE && loss.state.resumeOnGain)
        // Both resize and recreated Surface call the same production pause policy.
        repeat(2) { check(shouldPauseMpv(false, true)) }
        for (awaitingGain in listOf(loss.state, MpvFocusPolicyState(resumeOnGain = true))) {
            val gain = reduceMpvFocus(awaitingGain, MpvFocusSignal.GAIN, true, false)
            check(gain.effect == MpvFocusEffect.RESUME)
            check(reduceMpvFocus(gain.state, MpvFocusSignal.GAIN, true, true).effect == MpvFocusEffect.NONE)
            check(!shouldPauseMpv(false, false))
            check(reduceMpvFocus(awaitingGain, MpvFocusSignal.GAIN, false, false).effect == MpvFocusEffect.NONE)
            check(shouldPauseMpv(true, false))
        }
    }

    private fun retirementPermitWiring() {
        val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("mpvRetirementFence timeout, continue load" !in screen)
        check("withTimeoutOrNull(3_000L) { MpvRetirementFence.await() }" !in screen)
        val loadSource = screen.substringAfter("fun loadSource(").substringBefore("loadJob = instanceScope.launch")
        check("loadJob?.cancel()" in loadSource && "loadWorkJob.cancel()" in loadSource)
        check("exo.stop()" in loadSource && "exo.clearMediaItems()" in loadSource)
        check("Media3DecoderRetirements.finish(exo, exoRetirement)" in loadSource)
        val creation = screen.substringAfter("loadJob = instanceScope.launch")
        check(creation.indexOf("MpvRetirementFence.awaitPermit()") < creation.indexOf("retireMedia3Player(retiringPlayer"))
        check(creation.indexOf("retireMedia3Player(retiringPlayer") < creation.indexOf("Media3ResourcePlan.resolve(target.source"))
        val retire = screen.substringAfter("fun retireMedia3Player(").substringBefore("val disposeMedia3")
        check("Media3DecoderRetirements.begin(exo)" in retire && "MpvRetirementFence.track(retirement)" in retire)
        check(retire.indexOf("exo.release()") < retire.indexOf("Media3DecoderRetirements.released(exo)"))
        check("Media3DecoderRetirements.fail(exo, retirement" in retire)

        val loadJob = screen.substringAfter("val retirementPermit = MpvRetirementFence.awaitPermit()")
            .substringBefore("} catch (e: Exception)")
        check(loadJob.indexOf("loadCoordinator.grantPermit(generation, retirementPermit)") >= 0)
        check(loadJob.indexOf("loadCoordinator.grantPermit(generation, retirementPermit)") <
            loadJob.indexOf("exo.setMediaSource(videoSource)"))
        check(loadJob.indexOf("exo.setMediaSource(videoSource)") < loadJob.indexOf("exo.prepare()"))
        check("loadCoordinator.commit(loadPermit)" in loadJob)
        check("loadCoordinator.accepts(loadPermit)" in loadJob)

        val analytics = screen.substringAfter("val lifetime = object : AnalyticsListener")
            .substringBefore("exo.addAnalyticsListener(lifetime)")
        val decoderReleased = analytics.substringAfter("override fun onVideoDecoderReleased")
            .substringBefore("override fun onAudioTrackInitialized")
        val decoderInitialized = analytics.substringAfter("override fun onVideoDecoderInitialized")
            .substringBefore("override fun onRenderedFirstFrame")
        check("Media3DecoderRetirements.decoderReleased(exo, decoderName)" in decoderReleased)
        check("Media3DecoderRetirements.decoderInitialized(exo, decoderName)" in decoderInitialized)
    }
}
