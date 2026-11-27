package com.lumina.tv.diag

import java.util.concurrent.Executors

/** Install synchronously; only historical evidence collection is scheduled in the background. */
internal class ProcessCrashHandler(
    private val schedulePreviousExit: (() -> Unit) -> Unit = { previousExits.execute(it) },
) {
    private var installed = false
    @Volatile var previousExitFailure: String? = null
        private set

    @Synchronized fun install(
        write: (Thread, Throwable) -> Unit,
        collectPreviousExit: () -> Unit,
    ) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { write(thread, throwable) } catch (_: Throwable) { }
            try { previous?.uncaughtException(thread, throwable) } catch (_: Throwable) { }
        }
        installed = true
        try {
            schedulePreviousExit {
                try { collectPreviousExit() } catch (error: Throwable) {
                    previousExitFailure = error.javaClass.simpleName
                }
            }
        } catch (error: Exception) { previousExitFailure = error.javaClass.simpleName }
    }

    companion object {
        private val previousExits = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lumina-previous-exit").apply { isDaemon = true }
        }
    }
}
