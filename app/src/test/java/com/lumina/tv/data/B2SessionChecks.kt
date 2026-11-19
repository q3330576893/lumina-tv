package com.lumina.tv.data

import com.lumina.tv.AppRuntime
import com.lumina.tv.diag.PageLog
import java.util.Base64

internal object B2SessionChecks {
    private val a = Session("https://host/Emby", "b2-token-a", "user-a", "Alice", "Server A")
    private val b = Session("https://other/emby", "b2-token-b", "user-b", "Bob", "Server B")
    private val encrypt: (String) -> String? = { Base64.getEncoder().encodeToString(it.toByteArray()) }
    private val decrypt: (String) -> String? = { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }

    private fun store(memory: B2MemoryPrefs, seal: (String) -> String? = encrypt) =
        SessionStore(memory.sp, seal, decrypt, legacy = { memory.values[it] as? String })

    private fun legacy(session: Session) = mapOf<String, Any>("base_url" to session.baseUrl, "token" to session.token,
        "user_id" to session.userId, "user_name" to session.userName, "server_name" to session.serverName)

    fun run() {
        atomicCommit()
        migrationAndAvailability()
        savedServerIdentity()
        runtimeRestore()
        check("b2-token-a" !in PageLog.tail() && "b2-token-b" !in PageLog.tail())
        println("B2 PASS Q-008: five-field atomic commit; every editor boundary interruption; migration/failure/retry; checksum; logout; plaintext fallback")
        println("B2 PASS E7-A4: Prefs upsert/current/remove/rename; case-sensitive path; canonical host/port; invalid addresses unequal")
    }

    private fun atomicCommit() {
        val memory = B2MemoryPrefs(mapOf("saved_servers" to "preserve-list-byte-for-byte"))
        check(store(memory).save(a) && memory.commits == 1)
        val old = memory.values.toMap()
        val observed = mutableListOf<Session?>()
        memory.boundary = { observed += store(B2MemoryPrefs(memory.values)).read(migrate = false) }
        check(store(memory).save(b) && memory.commits == 2)
        check(observed.isNotEmpty() && observed.all { it == a || it == b })
        check(store(B2MemoryPrefs(memory.values)).read() == b)
        val operationCount = memory.operations / 2
        // Interrupt each staged blob/checksum/legacy-removal boundary, before the one atomic commit.
        for (boundary in 1..operationCount) {
            val killed = B2MemoryPrefs(old).apply { failAtOperation = boundary }
            check(!store(killed).save(b))
            check(store(B2MemoryPrefs(killed.values)).read(migrate = false) == a)
            check(killed.values["saved_servers"] == "preserve-list-byte-for-byte")
        }
        val failed = B2MemoryPrefs(old).apply { failCommit = true }
        check(!store(failed).save(b) && store(B2MemoryPrefs(failed.values)).read() == a)
        failed.failCommit = false
        check(store(failed).save(b) && store(failed).read() == b)
        val corrupt = B2MemoryPrefs(old + legacy(b) + (SessionStore.CHECKSUM to "corrupt"))
        check(store(corrupt).read() == null) // No fallback to a different legacy identity.
        val undecryptable = SessionStore(B2MemoryPrefs(old + legacy(b)).sp, encrypt, { null })
        check(undecryptable.read() == null)
        check(store(memory).clear())
        check(store(memory).read() == null && memory.values["saved_servers"] == "preserve-list-byte-for-byte")
        // Even stray historical fields cannot defeat the logout tombstone.
        memory.values.putAll(legacy(b))
        check(store(memory).read() == null)
        check(runCatching { store(memory).save(b.copy(token = "")) }.isFailure)
        check(runCatching { store(memory).save(b.copy(baseUrl = "https://bad host")) }.isFailure)
    }

    private fun migrationAndAvailability() {
        val initial = legacy(a) + ("saved_servers" to "unchanged-list")
        val memory = B2MemoryPrefs(initial)
        check(store(memory) { null }.read() == a && memory.values == initial)
        // Fail at each legacy read: never publish a partial five-field record or remove an old field.
        for (failedKey in SessionStore.LEGACY_KEYS) {
            val interrupted = SessionStore(memory.sp, encrypt, decrypt, legacy = {
                check(it != failedKey) { "Injected read interruption" }
                memory.values[it] as? String
            })
            check(interrupted.read() == null && memory.values == initial)
        }
        memory.failCommit = true
        check(store(memory).read() == a && memory.values == initial)
        memory.failCommit = false
        check(store(memory).read() == a && memory.commits == 2)
        check(SessionStore.LEGACY_KEYS.none(memory.values::containsKey))
        check(memory.values["saved_servers"] == "unchanged-list")
        val encrypted = memory.values.toMap()
        check(store(memory).read() == a && memory.values == encrypted && memory.commits == 2)
        val publishedFailure = B2MemoryPrefs(initial).apply { failCommit = true; publishFailedCommit = true }
        check(store(publishedFailure).read() == a)
        check(initial.all { (key, value) -> publishedFailure.values[key] == value })
        val cleanupFailure = B2MemoryPrefs(initial).apply { failAtCommit = 2 }
        check(store(cleanupFailure).read() == a && cleanupFailure.commits == 1)
        check(initial.all { (key, value) -> cleanupFailure.values[key] == value })
        check((cleanupFailure.values[SessionStore.KEY] as String).startsWith("enc:"))
        cleanupFailure.failAtCommit = null
        check(store(cleanupFailure).read() == a && SessionStore.LEGACY_KEYS.none(cleanupFailure.values::containsKey))
        for (cleanupBoundary in 1..10) {
            val killed = B2MemoryPrefs(initial).apply { failAtOperation = 5 + cleanupBoundary }
            check(store(killed).read() == a && killed.commits == 1)
            check(initial.all { (key, value) -> killed.values[key] == value })
            check(store(B2MemoryPrefs(killed.values)).read(migrate = false) == a)
        }
        val fallback = B2MemoryPrefs()
        check(store(fallback) { null }.save(b))
        check((fallback.values[SessionStore.KEY] as String).startsWith("plain:"))
        check(store(fallback) { null }.read() == b)
        check(store(fallback).read() == b)
        check((fallback.values[SessionStore.KEY] as String).startsWith("enc:"))
        val oldCiphertext = B2MemoryPrefs(initial.mapKeys { (key, _) ->
            if (key in SessionStore.LEGACY_KEYS) "enc_$key" else key
        }.mapValues { (key, value) -> if (key.startsWith("enc_")) checkNotNull(encrypt(value as String)) else value })
        val oldEncryptedStore = SessionStore(oldCiphertext.sp, encrypt, decrypt, legacy = { key ->
            (oldCiphertext.values["enc_$key"] as? String)?.let(decrypt)
        })
        check(oldEncryptedStore.read() == a)
        check(SessionStore.LEGACY_KEYS.none { oldCiphertext.values.containsKey("enc_$it") })
        check(oldCiphertext.values["saved_servers"] == "unchanged-list")
    }

    private fun savedServerIdentity() {
        fun server(url: String, name: String) = SavedServer(name, url, "U", "u", "b2-list-token", 1)
        val upper = server("https://host/Emby", "Upper")
        val lower = server("https://host/emby", "Lower")
        val memory = B2MemoryPrefs(mapOf("secrets_migrated_v1" to true) + legacy(a))
        val prefs = Prefs(memory.sp)
        prefs.upsertServer(upper)
        prefs.upsertServer(lower)
        check(prefs.savedServerList().map { it.url } == listOf(upper.url, lower.url))
        check(prefs.currentSavedServer()?.name == "Upper")
        prefs.upsertServer(upper.copy(url = "HTTPS://HOST:443/Emby/", name = "Upper 2"))
        check(prefs.savedServerList().size == 2 && prefs.currentSavedServer()?.name == "Upper 2")
        prefs.renameSavedServer("HTTPS://HOST:443/Emby/", "Renamed")
        check(prefs.savedServerList().first { it.url == lower.url }.name == "Lower")
        check(prefs.currentSavedServer()?.name == "Renamed" && prefs.serverName == "Renamed")
        prefs.removeSavedServer("https://host/Emby/")
        check(prefs.savedServerList() == listOf(lower.copy(lastUsed = prefs.savedServerList().single().lastUsed)))
        val invalid = "https://bad host"
        prefs.upsertServer(server(invalid, "Invalid 1"))
        prefs.upsertServer(server(invalid, "Invalid 2"))
        check(prefs.savedServerList().count { it.url == invalid } == 2) // null != null for matching.
        val before = prefs.savedServersJson
        prefs.removeSavedServer(invalid)
        prefs.renameSavedServer(invalid, "Must not rename")
        check(prefs.savedServersJson == before)
        prefs.prepareServerLogin(invalid, "draft")
        check(prefs.currentSavedServer() == null && !prefs.hasSession())
        check(prefs.baseUrl == invalid && prefs.userName == "draft" && prefs.token.isEmpty() && prefs.userId.isEmpty())
        check(prefs.savedServersJson == before)
    }

    private fun runtimeRestore() {
        val oldPrefs = AppRuntime.prefs
        val oldSession = AppRuntime.session
        val oldClient = AppRuntime.client
        val memory = B2MemoryPrefs(mapOf("secrets_migrated_v1" to true))
        try {
            AppRuntime.prefs = Prefs(memory.sp)
            AppRuntime.session = null
            AppRuntime.client = null
            AppRuntime.applySession(a)
            check(AppRuntime.prefs?.readSession() == a)
            memory.failCommit = true
            check(runCatching { AppRuntime.applySession(b) }.isFailure)
            check(AppRuntime.session == a && AppRuntime.client?.baseUrl == a.baseUrl)
            memory.failCommit = false
            AppRuntime.applySession(b)
            check(AppRuntime.restore() == b && AppRuntime.client?.token == b.token)
            AppRuntime.logout()
            check(AppRuntime.restore() == null)
        } finally {
            AppRuntime.client?.close()
            AppRuntime.prefs = oldPrefs
            AppRuntime.session = oldSession
            AppRuntime.client = oldClient
        }
    }
}
