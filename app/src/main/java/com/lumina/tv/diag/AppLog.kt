package com.lumina.tv.diag

import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Process lifetime owner. No filesystem work or queue waits occur on callers of start/add/snapshot. */
object AppLog {
    @Volatile private var writer: AppLogWriter? = null

    @Synchronized fun start(directory: File, identity: String) {
        if (writer != null) return
        writer = AppLogWriter(RollingAppLogFile(directory)).also { it.add("process_started $identity") }
    }

    fun add(event: String) { writer?.add(event) }
    fun snapshot(): String = writer?.snapshot() ?: "process log not initialized"
}

internal interface AppLogSink {
    fun initialize()
    fun append(line: String)
}

/** A full queue drops events with a visible counter, without running disk work on the producer. */
internal class AppLogWriter(private val sink: AppLogSink, capacity: Int = 128) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity), { runnable -> Thread(runnable, "lumina-app-log").apply { isDaemon = true } })
    private val dropped = AtomicInteger()
    private val recent = ArrayDeque<String>()
    private var recentChars = 0
    private var ready = false
    @Volatile private var lastFailure: String? = null

    init {
        worker.execute {
            runCatching { sink.initialize(); ready = true }
                .onFailure { lastFailure = "initialize:${it.javaClass.simpleName}" }
        }
    }

    fun add(event: String): Boolean {
        // Omit an oversized input entirely: cutting inside a quoted credential before redaction is unsafe.
        val bounded = if (event.length <= 8192) event else "event omitted: input exceeds 8192 characters"
        return try {
            worker.execute {
                val safe = "${System.currentTimeMillis()}\tdropped=${dropped.get()}\t" + AppLogPrivacy.redact(bounded)
                    .replace("\r", "\\r").replace("\n", "\\n").take(2048)
                synchronized(recent) {
                    recent.addLast(safe)
                    recentChars += safe.length
                    while (recentChars > 32 * 1024) recentChars -= recent.removeFirst().length
                }
                if (ready) runCatching { sink.append(safe + "\n") }
                    .onFailure { lastFailure = "append:${it.javaClass.simpleName}" }
            }
            true
        } catch (_: RejectedExecutionException) {
            dropped.incrementAndGet()
            false
        }
    }

    fun snapshot(): String = "processLog dropped=${dropped.get()} ioFailure=${lastFailure ?: "none"}\n" +
        synchronized(recent) { recent.joinToString("\n") }

    /** A bounded barrier for verification; production logging never waits for the worker. */
    fun awaitIdle(timeoutMs: Long = 2000L): Boolean = runCatching {
        val done = CompletableFuture<Unit>()
        worker.execute { done.complete(Unit) }
        done.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow()
    }
}

/** Only the writer thread calls these methods. Compaction retains the recent tail of this process. */
internal class RollingAppLogFile(directory: File, private val limit: Int = 256 * 1024) : AppLogSink {
    private val current = File(directory, "app.log")
    private val previous = File(directory, "app-prev.log")

    init { require(limit >= 8192) }

    override fun initialize() {
        current.parentFile?.mkdirs()
        if (current.isFile && current.length() > 0L) {
            previous.writeText(AppLogPrivacy.redact(tail(current, limit)), Charsets.UTF_8)
        }
        current.writeText("", Charsets.UTF_8)
    }

    override fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit)
        if (current.length() + bytes.size > limit) {
            val retained = tail(current, minOf(limit / 2, limit - bytes.size))
            current.writeText(retained, Charsets.UTF_8)
        }
        FileOutputStream(current, true).use { it.write(bytes) }
    }

    private fun tail(file: File, bytes: Int): String = RandomAccessFile(file, "r").use {
        val start = maxOf(0L, it.length() - bytes)
        it.seek(start)
        val buffer = ByteArray((it.length() - start).toInt())
        it.readFully(buffer)
        val text = buffer.toString(Charsets.UTF_8)
        if (start > 0L) text.substringAfter('\n', "") else text
    }
}

/** This new disk ingress masks cookie headers too; shared exporter policy is outside B9. */
internal object AppLogPrivacy {
    private val cookieHeader = Regex("""(?im)(?<![\w"'-])((?:set-cookie|cookie)\b[ \t]*[:=][ \t]*)[^\r\n]*""")
    private val cookieJson = Regex("""(?i)(["'](?:set-cookie|cookie)["']\s*:\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")

    fun redact(text: String): String = LogRedactor.redact(text)
        .replace(cookieHeader, "$1***")
        .replace(cookieJson) { it.groupValues[1] + it.groupValues[2].first() + "***" + it.groupValues[2].first() }
}
