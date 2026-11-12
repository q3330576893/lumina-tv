package com.lumina.tv.data

import org.json.JSONObject

/** Call from a local test harness to run DV profile parsing regression assertions. */
fun runDvProfileParsingAssertions() {
    val client = EmbyClient(baseUrl = "http://localhost")
    fun parse(stream: JSONObject) = client.parseMediaSource(
        JSONObject().put("MediaStreams", org.json.JSONArray().put(stream.put("Type", "Video")))
    ).streams.single().dvProfile

    check(parse(JSONObject().put("VideoDoViProfile", 7)) == 7)
    check(parse(JSONObject().put("video_do_vi_profile", "7")) == 7)
    check(parse(JSONObject().put("doviProfile", 5)) == 5)
    check(parse(JSONObject().put("dvProfile", 7)) == 7)
    check(parse(JSONObject().put("Codec", "dvhe.07.06")) == 7)
    check(parse(JSONObject().put("CodecTag", "Dvh1.08.06")) == 8)
    check(parse(JSONObject().put("CodecTagString", "dvi.05")) == 5)
    check(parse(JSONObject().put("VideoRangeType", "PQ").put("ColorTransfer", "PQ")) == null)
    check(parse(JSONObject().put("VideoRangeType", "HLG").put("ColorTransfer", "HLG")) == null)
}
