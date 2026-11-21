package com.lumina.tv.player

import java.io.File

/** A string-list fake: -append treats its argument as ONE literal element, whereas
 * the base option parses backslash-escaped commas and print loses that structure.
 * Mirrors mpv 0.41 options/m_option.c, not an echo of the setter argument. */
internal class B17HeaderBinding(
    val propertyWorks: Boolean = true,
    val readWorks: Boolean = true,
    val optionWorks: Boolean = true,
    val appendMask: Int = -1,
) {
    val elements = mutableListOf<String>()
    val calls = mutableListOf<Pair<String, String>>()
    private var appendIndex = 0

    fun setProperty(name: String, value: String) {
        calls += "property:$name" to value
        check(propertyWorks) { "property unavailable" }
        elements.clear()
        elements += parseList(value)
    }

    fun getProperty(name: String): String? {
        calls += "read:$name" to ""
        return if (readWorks) elements.joinToString(",") else null
    }

    fun setOption(name: String, value: String): Int {
        calls += "option:$name" to value
        check(name != "http-header-fields-clr") { "unsupported -clr must never return" }
        if (name == "http-header-fields-append") {
            val accepted = appendMask and (1 shl appendIndex++) != 0
            if (accepted) elements += value
            return if (accepted) 0 else -5
        }
        if (!optionWorks) return -5
        elements.clear()
        elements += parseList(value)
        return 0
    }

    fun apply(headers: Map<String, String>) = applyMpvHttpHeaders(headers, ::setOption)

    companion object {
        fun parseList(value: String): List<String> {
            if (value.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            val element = StringBuilder()
            value.forEach { char ->
                if (char == ',' && element.lastOrNull() == '\\') {
                    element.setLength(element.length - 1)
                    element.append(char)
                } else if (char == ',') {
                    result += element.toString()
                    element.setLength(0)
                } else element.append(char)
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
            "W4 Surface pause wiring" to ::surfaceWiring,
            "W4 transient/delayed focus and latest pause intent" to ::focusPause,
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
            "X-Escaped" to "a\\,b", "X-Note" to "字幕, 中文")
        val binding = B17HeaderBinding()
        val result = binding.apply(headers)
        check(binding.elements == headers.map { (k, v) -> "$k: $v" }) {
            "reported=$result; actual element count=${binding.elements.size}, expected=${headers.size}"
        }
        check(result.complete && result.accepted == headers.size)
        check(binding.calls.all { it.first == "option:http-header-fields-append" })

        // A rejected/throwing item must not block later headers or claim completeness.
        for (error in listOf<Throwable>(IllegalStateException("rejected"), UnsatisfiedLinkError("missing API"))) {
            var calls = 0
            val partial = applyMpvHttpHeaders(headers) { _, _ -> if (++calls == 2) throw error else 1 }
            check(calls == headers.size && partial.accepted == headers.size - 1 && !partial.complete)
            check(partial.notice?.contains("1/${headers.size}") == true)
        }
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
        warning.property("time-pos", 100.0)
        warning.log(30, "HTTP error 403 Forbidden")
        warning.log(40, "Opening https://example.invalid/?code=503")
        check(classifyMpvEndFile(warning.snapshot()) == MpvEndKind.EOF)
        warning.log(20, "unclassified native error")
        check(warning.snapshot().failureKind == null)
        check(classifyMpvEndFile(warning.snapshot()) == MpvEndKind.UNKNOWN)
        check(!mpvEndAllowsRecovery(classifyMpvEndFile(warning.snapshot())))
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
}
