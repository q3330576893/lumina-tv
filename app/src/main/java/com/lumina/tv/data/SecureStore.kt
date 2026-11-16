package com.lumina.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lumina.tv.diag.PageLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound secrets; callers retain a plaintext fallback when the Keystore is unavailable. */
object SecureStore {
    internal const val PREFIX = "enc_"
    private const val ALIAS = "lumina_secure_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private var preferences: SharedPreferences? = null
    private var keyStore: KeyStore? = null

    @Synchronized
    fun init(context: Context) {
        if (preferences != null) return
        try {
            preferences = context.applicationContext.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE)
            secretKey(create = true)
        } catch (e: Exception) {
            failed("init", e)
        }
    }

    @Synchronized
    fun put(key: String, value: String): Boolean = try {
        val sp = checkNotNull(preferences)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // AndroidKeyStore generates a fresh random IV; explicitly supplying one is forbidden.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true))
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        // Confirm persistence before Prefs removes the only legacy plaintext copy.
        check(sp.edit().putString(PREFIX + key, encoded).commit())
        true
    } catch (e: Exception) {
        failed("put", e)
        false
    }

    @Synchronized
    fun get(key: String): String? {
        return try {
            val encoded = checkNotNull(preferences).getString(PREFIX + key, null) ?: return null
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size >= IV_BYTES + TAG_BITS / 8)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            failed("get", e)
            null
        }
    }

    @Synchronized
    fun remove(key: String) {
        try {
            checkNotNull(preferences).edit().remove(PREFIX + key).apply()
        } catch (e: Exception) {
            failed("remove", e)
        }
    }

    @Synchronized
    fun isAvailable(): Boolean = try {
        checkNotNull(preferences)
        secretKey(create = true)
        true
    } catch (e: Exception) {
        failed("available", e)
        false
    }

    // Only called inside the guarded public operations above.
    private fun secretKey(create: Boolean): SecretKey {
        val store = keyStore ?: KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .also { keyStore = it }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        check(create && existing == null)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun failed(op: String, error: Exception) {
        // Exception messages may contain provider inputs; log the type only.
        runCatching { PageLog.add("secureStore op=$op failed=${error.javaClass.simpleName}") }
    }
}
