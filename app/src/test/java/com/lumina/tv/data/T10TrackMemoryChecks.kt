package com.lumina.tv.data

import java.security.MessageDigest
import com.lumina.tv.player.*
import org.json.JSONObject

/** The old digest is intentionally reproduced in this negative fixture only. */
object T10TrackMemoryChecks {
    @JvmStatic fun main(args: Array<String>) {
        matchingLegacyNeverRestores()
        if ("--legacy-counterexample" in args) return
        canonicalAndEvidence()
        migrationsAndAuthority()
        invalidationAndNoResurrection()
        atomicFailureAndConcurrency()
        temporaryManualAndPrewarm()
        preparationHttp()
        allReadWriteWiring()
        println("V6-T10 PASS: strict v2 identities, irreversible lazy migration, atomic fail-closed tombstones and scoped manual selections")
    }

    private fun source() = MediaSource("source-a", "A", "mkv", true, true, listOf(
        MediaStream(1, "Audio", "aac", "eng", null, true, false, null),
        MediaStream(2, "Audio", "aac", "chi", null, false, false, null),
        MediaStream(3, "Subtitle", "subrip", "eng", null, true, false, null),
        MediaStream(4, "Subtitle", "ass", "chi", null, false, false, null)),
        defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = 3)

    private fun memory() = B2MemoryPrefs(mapOf("secrets_migrated_v1" to true,
        "base_url" to "https://t10.invalid/Emby", "user_id" to "u"))

    private fun scope(prefs: Prefs) = Prefs::class.java.getDeclaredMethod("ns").apply { isAccessible = true }.invoke(prefs) as String

    private fun oldDigest(source: MediaSource, type: String): String {
        val inventory = source.streams.filter { it.type.equals(type, true) }.sortedBy { it.index }
            .map { listOf(it.index, it.codec, it.profile, it.language, it.channels, it.title, it.isExternal).toString() }
        return MessageDigest.getInstance("SHA-256").digest((source.id + inventory).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun matchingLegacyNeverRestores() {
        val s = source()
        val failures = mutableListOf<String>()
        for (type in listOf("Audio", "Subtitle")) {
            val memory = memory()
            val prefs = Prefs(memory.sp)
            val ns = scope(prefs)
            val prefix = type.lowercase()
            val matching = oldDigest(s, type)
            memory.values["${prefix}_identity_${ns}_item"] = matching
            memory.values["${prefix}_track_${ns}_item"] = if (type == "Audio") 2 else "track:emby:4"
            check(matching == oldDigest(s.copy(streams = s.streams.reversed()), type))
            val restored = if (type == "Audio") prefs.audioTrackFor("item", s) else prefs.subtitlePreferenceFor("item", s)
            if (restored != null) failures += "$type restored=$restored despite unverifiable v1 identity"
        }
        check(failures.isEmpty()) { "T10_LEGACY_MATCH_MUST_NOT_RESTORE: ${failures.joinToString()}" }
        println("T10 legacy counterexample PASS: exactly matching v1 digests never restore audio/subtitle indices")
    }
    // Positive evidence below is supplied by the fixture, not by the current production parser.
    private fun fixtureIdentity(source: MediaSource?, type: TrackMemoryType) = attestedTrackMemoryIdentity(source, type,
        source?.contentValidator?.takeIf { it.isNotBlank() }?.let { TrackVersionEvidence("ETag", it) })
    private fun attestedSource() = source().copy(contentValidator = "fixture-etag-a", contentVersion = "version-a")
    private fun prefs(memory: B2MemoryPrefs) = Prefs(memory.sp, trackIdentity = ::fixtureIdentity)
    private fun payload(prefs: Prefs, type: TrackMemoryType, item: String = "item") =
        "track_memory_v2_${scope(prefs)}_${item}_${type.name.lowercase()}"
    private fun seen(prefs: Prefs, type: TrackMemoryType, item: String = "item") =
        "track_memory_v2_seen_${scope(prefs)}_${item}_${type.name.lowercase()}"
    private fun json(memory: B2MemoryPrefs, prefs: Prefs, type: TrackMemoryType, item: String = "item") =
        JSONObject(memory.values[payload(prefs, type, item)] as String)
    private fun tombstone(memory: B2MemoryPrefs, prefs: Prefs, type: TrackMemoryType, reason: TrackInvalidReason) {
        val stored = json(memory, prefs, type)
        check(stored.getString("mode") == "INHERIT" && stored.getString("invalidReason") == reason.name) { stored.toString() }
        check(stored.isNull("index") && stored.isNull("identity") && memory.values[seen(prefs, type)] == true)
    }

    private fun canonicalAndEvidence() {
        val s = attestedSource()
        val a = TrackMemoryType.Audio
        val subs = TrackMemoryType.Subtitle
        fun identity(source: MediaSource = s, type: TrackMemoryType = a) = fixtureIdentity(source, type)
        check(identity() is TrackMemoryIdentity.Available)
        check(sameTrackMemoryIdentity(identity(), identity(s.copy(streams = s.streams.reversed()))))
        check(!sameTrackMemoryIdentity(identity(), identity(s.copy(id = "another"))))
        check(!sameTrackMemoryIdentity(identity(), identity(s.copy(contentVersion = "b"))))
        check(!sameTrackMemoryIdentity(identity(), identity(s.copy(contentValidator = "etag-b"))))
        check(identity(s.copy(id = "")) is TrackMemoryIdentity.Unavailable)
        check(identity(s.copy(streams = emptyList())) is TrackMemoryIdentity.Unavailable)
        check(identity(s.copy(streams = s.streams + s.streams.first())) is TrackMemoryIdentity.Unavailable)
        check(identity(s.copy(streams = listOf(s.streams.first().copy(index = -1)))) is TrackMemoryIdentity.Unavailable)
        check(trackMemoryIdentity(s, "unknown") == TrackMemoryIdentity.Unavailable(TrackInvalidReason.TYPE_CHANGED))
        check(trackMemoryIdentity(null, "Audio") == TrackMemoryIdentity.Unavailable(TrackInvalidReason.NO_CONTEXT))
        for (candidate in listOf(s, s.copy(contentValidator = null), s.copy(contentValidator = ""),
            s.copy(contentVersion = "preview-hash", contentValidator = null), s.copy(size = 1234, contentValidator = null),
            s.copy(contentValidator = "a".repeat(64)))) {
            check(trackMemoryIdentity(candidate, "Audio") == TrackMemoryIdentity.Unavailable(TrackInvalidReason.UNATTESTED_VERSION))
        }
        check(!sameTrackMemoryIdentity(null, null))
        val unavailable = trackMemoryIdentity(s, "Audio")
        check(!sameTrackMemoryIdentity(unavailable, unavailable))
        check(attestedTrackMemoryIdentity(s, a, TrackVersionEvidence("opaque", "hash")) is TrackMemoryIdentity.Unavailable)
        check(attestedTrackMemoryIdentity(s, a, TrackVersionEvidence("ETag", "", "SOURCE_BYTES")) is TrackMemoryIdentity.Unavailable)
        check(attestedTrackMemoryIdentity(s, a, TrackVersionEvidence("ETag", "x", "METADATA_ONLY")) is TrackMemoryIdentity.Unavailable)
        val stream = s.streams.first()
        val values = listOf<String?>(null, "", "null", "a,b", "[a, b]", "a\u0000b", "a\nb", "中文", "🎬", "é", "e\u0301")
        check(values.map { trackInventoryDigest(a, listOf(stream.copy(title = it))) }.distinct().size == values.size)
        check(trackInventoryDigest(a, listOf(stream.copy(title = "a, b", language = "c"))) !=
            trackInventoryDigest(a, listOf(stream.copy(title = "a", language = "b, c"))))
        check(trackInventoryDigest(a, listOf(stream)) != trackInventoryDigest(subs, listOf(stream.copy(type = "Subtitle"))))
        val changed = listOf(stream.copy(codec = "ac3"), stream.copy(language = "chi"), stream.copy(title = "x"),
            stream.copy(isDefault = false), stream.copy(isExternal = true), stream.copy(displayTitle = "new"),
            stream.copy(profile = "p"), stream.copy(level = 1), stream.copy(width = 3), stream.copy(height = 4),
            stream.copy(bitRate = 99), stream.copy(channels = 8), stream.copy(sampleRate = 96_000),
            stream.copy(videoRangeType = "HDR"), stream.copy(dvProfile = 7), stream.copy(bitDepth = 10),
            stream.copy(colorSpace = "bt2020"), stream.copy(colorTransfer = "pq"), stream.copy(colorPrimaries = "bt2020"),
            stream.copy(frameRate = 23.976), stream.copy(index = 9))
        check(changed.all { trackInventoryDigest(a, listOf(it)) != trackInventoryDigest(a, listOf(stream)) })
        println("T10 identity PASS: typed canonical scalars/null/Unicode, all actual fields, type, ordering and strict evidence admission")
    }

    private fun migrationsAndAuthority() {
        val s = source()
        val rawValues: List<Any?> = listOf(null, "", "null", "garbage", false, 3, "off", "-1", "auto", "track:emby:4", "4", "track:0:2")
        for (value in rawValues) {
            val memory = memory()
            val prefs = Prefs(memory.sp)
            val ns = scope(prefs)
            @Suppress("UNCHECKED_CAST")
            (memory.values as MutableMap<String, Any?>)["subtitle_track_${ns}_item"] = value
            memory.values["subtitle_identity_${ns}_item"] = oldDigest(s, "Subtitle")
            val expected = when (value) { "off", "-1" -> "off"; "auto" -> "auto"; else -> null }
            check(prefs.subtitlePreferenceFor("item", s) == expected) { "legacy=$value" }
            check(Prefs(memory.sp).subtitlePreferenceFor("item", s) == expected)
            check(memory.values[seen(prefs, TrackMemoryType.Subtitle)] == true)
            if (value is String && (value.startsWith("track:") || value == "4")) {
                val stored = json(memory, prefs, TrackMemoryType.Subtitle)
                check(stored.getString("invalidReason") == "SCHEMA_LEGACY" && stored.getString("legacyReason") == "LEGACY_UNVERIFIABLE")
            }
        }
        for (oldIdentity in listOf(null, "mismatch", oldDigest(s, "Audio"))) {
            val memory = memory(); val prefs = Prefs(memory.sp); val ns = scope(prefs)
            memory.values["audio_track_${ns}_item"] = 2
            if (oldIdentity != null) memory.values["audio_identity_${ns}_item"] = oldIdentity
            check(prefs.audioTrackFor("item", s) == null)
            tombstone(memory, prefs, TrackMemoryType.Audio, TrackInvalidReason.SCHEMA_LEGACY)
            check(json(memory, prefs, TrackMemoryType.Audio).getString("legacyReason") == "LEGACY_UNVERIFIABLE")
        }
        for (variant in listOf("seen-false", "seen-wrong", "payload-int", "bad-json", "future", "legacy-schema", "wrong-type", "missing-payload")) {
            val memory = memory(); val prefs = Prefs(memory.sp); val ns = scope(prefs)
            memory.values["subtitle_track_${ns}_item"] = "off"
            val key = payload(prefs, TrackMemoryType.Subtitle)
            when (variant) {
                "seen-false" -> memory.values[seen(prefs, TrackMemoryType.Subtitle)] = false
                "seen-wrong" -> memory.values[seen(prefs, TrackMemoryType.Subtitle)] = "wrong"
                "payload-int" -> memory.values[key] = 42
                "bad-json" -> memory.values[key] = "{bad"
                "future" -> memory.values[key] = """{"schemaVersion":99,"type":"Subtitle","mode":"OFF","future":"keep"}"""
                "legacy-schema" -> memory.values[key] = """{"schemaVersion":1}"""
                "wrong-type" -> memory.values[key] = """{"schemaVersion":2,"type":"Audio"}"""
                else -> memory.values[seen(prefs, TrackMemoryType.Subtitle)] = true
            }
            val before = memory.values[key]
            check(prefs.subtitlePreferenceFor("item", s) == null) { variant }
            check(prefs.subtitleTrackFor("item", s) == null && prefs.subtitleModeFor("item", s) == Prefs.SubtitleMode.AUTO)
            check(Prefs(memory.sp).subtitlePreferenceFor("item", s) == null)
            if (variant == "future") check(memory.values[key] == before)
        }
        val memory = memory(); val prefs = Prefs(memory.sp); val ns = scope(prefs)
        memory.values["preferred_audio_language"] = " zh-Hant "
        memory.values["preferred_subtitle_language"] = "off"
        val legacy = Prefs::class.java.getDeclaredMethod("legacyNs").apply { isAccessible = true }.invoke(prefs) as String
        memory.values["last_source_${legacy}_item"] = " source原值 "
        memory.values["audio_track_${legacy}_item"] = 2
        check(prefs.audioTrackFor("item", s) == null)
        check(memory.values["audio_track_${ns}_item"] == 2 && memory.values["item_ns_migrated_v2_audio_track_${ns}_item"] == true)
        check(prefs.preferredAudioLanguage == " zh-Hant " && prefs.preferredSubtitleLanguage == "off")
        check(prefs.lastSourceFor("item") == " source原值 ")
        prefs.rememberSource("item", null)
        check(Prefs(memory.sp).lastSourceFor("item") == null && memory.values["last_source_${legacy}_item"] == " source原值 ")
        println("T10 migration PASS: v1 matching/missing/mismatched summaries downgrade; null/blank/malformed; OFF/-1/AUTO; v2/seen authority; future retention; languages/source/namespace")
    }

    private fun invalidationAndNoResurrection() {
        val s = attestedSource()
        val a = TrackMemoryType.Audio
        val mutations = listOf(
            s.copy(id = "other") to TrackInvalidReason.SOURCE_CHANGED,
            s.copy(contentValidator = "etag-b") to TrackInvalidReason.VERSION_CHANGED,
            s.copy(contentVersion = "version-b") to TrackInvalidReason.VERSION_CHANGED,
            s.copy(contentValidator = null) to TrackInvalidReason.UNATTESTED_VERSION,
            s.copy(streams = s.streams.map { if (it.index == 2) it.copy(codec = "ac3") else it }) to TrackInvalidReason.INVENTORY_CHANGED,
            s.copy(streams = s.streams.map { if (it.type == "Audio") it.copy(index = 3 - it.index) else it }) to TrackInvalidReason.INVENTORY_CHANGED,
            s.copy(streams = s.streams + s.streams.first().copy(index = 8)) to TrackInvalidReason.INVENTORY_CHANGED,
            s.copy(streams = s.streams.filter { it.index != 2 }) to TrackInvalidReason.INDEX_CHANGED,
            s.copy(streams = emptyList()) to TrackInvalidReason.INVENTORY_CHANGED,
            s.copy(streams = s.streams + s.streams.first()) to TrackInvalidReason.INVENTORY_CHANGED,
            s.copy(streams = s.streams.map { if (it.index == 2) it.copy(type = "Subtitle") else it }) to TrackInvalidReason.INDEX_CHANGED,
        )
        for ((changed, reason) in mutations) {
            val memory = memory(); val prefs = prefs(memory); val ns = scope(prefs)
            memory.values["audio_track_${ns}_item"] = 2
            memory.values["audio_identity_${ns}_item"] = oldDigest(s, "Audio")
            check(prefs.rememberAudioTrack("item", 2, s))
            check(prefs.audioTrackFor("item", s.copy(streams = s.streams.reversed())) == 2)
            check(prefs.audioTrackFor("item", null) == null)
            check(json(memory, prefs, a).getString("mode") == "TRACK")
            check(prefs.audioTrackFor("item", s) == 2)
            check(prefs.audioTrackFor("item", changed) == null) { reason.name }
            tombstone(memory, prefs, a, reason)
            check(prefs.audioTrackFor("item", s) == null && prefs(memory).audioTrackFor("item", s) == null)
            memory.values.remove(payload(prefs, a)) // seen alone still forbids fallback.
            check(prefs(memory).audioTrackFor("item", s) == null)
            check(prefs.rememberAudioTrack("item", 2, s)) // Only a new explicit selection can establish a new record.
            check(prefs(memory).audioTrackFor("item", s) == 2)
        }
        val memory = memory(); val prefs = prefs(memory)
        check(prefs.rememberAudioTrack("item", 2, s))
        check(Prefs(memory.sp).audioTrackFor("item", s) == null) // Actual producer still has no attestation.
        tombstone(memory, prefs, a, TrackInvalidReason.UNATTESTED_VERSION)
        check(prefs.audioTrackFor("item", s) == null)
        check(prefs.rememberAudioTrack("item", 2, s))
        check(prefs.rememberAudioTrack("item", 1, s.copy(contentValidator = null)))
        tombstone(memory, prefs, a, TrackInvalidReason.UNATTESTED_VERSION)
        check(prefs.audioTrackFor("item", s) == null)
        val sub = TrackMemoryType.Subtitle
        check(prefs.rememberSubtitleTrack("item", "track:emby:4", s))
        check(prefs.subtitlePreferenceFor("item", s) == "track:emby:4" && prefs.subtitleTrackFor("item", s) == "emby:4")
        check(prefs.subtitleModeFor("item", s) == Prefs.SubtitleMode.TRACK)
        check(prefs.subtitlePreferenceFor("item", s.copy(contentValidator = "b")) == null)
        tombstone(memory, prefs, sub, TrackInvalidReason.VERSION_CHANGED)
        check(prefs(memory).subtitlePreferenceFor("item", s) == null)
        for (mode in listOf("off", "auto")) {
            check(prefs.rememberSubtitleTrack("item", mode, s))
            check(prefs.subtitlePreferenceFor("item", null) == mode)
            check(prefs.subtitlePreferenceFor("item", s.copy(id = "other", streams = emptyList())) == mode)
            check(json(memory, prefs, sub).isNull("identity") && json(memory, prefs, sub).isNull("index"))
        }
        for (bad in listOf<String?>(null, "", "track:bad", "track:emby:-2", "track:emby:999")) {
            prefs.rememberSubtitleTrack("item", "track:emby:4", s)
            check(prefs.rememberSubtitleTrack("item", bad, s))
            check(prefs.subtitlePreferenceFor("item", s) == null)
        }
        val external = s.copy(streams = s.streams.map { if (it.index == 4) it.copy(isExternal = true) else it })
        check(prefs.rememberSubtitleTrack("item", "track:emby:4", external))
        tombstone(memory, prefs, sub, TrackInvalidReason.EXTERNAL_UNATTESTED)
        check(memory.values.keys.none { it.startsWith("subtitle_identity_") || it.startsWith("subtitle_track_") })
        prefs.rememberAudioTrack("item", 2, s)
        memory.values[payload(prefs, sub)] = memory.values[payload(prefs, a)]!!
        check(prefs.subtitlePreferenceFor("item", s) == null)
        tombstone(memory, prefs, sub, TrackInvalidReason.TYPE_CHANGED)
        println("T10 invalidation PASS: source/type/version/missing evidence/reindex/inventory/index; persistent tombstones never revive; context-free reads; explicit replacements; external tracks")
    }

    private fun atomicFailureAndConcurrency() {
        val s = attestedSource(); val a = TrackMemoryType.Audio
        for (publish in listOf(false, true)) {
            val memory = memory(); val prefs = prefs(memory); val ns = scope(prefs)
            memory.values["audio_track_${ns}_item"] = 2
            memory.values["audio_identity_${ns}_item"] = oldDigest(s, "Audio")
            memory.failCommit = true; memory.publishFailedCommit = publish
            val migrated = prefs.trackMemoryFor("item", a, s)
            check(!migrated.persisted && migrated.record.mode == TrackMemoryMode.INHERIT)
            check(prefs.trackMemoryStorageError != null && prefs.audioTrackFor("item", s) == null)
            if (publish) check(memory.values.containsKey(payload(prefs, a)) && memory.values[seen(prefs, a)] == true)
            else check(!memory.values.containsKey(payload(prefs, a)) && !memory.values.containsKey(seen(prefs, a)))
            check(Prefs(B2MemoryPrefs(memory.values.toMap()).sp).audioTrackFor("item", s) == null)
            memory.failCommit = false
            check(prefs.audioTrackFor("item", s) == null)
            check(prefs.trackMemoryStorageError == null)
            check(prefs.rememberAudioTrack("item", 2, s))
            memory.failCommit = true; memory.publishFailedCommit = false
            check(prefs.audioTrackFor("item", s.copy(contentValidator = "b")) == null)
            check(prefs(memory).audioTrackFor("item", s) == null) // Pending failure is shared across Prefs instances.
            memory.failCommit = false
            check(prefs(memory).audioTrackFor("item", s) == null)
            tombstone(memory, prefs, a, TrackInvalidReason.VERSION_CHANGED)
        }
        val interrupted = memory(); val p = prefs(interrupted)
        interrupted.failAtOperation = interrupted.operations + 2
        check(!p.rememberAudioTrack("item", 2, s))
        check(!interrupted.values.containsKey(payload(p, a)) && !interrupted.values.containsKey(seen(p, a)))
        interrupted.failAtOperation = null
        check(p.audioTrackFor("item", s) == null)
        val memory = memory(); val first = prefs(memory); val second = prefs(memory)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        memory.boundary = {
            if (once.compareAndSet(false, true)) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val migration = kotlin.concurrent.thread { runCatching { first.audioTrackFor("item", s) }.onFailure(failures::add) }
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val manual = kotlin.concurrent.thread { runCatching { check(second.rememberAudioTrack("item", 2, s)) }.onFailure(failures::add) }
        release.countDown(); migration.join(5_000); manual.join(5_000)
        memory.boundary = null
        check(!migration.isAlive && !manual.isAlive && failures.isEmpty()) { failures.toString() }
        check(first.audioTrackFor("item", s) == 2 && prefs(memory).audioTrackFor("item", s) == 2)
        println("T10 atomicity PASS: single-editor payload+seen, commit false/partial interruption, shared fail-closed state and concurrent migration/manual serialization")
    }

    private fun temporaryManualAndPrewarm() {
        val s = attestedSource(); val a = TrackMemoryType.Audio; val sub = TrackMemoryType.Subtitle
        val identity = fixtureIdentity(s, a)
        fun selection(type: TrackMemoryType = a, index: Int = 2, proof: TrackMemoryIdentity = fixtureIdentity(s, type)) =
            ManualTrackSelection("item", s.id, type, index, "instance", 7, proof, s)
        val pending = selection()
        check(pending.matchesCurrent("item", s, a, "instance", 7, identity))
        check(!pending.matchesCurrent("other", s, a, "instance", 7, identity))
        check(!pending.matchesCurrent("item", s, sub, "instance", 7, identity))
        check(!pending.matchesCurrent("item", s, a, "old-instance", 7, identity))
        check(!pending.matchesCurrent("item", s, a, "instance", 8, identity))
        check(pending.indexForProbe("item", s.copy(), a, fixtureIdentity(s.copy(), a)) == 2)
        check(pending.indexForProbe("item", s.copy(id = "b"), a, fixtureIdentity(s.copy(id = "b"), a)) == null)
        check(pending.indexForProbe("item", s.copy(contentValidator = "b"), a, fixtureIdentity(s.copy(contentValidator = "b"), a)) == null)
        check(pending.forLoad("item", s, "instance", 7, 8, identity)?.loadGeneration == 8)
        check(pending.forLoad("item", s, "instance", 6, 8, identity) == null)
        val unavailable = trackMemoryIdentity(s, "Audio")
        val immediate = selection(proof = unavailable)
        check(immediate.matchesCurrent("item", s, a, "instance", 7, trackMemoryIdentity(s, "Audio")))
        check(!immediate.matchesCurrent("item", s.copy(), a, "instance", 7, trackMemoryIdentity(s, "Audio")))
        check(!immediate.matchesCurrent("item", s, a, "instance", 8, unavailable))
        check(immediate.indexForProbe("item", s, a, unavailable) == null && immediate.forLoad("item", s, "instance", 7, 8, unavailable) == null)
        val subtitle = selection(sub, 4)
        check(subtitle.indexForProbe("item", s, sub, fixtureIdentity(s, sub)) == 4)
        check(subtitle.indexForProbe("item", s, a, identity) == null)
        val external = s.copy(streams = s.streams.map { if (it.index == 4) it.copy(isExternal = true) else it })
        val ext = ManualTrackSelection("item", external.id, sub, 4, "instance", 7, fixtureIdentity(external, sub), external)
        check(ext.indexForProbe("item", external, sub, fixtureIdentity(external, sub)) == null)
        check(!selection(index = 99).matchesCurrent("item", s, a, "instance", 7, identity))
        val one = resolvePlaybackTrackIntent(s, null, null, "zh", "zh")
        val two = resolvePlaybackTrackIntent(s, null, null, "zh", "zh")
        check(one.audioIndex == 2 && one.subtitleIndex == 4 && one.preparationNonce != null && one.preparationNonce != two.preparationNonce)
        val key = PlaybackRequestKey("item", false, s.id, 2, false, 120_000_000, SourceStrategy(), PlaybackStartIntent.Resume, 0, false, false, false)
        check(TrackAwarePlaybackKey(key, one) != TrackAwarePlaybackKey(key, two))
        val trusted = one.copy(audioIdentity = identity, subtitleIdentity = fixtureIdentity(s, sub), preparationNonce = null)
        check(TrackAwarePlaybackKey(key, trusted) == TrackAwarePlaybackKey(key, trusted.copy()))
        val duplicates = s.copy(streams = s.streams + s.streams.first().copy(index = 2))
        check(resolvePlaybackTrackIntent(duplicates, null, null, null, null, explicitAudio = 2).audioIndex != 2)
        check(resolvePlaybackTrackIntent(s, null, "off", null, null, explicitSubtitle = 4).subtitleIndex == 4)
        for (response in listOf(s.copy(contentValidator = null), s.copy(contentValidator = "b"), s.copy(contentVersion = "b"), s.copy(id = "other"))) {
            val merged = trackSourceAfterPlaybackInfo(s, response)
            check(merged.contentValidator == null && merged.contentVersion == response.contentVersion && merged.streams == s.streams)
            check(trackMemoryIdentity(merged, "Audio") is TrackMemoryIdentity.Unavailable)
        }
        println("T10 manual/cache PASS: item/source/type/player/load fences, immediate unverified mapping, no unverified cross-probe/index reuse, subtitle parity, per-preparation nonce, response evidence isolation")
    }

    private fun allReadWriteWiring() {
        val root = if (java.io.File("src/main").isDirectory) java.io.File(".") else java.io.File("app")
        fun source(path: String) = root.resolve("src/main/java/com/lumina/tv/$path").readText()
        val prefs = source("data/Prefs.kt")
        val trackReaders = prefs.substringAfter("internal fun trackMemoryFor").substringBefore("fun lastSourceFor")
        check("audio_identity" !in trackReaders && "subtitle_identity" !in trackReaders && "itemKey(" !in trackReaders)
        check("sp.contains(keys.payload)" in trackReaders && "sp.contains(keys.seen)" in trackReaders)
        check("edit.commit()" in prefs && "putBoolean(keys.seen, true)" in prefs)
        val player = source("ui/screens/PlayerScreen.kt")
        check("Pair<String, Int>" !in player && "pendingManualAudio?.first" !in player)
        listOf("pendingManualSubtitle", "manualTrackInstance", "matchesCurrentManual", "sameTrackMemoryIdentity",
            "audioForPreparation, subtitleForPreparation", "rememberConfirmedSubtitle(owner.currentSubtitleEmbyIndex())",
            "rememberConfirmedSubtitle(selectedSubtitleIndex)", "pendingManualSubtitle = null", "请重新选择音轨/字幕").forEach { check(it in player) { it } }
        val emby = source("data/EmbyClient.kt")
        val prepare = emby.substringAfter("suspend fun preparePlaybackTracks").substringBefore("suspend fun resolvePlayback")
        check("manualAudio?.indexForProbe" in prepare && "manualSubtitle?.indexForProbe" in prepare && "audioIdentity ==" !in prepare)
        check("trackSourceAfterPlaybackInfo(negotiationSource, responseSource)" in emby)
        val detail = source("ui/screens/DetailScreen.kt")
        listOf("audioTrackFor(id, trackSource)", "subtitlePreferenceFor(id, trackSource)", "rememberAudioTrack(currentItem.id, it, activeSource)",
            "rememberSubtitleTrack(currentItem.id").forEach { check(it in detail) { it } }
        check("preferredAudioLanguage" in source("ui/screens/settings/SettingsPlayback.kt"))
        check("preferredSubtitleLanguage" in source("ui/screens/settings/SettingsSubtitles.kt"))
        println("T10 wiring PASS: all Prefs wrappers, Detail/global settings, both player engines, prepare probes, reloads, source switch, actual confirmations and response merge")
    }

    private fun preparationHttp() = kotlinx.coroutines.runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val versioned = java.util.concurrent.atomic.AtomicBoolean(true)
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                check(session.uri.endsWith("/Items/item/PlaybackInfo"))
                val body = HashMap<String, String>()
                session.parseBody(body)
                requests += JSONObject(body.getValue("postData"))
                val streams = org.json.JSONArray()
                streams.put(JSONObject().put("Index", 0).put("Type", "Video").put("Codec", "h264"))
                source().streams.forEach { stream -> streams.put(JSONObject().put("Index", stream.index).put("Type", stream.type)
                    .put("Codec", stream.codec).put("Language", stream.language).put("IsDefault", stream.isDefault).put("IsExternal", stream.isExternal)) }
                val wire = JSONObject().put("Id", "source-a").put("Name", "A").put("Container", "mkv")
                    .put("SupportsDirectPlay", true).put("SupportsDirectStream", true).put("Size", 1234)
                    .put("DefaultAudioStreamIndex", 1).put("DefaultSubtitleStreamIndex", 3).put("MediaStreams", streams)
                if (versioned.get()) wire.put("ETag", "wire-etag")
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                    JSONObject().put("MediaSources", org.json.JSONArray().put(wire)).toString())
            }
        }.apply { start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        val memory = memory()
        memory.values["base_url"] = "http://127.0.0.1:${server.listeningPort}/Emby"
        val prefs = Prefs(memory.sp)
        prefs.preferredAudioLanguage = "zh"; prefs.preferredSubtitleLanguage = "zh"
        val previous = com.lumina.tv.AppRuntime.prefs
        com.lumina.tv.AppRuntime.prefs = prefs
        val client = EmbyClient(memory.values["base_url"] as String, userId = "u")
        try {
            val probed = client.getMediaSourcesResult("item").getOrThrow().single()
            check(!probed.contentValidator.isNullOrBlank())
            check(trackMemoryIdentity(probed, "Audio") == TrackMemoryIdentity.Unavailable(TrackInvalidReason.UNATTESTED_VERSION))
            val ns = scope(prefs)
            memory.values["audio_track_${ns}_item"] = 1
            memory.values["audio_identity_${ns}_item"] = oldDigest(probed, "Audio")
            memory.values["subtitle_track_${ns}_item"] = "track:emby:3"
            memory.values["subtitle_identity_${ns}_item"] = oldDigest(probed, "Subtitle")
            val audio = ManualTrackSelection("item", probed.id, TrackMemoryType.Audio, 1, "instance", 7,
                trackMemoryIdentity(probed, "Audio"), probed)
            val subtitle = ManualTrackSelection("item", probed.id, TrackMemoryType.Subtitle, 3, "instance", 7,
                trackMemoryIdentity(probed, "Subtitle"), probed)
            val prepared = client.preparePlaybackTracks("item", probed.id, 1, manualAudio = audio, manualSubtitle = subtitle)
            check(prepared.intent.audioIndex == 2 && prepared.intent.subtitleIndex == 4)
            val second = client.preparePlaybackTracks("item", probed.id, 1)
            check(prepared.intent.preparationNonce != second.intent.preparationNonce)
            val request = JSONObject(); prepared.intent.applyToPlaybackInfo(request)
            check(request.getInt("AudioStreamIndex") == 2 && request.getInt("SubtitleStreamIndex") == 4)
            prefs.rememberSubtitleTrack("item", "off")
            check(client.preparePlaybackTracks("item", probed.id, null).intent.requestedSubtitleIndex == -1)
            prefs.rememberSubtitleTrack("item", "auto")
            check(client.preparePlaybackTracks("item", probed.id, null).intent.subtitleMode == SubtitleIntentMode.AUTO)
            versioned.set(false)
            val missing = client.preparePlaybackTracks("item", probed.id, 1, manualAudio = audio)
            check(missing.source.contentValidator == null && missing.source.contentVersion.isNotBlank())
            check(missing.intent.audioIndex == 2)
            check(runCatching { client.preparePlaybackTracks("item", "gone", 1) }.isFailure)
            check(requests.size >= 7 && requests.all { !it.getBoolean("IsPlayback") && !it.has("AudioStreamIndex") && !it.has("SubtitleStreamIndex") })
        } finally {
            client.close(); server.stop(); com.lumina.tv.AppRuntime.prefs = previous
        }
        println("T10 prepare HTTP PASS: real opaque/null parser evidence, matching-v1 rejection, language/default fallback, both unverified manual probes, OFF/AUTO and fresh nonces")
    }
}
