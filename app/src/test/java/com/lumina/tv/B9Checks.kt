package com.lumina.tv

import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import com.lumina.tv.diag.CrashStore
import com.lumina.tv.diag.ExitTraceKind
import com.lumina.tv.diag.ExitTraceReader
import org.json.JSONObject
import com.lumina.tv.diag.ProcessCrashHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import com.lumina.tv.diag.AppLog
import com.lumina.tv.diag.AppLogSink
import com.lumina.tv.diag.AppLogWriter
import com.lumina.tv.diag.RollingAppLogFile
import com.lumina.tv.diag.PageLog
import com.lumina.tv.diag.NetLog
import com.lumina.tv.data.PlaybackCacheStorage
import java.util.concurrent.atomic.AtomicReference

/** B9 process startup and crash diagnostics regressions; no Android runtime required. */
object B9Checks {
    @JvmStatic fun main(args: Array<String>) {
        bootCapability()
        exitTraces()
        exitStats()
        processHandler()
        latestCrash()
        crashOverlay()
        processLogFiles()
        processLogBackpressure()
        processLogWiring()
        playbackCacheMaintenance()
        println("B9 PASS: Q-036 boot capability; Q-037 bounded exit text/protobuf; Q-038 stats recovery; Q-039 one process handler; Q-040 fresh latest; Q-041 overlay; Q-042 bounded background redacted process logs/rotation; R8-06 cache disk usage/serialized maintenance/failures")
    }

    private fun crashOverlay() {
        val root = source("MainActivity.kt").substringAfter("private fun AppRoot(")
        val snackbar = root.substringAfter("Snackbar(").substringBefore("AnimatedContent(")
        check(".zIndex(1f)" in snackbar)
        check("showCrashLog = true; onConsumeCrashNotice()" in snackbar)
        check("dismissAction = { TextButton(onClick = onConsumeCrashNotice)" in snackbar)
        check("AlertDialog(" in snackbar && "crashLogText" in snackbar)
        check("withContext(Dispatchers.IO)" in root && "CrashHandler.diagnosticReport(context)" in root)
        // The tested modifier belongs to the root Snackbar; pages remain in the lower sibling.
        check(root.indexOf("Snackbar(") < root.indexOf("AnimatedContent("))
        check("targetState = screen" in root.substringAfter("AnimatedContent("))
    }

    private fun processHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val forwarded = AtomicInteger()
        val written = AtomicInteger()
        val collected = AtomicInteger()
        val failure = IllegalStateException("same crash")
        val current = Thread.currentThread()
        try {
            Thread.setDefaultUncaughtExceptionHandler { actualThread, actualError ->
                check(actualThread === current && actualError === failure)
                forwarded.incrementAndGet()
            }
            val owner = ProcessCrashHandler { it() }
            owner.install({ _, _ -> written.incrementAndGet() }, { collected.incrementAndGet() })
            val first = Thread.getDefaultUncaughtExceptionHandler()!!
            val ready = CountDownLatch(12)
            val start = CountDownLatch(1)
            val installers = List(12) {
                thread {
                    ready.countDown(); start.await()
                    owner.install({ _, _ -> error("Duplicate wrapper installed") }, { collected.incrementAndGet() })
                }
            }
            ready.await(); start.countDown(); installers.forEach { it.join() }
            check(Thread.getDefaultUncaughtExceptionHandler() === first && collected.get() == 1)
            first.uncaughtException(current, failure)
            check(written.get() == 1 && forwarded.get() == 1)
            first.uncaughtException(current, failure)
            check(written.get() == 2 && forwarded.get() == 2)

            // Persistence and previous-exit collection failures must still leave a working wrapper.
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> forwarded.incrementAndGet() }
            val failing = ProcessCrashHandler { it() }
            failing.install({ _, _ -> throw IOException() }, { throw IOException() })
            check(failing.previousExitFailure == "IOException")
            val failedCollectorHandler = Thread.getDefaultUncaughtExceptionHandler()!!
            failing.install({ _, _ -> error("reinstalled after collection failure") }, {})
            check(Thread.getDefaultUncaughtExceptionHandler() === failedCollectorHandler)
            failedCollectorHandler.uncaughtException(current, failure)
            check(forwarded.get() == 3)
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
        check("CrashHandler.install(this)" in source("LuminaApp.kt"))
        check("CrashHandler.install" !in source("MainActivity.kt"))
        check("installation.install(" in source("CrashHandler.kt"))
    }

    private fun latestCrash() {
        val directory = Files.createTempDirectory("b9-latest").toFile()
        try {
            val store = CrashStore(directory)
            val latest = File(directory, "crash-latest.txt")
            store.recordJava("same", "time=1 page=Home engine=media3 cause=first token=latest-secret", 1L)
            val archived = File(directory, "crash-1.txt").readText()
            check("latest-secret" !in archived)
            store.recordJava("other", "time=2 page=Detail engine=media3 cause=other", 2L)
            store.recordJava("same", "time=3 page=Player engine=mpv cause=new-cause", 3L)
            check(latest.readText() == "time=3 page=Player engine=mpv cause=new-cause")
            val entry = store.readStats().getJSONObject("same")
            check(entry.getInt("count") == 2 && entry.getLong("lastAt") == 3L)
            check(File(directory, "crash-1.txt").readText().contains("cause=other"))
            check(File(directory, "crash-2.txt").readText() == archived)
            check(!File(directory, "crash-3.txt").exists())
            latest.delete()
            store.recordJava("same", "time=4 restored after clear", 4L)
            check(latest.readText() == "time=4 restored after clear")
            for (i in 1..8) store.recordJava("unique-$i", "scene-$i", i.toLong())
            check((1..5).all { File(directory, "crash-$it.txt").readText() == "scene-${9 - it}" })
            check(!File(directory, "crash-6.txt").exists())
            File(directory, "crash-stats.json").writeText("bad json")
            store.recordJava("recovered", "fresh scene", 12L)
            check(store.readStats().getJSONObject("recovered").getInt("count") == 1)
            check(latest.readText() == "fresh scene")
            // Preserve best-effort history rotation: a broken archive cannot keep latest/count stale.
            val blockedArchive = File(directory, "crash-5.txt")
            blockedArchive.delete(); blockedArchive.mkdirs()
            File(blockedArchive, "block").writeText("blocks overwrite")
            store.recordJava("archive-failure", "new latest despite broken history", 13L)
            check(latest.readText() == "new latest despite broken history")
            check(store.readStats().getJSONObject("archive-failure").getInt("count") == 1)
        } finally { directory.deleteRecursively() }
        val handler = source("CrashHandler.kt")
        check("CrashStore(root(context)).recordJava(fingerprint, safe, now)" in handler)
        check("entry.optInt(\"count\") > 1" !in handler)
    }

    private fun exitTraces() {
        var closed = false
        val anr = ExitTraceReader.read(ExitTraceKind.ANR, 30) {
            object : ByteArrayInputStream("main thread blocked\ntoken=trace-secret".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
        }
        check(closed && anr.status == "available" && !anr.truncated)
        check("main thread blocked" in anr.report() && "trace-secret" !in anr.report())
        for (kind in ExitTraceKind.entries) {
            var read = 0
            closed = false
            val large = ExitTraceReader.read(kind, 36) {
                object : ByteArrayInputStream(ByteArray(kind.limit * 2) { 65 }) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
                    override fun close() { closed = true; super.close() }
                }
            }
            check(closed && large.truncated && large.bytes.size == kind.limit && read == kind.limit + 1)
            if (kind == ExitTraceKind.NATIVE) {
                check(large.nativeFileName.endsWith(".pb.partial") && "not a complete protobuf" in large.report())
            }
            check(ExitTraceReader.read(kind, 36) { null }.status == "not_provided")
            check(ExitTraceReader.read(kind, 36) { ByteArrayInputStream(byteArrayOf()) }.status == "empty")
            closed = false
            val failed = ExitTraceReader.read(kind, 36) {
                object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("must not leak secret")
                    override fun close() { closed = true }
                }
            }
            check(closed && failed.status == "read_failed:IOException" && "secret" !in failed.report())
            check(ExitTraceReader.read(kind, kind.minSdk - 1) { error("Unsupported API opened stream") }.status == "unsupported_api")
        }
        val protobuf = byteArrayOf(8, 1, 18, 2, -1, -2)
        val native = ExitTraceReader.read(ExitTraceKind.NATIVE, 31) { ByteArrayInputStream(protobuf) }
        check(native.bytes.contentEquals(protobuf) && native.kind.format == "tombstone-protobuf")
        check("native-trace-latest.pb" in native.report() && '\uFFFD' !in native.report())
        val handler = source("CrashHandler.kt")
        check("exit.traceInputStream" in handler && "ExitTraceReader.read" in handler)
        check("/data/anr" !in handler && "/data/tombstones" !in handler)
        check("File(context.filesDir, trace.nativeFileName).writeBytes(trace.bytes)" in handler)
        check("binaryPersistence" in handler)
        check("LogRedactor.redact(exit.description" in handler)
        check("LogRedactor.redact(payload.toString" !in handler)
    }

    private fun processLogFiles() {
        val directory = Files.createTempDirectory("b9-log-files").toFile()
        val current = File(directory, "app.log")
        val previous = File(directory, "app-prev.log")
        try {
            AppLogWriter(RollingAppLogFile(directory, 16 * 1024)).use { writer ->
                check(writer.awaitIdle())
                check(current.isFile && !previous.exists())
                check(writer.add("page=Home token=token-secret https://user:password-secret@example.test/path?api_key=query-secret"))
                check(writer.add("Cookie: SID=cookie-secret; session=other-secret\nSet-Cookie: id=set-cookie-secret\nAuthorization: Bearer auth-secret"))
                check(writer.add("""{"Cookie":"json-cookie-secret","Set-Cookie":"json-set-cookie-secret"}"""))
                check(writer.add("error=\"Cookie: SID=quoted-cookie-secret\""))
                check(writer.add("error='Set-Cookie: SID=quoted-set-cookie-secret'"))
                check(writer.add("token=\"" + "oversized-secret".repeat(1000) + "\""))
                check(writer.awaitIdle())
                val text = current.readText()
                for (secret in listOf("token-secret", "password-secret", "query-secret", "cookie-secret", "other-secret",
                    "set-cookie-secret", "auth-secret", "json-cookie-secret", "json-set-cookie-secret", "oversized-secret",
                    "quoted-cookie-secret", "quoted-set-cookie-secret")) {
                    check(secret !in text && secret !in writer.snapshot()) { "Persisted secret: $secret" }
                }
                check("event omitted" in text && text.lines().filter(String::isNotBlank).size == 6)
                for (i in 1..100) check(writer.add("event-$i " + "中文😀".repeat(200)))
                check(writer.awaitIdle())
                check(current.length() <= 16 * 1024 && "event-100 " in current.readText())
                check("event-1 " !in current.readText() && !previous.exists())
                check(writer.snapshot().length < 33 * 1024)
            }
            val firstProcess = current.readText()
            AppLogWriter(RollingAppLogFile(directory, 16 * 1024)).use { writer ->
                check(writer.add("second process marker"))
                check(writer.awaitIdle())
                check(previous.readText() == firstProcess && previous.length() <= 16 * 1024)
                check("second process marker" in current.readText() && "event-100 " !in current.readText())
            }
            // An empty prior process does not erase the last useful previous log.
            current.writeText("")
            AppLogWriter(RollingAppLogFile(directory, 16 * 1024)).use { check(it.awaitIdle()) }
            check(previous.readText() == firstProcess)
        } finally { directory.deleteRecursively() }
    }

    private fun processLogBackpressure() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(3)
        val producer = Thread.currentThread()
        val wrongThread = AtomicInteger()
        val sink = object : AppLogSink {
            override fun initialize() { if (Thread.currentThread() === producer) wrongThread.incrementAndGet() }
            override fun append(line: String) {
                if (Thread.currentThread() === producer) wrongThread.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                written.countDown()
            }
        }
        AppLogWriter(sink, capacity = 2).use { writer ->
            try {
                check(writer.awaitIdle() && writer.add("blocked disk"))
                check(entered.await(2, TimeUnit.SECONDS))
                // The disk worker remains blocked throughout these producer calls.
                check(writer.add("queued 1") && writer.add("queued 2"))
                check(!writer.add("dropped"))
                check("dropped=1" in writer.snapshot())
            } finally { release.countDown() }
            check(written.await(2, TimeUnit.SECONDS) && writer.awaitIdle())
            check(wrongThread.get() == 0)
        }
        AppLogWriter(object : AppLogSink {
            override fun initialize() { throw IOException("private-failure-secret") }
            override fun append(line: String) = error("Initialization failed")
        }).use { writer ->
            check(writer.add("still observable in memory") && writer.awaitIdle())
            check("initialize:IOException" in writer.snapshot() && "still observable" in writer.snapshot())
            check("private-failure-secret" !in writer.snapshot())
        }
    }

    private fun processLogWiring() {
        val directory = Files.createTempDirectory("b9-log-owner").toFile()
        try {
            AppLog.start(directory, "first owner")
            PageLog.add("owner-page token=owner-secret")
            NetLog.add(1L, "GET", "https://example.test/owner-network?token=owner-secret", 2L, 200, null)
            PageLog.add("owner-cookie " + """{"Cookie":"""" + "cookie-overflow-secret".repeat(100) + "\"}")
            NetLog.add(2L, "GET", "https://example.test/owner-cookie-network", 2L, 500,
                """{"Set-Cookie":"""" + "cookie-overflow-secret".repeat(100) + "\"}")
            AppLog.start(File(directory, "must-not-initialize"), "second owner")
            val current = File(directory, "app.log")
            var text = ""
            for (attempt in 1..200) {
                text = runCatching { current.readText() }.getOrDefault("")
                if ("owner-cookie-network" in text && "owner-page" in text) break
                Thread.sleep(5)
            }
            check("process_started first owner" in text && "second owner" !in text)
            check("owner-page" in text && "owner-network" in text && "owner-secret" !in text)
            check("owner-cookie" in text && "owner-cookie-network" in text && "cookie-overflow-secret" !in text)
            check(!File(directory, "must-not-initialize").exists() && !File(directory, "app-prev.log").exists())
        } finally { directory.deleteRecursively() }
        val application = source("LuminaApp.kt")
        check(application.indexOf("AppLog.start(") < application.indexOf("CrashHandler.install("))
        check("initializeAppLog" !in source("MainActivity.kt") && "app-prev.log" !in source("MainActivity.kt"))
        check("AppLog.snapshot()" in source("CrashHandler.kt"))
        val exporter = source("diag/DiagExporter.kt")
        check("\"app.log\" to (AppLog.snapshot()" in exporter)
        check("File(context.filesDir, \"app-prev.log\")" in exporter)
        check("native-trace-latest.pb" !in exporter) // Unsanitized binary stays private.
    }

    private fun exitStats() {
        val directory = Files.createTempDirectory("b9-exit-stats").toFile()
        try {
            val file = File(directory, "crash-stats.json")
            val store = CrashStore(directory)
            for (initial in listOf(null, "", "broken {", "[]", "null")) {
                if (initial == null) file.delete() else file.writeText(initial)
                for (type in listOf("ANR", "NATIVE")) {
                    store.recordExit(type, "token=stats-secret", 123L)
                    val stats = JSONObject(file.readText())
                    val crash = stats.getJSONObject("lastCrash")
                    check(crash.getString("type") == type && crash.getLong("at") == 123L)
                    check("stats-secret" !in crash.getString("evidence"))
                }
            }
            file.writeText("""{"existing":{"count":4},"other":"keep"}""")
            store.recordExit("NATIVE", "evidence", 456L)
            val stats = JSONObject(file.readText())
            check(stats.getJSONObject("existing").getInt("count") == 4 && stats.getString("other") == "keep")
            check(stats.getJSONObject("lastCrash").getLong("at") == 456L)
        } finally { directory.deleteRecursively() }
    }

    private fun bootCapability() {
        check(BootLaunchCapability(28, false).status == "pre_android_10")
        for (sdk in listOf(29, 30, 31, 35, 36)) {
            val ordinary = BootLaunchCapability(sdk, false)
            check(ordinary.status == "background_activity_launch_restricted")
            check("可能拦截" in ordinary.description)
            check(BootLaunchCapability(sdk, null).status == "background_activity_launch_capability_unknown")
            val managed = BootLaunchCapability(sdk, true)
            check(managed.status == "device_owner_exemption" && "设备所有者" in managed.description)
            check("取决于设备系统" in managed.description)
        }
        val receiver = source("BootReceiver.kt")
        check("isDeviceOwnerApp(context.packageName)" in receiver)
        check("BAL acceptance unobservable" in receiver && "EXTRA_BOOT_LAUNCH, true" in receiver)
        check("bootCapability.description" in source("ui/screens/settings/SettingsBackend.kt"))
        check("bootLaunch activity_created" in source("MainActivity.kt"))
    }

    private fun playbackCacheMaintenance() {
        val parent = Files.createTempDirectory("b9-playback-cache").toFile()
        val directory = File(parent, "playback_cache").also { check(it.mkdir()) }
        val lock = Any()
        val storage = PlaybackCacheStorage(directory, lock)
        try {
            val span = File(directory, "span.exo").also { it.writeBytes(ByteArray(513)) }
            val nested = File(directory, "nested").also { check(it.mkdir()) }
            File(nested, "index").writeBytes(ByteArray(27))
            check(storage.spaceBytes() == 540L) // Does not need an enabled cache instance.
            var deleted = false
            check(storage.clear(release = { throw IOException("release failed") }, delete = { deleted = true }).isFailure)
            check(!deleted && storage.spaceBytes() == 540L)
            check(storage.clear(release = {}, delete = {}).isFailure) // A silent/partial platform delete is a failure.
            check(storage.clear(release = {}, delete = { check(span.delete()) }).isFailure)
            check(storage.spaceBytes() == 27L)

            val deleting = CountDownLatch(1)
            val allowDelete = CountDownLatch(1)
            val tryingToOpen = CountDownLatch(1)
            val opened = CountDownLatch(1)
            val result = AtomicReference<Result<Unit>?>(null)
            val openFailure = AtomicReference<Throwable?>()
            val order = mutableListOf<String>()
            val clearer = thread {
                result.set(storage.clear(release = {
                    check(Thread.holdsLock(lock))
                    order += "release"
                }, delete = { target ->
                    check(Thread.holdsLock(lock) && order == listOf("release"))
                    deleting.countDown()
                    check(allowDelete.await(3, TimeUnit.SECONDS))
                    check(target.deleteRecursively())
                    order += "delete"
                }))
            }
            var opener: Thread? = null
            try {
                check(deleting.await(3, TimeUnit.SECONDS))
                opener = thread {
                    tryingToOpen.countDown()
                    try {
                        synchronized(lock) {
                            check(order == listOf("release", "delete"))
                            check(directory.mkdir())
                            File(directory, "new-span.exo").writeBytes(ByteArray(4))
                            order += "open"
                        }
                    } catch (error: Throwable) { openFailure.set(error) }
                    finally { opened.countDown() }
                }
                check(tryingToOpen.await(3, TimeUnit.SECONDS))
                check(!opened.await(100, TimeUnit.MILLISECONDS))
            } finally {
                allowDelete.countDown()
                clearer.join(3_000L)
                opener?.join(3_000L)
            }
            check(!clearer.isAlive && opener?.isAlive == false && openFailure.get() == null)
            check(result.get()?.isSuccess == true && order == listOf("release", "delete", "open"))
            check(storage.spaceBytes() == 4L) // Reopened data was not deleted by the earlier clear.
            check(storage.clear(release = {}, delete = { check(it.deleteRecursively()) }).isSuccess)
            check(storage.spaceBytes() == 0L)
        } finally { parent.deleteRecursively() }

        val settings = source("ui/screens/settings/SettingsPlayback.kt")
        check("PlaybackCache.clear(context).getOrThrow()" in settings && "缓存维护失败，请重试" in settings)
    }

    private fun source(path: String): String = File("src/main/java/com/lumina/tv", path).readText()
}
