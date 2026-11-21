package com.lumina.tv.player

internal enum class HiddenPlaybackKey { OK, LEFT, RIGHT }
internal data class HiddenPlaybackInputState(
    val key: HiddenPlaybackKey? = null,
    val downAtMs: Long = 0,
    val boosted: Boolean = false,
) { val active: Boolean get() = key != null }

internal sealed interface HiddenPlaybackEvent {
    data class Down(val key: HiddenPlaybackKey, val repeat: Int) : HiddenPlaybackEvent
    data class Up(val key: HiddenPlaybackKey) : HiddenPlaybackEvent
    data object HoldTimeout : HiddenPlaybackEvent
    data object Cancel : HiddenPlaybackEvent
    data object Tap : HiddenPlaybackEvent
}

internal sealed interface HiddenPlaybackAction {
    data object ShowControls : HiddenPlaybackAction
    data object TogglePlay : HiddenPlaybackAction
    data class Boost(val enabled: Boolean) : HiddenPlaybackAction
    data class BeginSeek(val direction: Int, val repeat: Int) : HiddenPlaybackAction
    data class EndSeek(val direction: Int) : HiddenPlaybackAction
}

internal data class HiddenPlaybackTransition(
    val state: HiddenPlaybackInputState,
    val consumed: Boolean = false,
    val actions: List<HiddenPlaybackAction> = emptyList(),
)

internal const val HIDDEN_OK_HOLD_MS = 480L

/** Captured at Down, retained through recomposition until Up/cancel. All times are monotonic. */
internal fun reduceHiddenPlaybackInput(
    state: HiddenPlaybackInputState,
    event: HiddenPlaybackEvent,
    nowMs: Long,
    canStart: Boolean,
): HiddenPlaybackTransition {
    fun endActions(): List<HiddenPlaybackAction> = when (state.key) {
        HiddenPlaybackKey.OK -> if (state.boosted) listOf(HiddenPlaybackAction.Boost(false)) else emptyList()
        HiddenPlaybackKey.LEFT -> listOf(HiddenPlaybackAction.EndSeek(-1))
        HiddenPlaybackKey.RIGHT -> listOf(HiddenPlaybackAction.EndSeek(1))
        null -> emptyList()
    }
    return when (event) {
        HiddenPlaybackEvent.Cancel -> HiddenPlaybackTransition(HiddenPlaybackInputState(), actions = endActions())
        HiddenPlaybackEvent.Tap -> if (canStart)
            HiddenPlaybackTransition(HiddenPlaybackInputState(), true, endActions() + HiddenPlaybackAction.TogglePlay)
            else HiddenPlaybackTransition(state)
        HiddenPlaybackEvent.HoldTimeout -> if (state.key == HiddenPlaybackKey.OK && !state.boosted && nowMs - state.downAtMs >= HIDDEN_OK_HOLD_MS)
            HiddenPlaybackTransition(state.copy(boosted = true), true, listOf(HiddenPlaybackAction.Boost(true)))
            else HiddenPlaybackTransition(state)
        is HiddenPlaybackEvent.Down -> {
            if ((!state.active && (!canStart || event.repeat != 0)) || state.active && state.key != event.key)
                HiddenPlaybackTransition(state, state.active)
            else {
                val next = if (state.active) state else HiddenPlaybackInputState(event.key, nowMs)
                val actions = when (event.key) {
                    HiddenPlaybackKey.OK -> emptyList()
                    HiddenPlaybackKey.LEFT -> listOf(HiddenPlaybackAction.BeginSeek(-1, event.repeat))
                    HiddenPlaybackKey.RIGHT -> listOf(HiddenPlaybackAction.BeginSeek(1, event.repeat))
                }
                HiddenPlaybackTransition(next, true, actions)
            }
        }
        is HiddenPlaybackEvent.Up -> if (state.key != event.key) HiddenPlaybackTransition(state) else {
            val shortOk = state.key == HiddenPlaybackKey.OK && !state.boosted && nowMs - state.downAtMs < HIDDEN_OK_HOLD_MS
            HiddenPlaybackTransition(HiddenPlaybackInputState(), true,
                endActions() + if (shortOk) listOf(HiddenPlaybackAction.TogglePlay) else emptyList())
        }
    }
}

internal fun hiddenTouchEnabled(controlsVisible: Boolean, menuOpen: Boolean, panelOpen: Boolean, hasError: Boolean): Boolean =
    !controlsVisible && !menuOpen && !panelOpen && !hasError

internal fun playbackControlsOverlayVisible(controlsVisible: Boolean, seekHoldActive: Boolean, menuOpen: Boolean, buffering: Boolean): Boolean =
    (controlsVisible || seekHoldActive) && !menuOpen && !buffering

internal fun trickplayCanPresent(overlayVisible: Boolean, previewActive: Boolean, durationMs: Long, blocked: Boolean): Boolean =
    overlayVisible && previewActive && durationMs > 0 && !blocked
