package com.lumina.tv.player

import org.json.JSONArray
import org.json.JSONObject

/** Do not infer a session or actual route from a device list or an unqualified '[zero]'. */
internal object AudioLogCorrelation {
    private val epochLine = Regex("^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+")
    private val session = Regex("(?i)session(?:[ _-]?id)?\\s*[:=]?\\s*(\\d+)")
    private val track = Regex("(?i)AudioTrack(?:Impl)?\\s*\\((0x[0-9a-f]+|\\d+)\\)")
    private val route = Regex("(?i)(routedDeviceId|selectedDeviceId|deviceId)\\s*[:=]\\s*(\\d+)")
    private val pcm = Regex("(?i)write|frames?\\s*[:=]|underrun|\\[zero]")

    fun observations(lines: List<String>, pid: Int, fromMs: Long, untilMs: Long): JSONArray {
        val sessionByTrack = mutableMapOf<String, String>()
        return JSONArray(lines.mapNotNull { line ->
            val header = epochLine.find(line) ?: return@mapNotNull null
            val at = (header.groupValues[1].toDouble() * 1000).toLong()
            if (header.groupValues[2].toInt() != pid || at !in fromMs..untilMs) return@mapNotNull null
            val trackId = track.find(line)?.groupValues?.get(1)
            val explicitSession = session.find(line)?.groupValues?.get(1)
            if (trackId != null && explicitSession != null) sessionByTrack[trackId] = explicitSession
            val sessionId = explicitSession ?: trackId?.let(sessionByTrack::get)
            val routeMatch = route.find(line)
            JSONObject().put("atMs", at).put("pid", pid).put("tid", header.groupValues[3].toInt())
                .put("audioTrackHandle", trackId ?: JSONObject.NULL)
                .put("audioTrackSession", sessionId ?: JSONObject.NULL)
                .put("sessionKey", sessionId?.let { "$pid:$it" } ?: JSONObject.NULL)
                .put("sessionAssociation", when {
                    explicitSession != null -> "explicit"
                    sessionId != null -> "same AudioTrack handle"
                    else -> "unknown; PID/time alone is insufficient"
                })
                .put("routeField", routeMatch?.groupValues?.get(1) ?: JSONObject.NULL)
                .put("routeDeviceId", routeMatch?.groupValues?.get(2) ?: JSONObject.NULL)
                .put("pcmLogEvidence", if (pcm.containsMatchIn(line)) line else JSONObject.NULL)
                .put("line", line)
        })
    }
}
