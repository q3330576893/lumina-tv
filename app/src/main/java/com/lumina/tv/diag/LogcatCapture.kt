package com.lumina.tv.diag

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Single-flight logcat reader with one deadline covering open, read, and process exit. */
internal object LogcatCapture {
    private val inFlight = AtomicBoolean(false)
    internal val captureActive: Boolean get() = inFlight.get()

    fun read(
        maxBytes: Int,
        timeoutMs: Long,
        open: (DiagnosticReadResources) -> InputStream?,
    ): DiagnosticReadResult {
        require(maxBytes in 0 until Int.MAX_VALUE && timeoutMs > 0)
        if (!inFlight.compareAndSet(false, true)) return DiagnosticReadResult("reader_busy")
        val startedAtNs = System.nanoTime()
        val resources = DiagnosticReadResources()
        val result = CompletableFuture<DiagnosticReadResult>()
        val worker = Thread({
            try {
                val opened = resources.stream(open(resources))
                if (opened == null) {
                    result.complete(DiagnosticReadResult("not_provided"))
                    return@Thread
                }
                val input = opened
                val output = ByteArrayOutputStream(minOf(maxBytes + 1, 4096))
                val buffer = ByteArray(4096)
                while (output.size() <= maxBytes) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Logcat read interrupted")
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        output.write(one)
                    } else output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                val remainingMs = remainingMillis(startedAtNs, timeoutMs)
                if (!resources.awaitProcessExit(remainingMs)) {
                    result.complete(DiagnosticReadResult("timed_out"))
                } else {
                    result.complete(DiagnosticReadResult(
                        if (bytes.isEmpty()) "empty" else "available",
                        bytes.copyOf(minOf(bytes.size, maxBytes)), bytes.size > maxBytes,
                    ))
                }
            } catch (error: Throwable) {
                val cause = error.cause ?: error
                result.complete(DiagnosticReadResult("read_failed:${cause.javaClass.simpleName}"))
            } finally {
                runCatching { resources.close() }
                inFlight.set(false)
            }
        }, "lumina-logcat-reader").apply { isDaemon = true }

        return try {
            worker.start()
            result.get(remainingNanos(startedAtNs, timeoutMs), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            worker.interrupt()
            closeInBackground(resources)
            DiagnosticReadResult("timed_out")
        } catch (_: InterruptedException) {
            worker.interrupt()
            closeInBackground(resources)
            Thread.currentThread().interrupt()
            DiagnosticReadResult("cancelled")
        } catch (error: Throwable) {
            worker.interrupt()
            closeInBackground(resources)
            if (worker.state == Thread.State.NEW) inFlight.set(false)
            DiagnosticReadResult("read_failed:${error.javaClass.simpleName}")
        }
    }

    private fun remainingNanos(startedAtNs: Long, timeoutMs: Long): Long =
        (TimeUnit.MILLISECONDS.toNanos(timeoutMs) - (System.nanoTime() - startedAtNs)).coerceAtLeast(1L)

    private fun remainingMillis(startedAtNs: Long, timeoutMs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(remainingNanos(startedAtNs, timeoutMs)).coerceAtLeast(1L)

    private fun closeInBackground(resources: DiagnosticReadResources) {
        Thread({ runCatching { resources.close() } }, "lumina-logcat-close").apply { isDaemon = true }.start()
    }
}
