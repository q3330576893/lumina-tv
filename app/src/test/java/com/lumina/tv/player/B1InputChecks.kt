package com.lumina.tv.player

internal object B1InputChecks {
    fun run() {
        var state = HiddenPlaybackInputState()
        fun event(event: HiddenPlaybackEvent, now: Long, canStart: Boolean = true): HiddenPlaybackTransition =
            reduceHiddenPlaybackInput(state, event, now, canStart).also { state = it.state }
        check(event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 0), 0).actions.isEmpty())
        check(state.active)
        check(event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 1), 200).actions.isEmpty())
        check(event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 300).actions == listOf(HiddenPlaybackAction.TogglePlay))
        check(!state.active && event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 301).actions.isEmpty())
        event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 0), 1_000)
        check(event(HiddenPlaybackEvent.HoldTimeout, 1_479).actions.isEmpty())
        check(event(HiddenPlaybackEvent.HoldTimeout, 1_480).actions == listOf(HiddenPlaybackAction.Boost(true)))
        check(event(HiddenPlaybackEvent.HoldTimeout, 1_481).actions.isEmpty())
        // Changing control visibility does not revoke a captured gesture. KeyUp restores once.
        check(event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 2_000, canStart = false).actions == listOf(HiddenPlaybackAction.Boost(false)))
        check(event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 2_001).actions.isEmpty())
        event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 0), 3_000)
        check(event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 3_600).actions.isEmpty()) // Delayed timer must not cause a short action.
        for ((key, direction) in listOf(HiddenPlaybackKey.LEFT to -1, HiddenPlaybackKey.RIGHT to 1)) {
            check(event(HiddenPlaybackEvent.Down(key, 0), 4_000).actions == listOf(HiddenPlaybackAction.BeginSeek(direction, 0)))
            repeat(5) { check(event(HiddenPlaybackEvent.Down(key, it + 1), 4_100L + it).actions == listOf(HiddenPlaybackAction.BeginSeek(direction, it + 1))) }
            check(event(HiddenPlaybackEvent.Up(key), 5_000, false).actions == listOf(HiddenPlaybackAction.EndSeek(direction)))
            check(event(HiddenPlaybackEvent.Up(key), 5_001).actions.isEmpty())
        }
        for (reason in listOf("focus-loss", "menu", "pause", "new-load", "dispose")) {
            event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 0), 6_000)
            event(HiddenPlaybackEvent.HoldTimeout, 6_480)
            check(event(HiddenPlaybackEvent.Cancel, 6_500).actions == listOf(HiddenPlaybackAction.Boost(false))) { reason }
            check(!state.active && event(HiddenPlaybackEvent.Up(HiddenPlaybackKey.OK), 6_600).actions.isEmpty())
            event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.RIGHT, 0), 7_000)
            check(event(HiddenPlaybackEvent.Cancel, 7_100).actions == listOf(HiddenPlaybackAction.EndSeek(1)))
        }
        check(event(HiddenPlaybackEvent.Tap, 8_000).actions == listOf(HiddenPlaybackAction.TogglePlay))
        check(event(HiddenPlaybackEvent.Tap, 8_001, canStart = false).actions.isEmpty())
        check(!event(HiddenPlaybackEvent.Down(HiddenPlaybackKey.OK, 1), 9_000).consumed) // A late repeat cannot become a new press.
        check(hiddenTouchEnabled(false, false, false, false))
        check(!hiddenTouchEnabled(true, false, false, false))
        check(!hiddenTouchEnabled(false, true, false, false))
        check(!hiddenTouchEnabled(false, false, true, false))
        check(!hiddenTouchEnabled(false, false, false, true))
        for (controls in listOf(false, true)) for (hold in listOf(false, true)) for (menu in listOf(false, true)) for (buffering in listOf(false, true)) {
            val visible = playbackControlsOverlayVisible(controls, hold, menu, buffering)
            check(visible == ((controls || hold) && !menu && !buffering))
            check(trickplayCanPresent(visible, true, 900_000, false) == visible)
            check(!trickplayCanPresent(visible, true, 900_000, true))
            check(!trickplayCanPresent(visible, false, 900_000, false))
            check(!trickplayCanPresent(visible, true, 0, false))
        }
        val source = java.io.File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check(!source.contains("if ((!controlsVisible || seekHoldDirection"))
        check(source.contains("visible = controlsOverlayVisible"))
        check(source.contains("pointerInput(item.id)"))
        check(source.contains("if (!canShowTrickplay || client == null)"))
        println("B1 Q-005/Q-022 PASS: hidden taps, blocked/button regions, OK short/long exactly once, repeat/up/cancel, hold overlay and request visibility matrix (device input routing still required)")
    }
}
