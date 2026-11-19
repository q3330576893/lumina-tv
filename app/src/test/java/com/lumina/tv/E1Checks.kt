package com.lumina.tv

import android.content.SharedPreferences
import com.lumina.tv.data.EmbyClient
import com.lumina.tv.data.Prefs
import com.lumina.tv.data.ProgressQueue
import com.lumina.tv.data.canonicalServerKey
import com.lumina.tv.data.identityScope
import com.lumina.tv.data.parseLatestItemsResponse
import com.lumina.tv.data.stableCacheDigest
import com.lumina.tv.pair.PairingRequestSession
import com.lumina.tv.pair.PairingServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue

/** Plain JVM assertions against the production parsers and a loopback NanoHTTPD server. */
object E1Checks {
    @JvmStatic fun main(args: Array<String>) {
        pairingChecks()
        latestChecks()
        identityChecks()
        prefsMigrationChecks()
        switchServerChecks()
        println("E1 PASS: pairing authorization/one body parse, Latest shapes/fallbacks, canonical identity, settings migration and strict server switching")
    }

    private fun pairingChecks() {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                var parses = 0
                val counted = object : IHTTPSession by session {
                    override fun parseBody(files: MutableMap<String, String>) {
                        parses++
                        session.parseBody(files)
                    }
                }
                val request = PairingRequestSession(counted)
                val tokens = mutableListOf<String>()
                val authorize: (String) -> Boolean = { tokens += it; it == "valid" }
                val ok = PairingServer.authorized(request, authorize)
                check(PairingServer.authorized(request, authorize) == ok)
                if (ok && request.method == Method.POST) {
                    // Authorization and each handler field may all request the same parsed body.
                    request.parseBody(HashMap())
                    request.parseBody(HashMap())
                }
                return newFixedLengthResponse(JSONObject().put("ok", ok).put("parses", parses)
                    .put("action", request.parameters["action"]?.firstOrNull().orEmpty())
                    .put("keyword", request.parameters["keyword"]?.firstOrNull().orEmpty())
                    .put("token", tokens.firstOrNull().orEmpty()).toString())
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            fun request(path: String, body: String? = null): JSONObject = JSONObject(fetch(base + path, body))
            check(request("/api/state?token=valid").let { it.getBoolean("ok") && it.getInt("parses") == 0 })
            check(request("/api/remote?token=valid", "action=up").let {
                it.getBoolean("ok") && it.getInt("parses") == 1 && it.getString("action") == "up"
            })
            check(request("/api/remote", "token=valid&action=ok").let {
                it.getBoolean("ok") && it.getInt("parses") == 1 && it.getString("action") == "ok"
            })
            check(request("/api/search", "token=valid&keyword=movie").let {
                it.getBoolean("ok") && it.getInt("parses") == 1 && it.getString("keyword") == "movie"
            })
            check(request("/api/state", "token=valid").getBoolean("ok"))
            check(request("/api/remote?token=valid", "token=invalid&action=back").getBoolean("ok"))
            check(!request("/api/remote?token=invalid", "token=valid&action=back").getBoolean("ok"))
            check(request("/api/remote?token=", "token=valid&action=down").getBoolean("ok"))
            check(request("/api/remote", "action=up").let { !it.getBoolean("ok") && it.getInt("parses") == 1 })
            check(request("/api/remote", "token=%ZZ").let { !it.getBoolean("ok") && it.getInt("parses") == 1 })
            check(!request("/api/state").getBoolean("ok"))
        } finally {
            server.stop()
        }

        // Even a parser that mutates parameters before throwing must remain unauthorized on reentry.
        val parameters = mutableMapOf<String, MutableList<String>>()
        var attempts = 0
        val raw = proxy(NanoHTTPD.IHTTPSession::class.java) { name, _ ->
            when (name) {
                "getParameters" -> parameters
                "getMethod" -> NanoHTTPD.Method.POST
                "parseBody" -> {
                    attempts++
                    parameters["token"] = mutableListOf("valid")
                    throw IOException("bad form")
                }
                else -> error(name)
            }
        }
        val failed = PairingRequestSession(raw)
        repeat(2) { check(!PairingServer.authorized(failed) { error("Failed forms must not authorize") }) }
        check(attempts == 1)
    }

    private fun latestChecks() = runBlocking {
        val item = """{"Id":"latest","Name":"Latest","Type":"Movie"}"""
        for (body in listOf("[$item]", """{"Items":[$item]}""")) {
            val rows = parseLatestItemsResponse(body) { error("Valid Latest response: $it") }
            check(rows?.length() == 1 && rows.getJSONObject(0).getString("Id") == "latest")
        }
        val failures = mutableListOf<String>()
        check(parseLatestItemsResponse("{}", failures::add) == null)
        check(failures.size == 1 && failures.single().contains("missing Items"))
        failures.clear()
        check(parseLatestItemsResponse("[{", failures::add) == null)
        check(failures.size == 1 && failures.single().contains("top-level array parse failed"))
        failures.clear()
        check(parseLatestItemsResponse("{", failures::add) == null)
        check(failures.size == 1 && failures.single().contains("malformed JSON"))

        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val responses = ConcurrentLinkedQueue<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests += session.uri to session.parameters.mapValues { it.value.first() }
                return newFixedLengthResponse(responses.remove())
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            for (body in listOf("[$item]", """{"Items":[$item]}""")) {
                requests.clear()
                responses.add(body)
                val client = EmbyClient(base, userId = "user")
                try {
                    check(client.getLatest("library", 7).single().id == "latest")
                    check(client.lastApiFailure == null)
                    check(requests.toList() == listOf("/Users/user/Items/Latest" to mapOf("Limit" to "7")))
                } finally { client.close() }
            }
            requests.clear()
            responses.addAll(listOf("{}", "[{", """{"Items":[$item]}"""))
            val client = EmbyClient(base, userId = "user")
            try {
                check(client.getLatest("library", 7).single().id == "latest")
                check(client.lastApiFailure?.contains("top-level array parse failed") == true)
                val fields = "Overview,PrimaryImageAspectRatio,UserData,ProductionYear,ProductionLocations,OriginalLanguage"
                check(requests.toList() == listOf(
                    "/Users/user/Items/Latest" to mapOf("Limit" to "7"),
                    "/Users/user/Items/Latest" to mapOf("Limit" to "7", "Fields" to fields, "ParentId" to "library"),
                    "/Items" to mapOf("Recursive" to "true", "SortBy" to "DateCreated", "SortOrder" to "Descending",
                        "IncludeItemTypes" to "Movie,Series", "Limit" to "7", "Fields" to fields, "ParentId" to "library"),
                ))
            } finally { client.close() }
        } finally { server.stop() }
    }

    private fun identityChecks() {
        check(canonicalServerKey(" HTTPS://Host:443/Emby/// ") == "https://host/Emby")
        check(canonicalServerKey("http://h:80/x") == canonicalServerKey("http://h/x/"))
        check(canonicalServerKey("https://h:443/x/") == canonicalServerKey("https://h/x"))
        check(canonicalServerKey("http://h:81/x") != canonicalServerKey("http://h/x"))
        check(canonicalServerKey("https://h/x") != canonicalServerKey("http://h/x"))
        check(canonicalServerKey("https://h/") == "https://h")
        check(canonicalServerKey("https://h/Emby") != canonicalServerKey("https://h/emby"))
        check(canonicalServerKey("https://bad host") == null)
        check(identityScope("https://h/Emby", "u") != identityScope("https://h/emby", "u"))
        check(identityScope("http://h:80/x", "u") == identityScope("http://h/x/", "u"))
        check(identityScope("https://h/Emby/", "u") == identityScope("https://h/Emby", "u"))
        val hash = ProgressQueue.Companion::class.java.getDeclaredMethod("serverHash", String::class.java)
            .apply { isAccessible = true }
        for (url in listOf("HTTPS://Host:443/Emby/", "http://h:80/x", "http://h/x/")) {
            val canonical = checkNotNull(canonicalServerKey(url))
            check(identityScope(url, "u") == stableCacheDigest(canonical, "u"))
            check(hash.invoke(ProgressQueue.Companion, url) == MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) })
            val memory = MemoryPrefs(url)
            val prefs = Prefs(memory.sp)
            check(namespace(prefs) == expectedNamespace(canonical))
        }
        check(hash.invoke(ProgressQueue.Companion, "http://h:80/x") == hash.invoke(ProgressQueue.Companion, "http://h/x/"))
        check(hash.invoke(ProgressQueue.Companion, "https://h/Emby") != hash.invoke(ProgressQueue.Companion, "https://h/emby"))
    }

    private fun prefsMigrationChecks() {
        for (url in listOf("https://host/Emby", "HTTPS://Host:443/Emby/", "http://h:80/x/")) {
            val legacy = expectedNamespace(url.trim().lowercase())
            val canonical = expectedNamespace(checkNotNull(canonicalServerKey(url)))
            check(legacy != canonical)
            val values = mapOf<String, Any>("subtitle_track" to "off", "subtitle_identity" to "sub-identity",
                "audio_track" to 3, "audio_identity" to "audio-identity", "last_source" to "source")
            val oldKeys = values.mapKeys { (prefix, _) -> "${prefix}_${legacy}_item" }
            val memory = MemoryPrefs(url, oldKeys)
            val prefs = Prefs(memory.sp)
            val itemKey = Prefs::class.java.getDeclaredMethod("itemKey", String::class.java, String::class.java)
                .apply { isAccessible = true }
            for ((prefix, value) in values) {
                val key = "${prefix}_${canonical}_item"
                check(itemKey.invoke(prefs, prefix, "item") == key)
                check(memory.values[key] == value)
                check(memory.values["item_ns_migrated_v2_$key"] == true)
            }
            check(oldKeys.all { (key, value) -> memory.values[key] == value })
            check(prefs.lastSourceFor("item") == "source" && prefs.subtitlePreferenceFor("item") == "off")
            val writes = memory.writes
            repeat(2) { check(prefs.lastSourceFor("item") == "source") }
            check(memory.writes == writes)
            prefs.rememberSource("item", null)
            check(Prefs(memory.sp).lastSourceFor("item") == null) // No resurrection after restart.
            check(memory.values["last_source_${legacy}_item"] == "source")
            check(memory.values["base_url"] == url && memory.values["token"] == "fake-token")
        }

        val upper = "https://host/Emby"
        val upperKey = "last_source_${expectedNamespace(upper)}_item"
        val oldKey = "last_source_${expectedNamespace(upper.lowercase())}_item"
        val preferred = MemoryPrefs(upper, mapOf(oldKey to "legacy", upperKey to "new"))
        check(Prefs(preferred.sp).lastSourceFor("item") == "new")
        check(preferred.values[oldKey] == "legacy")

        val isolated = MemoryPrefs(upper, mapOf(oldKey to "legacy"))
        val prefs = Prefs(isolated.sp)
        check(prefs.lastSourceFor("item") == "legacy")
        isolated.values["base_url"] = upper.lowercase()
        prefs.rememberSource("item", "lower-only")
        isolated.values["base_url"] = upper
        check(prefs.lastSourceFor("item") == "legacy")
        prefs.rememberSource("item", "upper-only")
        isolated.values["base_url"] = upper.lowercase()
        check(prefs.lastSourceFor("item") == "lower-only")

        val interrupted = MemoryPrefs(upper, mapOf(oldKey to "legacy"))
        val retry = Prefs(interrupted.sp)
        interrupted.failApply = true
        check(runCatching { retry.lastSourceFor("item") }.isFailure)
        check(interrupted.values[oldKey] == "legacy" && !interrupted.values.containsKey(upperKey))
        check(!interrupted.values.containsKey("item_ns_migrated_v2_$upperKey"))
        interrupted.failApply = false
        check(retry.lastSourceFor("item") == "legacy")
    }

    private fun switchServerChecks() {
        val memory = MemoryPrefs("https://host/Emby", mapOf("saved_servers" to
            """[{"url":"https://host/Emby","token":"fake-token","userId":"u","userName":"U","name":"test"}]"""))
        val previousPrefs = AppRuntime.prefs
        val previousSession = AppRuntime.session
        val previousClient = AppRuntime.client
        AppRuntime.prefs = Prefs(memory.sp)
        AppRuntime.session = null
        AppRuntime.client = null
        try {
            check(!AppRuntime.switchToServer("https://host/emby"))
            check(!AppRuntime.switchToServer("https://bad host"))
            check(AppRuntime.switchToServer("HTTPS://HOST:443/Emby/"))
            check(AppRuntime.session?.baseUrl == "https://host/Emby")
        } finally {
            AppRuntime.client?.close()
            AppRuntime.prefs = previousPrefs
            AppRuntime.session = previousSession
            AppRuntime.client = previousClient
        }
    }

    private fun namespace(prefs: Prefs): String = Prefs::class.java.getDeclaredMethod("ns")
        .apply { isAccessible = true }.invoke(prefs) as String

    // The existing key format is retained; only its server input changes.
    private fun expectedNamespace(server: String): String = Integer.toHexString(server.hashCode()) + "_u"

    private class MemoryPrefs(url: String, initial: Map<String, Any> = emptyMap()) {
        val values = (mapOf<String, Any>("base_url" to url, "user_id" to "u", "token" to "fake-token",
            "secrets_migrated_v1" to true) + initial).toMutableMap()
        var writes = 0
        var failApply = false
        val sp: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getString", "getInt", "getBoolean" -> values[args[0]] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor()
                else -> error(name)
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            lateinit var edit: SharedPreferences.Editor
            edit = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "remove" -> { changes[args[0] as String] = null; edit }
                    "putString", "putInt", "putBoolean" -> { changes[args[0] as String] = args[1]; edit }
                    "apply", "commit" -> {
                        check(!failApply)
                        writes++
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (name == "commit") true else null
                    }
                    else -> error(name)
                }
            }
            return edit
        }
    }

    private fun fetch(url: String, body: String? = null): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.setRequestProperty("Connection", "close")
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(status == 200) { "HTTP $status: $response" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        }))
}
