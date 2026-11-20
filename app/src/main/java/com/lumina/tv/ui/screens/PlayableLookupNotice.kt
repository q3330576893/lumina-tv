package com.lumina.tv.ui.screens

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun <T> resolveWithSlowNotice(
    waitForSlow: suspend () -> Unit = { delay(3_000L) },
    onSlow: () -> Unit,
    resolve: suspend () -> T,
): T = coroutineScope {
    val notice = launch { waitForSlow(); onSlow() }
    try {
        resolve()
    } finally {
        notice.cancel()
    }
}
