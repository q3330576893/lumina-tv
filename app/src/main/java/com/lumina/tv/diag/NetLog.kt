package com.lumina.tv.diag

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.ArrayDeque

data class NetRecord(val at: Long, val method: String, val path: String, val ms: Long, val code: Int?, val error: String?)
data class PageRecord(val at: Long, val name: String)

object NetLog {
    private val records = ArrayDeque<NetRecord>()
    @Synchronized fun add(at: Long, method: String, url: String, ms: Long, code: Int?, error: String?) {
        val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().ifBlank { "/" }
        records.addLast(NetRecord(at, method, path, ms, code, error?.take(120)))
        while (records.size > 30) records.removeFirst()
    }
    @Synchronized fun tail(): String = JSONArray().apply {
        records.forEach { put(JSONObject().put("at", it.at).put("method", it.method).put("path", it.path).put("ms", it.ms).put("code", it.code).put("error", it.error)) }
    }.toString(2)
}

object PageLog {
    private val records = ArrayDeque<PageRecord>()
    @Synchronized fun add(name: String) {
        records.addLast(PageRecord(System.currentTimeMillis(), name.take(80)))
        while (records.size > 20) records.removeFirst()
    }
    @Synchronized fun tail(): String = records.joinToString("\n") { "${it.at}\t${it.name}" }
}
