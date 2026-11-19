package com.lumina.tv

import com.lumina.tv.data.B2ProgressChecks
import com.lumina.tv.data.B2SessionChecks
import com.lumina.tv.player.B2PlaybackChecks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object B2Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        withTimeout(60_000L) {
            B2ProgressChecks.run()
            B2SessionChecks.run()
            B2PlaybackChecks.run()
            B2ListChecks.run()
        }
        println("B2 PASS: progress/session persistence and playback policy regressions")
    }
}
