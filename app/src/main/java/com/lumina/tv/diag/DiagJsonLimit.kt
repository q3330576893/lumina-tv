package com.lumina.tv.diag

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Below two bytes no JSON object can fit. Such requests deliberately use the two-byte floor. */
internal const val MIN_DIAGNOSTIC_JSON_BYTES = 2

/** The serialized UTF-8 envelope, including escaped preview text, always fits the effective budget. */
internal fun boundedDiagnosticJson(text: String, maxBytes: Int): String {
    val budget = maxOf(MIN_DIAGNOSTIC_JSON_BYTES, maxBytes)
    val normalized = runCatching {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "Trailing data after JSON value" }
        when (value) {
            is JSONObject, is JSONArray -> value.toString()
            else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
        }
    }.getOrNull()
    if (normalized != null && normalized.toByteArray(Charsets.UTF_8).size <= budget) return normalized
    val result = JSONObject()
    fun fits() = result.toString().toByteArray(Charsets.UTF_8).size <= budget
    fun optional(key: String, value: Any) {
        result.put(key, value)
        if (!fits()) result.remove(key)
    }
    if (normalized == null) {
        optional("invalidJson", true)
        optional("reason", "parse_failed")
    } else {
        optional("truncated", true)
        optional("originalBytes", normalized.toByteArray(Charsets.UTF_8).size)
        optional("maxBytes", maxBytes)
    }
    // Test the actual encoder rather than estimating characters, Unicode, or JSON escape costs.
    // An unterminated JSON credential cannot be structurally redacted. Never archive its raw prefix.
    val preview = if (normalized == null) "invalid JSON input omitted" else text.take(2000).replace('\n', ' ').replace('\r', ' ')
    val ends = mutableListOf(0)
    var end = 0
    while (end < preview.length) {
        end += Character.charCount(preview.codePointAt(end))
        ends += end
    }
    var low = 0
    var high = ends.lastIndex
    var best = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        result.put("preview", preview.substring(0, ends[middle]))
        if (fits()) { best = middle; low = middle + 1 } else high = middle - 1
    }
    if (best >= 0) result.put("preview", preview.substring(0, ends[best])) else result.remove("preview")
    return result.toString()
}

/** Byte-limited seek, never a whole-file read. Discard the partial first line before redaction. IO only. */
internal fun readDiagnosticTail(file: File, maxBytes: Int = 120 * 1024): String {
    require(maxBytes >= 0)
    if (!file.isFile || maxBytes == 0) return ""
    val tail = RandomAccessFile(file, "r").use { input ->
        val end = input.length()
        val start = maxOf(0L, end - maxBytes)
        input.seek(start)
        val bytes = ByteArray((end - start).toInt())
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        val offset = if (start == 0L) 0 else bytes.indexOf('\n'.code.toByte()).let { if (it < 0) size else it + 1 }
        String(bytes, offset, size - offset, Charsets.UTF_8)
    }
    return LogRedactor.redact(tail)
}

internal fun appendDiagnosticLog(file: File, text: String, maxBytes: Int) {
    val safe = LogRedactor.redact(text)
    val bytes = safe.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes)
    if (file.length() + bytes.size > maxBytes) {
        val retained = readDiagnosticTail(file, minOf(maxBytes / 2, maxBytes - bytes.size))
        file.writeText(if (retained.toByteArray(Charsets.UTF_8).size + bytes.size <= maxBytes) retained else "", Charsets.UTF_8)
    }
    file.appendBytes(bytes)
}

/** One scheduled drain, bounded pending bytes/records, and one disk append per batch. */
internal class DiagnosticLogBuffer(
    private val execute: (() -> Unit) -> Unit,
    private val writeBatch: (String) -> Unit,
    private val capacity: Int = 128,
    private val maxPendingBytes: Int = 128 * 1024,
    private val batchSize: Int = 32,
    private val report: (String) -> Unit = {},
) {
    init { require(capacity > 0 && maxPendingBytes > 0 && batchSize > 0) }
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private val recent = ArrayDeque<String>()
    private var pendingBytes = 0
    private var recentBytes = 0
    private var scheduled = false
    private var closed = false
    private var finalSummaryPending = false
    private var dropped = 0L
    private var evicted = 0L
    private var failures = 0L

    fun add(text: String): Boolean {
        var schedule = false
        val accepted = synchronized(lock) {
            // Do not cut inside a credential before the IO worker can redact it.
            val line = if (text.length <= 8192) text else "mpv log omitted: input exceeds 8192 characters"
            if (line !== text) dropped++
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (closed || pending.size >= capacity || pendingBytes + size > maxPendingBytes) {
                dropped++
                false
            } else {
                pending.addLast(line); pendingBytes += size
                recent.addLast(line); recentBytes += size
                while (recentBytes > 128 * 1024 || recent.size > 2000) {
                    recentBytes -= recent.removeFirst().toByteArray(Charsets.UTF_8).size + 1
                    evicted++
                }
                if (!scheduled) { scheduled = true; schedule = true }
                true
            }
        }
        if (schedule) scheduleDrain()
        return accepted
    }

    fun snapshot(): String = synchronized(lock) { status() + "\n" + recent.joinToString("\n") }
    fun summary(): String = synchronized(lock) { status() }
    fun lastLine(): String = synchronized(lock) { recent.lastOrNull() ?: "none" }
    fun finish() {
        val schedule = synchronized(lock) {
            if (closed) return
            closed = true
            finalSummaryPending = true
            if (scheduled) false else { scheduled = true; true }
        }
        if (schedule) scheduleDrain() // Queue a final counter flush; never wait for disk on release.
    }
    private fun status() = "mpvLog dropped=$dropped evicted=$evicted ioFailures=$failures pending=${pending.size}"

    private fun scheduleDrain() {
        try { execute(::drain) } catch (_: Exception) {
            synchronized(lock) {
                failures++; dropped += pending.size
                pending.clear(); pendingBytes = 0; scheduled = false
            }
            runCatching { report(summary()) }
        }
    }

    private fun drain() {
        var records = 0
        val batch = synchronized(lock) {
            finalSummaryPending = false
            buildString {
                appendLine(status())
                var bytes = 0
                var count = 0
                while (pending.isNotEmpty() && count < batchSize) {
                    val nextBytes = pending.first().toByteArray(Charsets.UTF_8).size + 1
                    if (count > 0 && bytes + nextBytes > 64 * 1024) break
                    val line = pending.removeFirst()
                    pendingBytes -= nextBytes
                    bytes += nextBytes
                    count++
                    records++
                    appendLine(line)
                }
            }
        }
        try { writeBatch(LogRedactor.redact(batch)) } catch (_: Exception) {
            synchronized(lock) { failures++; dropped += records }
            runCatching { report(summary()) }
        } finally {
            val again = synchronized(lock) {
                if (pending.isEmpty() && !finalSummaryPending) { scheduled = false; false } else true
            }
            if (again) scheduleDrain()
        }
    }
}

/** Snapshot failure and diagnostic submission failure must never prevent resource release. */
internal fun releaseWithDiagnosticSnapshot(snapshot: () -> String, release: () -> Unit, submit: (String) -> Unit) {
    val frozen = try { snapshot() } catch (_: Throwable) { "{\"captureFailed\":true}" }
    try { release() } finally { runCatching { submit(frozen) } }
}

/** Coalesces queued snapshots. A late old-session submission cannot displace a newer one. */
internal class LatestDiagnosticWrite<T>(
    private val execute: (() -> Unit) -> Unit,
    private val write: (T) -> Unit,
) {
    private val lock = Any()
    private var newest = Long.MIN_VALUE
    private var pending: Pair<Long, T>? = null
    private var scheduled = false
    val failures = AtomicLong()
    val stale = AtomicLong()

    fun submit(session: Long, snapshot: T): Boolean {
        val schedule = synchronized(lock) {
            if (session < newest) { stale.incrementAndGet(); return false }
            newest = session
            pending = session to snapshot
            if (scheduled) false else { scheduled = true; true }
        }
        if (schedule) try { execute(::drain) } catch (_: Exception) {
            synchronized(lock) { scheduled = false; pending = null; failures.incrementAndGet() }
        }
        return true
    }

    private fun drain() {
        val value = synchronized(lock) { pending.also { pending = null } }
        try { if (value != null) write(value.second) } catch (_: Exception) { failures.incrementAndGet() }
        finally {
            val again = synchronized(lock) { if (pending == null) { scheduled = false; false } else true }
            if (again) try { execute(::drain) } catch (_: Exception) {
                synchronized(lock) { scheduled = false; pending = null; failures.incrementAndGet() }
            }
        }
    }
}
