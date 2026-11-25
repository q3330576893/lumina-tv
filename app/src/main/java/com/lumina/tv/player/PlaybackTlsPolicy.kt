package com.lumina.tv.player

import com.lumina.tv.data.canonicalServerKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal const val CERTIFICATE_ERROR_MESSAGE = "证书验证失败（CERT_INVALID），请检查服务器证书、主机名和设备时间；自签证书需先配置受信任的证书"

private val nonCertificateHandshakePattern = Regex(
    "(?i)\\b(?:protocol[_ -]version|protocol negotiation failed|unsupported protocol|wrong version number|" +
        "no appropriate protocol|no (?:available |shared |supported |enabled )?cipher(?: suite)?s?|" +
        "cipher(?: suite)? negotiation failed|handshake[_ ](?:failure|failed)|" +
        "connection (?:reset|closed|aborted)|remote host terminated|peer (?:closed|shut down)|" +
        "unexpected (?:end of (?:stream|file)|eof)|(?:read|connect|handshake) timed out)\\b",
)

internal fun isCertificateFailure(error: Throwable): Boolean {
    val causes = playbackErrorCauses(error).toList()
    // Concrete certificate evidence takes precedence over generic handshake/transport descriptions.
    if (causes.any { it is SSLPeerUnverifiedException || it is java.security.cert.CertificateException ||
            it is java.security.cert.CertPathValidatorException }) return true
    if (causes.none { it is SSLHandshakeException }) return false
    // Preserve the established certificate fallback for unspecified handshakes, while excluding
    // explicit protocol/transport failures. A general SSLException alone is not certificate evidence.
    return causes.none {
        it is SSLProtocolException || it is java.net.SocketException ||
            it is java.net.SocketTimeoutException || it is java.io.EOFException ||
            (it is SSLException && nonCertificateHandshakePattern.containsMatchIn(it.message.orEmpty()))
    }
}

/** Match TLS causes, not general network failures or arbitrary strings containing 'certificate'. */
private val mpvCertificateFailurePattern = Regex(
    "(?i)\\bcertificate\\b[^\\n]*(?:(?:verify|verification|validation)[^\\n]*(?:fail|error)|expired|not trusted|unknown issuer|mismatch|invalid|cannot be authenticated|does not match|not ok|failed verification)|" +
        "\\b(?:expired|invalid|untrusted|self[- ]signed)\\s+(?:server\\s+|peer\\s+)?certificate\\b|" +
        "(?:hostname|host name)[^\\n]*(?:mismatch|does not match|not match)|" +
        "(?:error in|failed to)[^\\n]*(?:certificate verification|verify (?:the )?(?:server |peer )?certificate)|" +
        "unable to (?:verify the first certificate|get local issuer certificate)",
)
internal fun isMpvCertificateFailure(message: String): Boolean = mpvCertificateFailurePattern.containsMatchIn(message)

/** Error state is partitioned by canonical server identity, never used as a trust decision. */
internal object ServerCertificateErrors {
    private val errors = ConcurrentHashMap<String, String>()
    fun record(serverUrl: String) { canonicalServerKey(serverUrl)?.let { errors[it] = "CERT_INVALID" } }
    fun get(serverUrl: String): String? = canonicalServerKey(serverUrl)?.let(errors::get)
}

internal object MpvTlsPolicy {
    fun options(caFile: File): Map<String, String> = mapOf("tls-verify" to "yes", "tls-ca-file" to caFile.absolutePath)

    /** libmpv/FFmpeg cannot assume an Android system CA path. Export the platform trust anchors. */
    suspend fun platformCaBundle(cacheDir: File): File {
        var created: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(null as KeyStore?)
                }.trustManagers.filterIsInstance<X509TrustManager>()
                val anchors = managers.flatMap { it.acceptedIssuers.toList() }.distinctBy { it.serialNumber to it.issuerX500Principal }
                check(anchors.isNotEmpty()) { "平台信任库不可用，已停止 HTTPS 播放" }
                val pem = anchors.joinToString("\n") {
                    "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded) +
                        "\n-----END CERTIFICATE-----\n"
                }
                // The bundle is immutable for a controller; physical retries reuse it.
                File.createTempFile("mpv-ca-", ".pem", cacheDir).also { file ->
                    created = file
                    file.writeText(pem, Charsets.US_ASCII)
                }
            }
        } catch (failure: Throwable) {
            // Also owns cleanup if prompt cancellation discards the IO result before delivery.
            created?.delete()
            throw failure
        }
    }
}
