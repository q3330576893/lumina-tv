package com.lumina.tv.diag

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** File persistence shared by exit-reason collection and the Java crash handler. */
internal class CrashStore(private val directory: File, private val maxStatsBytes: Int = 120 * 1024) {
    companion object { private val lock = Any() }
    private val statsFile get() = File(directory, "crash-stats.json")

    fun readStats(): JSONObject = runCatching {
        JSONObject(statsFile.readText(Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    fun recordExit(type: String, evidence: String, at: Long) = synchronized(lock) {
        val stats = writableStats()
        stats.put("lastCrash", JSONObject().put("type", type).put("at", at)
            .put("evidence", LogRedactor.redact(evidence)))
        writeStats(stats)
    }

    fun recordJava(fingerprint: String, report: String, at: Long, details: (() -> String)? = null) = synchronized(lock) {
        // A duplicate fingerprint suppresses history only; the current scene is always refreshed.
        val latest = File(directory, "crash-latest.txt")
        latest.writeText(LogRedactor.redact(report), Charsets.UTF_8)
        if (details != null) {
            runCatching { latest.appendText(LogRedactor.redact(details()), Charsets.UTF_8) }
        }
        val stats = writableStats()
        val entry = stats.optJSONObject(fingerprint) ?: JSONObject()
        val count = entry.optInt("count", 0) + 1
        entry.put("type", "JAVA").put("count", count).put("lastAt", at)
        stats.put(fingerprint, entry)

        if (count == 1) {
            for (n in 5 downTo 2) {
                runCatching {
                    File(directory, "crash-${n - 1}.txt").takeIf(File::exists)
                        ?.copyTo(File(directory, "crash-$n.txt"), overwrite = true)
                }
            }
            runCatching { latest.copyTo(File(directory, "crash-1.txt"), overwrite = true) }
        }
        writeStats(stats)
    }

    private fun writableStats(): JSONObject = readStats().also { stats ->
        if (stats.optBoolean("truncated")) {
            stats.optJSONObject("lastCrash")?.put("evidence", "")
            stats.remove("truncated")
            stats.remove("originalBytes")
            stats.remove("maxBytes")
            stats.remove("preview")
        }
    }

    private fun writeStats(stats: JSONObject) {
        directory.mkdirs()
        val lastCrash = stats.optJSONObject("lastCrash")
        if (lastCrash != null) {
            val evidence = lastCrash.optString("evidence", "")
            val maxEvidenceBytes = 8 * 1024
            var end = 0
            var byteCount = 0
            while (end < evidence.length) {
                val codePoint = evidence.codePointAt(end)
                val codePointBytes = when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                if (byteCount + codePointBytes > maxEvidenceBytes) break
                byteCount += codePointBytes
                end += Character.charCount(codePoint)
            }
            if (end < evidence.length) lastCrash.put("evidence", evidence.substring(0, end))
        }
        val bounded = boundedDiagnosticJson(stats.toString(2), maxStatsBytes)
        val destination = statsFile.toPath()
        val temporary = Files.createTempFile(directory.toPath(), "crash-stats-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(bounded.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
