package com.lumina.tv.player

import kotlinx.coroutines.*

object P0PermitChecks {
    fun run() = runBlocking {
        for (boundary in listOf("success", "destroy-failure", "cancel", "source", "generation", "surface", "intent", "later-retirement",
            "cancel-late", "source-late", "generation-late", "surface-late", "intent-late")) {
            val fence = LoadRetirementFence()
            val coordinator = PlaybackLoadCoordinator("A")
            val generation = coordinator.beginLoad("A")
            val old = checkNotNull(coordinator.grantPermit(generation, fence.awaitPermit()))
            val retirement = LoadRetirement()
            fence.track(retirement)
            check(!coordinator.accepts(old)) { "Retirement revision must invalidate the old permit" }
            var current = true
            var installed: PlaybackLoadPermit? = null
            val events = mutableListOf<String>()
            val retry = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    renewSoftwareRetryPermit(retirement, fence, coordinator, generation,
                        isCurrent = { current && coordinator.sourceId == "A" },
                        install = { installed = it; events += "install" },
                        start = { playing ->
                            check(coordinator.accepts(installed) && !coordinator.accepts(old))
                            check(!playing) { "The latest pause intent must be committed" }
                            events += listOf("commit-paused", "create-software", "attach-latest-surface", "start")
                        })
                } catch (_: RetirementBlockedException) { false }
            }
            check(installed == null && events.isEmpty())
            coordinator.desiredPlaying = false
            val later = if (boundary == "later-retirement" || boundary.endsWith("-late")) LoadRetirement().also(fence::track) else null
            when (boundary) {
                "destroy-failure" -> retirement.fail(IllegalStateException("fixture native destroy failed"))
                "cancel" -> retry.cancel()
                "source" -> coordinator.resolveSource(generation, "B")
                "generation" -> coordinator.beginLoad("B")
                "surface", "intent" -> current = false
            }
            if (boundary != "destroy-failure") retirement.succeed()
            yield()
            if (later != null) {
                check(installed == null && events.isEmpty()) { "Own retirement is insufficient while another decoder retires" }
                when (boundary) {
                    "cancel-late" -> retry.cancel()
                    "source-late" -> coordinator.resolveSource(generation, "B")
                    "generation-late" -> coordinator.beginLoad("B")
                    "surface-late", "intent-late" -> current = false
                }
                later.succeed()
            }
            if (boundary.startsWith("cancel")) retry.join() else {
                val started = retry.await()
                check(started == (boundary in listOf("success", "later-retirement"))) { boundary }
                check(started || installed == null) { "$boundary granted a stale permit" }
            }
            if (boundary in listOf("success", "later-retirement"))
                check(events == listOf("install", "commit-paused", "create-software", "attach-latest-surface", "start"))
        }
        var cleanupFailure: Throwable? = null
        retireNativeResources(destroy = {}, cleanupFiles = { error("fixture file cleanup failed") },
            onFileCleanupFailure = { cleanupFailure = it })
        check(cleanupFailure != null) { "File failure must remain observable without poisoning native retirement" }
        check(runCatching { retireNativeResources(destroy = { error("fixture destroy failed") },
            cleanupFiles = {}, onFileCleanupFailure = {}) }.isFailure)
        for (destroyFails in listOf(false, true)) {
            val io = LoadScopedIo("physical", { true }, { it() })
            var fileFailureObserved = false
            val retired = io.retire {
                retireNativeResources(destroy = { if (destroyFails) error("fixture native destroy failed") },
                    cleanupFiles = { error("fixture temp-file deletion failed") },
                    onFileCleanupFailure = { fileFailureObserved = true })
            }
            val fence = LoadRetirementFence().apply { track(retired) }
            val result = withTimeout(3_000) { runCatching { fence.awaitPermit() } }
            check(fileFailureObserved)
            check(result.isSuccess != destroyFails)
            if (destroyFails) check(result.exceptionOrNull() is RetirementBlockedException)
        }
        check(!shouldRetryMpvInSoftware(autoSoftwareRetry = false, softwareRetryUsed = false))
        check(shouldRetryMpvInSoftware(autoSoftwareRetry = true, softwareRetryUsed = false))
        check(!shouldRetryMpvInSoftware(autoSoftwareRetry = true, softwareRetryUsed = true))
        println("P0PermitChecks PASS: revision invalidation; retirement success then fresh permit/install/commit/start; destroy fail-closed; file cleanup separate; cancel/source/generation/Surface/intent; feature flag")
    }
}
