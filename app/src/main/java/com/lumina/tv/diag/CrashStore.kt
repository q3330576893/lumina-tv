package com.lumina.tv.diag

import org.json.JSONObject
import java.io.File

/** File persistence shared by exit-reason collection and the Java crash handler. */
internal class CrashStore(private val directory: File) {
    companion object { private val lock = Any() }
    private val statsFile get() = File(directory, "crash-stats.json")

    fun readStats(): JSONObject = runCatching {
        JSONObject(statsFile.readText(Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    fun recordExit(type: String, evidence: String, at: Long) = synchronized(lock) {
        val stats = readStats()
        stats.put("lastCrash", JSONObject().put("type", type).put("at", at)
            .put("evidence", LogRedactor.redact(evidence)))
        statsFile.writeText(stats.toString(2), Charsets.UTF_8)
    }

    fun recordJava(fingerprint: String, report: String, at: Long) = synchronized(lock) {
        val stats = readStats()
        val entry = stats.optJSONObject(fingerprint) ?: JSONObject()
        val count = entry.optInt("count", 0) + 1
        entry.put("type", "JAVA").put("count", count).put("lastAt", at)
        stats.put(fingerprint, entry)

        // A duplicate fingerprint suppresses history only; the current scene is always refreshed.
        val safe = LogRedactor.redact(report)
        File(directory, "crash-latest.txt").writeText(safe, Charsets.UTF_8)
        if (count == 1) {
            for (n in 5 downTo 2) {
                File(directory, "crash-${n - 1}.txt").takeIf(File::exists)
                    ?.copyTo(File(directory, "crash-$n.txt"), overwrite = true)
            }
            File(directory, "crash-1.txt").writeText(safe, Charsets.UTF_8)
        }
        statsFile.writeText(stats.toString(2), Charsets.UTF_8)
    }
}
