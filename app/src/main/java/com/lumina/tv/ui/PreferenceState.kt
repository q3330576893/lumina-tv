package com.lumina.tv.ui

import androidx.compose.runtime.*
import com.lumina.tv.data.Prefs

/** Both the settings page and playback overlay observe the same persisted preference. */
@Composable
internal fun <T> rememberPreferenceState(prefs: Prefs?, key: String, read: () -> T, write: (T) -> Unit): MutableState<T> {
    val latestRead by rememberUpdatedState(read)
    val latestWrite by rememberUpdatedState(write)
    val snapshot = remember(prefs, key) { mutableStateOf(read()) }
    DisposableEffect(prefs, key) {
        val unsubscribe = prefs?.observePreference(key) { snapshot.value = latestRead() }
        snapshot.value = latestRead()
        onDispose { unsubscribe?.invoke() }
    }
    return remember(prefs, key) {
        object : MutableState<T> {
            override var value: T
                get() = snapshot.value
                set(value) { latestWrite(value); snapshot.value = latestRead() }
            override fun component1(): T = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
