package com.lumina.tv.data

import android.content.SharedPreferences
import com.lumina.tv.AppRuntime
import com.lumina.tv.diag.DiagExporter
import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.NetRecord
import com.lumina.tv.diag.PageLog
import com.lumina.tv.diag.redactDiagnosticSecrets
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.security.ProviderException
import java.util.ArrayDeque
import java.util.Base64

/** Plain JVM check() assertions. Run with the adjacent Gradle init script; no JUnit dependency. */
object S1SecurityChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        redactionChecks()
        diagnosticRedactionChecks()
        migrationChecks()
        encryptedWriteChecks()
        // No Android context/Keystore on this JVM: every operation must fail safely.
        check(!SecureStore.put("token", "s1-fake-plaintext"))
        check(SecureStore.get("token") == null)
        check(!SecureStore.isAvailable())
        SecureStore.remove("token")
        check("s1-fake-plaintext" !in PageLog.tail())
        println("S1 checks passed: diagnostic redaction before truncation, encrypted-only writes, legacy migration/retry and safe failure.")
    }

    private fun redactionChecks() {
        val cases = listOf(
            "X-Emby-Token: abc123" to "X-Emby-Token: ***",
            "Authorization: Bearer abc123\r\nnext: ok" to "Authorization: ***\r\nnext: ok",
            "X-Emby-Authorization: Emby UserId=\"user\", Token=\"abc123\"" to "X-Emby-Authorization: ***",
            "Authorization=Bearer abc123" to "Authorization=***",
            """{"token":"abc123","ok":true}""" to """{"token":"***","ok":true}""",
            """{"Authorization":"Bearer abc123","token":"a\"b"}""" to """{"Authorization":"***","token":"***"}""",
            "https://example.test/a?api_key=abc123&keep=1" to "https://example.test/a?api_key=***&keep=1",
            "https://example.test/?X-EmBy-ToKeN=abc123&keep=1" to "https://example.test/?X-EmBy-ToKeN=***&keep=1",
            "api_key=abc%26def&keep=1" to "api_key=***&keep=1",
            "api_key%3Dabc123%26keep%3D1" to "api_key%3D***%26keep%3D1",
            "X-EmBy-ToKeN%3dabc123&keep=1" to "X-EmBy-ToKeN%3d***&keep=1",
            "access%5Ftoken%3Dabc123%26keep%3D1" to "access%5Ftoken%3D***%26keep%3D1",
            "token%3D%22abc123%22%26keep%3D1" to "token%3D%22***%22%26keep%3D1",
            "https://user:pass@example.test/path?token=abc123" to "https://***@example.test/path?token=***",
            """{"baseUrl":"https://user:pass@example.test:8096/emby?token=abc123#secret"}""" to
                """{"baseUrl":"example.test"}""",
            """{"nested":{"base_url":"https:\/\/user:pass@example.test\/?password=abc123"}}""" to
                """{"nested":{"base_url":"example.test"}}""",
            """{"baseUrl":"http://[::1]:8096/path?api_key=abc123"}""" to """{"baseUrl":"[::1]"}""",
            """{"baseUrl":"https://bad host/?token=abc123"}""" to """{"baseUrl":"***"}""",
            "ordinary log line" to "ordinary log line",
        )
        for ((index, case) in cases.withIndex()) {
            val (input, expected) = case
            val actual = LogRedactor.redact(input)
            check(actual == expected) { "Redaction case $index failed: $actual" }
            check(LogRedactor.redact(actual) == actual) { "Redaction case $index is not idempotent" }
        }
        for (key in listOf("api_key", "apikey", "x-emby-token", "x-emby-authorization",
            "x-mediabrowser-token", "access_token", "authorization", "password", "passwd", "pw", "token")) {
            check(LogRedactor.redact("$key: abc123") == "$key: ***")
            check(LogRedactor.redact("$key=abc123") == "$key=***")
            check(LogRedactor.redact("""{"$key":"abc123"}""") == """{"$key":"***"}""")
        }
        val longValue = "a".repeat(20_000)
        check(LogRedactor.redact("""{"token":"$longValue"}""") == """{"token":"***"}""")
        check(LogRedactor.redact("token%3D$longValue%26keep%3D1") == "token%3D***%26keep%3D1")
        PageLog.add("X-Emby-Token: " + "s1-fake-plaintext".repeat(40))
        check(PageLog.tail().endsWith("X-Emby-Token: ***"))
        NetLog.add(1L, "GET", "https://user:pass@example.test/path?token=abc123", 2L, null,
            "Authorization: Bearer " + "s1-fake-plaintext".repeat(40))
        val recordsField = NetLog::class.java.getDeclaredField("records").apply { isAccessible = true }
        val record = (recordsField.get(NetLog) as ArrayDeque<*>).last as NetRecord
        check(record.path == "/path" && record.error == "Authorization: ***")
    }

    private fun diagnosticRedactionChecks() {
        val current = "s1-current-" + "x".repeat(300)
        val cases = listOf(
            "echo=$current" to "echo=***",
            "https://host/error?api_key=another-secret&keep=1" to "https://host/error?api_key=***&keep=1",
            "https://host/error?Token=another-secret&keep=1" to "https://host/error?Token=***&keep=1",
            "Authorization: Bearer another-secret" to "Authorization: ***",
            "AccessToken=another-secret" to "AccessToken=***",
            "AccessToken%3Danother-secret%26keep%3D1" to "AccessToken%3D***%26keep%3D1",
            """{"AccessToken":"another-secret","token":"other","ok":true}""" to
                """{"AccessToken":"***","token":"***","ok":true}""",
        )
        cases.forEach { (input, expected) -> check(redactDiagnosticSecrets(input, current) == expected) }
        val escapedToken = "s1-\"quoted\\token"
        val document = JSONObject().put("detail", "echo=$escapedToken")
            .put("AccessToken", "another-secret").put("ok", true)
        val sanitized = JSONObject(LogRedactor.redactJson(document.toString(), escapedToken))
        check(sanitized.getString("detail") == "echo=***" && sanitized.getString("AccessToken") == "***" && sanitized.getBoolean("ok"))
        val client = EmbyClient("https://host", token = current)
        try {
            val safeBody = EmbyClient::class.java.getDeclaredMethod("safeErrorBody", String::class.java)
                .apply { isAccessible = true }
            val body = "echo=$current https://host/?api_key=another-secret Authorization=Bearer other-secret " + "z".repeat(300)
            val safe = safeBody.invoke(client, body) as String
            check(safe.length <= 200 && "s1-current-" !in safe && "another-secret" !in safe && "other-secret" !in safe)
        } finally { client.close() }
        val previous = AppRuntime.session
        try {
            AppRuntime.session = Session("https://host", current, "u", "U", "Emby")
            check(DiagExporter.sanitize("unexpected=$current") == "unexpected=***")
        } finally { AppRuntime.session = previous }
    }

    private fun migrationChecks() {
        val legacy = linkedMapOf(
            "base_url" to "https://example.test", "token" to "s1-fake-plaintext",
            "user_id" to "user", "user_name" to "", "server_name" to "Emby",
            "saved_servers" to """[{"token":"s1-fake-plaintext"}]""",
        )
        val memory = MemoryPrefs(legacy)
        val decoded = mutableMapOf<String, String>()
        memory.beforeCommit = { changes ->
            legacy.forEach { (key, value) ->
                if (changes.containsKey(key) && changes[key] == null) {
                    check(decoded[key] == value && memory.values.containsKey("enc_$key"))
                }
                if (changes["secret_migrated_v1_$key"] == true) check(key !in memory.values)
            }
        }
        var writes = 0
        var failOnToken = true
        val writer: (String, String) -> Boolean = { key, value ->
            if (failOnToken && key == "token") {
                false
            } else {
                writes++
                decoded[key] = value
                memory.values["enc_$key"] = "cipher-$writes"
                true
            }
        }
        fun migrate() = migrateLegacySecrets(memory.sp, decoded::get, writer)
        check(!migrate())
        check(writes == 1 && memory.values["secrets_migrated_v1"] != true)
        check("base_url" !in memory.values && memory.values["secret_migrated_v1_base_url"] == true)
        check(legacy.filterKeys { it != "base_url" }.all { (key, value) -> memory.values[key] == value })
        failOnToken = false
        // A cleanup commit failure retains that record's old plaintext and committed ciphertext.
        memory.failApply = true
        check(!migrate())
        check(writes == 2 && memory.values["secrets_migrated_v1"] != true)
        check(memory.values["token"] == legacy.getValue("token") && decoded["token"] == legacy.getValue("token"))
        check(memory.values["secret_migrated_v1_token"] != true)
        memory.failApply = false
        check(migrate())
        check(memory.values["secrets_migrated_v1"] == true)
        check(legacy.keys.none(memory.values::containsKey) && decoded == legacy)
        val encryptedBefore = memory.values.toMap()
        check(migrate())
        check(writes == legacy.size && memory.values == encryptedBefore)

        val corrupt = MemoryPrefs(mapOf("token" to "s1-fake-plaintext", "enc_token" to "corrupt"))
        migrateLegacySecrets(corrupt.sp, { null }, { _, _ -> error("Existing ciphertext must not be overwritten") })
        check(corrupt.values["token"] == "s1-fake-plaintext")
        check(corrupt.values["secrets_migrated_v1"] != true)

        val throwing = MemoryPrefs(mapOf("token" to "s1-fake-plaintext"))
        migrateLegacySecrets(throwing.sp, { null }, { _, _ -> throw ProviderException("s1-fake-plaintext") })
        check(throwing.values["token"] == "s1-fake-plaintext")
        check(throwing.values["secrets_migrated_v1"] != true)

        val markerFailure = MemoryPrefs(mapOf("token" to "s1-fake-plaintext"))
        markerFailure.failCommitAt = 2
        val markWriter: (String, String) -> Boolean = { key, value ->
            markerFailure.values["enc_$key"] = seal(value)
            true
        }
        fun retryMarker() = migrateLegacySecrets(markerFailure.sp, { key ->
            (markerFailure.values["enc_$key"] as? String)?.let(::open)
        }, markWriter, keys = listOf("token"))
        check(!retryMarker())
        check("token" !in markerFailure.values && markerFailure.values["secret_migrated_v1_token"] != true)
        check(retryMarker() && markerFailure.values["secret_migrated_v1_token"] == true)
        check("s1-fake-plaintext" !in PageLog.tail())
    }

    private fun encryptedWriteChecks() {
        val a = Session("https://host/Emby", "s1-token-a", "a", "Alice", "A")
        val b = Session("https://other/emby", "s1-token-b", "b", "Bob", "B")
        val legacy = mapOf("base_url" to a.baseUrl, "token" to a.token, "user_id" to a.userId,
            "user_name" to a.userName, "server_name" to a.serverName)
        val memory = MemoryPrefs(legacy)
        var encryptionFails = true
        val store = SessionStore(memory.sp, { if (encryptionFails) null else seal(it) }, ::open,
            legacy = { memory.values[it] as? String })
        val before = memory.values.toMap()
        check(store.read() == a && memory.values == before && store.lastError != null)
        check(!store.save(b) && memory.values == before)
        encryptionFails = false
        memory.failApply = true
        check(store.read() == a && memory.values == before)
        memory.failApply = false
        check(store.read() == a && legacy.keys.none(memory.values::containsKey))
        check((memory.values[SessionStore.KEY] as String).startsWith("enc:") && store.lastError == null)
        val encrypted = memory.values.toMap()
        encryptionFails = true
        check(!store.save(b) && memory.values == encrypted && store.read(migrate = false) == a)
        val throwing = SessionStore(memory.sp, { throw ProviderException("s1-token-b") }, ::open)
        check(!throwing.save(b) && memory.values == encrypted)

        val empty = MemoryPrefs()
        check(!SessionStore(empty.sp, { null }, ::open).save(b) && empty.values.isEmpty())
        // All three historical encodings stay readable; migration never deletes the sole old copy.
        val json = checkNotNull(open((encrypted.getValue(SessionStore.KEY) as String).removePrefix("enc:")))
        check(JSONObject(json).keys().asSequence().toSet() == setOf("version", "generation", "baseUrl", "token", "userId", "userName", "serverName"))
        for (prefix in listOf("", "plain:")) {
            val old = MemoryPrefs(encrypted + (SessionStore.KEY to "$prefix$json"))
            val oldValues = old.values.toMap()
            val failed = SessionStore(old.sp, { null }, ::open)
            check(failed.read() == a && old.values == oldValues && failed.lastError != null)
            check(SessionStore(old.sp, ::seal, ::open).read() == a)
            check((old.values[SessionStore.KEY] as String).startsWith("enc:"))
        }

        val saved = MemoryPrefs(mapOf("secrets_migrated_v1" to true))
        encryptionFails = false
        val prefs = Prefs(saved.sp, { if (encryptionFails) null else seal(it) }, ::open)
        val server = SavedServer("A", a.baseUrl, a.userName, a.userId, a.token, 0)
        check(prefs.upsertServer(server))
        val savedValues = saved.values.toMap()
        encryptionFails = true
        check(!prefs.upsertServer(server.copy(token = b.token)) && saved.values == savedValues)
        check(!prefs.removeSavedServer(server.url) && saved.values == savedValues)
        check(!prefs.renameSavedServer(server.url, "Renamed") && saved.values == savedValues)
        check(runCatching { prefs.savedServersJson = "[]" }.isFailure && saved.values == savedValues)
        check(prefs.savedServerList().single().token == a.token && prefs.credentialStorageError != null)
        encryptionFails = false
        check(prefs.upsertServer(server.copy(token = b.token)) && prefs.credentialStorageError == null)

        val oldSaved = MemoryPrefs(mapOf("saved_servers" to """[{"url":"https://host","token":"s1-old-token"}]"""))
        val retry = Prefs(oldSaved.sp, { null }, ::open)
        check(retry.savedServerList().single().token == "s1-old-token" && retry.credentialStorageError != null)
        check(oldSaved.values.containsKey("saved_servers") && !oldSaved.values.containsKey("enc_saved_servers"))
        val migrated = Prefs(oldSaved.sp, ::seal, ::open)
        check(migrated.savedServerList().single().token == "s1-old-token" && migrated.credentialStorageError == null)
        check("saved_servers" !in oldSaved.values && oldSaved.values["secret_migrated_v1_saved_servers"] == true)
        check("s1-token-a" !in PageLog.tail() && "s1-token-b" !in PageLog.tail())
    }

    // Deterministic fake encryption for storage failure tests, without the Android Keystore.
    private fun seal(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun open(value: String): String? = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()

    /** Minimal interface fake; Android framework constructors/methods are never invoked. */
    private class MemoryPrefs(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        var failApply = false
        var failCommitAt: Int? = null
        var commitAttempts = 0
        var beforeCommit: ((Map<String, Any?>) -> Unit)? = null
        val sp: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences method: $name")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            lateinit var edit: SharedPreferences.Editor
            edit = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "remove" -> { changes[args[0] as String] = null; edit }
                    "putBoolean", "putString" -> { changes[args[0] as String] = args[1]; edit }
                    "apply", "commit" -> {
                        beforeCommit?.invoke(changes)
                        val failed = failApply || (name == "commit" && ++commitAttempts == failCommitAt)
                        if (!failed) {
                            changes.forEach { (key, value) ->
                                if (value == null) values.remove(key) else values[key] = value
                            }
                        }
                        if (name == "commit") !failed else { check(!failed); null }
                    }
                    else -> error("Unexpected editor method: $name")
                }
            }
            return edit
        }
    }

    private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        }))
}
