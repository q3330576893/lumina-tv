package com.lumina.tv

import com.lumina.tv.diag.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Real production helpers on the JVM, including a killable child process and close-unblocked reads. */
object T11Checks {
    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("capture-child"))) {
            println("AudioTrack token=child-private"); System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        jsonBudgets()
        archiveBudgets()
        tailReads()
        captures()
        processDeadline()
        logBackpressure()
        sessionOrdering()
        crashInstallation()
        crashCompetition()
        wiring()
        println("V6-T11 PASS: close/kill deadlines, cancellation/late open, bounded batched ingress and drops, deep/ordered exit snapshots, escaped UTF-8 JSON budgets, synchronous crash install and newer-crash priority")
    }

    private fun jsonBudgets() {
        check(MIN_DIAGNOSTIC_JSON_BYTES == 2)
        val inputs = listOf(
            "", "{broken", "{} trailing", "{\"x\":", "[]", "null", "true", "123", "\"界😀\"",
            JSONObject().put("payload", "界😀\"\\\t\u0001".repeat(3000)).toString(),
            JSONArray().put("\\\"\u0000\n\t界😀".repeat(4000)).toString(),
            "{bad" + "界😀\"\\\u0002".repeat(1000),
        )
        for (input in inputs) for (budget in (-3..160) + listOf(256, 1024, 16 * 1024)) {
            val bounded = boundedDiagnosticJson(input, budget)
            val bytes = bounded.toByteArray(Charsets.UTF_8)
            check(bytes.size <= maxOf(2, budget)) { "budget=$budget size=${bytes.size}" }
            val tokener = JSONTokener(bytes.toString(Charsets.UTF_8))
            tokener.nextValue()
            check(tokener.nextClean() == '\u0000')
            check(!bounded.endsWith("\uD83D"))
        }
        val malformed = JSONObject(boundedDiagnosticJson("{broken", 128))
        check(malformed.getBoolean("invalidJson") && malformed.getString("reason") == "parse_failed")
        check("unterminated-private" !in boundedDiagnosticJson("{\"token\":\"unterminated-private", 1024))
        val large = JSONObject(boundedDiagnosticJson(inputs[9], 256))
        check(large.getBoolean("truncated") && large.getInt("maxBytes") == 256)
        check(large.has("originalBytes") && large.has("preview"))
        check(boundedDiagnosticJson("{}", 0) == "{}")
        println("T11 JSON PASS: actual encoded bytes including escapes/envelope; negative/zero/tiny budgets use the documented two-byte floor")
    }

    private fun archiveBudgets() = temporary("archive") { directory ->
        val archive = File(directory, "diagnostic.zip")
        com.lumina.tv.diag.DiagExporter.writeArchive(archive, linkedMapOf(
            "meta.json" to JSONObject().put("payload", "界😀\"\\\t\u0001".repeat(50_000)).put("token", "archive-private").toString(),
            "errors.json" to "{\"token\":\"unterminated-private" + "界😀".repeat(500),
        ))
        ZipFile(archive).use { zip ->
            for (name in listOf("meta.json", "errors.json")) {
                val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                check(bytes.size <= 120 * 1024)
                val text = bytes.toString(Charsets.UTF_8)
                JSONObject(text)
                check("archive-private" !in text && "unterminated-private" !in text)
            }
        }
        for (budget in listOf(0, 1, 2, 8, 32, 64, 128, 256)) {
            val root = File(directory, "stats-$budget")
            CrashStore(root, budget).recordExit("ANR", "界😀\"\\\u0001".repeat(500), 1L)
            val bytes = File(root, "crash-stats.json").readBytes()
            check(bytes.size <= maxOf(2, budget))
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        println("T11 archive PASS: final uncompressed JSON entry bytes include redaction/escaping/envelope; small crash-store budgets and malformed credential privacy")
    }

    private fun tailReads() = temporary("tail") { directory ->
        val file = File(directory, "large.log")
        RandomAccessFile(file, "rw").use {
            it.setLength(64L * 1024 * 1024)
            it.seek(it.length() - 256)
            it.write(("x".repeat(150) + "\n界😀 token=tail-private\nlast-tail-marker\n").toByteArray())
        }
        val tail = readDiagnosticTail(file, 256)
        check("last-tail-marker" in tail && "tail-private" !in tail && '\uFFFD' !in tail)
        check(tail.length < 256)
        file.writeText("界😀".repeat(100) + "\ncomplete-final-line\n")
        check(readDiagnosticTail(file, 31) == "complete-final-line\n")
        check(readDiagnosticTail(file, 0).isEmpty() && readDiagnosticTail(File(directory, "missing")).isEmpty())
        val rolling = File(directory, "rolling.log")
        repeat(80) { appendDiagnosticLog(rolling, "line=$it 界😀 token=rolling-private\n", 1024) }
        check(rolling.length() <= 1024 && "line=79" in rolling.readText() && "rolling-private" !in rolling.readText())
        println("T11 tail PASS: sparse large file, partial Unicode line, missing/empty files, redacted bounded rolling tail")
    }

    private class StuckInput : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun read(): Int {
            entered.countDown()
            while (closed.count > 0) try { closed.await() } catch (_: InterruptedException) { }
            exited.countDown()
            throw IOException("closed private-stream-secret")
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = read()
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    private fun captures() {
        class OwnedProcess(private val forceSupported: Boolean) : Process() {
            val inputCloses = AtomicInteger()
            val outputCloses = AtomicInteger()
            val errorCloses = AtomicInteger()
            val kills = AtomicInteger()
            private val input = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { inputCloses.incrementAndGet() } }
            private val output = object : ByteArrayOutputStream() { override fun close() { outputCloses.incrementAndGet() } }
            private val errors = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { errorCloses.incrementAndGet() } }
            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): java.io.OutputStream = output
            override fun getErrorStream(): InputStream = errors
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() { kills.incrementAndGet() }
            override fun destroyForcibly(): Process {
                if (!forceSupported) throw UnsupportedOperationException()
                kills.incrementAndGet(); return this
            }
        }
        for (forceSupported in listOf(true, false)) {
            val lateProcess = OwnedProcess(forceSupported)
            val closedResources = DiagnosticReadResources()
            closedResources.close()
            check(runCatching { closedResources.process(lateProcess) }.exceptionOrNull() is InterruptedException)
            check(lateProcess.kills.get() == 1 && lateProcess.inputCloses.get() == 1 &&
                lateProcess.outputCloses.get() == 1 && lateProcess.errorCloses.get() == 1)
            val registered = OwnedProcess(forceSupported)
            val resources = DiagnosticReadResources()
            resources.process(registered); resources.stream(registered.inputStream)
            resources.close(); resources.close()
            check(registered.kills.get() == 1 && registered.inputCloses.get() == 1 &&
                registered.outputCloses.get() == 1 && registered.errorCloses.get() == 1)
        }
        val stuck = StuckInput()
        val timed = ExitTraceReader.read(ExitTraceKind.ANR, 30, 100L) { stuck }
        check(timed.status == "timed_out" && stuck.closes.get() == 1)
        check(stuck.exited.await(2, TimeUnit.SECONDS)) // Interrupts alone deliberately do not unblock this fixture.

        val cancelled = StuckInput()
        val result = AtomicReference<DiagnosticReadResult>()
        val interrupted = AtomicReference(false)
        val caller = thread {
            result.set(DiagnosticCapture.read(1024, 5000L) { cancelled })
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        check(cancelled.entered.await(2, TimeUnit.SECONDS))
        caller.interrupt(); caller.join(2000L)
        check(!caller.isAlive && result.get().status == "cancelled" && interrupted.get())
        check(cancelled.exited.await(2, TimeUnit.SECONDS) && cancelled.closes.get() == 1)

        val allowOpen = CountDownLatch(1)
        val lateClosed = CountDownLatch(1)
        val late = DiagnosticCapture.read(16, 100L) {
            while (allowOpen.count > 0) try { allowOpen.await() } catch (_: InterruptedException) { }
            object : InputStream() {
                override fun read(): Int = error("Late cancelled stream was read")
                override fun close() { lateClosed.countDown() }
            }
        }
        check(late.status == "timed_out")
        allowOpen.countDown()
        check(lateClosed.await(2, TimeUnit.SECONDS))
        awaitNoReaders()
        val allowBusyOpen = CountDownLatch(1)
        val busyClosed = CountDownLatch(2)
        repeat(2) {
            check(DiagnosticCapture.read(16, 100L) {
                while (allowBusyOpen.count > 0) try { allowBusyOpen.await() } catch (_: InterruptedException) { }
                object : InputStream() {
                    override fun read(): Int = -1
                    override fun close() { busyClosed.countDown() }
                }
            }.status == "timed_out")
        }
        try {
            check(DiagnosticCapture.activeReaders == 2)
            check(DiagnosticCapture.read(16, 100L) { error("Queued despite worker ceiling") }.status == "reader_busy")
        } finally { allowBusyOpen.countDown() }
        check(busyClosed.await(2, TimeUnit.SECONDS))
        awaitNoReaders()
        val failure = DiagnosticCapture.read(16, 1000L) { throw IOException("private-open-secret") }
        check(failure.status == "read_failed:IOException" && failure.bytes.isEmpty())
        val zeroReads = AtomicInteger()
        val zero = DiagnosticCapture.read(3, 1000L) {
            object : InputStream() {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int = 0
                override fun read(): Int = if (zeroReads.incrementAndGet() <= 4) 'a'.code else -1
            }
        }
        check(zero.status == "available" && zero.truncated && zero.bytes.size == 3 && zeroReads.get() == 4)
        check(ExitTraceReader.read(ExitTraceKind.NATIVE, 30) { error("unsupported") }.status == "unsupported_api")
        awaitNoReaders()
        println("T11 stream PASS: stuck read really closed/exited, caller cancellation, late opens, opener exception and zero-read sentinel")
    }

    private fun processDeadline() {
        val child = AtomicReference<Process>()
        val result = DiagnosticCapture.read(64 * 1024, 1200L) { resources ->
            val javaExecutable = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/$javaExecutable").path,
                "-cp", System.getProperty("java.class.path"), T11Checks::class.java.name, "capture-child")
                .redirectErrorStream(true).start()
            child.set(process)
            resources.process(process).inputStream
        }
        check(result.status == "timed_out")
        val process = child.get() ?: error("No child started")
        check(process.waitFor(2, TimeUnit.SECONDS) && !process.isAlive)
        println("T11 process PASS: real child with stdout held open is killed at collection deadline")
    }

    private fun logBackpressure() {
        val scheduled = ArrayDeque<() -> Unit>()
        val batches = mutableListOf<String>()
        val buffer = DiagnosticLogBuffer({ scheduled.addLast(it); Unit }, { batches += it }, capacity = 2, batchSize = 2)
        check(buffer.add("first token=batch-private") && buffer.add("second 界😀"))
        repeat(1000) { check(!buffer.add("overflow-$it")) }
        check(scheduled.size == 1 && "dropped=1000" in buffer.snapshot())
        scheduled.removeFirst().invoke()
        check(batches.size == 1 && "batch-private" !in batches.single() && "dropped=1000" in batches.single())
        buffer.finish(); check(!buffer.add("after retirement"))
        check("dropped=1001" in buffer.snapshot())
        scheduled.removeFirst().invoke()
        check("dropped=1001" in batches.last())

        val producer = Thread.currentThread()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val async = DiagnosticLogBuffer({ worker.execute(it) }, {
            try {
                check(Thread.currentThread() !== producer)
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                flushed.countDown()
            } catch (error: Throwable) { failure.set(error) }
        }, capacity = 4, batchSize = 4)
        try {
            check(async.add("blocks disk")); check(entered.await(2, TimeUnit.SECONDS))
            repeat(4) { check(async.add("queued-$it")) }
            check(!async.add("dropped"))
            check("dropped=1" in async.snapshot())
        } finally { release.countDown(); async.finish() }
        check(flushed.await(2, TimeUnit.SECONDS) && failure.get() == null)
        worker.shutdown(); check(worker.awaitTermination(2, TimeUnit.SECONDS))

        val failed = DiagnosticLogBuffer({ scheduled.addLast(it); Unit }, { throw IOException("private-write-secret") })
        failed.add("one"); scheduled.removeFirst().invoke()
        check("ioFailures=1" in failed.snapshot() && "private-write-secret" !in failed.snapshot())
        failed.add("two"); scheduled.removeFirst().invoke()
        check("ioFailures=2" in failed.snapshot())
        val oversized = DiagnosticLogBuffer({ scheduled.addLast(it); Unit }, { batches += it })
        oversized.add("token=\"" + "oversized-private".repeat(1000))
        scheduled.removeFirst().invoke()
        check("dropped=1" in oversized.snapshot() && "oversized-private" !in batches.last())
        println("T11 logs PASS: producer outruns blocked sink, bounded ingress, one scheduled drain, batch flush, retirement, rejection and observable IO/drop counts")
    }

    private fun sessionOrdering() {
        val scheduled = ArrayDeque<() -> Unit>()
        val writes = mutableListOf<String>()
        val writer = LatestDiagnosticWrite<String>({ scheduled.addLast(it); Unit }, { writes += it })
        val nested = JSONObject().put("nested", JSONObject().put("value", "captured"))
        val frozen = nested.toString()
        writer.submit(1, frozen)
        nested.getJSONObject("nested").put("value", "mutated")
        scheduled.removeFirst().invoke()
        check(JSONObject(writes.single()).getJSONObject("nested").getString("value") == "captured")
        check(writer.submit(3, "new") && !writer.submit(2, "stale"))
        scheduled.removeFirst().invoke()
        check(writes.last() == "new" && !writer.submit(1, "old-completed-late"))
        check(writer.stale.get() == 2L)
        repeat(1000) { writer.submit(4, "coalesced-$it") }
        check(scheduled.size == 1)
        scheduled.removeFirst().invoke()
        check(writes.last() == "coalesced-999")
        val failing = LatestDiagnosticWrite<String>({ scheduled.addLast(it); Unit }, { throw IOException() })
        failing.submit(1, "first"); scheduled.removeFirst().invoke()
        failing.submit(2, "second"); scheduled.removeFirst().invoke()
        check(failing.failures.get() == 2L)
        val order = mutableListOf<String>()
        var captured = ""
        releaseWithDiagnosticSnapshot(snapshot = { nested.toString() }, release = {
            order += "release"
            nested.getJSONObject("nested").put("value", "after release")
        }, submit = {
            order += "submit"
            captured = it
        })
        check(order == listOf("release", "submit"))
        check(JSONObject(captured).getJSONObject("nested").getString("value") == "mutated")
        var released = false
        var submitted = false
        releaseWithDiagnosticSnapshot(snapshot = { throw IOException() }, release = { released = true }, submit = {
            submitted = true
            captured = it
        })
        check(released && submitted && JSONObject(captured).getBoolean("captureFailed"))
        val releaseFailure = IOException("release failure")
        submitted = false
        check(runCatching {
            releaseWithDiagnosticSnapshot({ "{}" }, { throw releaseFailure }, { submitted = true })
        }.exceptionOrNull() === releaseFailure && submitted)
        releaseWithDiagnosticSnapshot({ "{}" }, {}, { throw IOException("submission failure") })
        println("T11 exit PASS: deep snapshot immutability, queued supersession, old-session rejection and writer failure recovery")
    }

    private fun crashInstallation() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val pending = ArrayDeque<() -> Unit>()
            var forwarded = 0
            var written = 0
            var collected = 0
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> forwarded++ }
            val owner = ProcessCrashHandler { pending.addLast(it); Unit }
            owner.install({ _, _ -> written++ }, { collected++ })
            val installed = Thread.getDefaultUncaughtExceptionHandler()!!
            check(pending.size == 1 && collected == 0)
            installed.uncaughtException(Thread.currentThread(), IOException())
            check(written == 1 && forwarded == 1) // Catch early failure before background collection starts.
            owner.install({ _, _ -> error("duplicate") }, { collected++ })
            check(Thread.getDefaultUncaughtExceptionHandler() === installed && pending.size == 1)
            pending.removeFirst().invoke(); check(collected == 1)
            val failed = ProcessCrashHandler { it() }
            failed.install({ _, _ -> throw IOException() }, { throw IOException("private-collector-secret") })
            check(failed.previousExitFailure == "IOException")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IOException())
            check(forwarded == 2)
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
        println("T11 handler PASS: synchronous install before collection, exactly once and failures still forward")
    }

    private fun crashCompetition() = temporary("crash") { directory ->
        val store = CrashStore(directory)
        val token = store.previousExitToken()
        val readerReady = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldCommitted = AtomicReference<Boolean>()
        val old = thread {
            readerReady.countDown(); releaseOld.await()
            oldCommitted.set(store.commitPreviousExit(token, 10L) {
                File(directory, "exit-reason.json").writeText("old")
                store.recordExit("NATIVE", "old evidence", 10L)
            })
        }
        check(readerReady.await(2, TimeUnit.SECONDS))
        store.recordJava("new", "new-scene token=crash-private", 20L)
        releaseOld.countDown(); old.join(2000L)
        check(!old.isAlive && oldCommitted.get() == false && !File(directory, "exit-reason.json").exists())
        check(!store.readStats().has("lastCrash"))
        store.recordJava("older", "old scene", 15L)
        check(File(directory, "crash-latest.txt").readText() == "new-scene token=***")
        check(store.readStats().getJSONObject("older").getInt("count") == 1)
        val pendingToken = store.previousExitToken()
        store.noteJavaCrash()
        check(!store.commitPreviousExit(pendingToken, 21L) { error("Reservation ignored") })
        store.recordExit("ANR", "old late trace", 19L)
        check(!store.readStats().has("lastCrash"))
        store.recordExit("NATIVE", "newer native evidence", 30L)
        store.recordExit("ANR", "older exit delivered late", 25L)
        check(store.readStats().getJSONObject("lastCrash").getString("type") == "NATIVE")
        check(store.readStats().getJSONObject("lastCrash").getLong("at") == 30L)
        println("T11 crash PASS: historical reader loses to newly reserved/persisted Java crash; stale Java scene cannot replace newer report")
    }

    private fun wiring() {
        val player = source("ui/screens/PlayerScreen.kt")
        val diagnostics = source("ui/screens/PlayerDiagnostics.kt")
        check("check(Looper.myLooper() == Looper.getMainLooper())" in diagnostics)
        check("check(exo.applicationLooper == Looper.getMainLooper())" in diagnostics)
        check(".readText(" !in diagnostics && "readDiagnosticTail" in diagnostics)
        check("withContext(Dispatchers.Main.immediate) { buildDiagPayload(outcome) }" in player)
        val exit = player.substringAfter("releaseWithDiagnosticSnapshot(snapshot =")
            .substringBefore("DisposableEffect(item.id)")
        check(exit.indexOf("backend?.release(") < exit.indexOf("ExitSessionDiagnostics.enqueue("))
        check("last-session.json\").writeText" !in player)
        val audio = source("player/MpvAudioDiagnostics.kt")
        check("properties.toMap()" in audio && "LogcatCapture.read(" in audio)
        check("useLines" !in audio && "waitFor(" !in audio && "readText(" !in audio)
        val close = audio.substringAfter("fun close()").substringBefore("companion object")
        check(".close()" !in close && ".destroy" !in close && "shutdownNow()" in close)
        val controller = source("player/MpvPlayerController.kt")
        check("DiagnosticLogBuffer(" in controller && "diagnosticLogBuffer.finish()" in controller)
        check("sessionLogFile.readBytes()" !in controller && "sessionLogFile.readText(" !in controller)
        val crash = source("CrashHandler.kt")
        check("commitPreviousExit(" in crash && "noteJavaCrash()" in crash && "remainingMs" in crash)
        check("File(context.filesDir, trace.nativeFileName).writeBytes(trace.bytes)" in crash)
        check("native-trace-latest.pb" !in source("diag/DiagExporter.kt"))
        check("CrashHandler.crashFile(context).readText()" !in source("MainActivity.kt"))
        check("withContext(Dispatchers.IO) { CrashHandler.diagnosticReport(context) }" in source("ui/screens/settings/SettingsAbout.kt"))
    }

    private fun source(path: String): String = File(if (File("src/main").isDirectory) "src/main/java/com/lumina/tv" else "app/src/main/java/com/lumina/tv", path).readText()
    private fun awaitNoReaders() {
        repeat(200) {
            if (DiagnosticCapture.activeReaders == 0) return
            Thread.sleep(5)
        }
        check(DiagnosticCapture.activeReaders == 0) { "Diagnostic reader remained active after close" }
    }
    private inline fun temporary(name: String, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("t11-$name-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
