package com.lumina.tv.player

/** The pinned AAR exposes event_id only. UNKNOWN is deliberately not a failure. */
enum class MpvEndKind { EOF, ERROR, STOP, UNKNOWN }

internal data class MpvEndFileEvidence(
    val positionMs: Long?,
    val durationMs: Long?,
    val stopped: Boolean = false,
    val failureKind: MpvPlayerController.FailureKind? = null,
    val errorLogSeen: Boolean = false,
)

/** Native callbacks record before posting to the UI. An END_FILE snapshot is
 * immutable, so later unavailable/reset properties and a busy UI cannot erase it. */
internal class MpvEndFileTracker {
    private var positionMs: Long? = null
    private var durationMs: Long? = null
    private var stopped = false
    private var failureKind: MpvPlayerController.FailureKind? = null
    private var errorLogSeen = false

    @Synchronized fun property(name: String, value: Double) {
        val sample = mpvSecondsToMs(value) ?: return
        if (name == "time-pos") positionMs = sample
        if (name == "duration" && sample > 0L) durationMs = sample
    }

    @Synchronized fun log(level: Int, text: String) {
        if (!isMpvErrorLogLevel(level)) return
        errorLogSeen = true
        mpvEndFailureKind(level, text)?.let { failureKind = it }
    }

    @Synchronized fun stop() { stopped = true }

    @Synchronized fun snapshot() = MpvEndFileEvidence(positionMs, durationMs, stopped, failureKind, errorLogSeen)
}

internal fun mpvSecondsToMs(value: Double?): Long? =
    value?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1000.0).toLong() }

/** Supplement W3, second choice: all three independent pieces of evidence are
 * required. A short clip must not count as EOF merely because it started. */
internal fun classifyMpvEndFile(evidence: MpvEndFileEvidence): MpvEndKind {
    if (evidence.stopped) return MpvEndKind.STOP
    if (evidence.failureKind != null) return MpvEndKind.ERROR
    // An unrelated/recovered native error line vetoes EOF, but cannot prove
    // that this END_FILE was a failure. Keep that ambiguity neutral too.
    if (evidence.errorLogSeen) return MpvEndKind.UNKNOWN
    val position = evidence.positionMs ?: return MpvEndKind.UNKNOWN
    val duration = evidence.durationMs?.takeIf { it > 0L } ?: return MpvEndKind.UNKNOWN
    val tolerance = minOf(1_000L, duration / 10L).coerceAtLeast(1L)
    return if (position > 0L && position >= duration - tolerance && position <= duration + tolerance)
        MpvEndKind.EOF else MpvEndKind.UNKNOWN
}

/** Only positive failure evidence may enter automatic recovery after END_FILE. */
internal fun mpvEndAllowsRecovery(kind: MpvEndKind?): Boolean = kind == null || kind == MpvEndKind.ERROR

internal fun mpvEndFailureKind(level: Int, text: String): MpvPlayerController.FailureKind? {
    if (!isMpvErrorLogLevel(level)) return null
    return when {
        isMpvCertificateFailure(text) -> MpvPlayerController.FailureKind.CERT_INVALID
        isMpvHttpFailureLog(level, text) -> MpvPlayerController.FailureKind.HTTP
        MPV_END_DECODER_FAILURE.containsMatchIn(text) -> MpvPlayerController.FailureKind.DECODING
        else -> null
    }
}

private val MPV_END_DECODER_FAILURE = Regex("(?i)failed to (?:decode|open.*decoder)|error while decoding|decoder initialization failed")
