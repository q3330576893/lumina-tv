package com.lumina.tv.player

import com.lumina.tv.diag.PageLog
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.HashMap

internal enum class PlayerHostLifecycleEvent { ON_START, ON_STOP }

internal data class PlayerHostLifecycleTransition(
    val resumedByBackground: Boolean,
    val pausePlayback: Boolean = false,
    val restoreControls: Boolean = false,
)

internal fun reducePlayerHostLifecycle(
    resumedByBackground: Boolean,
    event: PlayerHostLifecycleEvent,
    playbackRequested: Boolean,
): PlayerHostLifecycleTransition = when (event) {
    PlayerHostLifecycleEvent.ON_STOP -> if (playbackRequested) {
        PlayerHostLifecycleTransition(resumedByBackground = true, pausePlayback = true)
    } else {
        PlayerHostLifecycleTransition(resumedByBackground = resumedByBackground)
    }
    PlayerHostLifecycleEvent.ON_START -> if (resumedByBackground) {
        PlayerHostLifecycleTransition(resumedByBackground = false, restoreControls = true)
    } else {
        PlayerHostLifecycleTransition(resumedByBackground = false)
    }
}

internal fun detailPreviewShouldPlay(requested: Boolean, hostResumed: Boolean): Boolean = requested && hostResumed

private class IdentityWeakReference(
    referent: Any,
    queue: ReferenceQueue<Any>? = null,
) : WeakReference<Any>(referent, queue) {
    private val identityHash = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHash

    override fun equals(other: Any?): Boolean = this === other ||
        (other is IdentityWeakReference && get() != null && get() === other.get())
}

/** Media3 exposes decoder callbacks. libmpv only exposes controller lifetime through this API. */
internal class PlayerLifecycleRegistry(private val log: (String) -> Unit) {
    private data class Entry(val id: Long, val engine: String, val owner: String, val decoders: MutableList<String>)
    private val playerQueue = ReferenceQueue<Any>()
    private val players = HashMap<IdentityWeakReference, Entry>()
    private var nextId = 0L
    val liveVideoDecoders: Int @Synchronized get() {
        expungeStalePlayers()
        return players.values.sumOf { it.decoders.size }
    }

    @Synchronized fun created(player: Any, engine: String, owner: String): Long {
        expungeStalePlayers()
        players[IdentityWeakReference(player)]?.let { return it.id }
        val entry = Entry(++nextId, engine, owner, mutableListOf())
        players[IdentityWeakReference(player, playerQueue)] = entry
        log("playerLifecycle event=create engine=$engine id=${entry.id} owner=$owner players=${players.size}")
        return entry.id
    }

    @Synchronized fun decoderInitialized(player: Any, name: String) {
        expungeStalePlayers()
        val entry = players[IdentityWeakReference(player)] ?: return
        entry.decoders += name
        val count = liveVideoDecoders
        log("decoder: initialized count=$count engine=${entry.engine} id=${entry.id} owner=${entry.owner} name=$name")
        if (count > 1) log("decoder: overlap count=$count holders=${players.values.filter { it.decoders.isNotEmpty() }.joinToString { "${it.engine}:${it.id}:${it.owner}" }}")
    }

    @Synchronized fun decoderReleased(player: Any, name: String) {
        expungeStalePlayers()
        val entry = players[IdentityWeakReference(player)] ?: return
        if (entry.decoders.remove(name)) {
            log("decoder: released count=$liveVideoDecoders engine=${entry.engine} id=${entry.id} owner=${entry.owner} name=$name")
        }
    }

    @Synchronized fun released(player: Any, reason: String) {
        expungeStalePlayers()
        val entry = players.remove(IdentityWeakReference(player)) ?: return
        // release() may suppress pending analytics callbacks; it synchronously tears down those decoders.
        log("playerLifecycle event=release engine=${entry.engine} id=${entry.id} owner=${entry.owner} reason=$reason decoders=${entry.decoders.size} liveVideoDecoders=$liveVideoDecoders players=${players.size}")
    }

    private fun expungeStalePlayers() {
        while (true) {
            val stale = playerQueue.poll() as IdentityWeakReference? ?: return
            players.remove(stale)
        }
    }
}

internal object PlayerLifecycle {
    val registry = PlayerLifecycleRegistry { PageLog.add(it) }
}
