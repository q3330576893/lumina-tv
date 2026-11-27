package com.lumina.tv.data

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** V6-T04: real cache wire format, playback admission and existing disk fencing. */
object T04Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        roundTrips()
        presenceAndTypes()
        homeSchemasAndColdIdentity()
        playbackAdmission()
        withTimeout(15_000L) {
            lookupFences()
            existingItemEndpoint()
        }
        diskFencesAndRetention()
        productionWiring()
        println("V6-T04 PASS: complete Movie/Series/Episode/Missing schema, nullable/presence/types, legacy/future/corrupt, cold identity, retry/cancel, account/revision fences, atomic retention")
    }

    private fun populated(id: String, type: String, missing: Boolean = false) = MediaItem(
        id, "标题 $id", type, imageTag = "poster", overview = "Overview\n第二行", year = 2026,
        rating = 8.75, runtimeTicks = Long.MAX_VALUE - 1, seriesId = "parent-series", seasonId = "season-2",
        indexNumber = 3, parentIndexNumber = 2, seriesName = "Series name", positionTicks = 12_345_678_901_234_567,
        played = true, playedPercentage = 42.125, primaryImageAspectRatio = 1.7777777777777777,
        backdropTag = "backdrop", backdropTags = linkedMapOf(0 to "first", 2 to "third"),
        parentBackdropItemId = "parent", parentBackdropTags = mapOf(1 to "parent-second"), logoTag = "logo",
        sourceCount = 2, communityRating = 9.125, genres = listOf("Drama", "剧情"), isFavorite = true,
        resolution = "4K", isHdr = true, isDolbyVision = true, audioFormat = "7.1",
        people = listOf(MediaPerson("person", "演员", "role", "Actor", "portrait"), MediaPerson(null, "Crew")),
        providerIds = linkedMapOf("tmdb" to "123", "ImDb" to "tt456"), productionLocations = listOf("Japan", "中国"),
        originalLanguage = "ja", lastPlayedDate = 1_790_000_123_456, isMissing = missing, contentVersion = "etag:content-v2",
    )

    private fun decoded(json: JSONObject) = checkNotNull(CacheMediaItemCodec.decode(JSONObject(json.toString())))
    private fun home(item: MediaItem) = LibraryCache.HomeData(
        resume = listOf(item), latest = listOf(item), movies = listOf(item), series = listOf(item),
        favorites = listOf(item), genres = listOf("Drama", "剧情"),
        views = listOf(LibraryView("library", "Library", "movies", "tag"), LibraryView("tv", "TV", "tvshows")),
        at = System.currentTimeMillis(),
    )
    private fun legacy(id: String = "legacy", type: String = "Movie", missing: Boolean? = null): MediaItem {
        val json = JSONObject().put("id", id).put("name", "Legacy").put("type", type)
        if (missing != null) json.put("isMissing", missing)
        return decoded(json).item
    }

    private fun roundTrips() {
        val constructorFields = MediaItem::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.endsWith("\$delegate") }
            .map { it.name }.toSet()
        check(CacheMediaItemCodec.fieldNames == constructorFields) { "A MediaItem constructor field is missing from the disk schema" }
        val fixtures = listOf(
            populated("movie", "Movie"), populated("series", "Series"), populated("episode", "Episode"),
            populated("missing", "Episode", true), populated("missing-type", "Missing", true),
            MediaItem("empty", "", "Movie"),
            MediaItem("literal", "null", "Episode", imageTag = "", overview = "null", originalLanguage = "",
                providerIds = mapOf("" to "null"), backdropTags = mapOf(-1 to "", Int.MAX_VALUE to "null")),
        )
        for (item in fixtures) {
            val json = CacheMediaItemCodec.encode(item)
            check(json.getInt("schemaVersion") == CacheMediaItemCodec.SCHEMA_VERSION)
            check(CacheMediaItemCodec.fieldNames.all(json::has))
            val restored = decoded(json)
            check(restored.item == item) { "Incomplete round-trip for ${item.id}" }
            check(!restored.fields.requiresRefresh && !CacheMediaItemCodec.requiresRefresh(restored.item))
        }
        val emptyJson = CacheMediaItemCodec.encode(fixtures[5])
        for (key in listOf("imageTag", "year", "rating", "runtimeTicks", "originalLanguage", "lastPlayedDate")) {
            check(emptyJson.has(key) && emptyJson.isNull(key))
        }
        for (key in listOf("genres", "productionLocations", "people")) check(emptyJson.get(key) is JSONArray)
        for (key in listOf("providerIds", "backdropTags", "parentBackdropTags")) check(emptyJson.get(key) is JSONObject)
        println("T04 schema PASS: every constructor field, explicit nulls, empty/nonempty typed collections and exact Long values")
    }

    private fun presenceAndTypes() {
        val item = populated("presence", "Movie")
        for (field in CacheMediaItemCodec.fieldNames) {
            val json = CacheMediaItemCodec.encode(item).also { it.remove(field) }
            val envelope = CacheMediaItemCodec.decode(json)
            if (field == "id") check(envelope == null) else {
                checkNotNull(envelope)
                check(field in envelope.fields.missing && envelope.fields.requiresRefresh)
                check(!CacheMediaItemCodec.encode(envelope.item).has(field)) // A later partial home refresh cannot launder defaults.
            }
        }
        val absent = decoded(CacheMediaItemCodec.encode(MediaItem("equal", "Equal", "Movie")).also { it.remove("lastPlayedDate") })
        val explicitNull = decoded(CacheMediaItemCodec.encode(MediaItem("equal", "Equal", "Movie")))
        check(absent.item == explicitNull.item && absent.fields.requiresRefresh && !explicitNull.fields.requiresRefresh)
        check(CacheMediaItemCodec.requiresRefresh(absent.item)) // Equal complete objects cannot promote an incomplete object.
        check(!CacheMediaItemCodec.requiresRefresh(explicitNull.item))

        val badValues = listOf(
            "isMissing" to "false", "isMissing" to JSONObject.NULL, "positionTicks" to "12",
            "positionTicks" to 1.5, "sourceCount" to Long.MAX_VALUE, "playedPercentage" to "42.0",
            "genres" to JSONObject(), "genres" to JSONArray().put(3), "people" to JSONObject(),
            "providerIds" to JSONArray(), "providerIds" to JSONObject().put("tmdb", 123),
            "backdropTags" to JSONObject().put("01", "tag"), "productionLocations" to JSONObject.NULL,
            "people" to JSONArray().put(JSONObject().put("name", "Missing nullable keys")),
        )
        for ((field, value) in badValues) {
            val invalid = decoded(CacheMediaItemCodec.encode(item).put(field, value))
            check(field in invalid.fields.invalid && invalid.fields.requiresRefresh) { "Coerced invalid $field" }
            check(!CacheMediaItemCodec.encode(invalid.item).has(field))
        }
        for (version in listOf(JSONObject.NULL, "1", 1.5, -1, 0, CacheMediaItemCodec.SCHEMA_VERSION + 1)) {
            check(decoded(CacheMediaItemCodec.encode(item).put("schemaVersion", version)).fields.requiresRefresh)
        }
        val future = decoded(CacheMediaItemCodec.encode(item).put("schemaVersion", 2))
        check(CacheMediaItemCodec.encode(future.item).getInt("schemaVersion") == 2)
        val noVersion = decoded(CacheMediaItemCodec.encode(item).also { it.remove("schemaVersion") })
        check(noVersion.fields.requiresRefresh && !CacheMediaItemCodec.encode(noVersion.item).has("schemaVersion"))
        check(!decoded(CacheMediaItemCodec.encode(item).put("futureExtra", JSONArray())).fields.requiresRefresh)
        println("T04 presence PASS: absent differs from null, strict types, nested people, future versions, no default promotion on rewrite")
    }

    private fun homeSchemasAndColdIdentity() {
        val item = populated("cold", "Episode", true)
        val original = home(item)
        val wire = LibraryCache.encodeHome(original)
        LibraryCache.clear()
        check(LibraryCache.home == null)
        val cold = checkNotNull(LibraryCache.decodeHome(wire))
        check(cold == original && cold.resume.single() !== item)
        check(cold.resume.single().isMissing && cold.resume.single().contentVersion == item.contentVersion)
        check(!CacheMediaItemCodec.requiresRefresh(cold.resume.single()))

        val oldJson = JSONObject(wire).also { it.remove("schemaVersion"); it.remove("genres") }
        for (key in listOf("resume", "latest", "movies", "series", "favorites")) {
            oldJson.put(key, JSONArray().put(JSONObject().put("id", "old-$key").put("name", "Old").put("type", "Movie")))
        }
        val old = checkNotNull(LibraryCache.decodeHome(oldJson.toString()))
        check(old.genres.isEmpty() && old.resume.single().name == "Old")
        check(CacheMediaItemCodec.requiresRefresh(old.resume.single()))
        val rewritten = checkNotNull(LibraryCache.decodeHome(LibraryCache.encodeHome(old)))
        check(CacheMediaItemCodec.requiresRefresh(rewritten.resume.single()))
        check(!JSONObject(LibraryCache.encodeHome(old)).getJSONArray("resume").getJSONObject(0).has("isMissing"))
        check(LibraryCache.decodeHome(JSONObject(wire).put("schemaVersion", 2).toString()) == null)
        check(LibraryCache.decodeHome(JSONObject(wire).put("schemaVersion", "1").toString()) == null)
        for (broken in listOf("{broken", "[]", "null", "", JSONObject(wire).put("savedAt", "oops").toString(),
            JSONObject(wire).put("movies", JSONObject()).toString())) check(LibraryCache.decodeHome(broken) == null)
        check(LibraryCache.decodeHome(JSONObject(wire).put("savedAt", 1L).toString())?.at == 0L) // Original TTL retained.
        check(LibraryCache.decodeHome(JSONObject(wire).put("newTopLevelField", "ignored").toString()) == original)

        // The previous reader's flat arrays/keys still work: new fields do not wrap or rename old ones.
        val previousReaderItems = JSONObject(wire).getJSONArray("movies")
        val previousReaderItem = previousReaderItems.getJSONObject(0)
        check(previousReaderItem.optString("id") == item.id && previousReaderItem.optString("type") == item.type)
        check(previousReaderItem.optLong("positionTicks") == item.positionTicks)
        check(previousReaderItem.optJSONArray("people")?.length() == item.people.size)
        println("T04 home PASS: cold disk identity, all home fields, old flat-reader compatibility, old/future/corrupt schemas and TTL")
    }

    private suspend fun playbackAdmission() {
        suspend fun resolve(item: MediaItem, load: suspend (String) -> MediaItem?) = resolveCachedPlayableItem(
            item, load, { true },
        ) { verified -> resolvePlayableItem(verified, { listOf(MediaItem("s1e1", "First", "Episode", sourceCount = 1)) }) {
            EpisodeSourceAvailability.HAS_SOURCE
        } }
        for (type in listOf("Movie", "Series", "Episode")) {
            for (missing in listOf(legacy("known-$type", type, true), decoded(CacheMediaItemCodec.encode(populated("full-$type", type, true))).item)) {
                check(resolve(missing) { error("Known Missing must not reread metadata") } == PlayableItemResult.Unavailable)
            }
            val old = legacy("old-$type", type)
            check(resolvePlayableItem(old, { error("Incomplete item must not query episodes") }) {
                error("Incomplete item must not query sources")
            } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { null } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { throw IOException("offline") } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { MediaItem("wrong-id", "Wrong", type) } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { legacy(it, type) } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { MediaItem(it, "Unknown") } == PlayableItemResult.RetryableFailure)
            check(resolve(old) { MediaItem(it, "Missing", type, isMissing = true) } == PlayableItemResult.Unavailable)
            var reads = 0
            val result = resolve(old) { reads++; MediaItem(it, "Fresh", type) }
            check(reads == 1 && result is PlayableItemResult.Ready)
            check(result.item.id == if (type == "Series") "s1e1" else old.id)
            check(CacheMediaItemCodec.requiresRefresh(old)) // Lookup does not promote a stale object in other UI state.
            val current = decoded(CacheMediaItemCodec.encode(MediaItem("current-$type", "Current", type))).item
            check(resolve(current) { error("Complete schema must not require item reread") } is PlayableItemResult.Ready)
        }
        try {
            resolve(legacy()) { throw CancellationException("cancel revalidation") }
            error("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        println("T04 admission PASS: known Missing rejects before reread; old data uses metadata, failure stays retryable, cancellation propagates")
    }

    private suspend fun lookupFences() = coroutineScope {
        for (change in listOf("account", "revision", "account-aba", "cancel")) {
            var account = "a"
            var revision = 1L
            val gate = PlayableItemRequestGate().apply { updateContext(account) }
            val ticket = checkNotNull(gate.begin())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<MediaItem?>()
            var resolutions = 0
            val pending = async {
                resolveCachedPlayableItem(legacy(), loadItem = { started.complete(Unit); release.await() },
                    isCurrent = { gate.accepts(ticket) && account == "a" && revision == 1L },
                    resolve = { resolutions++; PlayableItemResult.Ready(it) })
            }
            started.await()
            when (change) {
                "account" -> account = "b"
                "revision" -> revision++
                "account-aba" -> { gate.updateContext("b"); gate.updateContext("a") }
                else -> gate.cancel()
            }
            release.complete(MediaItem("legacy", "Fresh", "Movie"))
            check(pending.await() == PlayableItemResult.RetryableFailure && resolutions == 0)
        }
        // Also fence results/replay prompts when the revision changes during series resolution.
        for (replay in listOf(false, true)) {
            var current = true
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = async {
                resolveCachedPlayableItem(MediaItem("series", "Series", "Series"), { error("No reread") }, { current }) {
                    started.complete(Unit); release.await()
                    val episode = MediaItem("episode", "Episode", "Episode")
                    if (replay) PlayableItemResult.ReplayAvailable(episode, true) else PlayableItemResult.Ready(episode)
                }
            }
            started.await(); current = false; release.complete(Unit)
            check(pending.await() == PlayableItemResult.RetryableFailure)
        }
        println("T04 lookup fences PASS: delayed identity and episode/replay results rejected after account/revision/ABA/cancel changes")
    }

    private suspend fun existingItemEndpoint() {
        val requests = CopyOnWriteArrayList<String>()
        var fail = false
        var missing = false
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests += session.uri
                val status = if (fail) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK
                return newFixedLengthResponse(status, "application/json",
                    """{"Id":"network","Name":"Fresh","Type":"Movie","IsMissing":$missing,"MediaSources":[{"Id":"source"}]}""")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val client = EmbyClient("http://127.0.0.1:${server.listeningPort}/emby", userId = "fixture")
        try {
            suspend fun play(item: MediaItem) = resolveCachedPlayableItem(item, client::getItem, { true }, client::resolvePlayableItem)
            check(play(legacy("network")) is PlayableItemResult.Ready)
            check(requests.single() == "/emby/Users/fixture/Items/network")
            missing = true
            check(play(legacy("network")) == PlayableItemResult.Unavailable)
            val count = requests.size
            check(play(legacy("network", missing = true)) == PlayableItemResult.Unavailable && requests.size == count)
            fail = true
            check(play(legacy("network")) == PlayableItemResult.RetryableFailure)
        } finally { client.close(); server.stop() }
        println("T04 endpoint PASS: production Emby getItem/resolve, basePath preserved, no network for known Missing, HTTP failure retryable")
    }

    private fun diskFencesAndRetention() {
        val directory = Files.createTempDirectory("v6-t04-cache").toFile()
        val file = File(directory, "home.json")
        val lock = Any()
        var account: String? = "a"
        var revision = 1L
        var now = 100_000L
        val store = HomeCacheDiskStore(lock, 30_000L, { account }, { revision }, { now })
        fun snapshot(write: Boolean = false) = checkNotNull(store.snapshot(file, account, revision, write))
        val data = home(populated("disk", "Movie"))
        val wire = LibraryCache.encodeHome(data)
        try {
            check(store.write(snapshot(true)) { LibraryCache.encodeHome(data) })
            check(file.readText() == wire)
            check(store.read(snapshot(), LibraryCache::decodeHome) == data)
            check(store.snapshot(file, "other", revision) == null && store.snapshot(file, account, revision - 1) == null)
            for (change in listOf("account", "revision", "generation")) {
                val read = snapshot()
                val result = store.read(read) {
                    check(!Thread.holdsLock(lock))
                    val decoded = LibraryCache.decodeHome(it)
                    when (change) { "account" -> account = "b"; "revision" -> revision++; else -> store.invalidate() }
                    decoded
                }
                check(result == null && file.readText() == wire)
                account = "a"
            }
            for (change in listOf("account", "revision", "generation")) {
                now += 30_000L
                val write = snapshot(true)
                check(!store.write(write) {
                    val encoded = LibraryCache.encodeHome(data)
                    when (change) { "account" -> account = "b"; "revision" -> revision++; else -> store.invalidate() }
                    encoded
                })
                check(file.readText() == wire && directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
                account = "a"
            }
            for (unreadable in listOf("{broken", JSONObject(wire).put("schemaVersion", 2).toString())) {
                file.writeText(unreadable)
                check(store.read(snapshot(), LibraryCache::decodeHome) == null)
                check(file.exists() && file.readText() == unreadable) // No silent deletion on incompatible/corrupt reads.
            }
            now += 30_000L
            val beforeFailure = file.readText()
            check(!store.write(snapshot(true)) { throw IOException("encode failed") })
            check(file.readText() == beforeFailure)
            check(store.write(snapshot(true)) { wire }) // Failure does not consume the write interval.
            now += 30_000L
            val older = snapshot(true)
            val newer = snapshot(true)
            check(store.write(newer) { wire } && !store.write(older) { "obsolete" })
            check(file.readText() == wire)
        } finally { directory.deleteRecursively() }
        println("T04 disk PASS: real schema payloads, account/revision/generation/write-ID fences, retention and retry after encode failure")
    }

    private fun productionWiring() {
        val action = File("src/main/java/com/lumina/tv/ui/screens/PlayableItemAction.kt").readText()
        check("resolveCachedPlayableItem(item," in action && "loadItem = { client?.getItem(it) }" in action)
        check("isCurrent = { current(ticket) }" in action && "resolve = { client?.resolvePlayableItem(it)" in action)
        check("LibraryCache.userDataRevision == revision" in action && "媒体信息尚未核实，请重试" in action)
        val cache = File("src/main/java/com/lumina/tv/data/LibraryCache.kt").readText()
        check("homeDisk.read(snapshot, ::decodeHome)" in cache && "encodeHome(saved)" in cache)
        check("CacheMediaItemCodec.encode(this)" in cache && "let(CacheMediaItemCodec::decode)" in cache)
    }
}
