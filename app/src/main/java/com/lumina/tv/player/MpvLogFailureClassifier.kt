package com.lumina.tv.player

/** Only mpv fatal/error logs can establish a transport or loading failure. */
internal fun isMpvHttpFailureLog(level: Int, text: String): Boolean {
    if (!isMpvErrorLogLevel(level) || text.contains("Opening", ignoreCase = true)) return false
    return HTTP_FAILURE.containsMatchIn(text)
}

/** Values exposed by MPVLib 1.0.4: FATAL=10, ERROR=20, WARN=30, INFO=40. */
internal fun isMpvErrorLogLevel(level: Int): Boolean = level == MPV_LOG_FATAL || level == MPV_LOG_ERROR

private const val MPV_LOG_FATAL = 10
private const val MPV_LOG_ERROR = 20
private val HTTP_FAILURE = Regex(
    "(?i)\\bHTTP\\s+error\\s+[45][0-9]{2}\\b" +
        "|\\bHTTP\\s+status(?:\\s+code)?\\s+[45][0-9]{2}\\b" +
        "|\\bserver\\s+returned\\s+[45][0-9]{2}\\b" +
        "|\\bconnection\\s+refused\\b|\\bfailed\\s+to\\s+resolve\\b"
)
