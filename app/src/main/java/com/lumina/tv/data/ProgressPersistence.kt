package com.lumina.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** All queue instances share FIFO IO, including an old client's final write before a reopen. */
internal class SerialProgressIo(scope: CoroutineScope) {
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) {
        for (command in commands) command()
    }

    fun submit(command: () -> Unit) {
        check(commands.trySend(command).isSuccess) { "Progress persistence is closed" }
    }

    suspend fun closeAndAwait() {
        commands.close()
        worker.join()
    }
}

/** Process/application lifetime: never cancelled with a screen, Activity or HTTP sender. */
internal object ProgressPersistence {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val io = SerialProgressIo(scope)
}

internal interface ProgressStorage {
    fun read(): String?
    fun write(json: String)
}

internal class FileProgressStorage(private val file: File) : ProgressStorage {
    override fun read(): String? = file.takeIf { it.exists() }?.readText(Charsets.UTF_8)

    override fun write(json: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // Never truncate the last good destination if replacement fails.
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

class ProgressBarrier internal constructor(internal val owner: ProgressQueue, internal val revision: Long)
