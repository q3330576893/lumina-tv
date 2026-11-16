package com.lumina.tv.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One accepted toggle at a time. Own this state per item, never per reused detail screen. */
internal class UserFlagMutation(initial: Boolean = false) {
    var value by mutableStateOf(initial)
        private set
    var pending by mutableStateOf(false)
        private set
    private var edited = false

    fun syncFromServer(actual: Boolean) {
        // A detail refresh started before a click must not overwrite its result.
        if (!edited) value = actual
    }

    fun toggle(
        scope: CoroutineScope,
        request: suspend (Boolean) -> Result<Boolean>,
        onSuccess: (Boolean) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (pending) return
        val old = value
        val target = !old
        edited = true
        pending = true
        value = target
        scope.launch {
            try {
                request(target).fold(
                    onSuccess = { actual -> value = actual; onSuccess(actual) },
                    onFailure = { failure -> value = old; onFailure(failure) },
                )
            } finally {
                pending = false
            }
        }
    }
}
