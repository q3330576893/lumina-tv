package com.lumina.tv.player

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import java.io.File
import java.io.IOException

object P1FirstFrameChecks {
    fun run() {
        val budget = PlaybackRecoveryBudget()
        val machine = FirstFrameRecovery().apply { beginLoad(1, 0) }
        fun tick(now: Long, generation: Int = machine.observation.generation, surface: Boolean = true,
                 video: Boolean = true, ready: Boolean = true) = machine.tick(now, generation, ready, video, surface, true, budget)
        val stages = mutableListOf(machine.stage)
        check(tick(0) == null)
        check(tick(7_999) == null)
        check(tick(8_000) == FirstFrameAction.REBIND); stages += machine.stage
        check(tick(9_999) == null)
        check(tick(10_000) == FirstFrameAction.RECREATE); stages += machine.stage
        check(budget.attempts == 1)
        machine.beginLoad(2, 10_001)
        check(tick(10_001) == null)
        check(tick(18_001) == null) // Shared cooldown is measured, not accumulated delay.
        check(tick(69_999) == null)
        check(tick(70_000) == FirstFrameAction.ALTERNATE); stages += machine.stage
        check(budget.attempts == 2)
        machine.beginLoad(3, 70_001)
        check(tick(70_001) == null)
        check(tick(78_001) == FirstFrameAction.TERMINAL); stages += machine.stage
        check(stages == FirstFrameStage.entries)
        repeat(50) { check(tick(80_000L + it * 10_000) == null) }
        machine.beginLoad(4, 600_000)
        check(tick(610_000) == null && budget.attempts == 2) // Load changes never reopen Terminal.

        val late = FirstFrameRecovery().apply { beginLoad(5, 0) }
        val fresh = PlaybackRecoveryBudget()
        fun lateTick(t: Long, surface: Boolean) = late.tick(t, 5, true, true, surface, true, fresh)
        check(lateTick(0, false) == null)
        check(lateTick(8_000, false) == null)
        check(lateTick(14_000, true) == null)
        check(lateTick(21_999, true) == null && fresh.attempts == 0)
        check(late.frame(late.observation, 21_999))
        check(lateTick(30_000, true) == null)
        val absent = FirstFrameRecovery().apply { beginLoad(6, 0) }
        check(absent.tick(0, 6, true, true, false, true, fresh) == null)
        check(absent.tick(15_000, 6, true, true, false, true, fresh) == FirstFrameAction.TERMINAL)

        val audio = FirstFrameRecovery().apply { beginLoad(7, 0) }
        for (t in listOf(0L, 8_000, 60_000, 1_000_000))
            check(audio.tick(t, 7, true, false, false, true, fresh) == null)
        check(!audio.ownsRecovery && fresh.attempts == 0)
        val audioReplacement = FirstFrameRecovery().apply { beginLoad(1, 0) }
        audioReplacement.tick(0, 1, true, true, true, true, fresh)
        check(audioReplacement.ownsRecovery)
        audioReplacement.beginLoad(2, 5_000)
        audioReplacement.audioOnlyReady(2)
        check(!audioReplacement.ownsRecovery)
        check(audioReplacement.tick(1_000_000, 2, true, false, false, true, fresh) == null)
        val initialization = DecoderInitializationException(Format.Builder().setSampleMimeType("video/hevc").build(),
            IOException("fixture init failed"), false, -4999)
        val route = classifyRendererFailure(playbackErrorSample(PlaybackException.ERROR_CODE_UNSPECIFIED, initialization))
        check(route.route == RendererFailureRoute.VIDEO)
        val failed = FirstFrameRecovery().apply { beginLoad(8, 0); videoFailure(8) }
        check(failed.tick(0, 8, false, false, true, true, fresh) == FirstFrameAction.REBIND)
        check(failed.tick(2_000, 8, false, false, true, true, fresh) == FirstFrameAction.RECREATE)

        val frames = FirstFrameRecovery().apply { beginLoad(9, 10) }
        val oldLoad = frames.observation
        frames.beginLoad(10, 20)
        check(!frames.frame(oldLoad, 30))
        val beforeSeek = frames.observation
        frames.beforeSeek(40)
        check(!frames.frame(beforeSeek, 50))
        check(!frames.frame(frames.observation, 39))
        check(frames.frame(frames.observation, 41))
        check(frames.tick(100_000, 10, true, true, true, true, fresh) == null)

        // Other recoveries share the ceiling; no first-frame bypass can mint an extra attempt.
        val exhausted = PlaybackRecoveryBudget()
        for (t in listOf(0L, 60_000, 120_000)) check(exhausted.tryAcquire(t))
        val limited = FirstFrameRecovery().apply { beginLoad(11, 180_000) }
        limited.tick(180_000, 11, true, true, true, true, exhausted)
        check(limited.tick(188_000, 11, true, true, true, true, exhausted) == FirstFrameAction.REBIND)
        check(limited.tick(190_000, 11, true, true, true, true, exhausted) == FirstFrameAction.TERMINAL)
        check(exhausted.attempts == 3)
        val twoRounds = FirstFrameRecovery().apply { beginLoad(1, 0) }
        val twoBudget = PlaybackRecoveryBudget()
        for (t in listOf(0L, 8_000, 10_000)) twoRounds.tick(t, 1, true, true, true, true, twoBudget)
        twoRounds.beginLoad(2, 10_001)
        twoRounds.tick(10_001, 2, true, true, true, true, twoBudget)
        check(twoRounds.tick(70_000, 2, true, true, true, true, twoBudget) == FirstFrameAction.ALTERNATE)
        // The production no-alternate branch closes the second failed READY round immediately.
        check(twoRounds.terminate() == FirstFrameAction.TERMINAL)
        repeat(20) { check(twoRounds.tick(140_000L + it, 2, true, true, true, true, twoBudget) == null) }
        check(twoBudget.attempts == 2 && !twoBudget.available(PlaybackRecoveryKind.FIRST_FRAME_RECREATE))
        val deadline = FirstFrameRecovery().apply { beginLoad(1, 0) }
        deadline.tick(0, 1, true, true, true, true, PlaybackRecoveryBudget())
        check(deadline.tick(150_000, 1, false, true, false, true, PlaybackRecoveryBudget()) == FirstFrameAction.TERMINAL)
        val screen = File("src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt").readText()
        check("firstFrameRecoveryTried" !in screen)
        check("firstFrameRecovery.videoFailure(sessionGeneration)" in screen)
        check("firstFrameRecovery.ownsRecovery || recovering" in screen)
        check("请重试、更换片源或退出播放" in screen)
        println("P1FirstFrameChecks PASS: five stages; 78.001s convergence; two READY rounds; late/absent Surface; audio exclusion; VIDEO init; shared ceiling; stale load/seek frames")
    }
}
