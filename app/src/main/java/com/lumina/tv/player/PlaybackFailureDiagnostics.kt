package com.lumina.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/** Observes the OkHttp calls made by Media3 without retaining credential values. */
internal class PlaybackHttpTrace(private val currentLoad: () -> JSONObject?) : EventListener.Factory {
    private data class TimedAttempt(
        val load: String,
        val startedAtNs: Long,
        val url: String,
        val headers: JSONArray,
        val startSource: String,
        val redirects: JSONArray = JSONArray(),
        var status: Int? = null,
        var contentType: String? = null,
        var dnsStartedAtNs: Long? = null,
        var dnsMs: Long? = null,
        var connectStartedAtNs: Long? = null,
        var connectMs: Long? = null,
        var tlsStartedAtNs: Long? = null,
        var tlsMs: Long? = null,
        var firstByteMs: Long? = null,
        var totalMs: Long? = null,
        var result: String = "in_flight",
        var phase: String = "request",
        var phaseSource: String = startSource,
        var completionSource: String? = null,
        var errorClass: String? = null,
        var errorMessage: String? = null,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("loadIdentity", load)
            .put("source", "current")
            .put("url", url)
            .put("status", status ?: JSONObject.NULL)
            .put("responseContentType", contentType ?: JSONObject.NULL)
            .put("redirectChain", JSONArray(redirects.toString()))
            .put("requestHeaders", JSONArray(headers.toString()))
            .put("timings", JSONObject()
                .put("dns", dnsMs ?: JSONObject.NULL)
                .put("connect", connectMs ?: JSONObject.NULL)
                .put("tls", tlsMs ?: JSONObject.NULL)
                .put("firstByte", firstByteMs ?: JSONObject.NULL)
                .put("total", totalMs ?: JSONObject.NULL))
            .put("result", result)
            .put("phase", phase)
            .put("errorClass", errorClass ?: JSONObject.NULL)
            .put("errorMessage", errorMessage ?: JSONObject.NULL)
            .put("evidence", JSONObject()
                .put("request", "Call.request")
                .put("start", startSource)
                .put("phase", phaseSource)
                .put("completion", completionSource ?: JSONObject.NULL))
    }

    private val lock = Any()
    private val attempts = ArrayDeque<TimedAttempt>()

    override fun create(call: Call): EventListener {
        return object : EventListener() {
            private var attempt: TimedAttempt? = null

            // A request exists even if DNS/connect/TLS never produces a response callback.
            private fun observe(call: Call, event: String, update: (TimedAttempt) -> Unit) = synchronized(lock) {
                val current = attempt ?: TimedAttempt(
                    load = currentLoad()?.toString().orEmpty(),
                    startedAtNs = System.nanoTime(),
                    url = safePlaybackUrl(call.request().url.toString()),
                    headers = fingerprintHeaders(call.request().headers.names().map { it to call.request().header(it).orEmpty() }),
                    startSource = event,
                ).also {
                    attempt = it
                    attempts.addLast(it)
                    while (attempts.size > MAX_ATTEMPTS) attempts.removeFirst()
                }
                update(current)
            }

            override fun callStart(call: Call) = observe(call, "EventListener.callStart") { }

            override fun dnsStart(call: Call, domainName: String) = observe(call, "EventListener.dnsStart") { attempt ->
                attempt.phase = "dns"
                attempt.phaseSource = "EventListener.dnsStart"
                attempt.dnsStartedAtNs = System.nanoTime()
            }

            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = observe(call, "EventListener.dnsEnd") { attempt ->
                attempt.dnsStartedAtNs?.let { attempt.dnsMs = elapsedMs(it) }
            }

            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = observe(call, "EventListener.connectStart") { attempt ->
                attempt.phase = "connect"
                attempt.phaseSource = "EventListener.connectStart"
                if (attempt.connectStartedAtNs == null) attempt.connectStartedAtNs = System.nanoTime()
            }

            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = observe(call, "EventListener.connectEnd") { attempt ->
                attempt.connectStartedAtNs?.let { attempt.connectMs = elapsedMs(it) }
                attempt.phase = "request"
                attempt.phaseSource = "EventListener.connectEnd"
            }

            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = observe(call, "EventListener.connectFailed") { attempt ->
                attempt.connectStartedAtNs?.let { attempt.connectMs = elapsedMs(it) }
            }

            override fun secureConnectStart(call: Call) = observe(call, "EventListener.secureConnectStart") { attempt ->
                attempt.phase = "tls"
                attempt.phaseSource = "EventListener.secureConnectStart"
                attempt.tlsStartedAtNs = System.nanoTime()
            }

            override fun secureConnectEnd(call: Call, handshake: Handshake?) = observe(call, "EventListener.secureConnectEnd") { attempt ->
                attempt.tlsStartedAtNs?.let { attempt.tlsMs = elapsedMs(it) }
            }

            override fun requestHeadersStart(call: Call) = observe(call, "EventListener.requestHeadersStart") { attempt ->
                attempt.phase = "request"
                attempt.phaseSource = "EventListener.requestHeadersStart"
            }

            override fun responseHeadersStart(call: Call) = observe(call, "EventListener.responseHeadersStart") { attempt ->
                if (attempt.firstByteMs == null) attempt.firstByteMs = elapsedMs(attempt.startedAtNs)
            }

            override fun responseHeadersEnd(call: Call, response: Response) = observe(call, "EventListener.responseHeadersEnd") { attempt ->
                attempt.status = response.code
                attempt.contentType = response.header("Content-Type")
                attempt.redirects.put(JSONObject()
                    .put("status", response.code)
                    .put("host", response.request.url.host)
                    .put("source", "current"))
            }

            override fun callEnd(call: Call) = finish(call, null)
            override fun callFailed(call: Call, ioe: IOException) = finish(call, ioe)

            private fun finish(call: Call, failure: IOException?) = observe(call, if (failure == null) "EventListener.callEnd" else "EventListener.callFailed") { attempt ->
                val finishedAtNs = System.nanoTime()
                attempt.totalMs = elapsedMs(attempt.startedAtNs, finishedAtNs)
                attempt.completionSource = if (failure == null) "EventListener.callEnd" else "EventListener.callFailed"
                attempt.result = when {
                    failure != null -> "failed"
                    (attempt.status ?: 0) >= 400 -> "http_${attempt.status}"
                    else -> "success"
                }
                if (failure != null) {
                    attempt.errorClass = failure.javaClass.name
                    attempt.errorMessage = failure.message?.let { com.lumina.tv.diag.LogRedactor.redact(it) }
                    if (attempt.phaseSource == "EventListener.callStart" || attempt.phaseSource == "EventListener.callFailed") {
                        // No phase callback: classify from the failure, keeping the fallback explicit.
                        val causes = throwableChain(failure)
                        attempt.phase = when {
                            causes.any { it is UnknownHostException } -> "dns"
                            causes.any { it is ConnectException } -> "connect"
                            causes.any { it is SSLException } -> "tls"
                            else -> "request"
                        }
                        attempt.phaseSource = "EventListener.callFailed.exception"
                    }
                    attempt.dnsStartedAtNs?.takeIf { attempt.dnsMs == null }?.let { attempt.dnsMs = elapsedMs(it, finishedAtNs) }
                    attempt.connectStartedAtNs?.takeIf { attempt.connectMs == null }?.let { attempt.connectMs = elapsedMs(it, finishedAtNs) }
                    attempt.tlsStartedAtNs?.takeIf { attempt.tlsMs == null }?.let { attempt.tlsMs = elapsedMs(it, finishedAtNs) }
                }
            }
        }
    }

    fun snapshot(loadIdentity: JSONObject?): JSONObject {
        val identity = loadIdentity?.toString().orEmpty()
        val selected = synchronized(lock) { attempts.filter { it.load == identity }.map { it.json() } }
        val last = selected.lastOrNull()
        return JSONObject()
            .put("source", "current")
            .put("url", last?.optString("url")?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("status", last?.opt("status") ?: JSONObject.NULL)
            .put("statusState", when {
                last == null -> "not_collected"
                last.isNull("status") -> "no_response_received"
                else -> "received"
            })
            .put("responseContentType", last?.opt("responseContentType") ?: JSONObject.NULL)
            .put("redirectChain", last?.optJSONArray("redirectChain") ?: JSONArray())
            .put("attempts", JSONArray(selected))
            .put("attemptCount", selected.size)
            .put("timings", last?.optJSONObject("timings") ?: JSONObject()
                .put("dns", JSONObject.NULL).put("connect", JSONObject.NULL).put("tls", JSONObject.NULL)
                .put("firstByte", JSONObject.NULL).put("total", JSONObject.NULL))
            .put("requestHeaders", last?.optJSONArray("requestHeaders") ?: JSONArray())
    }

    private fun elapsedMs(startedAtNs: Long, endedAtNs: Long = System.nanoTime()): Long = ((endedAtNs - startedAtNs) / 1_000_000L).coerceAtLeast(0L)

    private fun fingerprintHeaders(headers: Iterable<Pair<String, String>>): JSONArray = JSONArray().apply {
        headers.sortedBy { it.first.lowercase() }.forEach { (name, value) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it) }
            put("$name(len=${value.toByteArray(Charsets.UTF_8).size}, sha8=$digest)")
        }
    }

    private companion object { const val MAX_ATTEMPTS = 64 }
}

internal fun safePlaybackUrl(raw: String): String = runCatching {
    val url = raw.toHttpUrlOrNull() ?: return@runCatching com.lumina.tv.diag.LogRedactor.redact(raw)
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val query = url.encodedQuery?.split('&')?.joinToString("&") { it.substringBefore('=') + "=***" }
        ?.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
    "${url.scheme}://$host:${url.port}${url.encodedPath}$query"
}.getOrElse { com.lumina.tv.diag.LogRedactor.redact(raw) }

internal data class PlaybackFailureInput(
    val failure: Throwable?,
    val playbackException: PlaybackException?,
    val http: JSONObject,
    val sessionId: String?,
    val generation: Int?,
    val loadIdentity: JSONObject?,
    val engine: String,
    val decoderName: String?,
    val decoderMime: String?,
    val hardwareAccelerated: Boolean?,
    val initResult: String?,
    val fallbackHistory: JSONArray,
    val audioProbe: JSONObject,
    val audioSelection: JSONObject,
    val phaseTimeline: JSONObject,
    val nativeVersions: JSONObject,
    val abi: JSONArray,
    val hdr: JSONArray?,
    val engineSelectionReason: String,
    val source: String = "current",
)

internal fun playbackFailureJson(input: PlaybackFailureInput): JSONObject {
    val error = input.playbackException ?: input.failure
    val causes = throwableChain(error)
    val root = causes.firstOrNull()
    val media3 = input.playbackException
    val http = JSONObject(input.http.toString())
    fillHttpFromException(http, causes)
    val timeline = JSONObject(input.phaseTimeline.toString())
        .put("sessionId", input.sessionId ?: JSONObject.NULL)
        .put("generation", input.generation ?: JSONObject.NULL)
        .put("loadIdentity", input.loadIdentity ?: JSONObject.NULL)
        .put("source", input.source)
    val exception = root?.let { causeJson(it, input.source) } ?: JSONObject()
        .put("class", JSONObject.NULL).put("message", JSONObject.NULL)
        .put("cause", JSONObject.NULL).put("stackFrames", JSONArray())
    exception.put("source", input.source)
    return JSONObject()
        .put("schemaVersion", 1)
        .put("exception", exception)
        .put("media3", JSONObject()
            .put("errorCode", media3?.errorCode ?: JSONObject.NULL)
            .put("errorCodeName", media3?.errorCodeName ?: JSONObject.NULL)
            .put("rendererName", readableProperty(media3, "rendererName") ?: JSONObject.NULL)
            .put("mediaPeriodId", input.phaseTimeline.opt("mediaPeriodId") ?: JSONObject.NULL)
            .put("source", input.source))
        .put("http", http.put("source", input.source))
        .put("decoder", JSONObject()
            .put("name", input.decoderName ?: JSONObject.NULL)
            .put("mime", input.decoderMime ?: JSONObject.NULL)
            .put("hardwareAccelerated", input.hardwareAccelerated ?: JSONObject.NULL)
            .put("initResult", input.initResult ?: JSONObject.NULL)
            .put("fallbackHistory", JSONArray(input.fallbackHistory.toString()))
            .put("source", input.source))
        .put("audio", JSONObject()
            .put("probe", JSONObject(input.audioProbe.toString()).put("source", input.source))
            .put("finalSelection", JSONObject(input.audioSelection.toString()).put("source", input.source))
            .put("source", input.source))
        .put("timeline", timeline)
        .put("environment", JSONObject()
            .put("native", JSONObject(input.nativeVersions.toString()))
            .put("abi", JSONArray(input.abi.toString()))
            .put("hdr", input.hdr ?: JSONObject.NULL)
            .put("engine", input.engine)
            .put("selectionReason", input.engineSelectionReason)
            .put("source", input.source))
}

internal fun diagnosticsGap(record: JSONObject, reason: String = "required playback facts were not captured"): JSONObject {
    val required = listOf(
        "exception.class", "exception.message", "exception.cause", "exception.stackFrames",
        "media3.errorCode", "media3.errorCodeName", "media3.rendererName", "media3.mediaPeriodId",
        "http.url", "http.status", "http.responseContentType", "http.redirectChain", "http.attemptCount", "http.attempts",
        "http.timings.dns", "http.timings.connect", "http.timings.tls", "http.timings.firstByte", "http.timings.total",
        "http.requestHeaders", "decoder.name", "decoder.mime", "decoder.hardwareAccelerated",
        "decoder.initResult", "decoder.fallbackHistory", "audio.probe.api", "audio.probe.input",
        "audio.probe.rawResult", "audio.finalSelection", "timeline.loadStartedMs", "timeline.failureElapsedMs",
        "timeline.sessionId", "timeline.generation", "timeline.loadIdentity", "environment.native",
        "environment.abi", "environment.hdr", "environment.engine", "environment.selectionReason",
    )
    val missing = JSONArray()
    required.filterNot { hasJsonPath(record, it) }.forEach(missing::put)
    return JSONObject().put("missing", missing).put("reason", reason)
}

private fun hasJsonPath(root: JSONObject, path: String): Boolean {
    var value: Any? = root
    var parent: JSONObject? = null
    for (part in path.split('.')) {
        parent = value as? JSONObject ?: return false
        if (!parent.has(part)) return false
        value = parent.opt(part)
    }
    if (value != JSONObject.NULL) return true
    return when (path) {
        "exception.cause" -> true
        "http.status" -> root.optJSONObject("http")?.let { http ->
            http.optString("statusState") == "no_response_received" &&
                (http.optJSONArray("attempts")?.length() ?: 0) > 0
        } == true
        else -> false
    }
}

private fun fillHttpFromException(http: JSONObject, causes: List<Throwable>) {
    if (http.has("status") && !http.isNull("status")) return
    val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return
    http.put("status", response.responseCode)
    http.put("statusState", "received")
    http.put("responseContentType", response.headerFields.entries
        .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull() ?: JSONObject.NULL)
    if (http.opt("url") == JSONObject.NULL) http.put("url", safePlaybackUrl(response.dataSpec.uri.toString()))
    if (http.optJSONArray("requestHeaders")?.length() == 0) {
        http.put("requestHeaders", JSONArray().apply {
            response.dataSpec.httpRequestHeaders.forEach { (name, value) ->
                val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                    .take(4).joinToString("") { "%02x".format(it) }
                put("$name(len=${value.toByteArray(Charsets.UTF_8).size}, sha8=$digest)")
            }
        })
    }
}

private fun throwableChain(error: Throwable?): List<Throwable> {
    val result = mutableListOf<Throwable>()
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = error
    while (current != null && seen.add(current) && result.size < MAX_CAUSES) {
        result += current
        current = current.cause
    }
    return result
}

private fun causeJson(error: Throwable, source: String): JSONObject {
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    var root: JSONObject? = null
    var parent: JSONObject? = null
    var count = 0
    while (current != null && seen.add(current) && count++ < MAX_CAUSES) {
        val cause = current
        val node = JSONObject()
            .put("class", cause.javaClass.name)
            .put("message", cause.message?.let { com.lumina.tv.diag.LogRedactor.redact(it) } ?: JSONObject.NULL)
            .put("stackFrames", JSONArray().apply {
                cause.stackTrace.take(8).forEach { frame -> put("${frame.className}.${frame.methodName}(${frame.fileName ?: "unknown"}:${frame.lineNumber})") }
            })
            .put("source", source)
            .put("cause", JSONObject.NULL)
        if (root == null) root = node else parent?.put("cause", node)
        parent = node
        current = cause.cause
    }
    return root ?: JSONObject()
}

private fun readableProperty(instance: Any?, property: String): String? = runCatching {
    val method = instance?.javaClass?.methods?.firstOrNull { it.name.equals("get${property.replaceFirstChar(Char::uppercase)}") }
        ?: return@runCatching null
    method.invoke(instance)?.toString()?.takeIf(String::isNotBlank)
}.getOrNull()

private const val MAX_CAUSES = 16
