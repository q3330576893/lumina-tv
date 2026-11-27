package com.lumina.tv.diag

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

/** Owns resources independently of the blocked reader. Late opens after cancellation close immediately. */
internal class DiagnosticReadResources : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var input: InputStream? = null
    private var process: Process? = null

    fun stream(value: InputStream?): InputStream? {
        if (value == null) return null
        val reject = synchronized(lock) { if (closed) true else { input = value; false } }
        if (reject) { runCatching { value.close() }; throw InterruptedException("Capture closed") }
        return value
    }

    fun process(value: Process): Process {
        val reject = synchronized(lock) { if (closed) true else { process = value; false } }
        if (reject) { stop(value); throw InterruptedException("Capture closed") }
        return value
    }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            (process to input).also { process = null; input = null }
        }
        // Kill the producer first: closing a pipe whose reader holds a monitor can otherwise block.
        val processInput = resources.first?.let(::stop)
        if (resources.second !== processInput) runCatching { resources.second?.close() }
    }

    private fun stop(value: Process): InputStream? {
        runCatching { value.destroyForcibly() }.onFailure { runCatching { value.destroy() } }
        runCatching { value.outputStream.close() }
        runCatching { value.errorStream.close() }
        val input = runCatching { value.inputStream }.getOrNull()
        runCatching { input?.close() }
        return input
    }
}

internal data class DiagnosticReadResult(val status: String, val bytes: ByteArray = byteArrayOf(), val truncated: Boolean = false)

/** Fixed worker ceiling even if a platform opener ignores interrupts. No unbounded task queue. */
internal object DiagnosticCapture {
    private val readers = ThreadPoolExecutor(0, 2, 10L, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "lumina-diagnostic-reader").apply { isDaemon = true } })
    internal val activeReaders: Int get() = readers.activeCount

    fun read(
        maxBytes: Int,
        timeoutMs: Long,
        resources: DiagnosticReadResources = DiagnosticReadResources(),
        open: (DiagnosticReadResources) -> InputStream?,
    ): DiagnosticReadResult {
        require(maxBytes in 0 until Int.MAX_VALUE && timeoutMs > 0)
        val task = FutureTask {
            try {
                val input = resources.stream(open(resources)) ?: return@FutureTask DiagnosticReadResult("not_provided")
                val output = ByteArrayOutputStream(minOf(maxBytes + 1, 4096))
                val buffer = ByteArray(4096)
                while (output.size() <= maxBytes) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Capture interrupted")
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val single = input.read()
                        if (single < 0) break
                        output.write(single)
                    } else output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                DiagnosticReadResult(if (bytes.isEmpty()) "empty" else "available",
                    bytes.copyOf(minOf(bytes.size, maxBytes)), bytes.size > maxBytes)
            } finally { resources.close() }
        }
        return try {
            readers.execute(task)
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            DiagnosticReadResult("timed_out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DiagnosticReadResult("cancelled")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            DiagnosticReadResult("reader_busy")
        } catch (error: Exception) {
            val cause = error.cause ?: error
            DiagnosticReadResult("read_failed:${cause.javaClass.simpleName}")
        } finally {
            task.cancel(true)
            resources.close()
        }
    }
}

/** Read a bounded prefix plus sentinel; a real deadline closes the stream, including on cancellation. */
object ExitTraceReader {
    fun read(kind: ExitTraceKind, sdkInt: Int, timeoutMs: Long = 1500L, open: () -> InputStream?): ExitTraceEvidence {
        if (sdkInt < kind.minSdk) return ExitTraceEvidence(kind, "unsupported_api")
        val result = DiagnosticCapture.read(kind.limit, timeoutMs) { open() }
        return ExitTraceEvidence(kind, result.status, result.bytes, result.truncated)
    }
}
