package com.lumina.tv.player

import com.lumina.tv.diag.PageLog
import java.util.IdentityHashMap

/** Media3 exposes decoder callbacks. libmpv only exposes controller lifetime through this API. */
internal class PlayerLifecycleRegistry(private val log: (String) -> Unit) {
    private data class Entry(val id: Long, val engine: String, val owner: String, val decoders: MutableList<String>)
    private val players = IdentityHashMap<Any, Entry>()
    private var nextId = 0L
    val liveVideoDecoders: Int @Synchronized get() = players.values.sumOf { it.decoders.size }

    @Synchronized fun created(player: Any, engine: String, owner: String): Long {
        players[player]?.let { return it.id }
        val entry = Entry(++nextId, engine, owner, mutableListOf())
        players[player] = entry
        log("playerLifecycle event=create engine=$engine id=${entry.id} owner=$owner players=${players.size}")
        return entry.id
    }

    @Synchronized fun decoderInitialized(player: Any, name: String) {
        val entry = players[player] ?: return
        entry.decoders += name
        val count = liveVideoDecoders
        log("decoder: initialized count=$count engine=${entry.engine} id=${entry.id} owner=${entry.owner} name=$name")
        if (count > 1) log("decoder: overlap count=$count holders=${players.values.filter { it.decoders.isNotEmpty() }.joinToString { "${it.engine}:${it.id}:${it.owner}" }}")
    }

    @Synchronized fun decoderReleased(player: Any, name: String) {
        val entry = players[player] ?: return
        if (entry.decoders.remove(name)) {
            log("decoder: released count=$liveVideoDecoders engine=${entry.engine} id=${entry.id} owner=${entry.owner} name=$name")
        }
    }

    @Synchronized fun released(player: Any, reason: String) {
        val entry = players.remove(player) ?: return
        // release() may suppress pending analytics callbacks; it synchronously tears down those decoders.
        log("playerLifecycle event=release engine=${entry.engine} id=${entry.id} owner=${entry.owner} reason=$reason decoders=${entry.decoders.size} liveVideoDecoders=$liveVideoDecoders players=${players.size}")
    }
}

internal object PlayerLifecycle {
    val registry = PlayerLifecycleRegistry { PageLog.add(it) }
}
