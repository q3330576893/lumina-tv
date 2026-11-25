package com.lumina.tv.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.PlayableItemRequestGate
import com.lumina.tv.data.PlayableItemResult
import com.lumina.tv.diag.PageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayableItemAction(val resolving: Boolean, val invalidate: () -> Unit, private val launch: (MediaItem, Boolean) -> Unit) {
    fun play(item: MediaItem, fromStart: Boolean = false) = launch(item, fromStart)
}

private data class ReplayRequest(val ticket: Long, val result: PlayableItemResult.ReplayAvailable, val fromStart: Boolean)

/** Shared click admission, account/item validation, and explicit empty/completed-series UI. */
@Composable
fun rememberPlayableItemAction(contextKey: Any, active: Boolean = true, onResolved: (MediaItem, Boolean) -> Unit): PlayableItemAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = AppRuntime.client
    val account = AppRuntime.session
    val userId = client?.userId
    val server = client?.baseUrl
    val requestContext = listOf(contextKey, client, account, userId, server, active)
    val gate = remember(requestContext) { PlayableItemRequestGate().apply { updateContext(requestContext) } }
    var resolving by remember(requestContext) { mutableStateOf(false) }
    var slow by remember(requestContext) { mutableStateOf(false) }
    var lookupJob by remember(gate) { mutableStateOf<Job?>(null) }
    var replay by remember(requestContext) { mutableStateOf<ReplayRequest?>(null) }
    val currentOnResolved by rememberUpdatedState(onResolved)
    DisposableEffect(gate) {
        onDispose {
            gate.updateContext(null)
            lookupJob?.cancel()
        }
    }

    fun cancelLookup() {
        gate.cancel()
        lookupJob?.cancel()
        lookupJob = null
        resolving = false
        slow = false
        replay = null
    }
    LaunchedEffect(active) { if (!active) cancelLookup() }
    BackHandler(enabled = active && resolving) { cancelLookup() }

    fun current(ticket: Long) = gate.accepts(ticket) && AppRuntime.client === client &&
        AppRuntime.session === account && client?.userId == userId && client?.baseUrl == server
    fun dismissReplay() {
        replay?.let { gate.finish(it.ticket) }
        replay = null
    }
    if (active && resolving && slow) {
        AlertDialog(
            onDismissRequest = ::cancelLookup,
            title = { Text("正在解析可播单集") },
            text = { Text("片源响应较慢，仍在尝试。正在读取最新集数与观看进度，并检查候选单集是否有可用片源。") },
            confirmButton = {
                TextButton(onClick = ::cancelLookup) { Text("取消查找") }
            },
        )
    }
    replay?.let { request ->
        AlertDialog(
            onDismissRequest = ::dismissReplay,
            title = { Text(if (request.result.allWatched) "本剧已全部看完" else "暂无可播的未看单集") },
            text = { Text("可以从首个可播单集重播：${request.result.firstEpisode.subtitle}") },
            confirmButton = { TextButton(onClick = {
                var keepTicket = false
                try {
                    if (current(request.ticket)) {
                        currentOnResolved(request.result.firstEpisode, request.fromStart)
                        keepTicket = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    PageLog.add("playableItem action=failed type=${failure.javaClass.simpleName}")
                    if (current(request.ticket)) Toast.makeText(context, "播放失败，请重试", Toast.LENGTH_LONG).show()
                } finally {
                    if (replay?.ticket == request.ticket) replay = null
                    if (!keepTicket) gate.finish(request.ticket)
                }
            }) { Text("播放首集") } },
            dismissButton = { TextButton(onClick = ::dismissReplay) { Text("取消") } },
        )
    }
    return PlayableItemAction(resolving || replay != null, invalidate = ::cancelLookup) { item, fromStart ->
        val ticket = if (active) gate.begin() else null
        if (ticket != null) {
            resolving = true
            slow = false
            lookupJob = scope.launch {
                var keepTicket = false
                try {
                    val result = resolveWithSlowNotice(onSlow = { if (current(ticket)) slow = true }) {
                        client?.resolvePlayableItem(item) ?: PlayableItemResult.Unavailable
                    }
                    if (current(ticket)) {
                        when (result) {
                            is PlayableItemResult.Ready -> {
                                currentOnResolved(result.item, fromStart)
                                keepTicket = true // Retain only after navigation returns successfully.
                            }
                            is PlayableItemResult.ReplayAvailable -> {
                                keepTicket = true
                                replay = ReplayRequest(ticket, result, fromStart)
                            }
                            PlayableItemResult.Unavailable -> Toast.makeText(context,
                                "暂无可播放的单集或片源", Toast.LENGTH_LONG).show()
                        }
                    } else PageLog.add("playableItem action=stale-result-dropped")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    PageLog.add("playableItem action=failed type=${failure.javaClass.simpleName}")
                    if (current(ticket)) Toast.makeText(context, "加载单集失败，请重试", Toast.LENGTH_LONG).show()
                } finally {
                    if (current(ticket)) {
                        resolving = false
                        slow = false
                    }
                    if (!keepTicket) gate.finish(ticket)
                }
            }
        }
    }
}
