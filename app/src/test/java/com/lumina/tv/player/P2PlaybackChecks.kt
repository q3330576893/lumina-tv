package com.lumina.tv.player

/** Delayed native events, preference exclusion, alternate audio paths and shared recovery budget. */
object P2PlaybackChecks {
    @JvmStatic fun main(args: Array<String>) {
        val gate = LoadEventGate<Any>()
        val queue = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        fun receive(token: Any, event: String) {
            if (gate.accepts(token)) queue.add { if (gate.accepts(token)) delivered += event }
        }
        val versions = List(3) { Any() }
        versions.forEachIndexed { index, token ->
            gate.activate(token)
            receive(token, "pause-$index")
            receive(token, "ready-$index")
            while (queue.isNotEmpty()) queue.removeFirst()()
            receive(token, "late-eof-$index")
            receive(token, "late-error-$index")
            gate.retire(token)
            // Events already queued, and events arriving after retirement, are both stale.
            receive(token, "after-retire-$index")
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
        check(delivered == listOf("pause-0", "ready-0", "pause-1", "ready-1", "pause-2", "ready-2"))
        val hardware = Any()
        val software = Any()
        gate.activate(hardware)
        receive(hardware, "old-hw-error")
        gate.activate(software)
        receive(software, "software-ready")
        while (queue.isNotEmpty()) queue.removeFirst()()
        check(delivered.last() == "software-ready" && "old-hw-error" !in delivered)
        receive(software, "after-exit")
        gate.close()
        while (queue.isNotEmpty()) queue.removeFirst()()
        check("after-exit" !in delivered)

        val failures = PlaybackPathFailures()
        val dts = PlaybackPath("source-a", "mpv", PlaybackDelivery.DIRECT_PLAY, "dts:1", "auto")
        fun select(path: PlaybackPath = dts, pin: String? = null, available: Boolean = true) =
            selectPlaybackEngine("mpv", pin, path, failures, mpvAvailable = available)
        check(select() == "mpv")
        check(select(available = false) == "media3")
        for (reason in listOf(PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.FIRST_FRAME_TIMEOUT, PlaybackFailureReason.SURFACE)) {
            failures.record(PlaybackPathFailure(dts, reason, 1, "injected"))
            check(select() == "media3")
            check(select(pin = "mpv") == "media3")
            check(select(pin = "mpv", available = false) == "media3")
        }
        failures.record(PlaybackPathFailure(dts.copy(engine = "media3"), PlaybackFailureReason.DECODING, 2, "DTS failed"))
        check(select() == null)
        check(select(dts.copy(audioTrack = "aac:2"), pin = "media3") == "media3")
        check(select(dts.copy(delivery = PlaybackDelivery.TRANSCODE), pin = "media3") == "media3")
        check(select(dts.copy(sourceId = "source-b")) == "mpv")
        check(select(dts.copy(requestVariant = 1)) == "mpv")
        check(failures.snapshot().map { it.reason }.containsAll(listOf(
            PlaybackFailureReason.INITIALIZATION, PlaybackFailureReason.FIRST_FRAME_TIMEOUT, PlaybackFailureReason.SURFACE)))

        val audio = AudioLogCorrelation.observations(listOf(
            "100.000 42 9 D AudioTrack(0x123): sessionId=7 routedDeviceId=4",
            "100.100 42 9 D AudioTrack(0x123): write frames=480 underrun=1",
            "100.200 42 9 D AudioTrackImpl: [zero]",
            "100.300 99 9 D AudioTrack(0x123): sessionId=9",
            "90.000 42 9 D AudioTrack(0x123): sessionId=5",
        ), pid = 42, fromMs = 100_000, untilMs = 101_000)
        check(audio.length() == 3)
        check(audio.getJSONObject(0).getString("routeDeviceId") == "4")
        check(audio.getJSONObject(1).getString("sessionKey") == "42:7")
        check(audio.getJSONObject(2).isNull("audioTrackSession"))
        check(audio.getJSONObject(2).isNull("routeDeviceId"))

        val budget = PlaybackRecoveryBudget()
        check(budget.tryAcquire(0))
        // Neither re-selection, renegotiation, rebuild nor source change acquires/resets the budget.
        repeat(3) { select(dts.copy(sourceId = "source-$it")) }
        check(budget.attempts == 1 && !budget.tryAcquire(59_999))
        check(budget.tryAcquire(60_000) && budget.tryAcquire(120_000))
        check(!budget.tryAcquire(180_000) && budget.exhausted)
        failures.clearForManualRetry()
        val manualBudget = PlaybackRecoveryBudget()
        check(select() == "mpv" && manualBudget.tryAcquire(180_000))
        println("P2 PASS: queued/late EOF and error isolation; three loads; software retry; exit; path exclusion; shared budget; AudioTrack time/PID/session correlation")
    }
}
