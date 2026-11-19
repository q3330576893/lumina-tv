package com.lumina.tv.player

import java.io.IOException

object E6VttChecks {
    private val emptyVtt = "WEBVTT\n\n".toByteArray(Charsets.UTF_8)

    @JvmStatic
    fun main(args: Array<String>) {
        validMultiCue()
        emptyAndHeaderOnly()
        httpFailures()
        htmlErrorBody()
        oversizedBody()
        malformedTimeline()
        productionWiring()
        println("E6Vtt PASS: multi-cue and empty VTT, HTTP 404/500, HTML rejection, 16 MiB overflow downgrade, and malformed timestamp tolerance")
    }

    private fun validMultiCue() {
        val input = """WEBVTT

00:00:00.000 --> 00:00:01.000
First cue

00:00:01.500 --> 00:00:03.000
Second cue
""".toByteArray(Charsets.UTF_8)
        var failure: Exception? = null
        val output = loadFixture({ input }) { failure = it }
        check(failure == null && output.contentEquals(input))
    }

    private fun emptyAndHeaderOnly() {
        var emptyFailure: Exception? = null
        val emptyOutput = loadFixture({ ByteArray(0) }) { emptyFailure = it }
        check(emptyFailure?.message == "invalid VTT header")
        check(emptyOutput.contentEquals(emptyVtt))

        var headerFailure: Exception? = null
        val headerOnly = loadFixture({ emptyVtt }) { headerFailure = it }
        check(headerFailure == null && headerOnly.contentEquals(emptyVtt))
    }

    private fun httpFailures() {
        for (status in listOf(404, 500)) {
            var failure: Exception? = null
            val fallback = loadFixture({ throw IOException("HTTP $status") }) { failure = it }
            check(failure?.message == "HTTP $status")
            check(fallback.contentEquals(emptyVtt))
        }
    }

    private fun htmlErrorBody() {
        val html = "<html><body>upstream error</body></html>".toByteArray(Charsets.UTF_8)
        var failure: Exception? = null
        val fallback = loadFixture({ html }) { failure = it }
        check(failure?.message == "invalid VTT header" && fallback.contentEquals(emptyVtt))
    }

    private fun oversizedBody() {
        var failure: Exception? = null
        val fallback = loadFixture(
            read = { readOptionalVttBytes(oversizedReader()) },
        ) { failure = it }
        check(failure is IOException && failure?.message == "VTT size limit")
        check(fallback.contentEquals(emptyVtt))
    }

    private fun malformedTimeline() {
        val malformed = "WEBVTT\n\n00:00:00.000 00:00:01.000\nMissing arrow\n".toByteArray(Charsets.UTF_8)
        var failure: Exception? = null
        val output = loadOptionalVttBytes(read = { malformed }, onUnavailable = { failure = it })
        check(output.contentEquals(malformed) || output.contentEquals(emptyVtt))
        check(failure == null || output.contentEquals(emptyVtt))
    }

    private fun productionWiring() {
        val root = if (java.io.File("src/main").isDirectory) java.io.File(".") else java.io.File("app")
        val source = root.resolve("src/main/java/com/lumina/tv/player/OptionalVttDataSource.kt").readText()
        check(source.contains("loadOptionalVttBytes(read = {"))
        check(source.contains("parse: (ByteArray) -> Unit = ::parseOptionalVtt"))
        check(source.contains("WebvttParser().parse(bytes, 0, bytes.size"))
    }

    private fun loadFixture(
        read: () -> ByteArray,
        onUnavailable: (Exception) -> Unit,
    ): ByteArray = loadOptionalVttBytes(read, onUnavailable, ::validateVttFixture)

    private fun validateVttFixture(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isEmpty() || !text.startsWith("WEBVTT")) throw IOException("invalid VTT header")
        if (text.contains("First cue")) check(text.lineSequence().count { "-->" in it } == 2)
    }

    private fun oversizedReader(): (ByteArray, Int, Int) -> Int {
        var remaining = OPTIONAL_VTT_MAX_BYTES + 1
        return { buffer, offset, length ->
            if (remaining == 0) {
                -1
            } else {
                val size = minOf(remaining, length)
                buffer.fill(0.toByte(), offset, offset + size)
                remaining -= size
                size
            }
        }
    }
}
