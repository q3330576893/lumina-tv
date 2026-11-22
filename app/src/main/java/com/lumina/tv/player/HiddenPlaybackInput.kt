package com.lumina.tv.player

internal enum class HiddenPlaybackKey { OK, LEFT, RIGHT }
internal data class HiddenPlaybackInputState(
    val key: HiddenPlaybackKey? = null,
    val downAtMs: Long = 0,
    val boosted: Boolean = false,
    val physicalCode: Int? = null,
    val lastUpPhysicalCode: Int? = null,
    val lastUpAtMs: Long = Long.MIN_VALUE,
) { val active: Boolean get() = key != null }

internal sealed interface HiddenPlaybackEvent {
    data class Down(val key: HiddenPlaybackKey, val repeat: Int, val physicalCode: Int? = null) : HiddenPlaybackEvent
    data class Up(val key: HiddenPlaybackKey, val physicalCode: Int? = null) : HiddenPlaybackEvent
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
private const val HIDDEN_DUPLICATE_UP_WINDOW_MS = 400L

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
    fun cleared(lastUpPhysicalCode: Int? = state.lastUpPhysicalCode, lastUpAtMs: Long = state.lastUpAtMs) =
        HiddenPlaybackInputState(lastUpPhysicalCode = lastUpPhysicalCode, lastUpAtMs = lastUpAtMs)
    return when (event) {
        HiddenPlaybackEvent.Cancel -> HiddenPlaybackTransition(cleared(), actions = endActions())
        HiddenPlaybackEvent.Tap -> if (canStart)
            HiddenPlaybackTransition(cleared(), true, endActions() + HiddenPlaybackAction.TogglePlay)
            else HiddenPlaybackTransition(state)
        HiddenPlaybackEvent.HoldTimeout -> if (state.key == HiddenPlaybackKey.OK && !state.boosted && nowMs - state.downAtMs >= HIDDEN_OK_HOLD_MS)
            HiddenPlaybackTransition(state.copy(boosted = true), true, listOf(HiddenPlaybackAction.Boost(true)))
            else HiddenPlaybackTransition(state)
        is HiddenPlaybackEvent.Down -> {
            val physicalCode = event.physicalCode ?: event.key.ordinal
            if (!state.active) {
                if (!canStart || event.repeat != 0) return HiddenPlaybackTransition(state)
                val next = state.copy(key = event.key, downAtMs = nowMs, boosted = false, physicalCode = physicalCode)
                val actions = when (event.key) {
                    HiddenPlaybackKey.OK -> emptyList()
                    HiddenPlaybackKey.LEFT -> listOf(HiddenPlaybackAction.BeginSeek(-1, 0))
                    HiddenPlaybackKey.RIGHT -> listOf(HiddenPlaybackAction.BeginSeek(1, 0))
                }
                HiddenPlaybackTransition(next, true, actions)
            } else if (state.key != event.key || state.physicalCode != physicalCode) {
                HiddenPlaybackTransition(state, true)
            } else if (event.repeat == 0) {
                HiddenPlaybackTransition(state, true)
            } else {
                val actions = when (event.key) {
                    HiddenPlaybackKey.OK -> emptyList()
                    HiddenPlaybackKey.LEFT -> listOf(HiddenPlaybackAction.BeginSeek(-1, event.repeat))
                    HiddenPlaybackKey.RIGHT -> listOf(HiddenPlaybackAction.BeginSeek(1, event.repeat))
                }
                HiddenPlaybackTransition(state, true, actions)
            }
        }
        is HiddenPlaybackEvent.Up -> {
            val physicalCode = event.physicalCode ?: event.key.ordinal
            if (state.key != event.key || state.physicalCode != physicalCode) {
                val duplicate = !state.active && state.lastUpPhysicalCode == physicalCode &&
                    nowMs - state.lastUpAtMs in 0 until HIDDEN_DUPLICATE_UP_WINDOW_MS
                HiddenPlaybackTransition(state, duplicate)
            } else {
            val shortOk = state.key == HiddenPlaybackKey.OK && !state.boosted && nowMs - state.downAtMs < HIDDEN_OK_HOLD_MS
                HiddenPlaybackTransition(cleared(physicalCode, nowMs), true,
                    endActions() + if (shortOk) listOf(HiddenPlaybackAction.TogglePlay) else emptyList())
            }
        }
    }
}

internal fun hiddenTouchEnabled(controlsVisible: Boolean, menuOpen: Boolean, panelOpen: Boolean, hasError: Boolean): Boolean =
    !controlsVisible && !menuOpen && !panelOpen && !hasError

internal fun playbackControlsOverlayVisible(controlsVisible: Boolean, seekHoldActive: Boolean, menuOpen: Boolean, buffering: Boolean): Boolean =
    (controlsVisible || seekHoldActive) && !menuOpen && !buffering

internal fun trickplayCanPresent(overlayVisible: Boolean, previewActive: Boolean, durationMs: Long, blocked: Boolean): Boolean =
    overlayVisible && previewActive && durationMs > 0 && !blocked
