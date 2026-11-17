package com.lumina.tv

object P4Checks {
    @JvmStatic fun main(args: Array<String>) {
        com.lumina.tv.player.P4TrackChecks.main(args)
        com.lumina.tv.data.P4NegotiationChecks.main(args)
        com.lumina.tv.data.P4PlayableItemChecks.main(args)
    }
}
