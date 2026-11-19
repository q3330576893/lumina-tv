package com.lumina.tv.diag

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Keep archived JSON parseable even when a diagnostic entry exceeds its size budget. */
internal fun boundedDiagnosticJson(text: String, maxBytes: Int): String {
    require(maxBytes > 0)
    val normalized = runCatching {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "Trailing data after JSON value" }
        when (value) {
            is JSONObject, is JSONArray -> value.toString()
            else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
        }
    }
        .getOrElse {
            return JSONObject().put("truncated", true).put("reason", "invalid_json").toString()
        }
    val originalBytes = normalized.toByteArray(Charsets.UTF_8).size
    if (originalBytes <= maxBytes) return normalized
    return JSONObject()
        .put("truncated", true)
        .put("originalBytes", originalBytes)
        .put("maxBytes", maxBytes)
        .toString()
}
