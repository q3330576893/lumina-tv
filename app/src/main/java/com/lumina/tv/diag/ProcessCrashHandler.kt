package com.lumina.tv.diag

/** One wrapper and one previous-exit collection per process owner, including concurrent installation. */
internal class ProcessCrashHandler {
    private var installed = false

    @Synchronized fun install(
        write: (Thread, Throwable) -> Unit,
        collectPreviousExit: () -> Unit,
    ) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { write(thread, throwable) } catch (_: Throwable) { }
            // Diagnostics must never prevent Android's original fatal-exception handling.
            try { previous?.uncaughtException(thread, throwable) } catch (_: Throwable) { }
        }
        installed = true
        collectPreviousExit()
    }
}
