package com.lumina.tv.data

import android.content.SharedPreferences
import com.lumina.tv.diag.PageLog
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** A session is one record. Legacy fields are migration inputs, never independent new writes. */
internal class SessionStore(
    private val sp: SharedPreferences,
    private val encrypt: (String) -> String? = SecureStore::encrypt,
    private val decrypt: (String) -> String? = SecureStore::decrypt,
    private val legacy: (String) -> String? = { SecureStore.get(it) ?: sp.getString(it, null) },
) {
    fun hasRecord(): Boolean = sp.contains(KEY) || sp.contains(CHECKSUM)

    @Synchronized
    fun read(migrate: Boolean = true): Session? = runCatching {
        if (hasRecord()) {
            val stored = sp.getString(KEY, null) ?: return null
            val json = when {
                stored.startsWith("enc:") -> decrypt(stored.removePrefix("enc:"))
                stored.startsWith("plain:") -> stored.removePrefix("plain:")
                else -> null // Includes the logout tombstone. Never resurrect legacy credentials.
            } ?: return null
            if (checksum(json) != sp.getString(CHECKSUM, null)) return null
            val item = JSONObject(json)
            if (item.getInt("version") != 2 || item.getString("generation").isBlank()) return null
            val session = Session(item.getString("baseUrl"), item.getString("token"), item.getString("userId"),
                item.getString("userName"), item.getString("serverName"))
            if (!complete(session)) return null
            if (migrate && (stored.startsWith("plain:") || hasLegacyFields())) write(session, requireEncryption = true)
            return session
        }
        val session = Session(legacy("base_url").orEmpty(), legacy("token").orEmpty(),
            legacy("user_id").orEmpty(), legacy("user_name").orEmpty(), legacy("server_name") ?: "Emby")
        if (!complete(session)) return null
        // An encryption/commit failure keeps the legacy record readable and leaves it for retry.
        if (migrate) write(session, requireEncryption = true)
        session
    }.onFailure { failed("read", it) }.getOrNull()

    @Synchronized
    fun save(session: Session): Boolean {
        require(complete(session)) { "Incomplete session" }
        return write(session, requireEncryption = false)
    }

    private fun write(session: Session, requireEncryption: Boolean): Boolean = runCatching {
        val json = JSONObject().put("version", 2).put("generation", UUID.randomUUID().toString())
            .put("baseUrl", session.baseUrl).put("token", session.token).put("userId", session.userId)
            .put("userName", session.userName).put("serverName", session.serverName).toString()
        val ciphertext = encrypt(json)
        if (ciphertext == null && requireEncryption) return false
        // Preserve the existing Keystore-unavailable availability policy, but for the WHOLE record.
        val stored = ciphertext?.let { "enc:$it" } ?: "plain:$json"
        val edit = sp.edit().putString(KEY, stored).putString(CHECKSUM, checksum(json))
        if (!requireEncryption) removeLegacy(edit)
        edit.remove(DRAFT_URL).remove(DRAFT_USER).remove(DRAFT_NAME)
        if (!edit.commit()) {
            failed("write", IllegalStateException("Session commit failed"))
            return false
        }
        if (requireEncryption && hasLegacyFields()) {
            // A failed commit can still publish new values in SharedPreferences memory. Keep every
            // legacy field out of that first editor until the encrypted record is confirmed durable.
            val cleanup = sp.edit()
            removeLegacy(cleanup)
            if (!cleanup.commit()) {
                failed("write_cleanup", IllegalStateException("Session legacy cleanup failed"))
                return false
            }
        }
        true
    }.onFailure { failed("write", it) }.getOrDefault(false)

    @Synchronized
    fun clear(loginUrl: String = "", loginUser: String = ""): Boolean = runCatching {
        val edit = sp.edit().putString(KEY, "none").remove(CHECKSUM)
        removeLegacy(edit)
        edit.putString(DRAFT_URL, loginUrl).putString(DRAFT_USER, loginUser).remove(DRAFT_NAME)
        if (!edit.commit()) {
            failed("clear", IllegalStateException("Session clear failed"))
            return false
        }
        true
    }.onFailure { failed("clear", it) }.getOrDefault(false)

    private fun removeLegacy(edit: SharedPreferences.Editor) {
        LEGACY_KEYS.forEach { edit.remove(it).remove(SecureStore.PREFIX + it) }
    }

    private fun hasLegacyFields(): Boolean = LEGACY_KEYS.any { sp.contains(it) || sp.contains(SecureStore.PREFIX + it) }

    private fun failed(operation: String, error: Throwable) {
        runCatching { PageLog.add("sessionStore op=$operation failed=${error.javaClass.simpleName}") }
    }

    companion object {
        internal const val KEY = "session_v2"
        internal const val CHECKSUM = "session_v2_checksum"
        internal const val DRAFT_URL = "login_base_url"
        internal const val DRAFT_USER = "login_user_name"
        internal const val DRAFT_NAME = "login_server_name"
        internal val LEGACY_KEYS = listOf("base_url", "token", "user_id", "user_name", "server_name")

        fun complete(session: Session): Boolean = canonicalServerKey(session.baseUrl) != null &&
            session.token.isNotBlank() && session.userId.isNotBlank()

        private fun checksum(json: String): String = MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
