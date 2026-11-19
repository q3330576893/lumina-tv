package com.lumina.tv.diag

import java.io.ByteArrayOutputStream
import java.io.InputStream

enum class ExitTraceKind(val minSdk: Int, val format: String, val limit: Int) {
    ANR(30, "text", 64 * 1024),
    NATIVE(31, "tombstone-protobuf", 256 * 1024),
}

data class ExitTraceEvidence(
    val kind: ExitTraceKind,
    val status: String,
    val bytes: ByteArray = byteArrayOf(),
    val truncated: Boolean = false,
) {
    val nativeFileName: String get() = if (truncated) "native-trace-latest.pb.partial" else "native-trace-latest.pb"
    val summary: String get() = "source=ApplicationExitInfo.getTraceInputStream format=${kind.format} " +
        "status=$status capturedBytes=${bytes.size} truncated=$truncated"

    fun report(): String = summary + when {
        status != "available" -> "\nSystem exit trace unavailable; restricted system directories were not used."
        kind == ExitTraceKind.ANR -> "\n" + LogRedactor.redact(bytes.toString(Charsets.UTF_8))
        else -> "\nBinary evidence file=$nativeFileName (private); " +
            if (truncated) "bounded prefix only, not a complete protobuf; no text decoding attempted."
            else "complete bounded protobuf; no text decoding attempted."
    }
}

/** Read at most limit + one sentinel byte, close on every path, and never decode native protobuf as text. */
object ExitTraceReader {
    fun read(kind: ExitTraceKind, sdkInt: Int, open: () -> InputStream?): ExitTraceEvidence {
        if (sdkInt < kind.minSdk) return ExitTraceEvidence(kind, "unsupported_api")
        return try {
            val stream = open() ?: return ExitTraceEvidence(kind, "not_provided")
            stream.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= kind.limit) {
                    val count = it.read(buffer, 0, minOf(buffer.size, kind.limit + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val single = it.read()
                        if (single < 0) break
                        output.write(single)
                    } else output.write(buffer, 0, count)
                }
                val captured = output.toByteArray()
                ExitTraceEvidence(kind, if (captured.isEmpty()) "empty" else "available",
                    captured.copyOf(minOf(captured.size, kind.limit)), captured.size > kind.limit)
            }
        } catch (error: Exception) {
            ExitTraceEvidence(kind, "read_failed:${error.javaClass.simpleName}")
        }
    }
}
