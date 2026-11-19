package com.lumina.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One monotonic deadline is inherited by retries, child requests and fallbacks. */
internal class RequestDeadline(val expiresAtNanos: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDeadline>
    fun remainingNanos(): Long = (expiresAtNanos - System.nanoTime()).coerceAtLeast(1L)
}

suspend fun <T> withRequestBudget(timeoutMs: Long = 20_000L, block: suspend CoroutineScope.() -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (context[RequestDeadline] != null) return kotlinx.coroutines.coroutineScope(block)
    return withTimeout(timeoutMs) {
        withContext(RequestDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)), block)
    }
}

internal suspend fun <T> withMetadataContext(timeoutMs: Long = 20_000L, block: suspend CoroutineScope.() -> T): T =
    withRequestBudget(timeoutMs) { withContext(Dispatchers.IO, block) }

/** Unlike runCatching, this never converts cancellation into a fallback value. */
inline fun <T> catchingRequest(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal suspend fun <T> catchingSuspendRequest(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

/** read must only consume the response, without writing UI/cache state. Not for media streams. */
internal suspend fun <T> OkHttpClient.readCancellable(request: Request, read: (Response) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val call = newCall(request)
    context[RequestDeadline]?.let { call.timeout().timeout(it.remainingNanos(), TimeUnit.NANOSECONDS) }
    return suspendCancellableCoroutine { continuation ->
        // Remains registered while onResponse reads the body, not just until headers arrive.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    continuation.context.ensureActive()
                    continuation.resume(value)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }
}
