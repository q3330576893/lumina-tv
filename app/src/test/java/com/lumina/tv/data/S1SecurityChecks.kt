package com.lumina.tv.data

import android.content.SharedPreferences
import com.lumina.tv.diag.LogRedactor
import com.lumina.tv.diag.NetLog
import com.lumina.tv.diag.NetRecord
import com.lumina.tv.diag.PageLog
import java.lang.reflect.Proxy
import java.security.ProviderException
import java.util.ArrayDeque

/** Plain JVM check() assertions. Run with the adjacent Gradle init script; no JUnit dependency. */
object S1SecurityChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        redactionChecks()
        migrationChecks()
        // No Android context/Keystore on this JVM: every operation must fail safely.
        check(!SecureStore.put("token", "s1-fake-plaintext"))
        check(SecureStore.get("token") == null)
        check(!SecureStore.isAvailable())
        SecureStore.remove("token")
        check("s1-fake-plaintext" !in PageLog.tail())
        println("S1 checks passed: redaction, log ingestion, migration/retry/idempotence, safe failure.")
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

    private fun migrationChecks() {
        val legacy = linkedMapOf(
            "base_url" to "https://example.test", "token" to "s1-fake-plaintext",
            "user_id" to "user", "user_name" to "", "server_name" to "Emby",
            "saved_servers" to """[{"token":"s1-fake-plaintext"}]""",
        )
        val memory = MemoryPrefs(legacy)
        val decoded = mutableMapOf<String, String>()
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
        migrate()
        check(writes == 1 && memory.values["secrets_migrated_v1"] != true)
        check(legacy.all { (key, value) -> memory.values[key] == value })
        failOnToken = false
        // Simulate a failure when applying removals/marker: keep all plaintext and retry.
        memory.failApply = true
        migrate()
        check(writes == legacy.size && memory.values["secrets_migrated_v1"] != true)
        check(legacy.all { (key, value) -> memory.values[key] == value })
        memory.failApply = false
        migrate()
        check(memory.values["secrets_migrated_v1"] == true)
        check(legacy.keys.none(memory.values::containsKey) && decoded == legacy)
        val encryptedBefore = memory.values.toMap()
        migrate()
        check(writes == legacy.size && memory.values == encryptedBefore)

        val corrupt = MemoryPrefs(mapOf("token" to "s1-fake-plaintext", "enc_token" to "corrupt"))
        migrateLegacySecrets(corrupt.sp, { null }, { _, _ -> error("Existing ciphertext must not be overwritten") })
        check(corrupt.values["token"] == "s1-fake-plaintext")
        check(corrupt.values["secrets_migrated_v1"] != true)

        val throwing = MemoryPrefs(mapOf("token" to "s1-fake-plaintext"))
        migrateLegacySecrets(throwing.sp, { null }, { _, _ -> throw ProviderException("s1-fake-plaintext") })
        check(throwing.values["token"] == "s1-fake-plaintext")
        check(throwing.values["secrets_migrated_v1"] != true)
        check("s1-fake-plaintext" !in PageLog.tail())
    }

    /** Minimal interface fake; Android framework constructors/methods are never invoked. */
    private class MemoryPrefs(initial: Map<String, String>) {
        val values = initial.mapValues { it.value as Any }.toMutableMap()
        var failApply = false
        val sp: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
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
                    "putBoolean" -> { changes[args[0] as String] = args[1]; edit }
                    "apply" -> {
                        check(!failApply)
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        null
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
